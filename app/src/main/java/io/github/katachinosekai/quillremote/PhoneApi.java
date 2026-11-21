package io.github.katachinosekai.quillremote;

import android.content.SharedPreferences;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import org.json.JSONException;
import org.json.JSONObject;

final class PhoneApi {
    static final int MAX_BYTES = 4 << 20;

    static final class Reply {
        final int code;
        final JSONObject body;
        final boolean replay;

        Reply(int code, JSONObject body, boolean replay) {
            this.code = code;
            this.body = body;
            this.replay = replay;
        }

        boolean ok() {
            return code >= 200 && code < 300 && body != null;
        }

        String detail() {
            JSONObject e = body == null ? null : body.optJSONObject("error");
            return e == null ? "" : e.optString("message");
        }

        String error() {
            JSONObject e = body == null ? null : body.optJSONObject("error");
            return e == null ? (code == 0 ? "unreachable" : "http_" + code) : e.optString("code");
        }
    }

    final SharedPreferences prefs;
    final SecureRandom random = new SecureRandom();
    SSLContext ctx;
    String ctxFp;

    PhoneApi(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    boolean paired2() {
        return prefs.getInt("pair.v", 0) >= 2 && !prefs.getString("pair.key", "").isEmpty();
    }

    boolean available() {
        return !prefs.getBoolean("phone.unsupported", false) && prefs.getInt("pair.v", 0) >= 2 && !prefs.getString("pair.key", "").isEmpty()
                && !prefs.getString("pair.fp", "").isEmpty() && prefs.getInt("pair.port", -1) > 0;
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (IOException e) {
            return s;
        }
    }

    String newKey() {
        byte[] b = new byte[18];
        random.nextBytes(b);
        return android.util.Base64.encodeToString(b, android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);
    }

    List<String> hosts() {
        List<String> out = new ArrayList<>();
        String last = prefs.getString("pair.lastHost", "");
        for (String h : prefs.getString("pair.hosts", "").split(",")) {
            if (!h.isEmpty()) {
                out.add(h);
            }
        }
        if (out.remove(last)) {
            out.add(0, last);
        }
        List<String> ips = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (String h : out) {
            (ipLiteral(h) ? ips : names).add(h);
        }
        ips.addAll(names);
        return ips;
    }

    static boolean ipLiteral(String h) {
        return h.matches("[0-9.]+") || (h.contains(":") && h.matches("[\\[\\]0-9a-fA-F:.%]+"));
    }

    synchronized SSLContext ssl(String fp) throws IOException {
        if (ctx == null || !fp.equals(ctxFp)) {
            try {
                SSLContext c = SSLContext.getInstance("TLS");
                c.init(null, new TrustManager[]{FolderSync.pinned(fp)}, null);
                ctx = c;
                ctxFp = fp;
            } catch (Exception e) {
                throw new IOException(e);
            }
        }
        return ctx;
    }

    Reply get(String path, int readTimeoutMs) {
        return call("GET", path, null, null, readTimeoutMs);
    }

    Reply post(String path, JSONObject body) {
        String key = newKey();
        Reply r = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            r = call("POST", path, body, key, 15000);
            boolean retry = r.code == 0 || r.code == 503 || r.code == 429
                    || (r.code == 409 && "in_progress".equals(r.error()));
            if (!retry) {
                return r;
            }
            long wait = r.code == 429 ? 1000L * Math.max(1, r.body == null ? 1 : r.body.optInt("retryAfter", 1)) : 600L * (attempt + 1);
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return r;
            }
        }
        return r;
    }

    Reply call(String method, String path, JSONObject body, String idem, int readTimeoutMs) {
        String key = prefs.getString("pair.key", "");
        String fp = prefs.getString("pair.fp", "");
        int port = prefs.getInt("pair.port", -1);
        if (key.isEmpty() || fp.isEmpty() || port <= 0) {
            return new Reply(0, null, false);
        }
        SSLContext c;
        try {
            c = ssl(fp);
        } catch (IOException e) {
            return new Reply(0, null, false);
        }
        byte[] payload = body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8);
        for (String host : hosts()) {
            HttpsURLConnection conn = null;
            try {
                String h = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
                conn = (HttpsURLConnection) new URL("https://" + h + ":" + port + path).openConnection();
                conn.setSSLSocketFactory(c.getSocketFactory());
                conn.setHostnameVerifier((name, session) -> true);
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(readTimeoutMs);
                conn.setRequestMethod(method);
                conn.setRequestProperty("X-Quill-Key", key);
                conn.setRequestProperty("Accept", "application/json");
                if (idem != null) {
                    conn.setRequestProperty("Idempotency-Key", idem);
                }
                if (payload != null) {
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setFixedLengthStreamingMode(payload.length);
                    try (OutputStream o = conn.getOutputStream()) {
                        o.write(payload);
                    }
                }
                int code = conn.getResponseCode();
                InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                String text = in == null ? "" : read(in);
                JSONObject json = null;
                if (!text.isEmpty()) {
                    try {
                        json = new JSONObject(text);
                    } catch (JSONException ignored) {
                    }
                }
                if (json != null && code == 429) {
                    String ra = conn.getHeaderField("Retry-After");
                    try {
                        json.put("retryAfter", ra == null ? 1 : Integer.parseInt(ra.trim()));
                    } catch (JSONException | NumberFormatException ignored) {
                    }
                }
                if (!host.equals(prefs.getString("pair.lastHost", ""))) {
                    prefs.edit().putString("pair.lastHost", host).apply();
                }
                return new Reply(code, json, "true".equals(conn.getHeaderField("Idempotent-Replay")));
            } catch (IOException ignored) {
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
        return new Reply(0, null, false);
    }

    static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        try (InputStream s = in) {
            while ((n = s.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BYTES) {
                    throw new IOException("reply too large");
                }
            }
        }
        return out.toString("UTF-8");
    }

    static JSONObject obj(Object... kv) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 1 < kv.length; i += 2) {
                o.put((String) kv[i], kv[i + 1]);
            }
        } catch (JSONException ignored) {
        }
        return o;
    }

    Reply hello(String name, String app) {
        return post("/phone/hello", obj("name", name, "app", app));
    }

    Reply sessions() {
        return get("/phone/sessions", 8000);
    }

    Reply folders() {
        return get("/phone/folders", 8000);
    }

    Reply items(String id, int epoch, long since) {
        String q = epoch >= 0 ? "?epoch=" + epoch + "&since=" + since : "";
        return get("/phone/session/" + enc(id) + "/items" + q, 10000);
    }

    Reply older(String id, int before) {
        return get("/phone/session/" + enc(id) + "/items?before=" + before, 10000);
    }

    Reply stream(long cursor) {
        return get("/phone/stream?cursor=" + cursor + "&wait=25", 35000);
    }

    Reply send(String id, String text) {
        return post("/phone/session/" + enc(id) + "/send", obj("text", text));
    }

    Reply answer(String id, JSONObject body) {
        return post("/phone/session/" + enc(id) + "/answer", body);
    }

    Reply interrupt(String id) {
        return post("/phone/session/" + enc(id) + "/interrupt", new JSONObject());
    }

    Reply queue(String id, String op, int index, String text) {
        return post("/phone/session/" + enc(id) + "/queue/" + op, obj("index", index, "text", text));
    }

    Reply resume() {
        return post("/phone/usage/resume", new JSONObject());
    }

    Reply newSession(String folder, String backend, String model, String mode) {
        return post("/phone/sessions/new", obj("folder", folder, "backend", backend, "model", model, "mode", mode));
    }
}
