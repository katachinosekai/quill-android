package io.github.katachinosekai.quillremote;

import android.content.ContentResolver;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

class FolderSync {
    static final long POLL_MS = 30_000;
    static final long GIST_POLL_MS = 60_000;
    static final int MAX_BYTES = 2 << 20;
    static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");
    static final Pattern KEY = Pattern.compile("[A-Za-z0-9_-]{16,128}");
    static final Pattern HOST = Pattern.compile("[A-Za-z0-9.:\\-\\[\\]]{1,255}");
    static final Pattern GIST = Pattern.compile("[A-Za-z0-9-]{1,39}/[0-9a-f]{20,40}");
    static final Pattern FILE = Pattern.compile("[A-Za-z0-9._-]{1,100}");

    interface Listener {
        void onFolders(Map<String, String> sessions, List<String> order);
    }

    static class Data {
        final long updatedMs;
        final Map<String, String> sessions;
        final List<String> order;
        final String raw;

        Data(long updatedMs, Map<String, String> sessions, List<String> order, String raw) {
            this.updatedMs = updatedMs;
            this.sessions = sessions;
            this.order = order;
            this.raw = raw;
        }
    }

    final SharedPreferences prefs;
    final ContentResolver resolver;
    final Listener listener;
    final ExecutorService pool = Executors.newSingleThreadExecutor();
    final Handler ui = new Handler(Looper.getMainLooper());
    boolean running;
    long lastGist;
    String status = "";
    final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            refresh();
            ui.postDelayed(this, POLL_MS);
        }
    };

    FolderSync(SharedPreferences prefs, ContentResolver resolver, Listener listener) {
        this.prefs = prefs;
        this.resolver = resolver;
        this.listener = listener;
    }

    void start() {
        Data d = parse(prefs.getString("folders.cache", null));
        if (d != null) {
            listener.onFolders(d.sessions, d.order);
        }
        running = true;
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    void stop() {
        running = false;
        ui.removeCallbacks(tick);
    }

    boolean paired() {
        return !prefs.getString("pair.key", "").isEmpty() || !prefs.getString("pair.gist", "").isEmpty() || !prefs.getString("pair.file", "").isEmpty();
    }

    static String pair(SharedPreferences prefs, Uri u) {
        if (u == null || !"quillpair".equals(u.getScheme()) || !"pair".equals(u.getHost())) {
            return null;
        }
        String h = u.getQueryParameter("h"), p = u.getQueryParameter("p"), k = u.getQueryParameter("k");
        String fp = u.getQueryParameter("fp"), f = u.getQueryParameter("f"), g = u.getQueryParameter("g");
        String n = u.getQueryParameter("n");
        SharedPreferences.Editor e = prefs.edit().remove("pair.hosts").remove("pair.port").remove("pair.key").remove("pair.fp").remove("pair.gist");
        boolean any = false;
        if (h != null && p != null && k != null && fp != null) {
            List<String> hosts = new ArrayList<>();
            for (String one : h.split(",")) {
                if (HOST.matcher(one).matches()) {
                    hosts.add(one);
                }
            }
            int port;
            try {
                port = Integer.parseInt(p);
            } catch (NumberFormatException ex) {
                port = -1;
            }
            fp = fp.toLowerCase(Locale.ROOT);
            if (!hosts.isEmpty() && port > 1024 && port < 65536 && KEY.matcher(k).matches() && HEX64.matcher(fp).matches()) {
                e.putString("pair.hosts", String.join(",", hosts)).putInt("pair.port", port).putString("pair.key", k).putString("pair.fp", fp);
                any = true;
            }
        }
        if (g != null && GIST.matcher(g).matches()) {
            e.putString("pair.gist", g);
            any = true;
        }
        e.putString("pair.fileName", f != null && FILE.matcher(f).matches() ? f : "quill-folders.json");
        if (!any) {
            return null;
        }
        String name = n != null && n.length() <= 64 ? n : "your computer";
        e.putString("pair.name", name).apply();
        return name;
    }

    void unpair() {
        prefs.edit().remove("pair.hosts").remove("pair.port").remove("pair.key").remove("pair.fp").remove("pair.gist")
                .remove("pair.name").remove("pair.fileName").remove("pair.file").remove("folders.cache").remove("folders.source")
                .remove("folders.at").remove("events.boot").remove("events.since").remove("usage.cache").apply();
        listener.onFolders(new LinkedHashMap<>(), new ArrayList<>());
    }

    void setFile(Uri uri) {
        prefs.edit().putString("pair.file", uri.toString()).apply();
        refresh();
    }

    void refresh() {
        if (!paired()) {
            status = "Not paired";
            return;
        }
        boolean gistDue = System.currentTimeMillis() - lastGist >= GIST_POLL_MS;
        pool.execute(() -> {
            Data best = parse(prefs.getString("folders.cache", null));
            String source = prefs.getString("folders.source", "");
            Data lan = fetchLan();
            if (usageListener != null) {
                String u = lanGet("/usage.json");
                if (u != null) {
                    ui.post(() -> {
                        prefs.edit().putString("usage.cache", u).apply();
                        usageListener.onUsage(u);
                    });
                }
            }
            if (newer(lan, best)) {
                best = lan;
                source = "local network";
            }
            Data file = readFile();
            if (newer(file, best)) {
                best = file;
                source = "synced file";
            }
            if (gistDue) {
                lastGist = System.currentTimeMillis();
                Data gist = fetchGist();
                if (newer(gist, best)) {
                    best = gist;
                    source = "gist";
                }
            }
            String st = lan != null ? "local network" : file != null ? "synced file" : null;
            Data result = best;
            String src = source;
            ui.post(() -> {
                status = st != null ? "Reaching Quill via " + st : "Quill not reachable, using last copy";
                if (result == null) {
                    return;
                }
                if (!result.raw.equals(prefs.getString("folders.cache", null))) {
                    prefs.edit().putString("folders.cache", result.raw).putString("folders.source", src).putLong("folders.at", result.updatedMs).apply();
                    listener.onFolders(result.sessions, result.order);
                }
            });
        });
    }

    static boolean newer(Data a, Data b) {
        return a != null && (b == null || a.updatedMs > b.updatedMs);
    }

    static Data parse(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            JSONObject o = new JSONObject(raw);
            if (o.optInt("version") != 1) {
                return null;
            }
            Map<String, String> sessions = new LinkedHashMap<>();
            JSONObject s = o.optJSONObject("sessions");
            if (s != null) {
                JSONArray names = s.names();
                for (int i = 0; names != null && i < names.length(); i++) {
                    String id = names.getString(i);
                    if (SessionBars.SESSION.matcher("/code/" + id).matches()) {
                        sessions.put(id, s.getString(id));
                    }
                }
            }
            List<String> order = new ArrayList<>();
            JSONArray ord = o.optJSONArray("order");
            for (int i = 0; ord != null && i < ord.length(); i++) {
                order.add(ord.getString(i));
            }
            return new Data(o.optLong("updatedMs"), sessions, order, raw);
        } catch (JSONException e) {
            return null;
        }
    }

    interface UsageListener {
        void onUsage(String json);
    }

    UsageListener usageListener;
    long lastUsage;

    Data fetchLan() {
        return parse(lanGet("/folders.json"));
    }

    String lanGet(String path) {
        String key = prefs.getString("pair.key", "");
        String fp = prefs.getString("pair.fp", "");
        int port = prefs.getInt("pair.port", -1);
        if (key.isEmpty() || fp.isEmpty() || port <= 0) {
            return null;
        }
        SSLContext ctx;
        try {
            ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{pinned(fp)}, null);
        } catch (Exception e) {
            return null;
        }
        for (String host : prefs.getString("pair.hosts", "").split(",")) {
            if (host.isEmpty()) {
                continue;
            }
            try {
                String h = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
                HttpsURLConnection c = (HttpsURLConnection) new URL("https://" + h + ":" + port + path).openConnection();
                c.setSSLSocketFactory(ctx.getSocketFactory());
                c.setHostnameVerifier((name, session) -> true);
                c.setConnectTimeout(2500);
                c.setReadTimeout(4000);
                c.setRequestProperty("X-Quill-Key", key);
                String body = read(c);
                if (body != null) {
                    return body;
                }
            } catch (IOException ignored) {
            }
        }
        return null;
    }

    static X509TrustManager pinned(String fp) {
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("client certificates are not used");
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                if (chain == null || chain.length == 0 || !fp.equals(sha256(chain[0].getEncoded()))) {
                    throw new CertificateException("certificate does not match the paired Quill");
                }
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }

    static String sha256(byte[] b) throws CertificateException {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : d) {
                sb.append(String.format(Locale.ROOT, "%02x", x));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new CertificateException(e);
        }
    }

    Data readFile() {
        String uri = prefs.getString("pair.file", "");
        if (uri.isEmpty()) {
            return null;
        }
        try (InputStream in = resolver.openInputStream(Uri.parse(uri))) {
            return in == null ? null : parse(drain(in));
        } catch (IOException | SecurityException e) {
            return null;
        }
    }

    Data fetchGist() {
        String g = prefs.getString("pair.gist", "");
        if (g.isEmpty()) {
            return null;
        }
        String file = prefs.getString("pair.fileName", "quill-folders.json");
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("https://gist.githubusercontent.com/" + g + "/raw/" + file).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(8000);
            c.setUseCaches(false);
            return parse(read(c));
        } catch (IOException e) {
            return null;
        }
    }

    static String read(HttpURLConnection c) throws IOException {
        try {
            if (c.getResponseCode() != 200) {
                return null;
            }
            try (InputStream in = c.getInputStream()) {
                return drain(in);
            }
        } finally {
            c.disconnect();
        }
    }

    static String drain(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > MAX_BYTES) {
                throw new IOException("folders file too large");
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    String describe() {
        if (!paired()) {
            return "Not paired  ·  Quill → Settings → Pair phone";
        }
        long at = prefs.getLong("folders.at", 0);
        String src = prefs.getString("folders.source", "");
        List<String> srcs = new ArrayList<>();
        if (!prefs.getString("pair.key", "").isEmpty()) {
            srcs.add("network");
        }
        if (!prefs.getString("pair.file", "").isEmpty()) {
            srcs.add("synced file");
        }
        if (!prefs.getString("pair.gist", "").isEmpty()) {
            srcs.add("gist");
        }
        StringBuilder sb = new StringBuilder("Paired with ").append(prefs.getString("pair.name", "Quill"))
                .append("  ·  ").append(String.join(", ", srcs));
        sb.append("\nUpdated ").append(at == 0 ? "never" : ago(System.currentTimeMillis() - at)).append(src.isEmpty() ? "" : " via " + src);
        if (!status.isEmpty()) {
            sb.append("\n").append(status);
        }
        return sb.toString();
    }

    static String ago(long ms) {
        long m = ms / 60000;
        if (m < 1) {
            return "just now";
        }
        if (m < 60) {
            return m + " min ago";
        }
        long h = m / 60;
        if (h < 48) {
            return h + " h ago";
        }
        return (h / 24) + " days ago";
    }
}
