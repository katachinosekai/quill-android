package io.github.katachinosekai.quillremote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URL;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;

class Notifier {
    static final String CHANNEL = "sessions";
    static final String EXTRA_OPEN = "quill.openSession";
    static final List<String> TYPES = Arrays.asList("approval", "question", "plan", "finished", "error");
    static final String[] LABELS = {"Approval needed", "Question", "Plan to approve", "Finished", "Error"};
    static final long STALE_MS = 10 * 60 * 1000;
    static volatile String foregroundRc;
    static volatile String foregroundQuill;
    static final String EXTRA_OPEN_QUILL = "quill.openQuill";
    static volatile boolean appVisible;

    final Context c;
    final SharedPreferences prefs;
    volatile boolean running;
    Thread thread;

    Notifier(Context c) {
        this.c = c.getApplicationContext();
        this.prefs = this.c.getSharedPreferences("quill", Context.MODE_PRIVATE);
    }

    static void channel(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Session alerts", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Approvals, questions, plans, finished turns and errors from Quill Desktop");
            nm.createNotificationChannel(ch);
        }
    }

    boolean enabled(String type) {
        return TYPES.contains(type) && prefs.getBoolean("notify." + type, true);
    }

    synchronized void start(int waitSeconds) {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(() -> loop(waitSeconds), "quill-events");
        thread.setDaemon(true);
        thread.start();
    }

    synchronized void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    void loop(int waitSeconds) {
        while (running) {
            boolean ok = pollOnce(waitSeconds);
            try {
                Thread.sleep(ok ? 300 : 15000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    boolean pollOnce(int waitSeconds) {
        String key = prefs.getString("pair.key", "");
        String fp = prefs.getString("pair.fp", "");
        int port = prefs.getInt("pair.port", -1);
        if (key.isEmpty() || fp.isEmpty() || port <= 0) {
            return false;
        }
        javax.net.ssl.SSLSocketFactory factory;
        try {
            factory = Net.factory(fp);
        } catch (java.io.IOException e) {
            return false;
        }
        long since = prefs.getLong("events.since", 0);
        if (prefs.getString("events.boot", "").isEmpty()) {
            waitSeconds = 0;
        }
        for (String host : Net.hosts(prefs)) {
            if (!running) {
                continue;
            }
            try {
                Net.log("events", "GET", "/events");
                HttpsURLConnection conn = (HttpsURLConnection) new URL(Net.url(host, port, "/events?since=" + since + "&wait=" + waitSeconds)).openConnection();
                conn.setSSLSocketFactory(factory);
                conn.setHostnameVerifier(Net.ANY_HOST);
                conn.setConnectTimeout(2500);
                conn.setReadTimeout((waitSeconds + 10) * 1000);
                conn.setRequestProperty("X-Quill-Key", key);
                String unlock = PhoneApi.unlockToken;
                if (unlock != null) {
                    conn.setRequestProperty("X-Quill-Unlock", unlock);
                }
                String body = FolderSync.read(conn);
                if (body == null) {
                    continue;
                }
                Net.answered(prefs, host);
                handle(new JSONObject(body));
                return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    void handle(JSONObject o) throws Exception {
        if (o.optInt("version") != 1) {
            return;
        }
        String boot = o.optString("boot");
        long since = prefs.getLong("events.since", 0);
        boolean newBoot = !boot.equals(prefs.getString("events.boot", ""));
        if (newBoot && since != 0) {
            prefs.edit().putString("events.boot", boot).putLong("events.since", 0).apply();
            return;
        }
        JSONArray evs = o.optJSONArray("events");
        long now = System.currentTimeMillis();
        boolean first = prefs.getString("events.boot", "").isEmpty();
        for (int i = 0; evs != null && i < evs.length(); i++) {
            JSONObject e = evs.getJSONObject(i);
            if (first || now - e.optLong("time") > STALE_MS) {
                continue;
            }
            show(e);
        }
        prefs.edit().putString("events.boot", boot).putLong("events.since", o.optLong("next", since)).apply();
        if (evs != null && evs.length() > 0 && !appVisible && Home.hasWidgets(c)) {
            Home.refresh(c, () -> { });
        }
    }

    void show(JSONObject e) {
        String type = e.optString("type");
        if (!enabled(type)) {
            return;
        }
        String rc = e.optString("rc");
        if (!rc.isEmpty() && !SessionBars.SESSION.matcher("/code/" + rc).matches()) {
            rc = "";
        }
        String quill = e.optString("quill");
        if (!SessionBars.REMOTE_ID.matcher(quill).matches()) {
            quill = "";
        }
        boolean remote = new PhoneApi(prefs).available();
        if (appVisible && (remote ? !quill.isEmpty() && quill.equals(foregroundQuill) : !rc.isEmpty() && rc.equals(foregroundRc))) {
            return;
        }
        String title = clip(e.optString("title"), 80);
        String folder = clip(e.optString("folder"), 40);
        String body = clip(e.optString("body"), 160);
        String text = !body.isEmpty() ? body : remote ? "Quill Desktop" : folder.isEmpty() ? "Quill" : folder;
        String label = LABELS[TYPES.indexOf(type)];
        channel(c);
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (remote && !quill.isEmpty()) {
            open.putExtra(EXTRA_OPEN_QUILL, quill);
        } else if (!rc.isEmpty()) {
            open.putExtra(EXTRA_OPEN, rc);
        }
        int id = (rc.isEmpty() ? e.optString("quill") : rc).hashCode();
        PendingIntent pi = PendingIntent.getActivity(c, id, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String head = label + (title.isEmpty() ? "" : ": " + title);
        // Approvals get Allow / Deny buttons (remote mode): look up the pending permission request in the session.
        ChatView.Item req = remote && type.equals("approval") && !quill.isEmpty() ? pendingPermission(new PhoneApi(prefs), quill) : null;
        if (req != null) {
            String what = !req.detail.isEmpty() ? req.detail : req.summary;
            text = clip(req.toolName + (what.isEmpty() ? "" : ": " + what), 300);
        }
        Notification.Builder nb = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(head)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setCategory(type.equals("finished") ? Notification.CATEGORY_STATUS : Notification.CATEGORY_REMINDER);
        if (req != null) {
            nb.addAction(new Notification.Action.Builder(null, "Allow", NotifyAction.intent(c, id, quill, req.requestId, "allow", head)).build());
            nb.addAction(new Notification.Action.Builder(null, "Deny", NotifyAction.intent(c, id, quill, req.requestId, "deny", head)).build());
        }
        Notification n = nb.build();
        try {
            c.getSystemService(NotificationManager.class).notify(id, n);
        } catch (SecurityException ignored) {
        }
    }

    /** The newest still-pending permission request in a Quill Desktop session, or null (none, or unreachable). */
    static ChatView.Item pendingPermission(PhoneApi api, String session) {
        PhoneApi.Reply r = api.items(session, -1, 0);
        JSONArray arr = r.ok() ? r.body.optJSONArray("items") : null;
        for (int i = arr == null ? -1 : arr.length() - 1; i >= 0; i--) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null || !"permission".equals(o.optString("kind"))) {
                continue;
            }
            ChatView.Item it = ChatView.Item.from(o);
            if ((it.state.isEmpty() || "pending".equals(it.state)) && it.requestId != null && !it.requestId.isEmpty()) {
                return it;
            }
        }
        return null;
    }

    static String clip(String s, int n) {
        s = s == null ? "" : s.replaceAll("[\\p{Cntrl}]", " ").trim();
        return s.length() > n ? s.substring(0, n - 1) + "…" : s;
    }
}
