package io.github.katachinosekai.quillremote;

import static org.junit.Assert.assertEquals;

import java.util.List;
import org.junit.Test;

/** Numbered and bulleted steps in a plan card's prose each get a block of their own, keeping their numbers. */
public class PlanListLinesTest {
    static final String REPLY = "The full suite passes on master 25309c0. The gate refused step 3.\n\n"
            + "1. Rebuild Quill Desktop from master `25309c0` and live-reload it, then check that the new process runs the new commit.\n"
            + "2. Add this case to gate bug task t4b7838.\n\n"
            + "**Can I run steps 1–2? Yes (recommended) or no?**";

    static String markers(String text) {
        StringBuilder sb = new StringBuilder();
        for (Markdown.Block b : Markdown.parse(text)) {
            if (b.type == Markdown.Type.NUMBERED || b.type == Markdown.Type.BULLET) {
                sb.append(b.marker).append(' ');
            }
        }
        return sb.toString().trim();
    }

    @Test
    public void exactReplyGivesTwoNumberedBlocks() {
        assertEquals("1. 2.", markers(REPLY));
        // The part the plan card draws above the approval question, as PlanOverlay.lines cuts it.
        assertEquals("1. 2.", markers(PlanOverlay.lines(REPLY, 0, 5)));
    }

    @Test
    public void itemsSplitByQuestionsKeepTheirNumbers() {
        String reply = "Plan:\n1. Edit the first file.\n2. Build it.\n\nShould rule 21 stay as it is?\n\n3. Then run the check.\n4. Ship it.\n";
        assertEquals("1. 2.", markers(PlanOverlay.lines(reply, 0, 4)));
        assertEquals("3. 4.", markers(PlanOverlay.lines(reply, 4, Integer.MAX_VALUE)));
        assertEquals("- -".replace("-", "•"), markers("- one here\n- two here"));
        List<Markdown.Block> bs = Markdown.parse(PlanOverlay.lines(reply, 6, Integer.MAX_VALUE));
        assertEquals(2, bs.size());
    }
}
