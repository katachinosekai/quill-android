package io.github.katachinosekai.quillremote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class KmemModelsTest {
    static KmemModels.Task t(String id, int prio, String topic, String claim, String owner, boolean user, boolean issue) {
        return new KmemModels.Task(id, prio, "open", "Title " + id, topic, owner, claim, user, issue);
    }

    @Test
    public void buttonOnlyForKnownLevelAndOn() {
        assertTrue(KmemModels.showButton(1, true));
        assertTrue(KmemModels.showButton(2, true));
        assertFalse(KmemModels.showButton(0, true));
        assertFalse(KmemModels.showButton(1, false));
    }

    @Test
    public void bubbleAndRun() {
        assertEquals("", KmemModels.bubble(0, ""));
        assertEquals("7", KmemModels.bubble(7, ""));
        assertEquals("!7", KmemModels.bubble(7, "plan"));
        assertEquals("!", KmemModels.bubble(0, "question"));
        assertEquals("99+", KmemModels.bubble(120, null));
        assertTrue(KmemModels.running("live"));
        assertTrue(KmemModels.running("saving"));
        assertTrue(KmemModels.running("stopping"));
        assertFalse(KmemModels.running("failed"));
        assertFalse(KmemModels.running("off"));
    }

    @Test
    public void claimLabels() {
        assertEquals("claimed by quill", KmemModels.claimLabel("live", "quill"));
        assertEquals("claim gone (quill)", KmemModels.claimLabel("gone", "quill"));
        assertEquals("lease expired", KmemModels.claimLabel("leaseExpired", ""));
        assertEquals("handoff by x", KmemModels.claimLabel("handoff", "x"));
        assertEquals("", KmemModels.claimLabel("", ""));
        assertEquals("[t1] p2 · claimed by quill", KmemModels.taskMeta(t("t1", 2, "a", "live", "quill", false, false)));
        assertEquals("[t1] p3", KmemModels.taskMeta(t("t1", 3, "a", "", "", false, false)));
    }

    @Test
    public void needsLinesSkipEmpty() {
        assertTrue(KmemModels.needsLines(0, "", 0, "", "", 0).isEmpty());
        List<String> l = KmemModels.needsLines(1, "2 problems", 3, "maint-1.txt", "wait", 2);
        assertEquals(5, l.size());
        assertEquals("Recycle bin: 1 item (empty it on Quill Desktop)", l.get(0));
        assertEquals("Maintenance: 3 warnings (report maint-1.txt)", l.get(2));
    }

    @Test
    public void filtersAndGroups() {
        List<KmemModels.Task> ts = Arrays.asList(
                t("b", 2, "quill/android", "live", "quill", false, false),
                t("a", 1, "quill/android", "", "", true, false),
                t("c", 3, "kmem", "", "", false, true),
                t("d", 1, "", "", "", false, false));
        assertEquals(Arrays.asList("All", "Waiting for you", "kmem issues", "kmem", "quill"), KmemModels.filters(ts));
        assertEquals(Arrays.asList("(no topic)", "kmem", "quill/android"),
                Arrays.asList(KmemModels.group(ts, "All", "").keySet().toArray(new String[0])));
        assertEquals("a", KmemModels.group(ts, "quill", "").get("quill/android").get(0).id);
        assertEquals(1, KmemModels.group(ts, "Waiting for you", "").size());
        assertEquals(1, KmemModels.group(ts, "All", "TITLE c").size());
        assertTrue(KmemModels.group(ts, "kmem issues", "zzz").isEmpty());
    }

    @Test
    public void errors() {
        assertEquals("kmem isn't available on Quill Desktop right now.", KmemModels.errorLine(503, "kmem_unavailable"));
        assertTrue(KmemModels.errorLine(409, "kmem_busy").contains("busy"));
        assertTrue(KmemModels.errorLine(0, "unreachable").contains("reachable"));
    }
}
