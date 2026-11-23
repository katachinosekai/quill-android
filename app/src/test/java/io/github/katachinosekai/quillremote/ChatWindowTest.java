package io.github.katachinosekai.quillremote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class ChatWindowTest {
    static ChatView.Item item(int seq, String kind, String text) {
        ChatView.Item it = new ChatView.Item();
        it.seq = seq;
        it.kind = kind;
        it.text = text;
        return it;
    }

    /** A busy coordinator tail: blank thinking, tool-only replies, empty notices and user turns, then a reply. */
    static List<ChatView.Item> busy(int tail) {
        List<ChatView.Item> l = new ArrayList<>();
        int seq = 1;
        for (int i = 0; i < 40; i++) {
            l.add(item(seq++, "user", "message " + i));
            l.add(item(seq++, "assistant", "reply " + i));
        }
        for (int i = 0; i < tail; i++) {
            l.add(item(seq++, "thinking", ""));
            l.add(item(seq++, "assistant", ""));
            l.add(item(seq++, "notice", ""));
            l.add(item(seq++, "user", ""));
            l.add(item(seq++, "mystery", ""));
        }
        l.add(item(seq++, "assistant", "The subagent finished."));
        return l;
    }

    @Test
    public void emptyItemsDoNotUseTheWindow() {
        List<ChatView.Item> l = busy(30); // 150 empty items after 80 visible ones, then one reply
        int start = ChatView.windowStart(l, 50);
        assertEquals(50, ChatView.visibleCount(l, start, l.size()));
        assertEquals(81 - 50, ChatView.visibleCount(l, 0, start));
        assertFalse(ChatView.empty(l.get(start)));
    }

    @Test
    public void toolsAreVisibleAndCounted() {
        List<ChatView.Item> l = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            l.add(item(i, i % 3 == 0 ? "assistant" : "tool", i % 3 == 0 ? "" : "x"));
        }
        int start = ChatView.windowStart(l, 50);
        assertEquals(50, ChatView.visibleCount(l, start, l.size()));
    }

    @Test
    public void shortListShowsEverything() {
        List<ChatView.Item> l = busy(2);
        assertEquals(0, ChatView.windowStart(l, 500));
    }
}
