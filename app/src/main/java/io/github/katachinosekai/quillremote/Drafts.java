package io.github.katachinosekai.quillremote;

import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

/** Unsent message-box text per chat, with the caret/selection, kept in prefs so it survives the app being backgrounded. */
final class Drafts {
    static final String PREFIX = "draft.";
    static final int MAX_CHARS = 20000;

    static final class Draft {
        final String text;
        final int start;
        final int end;

        Draft(String text, int start, int end) {
            this.text = text;
            this.start = Math.max(0, Math.min(start, text.length()));
            this.end = Math.max(0, Math.min(end, text.length()));
        }
    }

    private final SharedPreferences prefs;

    Drafts(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    /** Remember a chat's draft; an empty one is dropped. Over-long text is cut (the caret clamps with it). */
    void save(String id, String text, int start, int end) {
        if (id == null || id.isEmpty()) {
            return;
        }
        if (text == null || text.isEmpty()) {
            clear(id);
            return;
        }
        if (text.length() > MAX_CHARS) {
            text = text.substring(0, MAX_CHARS);
        }
        int a = Math.max(0, Math.min(start, text.length()));
        int b = Math.max(0, Math.min(end, text.length()));
        prefs.edit().putString(PREFIX + id, Math.min(a, b) + "," + Math.max(a, b) + "," + text).apply();
    }

    Draft load(String id) {
        String v = id == null ? null : prefs.getString(PREFIX + id, null);
        if (v == null) {
            return null;
        }
        int c1 = v.indexOf(',');
        int c2 = c1 < 0 ? -1 : v.indexOf(',', c1 + 1);
        if (c2 < 0) {
            return null;
        }
        try {
            return new Draft(v.substring(c2 + 1), Integer.parseInt(v.substring(0, c1)), Integer.parseInt(v.substring(c1 + 1, c2)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    void clear(String id) {
        if (id != null && prefs.contains(PREFIX + id)) {
            prefs.edit().remove(PREFIX + id).apply();
        }
    }

    /** Forget the drafts of chats that no longer exist. */
    void prune(Set<String> live) {
        SharedPreferences.Editor e = null;
        for (Map.Entry<String, ?> en : new ArrayList<>(prefs.getAll().entrySet())) {
            String k = en.getKey();
            if (k.startsWith(PREFIX) && !live.contains(k.substring(PREFIX.length()))) {
                e = e == null ? prefs.edit() : e;
                e.remove(k);
            }
        }
        if (e != null) {
            e.apply();
        }
    }
}
