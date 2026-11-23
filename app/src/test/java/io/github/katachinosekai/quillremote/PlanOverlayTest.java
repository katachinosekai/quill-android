package io.github.katachinosekai.quillremote;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
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
        assertNull(PlanOverlay.match(REPLY, new int[]{0}, new String[]{"Something else entirely here"}));
        assertNull(PlanOverlay.match(REPLY, new int[]{1}, new String[]{"Something else entirely here"}));
        assertNull(PlanOverlay.match("No questions here.", new int[]{1}, new String[]{"Should the beta channel get it first?"}));
    }

    @Test
    public void unnumberedQuestionsAreFoundByTheirText() {
        int[][] m = PlanOverlay.match(REPLY, new int[]{0, 0},
                new String[]{"Should the beta channel get it first? Yes or no?", "Which build? Stable or beta?"});
        assertArrayEquals(new int[]{6, 7}, m[0]);
        assertArrayEquals(new int[]{7, 10}, m[1]);
    }

    @Test
    public void aPlanWithoutQuestionsOverlaysWhenTheReplyHoldsItsApprovalQuestion() {
        String reply = "Plan:\n1. Edit x.\n\n**Can I run steps 1-2? Yes (recommended) or no?**";
        assertArrayEquals(new int[0][], PlanOverlay.matchPlan(reply, new int[0], new String[0], "Can I run steps 1-2? Yes (recommended) or no?"));
        assertNull(PlanOverlay.matchPlan("Running the checks first.", new int[0], new String[0], "Can I run steps 1-2? Yes (recommended) or no?"));
        assertNull(PlanOverlay.matchPlan(reply, new int[0], new String[0], ""));
        assertArrayEquals(new int[]{6, 7}, PlanOverlay.matchPlan(REPLY, new int[]{1}, new String[]{"Should the beta channel get it first?"}, "x?")[0]);
    }

    @Test
    public void approvalSpanFindsItsLineAndAnOptionsLineBelow() {
        String reply = "Plan.\n\n1. Edit x.\n\nCan I run steps 1-2?\nYes (recommended) or no?\n\nThanks.";
        assertArrayEquals(new int[]{4, 5}, PlanOverlay.approvalSpan(reply, "Can I run steps 1-2? Yes (recommended) or no?"));
        assertArrayEquals(new int[]{2, 2}, PlanOverlay.approvalSpan("a\nb\n**Do you approve?**\n\n", "Do you approve?"));
        assertNull(PlanOverlay.approvalSpan(reply, "Something else entirely?"));
        assertNull(PlanOverlay.approvalSpan(reply, ""));
    }

    @Test
    public void approvalCaptionNamesTheGoAheadOption() {
        java.util.List<String> opts = java.util.Arrays.asList("Yes", "No");
        assertEquals("Answering \"Yes\" runs this plan: every command and change, including its background jobs and subagents, is approved until the plan is done.",
                PlanOverlay.approvalCaption(opts, new boolean[]{false, true}, false));
        assertEquals("Answering \"Go on\" runs this plan: every command and change, including its background jobs and subagents, is approved until the plan is done. Unanswered questions are sent as \"no answer\".",
                PlanOverlay.approvalCaption(java.util.Arrays.asList("Stop", "Go on"), new boolean[]{true, false}, true));
    }

    @Test
    public void theApprovalQuestionSpanStartsAtItsLineSoTheBoxCanHoldIt() {
        String reply = "Plan:\n1. Edit x.\n\n**Can I run steps 1-2?**\nYes (recommended) or no?\n\nDone.";
        int[] span = PlanOverlay.approvalSpan(reply, "Can I run steps 1-2? Yes (recommended) or no?");
        assertArrayEquals(new int[]{3, 4}, span);
        assertEquals("Plan:\n1. Edit x.\n", PlanOverlay.lines(reply, 0, span[0]));
    }
}
