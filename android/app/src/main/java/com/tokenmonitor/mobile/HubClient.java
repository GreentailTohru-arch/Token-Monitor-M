package com.tokenmonitor.mobile;

import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** Android transport for the upstream Hub HTTP and SSE protocol. */
final class HubClient implements AutoCloseable {
    interface Listener {
        void onHubReply(String id, JSONObject response);
        void onHubEvent(JSONObject event);
        void onHubStatus(boolean connected, String reason);
    }
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService requests = Executors.newFixedThreadPool(3);
    private final ExecutorService streams = Executors.newCachedThreadPool();
    private volatile Session current;
    private volatile boolean closed;
    private static final int LIMIT = 16 * 1024 * 1024;
    HubClient(Listener listener) { this.listener = listener; }
    static String validateUrl(String text) throws Exception {
        String clean = text.trim().replaceAll("/+$", "");
        URI uri = new URI(clean);
        if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null
            || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
            || uri.getPort() > 65535 || uri.getPort() == 0) throw new IllegalArgumentException("请输入完整 Hub URL，例如 http://192.168.1.2:17321");
        return clean;
    }
    synchronized void configure(String url, String secret) {
        if (closed) return;
        if (current != null && current.url.equals(url) && current.secret.equals(secret)) return;
        disconnect();
        if (url.isEmpty()) return;
        Session session = new Session(url, secret); current = session;
        streams.execute(() -> stream(session));
    }
    synchronized void disconnect() {
        Session previous = current; current = null;
        if (previous != null) for (HttpURLConnection socket : previous.sockets) socket.disconnect();
        main.post(() -> { if (!closed && current == null) listener.onHubStatus(false, "disconnected"); });
    }
    boolean active(Session session) { return !closed && session == current; }
    HttpURLConnection open(Session session, String path, String method) throws Exception {
        HttpURLConnection socket = (HttpURLConnection) new URL(session.url + path).openConnection();
        socket.setRequestMethod(method); socket.setConnectTimeout(8000); socket.setReadTimeout(20000);
        socket.setInstanceFollowRedirects(false); socket.setUseCaches(false);
        if (!session.secret.isEmpty()) socket.setRequestProperty("Authorization", "Bearer " + session.secret);
        socket.setRequestProperty("Accept", "application/json");
        session.sockets.add(socket);
        if (!active(session)) { socket.disconnect(); session.sockets.remove(socket); throw new IOException("连接已更改"); }
        return socket;
    }
    void request(String id, String method, String path, String body) {
        Session session = current;
        boolean allowed = "GET".equals(method) && (path.equals("/api/stats") || path.equals("/api/history")
            || path.equals("/api/health") || path.equals("/api/subscriptions") || path.equals("/api/devices"));
        allowed |= "PUT".equals(method) && path.equals("/api/subscriptions");
        allowed |= "DELETE".equals(method) && path.matches("/api/devices/[A-Za-z0-9%._~-]+");
        if (!allowed || body == null || body.length() > 1024 * 1024) { reply(id, error("不支持的 Hub 请求")); return; }
        if (session == null) { reply(id, error("当前离线，请在设置中连接 Hub")); return; }
        requests.execute(() -> {
            HttpURLConnection socket = null;
            try {
                socket = open(session, path, method);
                if (method.equals("PUT")) {
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    socket.setRequestProperty("Content-Type", "application/json"); socket.setDoOutput(true);
                    socket.setFixedLengthStreamingMode(bytes.length);
                    try (OutputStream output = socket.getOutputStream()) { output.write(bytes); }
                }
                int code = socket.getResponseCode();
                InputStream input = code >= 400 ? socket.getErrorStream() : socket.getInputStream();
                String text = read(input);
                if (!active(session)) throw new IOException("Hub 已更改，请重试");
                if (code >= 400) {
                    JSONObject failure = error(code == 401 || code == 403 ? "Hub 共享密钥不正确" : "Hub 返回 HTTP " + code);
                    failure.put("status", code); reply(id, failure);
                } else reply(id, new JSONObject().put("ok", true).put("result", new JSONObject(text)));
            } catch (Exception error) { reply(id, error(active(session) ? "无法连接 Hub，请检查地址、网络和电脑服务" : "Hub 已更改，请重试")); }
            finally { if (socket != null) { session.sockets.remove(socket); socket.disconnect(); } }
        });
    }
    private static String read(InputStream input) throws IOException {
        if (input == null) return "{}";
        try (InputStream source = input; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16384]; int count;
            while ((count = source.read(buffer)) != -1) { if (bytes.size()+count > LIMIT) throw new IOException("Hub 响应过大"); bytes.write(buffer,0,count); }
            return bytes.toString("UTF-8");
        }
    }
    private void stream(Session session) {
        while (active(session)) {
            HttpURLConnection socket = null;
            try {
                socket = open(session, "/api/stats/stream", "GET");
                socket.setReadTimeout(45000); socket.setRequestProperty("Accept", "text/event-stream");
                // No delta header: upstream sends complete, backward-compatible snapshots.
                int code = socket.getResponseCode();
                if (code != 200) { status(session,false,code==401||code==403?"unauthorized":"server_error"); throw new IOException("HTTP " + code); }
                if (!String.valueOf(socket.getContentType()).contains("text/event-stream")) throw new IOException("Invalid stream");
                try (BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8))) {
                    StringBuilder data = new StringBuilder(); String line; boolean received = false;
                    while (active(session) && (line = input.readLine()) != null) {
                        if (line.isEmpty()) {
                            if (data.length() > 0) {
                                JSONObject event = new JSONObject(data.toString());
                                if (event.optJSONObject("stats") != null) {
                                    if (!received) { status(session,true,""); received = true; }
                                    main.post(() -> { if (active(session)) listener.onHubEvent(event); });
                                }
                            }
                            data.setLength(0);
                        } else if (line.startsWith("data:")) {
                            if (data.length()+line.length() > LIMIT) throw new IOException("Stream event too large");
                            data.append(line.substring(5).trim()).append('\n');
                        }
                    }
                }
                status(session,false,"disconnected");
            } catch (Exception error) { if (socket == null || !isAuthFailure(socket)) status(session,false,"network"); }
            finally { if (socket != null) { session.sockets.remove(socket); socket.disconnect(); } }
            try { Thread.sleep(3000); } catch (InterruptedException stop) { return; }
        }
    }
    private boolean isAuthFailure(HttpURLConnection socket) { try { int code=socket.getResponseCode(); return code==401||code==403; } catch(Exception error) { return false; } }
    private void status(Session session,boolean connected,String reason) { main.post(() -> { if(active(session))listener.onHubStatus(connected,reason); }); }
    private void reply(String id,JSONObject response) { main.post(() -> { if(!closed)listener.onHubReply(id,response); }); }
    private static JSONObject error(String message) { JSONObject value=new JSONObject(); try { value.put("ok",false).put("error",message); } catch(Exception ignored) {} return value; }
    @Override public void close() { disconnect(); closed=true; requests.shutdownNow(); streams.shutdownNow(); main.removeCallbacksAndMessages(null); }
    private static final class Session {
        final String url,secret;
        final java.util.Set<HttpURLConnection> sockets=ConcurrentHashMap.newKeySet();
        Session(String url,String secret){this.url=url;this.secret=secret;}
    }
}
