package io.github.katachinosekai.quillremote;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class FoldTest {
    static String numbers(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 1; i <= n; i++) {
            b.append(i == 1 ? "" : "\n").append(i);
        }
        return b.toString();
    }

    @Test
    public void shortTextStays() {
        String s = numbers(200);
        assertEquals(s, ChatView.fold(s));
    }

    @Test
    public void manyLinesKeepFirstSixty() {
        String f = ChatView.fold(numbers(3000));
        assertEquals(60, ChatView.lineCount(f));
        assertEquals(numbers(60), f);
    }

    @Test
    public void longLinesCutByChars() {
        String s = "x".repeat(30000);
        assertEquals(6000, ChatView.fold(s).length());
    }
}
