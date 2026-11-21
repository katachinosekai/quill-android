package io.github.katachinosekai.quillremote;

import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class Remote implements ChatView.Actions {
    interface Host {
        void onSessions(String barsJson, String usageJson);

        void onOpened(String id, JSONObject session);

        void onConnection(String line);

        void toast(String msg);

        void onUnsupported();
    }

    final PhoneApi api;
    final ChatView chat;
    final Host host;
    final String appVersion;
    final Handler ui = new Handler(Looper.getMainLooper());
    final ExecutorService work = Executors.newSingleThreadExecutor();
    final java.util.concurrent.atomic.AtomicReference<Runnable> uiSessions = new java.util.concurrent.atomic.AtomicReference<>();
    final java.util.concurrent.atomic.AtomicReference<Runnable> uiItems = new java.util.concurrent.atomic.AtomicReference<>();
    final java.util.concurrent.atomic.AtomicBoolean fetchQueued = new java.util.concurrent.atomic.AtomicBoolean();

    void postLatest(java.util.concurrent.atomic.AtomicReference<Runnable> slot, Runnable r) {
        if (slot.getAndSet(r) == null) {
            ui.post(() -> {
                Runnable x = slot.getAndSet(null);
                if (x != null) {
                    x.run();
                }
            });
        }
    }
    Thread streamThread;
    volatile boolean running;
    volatile int gen;
    volatile boolean control = true;
    volatile String deskVersion = "";

    String boot = "";
    long cursor;
    final List<JSONObject> sessions = new ArrayList<>();
    JSONObject usage;
    JSONObject models = new JSONObject();
    JSONObject modes = new JSONObject();
    volatile String current;
    int epoch = -1;
    long rev;
    final TreeMap<Integer, JSONObject> items = new TreeMap<>();
    JSONArray queue = new JSONArray();

    Remote(PhoneApi api, ChatView chat, Host host, String appVersion, String last) {
        this.api = api;
        this.chat = chat;
        this.host = host;
        this.appVersion = appVersion;
        this.current = last;
        chat.actions = this;
    }

    void start() {
        if (running) {
            return;
        }
        running = true;
        int g = ++gen;
        streamThread = new Thread(() -> loop(g), "quill-remote");
        streamThread.setDaemon(true);
        streamThread.start();
    }

    void stop() {
        running = false;
        gen++;
        if (streamThread != null) {
            streamThread.interrupt();
        }
    }

    void restart() {
        stop();
        synchronized (this) {
            boot = "";
            epoch = -1;
            rev = 0;
            items.clear();
            sessions.clear();
        }
        chat.clear();
        start();
    }

    void loop(int g) {
        boolean hello = false;
        int fails = 0;
        while (running && g == gen) {
            if (!hello) {
                PhoneApi.Reply h = api.hello(android.os.Build.MODEL, appVersion);
                if (h.ok()) {
                    hello = true;
                    control = h.body.optBoolean("control", true);
                    deskVersion = h.body.optString("quill", "");
                } else if (h.code == 403 && "forbidden".equals(h.error())) {
                    connection("This phone was removed in Quill Desktop. Pair it again from the Devices button in the Quill Desktop top bar.");
                    sleep(15000);
                    continue;
                }
            }
            PhoneApi.Reply r = boot.isEmpty() ? api.sessions() : api.stream(cursor);
            if (!running || g != gen) {
                return;
            }
            if (r.code == 404) {
                running = false;
                ui.post(host::onUnsupported);
                return;
            }
            if (!r.ok()) {
                fails++;
                connection(r.code == 0 ? "Can't reach Quill Desktop. Retrying…" : "Quill Desktop replied " + r.error() + ". Retrying…");
                if (r.code == 0 || r.code >= 500) {
                    boot = "";
                }
                sleep(Math.min(15000, 1500L * fails));
                continue;
            }
            if (fails > 0 || boot.isEmpty()) {
                connection("");
            }
            fails = 0;
            try {
                if (boot.isEmpty() || r.body.has("sessions")) {
                    full(r.body);
                } else {
                    changes(r.body);
                }
            } catch (JSONException e) {
                boot = "";
            }
        }
    }

    void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    void connection(String line) {
        ui.post(() -> host.onConnection(line));
    }

    synchronized void full(JSONObject o) throws JSONException {
        String b = o.optString("boot");
        boolean newBoot = !b.equals(boot);
        boot = b;
        cursor = o.optLong("cursor");
        control = o.optBoolean("control", control);
        usage = o.optJSONObject("usage");
        if (o.optJSONObject("models") != null) {
            models = o.optJSONObject("models");
        }
        if (o.optJSONObject("modes") != null) {
            modes = o.optJSONObject("modes");
        }
        sessions.clear();
        JSONArray arr = o.optJSONArray("sessions");
        for (int i = 0; arr != null && i < arr.length(); i++) {
            sessions.add(arr.getJSONObject(i));
        }
        if (current == null || session(current) == null) {
            current = sessions.isEmpty() ? null : sessions.get(0).optString("id");
            newBoot = true;
        }
        Notifier.foregroundQuill = current;
        publish();
        if (current != null) {
            String id = current;
            boolean fresh = newBoot;
            work.execute(() -> fetch(id, fresh));
        }
    }

    synchronized void changes(JSONObject o) throws JSONException {
        if (o.optBoolean("reset") || !o.optString("boot").equals(boot)) {
            boot = "";
            return;
        }
        cursor = o.optLong("cursor", cursor);
        JSONArray ch = o.optJSONArray("changes");
        boolean list = false;
        boolean fetchCurrent = false;
        for (int i = 0; ch != null && i < ch.length(); i++) {
            JSONObject c = ch.getJSONObject(i);
            switch (c.optString("type")) {
                case "session": {
                    JSONObject s = c.getJSONObject("session");
                    replace(s);
                    list = true;
                    if (s.optString("id").equals(current) && (s.optInt("epoch") != epoch || s.optLong("rev") > rev)) {
                        fetchCurrent = true;
                    }
                    break;
                }
                case "removed":
                    remove(c.optString("id"));
                    list = true;
                    break;
                case "order": {
                    JSONArray ids = c.optJSONArray("ids");
                    List<JSONObject> next = new ArrayList<>();
                    for (int k = 0; ids != null && k < ids.length(); k++) {
                        JSONObject s = session(ids.optString(k));
                        if (s != null) {
                            next.add(s);
                        }
                    }
                    for (JSONObject s : sessions) {
                        if (!next.contains(s)) {
                            next.add(s);
                        }
                    }
                    sessions.clear();
                    sessions.addAll(next);
                    list = true;
                    break;
                }
                case "usage":
                    usage = c.optJSONObject("usage");
                    list = true;
                    break;
                case "control":
                    control = c.optBoolean("control");
                    list = true;
                    break;
                default:
                    break;
            }
        }
        if (list) {
            publish();
        }
        if (fetchCurrent && fetchQueued.compareAndSet(false, true)) {
            work.execute(() -> {
                fetchQueued.set(false);
                String id = current;
                if (id != null) {
                    fetch(id, false);
                }
            });
        }
    }

    synchronized JSONObject session(String id) {
        for (JSONObject s : sessions) {
            if (s.optString("id").equals(id)) {
                return s;
            }
        }
        return null;
    }

    void replace(JSONObject s) {
        for (int i = 0; i < sessions.size(); i++) {
            if (sessions.get(i).optString("id").equals(s.optString("id"))) {
                sessions.set(i, s);
                return;
            }
        }
        sessions.add(s);
    }

    void remove(String id) {
        sessions.removeIf(s -> s.optString("id").equals(id));
    }

    synchronized void publish() {
        JSONObject bars = new JSONObject();
        JSONObject meta = new JSONObject();
        try {
            JSONArray its = new JSONArray();
            JSONArray order = new JSONArray();
            for (JSONObject s : sessions) {
                String g = s.optString("group", "Other");
                its.put(new JSONObject().put("id", s.optString("id")).put("folder", g).put("title", s.optString("title")));
                boolean seen = false;
                for (int k = 0; k < order.length(); k++) {
                    seen |= order.optString(k).equals(g);
                }
                if (!seen) {
                    order.put(g);
                }
                JSONObject m = new JSONObject().put("last", s.optLong("updatedMs")).put("status", barStatus(s.optString("status")));
                if (!s.optString("kmem").isEmpty()) {
                    m.put("kmem", s.optString("kmem"));
                }
                JSONObject ctx = s.optJSONObject("context");
                if (ctx != null) {
                    m.put("context", ctx.optLong("used")).put("contextMax", ctx.optLong("max"));
                }
                if (s.has("tokens")) {
                    m.put("tokens", s.optLong("tokens"));
                }
                meta.put(s.optString("id"), m);
            }
            bars.put("items", its).put("order", order).put("current", current == null ? "" : current);
        } catch (JSONException ignored) {
        }
        String usageJson = usageJson(meta);
        String barsJson = bars.toString();
        JSONObject cur = current == null ? null : session(current);
        boolean paused = usage != null && usage.optBoolean("paused");
        long until = usage == null ? 0 : usage.optLong("pausedUntilMs");
        postLatest(uiSessions, () -> {
            host.onSessions(barsJson, usageJson);
            statusLine(cur);
            chat.setPause(paused, until);
        });
    }

    static String barStatus(String s) {
        switch (s) {
            case "running":
            case "waiting":
            case "idle":
            case "error":
            case "terminal":
                return s;
            default:
                return "";
        }
    }

    String usageJson(JSONObject meta) {
        JSONObject u = new JSONObject();
        try {
            JSONObject out = new JSONObject().put("credits", usage == null ? "" : usage.optString("credits"));
            if (usage != null) {
                if (usage.has("todayTokens")) {
                    out.put("today", usage.optLong("todayTokens"));
                }
                if (usage.has("weekTokens")) {
                    out.put("week", usage.optLong("weekTokens"));
                }
                JSONObject five = usage.optJSONObject("fiveHour"), week = usage.optJSONObject("week");
                if (five != null) {
                    out.put("fiveHour", new JSONObject().put("utilization", five.optDouble("pct") / 100.0).put("resetsAt", five.optLong("resetsAtMs") / 1000));
                }
                if (week != null) {
                    out.put("weekly", new JSONObject().put("utilization", week.optDouble("pct") / 100.0).put("resetsAt", week.optLong("resetsAtMs") / 1000));
                }
                if (usage.optBoolean("paused")) {
                    out.put("paused", true);
                }
            }
            u.put("version", 1).put("usage", out).put("meta", meta);
        } catch (JSONException ignored) {
        }
        return u.toString();
    }

    void statusLine(JSONObject s) {
        chat.setReadOnly(!control);
        if (s == null) {
            chat.setBusy(false, null);
            return;
        }
        StringBuilder sb = new StringBuilder();
        String st = s.optString("status");
        sb.append(st.equals("waiting") ? "waiting for you" : st.equals("terminal") ? "open in a terminal" : st);
        sb.append("  ·  ").append(s.optString("model"));
        if (!"default".equals(s.optString("mode"))) {
            sb.append(" · ").append(s.optString("mode"));
        }
        JSONObject ctx = s.optJSONObject("context");
        if (ctx != null && ctx.optLong("max") > 0) {
            sb.append("  ·  ctx ").append(Math.round(100.0 * ctx.optLong("used") / ctx.optLong("max"))).append('%');
        }
        if (s.optInt("queue") > 0) {
            sb.append("  ·  ").append(s.optInt("queue")).append(" queued");
        }
        if (s.optInt("background") > 0) {
            sb.append("  ·  ").append(s.optInt("background")).append(" background");
        }
        if (!control) {
            sb.append("  ·  read only (Quill Desktop → Settings → Sessions → Devices)");
        }
        chat.setBusy(s.optBoolean("busy"), sb.toString());
    }

    void fetch(String id, boolean fresh) {
        int ep;
        long since;
        synchronized (this) {
            ep = epoch;
            since = rev;
        }
        PhoneApi.Reply r = fresh || ep < 0 ? api.items(id, -1, 0) : api.items(id, ep, since);
        if (r.ok()) {
            apply(id, fresh, r.body);
        }
    }

    synchronized void apply(String id, boolean fresh, JSONObject o) {
        if (!id.equals(current)) {
            return;
        }
        if (fresh || epoch < 0 || o.optBoolean("reset") || o.optInt("epoch") != epoch) {
            items.clear();
        }
        epoch = o.optInt("epoch");
        rev = Math.max(rev, o.optLong("rev"));
        JSONArray arr = o.optJSONArray("items");
        for (int i = 0; arr != null && i < arr.length(); i++) {
            JSONObject it = arr.optJSONObject(i);
            if (it != null) {
                items.put(it.optInt("seq"), it);
            }
        }
        queue = o.optJSONArray("queue") == null ? new JSONArray() : o.optJSONArray("queue");
        List<ChatView.Item> list = new ArrayList<>();
        for (JSONObject it : items.values()) {
            list.add(ChatView.Item.from(it));
        }
        JSONArray q = queue;
        postLatest(uiItems, () -> {
            if (id.equals(current)) {
                chat.setItems(list);
                chat.setQueue(q);
            }
        });
    }

    synchronized void open(String id) {
        if (id == null || id.equals(current)) {
            return;
        }
        current = id;
        Notifier.foregroundQuill = id;
        epoch = -1;
        rev = 0;
        items.clear();
        chat.clear();
        JSONObject s = session(id);
        host.onOpened(id, s);
        statusLine(s);
        publish();
        work.execute(() -> fetch(id, true));
    }

    void write(String what, java.util.function.Supplier<PhoneApi.Reply> call) {
        work.execute(() -> {
            PhoneApi.Reply r = call.get();
            if (r.ok()) {
                String id = current;
                if (id != null) {
                    fetch(id, false);
                }
                return;
            }
            if (r.code == 403 && "control_off".equals(r.error())) {
                synchronized (this) {
                    control = false;
                }
                publish();
            }
            String code = r.error();
            String msg;
            switch (code) {
                case "control_off":
                    msg = "Quill Desktop doesn't let paired devices control sessions. Turn on \"Let paired devices control sessions\" in Quill Desktop → Settings → Sessions → Devices.";
                    break;
                case "stale":
                    msg = "Already answered on another device.";
                    break;
                case "blocked":
                    msg = "Quill Desktop can't take a message for this session right now.";
                    break;
                case "not_running":
                    msg = "Nothing is running in this session.";
                    break;
                case "unreachable":
                    msg = "Can't reach Quill Desktop; " + what + " not sent.";
                    break;
                default:
                    msg = what + " failed: " + code;
            }
            ui.post(() -> host.toast(msg));
            if ("stale".equals(code) && current != null) {
                fetch(current, false);
            }
        });
    }

    @Override
    public void send(String text) {
        String id = current;
        if (id == null) {
            return;
        }
        write("message", () -> api.send(id, text));
    }

    @Override
    public void answer(String requestId, String action, JSONObject extra) {
        String id = current;
        if (id == null) {
            return;
        }
        JSONObject body = new JSONObject();
        try {
            if ("choice".equals(action)) {
                body.put("seq", extra.optInt("seq")).put("index", extra.optInt("index"));
            } else {
                body.put("requestId", requestId);
                if (extra.has("answers")) {
                    body.put("answers", extra.get("answers"));
                }
                if (extra.has("text")) {
                    body.put("text", extra.optString("text"));
                }
            }
            body.put("action", action);
        } catch (JSONException e) {
            return;
        }
        write("answer", () -> api.answer(id, body));
    }

    @Override
    public void queueOp(String op, int index, String text) {
        String id = current;
        if (id != null) {
            write("delete".equals(op) ? "remove" : "send now", () -> {
                PhoneApi.Reply r = api.queue(id, op, index, text);
                if (r.ok() && r.body.has("queue")) {
                    JSONArray q = r.body.optJSONArray("queue");
                    ui.post(() -> {
                        if (id.equals(current)) {
                            chat.setQueue(q);
                        }
                    });
                }
                return r;
            });
        }
    }

    @Override
    public void resume() {
        write("resume", () -> {
            PhoneApi.Reply r = api.resume();
            if (r.ok() && r.body.optJSONObject("usage") != null) {
                synchronized (Remote.this) {
                    usage = r.body.optJSONObject("usage");
                }
                publish();
            }
            return r;
        });
    }

    interface Created {
        void onCreated(String id);
    }

    void folders(java.util.function.Consumer<JSONArray> done) {
        work.execute(() -> {
            PhoneApi.Reply r = api.folders();
            JSONArray f = r.ok() ? r.body.optJSONArray("folders") : null;
            ui.post(() -> done.accept(f == null ? new JSONArray() : f));
        });
    }

    void newSession(String folder, String backend, String model, String mode) {
        write("new session", () -> {
            PhoneApi.Reply r = api.newSession(folder, backend, model, mode);
            JSONObject s = r.ok() ? r.body.optJSONObject("session") : null;
            if (s != null) {
                synchronized (Remote.this) {
                    replace(s);
                }
                publish();
                String id = s.optString("id");
                ui.post(() -> open(id));
            }
            return r;
        });
    }

    @Override
    public void interrupt() {
        String id = current;
        if (id != null) {
            write("stop", () -> api.interrupt(id));
        }
    }
}
