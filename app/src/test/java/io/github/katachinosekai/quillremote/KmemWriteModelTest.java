package io.github.katachinosekai.quillremote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class KmemWriteModelTest {
    @Test
    public void resultValidation() {
        assertNull(KmemWriteModel.validResult(null));
        assertNull(KmemWriteModel.validResult("   "));
        assertEquals("ok", KmemWriteModel.validResult("  ok "));
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            s.append('x');
        }
        assertEquals(300, KmemWriteModel.validResult(s.toString()).length());
        assertNull(KmemWriteModel.validResult(s + "y"));
        assertEquals("2/300", KmemWriteModel.counter(" ab "));
    }

    @Test
    public void errorMapping() {
        assertEquals(KmemWriteModel.Next.REPREPARE, KmemWriteModel.next(409, "stale"));
        assertEquals(KmemWriteModel.Next.REPREPARE, KmemWriteModel.next(409, "confirm_invalid"));
        assertEquals(KmemWriteModel.Next.REPREPARE, KmemWriteModel.next(409, "confirm_expired"));
        assertEquals(KmemWriteModel.Next.SHOW_MESSAGE, KmemWriteModel.next(409, "kmem_refused"));
        assertEquals(KmemWriteModel.Next.RETRY_LATER, KmemWriteModel.next(409, "kmem_busy"));
        assertEquals(KmemWriteModel.Next.RETRY_LATER, KmemWriteModel.next(429, "rate"));
        assertEquals(KmemWriteModel.Next.UNAVAILABLE, KmemWriteModel.next(503, "kmem_unavailable"));
        assertEquals(KmemWriteModel.Next.CONTROL_OFF, KmemWriteModel.next(403, "control_off"));
        assertEquals(KmemWriteModel.Next.UNLOCK, KmemWriteModel.next(401, "locked"));
        assertEquals(KmemWriteModel.Next.OTHER, KmemWriteModel.next(400, "bad"));
        assertTrue(KmemWriteModel.refreshAfter(409, "stale"));
        assertFalse(KmemWriteModel.refreshAfter(409, "kmem_refused"));
        assertEquals("kmem refused: live claim", KmemWriteModel.failText(409, "kmem_refused", "live claim"));
    }

    @Test
    public void binRows() {
        KmemWriteModel.BinItem a = new KmemWriteModel.BinItem("b1", "fact", 1, "qa", "dup", "a/b", "");
        KmemWriteModel.BinItem b = new KmemWriteModel.BinItem("b2", "task", 5, "", "", "a", "T");
        assertEquals("a/b", a.label());
        assertEquals("fact · 1 day · by qa", KmemWriteModel.binMeta(a));
        assertEquals("T", b.label());
        assertEquals("a", KmemWriteModel.binTopic(b));
        assertEquals("", KmemWriteModel.binTopic(a));
        assertEquals("task · 5 days", KmemWriteModel.binMeta(b));
        assertEquals("b3", new KmemWriteModel.BinItem("b3", "", 0, "", "", "", "").label());
    }

    @Test
    public void taskActions() {
        KmemModels.Task t = new KmemModels.Task("t1", 1, "open", "x", "", "", "", false, false, java.util.Arrays.asList("done", "release"));
        assertTrue(t.can("release"));
        assertFalse(new KmemModels.Task("t2", 1, "open", "y", "", "", "", false, false).can("done"));
    }
}
