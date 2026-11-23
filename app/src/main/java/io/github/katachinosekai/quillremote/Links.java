package io.github.katachinosekai.quillremote;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The shared link rules (Quill Desktop docs/design/phone-links.md section 3), a port of Quill Desktop's
 * cmd/quill/links.go. Both apps run the same cases (links.json); change the rules in all three places together.
 */
final class Links {
    private Links() {
    }

    static final String WEB = "web", FILE = "file", PATH = "path", OTHER = "other";
    /** At most this many links per item; the rest stay plain text. */
    static final int MAX = 50;

    /** One link. a/b: UTF-16 offsets into the searched text (b exclusive); start/end: the same in code points. */
    static final class Link {
        String kind = "";
        int a, b, start, end;
        String target = "", label = "";
        int line, lineEnd;
        String text = "";
    }

    private static final Pattern MD_LINK = Pattern.compile("\\[([^\\[\\]\\n]*)\\]\\(([^()\\t\\n\\f\\r ]+)\\)");
    private static final Pattern ANGLE = Pattern.compile("<((?:https?|file)://[^\\t\\n\\f\\r <>]+)>");
    private static final Pattern BARE = Pattern.compile("(?:https?|file)://[^\\t\\n\\f\\r <>()\\[\\]`\"']+");
    private static final Pattern SCHEME = Pattern.compile("^([A-Za-z][A-Za-z0-9+.\\-]*):");
    private static final Pattern LINE_HINT = Pattern.compile(":([0-9]+)(?:([-:])([0-9]+))?\\z");

    /** The links in text (prose, fences and code skipped as in the rules), in order, at most MAX. */
    static List<Link> find(String text) {
        List<Link> out = new ArrayList<>();
        int off = 0;
        String fence = "";
        while (off < text.length()) {
            int nl = text.indexOf('\n', off);
            int end = nl < 0 ? text.length() : nl + 1;
            String body = text.substring(off, nl < 0 ? text.length() : nl);
            int start = off;
            off = end;
            String trim = trimSpace(body);
            if (!fence.isEmpty()) {
                if (trim.startsWith(fence)) {
                    fence = "";
                }
                continue;
            }
            String f = fenceMark(trim);
            if (!f.isEmpty()) {
                if (!trim.substring(3).contains(f)) {
                    fence = f;
                }
                continue;
            }
            if (body.startsWith("    ") || body.startsWith("\t")) {
                continue;
            }
            out.addAll(lineLinks(body, start));
            if (out.size() >= MAX) {
                out = new ArrayList<>(out.subList(0, MAX));
                break;
            }
        }
        for (Link l : out) {
            fill(l, text);
        }
        return out;
    }

    /** Prose rules only (bare URLs, angle links, paths) on a piece of already parsed Markdown text. */
    static List<Link> prose(String s) {
        List<Link> out = proseLinks(s, 0);
        for (Link l : out) {
            fill(l, s);
        }
        return out;
    }

    /** An inline code span that is one whole path (decided: only then it is a link), else null. Offsets: the whole code. */
    static Link codePath(String code) {
        Link l = codePath(code, 0);
        if (l != null) {
            fill(l, code);
        }
        return l;
    }

