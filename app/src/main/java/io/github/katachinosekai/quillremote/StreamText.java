package io.github.katachinosekai.quillremote;

/**
 * Streaming replies formatted while they arrive, ported from Quill Desktop's cmd/quill/streamtext.go (streamCut) and
 * chatview.go (closeOpenMarkdown): finished paragraphs end at the last blank line outside a code fence; the growing
 * last paragraph is drawn with its open markup closed for now.
 */
final class StreamText {
    private StreamText() {
    }

    /** Quill Desktop's splitParas: paragraphs cut at blank lines outside code fences; the last part is still growing (can be empty). */
    static java.util.List<String> split(String text, StringBuilder live) {
        java.util.List<String> done = new java.util.ArrayList<>();
        boolean inFence = false;
        int start = 0, pos = 0;
        for (String l : text.split("(?<=\n)", -1)) {
            if (l.isEmpty()) {
                continue;
            }
            if (l.trim().startsWith("```")) {
                inFence = !inFence;
            }
            pos += l.length();
            if (!inFence && l.trim().isEmpty() && !text.substring(pos).trim().isEmpty()) {
                String p = text.substring(start, pos).trim();
                if (!p.isEmpty()) {
                    done.add(p);
                }
                start = pos;
            }
        }
        if (live != null) {
            live.setLength(0);
            live.append(text.substring(start).replaceAll("^\n+", ""));
        }
        return done;
    }

    static final int REVEAL_MIN = 2, REVEAL_CATCH_UP = 12, REVEAL_MAX = 24;

    /**
     * Next reveal position (Quill Desktop's revealLoop): 2..24 characters a frame, faster when far behind, extended to
     * the end of the word (if that's within 24), the rest at once when a finished reply has no more spaces, never
     * inside a surrogate pair.
     */
    static int nextReveal(String text, int pos, boolean streaming) {
        if (pos >= text.length()) {
            return text.length();
        }
        int gap = text.length() - pos;
        int step = Math.min(Math.max(REVEAL_MIN, gap / REVEAL_CATCH_UP), REVEAL_MAX);
        int n = Math.min(pos + step, text.length());
        int sp = -1;
        for (int i = n; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == ' ' || ch == '\n') {
                sp = i - n;
                break;
            }
        }
        if (sp >= 0 && sp <= REVEAL_MAX) {
            n += sp;
        } else if (sp < 0 && !streaming) {
            n = text.length();
        }
        while (n < text.length() && Character.isLowSurrogate(text.charAt(n))) {
            n++;
        }
        return n;
    }

    /** Close an open code fence, inline code span or bold run at the end of s, so a half-written paragraph renders. */
    static String closeOpen(String s) {
        int fences = 0;
        for (String l : s.split("\n", -1)) {
            if (l.trim().startsWith("```")) {
                fences++;
            }
        }
        if (fences % 2 == 1) {
            return s + "\n```";
        }
        String last = s.substring(s.lastIndexOf("\n\n") + 1);
        String add = "";
        if (count(last, "`") % 2 == 1) {
            add = "`";
        }
        StringBuilder outside = new StringBuilder();
        String[] parts = (last + add).split("`", -1);
        for (int i = 0; i < parts.length; i += 2) {
            outside.append(parts[i]);
        }
        if (count(outside.toString(), "**") % 2 == 1) {
            if (add.isEmpty() && s.endsWith("**")) {
                return s.substring(0, s.length() - 2);
            }
            add += "**";
        }
        return s + add;
    }

    static int count(String s, String sub) {
        int n = 0;
        for (int i = s.indexOf(sub); i >= 0; i = s.indexOf(sub, i + sub.length())) {
            n++;
        }
        return n;
    }

}
