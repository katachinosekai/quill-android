package io.github.katachinosekai.quillremote;

import static org.junit.Assert.*;

import org.junit.Test;

public class CutTest {
    private static final String EMOJI = "😀"; // one char, two UTF-16 units

    private static void assertNoLoneSurrogate(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                assertTrue("dangling high surrogate in " + s, i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1)));
                i++;
            } else {
                assertFalse("lone low surrogate in " + s, Character.isLowSurrogate(c));
            }
        }
    }

    @Test
    public void headBacksOffFromPairMiddle() {
        String s = "ab" + EMOJI + "cd";
        assertEquals("ab", Cut.head(s, 3));
        assertEquals("ab" + EMOJI, Cut.head(s, 4));
        assertEquals(s, Cut.head(s, 6));
        assertEquals("", Cut.head(s, 0));
        assertEquals("", Cut.head(null, 3));
    }

    @Test
    public void cjkIsCutAtAnyLength() {
        assertEquals("日本", Cut.head("日本語", 2));
    }

    @Test
    public void everyCutLengthStaysWellFormed() {
        String s = "x" + EMOJI + EMOJI + "日" + EMOJI;
        for (int n = 0; n <= s.length() + 1; n++) {
            assertNoLoneSurrogate(Cut.head(s, n));
            assertNoLoneSurrogate(Cut.ellipsize(s, n, "…"));
        }
    }

    @Test
    public void clipSitesKeepPairsWhole() {
        String s = "abc" + EMOJI + EMOJI + EMOJI;
        assertEquals("abc…", Notifier.clip(s, 5));
        assertEquals("abc" + EMOJI + "…", Notifier.clip(s, 6));
        assertEquals("abc…", UpdateNotes.clip(s, 5));
        assertEquals("abc" + EMOJI + "…", UpdateNotes.clip(s, 6));
        String out = "b".repeat(156) + EMOJI + "zz";
        assertEquals("b".repeat(156) + "...", KmemWriteModel.brief(out));
    }
}
