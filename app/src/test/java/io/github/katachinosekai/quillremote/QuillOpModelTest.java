package io.github.katachinosekai.quillremote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.Test;

public class QuillOpModelTest {
    @SuppressWarnings("unchecked")
    static Map<String, Object> j(String s) {
        return (Map<String, Object>) MiniJson.parse(s);
    }

    static final String STATE = "{\"v\":1,\"api\":1,\"state\":\"live\",\"status\":\"waiting\",\"waiting\":\"plan\","
            + "\"needs\":{\"count\":2,\"unread\":1,\"items\":[]},\"actions\":[\"stop\",\"send\"],"
            + "\"operator\":{\"state\":\"live\",\"waiting\":\"plan\",\"epoch\":3,\"rev\":9,\"status\":\"waiting\",\"dot\":true},"
            + "\"changes\":[{\"n\":3,\"id\":\"animateSpeed\",\"label\":\"Text animation speed\",\"old\":\"normal\",\"new\":\"fast\","
            + "\"timeMs\":1795000000000,\"why\":\"the user's request\",\"by\":\"operator\",\"undone\":false,\"canUndo\":true},"
            + "{\"n\":2,\"id\":\"reduceMotion\",\"label\":\"Reduce motion\",\"old\":false,\"new\":true,\"undone\":true,\"canUndo\":true},"
            + "{\"id\":\"noNumber\"}]}";

    @Test
    public void sheetModelFromJson() {
        QuillOpModel.State s = QuillOpModel.parse(j(STATE));
        assertNotNull(s);
        assertEquals("live", s.state);
        assertEquals("waiting for you to approve a plan", s.line());
        assertEquals(2, s.count);
        assertTrue(s.dot);
        assertEquals("!2", s.bubble());
        assertEquals(2, s.changes.size()); // the entry without "n" is skipped
        assertNull(QuillOpModel.parse(null));
        assertEquals("working", QuillOpModel.parse(j("{\"state\":\"live\",\"status\":\"working\"}")).line());
        assertEquals("not running (panels only)", QuillOpModel.statusLine("panels", "off", ""));
        assertEquals("running in a tab on Quill Desktop", QuillOpModel.statusLine("elsewhere", "idle", ""));
        // a stream change keeps the status line and the change log
        QuillOpModel.State m = QuillOpModel.merge(s, j("{\"type\":\"quill\",\"needs\":{\"count\":0,\"unread\":0},\"operator\":{\"state\":\"live\",\"waiting\":\"\"},\"lastChange\":4}"));
        assertEquals(0, m.count);
        assertEquals("", m.waiting);
        assertEquals(2, m.changes.size());
    }

    @Test
    public void hiddenForOlderDesktop() {
        assertFalse(QuillOpModel.showButton(j("{\"v\":1,\"quill\":\"0.1.14\"}"))); // field left out
        assertFalse(QuillOpModel.showButton(j("{\"quillOperator\":{\"api\":1,\"on\":false}}")));
        assertFalse(QuillOpModel.showButton(j("{\"quillOperator\":{\"api\":0,\"on\":true}}")));
        assertFalse(QuillOpModel.showButton(null));
        assertTrue(QuillOpModel.showButton(j("{\"quillOperator\":{\"api\":1,\"on\":true}}")));
    }

    @Test
    public void changesAndUndoBody() {
        List<QuillOpModel.Change> l = QuillOpModel.parseChanges(j(STATE).get("changes"));
        QuillOpModel.Change c = l.get(0);
        assertEquals(3, c.n);
        assertEquals("Text animation speed: normal → fast", c.line());
        assertEquals("the user's request", c.why);
        assertTrue(c.undoable());
        QuillOpModel.Change u = l.get(1);
        assertEquals("false", u.oldValue);
        assertEquals("true", u.newValue);
        assertFalse(u.undoable()); // undone: the button is disabled even if canUndo says so
        assertEquals("{\"change\":3}", QuillOpModel.undoBody(3));
        assertTrue(QuillOpModel.parseChanges(null).isEmpty());
    }

    @Test
    public void notificationRouting() {
        assertTrue(QuillOpModel.opensSheet("quill"));
        assertFalse(QuillOpModel.opensSheet("kmem"));
        assertTrue(Notifier.TYPES.contains("quill"));
        assertEquals(Notifier.TYPES.size(), Notifier.LABELS.length);
        assertEquals("Quill operator needs you", Notifier.LABELS[Notifier.TYPES.indexOf("quill")]);
    }

    @Test
    public void topicQuillOpensSheet() {
        Map<String, Object> ok = j("{\"v\":1,\"switched\":true,\"operator\":true,\"starting\":false,\"topic\":{\"slug\":\"quill\"}}");
        assertEquals(QuillOpModel.OPEN_SHEET, QuillOpModel.topicOpen(ok));
        assertFalse(QuillOpModel.topicStarting(ok));
        Map<String, Object> st = j("{\"switched\":true,\"operator\":true,\"starting\":true}");
        assertEquals(QuillOpModel.OPEN_SHEET, QuillOpModel.topicOpen(st));
        assertTrue(QuillOpModel.topicStarting(st));
        assertEquals(QuillOpModel.OPEN_NONE, QuillOpModel.topicOpen(j("{\"session\":{\"id\":\"x\"},\"switched\":false}")));
        assertEquals(QuillOpModel.OPEN_NONE, QuillOpModel.topicOpen(null));
        assertTrue(QuillOpModel.isOperatorTopic(j("{\"slug\":\"quill\",\"live\":\"operator\"}")));
        assertFalse(QuillOpModel.isOperatorTopic(j("{\"slug\":\"quill\",\"live\":\"\"}")));
        assertFalse(QuillOpModel.isOperatorTopic(j("{\"slug\":\"alpha\",\"live\":\"operator\"}")));
    }

    @Test
    public void sheetSizes() {
        assertEquals(550, BottomSheet.heightFor(1000, false));
        assertEquals(1000, BottomSheet.heightFor(1000, true));
        assertEquals(1, BottomSheet.dragResult(-60, 40));
        assertEquals(-1, BottomSheet.dragResult(60, 40));
        assertEquals(0, BottomSheet.dragResult(10, 40));
    }
}
