package io.github.katachinosekai.quillremote;

final class Choices {
    static String[] parse(String line) {
        String clean = (line == null ? "" : line).replace("**", "").replace("__", "").replace("`", "").replaceAll("^[#>*\\-_ ]+", "").trim();
        int q = clean.lastIndexOf('?');
        if (q < 0) {
            return null;
        }
        String ask = clean.substring(0, q);
        String sep = ", or ";
        int i = ask.lastIndexOf(sep);
        if (i < 0) {
            sep = " or ";
            i = ask.lastIndexOf(sep);
        }
        if (i < 0) {
            return null;
        }
        String a = ask.substring(0, i).trim();
        String b = ask.substring(i + sep.length()).trim();
        if (a.isEmpty() || b.isEmpty() || b.contains(" or ")) {
            return null;
        }
        return new String[]{cap(a), cap(b)};
    }

    static String cap(String s) {
        int cp = s.codePointAt(0);
        return new String(Character.toChars(Character.toUpperCase(cp))) + s.substring(Character.charCount(cp));
    }
}
