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

        /** Quill Desktop wants a password for this device (hello "unlock", or 401 "locked"). */
        void onLocked();

        /** Show the phone's own unlock prompt (screen lock or fingerprint); done gets true when it succeeded. */
        void screenLock(java.util.function.Consumer<Boolean> done);

        /** Unlocked by any route (key or password): close a password prompt still on screen. */
        void onUnlocked();
    }

    final PhoneApi api;
    final ChatView chat;
    final Host host;
    final String appVersion;
    /** For Home (shortcuts, widget); taken once on the UI thread, since View calls are UI-thread only. */
    final android.content.Context appContext;
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
    volatile long lastOk;

    String boot = "";
    long cursor;
    final List<JSONObject> sessions = new ArrayList<>();
    JSONObject usage;
    JSONObject models = new JSONObject();
    JSONObject modes = new JSONObject();
    JSONArray projects = new JSONArray();
    /** Quill Desktop's session switches ("Ask before building", "Coordinate with subagents"); null = not supported. */
    volatile JSONObject settings;
    /** Called on the UI thread when settings change (the open ⚙ sheet refreshes its switches). */
    volatile Runnable onSettings;
    /** kmem (hello.kmem api >= 1 and on): the button's data. Written on the stream thread, read on the UI thread. */
    volatile boolean kmemOn;
    /** Files (hello.files and the stream's "files" change): file links open only while api == 1 and on. */
    volatile int filesApi;
    volatile boolean filesOn;
    final LinkActions.Host linkHost = new LinkActions.Host() {
        @Override
        public boolean filesOn() {
            return filesApi == 1 && Remote.this.filesOn;
        }

        @Override
        public void openFile(String path, int line, int lineEnd) {
            String id = current;
            if (id != null) {
                new FileViewer(chat.getContext(), api, id, path, line, lineEnd, Remote.this::expired).show();
            }
        }
    };
    volatile int kmemCount;
    volatile String kmemState = "", kmemWaiting = "";
    /** The assistant's epoch and rev from the last kmem stream change or overview (the open chat refetches when they move). */
    volatile long kmemEpoch, kmemRev;
    /** Called on the UI thread when the kmem summary changed (button) or the stream says kmem changed (open screen). */
    volatile Runnable onKmem;
    /** The Quill operator (hello.quillOperator on; older Quill Desktop leaves it out and the button stays hidden). */
    volatile boolean quillOn;
    volatile QuillOpModel.State quillState;
    volatile long quillEpoch, quillRev, quillLastChange = -1;
    /** Called on the UI thread when the Quill summary changed (button, open sheet). */
    volatile Runnable onQuill;
    final java.util.Set<String> actions = new java.util.HashSet<>();
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
        this.appContext = chat.getContext().getApplicationContext();
        this.current = last;
        ping = new ActivityPing(() -> {
        PhoneApi.Reply r = api.touch();
        boolean ok = r.ok();
        long left = ok && r.body.has("idleLeftSec") ? r.body.optLong("idleLeftSec", -1) : -1;
        return new ActivityPing.Result(ok, r.code == 401 && "locked".equals(r.error()), left);
    }, pings, System::currentTimeMillis, PhoneApi::isUnlocked, this::expired);
        chat.actions = this;
        chat.linkHost = linkHost;
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
                    setFiles(h.body.optJSONObject("files"));
                    JSONObject km = h.body.optJSONObject("kmem");
                    boolean was = kmemOn;
                    kmemOn = km != null && KmemModels.showButton(km.optInt("api"), km.optBoolean("on"));
                    if (kmemOn) {
                        work.execute(() -> kmemRefresh());
                    } else if (was) {
                        kmemChanged();
                    }
                    boolean quillWas = quillOn;
                    quillOn = QuillOpModel.showButton(QuillOpModel.map(ChatView.Item.plain(h.body)));
                    if (quillOn) {
                        work.execute(() -> quillRefresh());
                    } else if (quillWas) {
                        quillState = null;
                        quillChanged();
                    }
                    if (h.body.optBoolean("unlock") && !PhoneApi.isUnlocked()) {
                        lockedWait(g);
                        continue;
                    }
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
            if (r.code == 401 && "locked".equals(r.error())) {
                lockedWait(g);
                continue;
            }
            if (r.code == 404) {
                running = false;
                ui.post(host::onUnsupported);
                return;
            }
            if (!r.ok()) {
                fails++;
                connection(r.code == 0 ? unreachable(fails) : "Quill Desktop replied " + r.error() + ". Retrying…");
                if (r.code == 0 || r.code >= 500) {
                    boot = "";
                }
                sleep(Math.min(15000, 1500L * fails));
                continue;
            }
            lastOk = System.currentTimeMillis();
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

    /** Drop everything loaded (like Quill Desktop's client), ask for the password, and wait until unlocked. */
    /** The unlock token came from the device password (only such a session may register the home-network key). */
    volatile boolean passwordSession;
    /** Why the last home-network unlock didn't work ("" = it did, or wasn't tried), shown in the password prompt. */
    volatile String lanNote = "";

    /**
     * Home-network unlock (user 2026-11-22, Quill Desktop b12fad1): when Quill Desktop says this device may use its key
     * here (hello lanUnlock, only on its home network and for devices switched on in Devices), sign a fresh challenge
     * with the Keystore key instead of asking for the password. The signed text names this device, the nonce and the
     * certificate fingerprint pinned at pairing, so a signature is useless anywhere else.
     */
    boolean keyUnlock(int g) {
        lanNote = "";
        if (!LanKey.exists()) {
            return false;
        }
        // Only a key Quill Desktop accepted (else every try is a bad_signature strike toward the lock-out), and only one
        // made with the screen-lock option the user has now.
        boolean screen = api.prefs.getBoolean("lanScreenLock", true);
        if (screen != api.prefs.getBoolean("lanScreenLock.applied", true)) {
            lanNote = "The screen-lock option changed: unlock with the password once to renew this phone's key.";
            return false;
        }
        if (!LanKey.publicKeyHash().equals(api.prefs.getString("lanKey.registered", ""))) {
            return false;
        }
        PhoneApi.Reply h = api.hello(android.os.Build.MODEL, appVersion);
        if (!h.ok() || !h.body.optBoolean("lanUnlock")) {
            return false;
        }
        String phoneId = h.body.optString("phoneId");
        String fp = api.prefs.getString("pair.fp", "");
        for (int attempt = 0; attempt < 2 && running && g == gen; attempt++) {
            PhoneApi.Reply c = api.unlockChallenge();
            if (!c.ok() || c.body.optString("nonce").isEmpty()) {
                lanNote = "Quill Desktop didn't offer the home-network unlock (" + c.error() + ").";
                return false;
            }
            String nonce = c.body.optString("nonce");
            String sig;
            try {
                sig = LanKey.sign(LanKey.message(phoneId, nonce, fp));
            } catch (android.security.keystore.UserNotAuthenticatedException e) {
                if (attempt > 0 || !askScreenLock()) {
                    lanNote = "The home-network unlock needs this phone's screen lock first.";
                    return false;
                }
                continue; // the phone was just unlocked: sign a fresh challenge
            } catch (android.security.keystore.KeyPermanentlyInvalidatedException e) {
                forgetKey(); // Android dropped it (screen lock changed): a new one comes with the next password unlock
                lanNote = "This phone's screen lock changed: unlock with the password once to renew the home-network key.";
                return false;
            } catch (Exception e) {
                lanNote = "This phone's home-network key can't be used (" + e.getClass().getSimpleName() + ").";
                return false;
            }
            PhoneApi.Reply r = api.unlockSigned(nonce, sig);
            if (r.ok() && !r.body.optString("token").isEmpty()) {
                PhoneApi.unlocked(r.body.optString("token"), fp);
                passwordSession = false;
                ping.reset();
                return true;
            }
            if ("bad_signature".equals(r.error())) {
                api.prefs.edit().remove("lanKey.registered").apply(); // no more tries with this key until re-registered
            }
            lanNote = "locked_out".equals(r.error()) ? "Too many failed tries; Quill Desktop blocks this device for 15 minutes."
                    : "Quill Desktop refused the home-network unlock (" + r.error() + ").";
            return false;
        }
        return false;
    }

    /** Blocks the stream thread until the phone's own unlock prompt is done. */
    boolean askScreenLock() {
        java.util.concurrent.CompletableFuture<Boolean> f = new java.util.concurrent.CompletableFuture<>();
        ui.post(() -> host.screenLock(f::complete));
        try {
            return f.get(120, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    void forgetKey() {
        LanKey.delete();
        api.prefs.edit().remove("lanKey.registered").apply();
    }

    void dropToken() {
        PhoneApi.dropUnlock();
        passwordSession = false;
    }

    void lockedWait(int g) {
        // Locked: drop what was loaded first (like Quill Desktop's client), then try the key, then ask for the password.
        dropToken();
        synchronized (this) {
            sessions.clear();
            items.clear();
            boot = "";
        }
        ui.post(chat::clear);
        publish();
        if (!Relock.silentFirst(() -> keyUnlock(g), () -> running && g == gen, () -> {
            connection("");
            ui.post(host::onUnlocked);
        }, () -> ui.post(host::onLocked))) {
            return; // unlocked silently, or a stopped or restarted connection: the newer one decides
        }
        publish();
        connection("Locked by Quill Desktop. Enter its device password to continue.");
        while (running && g == gen && !PhoneApi.isUnlocked()) {
            sleep(500);
        }
        connection("");
    }

    /** Pings run here, never on `work`: a slow fetch must not hold back the activity signal. */
    final ExecutorService pings = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "quill-ping");
        t.setDaemon(true);
        return t;
    });
    final ActivityPing ping;

    /**
     * After a password unlock: make sure Quill Desktop has this phone's current home-network key (registering again
     * replaces it, e.g. after a reinstall or a changed screen-lock option). A phone without a secure screen lock gets
     * no key while the screen-lock option is on. done (UI thread) gets null or the reason it wasn't registered.
     */
    void registerKey(java.util.function.Consumer<String> done) {
        work.execute(() -> {
            String msg = null;
            if (!passwordSession) {
                if (api.prefs.getBoolean("lanScreenLock", true) != api.prefs.getBoolean("lanScreenLock.applied", true)) {
                    forgetKey(); // the old key must not keep working under the old option
                }
                msg = "Unlock Quill Desktop with its password once to set this up.";
            } else {
                boolean screen = api.prefs.getBoolean("lanScreenLock", true);
                try {
                    if (!LanKey.exists() || !LanKey.usable() || api.prefs.getBoolean("lanScreenLock.applied", true) != screen) {
                        android.app.KeyguardManager km = chat.getContext().getSystemService(android.app.KeyguardManager.class);
                        if (screen && (km == null || !km.isDeviceSecure())) {
                            LanKey.delete();
                            msg = "Set a screen lock on this phone to use the home-network unlock (or turn off \"Require this phone's screen lock\").";
                        } else {
                            LanKey.create(screen);
                            api.prefs.edit().putBoolean("lanScreenLock.applied", screen).apply();
                        }
                    }
                    if (msg == null) {
                        PhoneApi.Reply r = api.unlockKey(LanKey.publicKeyBase64());
                        if (r.ok()) {
                            api.prefs.edit().putString("lanKey.registered", LanKey.publicKeyHash()).apply();
                        } else {
                            api.prefs.edit().remove("lanKey.registered").apply();
                            msg = "Quill Desktop didn't take this phone's key (" + r.error() + ").";
                        }
                    }
                } catch (Exception e) {
                    msg = "Couldn't create this phone's home-network key (" + e.getClass().getSimpleName() + ").";
                }
            }
            if (done != null) {
                String m = msg;
                ui.post(() -> done.accept(m));
            }
        });
    }

    /** POST /phone/unlock; done gets "" on success, else the message to show. */
    void unlock(String password, java.util.function.Consumer<String> done) {
        work.execute(() -> {
            String fp = api.prefs.getString("pair.fp", ""); // the Quill Desktop asked; a re-pair meanwhile won't get its token
            PhoneApi.Reply r = api.unlock(password);
            String msg;
            if (r.ok() && !r.body.optString("token").isEmpty()) {
                PhoneApi.unlocked(r.body.optString("token"), fp);
                passwordSession = true;
                ui.post(host::onUnlocked);
                ping.reset();
                msg = "";
                registerKey(null);
            } else {
                switch (r.error()) {
                    case "wrong_password":
                        msg = "Wrong password" + (r.detail().isEmpty() ? "." : " (" + r.detail() + ").");
                        break;
                    case "locked_out":
                        msg = "Too many wrong tries. Quill Desktop blocks this device for 15 minutes.";
                        break;
                    case "no_password":
                        msg = "Quill Desktop has no device password set yet (Settings → Sessions → Devices).";
                        break;
                    case "unreachable":
                        msg = "Can't reach Quill Desktop.";
                        break;
                    default:
                        msg = "Unlock failed: " + r.error();
                }
            }
            ui.post(() -> done.accept(msg));
        });
    }

    /** Activity keeps the unlock alive; at most once a minute. */
    @Override
    public void typing() {
        touch();
    }

    /** Your own activity (touches, keys, typing) keeps Quill Desktop's device unlock alive; sent at most once a minute. Without activity the desktop's idle timer (15 min default) runs out and the app asks for the password again. */
    void touch() {
        ping.touch();
    }

    /** Idle seconds Quill Desktop reported with the last ping (-1 = unknown). */
    long idleLeftSec() {
        return ping.idleLeftSec;
    }

    /**
     * Quill Desktop says the unlock ran out (401 locked from any call): drop the token and reconnect. The connection
     * tries the home-network key first (lockedWait -> keyUnlock) and only asks for the password when that fails.
     */
    void expired() {
        if (running && PhoneApi.isUnlocked()) {
            dropToken();
            restart();
        } else if (!running && !PhoneApi.isUnlocked()) {
            ui.post(host::onLocked);
        }
    }

    void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** After 2 failed tries in a row, suggest the VPN (Quill Desktop's Setup offers Tailscale, NetBird, ZeroTier, WireGuard). */
    static String unreachable(int fails) {
        return fails >= 2
                ? "Can't reach Quill Desktop. Retrying… Not at home? Turn on your VPN (Tailscale, NetBird, ZeroTier or WireGuard), or join your home Wi-Fi."
                : "Can't reach Quill Desktop. Retrying…";
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
        if (o.optJSONArray("projects") != null) {
            projects = o.optJSONArray("projects");
        }
        setSettings(o.optJSONObject("settings"));
        actions.clear();
        JSONArray acts = o.optJSONArray("actions");
        for (int i = 0; acts != null && i < acts.length(); i++) {
            actions.add(acts.optString(i));
        }
        sessions.clear();
        JSONArray arr = o.optJSONArray("sessions");
        for (int i = 0; arr != null && i < arr.length(); i++) {
            sessions.add(arr.getJSONObject(i));
            sawAutoApprove |= sessions.get(i).has("autoApprove");
        }
        if (current == null || session(current) == null) {
            current = sessions.isEmpty() ? null : sessions.get(0).optString("id");
            newBoot = true;
        }
        Notifier.foregroundQuill = current;
        if (chat.drafts != null) {
            java.util.Set<String> live = new java.util.HashSet<>();
            for (JSONObject s : sessions) {
                live.add(s.optString("id"));
            }
            String first = current;
            boolean initial = chat.draftId == null;
            ui.post(() -> {
                if (!live.isEmpty()) {
                    chat.drafts.prune(live);
                }
                if (initial && chat.draftId == null) {
                    chat.swapDraft(null, first);
                }
            });
        }
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
                case "kmem":
                    if (kmemOn) {
                        JSONObject n = c.optJSONObject("needs"), as = c.optJSONObject("assistant");
                        if (n != null) {
                            kmemCount = n.optInt("count");
                        }
                        if (as != null) {
                            kmemState = as.optString("state");
                            kmemWaiting = as.optString("waiting");
                            kmemEpoch = as.optLong("epoch");
                            kmemRev = as.optLong("rev");
                        }
                        kmemChanged();
                    }
                    break;
                case "quill":
                    if (quillOn) {
                        applyQuillChange(c);
                    }
                    break;
                case "files":
                    setFiles(c.optJSONObject("files"));
                    break;
                case "settings":
                    setSettings(c.optJSONObject("settings"));
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

    /** Hello or stream: the file capability; file links in the open chat appear or turn into plain text. */
    void setFiles(JSONObject f) {
        boolean was = linkHost.filesOn();
        filesApi = f == null ? 0 : f.optInt("api");
        filesOn = f != null && f.optBoolean("on");
        if (was != linkHost.filesOn()) {
            ui.post(chat::relink);
        }
    }

    void kmemChanged() {
        Runnable k = onKmem;
        if (k != null) {
            ui.post(k);
        }
    }

    /** Stream change {type quill, needs, operator{state, waiting, epoch, rev}, lastChange}. */
    void applyQuillChange(JSONObject c) {
        JSONObject op = c.optJSONObject("operator");
        QuillOpModel.State old = quillState;
        QuillOpModel.State now = QuillOpModel.merge(old, QuillOpModel.map(ChatView.Item.plain(c)));
        quillState = now;
        if (op != null) {
            quillEpoch = op.optLong("epoch");
            quillRev = op.optLong("rev");
        }
        quillChanged();
        if (op == null || !op.has("status")) {
            work.execute(this::quillRefresh); // the stream carries no status line or dot: read them
        }
        long lc = c.optLong("lastChange", -1);
        if (lc != quillLastChange) {
            quillLastChange = lc;
            Runnable r = onQuillChanges;
            if (r != null) {
                ui.post(r);
            }
        }
    }

    /** Called on the UI thread when the settings change log moved (lastChange). */
    volatile Runnable onQuillChanges;

    void quillChanged() {
        Runnable k = onQuill;
        if (k != null) {
            ui.post(k);
        }
    }

    /** Read GET /phone/quill once for the button and the sheet header. */
    void quillRefresh() {
        PhoneApi.Reply r = api.quillState();
        if (r.ok()) {
            quillState = QuillOpModel.parse(QuillOpModel.map(ChatView.Item.plain(r.body)));
            JSONObject op = r.body.optJSONObject("operator");
            if (op != null) {
                quillEpoch = op.optLong("epoch");
                quillRev = op.optLong("rev");
            }
            quillChanged();
        } else if (r.code == 401 && "locked".equals(r.error())) {
            expired();
        }
    }

    /** Fetch the overview once for the button (hello carries no count). */
    void kmemRefresh() {
        PhoneApi.Reply r = api.kmemOverview();
        if (r.ok()) {
            JSONObject n = r.body.optJSONObject("needs"), as = r.body.optJSONObject("assistant");
            kmemCount = n == null ? 0 : n.optInt("count");
            kmemState = as == null ? "" : as.optString("state");
            kmemWaiting = as == null ? "" : as.optString("waiting");
            kmemEpoch = as == null ? 0 : as.optLong("epoch");
            kmemRev = as == null ? 0 : as.optLong("rev");
            kmemChanged();
        }
    }

    /**
     * Read one kmem panel on the work thread; done runs on the UI thread. A 409 kmem_busy is retried a few times, a 401
     * locked goes to the unlock prompt.
     */
    void kmem(java.util.function.Supplier<PhoneApi.Reply> call, java.util.function.Consumer<PhoneApi.Reply> done) {
        work.execute(() -> {
            PhoneApi.Reply r = call.get();
            for (int i = 0; i < 3 && r.code == 409 && "kmem_busy".equals(r.error()); i++) {
                sleep(1200);
                r = call.get();
            }
            PhoneApi.Reply fin = r;
            if (fin.code == 401 && "locked".equals(fin.error())) {
                expired();
            }
            ui.post(() -> done.accept(fin));
        });
    }

    void setSettings(JSONObject s) {
        settings = s;
        Runnable r = onSettings;
        if (r != null) {
            ui.post(r);
        }
    }

    /** Switch one Quill Desktop setting; done gets null on success, else the message to show. Runs off the UI thread. */
    void setSetting(String key, boolean on, java.util.function.Consumer<String> done) {
        work.execute(() -> {
            PhoneApi.Reply r = api.settings(PhoneApi.obj(key, on));
            String msg = null;
            if (r.ok() && r.body.optJSONObject("settings") != null) {
                setSettings(r.body.optJSONObject("settings"));
            } else if ("control_off".equals(r.error())) {
                msg = "Quill Desktop doesn't let paired devices change settings. Turn on \"Let paired devices control sessions\" in Quill Desktop → Settings → Sessions → Devices.";
            } else if ("unreachable".equals(r.error())) {
                msg = "Can't reach Quill Desktop; the setting wasn't changed.";
            } else {
                msg = "Quill Desktop didn't change the setting (" + r.error() + ").";
            }
            String m = msg;
            ui.post(() -> done.accept(m));
        });
    }

    /** Answer one of Quill Desktop's pending settings questions; done (UI thread, may be null) gets null or the reason. */
    void answerSetting(String id, Integer choice, java.util.function.Consumer<String> done) {
        work.execute(() -> {
            PhoneApi.Reply r = api.settingsAnswer(id, choice);
            String msg = r.ok() ? null : "control_off".equals(r.error())
                    ? "Quill Desktop doesn't let paired devices change settings; answer it there."
                    : "Quill Desktop didn't take the answer (" + r.error() + "); it asks again next time.";
            if (done != null) {
                ui.post(() -> done.accept(msg));
            }
        });
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
                String g = s.optString("project", s.optString("group", "Other"));
                its.put(new JSONObject().put("id", s.optString("id")).put("folder", g).put("title", s.optString("title")).put("tab", s.optInt("tab", -1)));
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
                if (AutoApprove.on(s)) {
                    m.put("autoApprove", true);
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
        Home.sessions(appContext, sessions);
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
        chat.setAutoApprove(autoApproveSupported(), control && AutoApprove.on(s));
        chat.setSuggestion(s == null ? "" : s.optString("suggestion"));
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
        fetch(id, fresh, false);
    }

    /** stick: a heal reload; if the chat was at the newest message, it stays there after the window is replaced. */
    void fetch(String id, boolean fresh, boolean stick) {
        int ep;
        long since;
        StringBuilder have = new StringBuilder();
        synchronized (this) {
            ep = epoch;
            since = rev;
            // Streaming deltas: name the replies still streaming, so only their new text comes back.
            int n = 0;
            for (JSONObject it : items.descendingMap().values()) {
                String k = it.optString("kind");
                String t = it.optString("text");
                if (n < Delta.MAX_HAVE && it.optBoolean("streaming") && ("assistant".equals(k) || "thinking".equals(k)) && !t.isEmpty()) {
                    have.append(have.length() == 0 ? "" : ",").append(Delta.entry(it.optInt("seq"), t));
                    n++;
                }
            }
        }
        PhoneApi.Reply r = fresh || ep < 0 ? api.items(id, -1, 0) : api.items(id, ep, since, have.toString());
        if (!fresh && r.code == 400 && have.length() > 0) {
            r = api.items(id, ep, since); // a server that refuses the have list: plain incremental fetch
        }
        if (r.ok()) {
            apply(id, fresh, r.body, stick);
        }
    }

    synchronized void apply(String id, boolean fresh, JSONObject o) {
        apply(id, fresh, o, false);
    }

    synchronized void apply(String id, boolean fresh, JSONObject o, boolean stick) {
        if (!id.equals(current)) {
            return;
        }
        if (fresh || epoch < 0 || o.optBoolean("reset") || o.optInt("epoch") != epoch) {
            items.clear();
        }
        epoch = o.optInt("epoch");
        rev = Math.max(rev, o.optLong("rev"));
        JSONArray arr = o.optJSONArray("items");
        boolean broken = false;
        for (int i = 0; arr != null && i < arr.length(); i++) {
            JSONObject it = arr.optJSONObject(i);
            if (it == null) {
                continue;
            }
            if (it.has("textFrom")) {
                JSONObject old = items.get(it.optInt("seq"));
                String all = old == null ? null : Delta.append(old.optString("text"), it.optInt("textFrom"), it.optString("text"), it.optInt("textLen", -1));
                if (all == null) {
                    broken = true; // our copy and the server's disagree: keep ours, reload the session below
                    continue;
                }
                try {
                    it.put("text", all);
                } catch (JSONException e) {
                    broken = true;
                    continue;
                }
                it.remove("textFrom");
                it.remove("textLen");
            }
            items.put(it.optInt("seq"), it);
        }
        if (broken && !fresh) {
            work.execute(() -> fetch(id, true));
        }
        queue = o.optJSONArray("queue") == null ? new JSONArray() : o.optJSONArray("queue");
        healBlanks(id, fresh);
        List<ChatView.Item> list = new ArrayList<>();
        for (JSONObject it : items.values()) {
            list.add(ChatView.Item.from(it));
        }
        JSONArray q = queue;
        postLatest(uiItems, () -> {
            if (id.equals(current)) {
                boolean atEnd = chat.follow;
                chat.setItems(list);
                chat.setQueue(q);
                if (stick && atEnd) {
                    chat.stickToBottom();
                }
            }
        });
    }

    long lastHeal;
    final java.util.Set<String> healed = new java.util.HashSet<>();
    static final long HEAL_EVERY = 10_000;

    /**
     * Blank self-heal (user 2026-11-21): after a lost connection a reply could stay as an empty bubble, because the phone
     * kept its half-arrived copy and the later change never reached it. A text item (assistant/user) with no text that is
     * not streaming any more, or still "streaming" while the session isn't running, triggers one fresh reload of the
     * window (at most every 10 s, never from a fresh reload itself, so a really empty item can't loop).
     */
    void healBlanks(String id, boolean fresh) {
        if (fresh) {
            return;
        }
        JSONObject s = session(id);
        boolean running = s != null && "running".equals(s.optString("status"));
        // Each blank item gets one reload at most: some are really empty on Quill Desktop (a reply that was only tool
        // calls), and those must not reload the chat every 10 s.
        List<String> found = new ArrayList<>();
        for (JSONObject it : items.values()) {
            String k = it.optString("kind");
            if (("assistant".equals(k) || "user".equals(k)) && it.optString("text").trim().isEmpty()
                    && (!it.optBoolean("streaming") || !running)) {
                String key = id + ":" + epoch + ":" + it.optInt("seq");
                if (!healed.contains(key)) {
                    found.add(key);
                }
            }
        }
        boolean blank = !found.isEmpty();
        long now = System.currentTimeMillis();
        if (blank && now - lastHeal >= HEAL_EVERY) {
            healed.addAll(found);
            if (healed.size() > 2000) {
                healed.clear();
                healed.addAll(found);
            }
            lastHeal = now;
            android.util.Log.i("QuillHeal", "blank item in " + id + ", reloading the window");
            work.execute(() -> fetch(id, true, true));
        }
    }

    synchronized void open(String id) {
        if (id == null || id.equals(current)) {
            return;
        }
        String from = current;
        current = id;
        Notifier.foregroundQuill = id;
        epoch = -1;
        rev = 0;
        items.clear();
        chat.swapDraft(from, id);
        chat.clear();
        JSONObject s = session(id);
        host.onOpened(id, s);
        statusLine(s);
        publish();
        work.execute(() -> fetch(id, true));
    }

    synchronized boolean can(String action) {
        return actions.contains(action);
    }

    synchronized JSONArray projects() {
        return projects;
    }

    /** Resume or delete a saved kmem handoff (POST /phone/handoffs/{name}/resume|delete). */
    void handoffAction(String name, String action, Runnable done) {
        write(action.equals("delete") ? "delete handoff" : "resume", () -> api.post("/phone/handoffs/" + PhoneApi.enc(name) + "/" + action, new JSONObject()), r -> {
            JSONObject s = r.body.optJSONObject("session");
            if (s != null) {
                synchronized (Remote.this) {
                    replace(s);
                }
                publish();
                String id = s.optString("id");
                ui.post(() -> open(id));
            }
            if (done != null) {
                ui.post(done);
            }
        });
    }

    volatile boolean sawAutoApprove;

    /** Quill Desktop has auto-approve (version, or the field was seen); older ones: nothing is shown. */
    boolean autoApproveSupported() {
        return AutoApprove.supported(deskVersion, sawAutoApprove);
    }

    /** Turns the open session's auto-approve off (POST auto-approve-off); the chip goes away at once, a failure brings it back. */
    @Override
    public void autoApproveOff() {
        String id = current;
        if (id == null) {
            return;
        }
        setAutoApprove(id, false);
        work.execute(() -> {
            PhoneApi.Reply r = api.action(id, AutoApprove.OFF_PATH, null);
            if (!r.ok()) {
                ui.post(() -> host.toast("Couldn't turn off auto-approve."));
                setAutoApprove(id, true);
            }
        });
    }

    synchronized void setAutoApprove(String id, boolean on) {
        JSONObject s = session(id);
        if (s == null) {
            return;
        }
        try {
            if (on) {
                s.put("autoApprove", true);
            } else {
                s.remove("autoApprove");
            }
        } catch (JSONException ignored) {
        }
        publish();
    }

    /** A session-menu action (rename, resume, end, delete, place) from the long-press menu. */
    void sessionAction(String what, String id, String action, JSONObject body) {
        write(what, () -> api.action(id, action, body), r -> {
            JSONObject s = r.body.optJSONObject("session");
            String removed = r.body.optString("removed");
            synchronized (Remote.this) {
                if (s != null) {
                    replace(s);
                }
                if (!removed.isEmpty()) {
                    remove(removed);
                    if (removed.equals(current)) {
                        current = sessions.isEmpty() ? null : sessions.get(0).optString("id");
                    }
                }
            }
            publish();
            if (s != null && "resume".equals(action) && !s.optString("id").equals(id)) {
                String nid = s.optString("id");
                ui.post(() -> open(nid));
            } else if (!removed.isEmpty() && removed.equals(id) && current != null) {
                String cur = current;
                ui.post(() -> open(cur));
            }
        });
    }

    void write(String what, java.util.function.Supplier<PhoneApi.Reply> call) {
        write(what, call, null);
    }

    void write(String what, java.util.function.Supplier<PhoneApi.Reply> call, java.util.function.Consumer<PhoneApi.Reply> onOk) {
        work.execute(() -> {
            PhoneApi.Reply r = call.get();
            if (r.ok()) {
                if (onOk != null) {
                    onOk.accept(r);
                }
                String id = current;
                if (id != null) {
                    fetch(id, false);
                }
                return;
            }
            if (r.code == 401 && "locked".equals(r.error())) {
                dropToken();
                restart();
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
                    msg = r.detail().isEmpty() ? "Quill Desktop can't take a message for this session right now." : "Quill Desktop: " + r.detail() + ".";
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

    /** The POST .../answer body for a card action; null when it can't be built. */
    static JSONObject answerBody(String requestId, String action, JSONObject extra) {
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
                if (extra.has("choice")) {
                    body.put("choice", extra.optInt("choice"));
                }
                if (extra.has("approvalChoice")) {
                    body.put("approvalChoice", extra.optInt("approvalChoice"));
                }
                if (extra.has("planAnswers")) {
                    body.put("planAnswers", extra.get("planAnswers"));
                }
                if (extra.optBoolean("keepApproving") && "approvePlan".equals(action)) {
                    body.put("keepApproving", true);
                }
            }
            body.put("action", action);
        } catch (JSONException e) {
            return null;
        }
        return body;
    }

    @Override
    public void answer(String requestId, String action, JSONObject extra) {
        String id = current;
        if (id == null) {
            return;
        }
        JSONObject body = answerBody(requestId, action, extra);
        if (body == null) {
            return;
        }
        write("answer", () -> api.answer(id, body));
    }

    @Override
    public void queueOp(String op, int index, String text) {
        String id = current;
        if (id == null) {
            return;
        }
        // Optimistic queue actions (user 2026-11-21): the phone already hid the entry. Requests run one after another on
        // `work`, and each one looks up the entry's CURRENT index by its text right before sending, so earlier taps that
        // shifted the queue can't make a later one hit the wrong entry. Already gone = done. A failure brings it back.
        work.execute(() -> {
            int at = -1;
            synchronized (Remote.this) {
                for (int i = 0; queue != null && i < queue.length(); i++) {
                    JSONObject e = queue.optJSONObject(i);
                    if (e != null && text.equals(e.optString("text"))) {
                        at = e.optInt("index", i);
                        break;
                    }
                }
            }
            if (at < 0) {
                ui.post(() -> chat.queueDone(text));
                return;
            }
            PhoneApi.Reply r = api.queue(id, op, at, text);
            if (r.ok() && r.body.has("queue")) {
                JSONArray q = r.body.optJSONArray("queue");
                synchronized (Remote.this) {
                    if (id.equals(current)) {
                        queue = q;
                    }
                }
                ui.post(() -> {
                    chat.queueDone(text);
                    if (id.equals(current)) {
                        chat.setQueue(q);
                    }
                });
            } else if (!r.ok()) {
                ui.post(() -> {
                    chat.queueFailed(text);
                    host.toast(("delete".equals(op) ? "Couldn't remove" : "Couldn't send") + " the queued message (" + r.error() + "); it's back in the list.");
                });
                fetch(id, false);
            } else {
                ui.post(() -> chat.queueDone(text));
            }
        });
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

    void handoffs(java.util.function.Consumer<JSONArray> done) {
        work.execute(() -> {
            PhoneApi.Reply r = api.handoffs();
            JSONArray h = r.ok() ? r.body.optJSONArray("handoffs") : null;
            ui.post(() -> done.accept(r.ok() && h == null ? new JSONArray() : h));
        });
    }

    void slug(String title, java.util.function.Consumer<String> done) {
        work.execute(() -> {
            PhoneApi.Reply r = api.slug(title);
            String s = r.ok() ? r.body.optString("slug") : "";
            ui.post(() -> done.accept(s));
        });
    }

    void newSession(String folder, String backend, String model, String mode, JSONObject start) {
        write("new session", () -> {
            PhoneApi.Reply r = api.newSession(folder, backend, model, mode, start);
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
