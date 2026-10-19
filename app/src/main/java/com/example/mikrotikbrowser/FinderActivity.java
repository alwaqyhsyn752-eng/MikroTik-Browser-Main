package com.example.mikrotikbrowser;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.webkit.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class FinderActivity extends AppCompatActivity {
    private EditText etUrl, etPrefix, etSuffix, etDigits, etAttempts;
    private Button btnStart, btnStop;
    private TextView tvLog;
    private WebView hiddenWeb;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private String jsCode = "";
    private String fullLog = "";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_finder);
        etUrl = findViewById(R.id.etUrl);
        etPrefix = findViewById(R.id.etPrefix);
        etSuffix = findViewById(R.id.etSuffix);
        etDigits = findViewById(R.id.etDigits);
        etAttempts = findViewById(R.id.etAttempts);
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);
        tvLog = findViewById(R.id.tvLog);
        hiddenWeb = findViewById(R.id.hiddenWeb);
        tvLog.setMovementMethod(new ScrollingMovementMethod());

        String passedUrl = getIntent().getStringExtra("current_url");
etUrl.setText(passedUrl != null && !passedUrl.isEmpty() ? passedUrl : "http://t.net/login");
        etPrefix.setText("");
        etSuffix.setText("");
        etDigits.setText("");
        etAttempts.setText("");
        btnStop.setEnabled(false);

        try {
            InputStream is = getAssets().open("finder.js");
            byte[] buf = new byte[is.available()];
            is.read(buf);
            is.close();
            jsCode = new String(buf, StandardCharsets.UTF_8);
        } catch (Exception e) {
            appendLog("Error: " + e.getMessage());
        }

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(hiddenWeb, true);

        WebSettings s = hiddenWeb.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setAllowFileAccess(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        hiddenWeb.addJavascriptInterface(new Bridge(), "AndroidBridge");

        btnStart.setOnClickListener(v -> start());
        btnStop.setOnClickListener(v -> {
            hiddenWeb.evaluateJavascript("window.stopFinder && window.stopFinder();", null);
            appendLog("Stop requested");
        });
    }

    public class Bridge {
        @JavascriptInterface public void log(String msg) { appendLog(msg); }
        @JavascriptInterface public void progress(final int cur, final int total, final String v) {
            uiHandler.post(() -> tvLog.setText("[" + cur + "/" + total + "] " + v));
        }
        @JavascriptInterface public void found(String voucher, String statusJson) {
            appendLog("FOUND: " + voucher);
            appendLog(statusJson);
            uiHandler.post(() -> {
                btnStart.setEnabled(true);
                btnStop.setEnabled(false);
            });
        }
    }

    private void appendLog(String msg) {
        fullLog += msg + "\n";
        uiHandler.post(() -> {
            tvLog.setText(fullLog);
            tvLog.scrollTo(0, tvLog.getHeight());
        });
    }

    private void start() {
        String url = etUrl.getText().toString().trim();
        if (!url.startsWith("http")) url = "http://" + url;
        if (!url.endsWith("/login")) url = url.replaceAll("/+$", "") + "/login";

        int d = 2, a = 100;
        try { d = Integer.parseInt(etDigits.getText().toString()); } catch (Exception e) {}
        try { a = Integer.parseInt(etAttempts.getText().toString()); } catch (Exception e) {}
        final int digits = d;
        final int attempts = a;
        final String prefix = etPrefix.getText().toString().trim();
        final String suffix = etSuffix.getText().toString().trim();
        final String finalUrl = url;

        fullLog = "";
        btnStart.setEnabled(false);
        btnStop.setEnabled(true);

        hiddenWeb.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String u) {
                String cfg = "window.FINDER_CONFIG={url:'" + finalUrl
                    + "',prefix:'" + prefix + "',suffix:'" + suffix
                    + "',digits:" + digits + ",attempts:" + attempts + "};";
                view.evaluateJavascript(cfg, null);
                view.evaluateJavascript(jsCode, null);
            }
        });
        hiddenWeb.loadUrl(url);
    }
}
