package com.example.mikrotikbrowser;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FinderActivity extends AppCompatActivity {
    private EditText etUrl, etNetwork, etPrefix, etSuffix, etDigits, etAttempts;
    private Button btnStart, btnStop;
    private TextView tvLog;
    private ExecutorService executor;
    private volatile boolean running = false;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_finder);
        etUrl = findViewById(R.id.etUrl);
        etNetwork = findViewById(R.id.etNetwork);
        etPrefix = findViewById(R.id.etPrefix);
        etSuffix = findViewById(R.id.etSuffix);
        etDigits = findViewById(R.id.etDigits);
        etAttempts = findViewById(R.id.etAttempts);
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);
        tvLog = findViewById(R.id.tvLog);
        tvLog.setMovementMethod(new ScrollingMovementMethod());

        etUrl.setText("http://t.net/login");
        etNetwork.setText("default");
        etPrefix.setText("42091");
        etSuffix.setText("3");
        etDigits.setText("2");
        etAttempts.setText("100");
        btnStop.setEnabled(false);

        btnStart.setOnClickListener(v -> startFinding());
        btnStop.setOnClickListener(v -> { running = false; appendLog("طلب إيقاف..."); });
    }

    public void appendLog(String msg) {
        uiHandler.post(() -> tvLog.append(msg + "\n"));
    }

    public boolean isCancelled() { return !running; }

    private void startFinding() {
        if (running) return;

        final String url = etUrl.getText().toString().trim();
        final String network = etNetwork.getText().toString().trim();
        final String prefix = etPrefix.getText().toString().trim();
        final String suffix = etSuffix.getText().toString().trim();

        int d, a;
        try { d = Integer.parseInt(etDigits.getText().toString().trim()); } catch (Exception e) { d = 2; }
        try { a = Integer.parseInt(etAttempts.getText().toString().trim()); } catch (Exception e) { a = 100; }
        final int digits = d;
        final int attempts = a;

        if (url.isEmpty() || prefix.isEmpty()) {
            Toast.makeText(this, "أدخل الرابط والبادئة", Toast.LENGTH_SHORT).show();
            return;
        }

        tvLog.setText("");
        running = true;
        btnStart.setEnabled(false);
        btnStop.setEnabled(true);
        if (executor == null) executor = Executors.newSingleThreadExecutor();

        executor.execute(() -> {
            try {
                PyObject module = Python.getInstance().getModule("voucher_wrapper");
                module.callAttr("run", url, network, prefix, suffix, digits, attempts, this);
            } catch (Exception e) {
                appendLog("خطأ: " + e.getMessage());
            } finally {
                uiHandler.post(() -> {
                    running = false;
                    btnStart.setEnabled(true);
                    btnStop.setEnabled(false);
                    appendLog("انتهى التنفيذ");
                });
            }
        });
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        running = false;
        if (executor != null) executor.shutdownNow();
    }
}
