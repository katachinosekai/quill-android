package io.github.katachinosekai.quillremote;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The kmem assistant's chat on the phone (phase 2): ChatView.Actions that call /phone/kmem/assistant/* instead of the
 * session routes, and the items/queue/state the chat shows. Network on remote.work, results on the UI thread.
 */
final class KmemAssistant implements ChatView.Actions {
    final Remote remote;
    final ChatView chat;
    /** Called on the UI thread when the assistant state changed (header, card, countdown). */
    Runnable onModel;
    volatile KmemAssistModel model;
    boolean loaded;
    int epoch = -1;
    long rev;
    final TreeMap<Integer, JSONObject> items = new TreeMap<>();
    JSONArray queue = new JSONArray();
    final java.util.concurrent.atomic.AtomicBoolean fetching = new java.util.concurrent.atomic.AtomicBoolean();

    KmemAssistant(Remote remote, ChatView chat) {
        this.remote = remote;
        this.chat = chat;
        chat.actions = this;
    }

    /** Reload when the stream (or a tap) says the assistant changed; fresh = whole window. */
    void refresh(boolean fresh) {
        if (!fetching.compareAndSet(false, true)) {
            return;
        }
        remote.work.execute(() -> {
            try {
                fetch(fresh);
            } finally {
                fetching.set(false);
            }
        });
    }

    /** The stream's {epoch, rev}: refetch only when they differ from what was loaded. */
    void streamChanged(long streamEpoch, long streamRev) {
        KmemAssistModel m = model;
        if (m == null || !loaded || m.itemsChanged(streamEpoch, streamRev)) {
            refresh(false);
        }
    }

    void fetch(boolean fresh) {
        int ep;
        long since;
        StringBuilder have = new StringBuilder();
        synchronized (this) {
            ep = fresh ? -1 : epoch;
            since = rev;
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
        PhoneApi.Reply r = remote.api.kmemAssistItems(ep, since, have.toString());
        if (r.code == 400 && have.length() > 0) {
            r = remote.api.kmemAssistItems(ep, since, "");
        }
        if (r.ok()) {
            apply(r.body, fresh);
        } else if (r.code == 401 && "locked".equals(r.error())) {
            remote.ui.post(remote.host::onLocked);
        }
    }

    synchronized void apply(JSONObject o, boolean fresh) {
        KmemAssistModel m = KmemAssistModel.parse(o, System.currentTimeMillis());
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
                    broken = true;
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
            remote.work.execute(() -> fetch(true));
        }
        queue = o.optJSONArray("queue") == null ? new JSONArray() : o.optJSONArray("queue");
        loaded = true;
        List<ChatView.Item> list = new ArrayList<>();
        boolean busy = false;
        for (JSONObject it : items.values()) {
            list.add(ChatView.Item.from(it));
            busy |= it.optBoolean("streaming");
        }
        JSONArray q = queue;
        boolean b = busy;
        remote.postLatest(uiItems, () -> {
            boolean atEnd = chat.follow;
            chat.setItems(list);
            chat.setQueue(q);
            setModel(m, b);
            if (atEnd) {
                chat.stickToBottom();
            }
        });
    }

    final java.util.concurrent.atomic.AtomicReference<Runnable> uiItems = new java.util.concurrent.atomic.AtomicReference<>();
    boolean busy;

    /** UI thread. */
    void setModel(KmemAssistModel m, boolean busyNow) {
        if (m != null) {
            model = m;
        }
        busy = busyNow;
        syncChat();
        Runnable r = onModel;
        if (r != null) {
            r.run();
        }
    }

    /** Input only when "send" is offered and control is on; one status line says why not. */
    void syncChat() {
        KmemAssistModel m = model;
        boolean control = remote.control;
        chat.setReadOnly(!control || m == null || !m.canSend());
        String line = !control ? "Read only (Quill Desktop → Settings → Sessions → Devices)"
                : m != null && !m.canSend() ? "kmem assistant: " + KmemModels.stateText(m.state) + " (can't take messages now)" : "";
        chat.setBusy(busy, line);
    }

    /** Take the assistant object of a write's answer. */
    void takeModel(JSONObject body) {
        KmemAssistModel m = KmemAssistModel.parse(body, System.currentTimeMillis());
        if (m != null) {
            remote.ui.post(() -> setModel(m, busy));
        }
    }

