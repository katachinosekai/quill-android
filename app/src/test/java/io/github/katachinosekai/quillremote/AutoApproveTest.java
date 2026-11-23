package io.github.katachinosekai.quillremote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AutoApproveTest {
    @SuppressWarnings("unchecked")
    static Object field(String json) {
        return ((java.util.Map<String, Object>) MiniJson.parse(json)).get("autoApprove");
    }

    @Test
    public void chipShowsOnlyWhenTheSessionSaysTrue() {
        assertTrue(AutoApprove.on(field("{\"id\":\"a\",\"autoApprove\":true}")));
        assertFalse(AutoApprove.on(field("{\"id\":\"a\",\"autoApprove\":false}")));
        assertFalse(AutoApprove.on(field("{\"id\":\"a\"}")));
        assertFalse(AutoApprove.on((Object) null));
        assertEquals("", AutoApprove.listMark(false));
        assertTrue(AutoApprove.listMark(true).contains("auto-approve"));
    }

    @Test
    public void offCallGoesToTheAutoApproveOffEndpoint() {
        assertEquals("/phone/session/s1/auto-approve-off", PhoneApi.sessionPath("s1", AutoApprove.OFF_PATH));
    }

    @Test
    public void approvalBodyHasKeepApprovingOnlyWhenTicked() {
        assertFalse(AutoApprove.sendKeep(false, true, "approvePlan"));
        assertTrue(AutoApprove.sendKeep(true, true, "approvePlan"));
        assertFalse(AutoApprove.sendKeep(true, true, "rejectPlan"));
        assertFalse(AutoApprove.sendKeep(true, false, "approvePlan"));
    }

    @Test
    public void olderQuillDesktopHidesEverything() {
        assertFalse(AutoApprove.supported("", false));
        assertFalse(AutoApprove.supported(null, false));
        assertFalse(AutoApprove.supported("0.1.25", false));
        assertFalse(AutoApprove.supported("0.0.99", false));
        assertTrue(AutoApprove.supported("0.1.26", false));
        assertTrue(AutoApprove.supported("0.2.0", false));
        assertTrue(AutoApprove.supported("1.0", false));
        assertTrue(AutoApprove.supported("dev", false));
        assertTrue(AutoApprove.supported("", true));
    }
}
