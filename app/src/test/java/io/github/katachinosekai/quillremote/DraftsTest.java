package io.github.katachinosekai.quillremote;

import static org.junit.Assert.*;

import java.util.Set;
import org.junit.Test;

public class DraftsTest {
    @Test
    public void keepsTextAndSelectionPerChat() {
        Drafts d = new Drafts(new FakePrefs());
        d.save("a", "hello world", 3, 8);
        d.save("b", "other\nline", 2, 2);
        Drafts.Draft a = d.load("a");
        assertEquals("hello world", a.text);
        assertEquals(3, a.start);
        assertEquals(8, a.end);
        assertEquals(2, d.load("b").start);
        assertNull(d.load("c"));
    }

    @Test
    public void emptyOrClearedDraftIsDropped() {
        Drafts d = new Drafts(new FakePrefs());
        d.save("a", "x", 1, 1);
        d.save("a", "", 0, 0);
        assertNull(d.load("a"));
        d.save("a", "x", 1, 1);
        d.clear("a");
        assertNull(d.load("a"));
    }

    @Test
    public void textWithCommasAndLongTextAreSafe() {
        Drafts d = new Drafts(new FakePrefs());
        d.save("a", "1,2,3", 5, 5);
        assertEquals("1,2,3", d.load("a").text);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Drafts.MAX_CHARS + 50; i++) {
            sb.append('x');
        }
        d.save("a", sb.toString(), Drafts.MAX_CHARS + 40, Drafts.MAX_CHARS + 50);
        Drafts.Draft l = d.load("a");
        assertEquals(Drafts.MAX_CHARS, l.text.length());
        assertEquals(Drafts.MAX_CHARS, l.start);
    }

    @Test
    public void reversedSelectionIsOrdered() {
        Drafts d = new Drafts(new FakePrefs());
        d.save("a", "abcdef", 5, 2);
        assertEquals(2, d.load("a").start);
        assertEquals(5, d.load("a").end);
    }

    @Test
    public void pruneDropsVanishedChatsOnly() {
        FakePrefs p = new FakePrefs();
        p.edit().putString("unrelated", "keep").apply();
        Drafts d = new Drafts(p);
        d.save("a", "x", 0, 0);
        d.save("gone", "y", 0, 0);
        d.prune(Set.of("a"));
        assertNotNull(d.load("a"));
        assertNull(d.load("gone"));
        assertEquals("keep", p.getString("unrelated", null));
    }

    @Test
    public void overlongDraftIsNotCutThroughAnEmoji() {
        Drafts d = new Drafts(new FakePrefs());
        String text = "a".repeat(Drafts.MAX_CHARS - 1) + "\uD83D\uDE00tail";
        d.save("a", text, 0, 0);
        assertEquals("a".repeat(Drafts.MAX_CHARS - 1), d.load("a").text);
    }
}
