package io.github.katachinosekai.quillremote;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import org.junit.Test;

/** The kmem assistant's reply with two numbered option questions (Quill Android 0.9.35 showed an empty bubble). */
public class KmemChatTest {
    static final String REPLY = "Two things before I write these.\n\n"
            + "1. **How should \"Plan approval scope\" be stored? Pinned fact now, rule at the migration (recommended), shared rule now, or not at all?**\n\n"
            + "2. **When should I start t1b6536? Write it now and release it as 1.10.0 after the 1.9.1 rollout (recommended), wait until after the rollout, or release it as soon as it's ready?**";

    static ChatView.Item item(int seq, String kind, String text, boolean streaming) {
        ChatView.Item it = new ChatView.Item();
        it.seq = seq;
        it.kind = kind;
        it.text = text;
        it.streaming = streaming;
        it.state = "pending";
        return it;
    }

    @Test
    public void replyAndPlanDrawSomething() {
        ChatView.Item r = item(2, "assistant", REPLY, false);
        assertFalse(ChatView.empty(r));
        assertFalse(ChatView.empty(item(3, "plan", REPLY, false)));
        // The plan's questions line up with the reply, so the overlay shows the reply text with controls under each question.
        int[][] m = PlanOverlay.match(r.text, new int[]{1, 2}, new String[]{"How should \"Plan approval scope\" be stored?", "When should I start t1b6536?"});
        assertNotNull(m);
        assertArrayEquals(new int[]{2, 3}, m[0]);
        assertArrayEquals(new int[]{4, 5}, m[1]);
        // With a pending plan the reply gets no quick-choice buttons (the plan's controls are the only ones).
        assertTrue(QuestionCards.quickSuppressed(Arrays.asList("user", "assistant", "plan"), Arrays.asList(false, false, true)).contains(1));
        assertTrue(QuestionCards.quickSuppressed(Arrays.asList("user", "assistant"), Arrays.asList(false, false)).isEmpty());
    }

    @Test
    public void blankRepliesAreReloadedOnce() {
        List<ChatView.Item> items = new ArrayList<>();
        items.add(item(1, "assistant", REPLY, false));
        items.add(item(2, "assistant", "", false));
        HashSet<String> healed = new HashSet<>();
        assertEquals(Arrays.asList("7:2"), KmemAssistant.blankKeys(items, true, 7, healed));
        healed.add("7:2");
        assertTrue(KmemAssistant.blankKeys(items, true, 7, healed).isEmpty());
        // Still "streaming" while the assistant isn't running is blank too; while it runs, it is just starting.
        List<ChatView.Item> s = Arrays.asList(item(4, "assistant", "", true));
        assertTrue(KmemAssistant.blankKeys(s, true, 7, new HashSet<>()).isEmpty());
        assertEquals(1, KmemAssistant.blankKeys(s, false, 7, new HashSet<>()).size());
    }

    @Test
    public void modelLineNamesTheDefault() {
        assertEquals("Model: Online Claude (default)", KmemAssistModel.modelLine("default", "Online Claude (default)"));
        assertEquals("Model: Online Claude (default)", KmemAssistModel.modelLine("default", "New sessions"));
        assertEquals("Model: opus (kmem assistant setting)", KmemAssistModel.modelLine("opus", "kmem assistant setting"));
        assertEquals("Model: opus", KmemAssistModel.modelLine("opus", ""));
    }
}
