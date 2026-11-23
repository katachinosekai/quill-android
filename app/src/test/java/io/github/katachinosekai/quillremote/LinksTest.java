package io.github.katachinosekai.quillremote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** Runs the shared cases (Quill Desktop cmd/quill/testdata/links.json, copied to test resources). */
public class LinksTest {
    static byte[] resource(String name) throws Exception {
        try (InputStream in = LinksTest.class.getClassLoader().getResourceAsStream(name)) {
            return in.readAllBytes();
        }
    }

    @Test
    public void caseFileMatchesRecordedChecksum() throws Exception {
        String recorded = new String(resource("phone-links-cases.sha256"), StandardCharsets.UTF_8).trim().split("\\s+")[0];
        byte[] sum = MessageDigest.getInstance("SHA-256").digest(resource("links.json"));
        StringBuilder hex = new StringBuilder();
        for (byte b : sum) {
            hex.append(String.format("%02x", b));
        }
        assertEquals("links.json differs from the recorded Quill Desktop checksum", recorded, hex.toString());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void sharedCases() throws Exception {
        Map<String, Object> doc = (Map<String, Object>) MiniJson.parse(new String(resource("links.json"), StandardCharsets.UTF_8));
        assertEquals(1, ((Double) doc.get("version")).intValue());
        List<Object> cases = (List<Object>) doc.get("cases");
        assertEquals(28, cases.size());
        for (Object o : cases) {
            Map<String, Object> c = (Map<String, Object>) o;
            String name = (String) c.get("name");
            String text = (String) c.get("text");
            List<Object> want = (List<Object>) c.get("links");
            List<Links.Link> got = Links.find(text);
            assertEquals(name + ": link count", want.size(), got.size());
            for (int i = 0; i < want.size(); i++) {
                Map<String, Object> w = (Map<String, Object>) want.get(i);
                Links.Link g = got.get(i);
                String at = name + " #" + i;
                assertEquals(at + " kind", w.get("kind"), g.kind);
                assertEquals(at + " start", num(w, "start"), g.start);
                assertEquals(at + " end", num(w, "end"), g.end);
                assertEquals(at + " text", w.get("text"), g.text);
                assertEquals(at + " target", str(w, "target"), g.target);
                assertEquals(at + " label", str(w, "label"), g.label);
                assertEquals(at + " line", num(w, "line"), g.line);
                assertEquals(at + " lineEnd", num(w, "lineEnd"), g.lineEnd);
            }
        }
    }

    static int num(Map<String, Object> m, String k) {
        return m.containsKey(k) ? ((Double) m.get(k)).intValue() : 0;
    }

    static String str(Map<String, Object> m, String k) {
        return m.containsKey(k) ? (String) m.get(k) : "";
    }

    @Test
    public void capIsFifty() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            sb.append("https://x.io/").append(i).append(' ');
        }
        assertEquals(Links.MAX, Links.find(sb.toString()).size());
    }

    @Test
    public void proseAndCodePathHelpers() {
        List<Links.Link> p = Links.prose("see /home/user/a/b.go:7 and https://x.io/a.");
        assertEquals(2, p.size());
        assertEquals("/home/user/a/b.go", p.get(0).target);
        assertEquals(7, p.get(0).line);
        assertEquals("https://x.io/a", p.get(1).target);
        assertTrue(Links.codePath("/home/user/a/b.go") != null);
        assertTrue(Links.codePath("go test ./...") == null);
        assertTrue(Links.codePath("src/main.go") == null);
    }

    @Test
    public void classifyTargets() {
        assertEquals("", Links.classify("src/main.go").kind);
        assertEquals("", Links.classify("quillpair:abc").kind);
        assertEquals("other", Links.classify("tel:+10000000000").kind);
        assertEquals("/tmp/a b", Links.classify("file:///tmp/a%20b").target);
        assertEquals("", Links.classify("file:///%zz").kind);
    }
}
