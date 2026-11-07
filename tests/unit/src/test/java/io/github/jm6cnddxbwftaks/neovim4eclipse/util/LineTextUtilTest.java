// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

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

    /** The first push decides the final newline from the text; later pushes keep it (see the class comment). */
    @Test
    void toNeovimKeepsTheFinalNewlineDecidedBefore() {
        NeovimLinesCheck.check("a\n", null, List.of("a"), true, "first push of text ending in a line break");
        NeovimLinesCheck.check("a", null, List.of("a"), false, "first push of text without one");
        NeovimLinesCheck.check("a\n", false, List.of("a", ""), false, "the break starts an empty last line");
        NeovimLinesCheck.check("a\n\n", false, List.of("a", "", ""), false, "two empty last lines");
        NeovimLinesCheck.check("a\n", true, List.of("a"), true, "a final newline stays one");
        NeovimLinesCheck.check("a", true, List.of("a"), false, "the last line break deleted in Eclipse");
        NeovimLinesCheck.check("", null, List.of(""), false, "an empty document");
    }

    private static final class NeovimLinesCheck {
        static void check(String text, Boolean known, List<String> lines, boolean finalNewline, String what) {
            assertEquals(new LineTextUtil.NeovimLines(lines, finalNewline), LineTextUtil.toNeovim(text, known), what);
        }
    }

    @Test
    void platformLineDelimiterIsTheSystemOne() {
        assertEquals(System.lineSeparator(), LineTextUtil.platformLineDelimiter());
    }
}
