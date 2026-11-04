package com.tokenmonitor.mobile;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Transports the original desktop IPC API without interpreting its results.
 * Requests are not replayed after a network failure: some IPC handlers mutate
 * settings. A subsequent renderer request reconnects automatically instead.
 */
public final class RpcConnection implements AutoCloseable {
    public interface Listener {
        void onReply(String id, JSONObject response);
        void onStatus(String text, boolean connected);
    }

    public static final int DEFAULT_PORT = 47823;
    public static final UUID SERVICE_UUID = UUID.fromString("7f8b8b62-1de4-4b4b-94ce-8ab1d65342a1");
    private static final int MAX_REQUEST = 2 * 1024 * 1024;
    private static final int MAX_RESPONSE = 16 * 1024 * 1024;
    private static final int CONNECT_TIMEOUT = 8000;
    private static final int READ_TIMEOUT = 20000;
    private static final int CALL_DEADLINE_SECONDS = 45;

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object configurationLock = new Object();
    private final ThreadPoolExecutor httpWorkers = pool(4, "token-rpc-http");
    private final ThreadPoolExecutor bluetoothWorker = pool(1, "token-rpc-bluetooth");
    private final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, factory("token-rpc-timeouts"));
    private volatile Session current;
    private volatile boolean closed;

    public RpcConnection(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        timer.setRemoveOnCancelPolicy(true);
    }

    private static ThreadFactory factory(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    private static ThreadPoolExecutor pool(int count, String name) {
        return new ThreadPoolExecutor(count, count, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64), factory(name), new ThreadPoolExecutor.AbortPolicy());
    }

    /** Accepts a bare hostname or IP. The bridge owns the /api/rpc route. */
    public void configureHttp(String host, int port, String key) {
        try {
            String clean = host == null ? "" : host.trim();
            String scheme = clean.startsWith("https://") ? "https" : "http";
            if (clean.startsWith("https://")) clean = clean.substring(8);
            else if (clean.startsWith("http://")) clean = clean.substring(7);
            if (clean.startsWith("[") && clean.endsWith("]")) clean = clean.substring(1, clean.length() - 1);
            if (clean.isEmpty() || clean.contains("/") || clean.contains("@") || clean.contains("?") ||
                    clean.contains("#") || port < 1 || port > 65535)
                throw new IllegalArgumentException("请输入电脑 IP 和有效端口");
            URL url = new URI(scheme, null, clean, port, "/api/rpc", null, null).toURL();
            configure(new Session(url, null, validateKey(key)));
        } catch (Exception error) {
            invalidConfiguration(error instanceof IllegalArgumentException ? error.getMessage() : "电脑地址格式不正确");
        }
    }

    public void configureBluetooth(String mac, String key) {
        try {
            String address = mac == null ? "" : mac.trim().toUpperCase(Locale.ROOT);
            if (!BluetoothAdapter.checkBluetoothAddress(address)) throw new IllegalArgumentException("请选择已配对的电脑");
            configure(new Session(null, address, validateKey(key)));
        } catch (IllegalArgumentException error) {
            invalidConfiguration(error.getMessage());
        }
    }

    private static String validateKey(String value) {
        String key = value == null ? "" : value.trim();
        if (key.isEmpty()) throw new IllegalArgumentException("请输入电脑端配对密钥");
        if (key.length() > 256 || key.contains("\r") || key.contains("\n"))
            throw new IllegalArgumentException("配对密钥格式不正确");
        return key;
    }

    private void configure(Session next) {
        Session previous;
        synchronized (configurationLock) {
            if (closed) return;
            previous = current;
            current = next;
        }
        cancel(previous, "连接方式已更改，请重试");
        status(next, "连接已配置 · 等待电脑数据", false);
    }

    private void invalidConfiguration(String message) {
        disconnect();
        main.post(() -> { if (!closed && current == null) listener.onStatus(message, false); });
    }

    /** argsJson is a JSON array corresponding to Electron invoke(...args).
     * IDs must be unique across reconfigurations/page loads until resolved.
     */
    public void call(String id, String channel, String argsJson) {
        if (closed) return;
        Session session = current;
        if (session == null) { reply(id, failure("尚未连接电脑，请先设置连接")); return; }
        if (id == null || id.length() > 512) { reply(id, failure("无效请求 ID")); return; }
        if (channel == null || channel.isEmpty() || channel.length() > 256) {
            reply(id, failure("无效 RPC 通道")); return;
        }
        byte[] bytes;
        try {
            String args = argsJson == null || argsJson.isEmpty() ? "[]" : argsJson;
            if (args.length() > MAX_REQUEST) throw new IllegalArgumentException("请求超过 2 MB 限制");
            JSONObject request = new JSONObject().put("channel", channel).put("args", new JSONArray(args));
            if (session.mac != null) request.put("type", "rpc");
            bytes = request.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_REQUEST) throw new IllegalArgumentException("请求超过 2 MB 限制");
        } catch (Exception error) {
            reply(id, failure(error instanceof IllegalArgumentException ? error.getMessage() : "RPC 参数必须是 JSON 数组"));
            return;
        }
        Pending pending = new Pending(id, session);
        if (session.pending.putIfAbsent(id, pending) != null) {
            // Preserve the original pending request; duplicate IDs cannot be
            // correlated safely and must not execute a second mutation.
            return;
        }
        synchronized (configurationLock) {
            if (closed || current != session || session.cancelled) {
                finish(pending, failure("连接已更改，请重试"));
                return;
            }
            try {
                pending.timeout = timer.schedule(() -> {
                    if (finish(pending, failure("电脑响应超时，请检查连接后重试"))) {
                        abort(pending);
                        status(session, "电脑响应超时 · 下次请求将重连", false);
                    }
                }, CALL_DEADLINE_SECONDS, TimeUnit.SECONDS);
                pending.task = (session.mac == null ? httpWorkers : bluetoothWorker).submit(() -> {
                    if (!active(pending)) return;
                    try {
                        JSONObject response = session.mac == null ? http(pending, bytes) : bluetooth(pending, bytes);
                        if (finish(pending, response)) status(session, "已连接电脑", true);
                    } catch (Exception error) {
                        if (pending.done.get()) return;
                        String message = error instanceof AuthenticationException ? "配对密钥不正确，请重新输入" :
                                error instanceof SecurityException ? "需要附近设备权限才能连接蓝牙" :
                                error instanceof ProtocolException ? error.getMessage() :
                                "连接中断，请检查电脑桥接服务后重试";
                        if (finish(pending, failure(message))) status(session, message, false);
                    }
                });
            } catch (RejectedExecutionException error) {
                finish(pending, failure("等待中的请求过多，请稍后重试"));
            }
        }
    }

    private boolean active(Pending pending) {
        return !closed && !pending.done.get() && !pending.session.cancelled && current == pending.session;
    }

    private JSONObject http(Pending pending, byte[] body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) pending.session.url.openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT);
            connection.setReadTimeout(READ_TIMEOUT);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("Authorization", "Bearer " + pending.session.key);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Connection", "close");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body.length);
            synchronized (pending) {
                if (!active(pending)) throw new IOException("Cancelled");
                pending.http = connection;
            }
            try (OutputStream output = connection.getOutputStream()) { output.write(body); }
            int code = connection.getResponseCode();
            if (code == 401 || code == 403) throw new AuthenticationException();
            if (code != 200) throw new ProtocolException("电脑服务返回 HTTP " + code);
            if (connection.getContentLengthLong() > MAX_RESPONSE) throw new ProtocolException("电脑返回的数据超过 16 MB 限制");
            try (InputStream input = connection.getInputStream()) {
                return parseReply(readBounded(input, false, MAX_RESPONSE));
            }
        } finally {
            synchronized (pending) { if (pending.http == connection) pending.http = null; }
            connection.disconnect();
        }
    }

    @SuppressWarnings("MissingPermission")
    private JSONObject bluetooth(Pending pending, byte[] body) throws Exception {
        Session session = pending.session;
        try {
            BluetoothSocket socket;
            synchronized (session) {
                if (!active(pending)) throw new IOException("Cancelled");
                session.bluetoothOwner = pending;
                socket = session.socket;
            }
            if (socket == null) {
                BluetoothManager manager = context.getSystemService(BluetoothManager.class);
                BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
                if (adapter == null) throw new ProtocolException("此设备不支持蓝牙");
                if (!adapter.isEnabled()) throw new ProtocolException("请在系统设置中打开蓝牙");
                socket = adapter.getRemoteDevice(session.mac).createRfcommSocketToServiceRecord(SERVICE_UUID);
                synchronized (session) {
                    if (!active(pending)) { closeSocket(socket); throw new IOException("Cancelled"); }
                    session.socket = socket;
                }
                final BluetoothSocket connecting = socket;
                ScheduledFuture<?> connectionTimeout = timer.schedule(() -> closeSocket(connecting), 15, TimeUnit.SECONDS);
                try { socket.connect(); } finally { connectionTimeout.cancel(false); }
                session.input = new BufferedInputStream(socket.getInputStream(), 65536);
                session.output = socket.getOutputStream();
                byte[] auth = new JSONObject().put("type", "auth").put("key", session.key).toString().getBytes(StandardCharsets.UTF_8);
                writeLine(session.output, auth);
                JSONObject ack = new JSONObject(readBounded(session.input, true, 65536));
                if (ack.has("error")) throw new AuthenticationException();
                if (!"authenticated".equals(ack.optString("type"))) throw new ProtocolException("电脑蓝牙服务协议不匹配");
            }
            if (!active(pending)) throw new IOException("Cancelled");
            writeLine(session.output, body);
            return parseReply(readBounded(session.input, true, MAX_RESPONSE));
        } catch (Exception error) {
            resetBluetooth(session);
            throw error;
        } finally {
            synchronized (session) { if (session.bluetoothOwner == pending) session.bluetoothOwner = null; }
        }
    }

    private static void writeLine(OutputStream output, byte[] body) throws IOException {
        output.write(body);
        output.write('\n');
        output.flush();
    }

    private static JSONObject parseReply(String text) throws JSONException, ProtocolException {
        JSONObject value = new JSONObject(text);
        if (!(value.opt("ok") instanceof Boolean)) throw new ProtocolException("电脑服务返回了无效 RPC 响应");
        return value;
    }

    private static String readBounded(InputStream input, boolean line, int limit) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(16384);
        if (line) {
            while (true) {
                int next = input.read();
                if (next < 0) throw new EOFException("Bluetooth connection closed");
                if (next == '\n') break;
                if (buffer.size() >= limit) throw new ProtocolException("电脑返回的数据超过大小限制");
                buffer.write(next);
            }
        } else {
            byte[] chunk = new byte[16384];
            int count;
            while ((count = input.read(chunk)) >= 0) {
                if (buffer.size() + count > limit) throw new ProtocolException("电脑返回的数据超过大小限制");
                buffer.write(chunk, 0, count);
            }
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private static JSONObject failure(String error) {
        JSONObject value = new JSONObject();
        try { value.put("ok", false).put("error", error == null ? "连接失败" : error); }
        catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        return value;
    }

    private boolean finish(Pending pending, JSONObject response) {
        if (!pending.done.compareAndSet(false, true)) return false;
        pending.session.pending.remove(pending.id, pending);
        ScheduledFuture<?> timeout = pending.timeout;
        if (timeout != null) timeout.cancel(false);
        reply(pending.id, response);
        return true;
    }

    private void reply(String id, JSONObject response) {
        main.post(() -> { if (!closed) listener.onReply(id, response); });
    }

    private void status(Session session, String text, boolean connected) {
        main.post(() -> {
            if (!closed && current == session && !session.cancelled) listener.onStatus(text, connected);
        });
    }

    public void disconnect() {
        Session previous;
        synchronized (configurationLock) { previous = current; current = null; }
        cancel(previous, "连接已断开");
        main.post(() -> { if (!closed && current == null) listener.onStatus("未连接电脑", false); });
    }

    private void cancel(Session session, String reason) {
        if (session == null) return;
        session.cancelled = true;
        for (Pending pending : new ArrayList<>(session.pending.values())) {
            finish(pending, failure(reason));
            abort(pending);
        }
        resetBluetooth(session);
        httpWorkers.purge();
        bluetoothWorker.purge();
    }

    private void abort(Pending pending) {
        Future<?> task = pending.task;
        if (task != null) task.cancel(true);
        HttpURLConnection connection;
        synchronized (pending) { connection = pending.http; pending.http = null; }
        if (connection != null) connection.disconnect();
        synchronized (pending.session) {
            if (pending.session.bluetoothOwner == pending) resetBluetooth(pending.session);
        }
    }

    private static void resetBluetooth(Session session) {
        BluetoothSocket socket;
        synchronized (session) {
            socket = session.socket;
            session.socket = null;
            session.input = null;
            session.output = null;
        }
        closeSocket(socket);
    }

    private static void closeSocket(BluetoothSocket socket) {
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
    }

    @Override public void close() {
        synchronized (configurationLock) { if (closed) return; closed = true; }
        disconnect();
        httpWorkers.shutdownNow();
        bluetoothWorker.shutdownNow();
        timer.shutdownNow();
        main.removeCallbacksAndMessages(null);
    }

    private static final class Session {
        final URL url;
        final String mac;
        final String key;
        final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();
        volatile boolean cancelled;
        BluetoothSocket socket;
        InputStream input;
        OutputStream output;
        Pending bluetoothOwner;
        Session(URL url, String mac, String key) { this.url = url; this.mac = mac; this.key = key; }
    }

    private static final class Pending {
        final String id;
        final Session session;
        final AtomicBoolean done = new AtomicBoolean();
        volatile Future<?> task;
        volatile ScheduledFuture<?> timeout;
        HttpURLConnection http;
        Pending(String id, Session session) { this.id = id; this.session = session; }
    }

    private static final class AuthenticationException extends IOException { }
    private static final class ProtocolException extends IOException {
        ProtocolException(String message) { super(message); }
    }
}
