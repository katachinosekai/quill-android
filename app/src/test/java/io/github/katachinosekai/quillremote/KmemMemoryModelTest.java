package io.github.katachinosekai.quillremote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class KmemMemoryModelTest {
    static KmemMemoryModel.Fact fact(String id, boolean pinned, boolean expired) {
        return new KmemMemoryModel.Fact(id, "decision", "demo/topic", "text", "", pinned, expired);
    }

    @Test
    public void queryLength() {
        assertFalse(KmemMemoryModel.validQuery(null));
        assertFalse(KmemMemoryModel.validQuery("a"));
        assertFalse(KmemMemoryModel.validQuery(" a "));
        assertTrue(KmemMemoryModel.validQuery("ab"));
        assertTrue(KmemMemoryModel.validQuery(new String(new char[200]).replace('\0', 'x')));
        assertFalse(KmemMemoryModel.validQuery(new String(new char[201]).replace('\0', 'x')));
    }

    @Test
    public void findDelay() {
        // typing settled, no earlier find: send now
        assertEquals(0, KmemMemoryModel.findDelayMs(10_000, 9_000, 0, 0));
        // just typed: wait out the debounce
        assertEquals(500, KmemMemoryModel.findDelayMs(10_000, 10_000, 0, 0));
        // last find 300 ms ago: at most one per second
        assertEquals(700, KmemMemoryModel.findDelayMs(10_000, 8_000, 9_700, 0));
        // 429 with Retry-After: wait until then
        assertEquals(3_000, KmemMemoryModel.findDelayMs(10_000, 8_000, 9_700, 13_000));
    }

    @Test
    public void previewKeepsFirstLines() {
        assertEquals("", KmemMemoryModel.preview(null, 2));
        assertEquals("one", KmemMemoryModel.preview("one", 2));
        assertEquals("one\ntwo", KmemMemoryModel.preview("one\n\n  two  \n", 2));
        assertEquals("one\ntwo …", KmemMemoryModel.preview("one\ntwo\nthree", 2));
    }

    @Test
    public void metaAndTopicLines() {
        assertEquals("decision · demo/topic", KmemMemoryModel.factMeta(fact("f1", false, false)));
        assertEquals("decision · demo/topic · pinned · expired", KmemMemoryModel.factMeta(fact("f1", true, true)));
        assertEquals("fact", KmemMemoryModel.factMeta(new KmemMemoryModel.Fact("", "", "", "x", "", false, false)));
        assertEquals("demo · 1 fact · 2 KB", KmemMemoryModel.topicLine(new KmemMemoryModel.Topic("demo", 1, 2)));
        assertEquals("demo · 5 facts · 0 KB", KmemMemoryModel.topicLine(new KmemMemoryModel.Topic("demo", 5, 0)));
    }

    @Test
    public void plainRowsAreNotOpenable() {
        assertTrue(fact("f12", false, false).openable());
        assertFalse(fact(null, false, false).openable());
        assertFalse(fact("", false, false).openable());
    }

    @Test
    public void timesStatesValidity() {
        assertEquals("2026-11-22 19:22", KmemMemoryModel.shortTime("2026-11-22T19:22:20-08:00"));
        assertEquals("2026-11-22", KmemMemoryModel.shortTime("2026-11-22"));
        assertEquals("current", KmemMemoryModel.stateText(""));
        assertEquals("retired", KmemMemoryModel.stateText("retired"));
        assertEquals("superseded by a newer fact", KmemMemoryModel.stateText("superseded"));
        assertEquals("", KmemMemoryModel.validity("", ""));
        assertEquals("from 2026-01-01", KmemMemoryModel.validity("2026-01-01", ""));
        assertEquals("until 2026-02-01", KmemMemoryModel.validity("", "2026-02-01"));
        assertEquals("2026-01-01 to 2026-02-01", KmemMemoryModel.validity("2026-01-01", "2026-02-01"));
        assertEquals("2026-11-22 19:22 · retired · by demo",
                KmemMemoryModel.historyLine(new KmemMemoryModel.History("2026-11-22T19:22:20-08:00", "retired", "demo")));
        assertEquals("note", KmemMemoryModel.historyLine(new KmemMemoryModel.History("", "note", "")));
    }

    @Test
    public void detailRowsSkipEmptyOnes() {
        KmemMemoryModel.Fact f = new KmemMemoryModel.Fact("f1", "decision", "demo", "text", "because", true, false);
        KmemMemoryModel.Detail d = new KmemMemoryModel.Detail(f, "superseded", false, "2026-11-22T19:22:20-08:00", "demo", "", "", "", "", "f2", null);
        List<String[]> rows = KmemMemoryModel.detailRows(d);
        StringBuilder sb = new StringBuilder();
        for (String[] r : rows) {
            sb.append(r[0]).append('=').append(r[1]).append(';');
        }
        assertEquals("Kind=decision;Topic=demo;Recorded=2026-11-22 19:22;By=demo;Why=because;State=superseded by a newer fact;Pinned=yes;", sb.toString());
        assertEquals("f2", d.supersededBy);
        assertTrue(d.history.isEmpty());
    }

    @Test
    public void errorLines() {
        assertTrue(KmemMemoryModel.errorLine(404, "not_found").startsWith("Not found"));
        assertTrue(KmemMemoryModel.errorLine(400, "bad_request").contains("didn't accept"));
        assertEquals(KmemModels.errorLine(503, "kmem_unavailable"), KmemMemoryModel.errorLine(503, "kmem_unavailable"));
        assertEquals(KmemModels.errorLine(0, "unreachable"), KmemMemoryModel.errorLine(0, "unreachable"));
    }

    @Test
    public void navigationWithinTheTab() {
        KmemMemoryModel.Nav n = new KmemMemoryModel.Nav();
        assertFalse(n.canBack());
        assertFalse(n.back());
        n.push(KmemMemoryModel.Page.TOPIC, "demo");
        n.push(KmemMemoryModel.Page.TOPIC, "demo"); // same page twice: one entry
        n.push(KmemMemoryModel.Page.TOPIC, "demo/sub");
        n.push(KmemMemoryModel.Page.FACT, "f1");
        assertEquals(4, n.depth());
        assertEquals("f1", n.current().key);
        assertTrue(n.back());
        assertEquals("demo/sub", n.current().key);
        assertTrue(n.back());
        assertEquals("demo", n.current().key);
        n.push(KmemMemoryModel.Page.FACT, "f9");
        n.reset();
        assertEquals(KmemMemoryModel.Page.HOME, n.current().type);
        assertFalse(n.canBack());
    }
}
