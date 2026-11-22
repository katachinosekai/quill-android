package io.github.katachinosekai.quillremote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class DeltaTest {
    @Test
    public void entryCountsCodePointsAndCrcsUtf8() {
        // CRC-32 (IEEE) of "hello" is 3610a686
        assertEquals("7:5:3610a686", Delta.entry(7, "hello"));
        // an emoji is one code point (two Java chars); é is two UTF-8 bytes
        assertEquals(true, Delta.entry(3, "a😀é").startsWith("3:3:"));
    }

    @Test
    public void appendChecksBothLengths() {
        assertEquals("hello world", Delta.append("hello", 5, " world", 11));
        assertEquals("a😀bc", Delta.append("a😀", 2, "bc", 4));
        assertEquals("abc", Delta.append("ab", 2, "c", -1));
        assertNull(Delta.append("hello", 4, " world", 10)); // our copy isn't what the server thinks
        assertNull(Delta.append("hello", 5, " world", 12)); // result has the wrong length
        assertNull(Delta.append("", 0, "x", 1));
        assertNull(Delta.append(null, 3, "x", 4));
    }
}
