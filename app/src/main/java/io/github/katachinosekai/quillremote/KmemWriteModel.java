package io.github.katachinosekai.quillremote;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** kmem phase 4: pure helpers for task Done/Release and recycle-bin Restore. No Android views here. */
final class KmemWriteModel {
    private KmemWriteModel() {
    }

    static final int RESULT_MAX = 300;

    /** One row of /phone/kmem/bin. */
    static final class BinItem {
        final String id, kind, by, reason, topic, title;
        final int days;

        BinItem(String id, String kind, int days, String by, String reason, String topic, String title) {
            this.id = id;
            this.kind = kind;
            this.days = days;
            this.by = by;
            this.reason = reason;
            this.topic = topic;
            this.title = title;
        }

        boolean valid() {
            return !id.isEmpty();
        }

        /** Title, else topic, else the id. */
        String label() {
            return !title.isEmpty() ? title : !topic.isEmpty() ? topic : id;
        }
    }

    static List<BinItem> parseBin(JSONArray a) {
        List<BinItem> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null || o.optString("id").isEmpty()) {
                continue;
            }
            out.add(new BinItem(o.optString("id"), o.optString("kind"), o.optInt("days"), o.optString("by"),
                    o.optString("reason"), o.optString("topic"), o.optString("title")));
        }
        return out;
    }

    /** "fact · 3 days · by quill" */
    static String binMeta(BinItem b) {
        StringBuilder s = new StringBuilder(b.kind.isEmpty() ? "item" : b.kind);
        s.append(" · ").append(b.days).append(b.days == 1 ? " day" : " days");
        if (!b.by.isEmpty()) {
            s.append(" · by ").append(b.by);
        }
        return s.toString();
    }

    /** Topic line shown under the title when the title is shown; "" otherwise. */
    static String binTopic(BinItem b) {
        return !b.title.isEmpty() && !b.topic.isEmpty() ? b.topic : "";
    }

    /** Trimmed result text, or null if it is not 1..300 characters. */
    static String validResult(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() || t.codePointCount(0, t.length()) > RESULT_MAX ? null : t;
    }

    /** "12/300" counter. */
    static String counter(String s) {
        String t = s == null ? "" : s.trim();
        return t.codePointCount(0, t.length()) + "/" + RESULT_MAX;
    }

    /** What to do after a prepare/commit that failed. */
    enum Next {
        /** Refresh the data and ask the user to start the action again. */
        REPREPARE,
        /** Show kmem's own message. */
        SHOW_MESSAGE,
        /** Try again later. */
        RETRY_LATER,
        UNAVAILABLE,
        CONTROL_OFF,
        UNLOCK,
        OTHER
    }

    static Next next(int code, String error) {
        String e = error == null ? "" : error;
        if (code == 403 && e.equals("control_off")) {
            return Next.CONTROL_OFF;
        }
        if (code == 401) {
            return Next.UNLOCK;
        }
        if (code == 429) {
            return Next.RETRY_LATER;
        }
        if (code == 503 || e.equals("kmem_unavailable")) {
            return Next.UNAVAILABLE;
        }
        if (code == 409) {
            switch (e) {
                case "stale":
                case "confirm_invalid":
                case "confirm_expired":
                    return Next.REPREPARE;
                case "kmem_refused":
                    return Next.SHOW_MESSAGE;
                case "kmem_busy":
                    return Next.RETRY_LATER;
                default:
                    return Next.OTHER;
            }
        }
        return Next.OTHER;
    }

    /** The line to show for a failed prepare/commit; message is kmem's own text (may be empty). */
    static String failText(int code, String error, String message) {
        switch (next(code, error)) {
            case REPREPARE:
                return "That changed in the meantime. The list was refreshed; start again.";
            case SHOW_MESSAGE:
                return message == null || message.isEmpty() ? "kmem refused this." : "kmem refused: " + message;
            case RETRY_LATER:
                return "kmem is busy or you are going too fast. Try again in a moment.";
            case UNAVAILABLE:
                return "kmem isn't available on Quill Desktop right now.";
            case CONTROL_OFF:
                return "Quill Desktop doesn't let paired devices make changes. Turn on \"Let paired devices control sessions\" in Quill Desktop settings.";
            case UNLOCK:
                return "Quill Desktop is locked. Unlock it and try again.";
            default:
                return code == 0 ? "Quill Desktop isn't reachable." : "Quill Desktop replied " + error + ".";
        }
    }

    /** Whether a failure means the shown data is stale and should be reloaded. */
    static boolean refreshAfter(int code, String error) {
        return next(code, error) == Next.REPREPARE;
    }

    static String actionTitle(String action) {
        switch (action) {
            case "done":
                return "Mark task done";
            case "release":
                return "Release task";
            case "restore":
                return "Restore from recycle bin";
            default:
                return action;
        }
    }

    /** The confirm step's body: summary, then the commands kmem will run. */
    static String confirmText(JSONObject prepare) {
        StringBuilder s = new StringBuilder(prepare.optString("summary"));
        JSONArray c = prepare.optJSONArray("commands");
        if (c != null && c.length() > 0) {
            s.append("\n\nkmem will run:");
            for (int i = 0; i < c.length(); i++) {
                s.append("\n  ").append(c.optString(i));
            }
        }
        return s.toString();
    }

    static final String NOTE_BIN = "Emptying the recycle bin is only possible on the PC.";
    static final String HINT_CONTROL_OFF = "Changes are off: turn on \"Let paired devices control sessions\" in Quill Desktop settings to use Done, Release and Restore.";

    /** Output of a commit, shortened for a toast. */
    static String brief(String output) {
        String o = output == null ? "" : output.trim();
        return Cut.ellipsize(o, 160, "...");
    }
}
