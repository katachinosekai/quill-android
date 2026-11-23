package io.github.katachinosekai.quillremote;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** kmem on Quill Android (phase 1): pure helpers for Quill Desktop's /phone/kmem/* answers. No Android views here. */
final class KmemModels {
    private KmemModels() {
    }

    static final int API = 1;

    /** One row of /phone/kmem/tasks. */
    static final class Task {
        final String id, status, title, topic, owner, claim;
        final int prio;
        final boolean needsUser, issue;

        Task(String id, int prio, String status, String title, String topic, String owner, String claim, boolean needsUser, boolean issue) {
            this.id = id;
            this.prio = prio;
            this.status = status;
            this.title = title;
            this.topic = topic;
            this.owner = owner;
            this.claim = claim;
            this.needsUser = needsUser;
            this.issue = issue;
        }
    }

    /** hello.kmem: the button shows only for a level this build can read, and only when kmem is on. */
    static boolean showButton(int api, boolean on) {
        return on && api >= 1;
    }

    static boolean running(String state) {
        return "live".equals(state) || "saving".equals(state) || "stopping".equals(state);
    }

    /** The bubble on the kmem button: the needs count, with a "!" in front while the assistant waits for the user. */
    static String bubble(int count, String waiting) {
        boolean w = waiting != null && !waiting.isEmpty();
        if (count <= 0) {
            return w ? "!" : "";
        }
        return (w ? "!" : "") + (count > 99 ? "99+" : String.valueOf(count));
    }

    static String waitingText(String waiting) {
        switch (waiting == null ? "" : waiting) {
            case "plan":
                return "waiting for you to approve a plan";
            case "question":
                return "waiting for your answer to a question";
            case "permission":
                return "waiting for a permission from you";
            case "saveTimeout":
                return "waiting for your choice: saving took too long";
            default:
                return "";
        }
    }

    static String stateText(String state) {
        switch (state == null ? "" : state) {
            case "off":
                return "off";
            case "panels":
                return "not running (panels only)";
            case "elsewhere":
                return "running on another Quill Desktop";
            case "starting":
                return "starting";
            case "live":
                return "running";
            case "saving":
                return "saving its handoff";
            case "stopping":
                return "stopping";
            case "failed":
                return "failed";
            default:
                return "unknown";
        }
    }

    /** "kmem assistant: running · waiting for ..." */
    static String assistantLine(String state, String waiting, String model) {
        String s = "kmem assistant: " + stateText(state);
        String w = waitingText(waiting);
        if (!w.isEmpty()) {
            s += " · " + w;
        }
        if (model != null && !model.isEmpty() && running(state)) {
            s += " · model " + model;
        }
        return s;
    }

    /** The "Waiting for you" rows apart from the open kmem issues, which are listed by title. */
    static List<String> needsLines(int bin, String audit, int maint, String maintReport, String rolloutWait, int userTasks) {
        List<String> out = new ArrayList<>();
        if (bin > 0) {
            out.add("Recycle bin: " + bin + (bin == 1 ? " item" : " items") + " (empty it on Quill Desktop)");
        }
        if (audit != null && !audit.isEmpty()) {
            out.add("kmem audit: " + audit);
        }
        if (maint > 0) {
            out.add("Maintenance: " + maint + (maint == 1 ? " warning" : " warnings")
                    + (maintReport == null || maintReport.isEmpty() ? "" : " (report " + maintReport + ")"));
        }
        if (rolloutWait != null && !rolloutWait.isEmpty()) {
            out.add("Rollout paused: " + rolloutWait);
        }
        if (userTasks > 0) {
            out.add("Tasks for you: " + userTasks);
        }
        return out;
    }

    static String claimLabel(String claim, String owner) {
        String who = owner == null || owner.isEmpty() ? "" : owner;
        switch (claim == null ? "" : claim) {
            case "live":
                return "claimed by " + (who.isEmpty() ? "a live session" : who);
            case "gone":
                return "claim gone" + (who.isEmpty() ? "" : " (" + who + ")");
            case "lease":
                return "lease" + (who.isEmpty() ? "" : " by " + who);
            case "leaseExpired":
                return "lease expired" + (who.isEmpty() ? "" : " (" + who + ")");
            case "handoff":
                return "handoff" + (who.isEmpty() ? "" : " by " + who);
            default:
                return who.isEmpty() ? "" : "claimed by " + who;
        }
    }

