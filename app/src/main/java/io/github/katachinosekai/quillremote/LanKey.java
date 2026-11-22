package io.github.katachinosekai.quillremote;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;

/**
 * Trusted home network (user 2026-11-22): instead of the device password, Quill Android proves who it is with a P-256
 * key in Android's hardware Keystore that can never be exported. Quill Desktop only offers this on its own local
 * network, for devices it is switched on for, and asks for the password again every 30 days or when the network
 * changes. By default the key also needs the phone's own screen lock (PIN, pattern or fingerprint) within the last
 * AUTH_SECONDS, so a stolen phone on the home Wi-Fi still can't unlock Quill Desktop.
 */
final class LanKey {
    static final String ALIAS = "quill-lan-unlock";
    static final int AUTH_SECONDS = 300;

    private LanKey() {
    }

    /**
     * The text the phone signs (Quill Desktop's format): bound to this protocol, this device, Quill Desktop's fresh
     * single-use nonce (~60 s) and the certificate fingerprint the phone pinned at pairing, so a signature only works
     * for the exact Quill Desktop this phone is paired with. Fingerprint format: as stored in pair.fp (to be confirmed
     * against Quill Desktop's final API shape).
     */
    static String message(String deviceId, String nonce, String certFp) {
        return "quill-unlock-v1|" + deviceId + "|" + nonce + "|" + certFp;
    }

    static KeyStore store() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        return ks;
    }

    static boolean exists() {
        try {
            return store().containsAlias(ALIAS);
        } catch (Exception e) {
            return false;
        }
    }

    /** A new key (replacing any old one); screenLock = the key needs the phone's screen lock within AUTH_SECONDS. */
    static PublicKey create(boolean screenLock) throws Exception {
        delete();
        KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256);
        if (screenLock) {
            b.setUserAuthenticationRequired(true)
                    .setUserAuthenticationParameters(AUTH_SECONDS, KeyProperties.AUTH_DEVICE_CREDENTIAL | KeyProperties.AUTH_BIOMETRIC_STRONG)
                    .setInvalidatedByBiometricEnrollment(false);
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
        g.initialize(b.build());
        return g.generateKeyPair().getPublic();
    }

    static PublicKey publicKey() throws Exception {
        java.security.cert.Certificate c = store().getCertificate(ALIAS);
        return c == null ? null : c.getPublicKey();
    }

    /** base64 (no wrap) of the public key's SubjectPublicKeyInfo, as Quill Desktop stores it. */
    static String publicKeyBase64() throws Exception {
        PublicKey p = publicKey();
        return p == null ? null : android.util.Base64.encodeToString(p.getEncoded(), android.util.Base64.NO_WRAP);
    }

    /**
     * ECDSA-SHA256 signature (DER, base64) over message. Throws android.security.keystore.UserNotAuthenticatedException
     * when the screen lock is needed first (the caller shows the phone's unlock prompt and tries again).
     */
    static String sign(String message) throws Exception {
        PrivateKey k = (PrivateKey) store().getKey(ALIAS, null);
        if (k == null) {
            throw new IllegalStateException("no key");
        }
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(k);
        s.update(message.getBytes(StandardCharsets.UTF_8));
        return android.util.Base64.encodeToString(s.sign(), android.util.Base64.NO_WRAP);
    }

    static void delete() {
        try {
            store().deleteEntry(ALIAS);
        } catch (Exception ignored) {
        }
    }
}
