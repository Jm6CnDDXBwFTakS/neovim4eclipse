// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

class LineTextUtilTest {

    // ---- nonEmptyLines ----

    @Test
    void nonEmptyLinesGivesOneEmptyLineForNullOrEmpty() {
        assertEquals(List.of(""), LineTextUtil.nonEmptyLines(null));
        assertEquals(List.of(""), LineTextUtil.nonEmptyLines(List.of()));
    }

    @Test
    void nonEmptyLinesKeepsEverythingElseIncludingTrailingBlankLines() {
        assertEquals(List.of("a", "", ""), LineTextUtil.nonEmptyLines(List.of("a", "", "")));
    }

    @Test
    void nonEmptyLinesReturnsAnIndependentCopy() {
        List<String> input = new java.util.ArrayList<>(List.of("a"));
        LineTextUtil.nonEmptyLines(input).add("b");
        assertEquals(List.of("a"), input);
    }

    // ---- splitDocumentLines ----

    @Test
    void splitAcceptsAllThreeLineEndings() {
        assertEquals(List.of("a", "b", "c"), LineTextUtil.splitDocumentLines("a\nb\nc"));
        assertEquals(List.of("a", "b"), LineTextUtil.splitDocumentLines("a\r\nb"));
        assertEquals(List.of("a", "b"), LineTextUtil.splitDocumentLines("a\rb"));
    }

    @Test
    void splitMixedEndingsInOneDocument() {
        assertEquals(List.of("a", "b", "c"), LineTextUtil.splitDocumentLines("a\r\nb\nc"));
    }

    @Test
    void finalLineBreakDoesNotAddAnEmptyLine() {
        assertEquals(List.of("a", "b"), LineTextUtil.splitDocumentLines("a\nb\n"));
        assertEquals(List.of("a", "b"), LineTextUtil.splitDocumentLines("a\r\nb\r\n"));
    }

    @Test
    void realBlankLinesAreKeptEvenAtTheEnd() {
        assertEquals(List.of("a", "", "b"), LineTextUtil.splitDocumentLines("a\n\nb"));
        assertEquals(List.of("a", ""), LineTextUtil.splitDocumentLines("a\n\n"));
    }

    @Test
    void splitEmptyOrNullGivesOneEmptyLine() {
        assertEquals(List.of(""), LineTextUtil.splitDocumentLines(""));
        assertEquals(List.of(""), LineTextUtil.splitDocumentLines(null));
        assertEquals(List.of(""), LineTextUtil.splitDocumentLines("\n"));
    }

    // ---- endsWithLineBreak ----

    @Test
    void endsWithLineBreak() {
        assertTrue(LineTextUtil.endsWithLineBreak("a\n"));
        assertTrue(LineTextUtil.endsWithLineBreak("a\r\n"));
        assertTrue(LineTextUtil.endsWithLineBreak("a\r"));
        assertTrue(LineTextUtil.endsWithLineBreak("\n"));
        assertFalse(LineTextUtil.endsWithLineBreak("a"));
        assertFalse(LineTextUtil.endsWithLineBreak(""));
        assertFalse(LineTextUtil.endsWithLineBreak(null));
    }

    // ---- linesToDocumentText ----

    @Test
    void finalLineBreakFlagDecidesTheTrailingDelimiter() {
        assertEquals("a\nb", LineTextUtil.linesToDocumentText(List.of("a", "b"), "\n", false));
        assertEquals("a\nb\n", LineTextUtil.linesToDocumentText(List.of("a", "b"), "\n", true));
        assertEquals("a", LineTextUtil.linesToDocumentText(List.of("a"), "\n", false));
        assertEquals("a\n", LineTextUtil.linesToDocumentText(List.of("a"), "\n", true));
    }

    @Test
    void crLfDelimiter() {
        assertEquals("a\r\nb\r\n", LineTextUtil.linesToDocumentText(List.of("a", "b"), "\r\n", true));
    }

    @Test
    void nullOrEmptyDelimiterFallsBackToLf() {
        assertEquals("a\nb\n", LineTextUtil.linesToDocumentText(List.of("a", "b"), null, true));
        assertEquals("a\nb\n", LineTextUtil.linesToDocumentText(List.of("a", "b"), "", true));
    }

    @Test
    void emptyOrNullListIsOneEmptyLine() {
        assertEquals("", LineTextUtil.linesToDocumentText(List.of(), "\n", false));
        assertEquals("\n", LineTextUtil.linesToDocumentText(null, "\n", true));
    }

    // ---- final newline round trips (previously lost) ----

    @Test
    void roundTripPreservesAFinalNewlineOnASingleLine() {
        assertRoundTrips("a\n", "\n");
    }

    @Test
    void roundTripPreservesTheAbsenceOfAFinalNewline() {
        assertRoundTrips("a", "\n");
        assertRoundTrips("a\nb", "\n");
    }

    @Test
    void roundTripEdgeCases() {
        for (String text : new String[] { "", "\n", "\n\n", "a\n\n", "\na", "a\n\nb\n" }) {
            assertRoundTrips(text, "\n");
        }
        assertRoundTrips("a\r\nb\r\n", "\r\n");
        assertRoundTrips("a\r\n\r\nb", "\r\n");
    }

    @Test
    void roundTripOfRandomTexts() {
        Random random = new Random(11);
        for (int i = 0; i < 2000; i++) {
            StringBuilder sb = new StringBuilder();
            int n = random.nextInt(8);
            for (int j = 0; j < n; j++) {
                sb.append(random.nextBoolean() ? "x" : "\n");
            }
            assertRoundTrips(sb.toString(), "\n");
        }
    }

    private static void assertRoundTrips(String text, String delim) {
        String back = LineTextUtil.linesToDocumentText(
                LineTextUtil.splitDocumentLines(text), delim, LineTextUtil.endsWithLineBreak(text));
        assertEquals(text, back, "round trip of [" + text.replace("\n", "\\n").replace("\r", "\\r") + "]");
    }

    @Test
    void platformLineDelimiterIsTheSystemOne() {
        assertEquals(System.lineSeparator(), LineTextUtil.platformLineDelimiter());
    }
}
