package io.github.katachinosekai.quillremote;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plan card overlay (Quill Desktop 596036f, 2026-11-22): when a pending plan's reply ends with its numbered questions,
 * each question line in the reply gets its control right under it and the card's caption and buttons close the
 * reply; there is no separate card. Anything that doesn't line up falls back to the card. This finds the lines.
 */
final class PlanOverlay {
    static final Pattern NUMBERED = Pattern.compile("^\\s*(?:[-*+]\\s+)?(\\d{1,3})[.)]\\s+(.*)$");

    private PlanOverlay() {
    }

    /** Is a plan with this request.planState current (its buttons answer it)? Older builds send none. */
    static boolean active(String planState) {
        return planState == null || planState.isEmpty() || "active".equals(planState);
    }

    static String norm(String s) {
        String t = s.replace("**", "").replace("__", "").replace("`", "").replaceAll("^[#>*\\s]+", "")
                .replace("(recommended)", "").toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        return t;
    }

    /** Does a reply line's text (after its number) say the same question as the card's text? */
    static boolean same(String line, String question) {
        String a = norm(line), b = norm(question);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        // The question can follow a lead-in sentence on its line (Quill Desktop sends the question's own sentence).
        String head = b.length() > 40 ? b.substring(0, 40) : b;
        return head.length() >= 8 && a.contains(head);
    }

    /**
     * Each question's lines in the reply as {start, end} line indexes (end exclusive; a question runs until a blank
     * line or the next numbered line), in order; null when any question isn't found or they are out of order. A
     * question the plan gives no number is found by its text alone.
     */
    static int[][] match(String reply, int[] nums, String[] texts) {
        if (reply == null || nums.length == 0 || nums.length != texts.length) {
            return null;
        }
        String[] lines = reply.split("\n", -1);
        // Lines inside a fenced code block are code, not questions.
        boolean[] code = new boolean[lines.length];
        boolean fence = false;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().startsWith("```")) {
                fence = !fence;
                code[i] = true;
            } else {
                code[i] = fence;
            }
        }
        int[][] out = new int[nums.length][];
        int from = 0;
        for (int q = 0; q < nums.length; q++) {
            int found = -1;
            // Two questions on one numbered line share its lines.
            if (q > 0 && nums[q] > 0 && nums[q] == nums[q - 1]) {
                Matcher sm = NUMBERED.matcher(lines[out[q - 1][0]].replace("**", ""));
                if (sm.matches() && same(lines[out[q - 1][0]], texts[q])) {
                    out[q] = out[q - 1];
                    continue;
                }
            }
            for (int i = from; i < lines.length && found < 0; i++) {
                if (code[i]) {
                    continue;
                }
                Matcher m = NUMBERED.matcher(lines[i].replace("**", ""));
                if (nums[q] > 0) {
                    if (m.matches() && Integer.parseInt(m.group(1)) == nums[q] && same(m.group(2), texts[q])) {
                        found = i;
                    }
                } else if (same(m.matches() ? m.group(2) : lines[i].replace("**", "").replaceAll("^\\s*[-*+]\\s+", ""), texts[q])) {
                    found = i; // an unnumbered question: found by its text alone
                }
            }
            if (found < 0) {
                return null;
            }
            int end = found + 1;
            while (end < lines.length && !code[end] && !lines[end].trim().isEmpty() && !NUMBERED.matcher(lines[end].replace("**", "")).matches()) {
                end++;
            }
            out[q] = new int[]{found, end};
            from = end;
        }
        return out;
    }

    /**
     * The overlay's match for a plan: its questions' lines (see match), or for a plan without questions an empty
     * array when the reply holds the plan's approval question (title) itself; null when the reply can't be matched
     * (the card is used then).
     */
    static int[][] matchPlan(String reply, int[] nums, String[] texts, String title) {
        if (reply == null || reply.trim().isEmpty()) {
            return null;
        }
        if (nums.length > 0) {
            return match(reply, nums, texts);
        }
        String t = norm(title);
        if (t.isEmpty()) {
            return null;
        }
        for (String line : reply.split("\n", -1)) {
            if (norm(line).contains(t)) {
                return new int[0][];
            }
        }
        return null;
    }

    /** Lines [from, to) of text joined back. */
    static String lines(String text, int from, int to) {
        String[] l = text.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, from); i < Math.min(to, l.length); i++) {
            sb.append(i > from ? "\n" : "").append(l[i]);
        }
        return sb.toString();
    }
}
