package io.github.katachinosekai.quillremote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.Test;

public class TextTest {
    static List<String> sorted(Locale l, String... in) {
        List<String> a = new ArrayList<>(Arrays.asList(in));
        a.sort(Text.comparator(l));
        return a;
    }

    @Test
    public void germanUmlautsSortWithTheirBaseLetter() {
        assertEquals(Arrays.asList("Apfel", "Äpfel", "Zebra"), sorted(Locale.GERMAN, "Zebra", "Äpfel", "Apfel"));
        assertEquals(Arrays.asList("Maße", "Masse2", "Maus"), sorted(Locale.GERMAN, "Maus", "Masse2", "Maße"));
    }

    @Test
    public void swedishPutsAaeAfterZ() {
        Locale sv = new Locale("sv");
        assertEquals(Arrays.asList("Zebra", "Å", "Ä", "Ö"), sorted(sv, "Ö", "Ä", "Å", "Zebra"));
    }

    @Test
    public void turkishDottedAndDotlessI() {
        Locale tr = new Locale("tr");
        assertEquals(Arrays.asList("ç", "d", "ı", "i", "ö", "s", "ş"), sorted(tr, "ş", "s", "ö", "i", "ı", "d", "ç"));
        assertTrue(Text.comparator(tr).compare("ı", "i") < 0);
    }

    @Test
    public void foldMatches() {
        assertTrue(Text.foldContains("Straße nach Hause", "STRASSE"));
        assertTrue(Text.foldContains("strasse", "straße"));
        assertTrue(Text.foldContains("İstanbul", "istanbul"));
        assertTrue(Text.foldContains("ISPARTA ılık", "ispartA ilik"));
        assertTrue(Text.foldContains("ΟΔΟΣ", "οδος"));
        assertTrue(Text.foldContains("οδος", "οδοσ"));
        assertFalse(Text.foldContains("abc", "abd"));
        assertEquals("", Text.fold(null));
    }

    static Path root() {
        Path p = Paths.get("").toAbsolutePath();
        return Files.exists(p.resolve("src/main")) ? p.resolve("src/main") : p.resolve("app/src/main");
    }

    @Test
    public void manifestSupportsRtl() throws IOException {
        String m = new String(Files.readAllBytes(root().resolve("AndroidManifest.xml")));
        assertTrue(m.contains("android:supportsRtl=\"true\""));
    }

    @Test
    public void noAsymmetricPhysicalPadding() throws IOException {
        Pattern call = Pattern.compile("\\.setPadding\\(([^;]*)\\);");
        List<String> bad = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root().resolve("java"))) {
            for (Path f : (Iterable<Path>) s.filter(x -> x.toString().endsWith(".java"))::iterator) {
                String src = new String(Files.readAllBytes(f));
                Matcher m = call.matcher(src);
                while (m.find()) {
                    String[] a = m.group(1).split(",");
                    // Symmetric left/right (or a window inset, which is physical) is fine.
                    if (a.length == 4 && !a[0].trim().equals(a[2].trim()) && !m.group(1).contains("bars.") && !m.group(1).contains("s.left") && !m.group(1).contains("p[0]")) {
                        bad.add(f.getFileName() + ": " + m.group());
                    }
                }
            }
        }
        assertTrue("use setPaddingRelative: " + bad, bad.isEmpty());
        assertFalse(bad.toString(), Files.walk(root().resolve("java")).anyMatch(f -> {
            try {
                String t = new String(Files.readAllBytes(f));
                return f.toString().endsWith(".java") && (t.contains("Gravity.LEFT") || t.contains("Gravity.RIGHT"));
            } catch (IOException e) {
                return false;
            }
        }));
    }
}
