package io.github.katachinosekai.quillremote;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** What a tap on a link does (Quill Desktop docs/design/phone-links.md sections 4 and 5). Pure, no Android types. */
final class LinkPolicy {
    private LinkPolicy() {
    }

    enum Action {
        /** http/https: the phone's browser, a new page. */
        OPEN_WEB,
        /** localhost and friends: "This address is on Quill Desktop's PC", Copy / Open anyway. */
        LOCAL_DIALOG,
        /** A file or path link: the in-app viewer. */
        FILE,
        /** mailto, tel, sms, geo: confirm with the full target, then open. */
        CONFIRM_OPEN,
        /** Any other scheme: "Open <scheme> link with another app?", then open. */
        CONFIRM_SCHEME,
        /** Never opened. */
        NEVER,
        /** Not a link. */
        NONE
    }

    private static final Set<String> NEVER = new HashSet<>(Arrays.asList(
            "intent", "android-app", "javascript", "data", "content", "vbscript", "quillpair"));
    private static final Set<String> CONFIRM = new HashSet<>(Arrays.asList("mailto", "tel", "sms", "geo"));

    static Action decide(Links.Link l) {
        switch (l.kind) {
            case Links.WEB:
                return isLocalHost(hostOf(l.target)) ? Action.LOCAL_DIALOG : Action.OPEN_WEB;
            case Links.FILE:
            case Links.PATH:
                return Action.FILE;
            case Links.OTHER: {
                String s = scheme(l.target);
                return NEVER.contains(s) ? Action.NEVER : CONFIRM.contains(s) ? Action.CONFIRM_OPEN : Action.CONFIRM_SCHEME;
            }
            default:
                return Action.NONE;
        }
    }

    /** The lower-case scheme of a target ("" = none). */
    static String scheme(String target) {
        int c = target.indexOf(':');
        return c <= 0 ? "" : target.substring(0, c).toLowerCase(Locale.ROOT);
    }

    /** The host of an http(s) URL, lower case, without user info, port and brackets' port part ("[::1]" stays bracketed). */
    static String hostOf(String url) {
        int s = url.indexOf("://");
        if (s < 0) {
            return "";
        }
        String rest = url.substring(s + 3);
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                end = i;
                break;
            }
        }
        String auth = rest.substring(0, end);
        int at = auth.lastIndexOf('@');
        if (at >= 0) {
            auth = auth.substring(at + 1);
        }
        String host;
        if (auth.startsWith("[")) {
            int close = auth.indexOf(']');
            host = close < 0 ? auth : auth.substring(0, close + 1);
        } else {
            int colon = auth.indexOf(':');
            host = colon < 0 ? auth : auth.substring(0, colon);
        }
        return host.toLowerCase(Locale.ROOT);
    }

    /** localhost, 127.0.0.0/8, [::1] and 0.0.0.0: addresses that only exist on the PC. */
    static boolean isLocalHost(String host) {
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.equals("localhost") || host.endsWith(".localhost") || host.equals("[::1]") || host.equals("0.0.0.0")
                || host.equals("[::]") || host.equals("[0:0:0:0:0:0:0:1]")) {
            return true;
        }
        String[] p = host.split("\\.", -1);
        return p.length == 4 && p[0].equals("127") && host.matches("[0-9.]+");
    }
}
