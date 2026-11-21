package io.github.katachinosekai.quillremote;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class Choices {
    static final String[] STEMS = {"do you want me to ", "would you like me to ", "should i ", "shall i ", "should we ", "shall we ", "do you want to ", "want me to ", "should it ", "do i "};
    static final int MAX_WORDS = 12;
    static final int MAX_OPTIONS = 4;

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
}
