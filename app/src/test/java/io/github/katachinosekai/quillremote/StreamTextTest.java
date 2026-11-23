package io.github.katachinosekai.quillremote;

import static org.junit.Assert.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

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
}
