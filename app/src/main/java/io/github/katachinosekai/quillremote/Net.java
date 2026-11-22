package io.github.katachinosekai.quillremote;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;

/**
 * Shared connection pieces for every request to Quill Desktop (PhoneApi, Notifier, FolderSync), 0.9.22: one
 * SSLSocketFactory and one HostnameVerifier per pairing, so Android's connection pool can reuse TLS connections
 * (a new factory or verifier per request made every request a fresh TCP + TLS handshake, ~49 per idle minute).
 * The certificate itself is pinned by FolderSync.pinned(fp); the host name check is off because Quill Desktop is
 * reached by IP address.
 */
final class Net {
    static final HostnameVerifier ANY_HOST = (name, session) -> true;
    private static String factoryFp;
    private static SSLSocketFactory factory;
    static volatile boolean debug;

    private Net() {
    }

    /** Debug builds log every request's method and path (tag QuillNet; never keys, tokens or bodies). */
    static void init(Context c) {
        debug = (c.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    static synchronized SSLSocketFactory factory(String fp) throws IOException {
        if (factory == null || !fp.equals(factoryFp)) {
            try {
                SSLContext c = SSLContext.getInstance("TLS");
                c.init(null, new TrustManager[]{FolderSync.pinned(fp)}, null);
                factory = c.getSocketFactory();
                factoryFp = fp;
            } catch (Exception e) {
                throw new IOException(e);
            }
        }
        return factory;
    }

    /** Paired addresses: the one that answered last first, then IP addresses, then names (slow DNS last). */
    static List<String> hosts(SharedPreferences prefs) {
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
            (PhoneApi.ipLiteral(h) ? ips : names).add(h);
        }
        ips.addAll(names);
        return ips;
    }

    static void answered(SharedPreferences prefs, String host) {
        if (!host.equals(prefs.getString("pair.lastHost", ""))) {
            prefs.edit().putString("pair.lastHost", host).apply();
        }
    }

    static String url(String host, int port, String path) {
        String h = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        return "https://" + h + ":" + port + path;
    }

    static void log(String who, String method, String path) {
        if (debug) {
            int q = path.indexOf('?');
            android.util.Log.d("QuillNet", who + " " + method + " " + (q < 0 ? path : path.substring(0, q)));
        }
    }
}
