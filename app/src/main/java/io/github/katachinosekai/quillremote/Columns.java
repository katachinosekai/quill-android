package io.github.katachinosekai.quillremote;

/**
 * Centered columns that scale with the chat (port of Quill Desktop's cmd/quill/balance.go, 1be563f, 2026-11-22).
 * Every width is a share of the width the chat has right now, worked out again on each layout:
 * <ul>
 * <li>in a group (a run of paragraphs, a list, the plan card's questions, the queued messages) a line more than
 * OVERLONG times as wide as the widest other line doesn't stretch the column; it wraps into as few lines as the
 * group's width allows (never narrower than FLOOR of the chat) and those lines are evened out;</li>
 * <li>a group wider than the chat is capped at CAP of the chat and centered.</li>
 * </ul>
 * Pure functions over measured word widths, so they are unit-tested on the JVM; ColumnView applies them.
 */
final class Columns {
    static final float OVERLONG = 1.5f;
    static final float FLOOR = 0.35f;
    static final float CAP = 0.75f;

    private Columns() {
    }

    /** A line's words, measured once: word widths, the width of a space, the widest word and the one-line width. */
    static final class Words {
        final float[] ws;
        final float sp;
        final float longest;
        final float total;

        Words(float[] ws, float sp) {
            this.ws = ws;
            this.sp = sp;
            float l = 0, t = 0;
            for (int i = 0; i < ws.length; i++) {
                l = Math.max(l, ws[i]);
                t += ws[i] + (i > 0 ? sp : 0);
            }
            longest = l;
            total = t;
        }

        /** Lines a greedy word wrap at width takes. */
        int lines(float width) {
            int n = 1;
            float x = 0;
            for (int i = 0; i < ws.length; i++) {
                if (i > 0 && x + sp + ws[i] > width) {
                    n++;
                    x = ws[i];
                    continue;
                }
                x += (i > 0 ? sp : 0) + ws[i];
            }
            return n;
        }

        /** The narrowest width that wraps the line into n lines or fewer. */
        float fitWidth(int n) {
            float lo = longest, hi = Math.max(total, longest);
            while (hi - lo > 0.5f) {
                float mid = (lo + hi) / 2;
                if (lines(mid) <= n) {
                    hi = mid;
                } else {
                    lo = mid;
                }
            }
            return hi;
        }

        /**
         * The width the line wraps at: as few lines as target allows, evened out, never narrower than floor (fewer
         * lines when evening out would go below it; floor itself when even two lines would). 0 = fits on one line
         * (or is one word).
         */
        float balanced(float target, float floor) {
            if (ws.length < 2) {
                return 0;
            }
            target = Math.max(Math.max(target, floor), longest);
            int n = lines(target);
            if (n < 2) {
                return 0;
            }
            for (; n >= 2; n--) {
                float b = fitWidth(n);
                if (b >= floor) {
                    return b;
                }
            }
            return floor;
        }
    }

    /** Index of the one line more than OVERLONG times as wide as the widest other line, or -1. */
    static int overlong(float[] widths) {
        if (widths.length < 2) {
            return -1;
        }
        int idx = 0;
        for (int i = 1; i < widths.length; i++) {
            if (widths[i] > widths[idx]) {
                idx = i;
            }
        }
        return widths[idx] > OVERLONG * widestOther(widths, idx) && widestOther(widths, idx) > 0 ? idx : -1;
    }

    static float widestOther(float[] widths, int idx) {
        float o = 0;
        for (int i = 0; i < widths.length; i++) {
            if (i != idx) {
                o = Math.max(o, widths[i]);
            }
        }
        return o;
    }

    /**
     * Column width for a group: texts are its lines, extra what each line adds around its text (markers, padding,
     * buttons). An overlong line wraps balanced (never narrower than FLOOR of avail); a group wider than avail is
     * capped at CAP of it.
     */
    static float groupWidth(float avail, Words[] texts, float extra, float slack) {
        float[] widths = new float[texts.length];
        float natural = 0;
        for (int i = 0; i < texts.length; i++) {
            widths[i] = texts[i].total;
            natural = Math.max(natural, texts[i].total + extra);
        }
        float col = natural;
        int i = overlong(widths);
        if (i >= 0) {
            float other = widestOther(widths, i);
            float b = texts[i].balanced(other, FLOOR * avail - extra);
            if (b > 0) {
                col = Math.max(b + slack, other) + extra;
            }
        }
        if (col > avail) {
            col = CAP * avail;
        }
        return col;
    }
}
