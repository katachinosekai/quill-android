package io.github.katachinosekai.quillremote;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * kmem on Quill Android (phase 3): pure helpers for the read-only Memory tab (/phone/kmem/find|topics|get|fact).
 * No Android views here; the parse methods are thin and everything else works on plain values.
 */
final class KmemMemoryModel {
    private KmemMemoryModel() {
    }

    static final int MIN_QUERY = 2, MAX_QUERY = 200;
    /** Quill Desktop allows one find per second; wait for the typing to settle first. */
    static final long DEBOUNCE_MS = 500, FIND_GAP_MS = 1000;

    /** One fact row of find/get. A row without an id is plain text and cannot be opened. */
    static final class Fact {
        final String id, kind, topic, text, why;
        final boolean pinned, expired;

        Fact(String id, String kind, String topic, String text, String why, boolean pinned, boolean expired) {
            this.id = id == null ? "" : id;
            this.kind = kind == null ? "" : kind;
            this.topic = topic == null ? "" : topic;
            this.text = text == null ? "" : text;
            this.why = why == null ? "" : why;
            this.pinned = pinned;
            this.expired = expired;
        }

        boolean openable() {
            return !id.isEmpty();
        }
    }

    /** A topic or a section of a topic: name, number of facts, size in KB. */
    static final class Topic {
        final String name;
        final int facts, kb;

        Topic(String name, int facts, int kb) {
            this.name = name;
            this.facts = facts;
            this.kb = kb;
        }
    }

    static final class History {
        final String at, what, by;

        History(String at, String what, String by) {
            this.at = at;
            this.what = what;
            this.by = by;
        }
    }

    /** The full fact of /phone/kmem/fact. */
    static final class Detail {
        final Fact fact;
        final String state, recorded, by, source, validFrom, validTo, task, supersededBy;
        final boolean binned;
        final List<History> history;

        Detail(Fact fact, String state, boolean binned, String recorded, String by, String source, String validFrom, String validTo,
               String task, String supersededBy, List<History> history) {
            this.fact = fact;
            this.state = n(state);
            this.binned = binned;
            this.recorded = n(recorded);
            this.by = n(by);
            this.source = n(source);
            this.validFrom = n(validFrom);
            this.validTo = n(validTo);
            this.task = n(task);
            this.supersededBy = n(supersededBy);
            this.history = history == null ? new ArrayList<>() : history;
        }
    }

    /** The topic view: its own facts, its sections, and whether the list was cut off. */
    static final class TopicView {
        final String topic;
        final List<Fact> facts;
        final List<Topic> sections;
        final boolean truncated;

        TopicView(String topic, List<Fact> facts, List<Topic> sections, boolean truncated) {
            this.topic = topic;
            this.facts = facts;
            this.sections = sections;
            this.truncated = truncated;
        }
    }

    private static String n(String s) {
        return s == null ? "" : s;
    }

    // ---- search gating ----

    static boolean validQuery(String q) {
        int len = q == null ? 0 : q.trim().length();
        return len >= MIN_QUERY && len <= MAX_QUERY;
    }

    /**
     * How long to wait before sending the next find: after the debounce since the last keystroke, no sooner than one second
     * after the previous find, and not before a 429's Retry-After ran out. 0 = send now.
     */
    static long findDelayMs(long now, long lastTyped, long lastFind, long blockedUntil) {
        long at = Math.max(lastTyped + DEBOUNCE_MS, Math.max(lastFind + FIND_GAP_MS, blockedUntil));
        return Math.max(0, at - now);
    }

    // ---- display ----

