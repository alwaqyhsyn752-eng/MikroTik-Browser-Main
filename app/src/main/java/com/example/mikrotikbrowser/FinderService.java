package com.example.mikrotikbrowser;

import android.app.*;
import android.content.Context;
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
    
    private WebView hiddenWeb;
    private String finderJs = "";
    private String url = "";
    private String configJson = "";
    private static final String CHANNEL_ID = "finder_channel";

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        try {
            InputStream is = getAssets().open("finder.js");
            byte[] buf = new byte[is.available()];
            is.read(buf); is.close();
            finderJs = new String(buf, StandardCharsets.UTF_8);
        } catch (Exception e) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        if (ACTION_STOP.equals(intent.getAction())) {
            stopForeground(true);
            stopSelf();
            if (hiddenWeb != null) { hiddenWeb.destroy(); hiddenWeb = null; }
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(intent.getAction())) {
            url = intent.getStringExtra("url");
            configJson = intent.getStringExtra("config");

            // إشعار إجباري لكي يعمل التطبيق في الخلفية
            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("Network Browser")
                    .setContentText("جاري التخمين في الخلفية...")
                    .setSmallIcon(android.R.drawable.ic_menu_search)
                    .setOngoing(true)
                    .build();
            startForeground(1, notification);

            // إنشاء متصفح مخفي
            if (hiddenWeb == null) {
                hiddenWeb = new WebView(this);
                WebSettings s = hiddenWeb.getSettings();
                s.setJavaScriptEnabled(true);
                s.setDomStorageEnabled(true);
                s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
                CookieManager.getInstance().setAcceptThirdPartyCookies(hiddenWeb, true);
                hiddenWeb.addJavascriptInterface(new Object() {
                    @android.webkit.JavascriptInterface
                    public void log(String m) {}
                    @android.webkit.JavascriptInterface
                    public void found(String v) {
                        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                        Notification notif = new NotificationCompat.Builder(FinderService.this, CHANNEL_ID)
                                .setContentTitle("✅ تم إيجاد كرت!")
                                .setContentText(v)
                                .setSmallIcon(android.R.drawable.ic_dialog_info)
                                .build();
                        nm.notify(2, notif);
                    }
                }, "AndroidBridge0");

                hiddenWeb.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String u) {
                        view.evaluateJavascript("window.FINDER_CONFIG=" + configJson + ";", null);
                        view.evaluateJavascript(finderJs, null);
                    }
                });
            }
            hiddenWeb.loadUrl(url);
            return START_STICKY;
        }
        return START_NOT_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID, "Finder Service Channel", NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(serviceChannel);
        }
    }

    @Override
    public void onDestroy() {
        if (hiddenWeb != null) { hiddenWeb.destroy(); hiddenWeb = null; }
        super.onDestroy();
    }
}
