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
    static final Pattern PHONE_ID = Pattern.compile("[0-9a-f]{8}");

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
        return paired(prefs);
    }

    /** A pairing link read but not applied yet: the user confirms it first (security review f436bdd). */
    static final class Pairing {
        String hosts = "";
        int port = -1;
        String key = "";
        String fp = "";
        String id = "";
        String gist = "";
        String fileName = "quill-folders.json";
        String name = "your computer";

        boolean lan() {
            return !key.isEmpty();
        }
    }

    static Pairing parse(Uri u) {
        if (u == null || !"quillpair".equals(u.getScheme()) || !"pair".equals(u.getHost())) {
            return null;
        }
        return parse(u::getQueryParameter);
    }

    /** The link's query parameters (q = name → value or null), checked; null when nothing usable is in it. */
    static Pairing parse(java.util.function.Function<String, String> q) {
        String h = q.apply("h"), p = q.apply("p"), k = q.apply("k");
        String fp = q.apply("fp"), f = q.apply("f"), g = q.apply("g");
        String n = q.apply("n"), id = q.apply("i"), v = q.apply("v");
        Pairing out = new Pairing();
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
                out.hosts = String.join(",", hosts);
                out.port = port;
                out.key = k;
                out.fp = fp;
                if (id != null && PHONE_ID.matcher(id).matches() && "2".equals(v)) {
                    out.id = id;
                }
                any = true;
            }
        }
        if (g != null && GIST.matcher(g).matches()) {
            out.gist = g;
            any = true;
        }
        if (f != null && FILE.matcher(f).matches()) {
            out.fileName = f;
        }
        if (n != null && !n.isEmpty() && n.length() <= 64) {
            out.name = Notifier.clip(n, 64);
        }
        return any ? out : null;
    }

    /**
     * Saves a confirmed pairing, replacing the old one. Quill Desktop's unlock token is dropped (it belongs to the old
     * pairing and must never reach a new host), and a home-network key registered with a different Quill Desktop
     * (other certificate) is forgotten, so the next unlock asks for the password instead of failing signatures.
     */
    static String apply(SharedPreferences prefs, Pairing p) {
        PhoneApi.dropUnlock();
        SharedPreferences.Editor e = prefs.edit().remove("pair.hosts").remove("pair.port").remove("pair.key").remove("pair.fp").remove("pair.gist")
                .remove("pair.id").remove("pair.v").remove("pair.lastHost").remove("phone.unsupported");
        if (!prefs.getString("pair.fp", "").equals(p.fp)) {
            e.remove("lanKey.registered");
        }
        if (p.lan()) {
            e.putString("pair.hosts", p.hosts).putInt("pair.port", p.port).putString("pair.key", p.key).putString("pair.fp", p.fp);
            if (!p.id.isEmpty()) {
                e.putString("pair.id", p.id).putInt("pair.v", 2);
            }
        }
        if (!p.gist.isEmpty()) {
            e.putString("pair.gist", p.gist);
        }
        e.putString("pair.fileName", p.fileName).putString("pair.name", p.name).apply();
        PhoneApi.dropUnlock();
        return p.name;
    }

    static String pair(SharedPreferences prefs, Uri u) {
        Pairing p = parse(u);
        return p == null ? null : apply(prefs, p);
    }

    /** The certificate fingerprint as the user compares it: the first 16 hex digits in groups of 4. */
    static String shortFp(String fp) {
        if (fp == null || fp.length() < 16) {
            return "";
        }
        return fp.substring(0, 4) + " " + fp.substring(4, 8) + " " + fp.substring(8, 12) + " " + fp.substring(12, 16);
    }

    /** What the pairing question shows; current = the name of the pairing it replaces, or null when not paired. */
    static String confirmText(Pairing p, String current) {
        StringBuilder sb = new StringBuilder();
        sb.append("Computer: ").append(p.name);
        if (p.lan()) {
            sb.append("\nAddress: ").append(p.hosts.replace(",", ", ")).append("  (port ").append(p.port).append(")");
            sb.append("\nCertificate: ").append(shortFp(p.fp));
        }
        if (!p.gist.isEmpty()) {
            sb.append("\nGist: ").append(p.gist);
        }
        if (current != null) {
            sb.append("\n\nThis replaces the pairing with ").append(current.isEmpty() ? "your computer" : current).append('.');
        }
        sb.append("\n\nOnly continue if you opened this link or code yourself from the Devices button in Quill Desktop. "
                + "A pairing link from a web page, a message or another app can point Quill Android at someone else's "
                + "computer, which would then ask for your Quill Desktop device password.");
        return sb.toString();
    }

    /** A pairing is saved (network, gist or synced file). */
    static boolean paired(SharedPreferences prefs) {
        return !prefs.getString("pair.key", "").isEmpty() || !prefs.getString("pair.gist", "").isEmpty() || !prefs.getString("pair.file", "").isEmpty();
    }

    /** Forgets the pairing, its cached data and Quill Desktop's unlock token. */
    static void clearPairing(SharedPreferences prefs) {
        PhoneApi.dropUnlock();
        prefs.edit().remove("pair.hosts").remove("pair.port").remove("pair.key").remove("pair.fp").remove("pair.gist")
                .remove("pair.id").remove("pair.v").remove("pair.lastHost").remove("pair.name").remove("pair.fileName").remove("pair.file").remove("folders.cache").remove("folders.source")
                .remove("folders.at").remove("events.boot").remove("events.since").remove("usage.cache").remove("lanKey.registered").apply();
    }

    void unpair() {
        clearPairing(prefs);
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
                status = st != null ? "Reaching Quill Desktop via " + st : "Quill Desktop not reachable, using last copy";
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
        javax.net.ssl.SSLSocketFactory factory;
        try {
            factory = Net.factory(fp);
        } catch (IOException e) {
            return null;
        }
        for (String host : Net.hosts(prefs)) {
            try {
                Net.log("folders", "GET", path);
                HttpsURLConnection c = (HttpsURLConnection) new URL(Net.url(host, port, path)).openConnection();
                c.setSSLSocketFactory(factory);
                c.setHostnameVerifier(Net.ANY_HOST);
                c.setConnectTimeout(2500);
                c.setReadTimeout(4000);
                c.setRequestProperty("X-Quill-Key", key);
                // Every paired device needs Quill Desktop's device password; without the token this got 401 (0.9.22).
                String unlock = PhoneApi.unlockToken(fp);
                if (unlock != null) {
                    c.setRequestProperty("X-Quill-Unlock", unlock);
                }
                String body = read(c);
                if (body != null) {
                    Net.answered(prefs, host);
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
                    throw new CertificateException("certificate does not match the paired Quill Desktop");
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
            return "Not paired  ·  pair from the Devices button in the Quill Desktop top bar";
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
