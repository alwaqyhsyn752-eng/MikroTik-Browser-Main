package com.example.mikrotikbrowser;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.webkit.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends AppCompatActivity {
    private WebView webView, hiddenWeb;
    private EditText urlBar;
    private ProgressBar progressBar;
    private LinearLayout panelExpanded;
    private TextView statusText, logText;
    private Button btnTogglePanel, btnStart, btnStop;
    private EditText etPrefix, etSuffix, etDigits, etAttempts;
    private EditText etPause, etRetry, etStatusEvery, etRttEvery;
    private SharedPreferences prefs;
    private String jsCode = "";
    private boolean running = false;

    private String currentFinderUrl, currentFinderPrefix, currentFinderSuffix, currentFinderNetwork;
    private int currentFinderDigits, currentFinderAttempts, currentFinderPause, currentFinderRetry, currentFinderStatus, currentFinderRtt;

    @SuppressLint("SetJavaScriptEnabled")
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("finder_prefs", MODE_PRIVATE);

        webView = findViewById(R.id.webView);
        urlBar = findViewById(R.id.urlBar);
        progressBar = findViewById(R.id.progressBar);
        panelExpanded = findViewById(R.id.panelExpanded);
        statusText = findViewById(R.id.statusText);
        logText = findViewById(R.id.logText);
        btnTogglePanel = findViewById(R.id.btnTogglePanel);
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);
        etPrefix = findViewById(R.id.etPrefix);
        etSuffix = findViewById(R.id.etSuffix);
        etDigits = findViewById(R.id.etDigits);
        etAttempts = findViewById(R.id.etAttempts);
        etPause = findViewById(R.id.etPause);
        etRetry = findViewById(R.id.etRetry);
        etStatusEvery = findViewById(R.id.etStatusEvery);
        etRttEvery = findViewById(R.id.etRttEvery);

        // استرجاع الإعدادات المحفوظة
        etPrefix.setText(prefs.getString("prefix", ""));
        etSuffix.setText(prefs.getString("suffix", ""));
        etDigits.setText(prefs.getString("digits", "2"));
        etAttempts.setText(prefs.getString("attempts", "100"));
        etPause.setText(prefs.getString("pause", "30"));
        etRetry.setText(prefs.getString("retry", "1500"));
        etStatusEvery.setText(prefs.getString("statusEvery", "30"));
        etRttEvery.setText(prefs.getString("rttEvery", "8"));

        // تحميل السكربت
        try {
            InputStream is = getAssets().open("finder.js");
            byte[] buf = new byte[is.available()];
            is.read(buf);
            is.close();
            jsCode = new String(buf, StandardCharsets.UTF_8);
        } catch (Exception e) {
            logText.setText("Error loading JS: " + e.getMessage());
        }

        // إعداد المتصفح الرئيسي
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        webView.setBackgroundColor(0xFF000000);

        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String u) {
                urlBar.setText(u);
                progressBar.setVisibility(View.GONE);
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView v, int p) {
                progressBar.setProgress(p);
                progressBar.setVisibility(p < 100 ? View.VISIBLE : View.GONE);
            }
        });

        // إعداد المتصفح المخفي (للتخمين بدون Service)
        hiddenWeb = new WebView(this);
        WebSettings hs = hiddenWeb.getSettings();
        hs.setJavaScriptEnabled(true);
        hs.setDomStorageEnabled(true);
        hs.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        hs.setAllowFileAccess(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(hiddenWeb, true);
        hiddenWeb.addJavascriptInterface(new Bridge(), "AndroidBridge");

        hiddenWeb.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String u) {
                String cfg = "window.FINDER_CONFIG={"
                    + "url:'" + currentFinderUrl + "',"
                    + "prefix:'" + currentFinderPrefix + "',"
                    + "suffix:'" + currentFinderSuffix + "',"
                    + "digits:" + currentFinderDigits + ","
                    + "attempts:" + currentFinderAttempts + ","
                    + "network:'" + currentFinderNetwork + "',"
                    + "tried:[],"
                    + "pause:" + currentFinderPause + ","
                    + "retryDelay:" + currentFinderRetry + ","
                    + "statusEvery:" + currentFinderStatus + ","
                    + "rttEvery:" + currentFinderRtt + "};";
                view.evaluateJavascript(cfg, null);
                view.evaluateJavascript(jsCode, null);
            }
        });

        // ربط الأزرار
        findViewById(R.id.btnGo).setOnClickListener(v -> {
            String u = urlBar.getText().toString().trim();
            if (u.isEmpty()) return;
            if (!u.startsWith("http")) u = "http://" + u;
            webView.loadUrl(u);
        });
        findViewById(R.id.btnBack).setOnClickListener(v -> { if (webView.canGoBack()) webView.goBack(); });
        findViewById(R.id.btnForward).setOnClickListener(v -> { if (webView.canGoForward()) webView.goForward(); });
        findViewById(R.id.btnRefresh).setOnClickListener(v -> webView.reload());

        // زر فتح/إغلاق اللوحة
        btnTogglePanel.setOnClickListener(v -> {
            if (panelExpanded.getVisibility() == View.VISIBLE) {
                panelExpanded.setVisibility(View.GONE);
            } else {
                panelExpanded.setVisibility(View.VISIBLE);
            }
        });

        btnStart.setOnClickListener(v -> startFinder());
        btnStop.setOnClickListener(v -> stopFinder());

        webView.loadUrl("http://connectivitycheck.gstatic.com/generate_204");
    }

    private void startFinder() {
        String currentUrl = webView.getUrl();
        if (currentUrl == null || currentUrl.contains("connectivitycheck") || !currentUrl.contains("/")) {
            Toast.makeText(this, "افتح صفحة الدخول أولاً", Toast.LENGTH_SHORT).show();
            return;
        }
        currentFinderUrl = currentUrl;
        if (!currentFinderUrl.contains("/login")) currentFinderUrl = currentFinderUrl.replaceAll("/+$", "") + "/login";

        currentFinderPrefix = etPrefix.getText().toString().trim();
        if (currentFinderPrefix.isEmpty()) { Toast.makeText(this, "أدخل البادئة", Toast.LENGTH_SHORT).show(); return; }
        currentFinderSuffix = etSuffix.getText().toString().trim();

        try { currentFinderDigits = Integer.parseInt(etDigits.getText().toString()); } catch (Exception e) { currentFinderDigits = 2; }
        try { currentFinderAttempts = Integer.parseInt(etAttempts.getText().toString()); } catch (Exception e) { currentFinderAttempts = 100; }
        try { currentFinderPause = Integer.parseInt(etPause.getText().toString()); } catch (Exception e) { currentFinderPause = 30; }
        try { currentFinderRetry = Integer.parseInt(etRetry.getText().toString()); } catch (Exception e) { currentFinderRetry = 1500; }
        try { currentFinderStatus = Integer.parseInt(etStatusEvery.getText().toString()); } catch (Exception e) { currentFinderStatus = 30; }
        try { currentFinderRtt = Integer.parseInt(etRttEvery.getText().toString()); } catch (Exception e) { currentFinderRtt = 8; }

        prefs.edit()
            .putString("prefix", currentFinderPrefix)
            .putString("suffix", currentFinderSuffix)
            .putString("digits", String.valueOf(currentFinderDigits))
            .putString("attempts", String.valueOf(currentFinderAttempts))
            .putString("pause", String.valueOf(currentFinderPause))
            .putString("retry", String.valueOf(currentFinderRetry))
            .putString("statusEvery", String.valueOf(currentFinderStatus))
            .putString("rttEvery", String.valueOf(currentFinderRtt))
            .apply();

        try {
            currentFinderNetwork = android.net.Uri.parse(currentFinderUrl).getHost();
            if (currentFinderNetwork == null) currentFinderNetwork = "default";
        } catch (Exception e) { currentFinderNetwork = "default"; }

        logText.setText("");
        statusText.setText("جاري التخمين...");
        btnStart.setEnabled(false);
        btnStop.setEnabled(true);
        running = true;

        hiddenWeb.loadUrl(currentFinderUrl);
    }

    private void stopFinder() {
        if (!running) return;
        running = false;
        hiddenWeb.evaluateJavascript("window.stopFinder && window.stopFinder();", null);
        statusText.setText("متوقف");
        btnStart.setEnabled(true);
        btnStop.setEnabled(false);
    }

    public class Bridge {
        @JavascriptInterface public void log(String m) {
            runOnUiThread(() -> {
                logText.append(m + "\n");
                int scrollAmount = logText.getLayout().getLineTop(logText.getLineCount()) - logText.getHeight();
                if (scrollAmount > 0) logText.scrollTo(0, scrollAmount);
                else logText.scrollTo(0, 0);
            });
        }
        @JavascriptInterface public void progress(int c, int t, String v) {
            runOnUiThread(() -> statusText.setText("[" + c + "/" + t + "] " + v));
        }
        @JavascriptInterface public void found(String v, String j) {
            runOnUiThread(() -> {
                statusText.setText("✅ وجد: " + v);
                Toast.makeText(MainActivity.this, "وجد الكرت: " + v, Toast.LENGTH_LONG).show();
                stopFinder();
                if (webView.getUrl() != null) webView.reload();
            });
        }
        @JavascriptInterface public void markBad(String v) {}
        @JavascriptInterface public void markErr(String v) {}
    }

    @Override public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
