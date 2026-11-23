package io.github.katachinosekai.quillremote;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class RecentFirstTest {
    @Test
    public void newestFirstIgnoringFolder() {
        SessionBars.Item a = new SessionBars.Item("a", "X", "A");
        SessionBars.Item b = new SessionBars.Item("b", "Y", "B");
        SessionBars.Item c = new SessionBars.Item("c", "X", "C");
        Map<String, Long> t = new HashMap<>();
        t.put("a", 10L);
        t.put("b", 30L);
        t.put("c", 20L);
        List<SessionBars.Item> r = SessionBars.recentFirst(Arrays.asList(a, b, c), i -> t.get(i.id));
        assertEquals(Arrays.asList(b, c, a), r);
    }

    @Test
    public void undatedKeepOrderAfterDated() {
        SessionBars.Item a = new SessionBars.Item("a", "", "A");
        SessionBars.Item b = new SessionBars.Item("b", "", "B");
        SessionBars.Item c = new SessionBars.Item("c", "", "C");
        List<SessionBars.Item> r = SessionBars.recentFirst(Arrays.asList(a, b, c), i -> i.id.equals("c") ? 5L : 0L);
        assertEquals(Arrays.asList(c, a, b), r);
    }
}
