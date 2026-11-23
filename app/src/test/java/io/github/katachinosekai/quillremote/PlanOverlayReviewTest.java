package io.github.katachinosekai.quillremote;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

/** Review of fix/plan-overlay (task t96bf4a): what the overlay must match. Failing tests are open defects. */
public class PlanOverlayReviewTest {
    // The screenshot's shape (2026-11-23): context first, then the question. Quill Desktop sends a question's text as
    // its question sentence only (planQ.Text), not the whole line.
    static final String REPLY = "Findings.\n\n"
            + "1. Rule 12 is read twice. Merge the two reads into one (recommended), or leave both? Merge or leave?\n"
            + "2. Typing exactly \"Approved.\" approves even when no card is showing, because it's the card's own text."
            + " Count it only while a plan or approval question is waiting (recommended), or keep it as is? Only while waiting, or keep?";

    /** Cause: PlanOverlay.same compares only the line's first 40 characters (PlanOverlay.java:30-31). */
    @Test
    public void numberedQuestionAfterAContextSentenceIsMatched() {
        int[][] m = PlanOverlay.match(REPLY, new int[]{1, 2}, new String[]{
                "Merge the two reads into one (recommended), or leave both?",
                "Count it only while a plan or approval question is waiting (recommended), or keep it as is?"});
        assertNotNull("the reply's question lines are found (no card fallback)", m);
        assertArrayEquals(new int[]{2, 3}, m[0]);
        assertArrayEquals(new int[]{3, 4}, m[1]);
    }

    /** Cause: PlanOverlay.match doesn't skip fenced code (PlanOverlay.java:48-57); Quill Desktop's parser does. */
    @Test
    public void aQuestionInACodeBlockIsNotTheQuestion() {
        String reply = "Plan:\n1. Edit x.\n\n```\n1. Which colour should it be?\n```\n\n1. Which colour should it be? Red or blue?";
        int[][] m = PlanOverlay.match(reply, new int[]{1}, new String[]{"Which colour should it be? Red or blue?"});
        assertNotNull(m);
        assertArrayEquals(new int[]{7, 8}, m[0]); // line 7: the reply has 7 lines before it
    }
}
