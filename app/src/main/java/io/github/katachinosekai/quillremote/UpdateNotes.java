package io.github.katachinosekai.quillremote;

import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * After an update (user rule 2026-11-22, same as Quill Desktop): settings are kept silently; only new, changed or
 * removed features that need the user's input ask, once each, in one dialog with the recommended choice picked;
 * security changes that need no input get a one-time notice; "Later" asks again at the next start and applies
 * nothing; a downgrade never clears answers (they are kept by id, never by version). A changed default only applies
 * to settings the user never set (userSet).
 */
final class UpdateNotes {
    static final String LAST = "updates.lastCode";
    static final String DONE = "updates.done";
    static final String USER_SET = "prefs.userSet";

    /** One entry: shown when updating from a version below since. kind "notice" (no input) or "question". */
    static final class Entry {
        final String id;
        final int since;
        final String kind;
        final String title;
        final String text;
        final String[] choices;
        final int recommended;

        Entry(String id, int since, String kind, String title, String text, String[] choices, int recommended) {
            this.id = id;
            this.since = since;
            this.kind = kind;
            this.title = title;
            this.text = text;
            this.choices = choices;
            this.recommended = recommended;
        }
    }

    /** This app's entries, oldest first. */
    static final List<Entry> ENTRIES = List.of(
            new Entry("lanScreenLock", 41, "notice", "Home-network unlock needs your screen lock",
                    "Quill Android 0.9.26 can unlock Quill Desktop without its password on your home network, once you "
                            + "switch that on for this phone in Quill Desktop → Devices. By default the phone's key only works "
                            + "right after you unlock your phone, so someone with your phone on your Wi-Fi still can't get in. "
                            + "You can change this in ⚙ → Home network.", null, -1),
            new Entry("pairingAsks", 43, "notice", "Safer pairing and approvals",
                    "Quill Android 0.9.28: pairing links now ask before pairing, and Allow/Deny for approvals "
                            + "needs an unlocked phone. Nothing to do.", null, -1));

    private UpdateNotes() {
    }

    /**
     * Limits for Quill Desktop's questions in the after-update dialog (security review f436bdd): more questions than
     * MAX_QUESTIONS wait for the next start, a question with no or more than MAX_CHOICES choices is shown but left
     * for Quill Desktop to answer, and long texts are cut.
     */
    static final int MAX_QUESTIONS = 20;
    static final int MAX_CHOICES = 10;
    static final int MAX_TITLE = 200;
    static final int MAX_TEXT = 2000;
    static final int MAX_CHOICE = 300;

    /** What the dialog does with one of Quill Desktop's questions. */
    enum DeskAction { NOTICE, CHOOSE, DESKTOP_ONLY }

    /** A notice is acknowledged without a choice; a question needs 1..MAX_CHOICES choices, else Quill Desktop answers it. */
    static DeskAction deskAction(String kind, int choices) {
        if ("notice".equalsIgnoreCase(kind)) {
            return DeskAction.NOTICE;
        }
        return choices >= 1 && choices <= MAX_CHOICES ? DeskAction.CHOOSE : DeskAction.DESKTOP_ONLY;
    }

    /** The recommended choice when it names one of the n choices, else the first. */
    static int recommended(int rec, int n) {
        return rec >= 0 && rec < n ? rec : 0;
    }

    static String clip(String s, int n) {
        s = s == null ? "" : s.trim();
        return Cut.ellipsize(s, n, "…");
    }

    /**
     * Entries to show now: those added after the version the user last finished with (lastCode), not done yet.
     * A fresh install (no lastCode yet) shows none: the defaults are simply the defaults.
     */
    static List<Entry> pending(int lastCode, int current, Set<String> done) {
        List<Entry> out = new ArrayList<>();
        if (lastCode <= 0) {
            return out;
        }
        for (Entry e : ENTRIES) {
            if (e.since > lastCode && e.since <= current && !done.contains(e.id)) {
                out.add(e);
            }
        }
        return out;
    }

    static List<Entry> pending(SharedPreferences p, int current) {
        return pending(p.getInt(LAST, 0), current, p.getStringSet(DONE, new HashSet<>()));
    }

    /** First start: remember the version so later updates know where they came from. */
    static void firstRun(SharedPreferences p, int current) {
        if (p.getInt(LAST, 0) <= 0) {
            p.edit().putInt(LAST, current).apply();
        }
    }

    /** Everything shown was answered or seen: mark done and move lastCode forward (never backwards on a downgrade). */
    static void finished(SharedPreferences p, List<Entry> shown, int current) {
        Set<String> done = new HashSet<>(p.getStringSet(DONE, new HashSet<>()));
        for (Entry e : shown) {
            done.add(e.id);
        }
        p.edit().putStringSet(DONE, done).putInt(LAST, Math.max(p.getInt(LAST, 0), current)).apply();
    }

    /** The user changed this setting themselves: a changed default never overrides it. */
    static void userSet(SharedPreferences p, String key) {
        Set<String> s = new HashSet<>(p.getStringSet(USER_SET, new HashSet<>()));
        if (s.add(key)) {
            p.edit().putStringSet(USER_SET, s).apply();
        }
    }

    /** A new default for key: applies only when the user never set it. */
    static void changedDefault(SharedPreferences p, String key, boolean value) {
        if (!p.getStringSet(USER_SET, new HashSet<>()).contains(key)) {
            p.edit().putBoolean(key, value).apply();
        }
    }
}