    /** "[t1a2b3c] p2 · claimed by quill" (the title goes on its own line). */
    static String taskMeta(Task t) {
        String c = claimLabel(t.claim, t.owner);
        return "[" + t.id + "] p" + t.prio + (t.status.isEmpty() || "open".equals(t.status) ? "" : " · " + t.status) + (c.isEmpty() ? "" : " · " + c);
    }

    /** Top-level topic ("quill/android" -> "quill"); "" for none. */
    static String topLevel(String topic) {
        if (topic == null) {
            return "";
        }
        int i = topic.indexOf('/');
        return i < 0 ? topic : topic.substring(0, i);
    }

    static final String ALL = "All", NEEDS = "Waiting for you", ISSUES = "kmem issues";

    /** Chip labels: All, Waiting for you, kmem issues (when there are such tasks), then one per top-level topic. */
    static List<String> filters(List<Task> tasks) {
        List<String> out = new ArrayList<>();
        out.add(ALL);
        boolean needs = false, issues = false;
        java.util.TreeSet<String> topics = new java.util.TreeSet<>();
        for (Task t : tasks) {
            needs |= t.needsUser;
            issues |= t.issue;
            if (!topLevel(t.topic).isEmpty()) {
                topics.add(topLevel(t.topic));
            }
        }
        if (needs) {
            out.add(NEEDS);
        }
        if (issues) {
            out.add(ISSUES);
        }
        out.addAll(topics);
        return out;
    }

    static boolean matches(Task t, String filter, String query) {
        boolean ok;
        if (filter == null || filter.equals(ALL)) {
            ok = true;
        } else if (filter.equals(NEEDS)) {
            ok = t.needsUser;
        } else if (filter.equals(ISSUES)) {
            ok = t.issue;
        } else {
            ok = topLevel(t.topic).equals(filter);
        }
        if (ok && query != null && !query.trim().isEmpty()) {
            String q = query.trim().toLowerCase(java.util.Locale.ROOT);
            ok = t.title.toLowerCase(java.util.Locale.ROOT).contains(q) || t.id.toLowerCase(java.util.Locale.ROOT).contains(q);
        }
        return ok;
    }

    /** Filtered tasks grouped by topic (topics sorted, tasks by priority then id); tasks without a topic go under "(no topic)". */
    static Map<String, List<Task>> group(List<Task> tasks, String filter, String query) {
        java.util.TreeMap<String, List<Task>> by = new java.util.TreeMap<>();
        for (Task t : tasks) {
            if (matches(t, filter, query)) {
                String k = t.topic.isEmpty() ? "(no topic)" : t.topic;
                by.computeIfAbsent(k, x -> new ArrayList<>()).add(t);
            }
        }
        Map<String, List<Task>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<Task>> e : by.entrySet()) {
            e.getValue().sort((x, y) -> x.prio != y.prio ? Integer.compare(x.prio, y.prio) : x.id.compareTo(y.id));
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    static String errorLine(int code, String error) {
        if (code == 0) {
            return "Quill Desktop isn't reachable.";
        }
        if (code == 503 || "kmem_unavailable".equals(error)) {
            return "kmem isn't available on Quill Desktop right now.";
        }
        if (code == 409 || "kmem_busy".equals(error)) {
            return "kmem is busy. Try again in a moment.";
        }
        return "Quill Desktop replied " + error + ".";
    }

    static List<Task> parseTasks(JSONArray a) {
        List<Task> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null || o.optString("id").isEmpty()) {
                continue;
            }
            out.add(new Task(o.optString("id"), o.optInt("prio"), o.optString("status"), o.optString("title"),
                    o.optString("topic"), o.optString("owner"), o.optString("claim"), o.optBoolean("needsUser"), o.optBoolean("issue")));
        }
        return out;
    }
}
