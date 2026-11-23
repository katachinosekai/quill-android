package io.github.katachinosekai.quillremote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class KmemAssistModelTest {
    static KmemAssistModel m(String state, int left, long at, String... actions) {
        return new KmemAssistModel(state, "", "", "", 2, 5, left, "", "", KmemAssistModel.actionSet(Arrays.asList(actions)), null, at);
    }

    @Test
    public void actionsOffered() {
        KmemAssistModel a = m("live", -1, 0, "stop", "save", "send", "");
        assertTrue(a.can("stop") && a.can("save") && a.canSend());
        assertFalse(a.can("start"));
        assertFalse(a.can(""));
        assertFalse(m("off", -1, 0).canSend());
        assertTrue(KmemAssistModel.actionSet(Collections.emptyList()).isEmpty());
    }

    @Test
    public void questionCard() {
        KmemAssistModel.Question q = KmemAssistModel.question("Saving took too long.", Arrays.asList("Wait", "Stop anyway", "Keep running"));
        assertNotNull(q);
        assertEquals(3, q.choices.size());
        assertEquals("Stop anyway", q.choices.get(1));
        assertNull(KmemAssistModel.question("x", Collections.emptyList()));
        assertNull(KmemAssistModel.question("", Arrays.asList("a")));
        assertNull(KmemAssistModel.question(null, null));
    }

    @Test
    public void countdown() {
        KmemAssistModel a = m("saving", 42, 10_000);
        assertEquals(42, a.leftS(10_000));
        assertEquals(37, a.leftS(15_400));
        assertEquals(0, a.leftS(200_000));
        assertEquals(42, a.leftS(5_000)); // clock went back: never above the start
        assertEquals("saving its handoff, then stopping \u00b7 40 s", a.stateLine(12_000));
        assertEquals(-1, KmemAssistModel.left(-1, 0, 5000));
        assertEquals("running", m("live", -1, 0).stateLine(0));
    }

    @Test
    public void changeAndStale() {
        KmemAssistModel a = m("live", -1, 0);
        assertFalse(a.itemsChanged(2, 5));
        assertTrue(a.itemsChanged(2, 6));
        assertTrue(a.itemsChanged(3, 5));
        assertTrue(KmemAssistModel.alreadyDone(409, "stale"));
        assertFalse(KmemAssistModel.alreadyDone(409, "kmem_busy"));
        assertFalse(KmemAssistModel.alreadyDone(403, "stale"));
    }
}
