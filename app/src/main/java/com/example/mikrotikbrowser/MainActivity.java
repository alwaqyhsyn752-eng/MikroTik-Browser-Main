package com.example.mikrotikbrowser;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity implements FinderState.Listener {
    private WebView webView;
    private EditText urlBar;
    private ProgressBar progressBar;
    private LinearLayout panelExpanded;
    private TextView statusText, logText;
    private Button btnTogglePanel, btnStart, btnStop;
    private EditText etPrefix, etSuffix, etDigits, etAttempts;
    private EditText etPause, etRetry, etStatusEvery, etRttEvery;
    private SharedPreferences prefs;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private static final String DETECT_URL = "http://connectivitycheck.gstatic.com/generate_204";

    @SuppressLint("SetJavaScriptEnabled")
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        FinderState.init(this);
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

        etPrefix.setText(prefs.getString("prefix", ""));
        etSuffix.setText(prefs.getString("suffix", ""));
        etDigits.setText(prefs.getString("digits", "2"));
        etAttempts.setText(prefs.getString("attempts", "100"));
        etPause.setText(prefs.getString("pause", "30"));
        etRetry.setText(prefs.getString("retry", "1500"));
        etStatusEvery.setText(prefs.getString("statusEvery", "30"));
        etRttEvery.setText(prefs.getString("rttEvery", "8"));

        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this,
                    android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 100);
            }
        }

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setAllowFileAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
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

        findViewById(R.id.btnGo).setOnClickListener(v -> {
            String u = urlBar.getText().toString().trim();
            if (u.isEmpty()) return;
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://" + u;
            webView.loadUrl(u);
        });
        findViewById(R.id.btnBack).setOnClickListener(v -> { if (webView.canGoBack()) webView.goBack(); });
        findViewById(R.id.btnForward).setOnClickListener(v -> { if (webView.canGoForward()) webView.goForward(); });
        findViewById(R.id.btnRefresh).setOnClickListener(v -> webView.reload());

        btnTogglePanel.setOnClickListener(v -> {
            panelExpanded.setVisibility(panelExpanded.getVisibility() == View.VISIBLE
                ? View.GONE : View.VISIBLE);
        });
        btnStart.setOnClickListener(v -> startFinder());
        btnStop.setOnClickListener(v -> stopFinder());

        FinderState.listener = this;
        if (FinderState.running) {
            btnStart.setEnabled(false);
            btnStop.setEnabled(true);
            logText.setText(FinderState.fullLog.toString());
            statusText.setText("[" + FinderState.current + "/" + FinderState.total + "] " + FinderState.lastVoucher);
        } else {
            btnStop.setEnabled(false);
        }

        webView.loadUrl(DETECT_URL);
    }

    private void startFinder() {
        String currentUrl = webView.getUrl();
        if (currentUrl == null || currentUrl.contains("connectivitycheck") || !currentUrl.contains("/")) {
            Toast.makeText(this, "افتح صفحة الدخول أولاً", Toast.LENGTH_SHORT).show();
            return;
        }
        String url = currentUrl;
        if (!url.contains("/login")) url = url.replaceAll("/+$", "") + "/login";

        String prefix = etPrefix.getText().toString().trim();
        if (prefix.isEmpty()) { Toast.makeText(this, "أدخل البادئة", Toast.LENGTH_SHORT).show(); return; }

        int digits, attempts, pause, retry, statusEvery, rttEvery;
        try { digits = Integer.parseInt(etDigits.getText().toString()); } catch (Exception e) { digits = 2; }
        try { attempts = Integer.parseInt(etAttempts.getText().toString()); } catch (Exception e) { attempts = 100; }
        try { pause = Integer.parseInt(etPause.getText().toString()); } catch (Exception e) { pause = 30; }
        try { retry = Integer.parseInt(etRetry.getText().toString()); } catch (Exception e) { retry = 1500; }
        try { statusEvery = Integer.parseInt(etStatusEvery.getText().toString()); } catch (Exception e) { statusEvery = 30; }
        try { rttEvery = Integer.parseInt(etRttEvery.getText().toString()); } catch (Exception e) { rttEvery = 8; }

        prefs.edit()
            .putString("prefix", prefix)
            .putString("suffix", etSuffix.getText().toString().trim())
            .putString("digits", String.valueOf(digits))
            .putString("attempts", String.valueOf(attempts))
            .putString("pause", String.valueOf(pause))
            .putString("retry", String.valueOf(retry))
            .putString("statusEvery", String.valueOf(statusEvery))
            .putString("rttEvery", String.valueOf(rttEvery))
            .apply();

        String network;
        try {
            network = android.net.Uri.parse(url).getHost();
            if (network == null) network = "default";
        } catch (Exception e) { network = "default"; }

        Intent svc = new Intent(this, FinderService.class);
        svc.setAction(FinderService.ACTION_START);
        svc.putExtra(FinderService.EXTRA_URL, url);
        svc.putExtra(FinderService.EXTRA_PREFIX, prefix);
        svc.putExtra(FinderService.EXTRA_SUFFIX, etSuffix.getText().toString().trim());
        svc.putExtra(FinderService.EXTRA_DIGITS, digits);
        svc.putExtra(FinderService.EXTRA_ATTEMPTS, attempts);
        svc.putExtra(FinderService.EXTRA_NETWORK, network);
        svc.putExtra(FinderService.EXTRA_PAUSE, pause);
        svc.putExtra(FinderService.EXTRA_RETRY, retry);
        svc.putExtra(FinderService.EXTRA_STATUS_EVERY, statusEvery);
        svc.putExtra(FinderService.EXTRA_RTT_EVERY, rttEvery);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, svc);
        } else {
            startService(svc);
        }

        FinderState.fullLog.setLength(0);
        logText.setText("");
        FinderState.running = true;
        btnStart.setEnabled(false);
        btnStop.setEnabled(true);
        statusText.setText("جاري التخمين...");
    }

    private void stopFinder() {
        Intent svc = new Intent(this, FinderService.class);
        svc.setAction(FinderService.ACTION_STOP);
        startService(svc);
        btnStart.setEnabled(true);
        btnStop.setEnabled(false);
        statusText.setText("متوقف");
    }

    @Override public void onLog(String msg) {
        uiHandler.post(() -> {
            logText.append(msg + "\n");
            if (logText.getText().length() > 30000) {
                logText.setText(logText.getText().subSequence(10000, logText.getText().length()));
            }
            logText.post(() -> {
                int y = logText.getLayout() != null
                    ? logText.getLayout().getHeight() - logText.getHeight() : 0;
                logText.scrollTo(0, Math.max(0, y));
            });
        });
    }

    @Override public void onProgress(int cur, int total, String v) {
        uiHandler.post(() -> statusText.setText("[" + cur + "/" + total + "] " + v));
    }

    @Override public void onFound(String voucher, String statusJson) {
        uiHandler.post(() -> {
            statusText.setText("✅ وجد: " + voucher);
            btnStart.setEnabled(true);
            btnStop.setEnabled(false);
            Toast.makeText(this, "وجد الكرت: " + voucher, Toast.LENGTH_LONG).show();
            if (webView.getUrl() != null) webView.reload();
        });
    }

    @Override public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (FinderState.listener == this) FinderState.listener = null;
    }
}
