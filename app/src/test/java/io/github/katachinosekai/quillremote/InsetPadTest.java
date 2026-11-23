package io.github.katachinosekai.quillremote;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

public class InsetPadTest {
    @Test
    public void barsOnly() {
        assertArrayEquals(new int[]{0, 24, 0, 48}, InsetPad.pad(0, 24, 0, 48, 0));
    }

    @Test
    public void keyboardReplacesNavBar() {
        assertArrayEquals(new int[]{0, 24, 0, 600}, InsetPad.pad(0, 24, 0, 48, 600));
    }

    @Test
    public void landscapeSideBarsAndCutout() {
        assertArrayEquals(new int[]{90, 0, 120, 0}, InsetPad.pad(90, 0, 120, 0, 0));
    }

    @Test
    public void negativeIgnored() {
        assertArrayEquals(new int[]{0, 0, 0, 0}, InsetPad.pad(-1, -1, -1, -1, -1));
    }
}
