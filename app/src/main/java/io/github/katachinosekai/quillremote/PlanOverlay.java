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
        String head = Cut.head(b, 40);
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
     * array when the reply holds the plan's approval question (title) itself or is the plan's own text (Quill
     * Desktop's planOverlayQs); null when the reply can't be matched (the card is used then).
     */
    static int[][] matchPlan(String reply, int[] nums, String[] texts, String title, String planText) {
        if (reply == null || reply.trim().isEmpty()) {
            return null;
        }
        if (nums.length > 0) {
            return match(reply, nums, texts);
        }
        if (approvalSpan(reply, title) != null) {
            return new int[0][];
        }
        if (planText != null && planText.trim().equals(reply.trim())) {
            return new int[0][]; // the reply is the plan: the buttons close it
        }
        return null;
    }

    static int[][] matchPlan(String reply, int[] nums, String[] texts, String title) {
        return matchPlan(reply, nums, texts, title, null);
    }

    static final Pattern NUMBERED_Q = Pattern.compile("^\\s*(?:[-*]\\s*)?(?:\\*\\*)?\\d+[.)]\\s+");
    static final Pattern OR = Pattern.compile("\\bor\\b", Pattern.CASE_INSENSITIVE);

    /**
     * The reply's lines {first, last} that hold the approval question (title): its options may sit on the next line
     * ("Can I run it?\nYes (recommended) or no?", Quill Desktop's JoinOptionLineSpans); the last such place wins.
     * null if no line does.
     */
    static int[] approvalSpan(String text, String title) {
        String t = norm(title == null ? "" : title);
        if (text == null || t.isEmpty()) {
            return null;
        }
        String[] lines = text.split("\n", -1);
        java.util.List<String> joined = new java.util.ArrayList<>();
        java.util.List<Integer> first = new java.util.ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            int n = joined.size();
            if (n > 0 && !l.isEmpty() && !NUMBERED_Q.matcher(l).find() && OR.matcher(l).find() && l.split("\\s+").length <= 30) {
                String prev = joined.get(n - 1).trim().replaceAll("[*_ ]+$", "");
                if (prev.endsWith("?")) {
                    joined.set(n - 1, prev + " " + l);
                    continue;
                }
            }
            joined.add(lines[i]);
            first.add(i);
        }
        for (int i = joined.size() - 1; i >= 0; i--) {
            String n = norm(joined.get(i));
            if (!n.isEmpty() && n.contains(t)) {
                int end = i + 1 < first.size() ? first.get(i + 1) - 1 : lines.length - 1;
                while (end > first.get(i) && lines[end].trim().isEmpty()) {
                    end--;
                }
                return new int[]{first.get(i), end};
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

    /** The note above the approval question: what the go-ahead does (Quill Desktop's approvalCaption). */
    static String approvalCaption(java.util.List<String> options, boolean[] stop, boolean otherQuestions) {
        String go = "Yes";
        for (int i = 0; i < options.size(); i++) {
            if (i >= stop.length || !stop[i]) {
                go = options.get(i);
                break;
            }
        }
        if (go.length() > 40) {
            go = go.substring(0, 39).trim() + "\u2026";
        }
        return "Answering \"" + go + "\" runs this plan: every command and change, including its background jobs and subagents, is approved until the plan is done."
                + (otherQuestions ? " Unanswered questions are sent as \"no answer\"." : "");
    }
}
