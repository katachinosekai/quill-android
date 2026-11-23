package io.github.katachinosekai.quillremote;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class Choices {
    static final String[] STEMS = {"do you want me to ", "would you like me to ", "should i ", "shall i ", "should we ", "shall we ", "do you want to ", "want me to ", "should it ", "do i "};
    static final int MAX_WORDS = 12;
    static final int MAX_OPTIONS = 4;
    static final java.util.regex.Pattern CONTINUATION = java.util.regex.Pattern.compile(
            "^(?:with|without|including|plus|so|so that|which|that|where|while|because|but|then|except|unless|since|using|via|like|such as|e\\.g\\.|i\\.e\\.)\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    static final java.util.regex.Pattern SOURCES_HEAD = java.util.regex.Pattern.compile(
            "^[#>\\s]*[*_]*\\s*(?:sources?|references?|links?|citations?)\\s*:?\\s*[*_]*\\s*:?$", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final String LINK = "(?:\\[[^\\]]*\\]\\([^)\\s]*\\)|<?https?://\\S+>?)";
    static final java.util.regex.Pattern LINK_ITEM = java.util.regex.Pattern.compile(
            "^(?:[-*+•]|\\d+[.)])?\\s*" + LINK + "(?:\\s*[,;]?\\s*" + LINK + ")*\\s*$");

    /**
     * text without a trailing sources/links block (blank lines, a "Sources:" heading, link-only list items), right-trimmed:
     * the question a web-search reply closes with sits above it (Quill Desktop's chat.SplitSources). Unchanged (just
     * right-trimmed) when there is no such block or the text is nothing else.
     */
    static String withoutSources(String text) {
        String t = (text == null ? "" : text).replaceAll("\\s+$", "");
        String[] lines = t.split("\n", -1);
        int i = lines.length;
        while (i > 0) {
            String l = lines[i - 1].trim();
            if (!l.isEmpty() && !SOURCES_HEAD.matcher(l).find() && !LINK_ITEM.matcher(l).find()) {
                break;
            }
            i--;
        }
        if (i == lines.length || i == 0) {
            return t;
        }
        String body = String.join("\n", java.util.Arrays.copyOfRange(lines, 0, i)).replaceAll("\\s+$", "");
        return body.isEmpty() ? t : body;
    }

    static String[] parse(String line) {
        String clean = (line == null ? "" : line).replace("**", "").replace("__", "").replace("`", "").replaceAll("^[#>*\\-_ ]+", "").trim();
        int q = clean.lastIndexOf('?');
        if (q < 0) {
            return null;
        }
        // Asides in parentheses explain an option ("add all (recommended)"); the button shows just the option
        // (Quill Desktop's parenAside), and a leading list number is no part of it.
        // "Have you finished? Yes or no?": the options are the sentence after the earlier question.
        String body = clean.substring(0, q);
        int earlier = lastQOutsideParens(body);
        if (earlier >= 0) {
            body = body.substring(earlier + 1).trim();
        }
        String ask = body.replaceAll("\\s*\\([^)]*\\)", "").replaceAll("^\\d+[.)]\\s+", "").trim();
        String sep = ", or ";
        int i = ask.lastIndexOf(sep);
        boolean list = i >= 0;
        if (i < 0) {
            sep = " or ";
            i = ask.lastIndexOf(sep);
        }
        if (i < 0) {
            return null;
        }
        String a = ask.substring(0, i).trim();
        String b = ask.substring(i + sep.length()).trim();
        int colon = a.lastIndexOf(": ");
        if (colon >= 0) {
            a = a.substring(colon + 2).trim();
        }
        a = stem(a);
        List<String> opts = new ArrayList<>();
        if (list && a.contains(", ")) {
            for (String p : a.split(", ")) {
                // A comma part that goes on with the option before it ("all of them, with X") stays with it
                // (Quill Desktop's mergeContinuations, 22c277a).
                if (!opts.isEmpty() && CONTINUATION.matcher(p.trim()).find()) {
                    opts.set(opts.size() - 1, opts.get(opts.size() - 1) + ", " + p.trim());
                    continue;
                }
                opts.add(p.trim());
            }
        } else {
            opts.add(a);
        }
        opts.add(b);
        if (b.contains(" or ") || opts.size() > MAX_OPTIONS) {
            return null;
        }
        String[] out = new String[opts.size()];
        for (int k = 0; k < out.length; k++) {
            String o = opts.get(k);
            if (o.isEmpty() || o.split("\\s+").length > MAX_WORDS) {
                return null;
            }
            out[k] = cap(o);
        }
        return out;
    }

    static final java.util.regex.Pattern REC_WORD = java.util.regex.Pattern.compile("(?i)(not\\s+)?\\brecommended\\b");
    static final java.util.regex.Pattern YES = java.util.regex.Pattern.compile("(?i)^\\W*yes\\b");
    static final java.util.regex.Pattern NO = java.util.regex.Pattern.compile("(?i)^\\W*no\\b");
    static final String RECOMMENDED = "  (recommended)";

    /**
     * Which of the n options parse() found a choice line marks "(recommended)", -1 if none or not clear (Quill
     * Desktop's choiceRecommended / recommendedOption). parse() drops the aside from the option; the reply sent
     * keeps the plain option.
     */
    static int recommended(String line, int n) {
        String clean = (line == null ? "" : line).replace("**", "").replace("__", "").replace("`", "")
                .replaceAll("^[#>*\\-_ ]+", "").trim().replaceAll("^\\d+[.)]\\s+", "").replaceAll("[*_ ]+$", "");
        int q = clean.lastIndexOf('?');
        String ask = q >= 0 ? clean.substring(0, q) : clean;
        int p = lastQOutsideParens(ask);
        if (p >= 0) {
            ask = ask.substring(p + 1);
        }
        List<String> segs = splitLast(ask, ", or ");
        if (n == 2 && segs.size() < 2) {
            List<String> yn = splitLast(ask, " or ");
            if (yn.size() == 2 && yesNo(yn.get(0), yn.get(1))) {
                segs = yn;
            }
        }
        if (segs.size() == 2 && n == 2 && yesNo(segs.get(0), segs.get(1))) {
            // "Yes, x (recommended), or no, y": keep the pair whole
        } else if (segs.size() < 2) {
            segs = splitLast(ask, " or ");
        } else {
            List<String> head = new ArrayList<>();
            for (String part : splitAll(segs.get(0), ", ")) {
                if (!head.isEmpty() && CONTINUATION.matcher(part.trim()).find()) {
                    head.set(head.size() - 1, head.get(head.size() - 1) + ", " + part);
                } else {
                    head.add(part);
                }
            }
            head.addAll(segs.subList(1, segs.size()));
            segs = head;
        }
        if (segs.size() != n) {
            return -1;
        }
        int rec = -1;
        for (int k = 0; k < segs.size(); k++) {
            java.util.regex.Matcher m = REC_WORD.matcher(segs.get(k));
            boolean yes = false;
            while (m.find()) {
                if (m.group(1) == null) {
                    yes = true;
                }
            }
            if (yes) {
                if (rec >= 0) {
                    return -1;
                }
                rec = k;
            }
        }
        return rec;
    }

    static boolean yesNo(String a, String b) {
        return YES.matcher(a).find() && NO.matcher(b).find();
    }

    static int lastQOutsideParens(String s) {
        int depth = 0, at = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
            } else if (c == '?' && depth == 0) {
                at = i;
            }
        }
        return at;
    }

    /** Positions of sep outside parentheses. */
    static List<Integer> cuts(String s, String sep) {
        List<Integer> out = new ArrayList<>();
        int depth = 0;
        for (int i = 0; i + sep.length() <= s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
            }
            if (depth == 0 && s.startsWith(sep, i)) {
                out.add(i);
            }
        }
        return out;
    }

    /** Splits at the last sep outside parentheses (one part if none). */
    static List<String> splitLast(String s, String sep) {
        List<Integer> c = cuts(s, sep);
        List<String> out = new ArrayList<>();
        if (c.isEmpty()) {
            out.add(s);
        } else {
            int at = c.get(c.size() - 1);
            out.add(s.substring(0, at));
            out.add(s.substring(at + sep.length()));
        }
        return out;
    }

    /** Splits at every sep outside parentheses. */
    static List<String> splitAll(String s, String sep) {
        List<String> out = new ArrayList<>();
        int prev = 0;
        for (int at : cuts(s, sep)) {
            out.add(s.substring(prev, at));
            prev = at + sep.length();
        }
        out.add(s.substring(prev));
        return out;
    }

    static String stem(String a) {
        String low = a.toLowerCase(Locale.ROOT);
        for (String s : STEMS) {
            if (low.startsWith(s)) {
                return a.substring(s.length()).trim();
            }
        }
        return a;
    }

    static String cap(String s) {
        int cp = s.codePointAt(0);
        return new String(Character.toChars(Character.toUpperCase(cp))) + s.substring(Character.charCount(cp));
    }

    /** Quill Desktop's reply to a quick choice: "I pick the first option: X." (choiceReply). */
    static String reply(int n, String option) {
        String which = n >= 0 && n < 4 ? new String[]{"first", "second", "third", "fourth"}[n] : "next";
        return "I pick the " + which + " option: " + option + ".";
    }

    /**
     * Which option the user's answer picked: the reply sent for a server choice (Quill Desktop's wording) or the
     * option's own text (a choice sent as a message); -1 when the answer is none of them.
     */
    static int picked(String answer, String[] opts) {
        if (answer == null || opts == null) {
            return -1;
        }
        String a = answer.trim();
        for (int k = 0; k < opts.length; k++) {
            if (a.equals(reply(k, opts[k])) || a.equalsIgnoreCase(opts[k].trim())) {
                return k;
            }
        }
        return -1;
    }
}
