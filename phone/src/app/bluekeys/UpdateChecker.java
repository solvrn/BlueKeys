package app.bluekeys;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asks GitHub which release is newest, at most once a day, on a background thread.
 * Offline, slow or blocked → it silently does nothing, so it never delays the app.
 * Nothing about the user is sent: it is a plain GET of a public page.
 */
final class UpdateChecker {

    static final String REPO = "solvrn/BlueKeys";
    /** Always serves the newest release's file, so one link never goes stale. */
    static final String DOWNLOAD_URL =
        "https://github.com/" + REPO + "/releases/latest/download/BlueKeys.apk";

    /**
     * Your GoatCounter site name (the "x" in x.goatcounter.com). Once a day the app then
     * counts one "open" for its version there. Sent: the app version, and (as with any web
     * request) the IP address the server sees. Never sent: anything typed, device or
     * account names. Empty = nothing is sent.
     */
    static final String GOATCOUNTER_SITE = "salven-goatcounter";

    private static final long ONE_DAY_MS = 24L * 60 * 60 * 1000;

    interface Callback { void onUpdateAvailable(String newVersion); }

    static void checkOncePerDay(Context ctx, SharedPreferences prefs, Callback cb) {
        long last = prefs.getLong("updateCheckedAt", 0);
        if (System.currentTimeMillis() - last < ONE_DAY_MS) return;
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                String mine = app.getPackageManager()
                    .getPackageInfo(app.getPackageName(), 0).versionName;
                String latest = fetchLatestVersion();
                if (latest == null) return;                       // no answer: try next launch
                prefs.edit().putLong("updateCheckedAt", System.currentTimeMillis()).apply();
                if (isNewer(latest, mine)) cb.onUpdateAvailable(latest);
            } catch (Exception ignored) { }
        }, "update-check").start();
    }

    private static String fetchLatestVersion() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(
            "https://api.github.com/repos/" + REPO + "/releases/latest").openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(5000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            if (c.getResponseCode() != 200) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            Matcher m = Pattern.compile("\"tag_name\"\\s*:\\s*\"v?([^\"]+)\"").matcher(sb);
            return m.find() ? m.group(1) : null;
        } finally {
            c.disconnect();
        }
    }

    /** Counts one open per day, independent of the update check. Silent on any failure. */
    static void pingOncePerDay(Context ctx, SharedPreferences prefs) {
        if (GOATCOUNTER_SITE.isEmpty()) return;
        long last = prefs.getLong("pingedAt", 0);
        if (System.currentTimeMillis() - last < ONE_DAY_MS) return;
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                String v = app.getPackageManager()
                    .getPackageInfo(app.getPackageName(), 0).versionName;
                HttpURLConnection c = (HttpURLConnection) new URL("https://" + GOATCOUNTER_SITE
                    + ".goatcounter.com/count?p=/open/" + v + "&t=BlueKeys%20" + v)
                    .openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(5000);
                // A browser-like name, or GoatCounter's bot filter may ignore the request.
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) BlueKeys/" + v);
                int code = c.getResponseCode();
                c.disconnect();
                if (code >= 200 && code < 300) {
                    prefs.edit().putLong("pingedAt", System.currentTimeMillis()).apply();
                }
            } catch (Exception ignored) { }
        }, "open-count").start();
    }

    /** "1.10" is newer than "1.9": compares dotted numbers part by part. */
    static boolean isNewer(String latest, String mine) {
        String[] a = latest.split("\\."), b = mine.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? num(a[i]) : 0, y = i < b.length ? num(b[i]) : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int num(String s) {
        try { return Integer.parseInt(s.replaceAll("[^0-9]", "")); } catch (Exception e) { return 0; }
    }
}
