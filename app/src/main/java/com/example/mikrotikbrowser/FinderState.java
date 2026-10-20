package com.example.mikrotikbrowser;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashSet;
import java.util.Set;

public class FinderState {
    public interface Listener {
        void onLog(String msg);
        void onProgress(int cur, int total, String voucher);
        void onFound(String voucher, String statusJson);
    }
    public static volatile Listener listener;
    public static volatile boolean running = false;
    public static volatile int current = 0, total = 0;
    public static volatile String lastVoucher = "";
    public static final StringBuilder fullLog = new StringBuilder();
    private static SharedPreferences prefs;

    public static synchronized void init(Context ctx) {
        if (prefs == null) prefs = ctx.getApplicationContext()
            .getSharedPreferences("voucher_store", Context.MODE_PRIVATE);
    }

    public static void log(String msg) {
        fullLog.append(msg).append('\n');
        if (fullLog.length() > 50000) fullLog.delete(0, fullLog.length() - 40000);
        if (listener != null) {
            try { listener.onLog(msg); } catch (Exception e) {}
        }
    }

    public static void progress(int c, int t, String v) {
        current = c; total = t; lastVoucher = v;
        if (listener != null) {
            try { listener.onProgress(c, t, v); } catch (Exception e) {}
        }
    }

    public static void found(String v, String json) {
        running = false;
        if (listener != null) {
            try { listener.onFound(v, json); } catch (Exception e) {}
        }
    }

    private static String key(String net) { return "tried_" + net; }

    public static Set<String> getTried(String net) {
        if (prefs == null) return new HashSet<>();
        String raw = prefs.getString(key(net), "");
        Set<String> out = new HashSet<>();
        if (!raw.isEmpty()) for (String line : raw.split("\n")) if (!line.isEmpty()) out.add(line);
        return out;
    }

    public static synchronized void markOk(String net, String v) { append(net, v + "|ok"); }
    public static synchronized void markBad(String net, String v) { append(net, v + "|bad"); }
    public static synchronized void markErr(String net, String v) { append(net, v + "|err"); }

    private static void append(String net, String entry) {
        if (prefs == null) return;
        String k = key(net);
        String raw = prefs.getString(k, "");
        if (raw.contains("\n" + entry) || raw.startsWith(entry + "\n") || raw.equals(entry)) return;
        prefs.edit().putString(k, raw.isEmpty() ? entry : raw + "\n" + entry).apply();
    }
}
