package io.github.katachinosekai.quillremote;

import static org.junit.Assert.assertEquals;

import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public class UpdateNotesTest {
    @Test
    public void showsEntriesAddedSinceTheLastVersionOnce() {
        Set<String> none = new HashSet<>();
        assertEquals(1, UpdateNotes.pending(40, 41, none).size()); // 0.9.25 → 0.9.26
        assertEquals(1, UpdateNotes.pending(30, 42, none).size()); // skipped versions too
        assertEquals(0, UpdateNotes.pending(41, 42, none).size()); // already past it
        assertEquals(0, UpdateNotes.pending(0, 41, none).size()); // fresh install: no questions
        assertEquals(0, UpdateNotes.pending(40, 41, Set.of("lanScreenLock")).size()); // done stays done
        assertEquals(0, UpdateNotes.pending(40, 40, none).size()); // downgraded below it: not shown, answers kept
    }
}
