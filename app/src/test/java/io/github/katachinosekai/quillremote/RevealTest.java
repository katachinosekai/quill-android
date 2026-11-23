package io.github.katachinosekai.quillremote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

/** The reveal counts rendered characters: no raw Markdown marker shows at any step. */
public class RevealTest {
    static final String REPLY = "# Big Title\n\nHere is **bold text** and *italic words* with `inline code` and a "
            + "[docs link](https://example.com/a_b) done.\n\n- first item\n- second **item**\n\n1. one\n2. two\n\n"
            + "```go\nfunc main() {\n    run()\n}\n```\n\nAll done.";

    /** What the reader sees of md: the text of every block (list markers are drawn apart), no spaces. */
    static String rendered(String md) {
        StringBuilder sb = new StringBuilder();
        List<Markdown.Block> blocks = Markdown.parse(md);
        for (Markdown.Block b : blocks) {
            sb.append(b.text());
        }
        return sb.toString().replaceAll("\\s+", "");
    }

    static void noMarkers(String shown, String where) {
        for (char c : "*`#[]".toCharArray()) {
            assertFalse(where + " shows " + c + ": " + shown, shown.indexOf(c) >= 0);
        }
        assertFalse(where + " shows a url: " + shown, shown.contains("http"));
        assertFalse(where + " shows ](: " + shown, shown.contains("]("));
    }

    @Test
    public void everyStepFinishedTextShowsNoMarker() {
        StreamText.Reveal rv = new StreamText.Reveal(REPLY, true);
        String all = rendered(REPLY);
        for (int n = 0; n <= rv.total(); n++) {
            String shown = rendered(rv.view(n));
            noMarkers(shown, "step " + n);
            String want = rv.visible.substring(0, n).replaceAll("\\s+", "");
            assertEquals("step " + n, want, shown);
            assertTrue(all.startsWith(shown));
        }
    }

    @Test
    public void everyStepWhileTheTextArrivesShowsNoMarker() {
        for (int len = 0; len <= REPLY.length(); len++) {
            String part = REPLY.substring(0, len);
            StreamText.Reveal rv = new StreamText.Reveal(part, true);
            String all = rendered(REPLY);
            for (int n = 0; n <= rv.total(); n++) {
                String shown = rendered(rv.view(n));
                noMarkers(shown, "text " + len + " step " + n);
                assertTrue("text " + len + " step " + n + ": " + shown, all.startsWith(shown));
            }
        }
    }

    @Test
    public void formattedFromTheFirstVisibleLetter() {
        StreamText.Reveal rv = new StreamText.Reveal(REPLY, true);
        int at = rv.visible.indexOf("bold") + 1; // "b" revealed
        List<Markdown.Block> blocks = Markdown.parse(rv.view(at));
        Markdown.Block p = blocks.get(1);
        Markdown.Run last = p.runs.get(p.runs.size() - 1);
        assertEquals("b", last.text);
        assertEquals(Markdown.BOLD, last.style);
        int l = rv.visible.indexOf("docs") + 2;
        p = Markdown.parse(rv.view(l)).get(1);
        last = p.runs.get(p.runs.size() - 1);
        assertEquals("do", last.text);
        assertTrue(last.link != null);
        int c = rv.visible.indexOf("func") + 2;
        Markdown.Block code = Markdown.parse(rv.view(c)).get(Markdown.parse(rv.view(c)).size() - 1);
        assertEquals(Markdown.Type.CODE, code.type);
        assertEquals("fu", code.raw);
        assertEquals(Markdown.Type.HEADING, Markdown.parse(rv.view(2)).get(0).type);
    }

    @Test
    public void finalTextIsUnchanged() {
        StreamText.Reveal rv = new StreamText.Reveal(REPLY, false);
        assertEquals(REPLY, rv.view(rv.total()));
        assertEquals(REPLY, rv.view(rv.total() + 5));
        assertEquals(REPLY.length(), rv.cut(rv.total()));
        // A marker that never closed is part of the finished text.
        StreamText.Reveal lit = new StreamText.Reveal("a ** b *c", false);
        assertEquals("a ** b *c", lit.view(lit.total()));
        assertEquals("a ** b *c", lit.visible);
        // Streaming hides it until it can close.
        assertEquals("a ", new StreamText.Reveal("a **", true).visible);
        assertEquals("a b", new StreamText.Reveal("a **b", true).visible);
    }
}
