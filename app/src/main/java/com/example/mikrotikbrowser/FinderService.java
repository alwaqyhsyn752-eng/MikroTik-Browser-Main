package com.example.mikrotikbrowser;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
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
    
    // متغيرات قفل الإيقاظ
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

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

            // 🔒 تفعيل قفل الإيقاظ (WakeLock) لمنع الهاتف من النوم
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NetworkBrowser::WakeLock");
                wakeLock.acquire(24 * 60 * 60 * 1000L); // لمدة 24 ساعة كحد أقصى
            }

            // 🔒 تفعيل قفل الواي فاي (WifiLock)
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && wifiLock == null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "NetworkBrowser::WifiLock");
                wifiLock.acquire();
            }

            // إنشاء متصفح مخفي
            if (hiddenWeb == null) {
                hiddenWeb = new WebView(this);
                WebSettings s = hiddenWeb.getSettings();
                s.setJavaScriptEnabled(true);
                s.setDomStorageEnabled(true);
                s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
                CookieManager.getInstance().setAcceptThirdPartyCookies(hiddenWeb, true);
                
                hiddenWeb.addJavascriptInterface(new Object() {
                    @android.webkit.JavascriptInterface public void log(String m) {}
                    @android.webkit.JavascriptInterface public void progress(int c, int t, String v) {}
                    @android.webkit.JavascriptInterface public void found(String v, String st) {
                        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                        Notification notif = new NotificationCompat.Builder(FinderService.this, CHANNEL_ID)
                                .setContentTitle("✅ تم إيجاد كرت!")
                                .setContentText(v)
                                .setSmallIcon(android.R.drawable.ic_dialog_info)
                                .build();
                        nm.notify(2, notif);
                    }
                    @android.webkit.JavascriptInterface public void clearCookies() {}
                    @android.webkit.JavascriptInterface public void triggerGlobalBlock() {}
                    @android.webkit.JavascriptInterface public boolean isGloballyBlocked() { return false; }
                    @android.webkit.JavascriptInterface public boolean requestTurn(long ms) { return true; }
                }, "AndroidBridge0");

                hiddenWeb.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String u) {
                        view.evaluateJavascript("window.FINDER_CONFIG=" + configJson + ";", null);
                        view.evaluateJavascript(finderJs, null);
                    }
                });
            }
            
            // استئناف عمل الجافاسكربت في الخلفية
            hiddenWeb.onResume();
            hiddenWeb.resumeTimers();
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
        // تحرير أقفال الإيقاظ لتوفير البطارية عند التوقف
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        super.onDestroy();
    }
}
