package io.github.katachinosekai.quillremote;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PhoneApiTest {
    @Test
    public void ipLiterals() {
        assertTrue(PhoneApi.ipLiteral("192.168.1.50"));
        assertTrue(PhoneApi.ipLiteral("100.64.0.7"));
        assertTrue(PhoneApi.ipLiteral("fd7a:115c:a1e0::1"));
        assertTrue(PhoneApi.ipLiteral("[fe80::1]"));
        assertFalse(PhoneApi.ipLiteral("desktop.local"));
        assertFalse(PhoneApi.ipLiteral("desktop.tail1234.ts.net"));
    }
}
