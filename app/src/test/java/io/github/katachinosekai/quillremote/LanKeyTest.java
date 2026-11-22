package io.github.katachinosekai.quillremote;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LanKeyTest {
    @Test
    public void signedMessageBindsProtocolDeviceNonceAndCertificate() {
        assertEquals("quill-unlock-v1|a1b2c3d4|n0nce|3c295d2a", LanKey.message("a1b2c3d4", "n0nce", "3c295d2a"));
    }
}
