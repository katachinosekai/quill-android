package io.github.katachinosekai.quillremote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Same cases as Quill Desktop's balance_test.go, with words measured as 10 px per character and 4 px spaces. */
public class ColumnsTest {
    static Columns.Words words(String s) {
        String[] w = s.trim().split("\\s+");
        float[] ws = new float[w.length];
        for (int i = 0; i < w.length; i++) {
            ws[i] = 10f * w[i].length();
        }
        return new Columns.Words(ws, 4f);
    }

    static final String LONG = "Make the overlong item wrap into a few balanced lines that are about as wide as each other, weighted against the other short items in the same list, instead of stretching the column";

    @Test
    public void overlongLine() {
        assertEquals(-1, Columns.overlong(new float[]{100}));
        assertEquals(-1, Columns.overlong(new float[]{100, 70, 50}));
        assertEquals(1, Columns.overlong(new float[]{50, 200, 70}));
        assertEquals(-1, Columns.overlong(new float[]{60, 90, 60})); // exactly 1.5x is not overlong
        assertEquals(1, Columns.overlong(new float[]{60, 91, 60}));
        assertEquals(-1, Columns.overlong(new float[]{300, 280, 50}));
    }

    @Test
    public void balancedEvensLines() {
        Columns.Words w = words(LONG);
        float target = w.total / 3.2f;
        float b = w.balanced(target, 0);
        assertTrue("balanced " + b, b > 0 && b <= target);
        assertEquals(w.lines(target), w.lines(b));
        assertEquals(0f, words("Done.").balanced(target, 0), 0);
        assertEquals(0f, words("xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx").balanced(target, 0), 0);
        for (float floor : new float[]{target * 0.9f, target * 1.5f, w.total * 0.6f}) {
            float fb = w.balanced(target, floor);
            assertTrue("floor " + floor + " width " + fb, fb == 0 || fb >= floor);
        }
    }

    @Test
    public void groupWidthScalesWithTheChatAndCaps() {
        Columns.Words[] list = {words("one item"), words("another short item"), words(LONG)};
        float wide = Columns.groupWidth(4000, list, 40, 8);
        float narrow = Columns.groupWidth(1200, list, 40, 8);
        assertTrue("an overlong item doesn't stretch the column: " + wide, wide < list[2].total + 40);
        assertTrue("never narrower than 35% of the chat: " + narrow, narrow >= 0.35f * 1200 - 1);
        Columns.Words[] wideList = {words(LONG), words(LONG + " again")};
        assertEquals(0.75f * 600, Columns.groupWidth(600, wideList, 40, 8), 0.01);
        Columns.Words[] small = {words("a b"), words("c d")};
        assertEquals(small[0].total + 40, Columns.groupWidth(1000, small, 40, 8), 0.01);
    }
}
