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

    static String[] parse(String line) {
        String clean = (line == null ? "" : line).replace("**", "").replace("__", "").replace("`", "").replaceAll("^[#>*\\-_ ]+", "").trim();
        int q = clean.lastIndexOf('?');
        if (q < 0) {
            return null;
        }
        String ask = clean.substring(0, q);
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
