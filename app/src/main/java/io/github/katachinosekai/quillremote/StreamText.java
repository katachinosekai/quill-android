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
        if (openFence(s)) {
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

    /** True when s ends inside a code fence. */
    static boolean openFence(String s) {
        int fences = 0;
        for (String l : s.split("\n", -1)) {
            if (l.trim().startsWith("```")) {
                fences++;
            }
        }
        return fences % 2 == 1;
    }

    static int count(String s, String sub) {
        int n = 0;
        for (int i = s.indexOf(sub); i >= 0; i = s.indexOf(sub, i + sub.length())) {
            n++;
        }
        return n;
    }


    /** Overlay controls and fallback cards fade in over this long when they first appear (under the 600 ms tap guard). */
    static final long FADE_IN_MS = 200;
    /** A newly revealed character goes from transparent to full over this long. */
    static final long CHAR_FADE_MS = 150;
    /** The fade is drawn in this many alpha steps, so one run of equal steps is one span. */
    static final int FADE_STEPS = 8;

    /**
     * Reveal times of the characters of the growing paragraph (as laid out, markup gone), to fade each new character
     * in. Pure bookkeeping: it never changes when or how many characters are revealed.
     */
    static final class Fade {
        private long[] at = new long[64];
        private int len;

        /** The paragraph now has n characters at time now: new ones start fading, a shorter text drops the rest. */
        void grew(int n, long now) {
            if (n < len) {
                len = n;
                return;
            }
            if (n > at.length) {
                at = java.util.Arrays.copyOf(at, Math.max(n, at.length * 2));
            }
            for (; len < n; len++) {
                at[len] = now;
            }
        }

        /** Alpha step 0..FADE_STEPS of character i (FADE_STEPS = fully shown). */
        int step(int i, long now) {
            if (i < 0 || i >= len) {
                return FADE_STEPS;
            }
            long age = now - at[i];
            if (age >= CHAR_FADE_MS) {
                return FADE_STEPS;
            }
            return age <= 0 ? 0 : (int) (age * FADE_STEPS / CHAR_FADE_MS);
        }

        /** Index of the first character that is still fading (len when none): everything before is fully shown. */
        int firstFading(long now) {
            int lo = 0, hi = len; // times never decrease, so the fading ones are a suffix
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (now - at[mid] >= CHAR_FADE_MS) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            return lo;
        }

        boolean active(long now) {
            return firstFading(now) < len;
        }

        int length() {
            return len;
        }
    }

    /** A last line that may still become a block marker ("#", "-", "1.", "``"): hidden while the reply streams. */
    static final java.util.regex.Pattern PARTIAL_MARKER = java.util.regex.Pattern.compile("^ {0,3}(#{1,6}|[-*+_~`]{1,2}|\\d{1,9}[.)]?)\\s*$");

    /**
     * The reveal counts rendered (visible) characters, not source characters: markers (**, *, `, #, list markers, fences,
     * the "](url)" of a link) are never counted. view(n) is the Markdown to draw for the first n visible characters: the
     * source up to the n-th one, plus the closing markers its open constructs need, so bold, italics, code, links and
     * headings show formatted from their first letter and no raw marker shows. While streaming, a marker with no closer
     * yet stays hidden (it is not counted) until it closes. A finished reply shows its text unchanged at the end.
     */
    static final class Reveal {
        private static final class Blk {
            final StringBuilder body = new StringBuilder();
            final java.util.List<Integer> src = new java.util.ArrayList<>();
            boolean plainText; // code and tables: every character shown as is

            void add(String text, int from, int to, int sepSrc) {
                while (from < to && Character.isWhitespace(text.charAt(from))) {
                    from++;
                }
                while (to > from && Character.isWhitespace(text.charAt(to - 1))) {
                    to--;
                }
                if (sepSrc >= 0 && body.length() > 0) {
                    body.append('\n');
                    src.add(sepSrc);
                }
                for (int k = from; k < to; k++) {
                    body.append(text.charAt(k));
                    src.add(k);
                }
            }
        }

        final String text;
        final boolean streaming;
        /** The visible characters in order (a newline between blocks), for word-boundary steps. */
        final String visible;
        private final int[] vsrc;
        private final java.util.List<int[]> real = new java.util.ArrayList<>(); // {last opener char, closer start, closer end} in text
        private final java.util.List<int[]> pend = new java.util.ArrayList<>(); // {opener}
        private final java.util.List<String> pendClose = new java.util.ArrayList<>();

        Reveal(String text, boolean streaming) {
            this.text = text;
            this.streaming = streaming;
            java.util.List<Blk> blocks = blocks(text, streaming);
            StringBuilder vis = new StringBuilder();
            java.util.List<Integer> vs = new java.util.ArrayList<>();
            for (int bi = 0; bi < blocks.size(); bi++) {
                Blk b = blocks.get(bi);
                String body = b.body.toString();
                Markdown.Shown sh = new Markdown.Shown(body.length());
                if (b.plainText) {
                    java.util.Arrays.fill(sh.vis, true);
                } else {
                    Markdown.inline(body, 0, new java.util.ArrayList<>(), sh, 0, streaming && bi == blocks.size() - 1);
                }
                if (bi > 0 && !vs.isEmpty() && body.length() > 0) {
                    vis.append('\n');
                    vs.add(vs.get(vs.size() - 1));
                }
                for (int k = 0; k < body.length(); k++) {
                    if (sh.vis[k]) {
                        vis.append(body.charAt(k));
                        vs.add(b.src.get(k));
                    }
                }
                for (int[] c : sh.closers) {
                    real.add(new int[]{b.src.get(c[0] - 1), b.src.get(c[1]), b.src.get(c[2] - 1) + 1});
                }
                for (int k = 0; k < sh.pendingAt.size(); k++) {
                    pend.add(new int[]{b.src.get(sh.pendingAt.get(k))});
                    pendClose.add(sh.pendingClose.get(k));
                }
            }
            real.sort((x, y) -> Integer.compare(x[1], y[1]));
            visible = vis.toString();
            vsrc = new int[vs.size()];
            for (int k = 0; k < vsrc.length; k++) {
                vsrc[k] = vs.get(k);
            }
        }

        /** Number of visible characters. */
        int total() {
            return vsrc.length;
        }

        /** Length of the source shown for the first n visible characters. */
        int cut(int n) {
            if (n >= vsrc.length && !streaming) {
                return text.length();
            }
            return n <= 0 ? 0 : vsrc[Math.min(n, vsrc.length) - 1] + 1;
        }

        /** The Markdown to draw for the first n visible characters (see the class comment). */
        String view(int n) {
            if (n >= vsrc.length && !streaming) {
                return text;
            }
            int cut = cut(n);
            if (cut == 0) {
                return "";
            }
            StringBuilder tail = new StringBuilder();
            for (int[] r : real) {
                if (r[0] < cut && r[1] >= cut) {
                    tail.append(text, r[1], r[2]);
                }
            }
            for (int k = pend.size() - 1; k >= 0; k--) {
                if (pend.get(k)[0] < cut) {
                    tail.append(pendClose.get(k));
                }
            }
            StringBuilder sb = new StringBuilder(text.substring(0, cut));
            if (tail.length() > 0) {
                // A closing marker only closes after a non-space: the trailing space shows with the next letter.
                while (sb.length() > 0 && Character.isWhitespace(sb.charAt(sb.length() - 1))) {
                    sb.setLength(sb.length() - 1);
                }
                sb.append(tail);
            }
            String s = sb.toString();
            return openFence(s) ? s + "\n```" : s;
        }

        /** The blocks of text the way Markdown.parse reads them, each with the source index of every body character. */
        private static java.util.List<Blk> blocks(String text, boolean streaming) {
            java.util.List<Blk> out = new java.util.ArrayList<>();
            String[] lines = text.split("\n", -1);
            int[] start = new int[lines.length + 1];
            for (int k = 0; k < lines.length; k++) {
                start[k + 1] = start[k] + lines[k].length() + 1;
            }
            Blk para = null;
            boolean lettered = false;
            int i = 0;
            while (i < lines.length) {
                String line = lines[i];
                java.util.regex.Matcher m;
                if ((m = Markdown.FENCE.matcher(line)).matches()) {
                    para = null;
                    String fence = m.group(1);
                    Blk b = new Blk();
                    b.plainText = true;
                    i++;
                    int first = i;
                    while (i < lines.length && !lines[i].trim().startsWith(fence)) {
                        i++;
                    }
                    int last = i; // exclusive
                    if (streaming && i >= lines.length && last > first && fence.startsWith(lines[last - 1].trim()) && !lines[last - 1].trim().isEmpty()) {
                        last--; // a closing fence still being typed
                    }
                    for (int k = first; k < last; k++) {
                        if (k > first) {
                            b.body.append('\n');
                            b.src.add(start[k] - 1);
                        }
                        for (int q = 0; q < lines[k].length(); q++) {
                            b.body.append(lines[k].charAt(q));
                            b.src.add(start[k] + q);
                        }
                    }
                    out.add(b);
                    i++;
                    continue;
                }
                if (line.trim().isEmpty()) {
                    para = null;
                    i++;
                    continue;
                }
                if (streaming && i == lines.length - 1 && PARTIAL_MARKER.matcher(line).matches()) {
                    i++;
                    continue;
                }
                if (Markdown.RULE.matcher(line).matches()) {
                    para = null;
                    i++;
                    continue;
                }
                if ((m = Markdown.HEADING.matcher(line)).matches()) {
                    para = null;
                    Blk b = new Blk();
                    b.add(text, start[i] + m.start(2), start[i] + m.end(2), -1);
                    out.add(b);
                    i++;
                    continue;
                }
                if (line.contains("|") && i + 1 < lines.length && Markdown.TABLE_SEP.matcher(lines[i + 1]).matches()) {
                    para = null;
                    Blk b = new Blk();
                    b.plainText = true;
                    b.add(text, start[i], start[i] + line.length(), -1);
                    i += 2;
                    while (i < lines.length && lines[i].contains("|") && !lines[i].trim().isEmpty()) {
                        b.add(text, start[i], start[i] + lines[i].length(), start[i] - 1);
                        i++;
                    }
                    out.add(b);
                    continue;
                }
                if ((m = Markdown.QUOTE.matcher(line)).matches()) {
                    para = null;
                    Blk b = new Blk();
                    b.add(text, start[i] + m.start(1), start[i] + m.end(1), -1);
                    i++;
                    while (i < lines.length && (m = Markdown.QUOTE.matcher(lines[i])).matches()) {
                        b.add(text, start[i] + m.start(1), start[i] + m.end(1), start[i] - 1);
                        i++;
                    }
                    out.add(b);
                    continue;
                }
                int group = -1;
                if ((m = Markdown.BULLET.matcher(line)).matches() && !Markdown.RULE.matcher(line).matches()) {
                    group = 2;
                } else if ((m = Markdown.LETTERED.matcher(line)).matches()
                        && (lettered || Markdown.LETTERED.matcher(Markdown.nextNonBlank(lines, i + 1)).matches())) {
                    group = 4;
                    lettered = true;
                } else {
                    lettered = false;
                    if ((m = Markdown.NUMBERED.matcher(line)).matches()) {
                        group = 3;
                    }
                }
                if (group > 0) {
                    para = null;
                    Blk b = new Blk();
                    b.add(text, start[i] + m.start(group), start[i] + m.end(group), -1);
                    int n = Markdown.extra(lines, i);
                    for (int k = 1; k <= n; k++) {
                        b.add(text, start[i + k], start[i + k] + lines[i + k].length(), start[i + k] - 1);
                    }
                    i += 1 + n;
                    out.add(b);
                    continue;
                }
                if (para == null) {
                    para = new Blk();
                    out.add(para);
                    para.add(text, start[i], start[i] + line.length(), -1);
                } else {
                    para.add(text, start[i], start[i] + line.length(), start[i] - 1);
                }
                i++;
            }
            return out;
        }
    }
}
