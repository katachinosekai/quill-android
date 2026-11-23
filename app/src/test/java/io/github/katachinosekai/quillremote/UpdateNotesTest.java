package io.github.katachinosekai.quillremote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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

    @Test
    public void recommendedOutOfRangeFallsBackToTheFirstChoice() {
        assertEquals(1, UpdateNotes.recommended(1, 3));
        assertEquals(0, UpdateNotes.recommended(3, 3));
        assertEquals(0, UpdateNotes.recommended(-1, 3));
        assertEquals(0, UpdateNotes.recommended(Integer.MAX_VALUE, 2));
    }

    @Test
    public void onlyNoticesAreAcknowledgedWithoutAChoice() {
        assertEquals(UpdateNotes.DeskAction.NOTICE, UpdateNotes.deskAction("notice", 0));
        assertEquals(UpdateNotes.DeskAction.NOTICE, UpdateNotes.deskAction("Notice", 2));
        assertEquals(UpdateNotes.DeskAction.DESKTOP_ONLY, UpdateNotes.deskAction("changed", 0)); // was auto-acknowledged
        assertEquals(UpdateNotes.DeskAction.DESKTOP_ONLY, UpdateNotes.deskAction("", 0));
        assertEquals(UpdateNotes.DeskAction.CHOOSE, UpdateNotes.deskAction("new", 1));
        assertEquals(UpdateNotes.DeskAction.CHOOSE, UpdateNotes.deskAction("removed", UpdateNotes.MAX_CHOICES));
        assertEquals(UpdateNotes.DeskAction.DESKTOP_ONLY, UpdateNotes.deskAction("new", UpdateNotes.MAX_CHOICES + 1));
    }

    @Test
    public void longTextsAreCut() {
        String big = "x".repeat(UpdateNotes.MAX_TEXT * 3);
        String cut = UpdateNotes.clip(big, UpdateNotes.MAX_TEXT);
        assertEquals(UpdateNotes.MAX_TEXT, cut.length());
        assertTrue(cut.endsWith("…"));
        assertEquals("line one\nline two", UpdateNotes.clip(" line one\nline two ", 100)); // line breaks stay
        assertEquals("", UpdateNotes.clip(null, 10));
    }
}
