package io.github.katachinosekai.quillremote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.After;
import org.junit.Test;

public class PairingTest {
    static final String FP_A = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    static final String FP_B = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";

    static Map<String, String> link(String fp, String name) {
        Map<String, String> q = new HashMap<>();
        q.put("h", "192.0.2.10,desktop.example");
        q.put("p", "43210");
        q.put("k", "AAAAAAAAAAAAAAAAAAAAAAAA");
        q.put("fp", fp);
        q.put("i", "a1b2c3d4");
        q.put("v", "2");
        q.put("n", name);
        return q;
    }

    @After
    public void reset() {
        PhoneApi.dropUnlock();
    }

    @Test
    public void parsingSavesNothing() {
        FakePrefs prefs = new FakePrefs();
        FolderSync.Pairing p = FolderSync.parse(link(FP_A, "host-a")::get);
        assertNotNull(p);
        assertEquals("192.0.2.10,desktop.example", p.hosts);
        assertEquals(43210, p.port);
        assertEquals(FP_A, p.fp);
        assertEquals("host-a", p.name);
        assertTrue(prefs.map.isEmpty());
        assertFalse(FolderSync.paired(prefs));
    }

    @Test
    public void rejectsLinksWithoutUsableData() {
        Map<String, String> q = link("nothex", "x");
        assertNull(FolderSync.parse(q::get));
        q = link(FP_A, "x");
        q.put("p", "80");
        assertNull(FolderSync.parse(q::get));
        assertNull(FolderSync.parse(new HashMap<String, String>()::get));
    }

    @Test
    public void confirmationShowsHostAndCertificateAndWhatItReplaces() {
        FolderSync.Pairing p = FolderSync.parse(link(FP_B, "host-b")::get);
        String fresh = FolderSync.confirmText(p, null);
        assertTrue(fresh.contains("host-b"));
        assertTrue(fresh.contains("192.0.2.10, desktop.example"));
        assertTrue(fresh.contains("fedc ba98 7654 3210"));
        assertFalse(fresh.contains("replaces"));
        assertTrue(FolderSync.confirmText(p, "host-a").contains("replaces the pairing with host-a"));
    }

    @Test
    public void unlockTokenOnlyGoesToTheQuillDesktopThatIssuedIt() {
        PhoneApi.unlocked("token-placeholder", FP_A);
        assertEquals("token-placeholder", PhoneApi.unlockToken(FP_A));
        assertNull(PhoneApi.unlockToken(FP_B));
        assertNull(PhoneApi.unlockToken(""));
    }

    @Test
    public void rePairDropsTheUnlockTokenAndTheOldHomeNetworkKey() {
        FakePrefs prefs = new FakePrefs();
        FolderSync.apply(prefs, FolderSync.parse(link(FP_A, "host-a")::get));
        prefs.edit().putString("lanKey.registered", "hash-placeholder").apply();
        PhoneApi.unlocked("token-placeholder", FP_A);

        FolderSync.apply(prefs, FolderSync.parse(link(FP_B, "host-b")::get));
        assertFalse(PhoneApi.isUnlocked());
        assertEquals(FP_B, prefs.getString("pair.fp", ""));
        assertEquals("host-b", prefs.getString("pair.name", ""));
        assertEquals(2, prefs.getInt("pair.v", 0));
        assertFalse(prefs.contains("lanKey.registered"));
    }

    @Test
    public void rePairWithTheSameQuillDesktopKeepsItsHomeNetworkKey() {
        FakePrefs prefs = new FakePrefs();
        FolderSync.apply(prefs, FolderSync.parse(link(FP_A, "host-a")::get));
        prefs.edit().putString("lanKey.registered", "hash-placeholder").apply();
        PhoneApi.unlocked("token-placeholder", FP_A);
        FolderSync.apply(prefs, FolderSync.parse(link(FP_A, "host-a")::get));
        assertFalse(PhoneApi.isUnlocked());
        assertEquals("hash-placeholder", prefs.getString("lanKey.registered", ""));
    }

    @Test
    public void unpairDropsTheUnlockToken() {
        FakePrefs prefs = new FakePrefs();
        FolderSync.apply(prefs, FolderSync.parse(link(FP_A, "host-a")::get));
        PhoneApi.unlocked("token-placeholder", FP_A);
        FolderSync.clearPairing(prefs);
        assertFalse(PhoneApi.isUnlocked());
        assertFalse(FolderSync.paired(prefs));
    }
}
