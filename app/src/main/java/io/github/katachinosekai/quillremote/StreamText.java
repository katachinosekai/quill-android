package io.github.katachinosekai.quillremote;

/**
 * Streaming replies formatted while they arrive, ported from Quill Desktop's cmd/quill/streamtext.go (streamCut) and
 * chatview.go (closeOpenMarkdown): finished paragraphs end at the last blank line outside a code fence; the growing
 * last paragraph is drawn with its open markup closed for now.
 */
final class StreamText {
    private StreamText() {
    }

    /** Quill Desktop's splitParas: paragraphs cut at blank lines outside code fences; the last part is still growing (can be empty). */
    static java.util.List<String> split(String text, StringBuilder live) {
        java.util.List<String> done = new java.util.ArrayList<>();
        boolean inFence = false;
        int start = 0, pos = 0;
        for (String l : text.split("(?<=\n)", -1)) {
            if (l.isEmpty()) {
                continue;
            }
            if (l.trim().startsWith("```")) {
                inFence = !inFence;
            }
            pos += l.length();
            if (!inFence && l.trim().isEmpty() && !text.substring(pos).trim().isEmpty() && !betweenListItems(text, pos - l.length(), pos)) {
                String p = text.substring(start, pos).trim();
                if (!p.isEmpty()) {
                    done.add(p);
                }
                start = pos;
            }
        }
        if (live != null) {
            live.setLength(0);
            live.append(text.substring(start).replaceAll("^\n+", ""));
        }
        return done;
    }

    /**
     * A blank line between two list items (bullet, numbered, or lettered) doesn't end a paragraph: the items stay one
     * list (one column when centered). While the reply streams, the next item may not be there yet; the view then
     * redraws its paragraphs once when it arrives (Quill Desktop 1b23803).
     */
    static boolean betweenListItems(String text, int blankStart, int blankEnd) {
        int prevEnd = blankStart;
        while (prevEnd > 0 && text.charAt(prevEnd - 1) == '\n') {
            prevEnd--;
        }
        int prevStart = text.lastIndexOf('\n', prevEnd - 1) + 1;
        String prev = prevEnd > prevStart ? text.substring(prevStart, prevEnd) : "";
        int nextStart = blankEnd;
        while (nextStart < text.length() && text.charAt(nextStart) == '\n') {
            nextStart++;
        }
        int nextEnd = text.indexOf('\n', nextStart);
        String next = text.substring(nextStart, nextEnd < 0 ? text.length() : nextEnd);
        return listItem(prev) && listItem(next);
    }

    static boolean listItem(String line) {
        return (Markdown.BULLET.matcher(line).matches() && !Markdown.RULE.matcher(line).matches())
                || Markdown.NUMBERED.matcher(line).matches() || Markdown.LETTERED.matcher(line).matches();
    }

    static final int REVEAL_MIN = 2, REVEAL_CATCH_UP = 12, REVEAL_MAX = 24;

    /**
     * Text animation speed (rescaled 2026-11-23, even Slow felt fast): each speed is a fraction of the old Normal pace
     * (2..24 characters a frame, faster when far behind). Retune a speed by changing its one constant.
     */
    static final double SCALE_FAST = 1.0 / 2, SCALE_NORMAL = 1.0 / 4, SCALE_SLOW = 1.0 / 8;

    /** Characters per frame (can be below one) with gap characters still hidden; Instant: all of them. */
    static double rate(String speed, int gap) {
        double scale;
        switch (speed(speed)) {
            case "slow":
                scale = SCALE_SLOW;
                break;
            case "fast":
                scale = SCALE_FAST;
                break;
            case "instant":
                return gap;
            default:
                scale = SCALE_NORMAL;
                break;
        }
        return Math.min(Math.max(REVEAL_MIN, gap / REVEAL_CATCH_UP), REVEAL_MAX) * scale;
    }

    /** ⚙ Text animation speeds (see rate): Slow 1/8, Normal 1/4 (the default), Fast 1/2 of the old Normal, Instant shows everything. */
    static final String[] SPEEDS = {"slow", "normal", "fast", "instant"};

    /** The stored value for anything unknown or missing is normal. */
    static String speed(String s) {
        for (String k : SPEEDS) {
            if (k.equals(s)) {
                return k;
            }
        }
        return "normal";
    }

    /** "normal" -> "Normal". */
    static String speedLabel(String s) {
        s = speed(s);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** The speed after tapping: Slow, Normal, Fast, Instant, Slow... */
    static String nextSpeed(String s) {
        return SPEEDS[(java.util.Arrays.asList(SPEEDS).indexOf(speed(s)) + 1) % SPEEDS.length];
    }

    /**
     * Next reveal position (Quill Desktop's revealLoop) after step characters (at least 1), extended to the end of the
     * word (if that's within 24), the rest at once when a finished reply has no more spaces, never inside a surrogate
     * pair. The caller keeps the fractions: see StreamView.tick.
     */
    static int revealBy(String text, int pos, boolean streaming, int step) {
        if (pos >= text.length()) {
            return text.length();
        }
        int max = REVEAL_MAX;
        int n = Math.min(pos + Math.max(1, step), text.length());
        int sp = -1;
        for (int i = n; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == ' ' || ch == '\n') {
                sp = i - n;
                break;
            }
        }
        if (sp >= 0 && sp <= max) {
            n += sp;
        } else if (sp < 0 && !streaming) {
            n = text.length();
        }
        while (n < text.length() && Character.isLowSurrogate(text.charAt(n))) {
            n++;
        }
        return n;
    }

    /** Close an open code fence, inline code span or bold run at the end of s, so a half-written paragraph renders. */
    static String closeOpen(String s) {
        int fences = 0;
        for (String l : s.split("\n", -1)) {
            if (l.trim().startsWith("```")) {
                fences++;
            }
        }
        if (fences % 2 == 1) {
            return s + "\n```";
        }
        String last = s.substring(s.lastIndexOf("\n\n") + 1);
        String add = "";
        if (count(last, "`") % 2 == 1) {
            add = "`";
        }
        StringBuilder outside = new StringBuilder();
        String[] parts = (last + add).split("`", -1);
        for (int i = 0; i < parts.length; i += 2) {
            outside.append(parts[i]);
        }
        if (count(outside.toString(), "**") % 2 == 1) {
            if (add.isEmpty() && s.endsWith("**")) {
                return s.substring(0, s.length() - 2);
            }
            add += "**";
        }
        return s + add;
    }

    static int count(String s, String sub) {
        int n = 0;
        for (int i = s.indexOf(sub); i >= 0; i = s.indexOf(sub, i + sub.length())) {
            n++;
        }
        return n;
    }

}
