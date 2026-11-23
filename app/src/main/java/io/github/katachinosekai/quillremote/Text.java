package io.github.katachinosekai.quillremote;

import java.text.Collator;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.Locale;

/** Locale-aware sorting and case-folded search, mirroring Quill Desktop's foldKey (cmd/quill/locale.go). */
final class Text {
    private Text() {
    }

    /** Folds for searching: ß matches ss, final sigma matches σ, Turkish İ and ı match a plain i. */
    static String fold(String s) {
        if (s == null) {
            return "";
        }
        // Upper then lower is a full case fold: ß -> SS -> ss, ς -> Σ -> σ, ı -> I -> i.
        String f = Normalizer.normalize(s, Normalizer.Form.NFC).toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);
        f = f.replace("̇", ""); // dot above left behind by İ
        return f.replace('ı', 'i').replace('ς', 'σ');
    }

    static boolean foldContains(String text, String query) {
        return fold(text).contains(fold(query));
    }

    /** Collator comparator for the given locale (tertiary strength: case and accents break ties only). */
    static Comparator<String> comparator(Locale locale) {
        Collator c = Collator.getInstance(locale);
        c.setStrength(Collator.TERTIARY);
        return c::compare;
    }

    /** Comparator for the device locale. */
    static Comparator<String> comparator() {
        return comparator(Locale.getDefault());
    }
}