    /** One write. onOk runs on the work thread; failures toast; a stale 409 refreshes (the other app already did it). */
    void write(String what, Supplier<PhoneApi.Reply> call, Consumer<PhoneApi.Reply> onOk) {
        remote.work.execute(() -> {
            PhoneApi.Reply r = call.get();
            if (r.ok()) {
                takeModel(r.body);
                if (onOk != null) {
                    onOk.accept(r);
                }
                fetch(false);
                return;
            }
            if (r.code == 401 && "locked".equals(r.error())) {
                remote.ui.post(remote.host::onLocked);
                return;
            }
            String msg;
            if (KmemAssistModel.alreadyDone(r.code, r.error())) {
                msg = "Already handled (on Quill Desktop or another device).";
                fetch(false);
            } else if (r.code == 403 && "control_off".equals(r.error())) {
                remote.control = false;
                msg = "Quill Desktop doesn't let paired devices control sessions. Turn on \"Let paired devices control sessions\" in Quill Desktop → Settings → Sessions → Devices.";
                remote.ui.post(this::syncChat);
            } else if ("unreachable".equals(r.error())) {
                msg = "Can't reach Quill Desktop; " + what + " not sent.";
            } else {
                msg = what + " failed: " + r.error();
            }
            remote.ui.post(() -> remote.host.toast(msg));
        });
    }

    @Override
    public void send(String text) {
        write("message", () -> remote.api.kmemAssist("send", PhoneApi.obj("text", text)), null);
    }

    @Override
    public void answer(String requestId, String action, JSONObject extra) {
        JSONObject body = new JSONObject();
        try {
            if ("choice".equals(action)) {
                body.put("seq", extra.optInt("seq")).put("index", extra.optInt("index"));
            } else {
                body.put("requestId", requestId);
                for (String k : new String[]{"answers", "text", "choice", "planAnswers"}) {
                    if (extra.has(k)) {
                        body.put(k, extra.get(k));
                    }
                }
            }
            body.put("action", action);
        } catch (JSONException e) {
            return;
        }
        write("answer", () -> remote.api.kmemAssist("answer", body), null);
    }

    @Override
    public void interrupt() {
        write("stop", () -> remote.api.kmemAssist("interrupt", null), null);
    }

    @Override
    public void queueOp(String op, int index, String text) {
        // Same optimistic handling as sessions: look the entry up by its text right before sending.
        remote.work.execute(() -> {
            int at = -1;
            synchronized (this) {
                for (int i = 0; queue != null && i < queue.length(); i++) {
                    JSONObject e = queue.optJSONObject(i);
                    if (e != null && text.equals(e.optString("text"))) {
                        at = e.optInt("index", i);
                        break;
                    }
                }
            }
            if (at < 0) {
                remote.ui.post(() -> chat.queueDone(text));
                return;
            }
            PhoneApi.Reply r = remote.api.kmemAssist("queue/" + op, PhoneApi.obj("index", at, "text", text));
            if (r.ok()) {
                takeModel(r.body);
                JSONArray q = r.body.optJSONArray("queue");
                if (q != null) {
                    synchronized (this) {
                        queue = q;
                    }
                }
                remote.ui.post(() -> {
                    chat.queueDone(text);
                    if (q != null) {
                        chat.setQueue(q);
                    }
                });
            } else {
                remote.ui.post(() -> {
                    chat.queueFailed(text);
                    remote.host.toast(("delete".equals(op) ? "Couldn't remove" : "Couldn't send") + " the queued message (" + r.error() + "); it's back in the list.");
                });
                fetch(false);
            }
        });
    }

    @Override
    public void resume() {
    }

    @Override
    public void typing() {
        remote.touch();
    }

    /** start, stop, restart or save (only offered ones are shown); the reply's assistant replaces the header. */
    void lifecycle(String op, String what) {
        write(what, () -> remote.api.kmemAssist(op, null), null);
    }

    /** Answer the save-timeout question; a stale 409 just refreshes and the card goes away. */
    void choose(int choice) {
        write("choice", () -> remote.api.kmemAssist("choose", PhoneApi.obj("choice", choice)), null);
    }
}
