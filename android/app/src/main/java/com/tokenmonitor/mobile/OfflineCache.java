package com.tokenmonitor.mobile;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Last successful display snapshot, private to the app and excluded from backup. */
final class OfflineCache {
    private static final int LIMIT = 12 * 1024 * 1024;
    private final AtomicFile file;
    OfflineCache(Context context) { file = new AtomicFile(new File(context.getFilesDir(), "offline-display-v1.json")); }
    private boolean valid(String text) {
        try {
            if (text == null || text.length() > LIMIT) return false;
            JSONObject value = new JSONObject(text);
            JSONObject rpc = value.getJSONObject("rpc");
            return value.optInt("version") == 2 && value.optLong("updatedAt") > 0
                && rpc.getJSONObject("stats:get").optJSONObject("periods") != null
                && rpc.optJSONObject("settings:get") != null;
        } catch (Exception error) { return false; }
    }
    synchronized String read() {
        try {
            if (file.getBaseFile().length() > LIMIT) return "";
            String text = new String(file.readFully(), StandardCharsets.UTF_8);
            return valid(text) ? text : "";
        } catch (Exception error) { return ""; }
    }
    synchronized boolean save(String text) {
        if (!valid(text)) return false;
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > LIMIT) return false;
        FileOutputStream stream = null;
        try {
            stream = file.startWrite(); stream.write(bytes); file.finishWrite(stream); return true;
        } catch (Exception error) { if (stream != null) file.failWrite(stream); return false; }
    }
    synchronized void clear() { file.delete(); }
}
