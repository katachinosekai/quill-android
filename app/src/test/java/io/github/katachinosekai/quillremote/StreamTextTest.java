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
            int n = StreamText.nextReveal(text, pos, true);
            maxStep = Math.max(maxStep, n - pos);
            assertEquals("whole words: " + n, true, n == text.length() || text.charAt(n) == ' ' || text.indexOf(' ', n) < 0); // the last word may show partly while streaming
            pos = n;
            frames++;
        }
        assertEquals(true, maxStep <= 2 * StreamText.REVEAL_MAX);
        assertEquals(true, frames > 5);
        assertEquals(2, StreamText.nextReveal("ab", 0, false)); // a finished reply shows its last word at once
        assertEquals(3, StreamText.nextReveal("a\uD83D\uDE00b", 0, true)); // never between a surrogate pair
    }
}
