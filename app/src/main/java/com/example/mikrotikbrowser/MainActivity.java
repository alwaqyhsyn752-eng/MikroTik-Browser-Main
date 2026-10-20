package com.example.mikrotikbrowser;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.webkit.*;
import android.widget.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private WebView webView;
    private EditText urlBar;
    private ProgressBar progressBar;
    private SharedPreferences prefs;
    private String finderJs = "";
    private TextView bridgeLog;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("finder", MODE_PRIVATE);

        try {
            InputStream is = getAssets().open("finder.js");
            byte[] buf = new byte[is.available()];
            is.read(buf);
            is.close();
            finderJs = new String(buf, StandardCharsets.UTF_8);
        } catch (Exception e) {}

        webView = findViewById(R.id.webView);
        urlBar = findViewById(R.id.urlBar);
        progressBar = findViewById(R.id.progressBar);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setAllowFileAccess(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
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

        findViewById(R.id.btnGo).setOnClickListener(v -> {
            String u = urlBar.getText().toString().trim();
            if (u.isEmpty()) return;
            if (!u.startsWith("http")) u = "http://" + u;
            webView.loadUrl(u);
        });
        findViewById(R.id.btnBack).setOnClickListener(v -> { if (webView.canGoBack()) webView.goBack(); });
        findViewById(R.id.btnForward).setOnClickListener(v -> { if (webView.canGoForward()) webView.goForward(); });
        findViewById(R.id.btnRefresh).setOnClickListener(v -> webView.reload());
        findViewById(R.id.btnFinder).setOnClickListener(v -> openFinder());

        webView.loadUrl("http://connectivitycheck.gstatic.com/generate_204");
    }

    private void openFinder() {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_finder, null);
        EditText etPrefix = v.findViewById(R.id.etPrefix);
        EditText etSuffix = v.findViewById(R.id.etSuffix);
        EditText etDigits = v.findViewById(R.id.etDigits);
        EditText etAttempts = v.findViewById(R.id.etAttempts);
        EditText etPause = v.findViewById(R.id.etPause);
        EditText etRetry = v.findViewById(R.id.etRetry);
        EditText etStatusEvery = v.findViewById(R.id.etStatusEvery);
        EditText etRttEvery = v.findViewById(R.id.etRttEvery);
        TextView logText = v.findViewById(R.id.logText);

        etPrefix.setText(prefs.getString("prefix", ""));
        etSuffix.setText(prefs.getString("suffix", ""));
        etDigits.setText(prefs.getString("digits", "2"));
        etAttempts.setText(prefs.getString("attempts", "100"));
        etPause.setText(prefs.getString("pause", "30"));
        etRetry.setText(prefs.getString("retry", "1500"));
        etStatusEvery.setText(prefs.getString("statusEvery", "30"));
        etRttEvery.setText(prefs.getString("rttEvery", "8"));

        bridgeLog = logText;

        AlertDialog dlg = new AlertDialog.Builder(this)
            .setView(v)
            .setPositiveButton("▶ ابدأ", (d, w) -> {
                String url = webView.getUrl();
                if (url == null || !url.contains("/")) {
                    Toast.makeText(this, "افتح صفحة الدخول أولاً", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!url.contains("/login")) url = url.replaceAll("/+$", "") + "/login";
                String prefix = etPrefix.getText().toString().trim();
                if (prefix.isEmpty()) { Toast.makeText(this, "أدخل البادئة", Toast.LENGTH_SHORT).show(); return; }
                String suffix = etSuffix.getText().toString().trim();
                int digits = pi(etDigits, 2);
                int attempts = pi(etAttempts, 100);
                int pause = pi(etPause, 30);
                int retry = pi(etRetry, 1500);
                int statusEvery = pi(etStatusEvery, 30);
                int rttEvery = pi(etRttEvery, 8);

                prefs.edit()
                    .putString("prefix", prefix).putString("suffix", suffix)
                    .putString("digits", String.valueOf(digits))
                    .putString("attempts", String.valueOf(attempts))
                    .putString("pause", String.valueOf(pause))
                    .putString("retry", String.valueOf(retry))
                    .putString("statusEvery", String.valueOf(statusEvery))
                    .putString("rttEvery", String.valueOf(rttEvery))
                    .apply();

                String cfg = "window.FINDER_CONFIG={"
                    + "url:'" + url + "',"
                    + "prefix:'" + prefix + "',"
                    + "suffix:'" + suffix + "',"
                    + "digits:" + digits + ","
                    + "attempts:" + attempts + ","
                    + "pause:" + pause + ","
                    + "retryDelay:" + retry + ","
                    + "statusEvery:" + statusEvery + ","
                    + "rttEvery:" + rttEvery + "};";
                webView.evaluateJavascript(cfg, null);
                webView.evaluateJavascript(finderJs, null);
            })
            .setNeutralButton("⏹ إيقاف", (d, w) ->
                webView.evaluateJavascript("window.stopFinder && window.stopFinder();", null))
            .setNegativeButton("إغلاق", null)
            .create();

        dlg.setOnDismissListener(x -> bridgeLog = null);
        dlg.show();
    }

    private int pi(EditText e, int def) {
        try { return Integer.parseInt(e.getText().toString().trim()); }
        catch (Exception ex) { return def; }
    }

    public class Bridge {
        @JavascriptInterface public void log(String m) {
            runOnUiThread(() -> {
                if (bridgeLog != null) {
                    bridgeLog.append(m + "\n");
                    int sc = bridgeLog.getLayout().getLineTop(bridgeLog.getLineCount()) - bridgeLog.getHeight();
                    if (sc > 0) bridgeLog.scrollTo(0, sc);
                }
            });
        }
        @JavascriptInterface public void found(String v, String j) {
            runOnUiThread(() -> {
                Toast.makeText(MainActivity.this, "✅ " + v, Toast.LENGTH_LONG).show();
                webView.reload();
            });
        }
    }

    @Override public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
