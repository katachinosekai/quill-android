package io.github.katachinosekai.quillremote;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class ChoicesTest {
    @Test
    public void sameCasesAsDesktop() {
        assertArrayEquals(new String[]{"Push 4bc346e now", "Wait until you've seen it rotate a session"},
                Choices.parse("**Push `4bc346e` now, or wait until you've seen it rotate a session?**"));
        assertArrayEquals(new String[]{"Reload now", "Wait until you've sent the queued messages"},
                Choices.parse("Should I reload now or wait until you've sent the queued messages?"));
        assertNull(Choices.parse("**Approve A–E, and which of 3–12 should I include?**"));
        assertNull(Choices.parse("Done. The suite passes."));
        assertArrayEquals(new String[]{"Ship it", "Hold"}, Choices.parse("## ship it or hold?"));
    }

    @Test
    public void listsAndLeadIns() {
        assertArrayEquals(new String[]{"Wide adoption", "Keeping it open", "Controlling commercial use"},
                Choices.parse("**Which matters most to you for kmem: wide adoption, keeping it open, or controlling commercial use?**"));
        assertArrayEquals(new String[]{"Red", "Green", "Blue"}, Choices.parse("Pick a color: red, green, or blue?"));
        assertArrayEquals(new String[]{"Merge now", "Wait for review"}, Choices.parse("Do you want me to merge now, or wait for review?"));
        assertNull(Choices.parse("One, two, three, four, or five?"));
        assertNull(Choices.parse("Should I rewrite the whole module from scratch using the new framework and also add tests for every edge case, or stop?"));
    }

    @Test
    public void planTitle() {
        org.junit.Assert.assertEquals("Approve? Once you do, I'll start.", ChatView.planTitle("Steps\n\n**Approve?** Once you do, I'll start."));
        org.junit.Assert.assertEquals("Approve this plan?", ChatView.planTitle("Steps\n1. a\n2. b"));
    }

    @Test
    public void continuationStaysWithItsOption() {
        // Quill Desktop 22c277a: "all of them, with X (recommended), or Y?" is 2 options, not 3
        assertArrayEquals(new String[]{"Do all of them, with single-action buttons getting their own row", "Only the first"},
                Choices.parse("Should I do all of them, with single-action buttons getting their own row, or only the first?"));
        assertArrayEquals(new String[]{"Red", "Green", "Blue"}, Choices.parse("Red, green, or blue?"));
    }

    @Test
    public void answeredChoiceMarksThePick() {
        String[] opts = {"Merge them", "Leave both"};
        assertEquals(1, Choices.picked("I pick the second option: Leave both.", opts));
        assertEquals(0, Choices.picked("merge them", opts));
        assertEquals(-1, Choices.picked("something else", opts));
        assertEquals(-1, Choices.picked(null, opts));
        assertEquals("I pick the next option: x.", Choices.reply(4, "x"));
    }
}
