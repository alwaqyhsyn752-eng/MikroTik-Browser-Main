package com.example.mikrotikbrowser;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.webkit.*;
import android.widget.EditText;
import android.widget.ProgressBar;
import androidx.appcompat.app.AppCompatActivity;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private EditText urlBar;
    private ProgressBar progressBar;
    private static final String DETECT_URL = "http://connectivitycheck.gstatic.com/generate_204";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        if (!Python.isStarted()) Python.start(new AndroidPlatform(this));

        webView = findViewById(R.id.webView);
        urlBar = findViewById(R.id.urlBar);
        progressBar = findViewById(R.id.progressBar);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setAllowFileAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String u) {
                urlBar.setText(u); progressBar.setVisibility(View.GONE);
            }
            @Override public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) {
                    String html = "<html><body style='text-align:center;padding:40px;font-family:sans-serif;direction:rtl;'>"
                        + "<h2>تعذر تحميل الصفحة</h2><p>" + err.getDescription() + "</p>"
                        + "<p>أدخل رابطاً آخر أعلاه أو تأكد من اتصالك.</p></body></html>";
                    v.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
                }
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
        findViewById(R.id.btnFinder).setOnClickListener(v -> startActivity(new Intent(this, FinderActivity.class)));

        webView.loadUrl(DETECT_URL);
    }

    @Override public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
