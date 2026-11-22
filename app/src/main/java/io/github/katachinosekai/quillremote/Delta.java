package io.github.katachinosekai.quillremote;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * Streaming deltas (Quill Desktop 0.1.24+, docs/phone-api.md): on incremental /items fetches the phone names the
 * streaming replies it holds ("have" = seq:codepoints:crc32-hex of their text as UTF-8); a reply whose copy is still a
 * prefix of the current text comes back with only the new code points ("textFrom" + "text" + "textLen") and the
 * phone appends. Anything that doesn't add up is dropped and the session reloaded in full.
 */
final class Delta {
    static final int MAX_HAVE = 16;

    private Delta() {
    }

    /** One "have" entry for an item whose whole text the phone holds. */
    static String entry(int seq, String text) {
        CRC32 c = new CRC32();
        c.update(text.getBytes(StandardCharsets.UTF_8));
        return seq + ":" + text.codePointCount(0, text.length()) + ":" + Long.toHexString(c.getValue());
    }

    /** old + part when old is exactly textFrom code points long and the result is textLen long (textLen < 0: not sent); else null. */
    static String append(String old, int textFrom, String part, int textLen) {
        if (old == null || part == null || textFrom <= 0 || old.codePointCount(0, old.length()) != textFrom) {
            return null;
        }
        String all = old + part;
        if (textLen >= 0 && all.codePointCount(0, all.length()) != textLen) {
            return null;
        }
        return all;
    }
}
