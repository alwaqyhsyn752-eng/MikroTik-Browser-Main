package com.example.mikrotikbrowser;

import android.app.*;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.webkit.*;
import androidx.core.app.NotificationCompat;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class FinderService extends Service {
    public static final String ACTION_START = "START";
    public static final String ACTION_STOP = "STOP";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_PREFIX = "prefix";
    public static final String EXTRA_SUFFIX = "suffix";
    public static final String EXTRA_DIGITS = "digits";
    public static final String EXTRA_ATTEMPTS = "attempts";
    public static final String EXTRA_NETWORK = "network";
    public static final String EXTRA_PAUSE = "pause";
    public static final String EXTRA_RETRY = "retry";
    public static final String EXTRA_STATUS_EVERY = "statusEvery";
    public static final String EXTRA_RTT_EVERY = "rttEvery";
    private static final String CHANNEL = "finder_ch";

    private WebView webView;
    private String network;
    private String jsCode = "";

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        FinderState.init(this);
        try {
            InputStream is = getAssets().open("finder.js");
            byte[] buf = new byte[is.available()];
            is.read(buf);
            is.close();
            jsCode = new String(buf, StandardCharsets.UTF_8);
        } catch (Exception e) {
            FinderState.log("خطأ تحميل JS: " + e.getMessage());
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL, "Voucher Finder", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotif(String text) {
        return new NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("التخمين يعمل")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            FinderState.log("الخدمة أُعيد تشغيلها بدون أوامر — إيقاف");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(intent.getAction())) {
            FinderState.running = false;
            if (webView != null) {
                webView.evaluateJavascript("window.stopFinder && window.stopFinder();", null);
            }
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction())) return START_NOT_STICKY;

        startForeground(1, buildNotif("جاري التحضير..."));

        final String url = intent.getStringExtra(EXTRA_URL);
        final String prefix = intent.getStringExtra(EXTRA_PREFIX);
        final String suffix = intent.getStringExtra(EXTRA_SUFFIX);
        final int digits = intent.getIntExtra(EXTRA_DIGITS, 2);
        final int attempts = intent.getIntExtra(EXTRA_ATTEMPTS, 100);
        final int pause = intent.getIntExtra(EXTRA_PAUSE, 30);
        final int retry = intent.getIntExtra(EXTRA_RETRY, 1500);
        final int statusEvery = intent.getIntExtra(EXTRA_STATUS_EVERY, 30);
        final int rttEvery = intent.getIntExtra(EXTRA_RTT_EVERY, 8);
        network = intent.getStringExtra(EXTRA_NETWORK);
        if (network == null) network = "default";

        FinderState.running = true;
        FinderState.fullLog.setLength(0);

        if (webView != null) { try { webView.destroy(); } catch (Exception e) {} }

        webView = new WebView(getApplicationContext());
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setAllowFileAccess(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        webView.addJavascriptInterface(new Bridge(), "AndroidBridge");

        final StringBuilder triedJson = new StringBuilder("[");
        boolean first = true;
        for (String t : FinderState.getTried(network)) {
            if (t.endsWith("|ok") || t.endsWith("|bad")) {
                if (!first) triedJson.append(",");
                triedJson.append("\"").append(t.split("\\|")[0]).append("\"");
                first = false;
            }
        }
        triedJson.append("]");

        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String u) {
                String cfg = "window.FINDER_CONFIG={"
                    + "url:'" + url + "',"
                    + "prefix:'" + prefix + "',"
                    + "suffix:'" + suffix + "',"
                    + "digits:" + digits + ","
                    + "attempts:" + attempts + ","
                    + "network:'" + network + "',"
                    + "tried:" + triedJson + ","
                    + "pause:" + pause + ","
                    + "retryDelay:" + retry + ","
                    + "statusEvery:" + statusEvery + ","
                    + "rttEvery:" + rttEvery + "};";
                view.evaluateJavascript(cfg, null);
                view.evaluateJavascript(jsCode, null);
            }
        });
        webView.loadUrl(url);
        return START_STICKY;
    }

    public class Bridge {
        @JavascriptInterface public void log(String m) { FinderState.log(m); notif(m); }
        @JavascriptInterface public void progress(int c, int t, String v) {
            FinderState.progress(c, t, v);
            notif("[" + c + "/" + t + "] " + v);
        }
        @JavascriptInterface public void found(String v, String j) {
            FinderState.markOk(network, v);
            FinderState.found(v, j);
            notif("✅ وجد: " + v);
            stopForeground(false);
        }
        @JavascriptInterface public void markBad(String v) { FinderState.markBad(network, v); }
        @JavascriptInterface public void markErr(String v) { FinderState.markErr(network, v); }
    }

    private void notif(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(1, buildNotif(text));
        } catch (Exception e) {}
    }

    @Override public void onDestroy() {
        if (webView != null) { try { webView.destroy(); } catch (Exception e) {} webView = null; }
        FinderState.running = false;
        super.onDestroy();
    }
}
