package io.github.katachinosekai.quillremote;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class PortraitOrderTest {
    @Test
    public void keepsGivenOrderAcrossFolders() {
        SessionBars.Item a = new SessionBars.Item("a", "X", "A");
        SessionBars.Item b = new SessionBars.Item("b", "Y", "B");
        SessionBars.Item c = new SessionBars.Item("c", "X", "C");
        List<SessionBars.Item> r = SessionBars.portraitOrder(Arrays.asList(c, a, b));
        assertEquals(Arrays.asList(c, a, b), r);
    }

    @Test
    public void dropsRepeatedIds() {
        SessionBars.Item a = new SessionBars.Item("a", "", "A");
        SessionBars.Item b = new SessionBars.Item("b", "", "B");
        SessionBars.Item a2 = new SessionBars.Item("a", "", "A again");
        assertEquals(Arrays.asList(a, b), SessionBars.portraitOrder(Arrays.asList(a, b, a2)));
    }

    @Test
    public void openTabsFirstInTabOrderThenRestAsArrived() {
        SessionBars.Item a = new SessionBars.Item("a", "X", "A", -1);
        SessionBars.Item b = new SessionBars.Item("b", "Y", "B", 1);
        SessionBars.Item c = new SessionBars.Item("c", "X", "C", 0);
        SessionBars.Item d = new SessionBars.Item("d", "Y", "D", -1);
        assertEquals(Arrays.asList(c, b, a, d), SessionBars.portraitOrder(Arrays.asList(a, b, d, c)));
    }

    @Test
    public void missingTabFieldKeepsArrivalOrder() {
        SessionBars.Item a = new SessionBars.Item("a", "X", "A");
        SessionBars.Item b = new SessionBars.Item("b", "Y", "B");
        assertEquals(Arrays.asList(b, a), SessionBars.portraitOrder(Arrays.asList(b, a)));
    }
}