    /** The first {@code lines} non-empty lines of a text, with an ellipsis when more follow. */
    static String preview(String text, int lines) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int used = 0;
        boolean more = false;
        for (String l : text.split("\n")) {
            if (l.trim().isEmpty()) {
                continue;
            }
            if (used == lines) {
                more = true;
                break;
            }
            if (used > 0) {
                sb.append('\n');
            }
            sb.append(l.trim());
            used++;
        }
        return more ? sb + " …" : sb.toString();
    }

    /** "decision · quill/decisions · pinned · expired" */
    static String factMeta(Fact f) {
        StringBuilder sb = new StringBuilder();
        sb.append(f.kind.isEmpty() ? "fact" : f.kind);
        if (!f.topic.isEmpty()) {
            sb.append(" · ").append(f.topic);
        }
        if (f.pinned) {
            sb.append(" · pinned");
        }
        if (f.expired) {
            sb.append(" · expired");
        }
        return sb.toString();
    }

    /** "quill/android · 12 facts · 3 KB" */
    static String topicLine(Topic t) {
        return t.name + " · " + t.facts + (t.facts == 1 ? " fact" : " facts") + " · " + t.kb + " KB";
    }

    /** "2026-11-22T19:22:20-08:00" -> "2026-11-22 19:22" (anything else is shown as it came). */
    static String shortTime(String iso) {
        if (iso == null) {
            return "";
        }
        if (iso.length() >= 16 && iso.charAt(10) == 'T') {
            return iso.substring(0, 10) + " " + iso.substring(11, 16);
        }
        return iso;
    }

    static String stateText(String state) {
        switch (state == null ? "" : state) {
            case "superseded":
                return "superseded by a newer fact";
            case "retired":
                return "retired";
            default:
                return "current";
        }
    }

    static String validity(String from, String to) {
        if (from.isEmpty() && to.isEmpty()) {
            return "";
        }
        if (to.isEmpty()) {
            return "from " + shortTime(from);
        }
        if (from.isEmpty()) {
            return "until " + shortTime(to);
        }
        return shortTime(from) + " to " + shortTime(to);
    }

    /** "2026-11-22 19:22 · retired · by quill" */
    static String historyLine(History h) {
        StringBuilder sb = new StringBuilder(shortTime(h.at));
        if (!h.what.isEmpty()) {
            sb.append(sb.length() > 0 ? " · " : "").append(h.what);
        }
        if (!h.by.isEmpty()) {
            sb.append(sb.length() > 0 ? " · " : "").append("by ").append(h.by);
        }
        return sb.toString();
    }

    /** The label and value rows under the full text of a fact; rows with nothing to say are left out. */
    static List<String[]> detailRows(Detail d) {
        List<String[]> out = new ArrayList<>();
        out.add(new String[]{"Kind", d.fact.kind});
        out.add(new String[]{"Topic", d.fact.topic});
        out.add(new String[]{"Recorded", shortTime(d.recorded)});
        out.add(new String[]{"By", d.by});
        out.add(new String[]{"Why", d.fact.why});
        out.add(new String[]{"Source", d.source});
        out.add(new String[]{"State", stateText(d.state)});
        out.add(new String[]{"Pinned", d.fact.pinned ? "yes" : ""});
        out.add(new String[]{"Expired", d.fact.expired ? "yes" : ""});
        out.add(new String[]{"In the recycle bin", d.binned ? "yes" : ""});
        out.add(new String[]{"Valid", validity(d.validFrom, d.validTo)});
        out.add(new String[]{"Task", d.task});
        out.removeIf(r -> r[1] == null || r[1].isEmpty());
        return out;
    }

    static String errorLine(int code, String error) {
        if (code == 404 || "not_found".equals(error)) {
            return "Not found. It may have been removed on Quill Desktop.";
        }
        if (code == 400 || "bad_request".equals(error)) {
            return "Quill Desktop didn't accept that request.";
        }
        if (code == 429 || "rate_limited".equals(error)) {
            return "Searching too fast; waiting a moment.";
        }
        return KmemModels.errorLine(code, error);
    }

    // ---- navigation inside the tab ----

    /** One screen of the Memory tab: the start page (search and topics), a topic, or a fact. */
    static final class Page {
        static final int HOME = 0, TOPIC = 1, FACT = 2;
        final int type;
        final String key;

        Page(int type, String key) {
            this.type = type;
            this.key = key == null ? "" : key;
        }
    }

    /** Back navigation within the tab: Home at the bottom; back at Home means leave the tab's stack as it is. */
    static final class Nav {
        private final List<Page> stack = new ArrayList<>();

        Nav() {
            stack.add(new Page(Page.HOME, ""));
        }

        Page current() {
            return stack.get(stack.size() - 1);
        }

        void push(int type, String key) {
            Page c = current();
            if (c.type == type && c.key.equals(key)) {
                return;
            }
            stack.add(new Page(type, key));
        }

        boolean canBack() {
            return stack.size() > 1;
        }

        /** True when a page was left. */
        boolean back() {
            if (!canBack()) {
                return false;
            }
            stack.remove(stack.size() - 1);
            return true;
        }

        int depth() {
            return stack.size();
        }

        void reset() {
            stack.subList(1, stack.size()).clear();
        }
    }

    // ---- parsing (org.json; thin) ----

    static List<Fact> parseFacts(JSONArray a) {
        List<Fact> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) {
                out.add(fact(o));
            }
        }
        return out;
    }

    static Fact fact(JSONObject o) {
        return new Fact(o.optString("id"), o.optString("kind"), o.optString("topic"), o.optString("text"), o.optString("why"),
                o.optBoolean("pinned"), o.optBoolean("expired"));
    }

    static List<Topic> parseTopics(JSONArray a) {
        List<Topic> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && !o.optString("name").isEmpty()) {
                out.add(new Topic(o.optString("name"), o.optInt("facts"), o.optInt("kb")));
            }
        }
        return out;
    }

    static TopicView parseTopicView(JSONObject o) {
        return new TopicView(o.optString("topic"), parseFacts(o.optJSONArray("facts")), parseTopics(o.optJSONArray("sections")),
                o.optBoolean("truncated"));
    }

    static Detail parseDetail(JSONObject o) {
        JSONObject f = o.optJSONObject("fact");
        if (f == null) {
            return null;
        }
        List<History> hist = new ArrayList<>();
        JSONArray h = f.optJSONArray("history");
        for (int i = 0; h != null && i < h.length(); i++) {
            JSONObject e = h.optJSONObject(i);
            if (e != null) {
                hist.add(new History(e.optString("at"), e.optString("what"), e.optString("by")));
            }
        }
        return new Detail(fact(f), f.optString("state"), f.optBoolean("binned"), f.optString("recorded"), f.optString("by"),
                f.optString("source"), f.optString("validFrom"), f.optString("validTo"), f.optString("task"),
                f.optString("supersededBy"), hist);
    }
}
