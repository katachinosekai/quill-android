package io.github.katachinosekai.quillremote;

import static org.junit.Assert.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/** Same cases as Quill Desktop's TestStreamCut and closeOpenMarkdown tests. */
public class StreamTextTest {
    @Test
    public void splitIntoParagraphs() {
        StringBuilder live = new StringBuilder();
        assertEquals(java.util.List.of(), StreamText.split("hello", live));
        assertEquals("hello", live.toString());
        assertEquals(java.util.List.of("one"), StreamText.split("one\n\ntwo", live));
        assertEquals("two", live.toString());
        assertEquals(java.util.List.of("one", "two"), StreamText.split("one\n\ntwo\n\nthr", live));
        assertEquals(java.util.List.of("```\na\n\nb\n```"), StreamText.split("```\na\n\nb\n```\n\nafter", live));
        assertEquals("after", live.toString());
        assertEquals(java.util.List.of(), StreamText.split("```\na\n\nb still in fence", live));
        assertEquals(java.util.List.of(), StreamText.split("para\n\n", live));
    }

    @Test
    public void closeOpenSameAsDesktop() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("Here:\n```go\nfunc x() {", "Here:\n```go\nfunc x() {\n```");
        m.put("Done.\n```\nok\n```\nMore", "Done.\n```\nok\n```\nMore");
        m.put("This is **bol", "This is **bol**");
        m.put("This is **", "This is ");
        m.put("Use `go te", "Use `go te`");
        m.put("**bold `cod", "**bold `cod`**");
        m.put("a **b** and `c` done", "a **b** and `c` done");
        m.put("First para **x**\n\nSecond `y", "First para **x**\n\nSecond `y`");
        for (Map.Entry<String, String> e : m.entrySet()) {
            assertEquals(e.getKey(), e.getValue(), StreamText.closeOpen(e.getKey()));
        }
    }

    @Test
    public void revealIsSteadyWholeWordsAndCatchesUp() {
        String text = "The first paragraph has bold words and a list of reasons to stream smoothly to the end.";
        int pos = 0, frames = 0, maxStep = 0;
        while (pos < text.length()) {
            int n = StreamText.revealBy(text, pos, true, Math.max(1, (int) StreamText.rate("fast", text.length() - pos)));
            maxStep = Math.max(maxStep, n - pos);
            assertEquals("whole words: " + n, true, n == text.length() || text.charAt(n) == ' ' || text.indexOf(' ', n) < 0); // the last word may show partly while streaming
            pos = n;
            frames++;
        }
        assertEquals(true, maxStep <= 2 * StreamText.REVEAL_MAX);
        assertEquals(true, frames > 5);
        assertEquals(2, StreamText.revealBy("ab", 0, false, 1)); // a finished reply shows its last word at once
        assertEquals(3, StreamText.revealBy("a\uD83D\uDE00b", 0, true, 2)); // never between a surrogate pair
    }

    @Test
    public void revealNeverStopsInsideALetter() {
        String[] texts = {
            "a\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67b", // ZWJ family
            "x\uD83C\uDDE9\uD83C\uDDEA\uD83C\uDDEB\uD83C\uDDF7y", // two flags
            "cafe\u0301 ok", // e + combining acute
            "\u0915\u094D\u0937\u093F \u0939\u0948", // Hindi conjunct
            "\u0645\u0631\u062D\u0628\u0627 \u0628\u0627\u0644\u0639\u0627\u0644\u0645", // Arabic
        };
        for (String text : texts) {
            java.text.BreakIterator it = java.text.BreakIterator.getCharacterInstance();
            it.setText(text);
            for (int step = 1; step <= 3; step++) {
                for (boolean streaming : new boolean[]{true, false}) {
                    int pos = 0;
                    while (pos < text.length()) {
                        int n = StreamText.revealBy(text, pos, streaming, step);
                        assertEquals(text + " step " + step + " at " + n, true, n > pos && it.isBoundary(n));
                        pos = n;
                    }
                }
            }
        }
        // a boundary inside a family / flag / accented letter moves to its end
        assertEquals(8, StreamText.letterEnd("\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67b", 3));
        assertEquals(4, StreamText.letterEnd("\uD83C\uDDE9\uD83C\uDDEA\uD83C\uDDEB\uD83C\uDDF7", 3));
        assertEquals(5, StreamText.letterEnd("cafe\u0301", 4));
        assertEquals(4, StreamText.letterEnd("ab\r\n", 3));
    }

    @Test
    public void speedsAreFractionsOfTheOldNormal() {
        for (int gap = 1; gap < 2000; gap++) {
            double old = Math.min(Math.max(2, gap / 12), 24);
            assertEquals(old / 2, StreamText.rate("fast", gap), 1e-9);
            assertEquals(old / 4, StreamText.rate("normal", gap), 1e-9);
            assertEquals(old / 8, StreamText.rate("slow", gap), 1e-9);
            assertEquals(old / 4, StreamText.rate(null, gap), 1e-9);
            assertEquals(old / 4, StreamText.rate("bogus", gap), 1e-9);
            assertEquals(gap, StreamText.rate("instant", gap), 1e-9);
        }
    }

    @Test
    public void speedNamesAndCycle() {
        assertEquals("normal", StreamText.speed(null));
        assertEquals("normal", StreamText.speed("zzz"));
        assertEquals("Instant", StreamText.speedLabel("instant"));
        assertEquals("normal", StreamText.nextSpeed("slow"));
        assertEquals("slow", StreamText.nextSpeed("instant"));
    }

    @Test
    public void blankLinesBetweenListItemsDontSplit() {
        StringBuilder live = new StringBuilder();
        assertEquals(java.util.List.of("1. one\n\n2. two"), StreamText.split("1. one\n\n2. two\n\nAfter", live));
        assertEquals("After", live.toString());
        assertEquals(java.util.List.of("a. one\n\nb) two"), StreamText.split("a. one\n\nb) two\n\nAfter", live));
        assertEquals(java.util.List.of("- x"), StreamText.split("- x\n\nText", live));
    }

    @Test
    public void newCharactersFadeInOverTheFadeTime() {
        StreamText.Fade f = new StreamText.Fade();
        f.grew(4, 1000);
        f.grew(10, 1100);
        assertEquals(0, f.step(0, 1000));
        assertEquals(StreamText.FADE_STEPS, f.step(0, 1000 + StreamText.CHAR_FADE_MS));
        assertEquals(StreamText.FADE_STEPS / 2, f.step(0, 1000 + StreamText.CHAR_FADE_MS / 2));
        assertEquals(0, f.step(7, 1100));
        assertEquals(StreamText.FADE_STEPS, f.step(99, 1100)); // beyond the text: shown
        assertEquals(0, f.firstFading(1100));
        assertEquals(4, f.firstFading(1000 + StreamText.CHAR_FADE_MS)); // the first four are done
        assertTrue(f.active(1100));
        assertFalse(f.active(1100 + StreamText.CHAR_FADE_MS));
    }

    @Test
    public void shorterTextDropsFadesAndFadeIsUnderTheTapGuard() {
        StreamText.Fade f = new StreamText.Fade();
        f.grew(8, 0);
        f.grew(3, 10);
        assertEquals(3, f.length());
        f.grew(5, 20);
        assertEquals(0, f.step(3, 20));
        assertEquals(StreamText.FADE_STEPS, f.step(3, 20 + StreamText.CHAR_FADE_MS));
        assertTrue(StreamText.FADE_IN_MS < QuestionCards.GUARD_MS);
    }
}
