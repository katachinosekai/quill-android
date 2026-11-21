package io.github.katachinosekai.quillremote;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ChoicesTest {
    @Test
    public void sameCasesAsDesktop() {
        assertArrayEquals(new String[]{"Push 4bc346e now", "Wait until you've seen it rotate a session"},
                Choices.parse("**Push `4bc346e` now, or wait until you've seen it rotate a session?**"));
        assertArrayEquals(new String[]{"Should I reload now", "Wait until you've sent the queued messages"},
                Choices.parse("Should I reload now or wait until you've sent the queued messages?"));
        assertNull(Choices.parse("**Approve A–E, and which of 3–12 should I include?**"));
        assertNull(Choices.parse("Done. The suite passes."));
        assertArrayEquals(new String[]{"Ship it", "Hold"}, Choices.parse("## ship it or hold?"));
    }

    @Test
    public void planTitle() {
        org.junit.Assert.assertEquals("Approve? Once you do, I'll start.", ChatView.planTitle("Steps\n\n**Approve?** Once you do, I'll start."));
        org.junit.Assert.assertEquals("Approve this plan?", ChatView.planTitle("Steps\n1. a\n2. b"));
    }
}
