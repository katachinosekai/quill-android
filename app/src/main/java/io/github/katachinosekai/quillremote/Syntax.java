package io.github.katachinosekai.quillremote;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Light syntax colouring: comments, strings, numbers and a short keyword list. Pure, no Android types. */
final class Syntax {
    private Syntax() {
    }

    static final int COMMENT = 1, STRING = 2, NUMBER = 3, KEYWORD = 4;

    /** One coloured range of the text (end exclusive). */
    static final class Span {
        final int start, end, type;

        Span(int start, int end, int type) {
            this.start = start;
            this.end = end;
            this.type = type;
        }
    }

    private static Set<String> kw(String s) {
        return new HashSet<>(Arrays.asList(s.split(" ")));
    }

    private static final Set<String> GO = kw("break case chan const continue default defer else fallthrough for func go goto if import interface map package range return select struct switch type var true false nil");
    private static final Set<String> JAVA = kw("abstract boolean break byte case catch char class continue default do double else enum extends final finally float for if implements import instanceof int interface long new null package private protected public return short static super switch this throw throws try void while true false");
    private static final Set<String> KOTLIN = kw("as break class continue do else false for fun if in interface is null object package return super this throw true try typealias val var when while override private public internal data sealed");
    private static final Set<String> PYTHON = kw("and as assert break class continue def del elif else except finally for from global if import in is lambda None nonlocal not or pass raise return try while with yield True False async await");
    private static final Set<String> JS = kw("async await break case catch class const continue default delete do else export extends finally for from function if import in instanceof let new null of return super switch this throw try typeof undefined var void while yield true false interface type enum implements");
    private static final Set<String> SH = kw("if then else elif fi for while do done case esac function in return exit local export set unset true false");
    private static final Set<String> C = kw("auto break case char const continue default do double else enum extern float for goto if inline int long register return short signed sizeof static struct switch typedef union unsigned void volatile while class namespace new delete public private protected template this true false NULL nullptr");
    private static final Set<String> DATA = kw("true false null");

    /** Normalised language: go, java, kotlin, python, js, sh, json, yaml, toml, c; "" = plain. */
    static String normalise(String lang) {
        switch (lang == null ? "" : lang.toLowerCase(Locale.ROOT)) {
            case "go": case "golang": return "go";
            case "java": return "java";
            case "kotlin": case "kt": case "kts": return "kotlin";
            case "python": case "py": return "python";
            case "javascript": case "js": case "jsx": case "typescript": case "ts": case "tsx": case "mjs": return "js";
            case "sh": case "bash": case "shell": case "zsh": return "sh";
            case "json": return "json";
            case "yaml": case "yml": return "yaml";
            case "toml": return "toml";
            case "c": case "h": case "cpp": case "c++": case "cc": case "hpp": return "c";
            default: return "";
        }
    }

    static List<Span> spans(String lang, String s) {
        List<Span> out = new ArrayList<>();
        String l = normalise(lang);
        if (l.isEmpty()) {
            return out;
        }
        boolean slash = l.equals("go") || l.equals("java") || l.equals("kotlin") || l.equals("js") || l.equals("c");
        boolean hash = l.equals("python") || l.equals("sh") || l.equals("yaml") || l.equals("toml");
        boolean backtick = l.equals("go") || l.equals("js");
        Set<String> words = l.equals("go") ? GO : l.equals("java") ? JAVA : l.equals("kotlin") ? KOTLIN : l.equals("python") ? PYTHON
                : l.equals("js") ? JS : l.equals("sh") ? SH : l.equals("c") ? C : DATA;
        int n = s.length(), i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (slash && c == '/' && i + 1 < n && s.charAt(i + 1) == '/' || hash && c == '#') {
                int e = s.indexOf('\n', i);
                e = e < 0 ? n : e;
                out.add(new Span(i, e, COMMENT));
                i = e;
            } else if (slash && c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                int e = s.indexOf("*/", i + 2);
                e = e < 0 ? n : e + 2;
                out.add(new Span(i, e, COMMENT));
                i = e;
            } else if (l.equals("python") && (s.startsWith("\"\"\"", i) || s.startsWith("'''", i))) {
                int e = s.indexOf(s.substring(i, i + 3), i + 3);
                e = e < 0 ? n : e + 3;
                out.add(new Span(i, e, STRING));
                i = e;
            } else if (c == '"' || c == '\'' && !l.equals("json") || c == '`' && backtick) {
                int e = i + 1;
                while (e < n && s.charAt(e) != c && (s.charAt(e) != '\n' || c == '`')) {
                    e += s.charAt(e) == '\\' && e + 1 < n && c != '`' ? 2 : 1;
                }
                e = Math.min(n, e < n && s.charAt(e) == c ? e + 1 : e);
                out.add(new Span(i, e, STRING));
                i = e;
            } else if (c >= '0' && c <= '9') {
                int e = i + 1;
                while (e < n && (Character.isLetterOrDigit(s.charAt(e)) || s.charAt(e) == '.' || s.charAt(e) == '_')) {
                    e++;
                }
                out.add(new Span(i, e, NUMBER));
                i = e;
            } else if (Character.isLetter(c) || c == '_') {
                int e = i + 1;
                while (e < n && (Character.isLetterOrDigit(s.charAt(e)) || s.charAt(e) == '_')) {
                    e++;
                }
                if (words.contains(s.substring(i, e))) {
                    out.add(new Span(i, e, KEYWORD));
                }
                i = e;
            } else {
                i++;
            }
        }
        return out;
    }
}
