// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.Diagnostic.Severity;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.DiagnosticPositions.Lines;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.DiagnosticPositions.Problem;

class DiagnosticPositionsTest {

    /** {@link Lines} over a string with {@code \n} or {@code \r\n} line breaks, counted as Neovim does. */
    private static Lines lines(String text) {
        List<Integer> starts = new ArrayList<>(List.of(0));
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                starts.add(i + 1);
            }
        }
        return new Lines() {
            @Override
            public int length() {
                return text.length();
            }

            @Override
            public int lineCount() {
                return text.endsWith("\n") ? starts.size() - 1 : starts.size();
            }

            @Override
            public int lineOf(int offset) {
                int line = 0;
                while (line + 1 < starts.size() && starts.get(line + 1) <= offset) {
                    line++;
                }
                return line;
            }

            @Override
            public int lineStart(int line) {
                return starts.get(line);
            }

            @Override
            public String lineText(int line) {
                int end = line + 1 < starts.size() ? starts.get(line + 1) - 1 : text.length();
                String raw = text.substring(starts.get(line), Math.max(starts.get(line), end));
                return raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            }
        };
    }

    private static Diagnostic one(String text, int offset, int length) {
        return DiagnosticPositions.toDiagnostic(new Problem(offset, length, Severity.ERROR, "m"), lines(text));
    }

    @Test
    void asciiOffsetsBecomeLineAndColumn() {
        // "int x;\nint y = z;\n": "z" is at offset 15, line 1, column 8.
        assertEquals(new Diagnostic(1, 8, 1, 9, Severity.ERROR, "m"), one("int x;\nint y = z;\n", 15, 1));
    }

    @Test
    void columnsAreUtf8BytesAfterMultiByteText() {
        // "é" is 2 bytes, "日" 3: the "1" at char column 11 is byte column 12, in "String é = 1;".
        assertEquals(new Diagnostic(1, 12, 1, 13, Severity.ERROR, "m"), one("int x;\nString é = 1;\n", 18, 1));
        // After an emoji (2 UTF-16 chars, 4 bytes): "😀x" with an error on x.
        assertEquals(new Diagnostic(0, 4, 0, 5, Severity.ERROR, "m"), one("😀x", 2, 1));
        // A problem on a multi-byte character itself covers all of its bytes: "日" is bytes 1 to 4.
        assertEquals(new Diagnostic(0, 1, 0, 4, Severity.ERROR, "m"), one("a日bc", 1, 1));
    }

    @Test
    void aProblemAtTheEndOfALine() {
        // A missing ";" is reported after the last character: column = the line's length, empty range.
        assertEquals(new Diagnostic(0, 5, 0, 5, Severity.ERROR, "m"), one("int x\nnext", 5, 0));
    }

    @Test
    void aRangeOverTwoLines() {
        // From "x" on line 0 to the end of "ab" on line 1.
        assertEquals(new Diagnostic(0, 4, 1, 2, Severity.ERROR, "m"), one("int x\nab\n", 4, 4));
    }

    @Test
    void aZeroOrNegativeLengthIsAPoint() {
        assertEquals(new Diagnostic(0, 2, 0, 2, Severity.ERROR, "m"), one("abcd", 2, 0));
        assertEquals(new Diagnostic(0, 2, 0, 2, Severity.ERROR, "m"), one("abcd", 2, -3));
    }

    @Test
    void crLfLineBreaksAreNotPartOfTheColumn() {
        // An end offset inside "\r\n" lands at the end of the line's text.
        assertEquals(new Diagnostic(0, 1, 0, 2, Severity.ERROR, "m"), one("ab\r\ncd", 1, 2));
        assertEquals(new Diagnostic(1, 0, 1, 2, Severity.ERROR, "m"), one("ab\r\ncd", 4, 2));
    }

    @Test
    void anOffsetAfterTheFinalLineBreakIsTheEndOfTheLastNeovimLine() {
        // "a\n": Eclipse has an empty line 1 after the break, Neovim's buffer has only line 0.
        assertEquals(new Diagnostic(0, 1, 0, 1, Severity.ERROR, "m"), one("a\n", 2, 0));
    }

    @Test
    void anOffsetPastTheTextIsClamped() {
        // A problem Eclipse has not moved yet after text was deleted.
        assertEquals(new Diagnostic(0, 3, 0, 3, Severity.ERROR, "m"), one("abc", 10, 5));
        assertEquals(new Diagnostic(0, 0, 0, 1, Severity.ERROR, "m"), one("abc", -4, 5));
    }

    @Test
    void anEmptyDocumentHasOneEmptyLine() {
        assertEquals(new Diagnostic(0, 0, 0, 0, Severity.WARN, "w"),
                DiagnosticPositions.toDiagnostic(new Problem(0, 0, Severity.WARN, "w"), lines("")));
    }

    @Test
    void theListIsSortedByPositionThenSeverity() {
        List<Diagnostic> sorted = DiagnosticPositions.toDiagnostics(List.of(
                new Problem(6, 1, Severity.INFO, "c"),
                new Problem(0, 1, Severity.WARN, "b"),
                new Problem(0, 1, Severity.ERROR, "a")), lines("abc\ndef\n"));
        assertEquals(List.of(
                new Diagnostic(0, 0, 0, 1, Severity.ERROR, "a"),
                new Diagnostic(0, 0, 0, 1, Severity.WARN, "b"),
                new Diagnostic(1, 2, 1, 3, Severity.INFO, "c")), sorted);
    }

    @Test
    void aMissingMessageIsEmpty() {
        assertEquals("", DiagnosticPositions.toDiagnostic(new Problem(0, 0, Severity.INFO, null), lines("x")).message());
    }
}
