package io.github.katachinosekai.quillremote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class KmemAssistModelTest {
    static KmemAssistModel parse(String json, long now) throws Exception {
        return KmemAssistModel.parse(new JSONObject(json), now);
    }

    @Test
    public void parsesActionsAndFields() throws Exception {
        KmemAssistModel m = parse("{\"assistant\":{\"state\":\"live\",\"waiting\":\"plan\",\"model\":\"opus\",\"epoch\":3,"
                + "\"rev\":9,\"actions\":[\"stop\",\"save\",\"send\"]}}", 1000);
        assertEquals("live", m.state);
        assertEquals("plan", m.waiting);
        assertEquals(3, m.epoch);
        assertTrue(m.can("stop") && m.can("save") && m.canSend());
        assertFalse(m.can("start"));
        assertEquals(-1, m.saveLeftS);
        assertNull(m.question);
    }

    @Test
    public void actionsBesideAssistantAndMissing() throws Exception {
        KmemAssistModel m = parse("{\"assistant\":{\"state\":\"off\",\"reason\":\"The kmem assistant is off.\",\"button\":\"Start\"},"
                + "\"actions\":[\"start\"]}", 0);
        assertTrue(m.can("start"));
        assertEquals("Start", m.button);
        assertEquals("The kmem assistant is off.", m.reason);
        assertFalse(parse("{\"assistant\":{\"state\":\"off\"}}", 0).canSend());
        assertNull(parse("{\"items\":[]}", 0));
    }

    @Test
    public void questionCard() throws Exception {
        KmemAssistModel m = parse("{\"assistant\":{\"state\":\"saving\",\"actions\":[\"choose\"],\"question\":{\"text\":\"Saving took too long.\","
                + "\"choices\":[\"Wait\",\"Stop anyway\",\"Keep running\"]}}}", 0);
        assertNotNull(m.question);
        assertEquals(3, m.question.choices.size());
        assertEquals("Stop anyway", m.question.choices.get(1));
        assertNull(KmemAssistModel.question(new JSONObject("{\"text\":\"x\",\"choices\":[]}")));
        assertNull(KmemAssistModel.question(new JSONObject("{\"choices\":[\"a\"]}")));
        assertNull(KmemAssistModel.question(null));
    }

    @Test
    public void countdown() throws Exception {
        KmemAssistModel m = parse("{\"assistant\":{\"state\":\"saving\",\"saveLeftS\":42}}", 10_000);
        assertEquals(42, m.leftS(10_000));
        assertEquals(37, m.leftS(15_400));
        assertEquals(0, m.leftS(200_000));
        assertEquals(42, m.leftS(5_000)); // clock went back: never above the start
        assertEquals("saving its handoff, then stopping · 40 s", m.stateLine(12_000));
        assertEquals(-1, KmemAssistModel.left(-1, 0, 5000));
        assertEquals("running", parse("{\"assistant\":{\"state\":\"live\"}}", 0).stateLine(0));
    }

    @Test
    public void changeAndStale() throws Exception {
        KmemAssistModel m = parse("{\"assistant\":{\"state\":\"live\",\"epoch\":2,\"rev\":5}}", 0);
        assertFalse(m.itemsChanged(2, 5));
        assertTrue(m.itemsChanged(2, 6));
        assertTrue(m.itemsChanged(3, 5));
        assertTrue(KmemAssistModel.alreadyDone(409, "stale"));
        assertFalse(KmemAssistModel.alreadyDone(409, "kmem_busy"));
        assertFalse(KmemAssistModel.alreadyDone(403, "stale"));
    }
}
