package io.github.katachinosekai.quillremote;

import static org.junit.Assert.assertEquals;

import java.util.List;
import org.junit.Test;

public class MarkdownTest {
    static String blocks(String src) {
        StringBuilder sb = new StringBuilder();
        for (Markdown.Block b : Markdown.parse(src)) {
            sb.append(b.type).append(b.level > 0 ? "" + b.level : "").append(b.marker == null ? "" : " " + b.marker)
                    .append(b.lang == null || b.lang.isEmpty() ? "" : " " + b.lang).append(": ")
                    .append(b.runs == null ? b.raw : b.runs.toString()).append('\n');
        }
        return sb.toString();
    }

    @Test
    public void blocksAndInline() {
        assertEquals("HEADING2: [Plan]\nPARAGRAPH: [Do , [1:this],  and , [2:that], , then , [4:go build], .]\n",
                blocks("## Plan\n\nDo **this** and *that*, then `go build`."));
    }

    @Test
    public void codeFenceKeepsText() {
        assertEquals("PARAGRAPH: [Run:]\nCODE go: a := 1\n\nb **x**\nPARAGRAPH: [done]\n",
                blocks("Run:\n```go\na := 1\n\nb **x**\n```\ndone"));
    }

    @Test
    public void lists() {
        assertEquals("BULLET •: [one]\nBULLET1 •: [two\nmore]\nNUMBERED 3.: [three]\n",
                blocks("- one\n  - two\n    more\n3. three"));
    }

    @Test
    public void linksQuotesRules() {
        assertEquals("PARAGRAPH: [See , [0→https://x.y/a:the docs], !]\nRULE: \nQUOTE: [a\n, [1:b]]\n",
                blocks("See [the docs](https://x.y/a)!\n\n---\n> a\n> **b**"));
    }

    @Test
    public void table() {
        List<Markdown.Block> b = Markdown.parse("| a | b |\n|---|---|\n| 1 | 2 |");
        assertEquals(Markdown.Type.TABLE, b.get(0).type);
        assertEquals(2, Markdown.tableRows(b.get(0).raw).size());
        assertEquals("2", Markdown.tableRows(b.get(0).raw).get(1)[1]);
    }

    @Test
    public void snakeCaseAndUnclosed() {
        assertEquals("PARAGRAPH: [use snake_case_name and 2 * 3 and **open]\n",
                blocks("use snake_case_name and 2 * 3 and **open"));
    }
}
