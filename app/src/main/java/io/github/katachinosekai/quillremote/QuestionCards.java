package io.github.katachinosekai.quillremote;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure helpers for Quill Android's one-card-per-question-set rule: which reply loses its quick-choice buttons, how a
 * plan's request.approval and request.questions are read (as plain Maps and Lists), when a card has changed, and the
 * answer bodies (docs/phone-api.md: approvePlan with approvalChoice and planAnswers, rejectPlan with text).
 */
final class QuestionCards {
    private QuestionCards() {
    }

    /** The pick for the card's own "Don't run" (Quill Desktop's dontRunPick). */
    static final int DONT_RUN = -2;
    /** A button set that just appeared or changed ignores taps this long. */
    static final long GUARD_MS = 600;

    /** request.approval: the approval question's own options. */
    static final class Approval {
        final List<String> options;
        final boolean[] stop;
        final int recommended;
        final boolean dontRun;

        Approval(List<String> options, boolean[] stop, int recommended, boolean dontRun) {
            this.options = options;
            this.stop = stop;
            this.recommended = recommended;
            this.dontRun = dontRun;
        }

        String key() {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < options.size(); i++) {
                b.append(options.get(i)).append(stop[i] ? "!" : "").append('/');
            }
            return b.append(recommended).append(dontRun ? "|dontRun" : "").toString();
        }
    }

    /** One of the plan's other numbered questions (request.questions). */
    static final class Question {
        final String id;
        final String text;
        final String kind;
        final List<String> options;
        final int num;

        Question(String id, String text, String kind, List<String> options, int num) {
            this.id = id;
            this.text = text;
            this.kind = kind;
            this.options = options;
            this.num = num;
        }
    }

    /** request.approval as a Map; null when absent or without options (older Quill Desktop builds). */
    static Approval parseApproval(Object plain) {
        if (!(plain instanceof Map)) {
            return null;
        }
        Map<?, ?> m = (Map<?, ?>) plain;
        List<String> opts = strings(m.get("options"));
        if (opts.isEmpty()) {
            return null;
        }
        boolean[] stop = new boolean[opts.size()];
        if (m.get("stop") instanceof List) {
            List<?> s = (List<?>) m.get("stop");
            for (int i = 0; i < stop.length && i < s.size(); i++) {
                stop[i] = Boolean.TRUE.equals(s.get(i));
            }
        }
        int rec = m.get("recommended") instanceof Number ? ((Number) m.get("recommended")).intValue() : -1;
        if (rec < 0 || rec >= opts.size()) {
            rec = -1;
        }
        return new Approval(opts, stop, rec, Boolean.TRUE.equals(m.get("dontRun")));
    }

    /** request.questions as a List of Maps; entries without an id are skipped. */
    static List<Question> parseQuestions(Object plain) {
        List<Question> out = new ArrayList<>();
        if (!(plain instanceof List)) {
            return out;
        }
        List<?> l = (List<?>) plain;
        for (int i = 0; i < l.size(); i++) {
            if (!(l.get(i) instanceof Map)) {
                continue;
            }
            Map<?, ?> q = (Map<?, ?>) l.get(i);
            if (q.get("id") == null) {
                continue;
            }
            int num = q.get("num") instanceof Number && ((Number) q.get("num")).intValue() > 0 ? ((Number) q.get("num")).intValue() : i + 1;
            out.add(new Question(String.valueOf(q.get("id")), q.get("text") == null ? "" : String.valueOf(q.get("text")),
                    q.get("kind") == null ? "text" : String.valueOf(q.get("kind")), strings(q.get("options")), num));
        }
        return out;
    }

    private static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List) {
            for (Object x : (List<?>) o) {
                out.add(x == null ? "" : String.valueOf(x));
            }
        }
        return out;
    }

    /**
     * The assistant items that must not show quick-choice buttons: every one in a turn (the stretch between user
     * messages) that has a pending plan card, so the plan's card is the only one for its question set.
     */
    static Set<Integer> quickSuppressed(List<String> kinds, List<Boolean> pendingPlan) {
        Set<Integer> out = new HashSet<>();
        int from = 0;
        for (int i = 0; i <= kinds.size(); i++) {
            if (i < kinds.size() && !"user".equals(kinds.get(i))) {
                continue;
            }
            boolean plan = false;
            for (int j = from; j < i; j++) {
                plan |= "plan".equals(kinds.get(j)) && Boolean.TRUE.equals(pendingPlan.get(j));
            }
            for (int j = from; plan && j < i; j++) {
                if ("assistant".equals(kinds.get(j))) {
                    out.add(j);
                }
            }
            from = i + 1;
        }
        return out;
    }

    /** What makes a pending plan card look different; equal keys mean the card stays as it is (picks are kept). */
    static String setKey(String planText, Approval a, List<Question> qs) {
        StringBuilder b = new StringBuilder();
        b.append(planText == null ? 0 : planText.hashCode()).append('|').append(a == null ? "-" : a.key());
        for (Question q : qs) {
            b.append('|').append(q.id).append(':').append(q.num).append(':').append(q.kind).append(':').append(q.text.hashCode()).append(':').append(q.options);
        }
        return b.toString();
    }

    /** Answers worth sending: drops the card's own bookkeeping keys (seen:, approval:, changes:). */
    static Map<String, Object> cleanAnswers(Map<String, Object> ans) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : ans.entrySet()) {
            String k = e.getKey();
            if (k.startsWith("seen:") || k.startsWith("approval:") || k.startsWith("changes:") || e.getValue() == null) {
                continue;
            }
            out.put(k, e.getValue());
        }
        return out;
    }

    /** The phone API action for a pick: rejectPlan for the card's own Don't run, else approvePlan. */
    static String action(int pick) {
        return pick == DONT_RUN ? "rejectPlan" : "approvePlan";
    }

    /**
     * The extra fields for answering with pick (an index into approval.options, or DONT_RUN). approvePlan carries
     * approvalChoice and planAnswers (a stop option acts as rejectPlan on Quill Desktop and still sends them);
     * rejectPlan takes no planAnswers, so Don't run puts the answers into its text, after the user's own text.
     */
    static Map<String, Object> body(Approval a, int pick, List<Question> qs, Map<String, Object> answers, String text) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> clean = cleanAnswers(answers);
        String t = text == null ? "" : text.trim();
        if (pick == DONT_RUN) {
            String d = describe(qs, clean);
            String all = t.isEmpty() ? d : d.isEmpty() ? t : t + "\n" + d;
            if (!all.isEmpty()) {
                out.put("text", all);
            }
            return out;
        }
        out.put("approvalChoice", pick);
        if (!clean.isEmpty()) {
            out.put("planAnswers", clean);
        }
        if (!t.isEmpty()) {
            out.put("text", t);
        }
        return out;
    }

    /**
     * The extra fields of an approvePlan go-ahead without the approval question's own buttons: the optional quick
     * choice (an index into request.choices, or null) and the card's "Changes (optional)" text, which Quill Desktop
     * sends to Claude with the go-ahead.
     */
    static Map<String, Object> goAheadExtras(Integer choice, String text) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (choice != null) {
            out.put("choice", choice);
        }
        String t = text == null ? "" : text.trim();
        if (!t.isEmpty()) {
            out.put("text", t);
        }
        return out;
    }

    /** "Answers to your questions: 1. text: answer; ..." for the questions that have an answer ("" if none). */
    static String describe(List<Question> qs, Map<String, Object> answers) {
        List<String> parts = new ArrayList<>();
        for (Question q : qs) {
            Object v = answers.get(q.id);
            if (v == null) {
                continue;
            }
            String s;
            if (v instanceof Collection) {
                List<String> names = new ArrayList<>();
                for (Object x : (Collection<?>) v) {
                    names.add(option(q, x));
                }
                s = String.join(", ", names);
            } else {
                s = option(q, v);
            }
            parts.add(q.num + ". " + q.text + ": " + s);
        }
        return parts.isEmpty() ? "" : "Answers to your questions: " + String.join("; ", parts);
    }

    private static String option(Question q, Object v) {
        if (v instanceof Number) {
            int i = ((Number) v).intValue();
            return i >= 0 && i < q.options.size() ? q.options.get(i) : String.valueOf(i);
        }
        return String.valueOf(v);
    }

    /** Taps are ignored for GUARD_MS after the buttons appeared or changed. */
    static final class ClickGuard {
        final long shownAt;

        ClickGuard(long now) {
            shownAt = now;
        }

        boolean allows(long now) {
            return now - shownAt >= GUARD_MS;
        }
    }
}
