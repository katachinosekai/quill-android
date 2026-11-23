package io.github.katachinosekai.quillremote;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The file viewer's data (reply of GET /phone/session/{id}/file) and its messages. Pure, no Android types.
 * Quill Desktop docs/design/phone-links.md section 8.
 */
final class FileModel {
    static final String TEXT = "text", IMAGE = "image", BINARY = "binary", TOO_LARGE = "too_large";

    final String kind, path, name, lang, text, mime, data;
    final long size;
    final boolean truncated;
    private List<String> lines;

    FileModel(String kind, String path, String name, long size, String lang, String text, boolean truncated, String mime, String data) {
        this.kind = kind;
        this.path = path;
        this.name = name.isEmpty() ? baseName(path) : name;
        this.size = size;
        this.lang = lang;
        this.text = text;
        this.truncated = truncated;
        this.mime = mime;
        this.data = data;
    }

    static String baseName(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }

    /** The text split into lines (a final newline does not add an empty line). */
    List<String> lines() {
        if (lines == null) {
            lines = new ArrayList<>();
            int off = 0;
            while (off < text.length()) {
                int nl = text.indexOf('\n', off);
                if (nl < 0) {
                    lines.add(text.substring(off));
                    break;
                }
                lines.add(text.substring(off, nl));
                off = nl + 1;
            }
            if (lines.isEmpty()) {
                lines.add("");
            }
        }
        return lines;
    }

    /** The 1-based line to scroll to for a :N hint: clamped into the text, 0 = no hint (stay at the top). */
    int jumpLine(int hint) {
        return hint <= 0 ? 0 : Math.min(hint, lines().size());
    }

    /** The last line of a :N-M range, clamped and never before the first; 0 = no range. */
    int rangeEnd(int hint, int endHint) {
        int from = jumpLine(hint);
        return from == 0 || endHint <= from ? 0 : Math.min(endHint, lines().size());
    }

    /** Character offset in text where the 1-based line starts. */
    int lineOffset(int line) {
        int off = 0;
        for (int n = 1; n < line && off < text.length(); n++) {
            int nl = text.indexOf('\n', off);
            off = nl < 0 ? text.length() : nl + 1;
        }
        return off;
    }

    /** "Showing the first 256 KiB of 3.1 MiB", or "" when the whole text is shown. */
    String truncationNote() {
        if (!TEXT.equals(kind) || !truncated) {
            return "";
        }
        return "Showing the first " + sizeText(text.getBytes(StandardCharsets.UTF_8).length) + " of " + sizeText(size);
    }

    /** The note for binary and too_large files, "" for the others. */
    String notShownNote() {
        if (BINARY.equals(kind)) {
            return "Binary file, not shown";
        }
        if (TOO_LARGE.equals(kind)) {
            return "Too large to show (" + sizeText(size) + ")";
        }
        return "";
    }

    static String sizeText(long b) {
        if (b < 1024) {
            return b + " B";
        }
        String[] u = {"KiB", "MiB", "GiB"};
        double v = b;
        int k = -1;
        while (v >= 1024 && k < u.length - 1) {
            v /= 1024;
            k++;
        }
        return (v == Math.rint(v) ? String.valueOf((long) v) : String.format(java.util.Locale.ROOT, "%.1f", v)) + " " + u[k];
    }

    /** "/home/user/x" shortened with ~ when it starts with the home folder, else as is. */
    static String shortPath(String path, String home) {
        if (home != null && !home.isEmpty() && (path.equals(home) || path.startsWith(home + "/"))) {
            return "~" + path.substring(home.length());
        }
        return path;
    }

    /**
     * A short message for an error reply. http: the status (0 = unreachable), code: the error code, detail: its message
     * (names the rule on file_denied), retryAfter: seconds of a 429.
     */
    static String errorMessage(int http, String code, String detail, int retryAfter) {
        if (http == 0 || "unreachable".equals(code)) {
            return "Quill Desktop is not reachable.";
        }
        switch (code) {
            case "files_off":
                return "Turn on 'Let this device open files' for this phone in Quill Desktop → Devices";
            case "file_denied":
                return "Quill Desktop won't open this file" + (detail == null || detail.isEmpty() ? "." : ": " + detail);
            case "not_found":
                return "Quill Desktop can't find this file.";
            case "bad_request":
                return "This path can't be opened.";
            case "forbidden":
                return "This phone was removed in Quill Desktop. Pair it again.";
            case "locked":
                return "Quill Desktop is locked. Unlock it, then try again.";
            case "file_busy":
                return "Another file is still loading. Try again in a moment.";
            case "rate_limited":
                return "Too many files opened. Try again in " + Math.max(1, retryAfter) + " s.";
            default:
                return "Could not open the file (" + (code == null || code.isEmpty() ? "http " + http : code) + ").";
        }
    }

    /** Seconds to wait before one automatic retry after a 429 (Retry-After, at least 1, at most 10). */
    static int retryWait(int retryAfter) {
        return Math.max(1, Math.min(10, retryAfter));
    }
}
