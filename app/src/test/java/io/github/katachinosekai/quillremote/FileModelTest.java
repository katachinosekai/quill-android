package io.github.katachinosekai.quillremote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class FileModelTest {
    static FileModel text(String t, boolean truncated, long size) {
        return new FileModel("text", "/home/user/p/main.go", "main.go", size, "go", t, truncated, "", "");
    }

    @Test
    public void linesAndJump() {
        FileModel m = text("a\nb\nc\n", false, 6);
        assertEquals(3, m.lines().size());
        assertEquals(0, m.jumpLine(0));
        assertEquals(2, m.jumpLine(2));
        assertEquals(3, m.jumpLine(99)); // clamped into the text
        assertEquals(2, m.lineOffset(2));
        assertEquals(4, m.lineOffset(3));
        assertEquals(3, m.rangeEnd(2, 3));
        assertEquals(0, m.rangeEnd(2, 1)); // an end before the start is no range
        assertEquals(3, m.rangeEnd(2, 50));
        assertEquals(0, m.rangeEnd(0, 3));
        assertEquals(1, text("", false, 0).lines().size());
        assertEquals(2, text("a\n\nb", false, 4).lines().size() - 1);
    }

    @Test
    public void truncationNote() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 256 * 1024; i++) {
            sb.append('x');
        }
        assertEquals("Showing the first 256 KiB of 3.1 MiB", text(sb.toString(), true, 3_250_000).truncationNote());
        assertEquals("", text("abc", false, 3).truncationNote());
    }

    @Test
    public void notShown() {
        FileModel b = new FileModel("binary", "/home/user/x.bin", "x.bin", 2048, "", "", false, "", "");
        assertEquals("Binary file, not shown", b.notShownNote());
        assertEquals("x.bin", b.name);
        assertEquals("2 KiB", FileModel.sizeText(b.size));
        FileModel t = new FileModel("too_large", "/home/user/big.iso", "", 41L * 1024 * 1024, "", "", false, "", "");
        assertEquals("Too large to show (41 MiB)", t.notShownNote());
        assertEquals("big.iso", t.name);
        assertEquals("", text("x", false, 1).notShownNote());
    }

    @Test
    public void sizes() {
        assertEquals("0 B", FileModel.sizeText(0));
        assertEquals("1023 B", FileModel.sizeText(1023));
        assertEquals("1.5 KiB", FileModel.sizeText(1536));
    }

    @Test
    public void shortPaths() {
        assertEquals("~/p/a.go", FileModel.shortPath("/home/user/p/a.go", "/home/user"));
        assertEquals("/home/username/a", FileModel.shortPath("/home/username/a", "/home/user"));
        assertEquals("/tmp/a", FileModel.shortPath("/tmp/a", ""));
    }

    @Test
    public void errorMessages() {
        assertEquals("Turn on 'Let this device open files' for this phone in Quill Desktop → Devices",
                FileModel.errorMessage(403, "files_off", "", 0));
        assertEquals("Quill Desktop won't open this file: on the deny list (.env)", FileModel.errorMessage(403, "file_denied", "on the deny list (.env)", 0));
        assertEquals("Quill Desktop can't find this file.", FileModel.errorMessage(404, "not_found", "", 0));
        assertEquals("This path can't be opened.", FileModel.errorMessage(400, "bad_request", "", 0));
        assertEquals("Too many files opened. Try again in 7 s.", FileModel.errorMessage(429, "rate_limited", "", 7));
        assertEquals("Another file is still loading. Try again in a moment.", FileModel.errorMessage(409, "file_busy", "", 0));
        assertTrue(FileModel.errorMessage(401, "locked", "", 0).contains("locked"));
        assertTrue(FileModel.errorMessage(403, "forbidden", "", 0).contains("removed"));
        assertEquals("Quill Desktop is not reachable.", FileModel.errorMessage(0, "unreachable", "", 0));
        assertEquals("Could not open the file (weird).", FileModel.errorMessage(500, "weird", "", 0));
    }

    @Test
    public void retryWaitIsBounded() {
        assertEquals(1, FileModel.retryWait(0));
        assertEquals(4, FileModel.retryWait(4));
        assertEquals(10, FileModel.retryWait(600));
    }

    @Test
    public void syntaxSpans() {
        String src = "func main() { x := \"hi\" // note\n\tn := 42 }";
        List<Syntax.Span> s = Syntax.spans("go", src);
        assertEquals(Syntax.KEYWORD, s.get(0).type);
        assertEquals("func", src.substring(s.get(0).start, s.get(0).end));
        boolean str = false, com = false, num = false;
        for (Syntax.Span p : s) {
            str |= p.type == Syntax.STRING && src.substring(p.start, p.end).equals("\"hi\"");
            com |= p.type == Syntax.COMMENT && src.substring(p.start, p.end).equals("// note");
            num |= p.type == Syntax.NUMBER && src.substring(p.start, p.end).equals("42");
        }
        assertTrue(str && com && num);
        assertTrue(Syntax.spans("unknown", src).isEmpty());
        assertEquals(1, Syntax.spans("py", "# c\nx = 1").stream().filter(p -> p.type == Syntax.COMMENT).count());
        assertEquals(Syntax.COMMENT, Syntax.spans("c", "/* a\nb */ int").get(0).type);
    }
}
