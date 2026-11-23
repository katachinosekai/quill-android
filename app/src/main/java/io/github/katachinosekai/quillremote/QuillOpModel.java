package io.github.katachinosekai.quillremote;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The Quill operator on Quill Android (design §4): pure helpers for the Quill sheet. The operator is Quill Desktop's
 * hidden coordinator; the phone chats, answers its plan cards and undoes settings changes, nothing else. Parsing works on
 * plain Maps and Lists (ChatView.Item.plain of a parsed reply), so the JVM tests need no org.json.
 */
final class QuillOpModel {
    private QuillOpModel() {
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    static String str(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        return v == null ? "" : v instanceof Double && (Double) v == Math.rint((Double) v) ? String.valueOf(((Double) v).longValue()) : String.valueOf(v);
    }

    static long num(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        return v instanceof Number ? ((Number) v).longValue() : 0;
    }

    static boolean bool(Map<String, Object> m, String k) {
        return m != null && Boolean.TRUE.equals(m.get(k));
    }

    /** hello.quillOperator: the Quill button shows only for a level this build knows and only while the operator can run. Older Quill Desktop leaves it out: hidden. */
    static boolean showButton(Map<String, Object> hello) {
        Map<String, Object> q = hello == null ? null : map(hello.get("quillOperator"));
        return q != null && num(q, "api") >= 1 && bool(q, "on");
    }

    /** One entry of the settings change log. */
    static final class Change {
        final int n;
        final String id, label, oldValue, newValue, why, by;
        final long timeMs;
        final boolean undone, canUndo;

        Change(int n, String id, String label, String oldValue, String newValue, String why, String by, long timeMs, boolean undone, boolean canUndo) {
            this.n = n;
            this.id = id;
            this.label = label;
            this.oldValue = oldValue;
            this.newValue = newValue;
            this.why = why;
            this.by = by;
            this.timeMs = timeMs;
            this.undone = undone;
            this.canUndo = canUndo;
        }

        /** "Text animation speed: normal → fast". */
        String line() {
            return (label.isEmpty() ? id : label) + ": " + oldValue + " → " + newValue;
        }

        /** The Undo button is enabled only while Quill Desktop says so; an undone change is never undoable. */
        boolean undoable() {
            return canUndo && !undone;
        }
    }

    /** "changes" of GET /phone/quill/settings/changes (newest first, at most 50); bad entries are skipped. */
    static List<Change> parseChanges(Object arr) {
        List<Change> l = new ArrayList<>();
        List<?> a = arr instanceof List ? (List<?>) arr : Collections.emptyList();
        for (int i = 0; i < a.size() && l.size() < 50; i++) {
            Map<String, Object> o = map(a.get(i));
            if (o == null || !(o.get("n") instanceof Number)) {
                continue;
            }
            l.add(new Change((int) num(o, "n"), str(o, "id"), str(o, "label"), str(o, "old"), str(o, "new"),
                    str(o, "why"), str(o, "by"), num(o, "timeMs"), bool(o, "undone"), bool(o, "canUndo")));
        }
        return Collections.unmodifiableList(l);
    }

    /** Body of POST /phone/quill/settings/undo: {"change": n}. */
    static String undoBody(int n) {
        return "{\"change\":" + n + "}";
    }

    /** The sheet's header state: what GET /phone/quill and the stream say. */
    static final class State {
        final String state, status, waiting;
        final int count, unread;
        final boolean dot;
        final List<Change> changes;

        State(String state, String status, String waiting, int count, int unread, boolean dot, List<Change> changes) {
            this.state = state;
            this.status = status;
            this.waiting = waiting;
            this.count = count;
            this.unread = unread;
            this.dot = dot;
            this.changes = changes;
        }

        /** Header status line: "working", "waiting for you to approve a plan", or the state when it isn't running. */
        String line() {
            return statusLine(state, status, waiting);
        }

        /** The button's bubble, as for kmem: the count with "!" in front while it waits for the user. */
        String bubble() {
            return KmemModels.bubble(Math.max(count, unread), waiting);
        }
    }

    static State parse(Map<String, Object> body) {
        if (body == null) {
            return null;
        }
        Map<String, Object> n = map(body.get("needs")), op = map(body.get("operator"));
        String state = body.containsKey("state") ? str(body, "state") : str(op, "state");
        String waiting = body.containsKey("waiting") ? str(body, "waiting") : str(op, "waiting");
        String status = body.containsKey("status") ? str(body, "status") : str(op, "status");
        boolean dot = op != null && op.containsKey("dot") ? bool(op, "dot") : "working".equals(status);
        return new State(state, status, waiting, (int) num(n, "count"), (int) num(n, "unread"), dot, parseChanges(body.get("changes")));
    }

    /** Apply a stream change to the last state: needs and operator state/waiting move, changes and status stay until the next read. */
    static State merge(State old, Map<String, Object> c) {
        Map<String, Object> n = map(c.get("needs")), op = map(c.get("operator"));
        String state = op != null ? str(op, "state") : old == null ? "" : old.state;
        String waiting = op != null ? str(op, "waiting") : old == null ? "" : old.waiting;
        String status = op != null && op.containsKey("status") ? str(op, "status") : old == null ? "" : old.status;
        boolean dot = op != null && op.containsKey("dot") ? bool(op, "dot") : old != null && old.dot;
        int count = n != null ? (int) num(n, "count") : old == null ? 0 : old.count;
        int unread = n != null ? (int) num(n, "unread") : old == null ? 0 : old.unread;
        return new State(state, status, waiting, count, unread, dot, old == null ? Collections.<Change>emptyList() : old.changes);
    }

    static String statusLine(String state, String status, String waiting) {
        if ("waiting".equals(status) || (waiting != null && !waiting.isEmpty())) {
            String w = KmemModels.waitingText(waiting);
            return w.isEmpty() ? "waiting for you" : w;
        }
        if ("elsewhere".equals(state)) {
            return "running in a tab on Quill Desktop";
        }
        if ("working".equals(status)) {
            return "working";
        }
        if ("live".equals(state) && "idle".equals(status)) {
            return "idle";
        }
        return KmemModels.stateText(state);
    }

    /** Where a tap on a notification goes: event type "quill" opens the Quill sheet (it has no session id). */
    static boolean opensSheet(String eventType) {
        return "quill".equals(eventType);
    }

    /** Answer of openTopic: what to do with it. */
    static final int OPEN_NONE = 0, OPEN_SHEET = 1;

    /** {switched, operator, starting}: the quill topic opens the sheet (also while starting, then it follows GET /phone/quill); anything else is not this sheet's. */
    static int topicOpen(Map<String, Object> answer) {
        return bool(answer, "operator") ? OPEN_SHEET : OPEN_NONE;
    }

    static boolean topicStarting(Map<String, Object> answer) {
        return bool(answer, "operator") && bool(answer, "starting");
    }

    /** A topics[] entry for slug "quill" with live "operator" opens the sheet, never a session. */
    static boolean isOperatorTopic(Map<String, Object> topic) {
        return topic != null && "quill".equals(str(topic, "slug")) && "operator".equals(str(topic, "live"));
    }
}
