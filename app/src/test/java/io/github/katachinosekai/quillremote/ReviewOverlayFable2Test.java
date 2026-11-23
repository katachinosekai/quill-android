package io.github.katachinosekai.quillremote;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Independent review of fix/plan-overlay (review/overlay-fable2). Tests named failing_* fail on 77a3d21: each shows
 * a reply Quill Desktop overlays that the phone can't match, so the phone shows the separate card. ok_* pass.
 * No product code is changed here.
 */
public class ReviewOverlayFable2Test {
    /**
     * FAILING (medium): Quill Desktop sends a question's own sentence as its text (planquestions.go
     * questionSentences), so a question line that starts with another sentence never matches. Cause:
     * PlanOverlay.java:25-32 (same() compares the first 40 characters of the line with the question's).
     */
    @Test
    public void failing_questionAfterALeadInSentenceIsFound() {
        String reply = "Two things to settle.\n\n1. Should the notice stay as it is? Yes (recommended) or no?\n"
                + "2. This would cover whatever runs next. Count it only while a card is waiting (recommended), or keep it as is?";
        int[][] m = PlanOverlay.match(reply, new int[]{1, 2}, new String[]{"Should the notice stay as it is?",
                "Count it only while a card is waiting (recommended), or keep it as is?"});
        assertNotNull("a lead-in sentence before the question: the separate card is shown", m);
        assertArrayEquals(new int[]{3, 4}, m[1]);
    }

    /**
     * FAILING (medium): two questions on one numbered line (Quill Desktop sends both with the same num). Cause:
     * PlanOverlay.java:66 (from = end: the second question is searched after the first one's lines).
     */
    @Test
    public void failing_twoQuestionsOnOneLineAreFound() {
        String reply = "Plan:\n1. Edit x.\n\nQuestions:\n1. Should the beta channel get it first? And what should the tag say?\n2. Do you approve step 1?";
        int[][] m = PlanOverlay.match(reply, new int[]{1, 1},
                new String[]{"Should the beta channel get it first?", "And what should the tag say?"});
        assertNotNull("two questions on one line: the separate card is shown", m);
    }

    /**
     * FAILING (low): a numbered question in a bullet ("- 1. …"), which Quill Desktop reads as question 1
     * (planquestions.go qNumLead). Cause: PlanOverlay.java:13 (NUMBERED allows no bullet before the number).
     */
    @Test
    public void failing_bulletedNumberedQuestionIsFound() {
        String reply = "Plan:\n1. Edit x.\n\n- 1. Use blue or red?\n- 2. What should the tag say?\n\nDo you approve step 1?";
        assertNotNull("a bulleted numbered question: the separate card is shown",
                PlanOverlay.match(reply, new int[]{1, 2}, new String[]{"Use blue or red?", "What should the tag say?"}));
    }

    /** OK: an old Quill Desktop sends no num (0): the questions are found by their text. */
    @Test
    public void ok_oldDesktopWithoutNumbersStillOverlays() {
        String reply = "Plan:\n1. Edit x.\n\n1. Use blue or red?\n2. What should the tag say?\n\nDo you approve step 1?";
        int[][] m = PlanOverlay.match(reply, new int[]{0, 0}, new String[]{"Use blue or red?", "What should the tag say?"});
        assertArrayEquals(new int[]{3, 4}, m[0]);
        assertArrayEquals(new int[]{4, 5}, m[1]);
    }

    /**
     * OK: approval alone overlays when the reply holds the approval question or is the plan's own text (Quill Desktop's
     * planOverlayQs); a made-up title with the plan's text elsewhere falls back to the card.
     */
    @Test
    public void ok_approvalAloneNeedsItsQuestionInTheReply() {
        String reply = "1. Edit x.\n2. Build.\n\n**Do you approve steps 1-2?**";
        assertArrayEquals(new int[0][], PlanOverlay.matchPlan(reply, new int[0], new String[0], "Do you approve steps 1-2?"));
        String noQuestion = "**Plan (needs your yes):**\n1. Edit x.";
        // The reply is the plan's own text: overlaid, the buttons close it.
        assertArrayEquals(new int[0][], PlanOverlay.matchPlan(noQuestion, new int[0], new String[0], "Approve this plan?", noQuestion + "\n"));
        // The plan's text is elsewhere: the card.
        assertNull(PlanOverlay.matchPlan(noQuestion, new int[0], new String[0], "Approve this plan?", "A different plan."));
        assertNull(PlanOverlay.matchPlan(noQuestion, new int[0], new String[0], "Approve this plan?"));
    }
}
