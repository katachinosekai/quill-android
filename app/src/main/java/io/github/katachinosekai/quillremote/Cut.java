package io.github.katachinosekai.quillremote;

/** Cutting text by length without ever splitting a UTF-16 surrogate pair (emoji and other non-BMP characters). */
final class Cut {
    private Cut() {}

    /** The first at most {@code n} chars of {@code s}, backing off one char when the cut would strand a high surrogate. */
    static String head(String s, int n) {
        if (s == null) {
            return "";
        }
        if (n <= 0) {
            return "";
        }
        if (s.length() <= n) {
            return s;
        }
        if (Character.isHighSurrogate(s.charAt(n - 1)) && Character.isLowSurrogate(s.charAt(n))) {
            n--;
        }
        return s.substring(0, n);
    }

    /** {@code s} shortened to at most {@code n} chars including a trailing ellipsis. */
    static String ellipsize(String s, int n, String ellipsis) {
        if (s == null) {
            return "";
        }
        if (s.length() <= n) {
            return s;
        }
        return head(s, n - ellipsis.length()) + ellipsis;
    }
}
