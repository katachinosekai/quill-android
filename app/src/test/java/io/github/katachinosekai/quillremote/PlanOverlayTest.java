package io.github.katachinosekai.quillremote;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class PlanOverlayTest {
    static final String REPLY = "Here is the plan.\n\n1. Build it\n2. Ship it\n\nOpen questions:\n1. Should the `beta` channel get it first? Yes (recommended) or no?\n2. **Which build?** Stable or beta?\n   a. stable\n   b. beta\n\nDone.";

    @Test
    public void findsEachQuestionLineAfterThePlanSteps() {
        int[][] m = PlanOverlay.match(REPLY, new int[]{1, 2},
                new String[]{"Should the beta channel get it first? Yes or no?", "Which build? Stable or beta?"});
        assertArrayEquals(new int[]{6, 7}, m[0]);
        assertArrayEquals(new int[]{7, 10}, m[1]);
    }

    @Test
    public void fallsBackWhenSomethingDoesntLineUp() {
        assertNull(PlanOverlay.match(REPLY, new int[]{1, 3}, new String[]{"Should the beta channel get it first?", "Which build?"}));
        assertNull(PlanOverlay.match(REPLY, new int[]{0}, new String[]{"Should the beta channel get it first?"}));
        assertNull(PlanOverlay.match(REPLY, new int[]{1}, new String[]{"Something else entirely here"}));
        assertNull(PlanOverlay.match("No questions here.", new int[]{1}, new String[]{"Should the beta channel get it first?"}));
    }
}
