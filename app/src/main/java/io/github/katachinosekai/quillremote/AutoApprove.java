package io.github.katachinosekai.quillremote;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Quill Desktop's per-tab auto-approve (phone API: Session.autoApprove, approvePlan keepApproving,
 * POST /phone/session/{id}/auto-approve-off). Pure helpers, no Android classes.
 */
final class AutoApprove {
    private AutoApprove() {
    }

    static final String CHECK = "Keep approving in this tab until I turn it off";
    static final String CHIP = "✓ Auto-approve on ×";
    static final String OFF_PATH = "auto-approve-off";
    /** Quill Desktop's first release with auto-approve (the field is left out when off, so the version says it). */
    static final int[] FIRST = {0, 1, 26};

    /** The session's auto-approve is on (the field is only present, as true, while it is on). */
    static boolean on(JSONObject session) {
        return session != null && on(session.opt("autoApprove"));
    }

    /** The field's value: only a true boolean counts (absent = off or an older Quill Desktop). */
    static boolean on(Object field) {
        return Boolean.TRUE.equals(field);
    }

    /**
     * Whether this Quill Desktop has auto-approve: seen on a session already, or its version (hello "quill") is at
     * least FIRST ("dev" local builds count). Older Quill Desktop: false, so nothing about it is shown.
     */
    static boolean supported(String deskVersion, boolean seenField) {
        if (seenField) {
            return true;
        }
        if (deskVersion == null) {
            return false;
        }
        String v = deskVersion.trim();
        if (v.startsWith("v")) {
            v = v.substring(1);
        }
        if (v.equals("dev")) {
            return true;
        }
        String[] parts = v.split("\\.");
        if (parts.length < 2) {
            return false;
        }
        for (int i = 0; i < FIRST.length; i++) {
            int n = i < parts.length ? lead(parts[i]) : 0;
            if (n < 0) {
                return false;
            }
            if (n != FIRST[i]) {
                return n > FIRST[i];
            }
        }
        return true;
    }

    private static int lead(String s) {
        int e = 0;
        while (e < s.length() && Character.isDigit(s.charAt(e)) && e < 6) {
            e++;
        }
        return e == 0 ? -1 : Integer.parseInt(s.substring(0, e));
    }

    /** keepApproving goes with an approvePlan go-ahead only: box ticked, feature offered, never on a refusal. */
    static boolean sendKeep(boolean ticked, boolean supported, String action) {
        return ticked && supported && "approvePlan".equals(action);
    }

    /** Adds keepApproving: true to an approvePlan body only when sendKeep says so. */
    static JSONObject withKeep(JSONObject body, boolean ticked) {
        if (ticked) {
            try {
                body.put("keepApproving", true);
            } catch (JSONException ignored) {
            }
        }
        return body;
    }

    /** The session list's marker, or "" when off. */
    static String listMark(boolean on) {
        return on ? "✓ auto-approve" : "";
    }
}
