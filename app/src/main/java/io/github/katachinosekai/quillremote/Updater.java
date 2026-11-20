package io.github.katachinosekai.quillremote;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class Updater {
    static final String FEED = "https://api.github.com/repos/katachinosekai/quill-android/releases/latest";
    static final String DOWNLOAD_PREFIX = "https://github.com/katachinosekai/quill-android/releases/download/";
    static final List<String> HOSTS = Arrays.asList("github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com");
    static final long MAX_APK = 50L << 20;
    static final long CHECK_EVERY = 24L * 3600 * 1000;
    static final Pattern TAG = Pattern.compile("v?(\\d+)\\.(\\d+)\\.(\\d+)");
    static final Pattern DIGEST = Pattern.compile("sha256:([0-9a-f]{64})");
    static final String ACTION_STATUS = "io.github.katachinosekai.quillremote.INSTALL_STATUS";

    interface Listener {
        void onUpdate(Release r);

        void onMessage(String msg);
    }

    static class Release {
        final String version, url, sha256;
        final long size;

        Release(String version, String url, String sha256, long size) {
            this.version = version;
            this.url = url;
            this.sha256 = sha256;
            this.size = size;
        }
    }

    final Activity a;
    final SharedPreferences prefs;
    final Listener listener;
    final ExecutorService pool = Executors.newSingleThreadExecutor();
    final Handler ui = new Handler(Looper.getMainLooper());
    String feed = FEED;
    boolean allowTestHost;
    boolean busy;

    Updater(Activity a, SharedPreferences prefs, Listener listener) {
        this.a = a;
        this.prefs = prefs;
        this.listener = listener;
    }

    String current() {
        try {
            return a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "0.0.0";
        }
    }

    static int[] parse(String v) {
        Matcher m = v == null ? null : TAG.matcher(v.trim());
        if (m == null || !m.matches()) {
            return null;
        }
        return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
    }

    static boolean newer(String a, String b) {
        int[] x = parse(a), y = parse(b);
        if (x == null || y == null) {
            return false;
        }
        for (int i = 0; i < 3; i++) {
            if (x[i] != y[i]) {
                return x[i] > y[i];
            }
        }
        return false;
    }

    void checkIfDue() {
        if (System.currentTimeMillis() - prefs.getLong("update.checked", 0) >= CHECK_EVERY) {
            check(false);
        }
    }

    void check(boolean manual) {
        if (busy) {
            return;
        }
        busy = true;
        pool.execute(() -> {
            Release r = null;
            String err = null;
            try {
                r = latest();
            } catch (Exception e) {
                err = e.getMessage();
            }
            Release res = r;
            String msg = err;
            ui.post(() -> {
                busy = false;
                prefs.edit().putLong("update.checked", System.currentTimeMillis()).apply();
                if (res != null && newer(res.version, current())) {
                    listener.onUpdate(res);
                } else if (manual) {
                    listener.onMessage(msg != null ? "Update check failed: " + msg : "Quill for Android " + current() + " is up to date");
                }
            });
        });
    }

    boolean allowed(String url) {
        Uri u = Uri.parse(url);
        if (allowTestHost && "http".equals(u.getScheme()) && "127.0.0.1".equals(u.getHost())) {
            return true;
        }
        return "https".equals(u.getScheme()) && u.getHost() != null && HOSTS.contains(u.getHost());
    }

    Release latest() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(feed).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "quill-android");
        String body;
        try {
            if (c.getResponseCode() != 200) {
                throw new IOException("GitHub answered " + c.getResponseCode());
            }
            try (InputStream in = c.getInputStream()) {
                body = FolderSync.drain(in);
            }
        } finally {
            c.disconnect();
        }
        JSONObject o = new JSONObject(body);
        if (o.optBoolean("draft") || o.optBoolean("prerelease")) {
            return null;
        }
        String tag = o.optString("tag_name");
        int[] v = parse(tag);
        if (v == null) {
            return null;
        }
        String version = v[0] + "." + v[1] + "." + v[2];
        String want = "quill-android-" + version + ".apk";
        JSONArray assets = o.optJSONArray("assets");
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject as = assets.getJSONObject(i);
            if (!want.equals(as.optString("name"))) {
                continue;
            }
            String url = as.optString("browser_download_url");
            Matcher d = DIGEST.matcher(as.optString("digest"));
            long size = as.optLong("size");
            boolean okUrl = url.startsWith(DOWNLOAD_PREFIX) || (allowTestHost && allowed(url));
            if (!okUrl || !d.matches() || size <= 0 || size > MAX_APK) {
                throw new IOException("release file failed the safety checks");
            }
            return new Release(version, url, d.group(1), size);
        }
        return null;
    }

    void install(Release r) {
        if (busy) {
            return;
        }
        if (!a.getPackageManager().canRequestPackageInstalls()) {
            listener.onMessage("Allow Quill to install updates, then tap Update again");
            a.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
            return;
        }
        busy = true;
        listener.onMessage("Downloading " + r.version + "...");
        pool.execute(() -> {
            String err = null;
            File f = new File(new File(a.getCacheDir(), "update"), "quill-update.apk");
            try {
                if (!(f.isFile() && f.length() == r.size && hex(fileSha(f)).equals(r.sha256))) {
                    download(r, f);
                }
                verify(f, r);
                session(f);
            } catch (Exception e) {
                err = e.getMessage();
                f.delete();
            }
            String msg = err;
            ui.post(() -> {
                busy = false;
                if (msg != null) {
                    listener.onMessage("Update not installed: " + msg);
                }
            });
        });
    }

    void download(Release r, File f) throws Exception {
        f.getParentFile().mkdirs();
        f.delete();
        String url = r.url;
        for (int hop = 0; hop < 5; hop++) {
            if (!allowed(url)) {
                throw new IOException("download left GitHub (" + Uri.parse(url).getHost() + ")");
            }
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(10000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "quill-android");
            try {
                int code = c.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null) {
                        throw new IOException("bad redirect");
                    }
                    url = new URL(new URL(url), loc).toString();
                    continue;
                }
                if (code != 200) {
                    throw new IOException("download answered " + code);
                }
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                long total = 0;
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(f)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > MAX_APK || total > r.size) {
                            throw new IOException("file larger than announced");
                        }
                        md.update(buf, 0, n);
                        out.write(buf, 0, n);
                    }
                }
                if (total != r.size) {
                    throw new IOException("file size does not match the release");
                }
                if (!hex(md.digest()).equals(r.sha256)) {
                    throw new IOException("checksum does not match the release (download may be tampered with)");
                }
                return;
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("too many redirects");
    }

    void verify(File f, Release r) throws Exception {
        PackageManager pm = a.getPackageManager();
        PackageInfo got = pm.getPackageArchiveInfo(f.getPath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (got == null || got.signingInfo == null) {
            throw new IOException("not a valid Quill APK");
        }
        if (!a.getPackageName().equals(got.packageName)) {
            throw new IOException("APK is for a different app");
        }
        PackageInfo mine = pm.getPackageInfo(a.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (got.getLongVersionCode() <= mine.getLongVersionCode()) {
            throw new IOException("APK is not newer than the installed app");
        }
        if (!certs(mine).equals(certs(got)) || certs(got).isEmpty()) {
            throw new IOException("APK is not signed by the Quill for Android key");
        }
    }

    static Set<String> certs(PackageInfo p) throws Exception {
        Set<String> out = new HashSet<>();
        Signature[] sigs = p.signingInfo.hasMultipleSigners() ? p.signingInfo.getApkContentsSigners() : p.signingInfo.getSigningCertificateHistory();
        if (sigs == null) {
            return out;
        }
        if (!p.signingInfo.hasMultipleSigners() && sigs.length > 0) {
            sigs = new Signature[]{sigs[sigs.length - 1]};
        }
        for (Signature s : sigs) {
            out.add(hex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray())));
        }
        return out;
    }

    void session(File f) throws Exception {
        PackageInstaller pi = a.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(a.getPackageName());
        params.setSize(f.length());
        int id = pi.createSession(params);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(f); OutputStream out = s.openWrite("quill.apk", 0, f.length())) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                s.fsync(out);
            }
            Intent cb = new Intent(a, InstallStatus.class).setAction(ACTION_STATUS).setPackage(a.getPackageName());
            PendingIntent pend = PendingIntent.getBroadcast(a, id, cb, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            s.commit(pend.getIntentSender());
        }
    }

    static byte[] fileSha(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        return md.digest();
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format(Locale.ROOT, "%02x", x));
        }
        return sb.toString();
    }

    static void clean(Context c) {
        File d = new File(c.getCacheDir(), "update");
        File[] fs = d.listFiles();
        if (fs != null) {
            for (File f : fs) {
                f.delete();
            }
        }
    }
}
