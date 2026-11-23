package io.github.katachinosekai.quillremote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class RelockTest {
    @Test
    public void silentUnlockSkipsThePasswordPrompt() {
        List<String> log = new ArrayList<>();
        boolean wait = Relock.silentFirst(() -> {
            log.add("lan");
            return true;
        }, () -> true, () -> log.add("unlocked"), () -> log.add("prompt"));
        assertFalse(wait);
        assertEquals(List.of("lan", "unlocked"), log);
    }

    @Test
    public void failedSilentUnlockTriesLanBeforeAskingForThePassword() {
        List<String> log = new ArrayList<>();
        boolean wait = Relock.silentFirst(() -> {
            log.add("lan");
            return false;
        }, () -> true, () -> log.add("unlocked"), () -> log.add("prompt"));
        assertTrue(wait);
        assertEquals(List.of("lan", "prompt"), log);
    }

    @Test
    public void staleConnectionShowsNoPrompt() {
        List<String> log = new ArrayList<>();
        assertFalse(Relock.silentFirst(() -> false, () -> false, () -> log.add("unlocked"), () -> log.add("prompt")));
        assertTrue(log.isEmpty());
    }
}
