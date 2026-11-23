package io.github.katachinosekai.quillremote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LinkPolicyTest {
    static LinkPolicy.Action of(String target) {
        return LinkPolicy.decide(Links.classify(target));
    }

    @Test
    public void webOpensInBrowser() {
        assertEquals(LinkPolicy.Action.OPEN_WEB, of("https://example.com/a"));
        assertEquals(LinkPolicy.Action.OPEN_WEB, of("http://example.com:8080/x?y=1"));
        assertEquals(LinkPolicy.Action.OPEN_WEB, of("HTTPS://Example.com"));
    }

    @Test
    public void localAddressesAsk() {
        for (String u : new String[]{"http://localhost:3000/", "https://127.0.0.1/x", "http://127.9.9.9", "http://[::1]:80/",
                "http://0.0.0.0:8000", "http://LOCALHOST./a", "http://user@localhost/a", "http://app.localhost/"}) {
            assertEquals(u, LinkPolicy.Action.LOCAL_DIALOG, of(u));
        }
        // A look-alike host is a normal web link, and a user-info trick doesn't hide the real host.
        assertEquals(LinkPolicy.Action.OPEN_WEB, of("http://localhost.example.com/"));
        assertEquals(LinkPolicy.Action.OPEN_WEB, of("http://localhost@example.com/"));
        assertEquals(LinkPolicy.Action.OPEN_WEB, of("http://127.0.0.1.example.com/"));
        assertFalse(LinkPolicy.isLocalHost("128.0.0.1"));
    }

    @Test
    public void dangerousSchemesNeverOpen() {
        for (String u : new String[]{"intent://x#Intent;end", "javascript:alert(1)", "data:text/html,hi", "content://a/b",
                "android-app://x", "vbscript:x", "INTENT:foo", "JavaScript:x"}) {
            assertEquals(u, LinkPolicy.Action.NEVER, of(u));
        }
        assertEquals(LinkPolicy.Action.NONE, of("quillpair:abc")); // not a link at all
    }

    @Test
    public void otherSchemesAsk() {
        assertEquals(LinkPolicy.Action.CONFIRM_OPEN, of("tel:+10000000000"));
        assertEquals(LinkPolicy.Action.CONFIRM_OPEN, of("mailto:user@example.com"));
        assertEquals(LinkPolicy.Action.CONFIRM_OPEN, of("geo:0,0"));
        assertEquals(LinkPolicy.Action.CONFIRM_SCHEME, of("slack://channel"));
        assertEquals(LinkPolicy.Action.CONFIRM_SCHEME, of("ftp://example.com/x"));
    }

    @Test
    public void filesGoToTheViewer() {
        assertEquals(LinkPolicy.Action.FILE, of("/home/user/a/b.go"));
        assertEquals(LinkPolicy.Action.FILE, of("file:///home/user/a.txt"));
        assertEquals(LinkPolicy.Action.NONE, of("src/main.go"));
        assertTrue(LinkPolicy.hostOf("http://a.b:1/c").equals("a.b"));
    }
}