    /** A Markdown link target, an angle link or a bare URL; kind "" = not a link. Offsets are not set. */
    static Link classify(String t) {
        String lower = t.toLowerCase(Locale.ROOT);
        Link l = new Link();
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            if (t.length() > "https://".length() || !lower.endsWith("://")) {
                l.kind = WEB;
                l.target = t;
            }
            return l;
        }
        if (lower.startsWith("file://")) {
            String p = fileUrlPath(t);
            if (p != null) {
                l.kind = FILE;
                l.target = p;
            }
            return l;
        }
        if (t.startsWith("/") || t.startsWith("~/")) {
            Link p = pathLink(t, 0);
            if (p != null) {
                l.kind = PATH;
                l.target = p.target;
                l.line = p.line;
                l.lineEnd = p.lineEnd;
            }
            return l;
        }
        Matcher m = SCHEME.matcher(t);
        if (m.find() && !m.group(1).toLowerCase(Locale.ROOT).equals("quillpair")) {
            l.kind = OTHER;
            l.target = t;
        }
        return l;
    }

    private static void fill(Link l, String text) {
        l.text = text.substring(l.a, l.b);
        l.start = text.codePointCount(0, l.a);
        l.end = l.start + text.codePointCount(l.a, l.b);
    }

    private static String fenceMark(String trim) {
        return trim.startsWith("```") ? "```" : trim.startsWith("~~~") ? "~~~" : "";
    }

    private static boolean isSpace(char c) {
        return c == ' ' || (c >= '\t' && c <= '\r') || c == 0x85 || c == 0xA0 || Character.isSpaceChar(c);
    }

    private static String trimSpace(String s) {
        int a = 0, b = s.length();
        while (a < b && isSpace(s.charAt(a))) {
            a++;
        }
        while (b > a && isSpace(s.charAt(b - 1))) {
            b--;
        }
        return s.substring(a, b);
    }

    private static List<Link> lineLinks(String line, int base) {
        List<Link> out = new ArrayList<>();
        int pos = 0;
        String[] parts = line.split("`", -1);
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (i % 2 == 0) {
                out.addAll(proseLinks(part, base + pos));
            } else if (i < parts.length - 1) { // an unclosed span stays code
                Link l = codePath(part, base + pos);
                if (l != null) {
                    out.add(l);
                }
            }
            pos += part.length() + 1;
        }
        return out;
    }

    private static Link codePath(String code, int base) {
        String t = trimSpace(code);
        if (t.isEmpty()) {
            return null;
        }
        int lead = 0;
        while (lead < code.length() && isSpace(code.charAt(lead))) {
            lead++;
        }
        int end = pathEnd(t, 0);
        if (end != t.length()) {
            return null;
        }
        Link l = pathLink(t, 0);
        if (l == null) {
            return null;
        }
        l.a = base + lead;
        l.b = base + lead + t.length();
        return l;
    }

    private static boolean overlaps(List<int[]> spans, int a, int b) {
        for (int[] s : spans) {
            if (a < s[1] && s[0] < b) {
                return true;
            }
        }
        return false;
    }

    private static List<Link> proseLinks(String s, int base) {
        List<Link> out = new ArrayList<>();
        List<int[]> taken = new ArrayList<>();
        Matcher m = MD_LINK.matcher(s);
        while (m.find()) {
            Link l = classify(m.group(2));
            l.label = m.group(1);
            add(out, taken, l, m.start(), m.end(), base);
        }
        m = ANGLE.matcher(s);
        while (m.find()) {
            if (overlaps(taken, m.start(), m.end())) {
                continue;
            }
            add(out, taken, classify(m.group(1)), m.start(), m.end(), base);
        }
        m = BARE.matcher(s);
        while (m.find()) {
            String raw = trimRight(m.group(), ".,;:!?*_");
            int a = m.start(), b = a + raw.length();
            if (overlaps(taken, a, b)) {
                continue;
            }
            Link l = classify(raw);
            if (!l.kind.equals(WEB) && !l.kind.equals(FILE)) {
                l.kind = "";
            }
            add(out, taken, l, a, b, base);
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '/' && !(c == '~' && i + 1 < s.length() && s.charAt(i + 1) == '/')) {
                continue;
            }
            if ((i > 0 && pathBlocked(s.charAt(i - 1))) || overlaps(taken, i, i + 1)) {
                continue;
            }
            int end = pathEnd(s, i);
            if (end < 0) {
                continue;
            }
            String raw = trimRight(s.substring(i, end), ".,;:!?");
            Link l = pathLink(raw, 0);
            if (l != null && !overlaps(taken, i, i + raw.length())) {
                add(out, taken, l, i, i + raw.length(), base);
            }
            i = end - 1;
        }
        Collections.sort(out, (x, y) -> Integer.compare(x.a, y.a));
        return out;
    }

    private static void add(List<Link> out, List<int[]> taken, Link l, int a, int b, int base) {
        taken.add(new int[]{a, b});
        if (!l.kind.isEmpty()) {
            l.a = base + a;
            l.b = base + b;
            out.add(l);
        }
    }

    private static String trimRight(String s, String cutset) {
        int b = s.length();
        while (b > 0 && cutset.indexOf(s.charAt(b - 1)) >= 0) {
            b--;
        }
        return s.substring(0, b);
    }

    /** A path can't start right after a letter, digit or one of these (and/or, 1/2, URL paths, ../x). */
    private static boolean pathBlocked(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c >= 0x80
                || "/:.~-\\@#%+&$".indexOf(c) >= 0;
    }

    /** The end of the run of path characters starting at i, or -1. */
    private static int pathEnd(String s, int i) {
        int j = i;
        if (s.charAt(j) == '~') {
            j++;
        }
        if (j >= s.length() || s.charAt(j) != '/') {
            return -1;
        }
        while (j < s.length() && !pathStop(s.charAt(j))) {
            j++;
        }
        return j;
    }

    private static boolean pathStop(char c) {
        return c == ' ' || (c >= '\t' && c <= '\r') || "<>\"'`()[]*".indexOf(c) >= 0;
    }

    /** raw: path chars only, trailing punctuation already dropped. Splits off the line hint. Null = not a path. */
    private static Link pathLink(String raw, int start) {
        Link l = new Link();
        l.kind = PATH;
        l.a = start;
        l.b = start + raw.length();
        String p = raw;
        Matcher m = LINE_HINT.matcher(p);
        if (m.find()) {
            l.line = atoi(m.group(1));
            if ("-".equals(m.group(2))) {
                l.lineEnd = atoi(m.group(3));
            }
            p = p.substring(0, m.start());
        }
        if (!validPath(p)) {
            return null;
        }
        l.target = p;
        return l;
    }

    private static int atoi(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    /** /a/b... (two segments at least) or ~/a...; never //. */
    private static boolean validPath(String p) {
        String rest;
        int min = 2;
        if (p.startsWith("~/")) {
            rest = p.substring(2);
            min = 1;
        } else if (p.startsWith("/") && !p.startsWith("//")) {
            rest = p.substring(1);
        } else {
            return false;
        }
        int n = 0;
        for (String seg : rest.split("/", -1)) {
            if (!seg.isEmpty()) {
                n++;
            }
        }
        return n >= min;
    }

    /** file:///abs or file://localhost/abs, percent-decoded; null = not usable. */
    private static String fileUrlPath(String t) {
        String rest = t.substring("file://".length());
        if (rest.toLowerCase(Locale.ROOT).startsWith("localhost/")) {
            rest = rest.substring("localhost".length());
        }
        if (!rest.startsWith("/") || rest.startsWith("//")) {
            return null;
        }
        String p = unescape(rest);
        if (p == null || p.indexOf('\0') >= 0) {
            return null;
        }
        for (int i = 0; i < p.length(); i++) {
            if (p.charAt(i) != '/') {
                return p;
            }
        }
        return null;
    }

    /** Percent-decoding without '+' handling (Go's url.PathUnescape); null on a bad escape. */
    private static String unescape(String s) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] raw = s.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < raw.length; i++) {
            if (raw[i] == '%') {
                if (i + 2 >= raw.length) {
                    return null;
                }
                int h = Character.digit((char) raw[i + 1], 16), l = Character.digit((char) raw[i + 2], 16);
                if (h < 0 || l < 0) {
                    return null;
                }
                out.write(h * 16 + l);
                i += 2;
            } else {
                out.write(raw[i]);
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
