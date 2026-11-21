package io.github.katachinosekai.quillremote;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Markdown {
    enum Type { PARAGRAPH, HEADING, CODE, BULLET, NUMBERED, QUOTE, RULE, TABLE }

    static final int BOLD = 1, ITALIC = 2, CODE_SPAN = 4, STRIKE = 8;

    static final class Run {
        final String text;
        final int style;
        final String link;

        Run(String text, int style, String link) {
            this.text = text;
            this.style = style;
            this.link = link;
        }

        @Override
        public String toString() {
            return (style == 0 && link == null) ? text : "[" + style + (link == null ? "" : "→" + link) + ":" + text + "]";
        }
    }

    static final class Block {
        final Type type;
        final int level;
        final String marker;
        final String lang;
        final String raw;
        final List<Run> runs;

        Block(Type type, int level, String marker, String lang, String raw, List<Run> runs) {
            this.type = type;
            this.level = level;
            this.marker = marker;
            this.lang = lang;
            this.raw = raw;
            this.runs = runs;
        }

        String text() {
            if (runs == null) {
                return raw;
            }
            StringBuilder sb = new StringBuilder();
            for (Run r : runs) {
                sb.append(r.text);
            }
            return sb.toString();
        }
    }

    static final Pattern FENCE = Pattern.compile("^ {0,3}(```+|~~~+)\\s*([^`\\s]*).*$");
    static final Pattern HEADING = Pattern.compile("^ {0,3}(#{1,6})\\s+(.*?)\\s*#*\\s*$");
    static final Pattern BULLET = Pattern.compile("^(\\s*)[-*+]\\s+(.*)$");
    static final Pattern NUMBERED = Pattern.compile("^(\\s*)(\\d{1,9})[.)]\\s+(.*)$");
    static final Pattern QUOTE = Pattern.compile("^ {0,3}>\\s?(.*)$");
    static final Pattern RULE = Pattern.compile("^ {0,3}([-*_])(\\s*\\1){2,}\\s*$");
    static final Pattern TABLE_SEP = Pattern.compile("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$");

    static List<Block> parse(String src) {
        List<Block> out = new ArrayList<>();
        String[] lines = (src == null ? "" : src.replace("\r\n", "\n")).split("\n", -1);
        StringBuilder para = new StringBuilder();
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            Matcher m;
            if ((m = FENCE.matcher(line)).matches()) {
                flush(out, para);
                String fence = m.group(1);
                StringBuilder code = new StringBuilder();
                i++;
                while (i < lines.length && !lines[i].trim().startsWith(fence)) {
                    code.append(lines[i]).append('\n');
                    i++;
                }
                if (code.length() > 0) {
                    code.setLength(code.length() - 1);
                }
                out.add(new Block(Type.CODE, 0, null, m.group(2), code.toString(), null));
                i++;
                continue;
            }
            if (line.trim().isEmpty()) {
                flush(out, para);
                i++;
                continue;
            }
            if (RULE.matcher(line).matches()) {
                flush(out, para);
                out.add(new Block(Type.RULE, 0, null, null, "", null));
                i++;
                continue;
            }
            if ((m = HEADING.matcher(line)).matches()) {
                flush(out, para);
                out.add(new Block(Type.HEADING, m.group(1).length(), null, null, m.group(2), inline(m.group(2))));
                i++;
                continue;
            }
            if (line.contains("|") && i + 1 < lines.length && TABLE_SEP.matcher(lines[i + 1]).matches()) {
                flush(out, para);
                StringBuilder t = new StringBuilder(line.trim());
                i += 2;
                while (i < lines.length && lines[i].contains("|") && !lines[i].trim().isEmpty()) {
                    t.append('\n').append(lines[i].trim());
                    i++;
                }
                out.add(new Block(Type.TABLE, 0, null, null, t.toString(), null));
                continue;
            }
            if ((m = QUOTE.matcher(line)).matches()) {
                flush(out, para);
                StringBuilder q = new StringBuilder(m.group(1));
                i++;
                while (i < lines.length && (m = QUOTE.matcher(lines[i])).matches()) {
                    q.append('\n').append(m.group(1));
                    i++;
                }
                out.add(new Block(Type.QUOTE, 0, null, null, q.toString(), inline(q.toString())));
                continue;
            }
            if ((m = BULLET.matcher(line)).matches() && !RULE.matcher(line).matches()) {
                flush(out, para);
                String body = continuation(lines, i, m.group(2));
                i += 1 + extra(lines, i);
                out.add(new Block(Type.BULLET, indent(m.group(1)), "•", null, body, inline(body)));
                continue;
            }
            if ((m = NUMBERED.matcher(line)).matches()) {
                flush(out, para);
                String body = continuation(lines, i, m.group(3));
                i += 1 + extra(lines, i);
                out.add(new Block(Type.NUMBERED, indent(m.group(1)), m.group(2) + ".", null, body, inline(body)));
                continue;
            }
            if (para.length() > 0) {
                para.append('\n');
            }
            para.append(line.trim());
            i++;
        }
        flush(out, para);
        return out;
    }

    static int indent(String ws) {
        int n = 0;
        for (char c : ws.toCharArray()) {
            n += c == '\t' ? 4 : 1;
        }
        return n / 2;
    }

    static boolean starts(String line) {
        return line.trim().isEmpty() || FENCE.matcher(line).matches() || HEADING.matcher(line).matches()
                || QUOTE.matcher(line).matches() || BULLET.matcher(line).matches() || NUMBERED.matcher(line).matches()
                || RULE.matcher(line).matches();
    }

    static int extra(String[] lines, int i) {
        int n = 0;
        while (i + 1 + n < lines.length && !starts(lines[i + 1 + n]) && lines[i + 1 + n].startsWith(" ")) {
            n++;
        }
        return n;
    }

    static String continuation(String[] lines, int i, String first) {
        StringBuilder sb = new StringBuilder(first.trim());
        int n = extra(lines, i);
        for (int k = 1; k <= n; k++) {
            sb.append('\n').append(lines[i + k].trim());
        }
        return sb.toString();
    }

    static void flush(List<Block> out, StringBuilder para) {
        if (para.length() == 0) {
            return;
        }
        String t = para.toString();
        out.add(new Block(Type.PARAGRAPH, 0, null, null, t, inline(t)));
        para.setLength(0);
    }

    static List<Run> inline(String s) {
        List<Run> out = new ArrayList<>();
        inline(s, 0, out);
        return merge(out);
    }

    static void inline(String s, int style, List<Run> out) {
        StringBuilder plain = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length() && "\\`*_~[]()#>!-|".indexOf(s.charAt(i + 1)) >= 0) {
                plain.append(s.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '`') {
                int ticks = 1;
                while (i + ticks < s.length() && s.charAt(i + ticks) == '`') {
                    ticks++;
                }
                String fence = s.substring(i, i + ticks);
                int end = s.indexOf(fence, i + ticks);
                if (end > 0) {
                    emit(out, plain, style);
                    String code = s.substring(i + ticks, end);
                    if (code.length() > 1 && code.startsWith(" ") && code.endsWith(" ")) {
                        code = code.substring(1, code.length() - 1);
                    }
                    out.add(new Run(code, style | CODE_SPAN, null));
                    i = end + ticks;
                    continue;
                }
            }
            if (c == '[') {
                int close = matching(s, i, '[', ']');
                if (close > 0 && close + 1 < s.length() && s.charAt(close + 1) == '(') {
                    int paren = s.indexOf(')', close + 2);
                    if (paren > 0) {
                        emit(out, plain, style);
                        String url = s.substring(close + 2, paren).trim();
                        int sp = url.indexOf(' ');
                        if (sp > 0) {
                            url = url.substring(0, sp);
                        }
                        List<Run> inner = new ArrayList<>();
                        inline(s.substring(i + 1, close), style, inner);
                        for (Run r : inner) {
                            out.add(new Run(r.text, r.style, url));
                        }
                        i = paren + 1;
                        continue;
                    }
                }
            }
            String delim = null;
            int flag = 0;
            if (s.startsWith("**", i) || s.startsWith("__", i)) {
                delim = s.substring(i, i + 2);
                flag = BOLD;
            } else if (s.startsWith("~~", i)) {
                delim = "~~";
                flag = STRIKE;
            } else if (c == '*' || c == '_') {
                delim = String.valueOf(c);
                flag = ITALIC;
            }
            if (delim != null && i + delim.length() < s.length() && !Character.isWhitespace(s.charAt(i + delim.length()))
                    && !(c == '_' && i > 0 && Character.isLetterOrDigit(s.charAt(i - 1)))) {
                int end = closing(s, i + delim.length(), delim);
                if (end > 0) {
                    emit(out, plain, style);
                    inline(s.substring(i + delim.length(), end), style | flag, out);
                    i = end + delim.length();
                    continue;
                }
            }
            plain.append(c);
            i++;
        }
        emit(out, plain, style);
    }

    static int closing(String s, int from, String delim) {
        int j = from;
        while (true) {
            j = s.indexOf(delim, j);
            if (j < 0) {
                return -1;
            }
            boolean ok = !Character.isWhitespace(s.charAt(j - 1));
            if (delim.length() == 1 && j + 1 < s.length() && s.charAt(j + 1) == delim.charAt(0)) {
                j += 2;
                continue;
            }
            if (delim.equals("_") && j + 1 < s.length() && Character.isLetterOrDigit(s.charAt(j + 1))) {
                ok = false;
            }
            if (ok && j > from) {
                return j;
            }
            j += delim.length();
        }
    }

    static int matching(String s, int open, char o, char c) {
        int depth = 0;
        for (int j = open; j < s.length(); j++) {
            if (s.charAt(j) == o) {
                depth++;
            } else if (s.charAt(j) == c && --depth == 0) {
                return j;
            }
        }
        return -1;
    }

    static void emit(List<Run> out, StringBuilder plain, int style) {
        if (plain.length() > 0) {
            out.add(new Run(plain.toString(), style, null));
            plain.setLength(0);
        }
    }

    static List<Run> merge(List<Run> runs) {
        List<Run> out = new ArrayList<>();
        for (Run r : runs) {
            Run last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null && last.style == r.style && (last.link == null ? r.link == null : last.link.equals(r.link))) {
                out.set(out.size() - 1, new Run(last.text + r.text, r.style, r.link));
            } else {
                out.add(r);
            }
        }
        return out;
    }

    static List<String[]> tableRows(String raw) {
        List<String[]> rows = new ArrayList<>();
        for (String line : raw.split("\n")) {
            String t = line.trim();
            if (t.startsWith("|")) {
                t = t.substring(1);
            }
            if (t.endsWith("|")) {
                t = t.substring(0, t.length() - 1);
            }
            String[] cells = t.split("\\|", -1);
            for (int k = 0; k < cells.length; k++) {
                cells[k] = cells[k].trim();
            }
            rows.add(cells);
        }
        return rows;
    }
}
