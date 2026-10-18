// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import io.github.jm6cnddxbwftaks.eclipseneovim.util.LineRangeEdit.Edit;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.LineRangeEdit.LineSource;

import org.junit.jupiter.api.Test;

/**
 * Tests {@link LineRangeEdit} against a string-backed document that mimics Eclipse's
 * {@code IDocument} line model (including the empty "phantom" line after a trailing delimiter).
 */
class LineRangeEditTest {

    /** Eclipse-like document over a plain string. */
    private static final class StringDoc implements LineSource {
        private final String text;
        private final List<Integer> starts = new ArrayList<>();
        private final List<Integer> contentLengths = new ArrayList<>();

        StringDoc(String text) {
            this.text = text;
            int start = 0;
            int i = 0;
            while (i < text.length()) {
                char c = text.charAt(i);
                if (c == '\n' || c == '\r') {
                    starts.add(start);
                    contentLengths.add(i - start);
                    i += c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n' ? 2 : 1;
                    start = i;
                } else {
                    i++;
                }
            }
            starts.add(start); // last line; empty when the text ends with a delimiter (the phantom line)
            contentLengths.add(text.length() - start);
        }

        @Override public int lineCount() { return starts.size(); }
        @Override public int lineOffset(int line) { return starts.get(line); }
        @Override public int lineContentLength(int line) { return contentLengths.get(line); }
        @Override public int length() { return text.length(); }
        @Override public String text(int offset, int length) { return text.substring(offset, offset + length); }
    }

    private static String apply(String doc, int first, int last, List<String> newLines, String delim) {
        Edit edit = LineRangeEdit.compute(new StringDoc(doc), first, last, newLines, delim);
        if (edit == null) {
            return doc;
        }
        return doc.substring(0, edit.offset()) + edit.text() + doc.substring(edit.offset() + edit.length());
    }

    // ---- targeted cases ----

    @Test
    void replaceSingleLineKeepsDelimiterAndNeighbors() {
        assertEquals("a\nX\nc\n", apply("a\nb\nc\n", 1, 2, List.of("X"), "\n"));
    }

    @Test
    void replaceSingleLineKeepsCrLfDelimiter() {
        assertEquals("a\r\nX\r\nc\r\n", apply("a\r\nb\r\nc\r\n", 1, 2, List.of("X"), "\r\n"));
    }

    @Test
    void identicalContentIsANoOp() {
        assertNull(LineRangeEdit.compute(new StringDoc("a\nb\n"), 0, 1, List.of("a"), "\n"));
        assertNull(LineRangeEdit.compute(new StringDoc("a\nb\n"), 0, 2, List.of("a", "b"), "\n"));
    }

    @Test
    void joiningLinesDoesNotGlueTheFollowingLine() {
        // Regression: `J` on "a\nb\nc" yields first=0,last=2,["a b"]. The old code lost the
        // delimiter and produced "a bc".
        assertEquals("a b\nc\n", apply("a\nb\nc\n", 0, 2, List.of("a b"), "\n"));
    }

    @Test
    void pureInsertionInTheMiddleAddsWholeLines() {
        // Regression: `first == last` was treated as a full-buffer replace and wiped the document.
        assertEquals("a\nX\nb\nc\n", apply("a\nb\nc\n", 1, 1, List.of("X"), "\n"));
        assertEquals("a\nX\nY\nb\nc\n", apply("a\nb\nc\n", 1, 1, List.of("X", "Y"), "\n"));
    }

    @Test
    void linewisePasteAboveTheCurrentLineAddsANewLine() {
        // `yyP` on line 2 of "a\nb\nc": Neovim reports an insertion at index 1.
        assertEquals("a\nb\nb\nc\n", apply("a\nb\nc\n", 1, 1, List.of("b"), "\n"));
        // Same result if Neovim instead reports "line before + new line" as one replaced range.
        assertEquals("a\nb\nb\nc\n", apply("a\nb\nc\n", 0, 1, List.of("a", "b"), "\n"));
    }

    @Test
    void linewisePasteOnASingleLineDocumentAddsANewLine() {
        assertEquals("foo\nfoo", apply("foo", 0, 0, List.of("foo"), "\n"));
        assertEquals("foo\nfoo\n", apply("foo\n", 0, 0, List.of("foo"), "\n"));
    }

    @Test
    void insertionAtTheStart() {
        assertEquals("X\na\nb\n", apply("a\nb\n", 0, 0, List.of("X"), "\n"));
    }

    @Test
    void appendAfterLastLineWithFinalNewlineKeepsIt() {
        assertEquals("a\nb\nc\n\n", apply("a\nb\nc\n", 3, 3, List.of(""), "\n"));
        assertEquals("a\nb\nX\n", apply("a\nb\n", 2, 2, List.of("X"), "\n"));
        assertEquals("a\n\n", apply("a\n", 1, 1, List.of(""), "\n")); // Neovim `o` on the only line
    }

    @Test
    void appendAfterLastLineWithoutFinalNewlineStartsANewLineAndAddsNoFinalNewline() {
        assertEquals("a\nb", apply("a", 1, 1, List.of("b"), "\n"));
        assertEquals("a\nb\nc", apply("a", 1, 1, List.of("b", "c"), "\n"));
    }

    @Test
    void deleteMiddleLine() {
        assertEquals("a\nc\n", apply("a\nb\nc\n", 1, 2, List.of(), "\n"));
    }

    @Test
    void deleteSeveralLines() {
        assertEquals("a\nd\n", apply("a\nb\nc\nd\n", 1, 3, List.of(), "\n"));
    }

    @Test
    void deleteLastLineKeepsTheFinalNewlineStateOfTheDocument() {
        assertEquals("a\n", apply("a\nb\n", 1, 2, List.of(), "\n"));
        assertEquals("a", apply("a\nb", 1, 2, List.of(), "\n"), "no final newline before, none after");
    }

    @Test
    void replacingTheLastLineKeepsTheFinalNewlineStateOfTheDocument() {
        assertEquals("a\nX\n", apply("a\nb\n", 1, 2, List.of("X"), "\n"));
        assertEquals("a\nX", apply("a\nb", 1, 2, List.of("X"), "\n"));
        assertEquals("a\nX\nY\n", apply("a\nb\n", 1, 2, List.of("X", "Y"), "\n"));
        assertEquals("a\nX\nY", apply("a\nb", 1, 2, List.of("X", "Y"), "\n"));
    }

    @Test
    void trailingBlankLinesAreRealLines() {
        assertEquals("a\n\n\n", apply("a\n\n", 2, 2, List.of(""), "\n"));
        assertEquals("a\n", apply("a\n\n", 1, 2, List.of(), "\n"));
    }

    @Test
    void replaceOneLineWithSeveral() {
        assertEquals("a\nX\nY\nc\n", apply("a\nb\nc\n", 1, 2, List.of("X", "Y"), "\n"));
    }

    @Test
    void replaceSeveralLinesWithOne() {
        assertEquals("a\nX\nd\n", apply("a\nb\nc\nd\n", 1, 3, List.of("X"), "\n"));
    }

    @Test
    void negativeLastMeansThroughEndOfDocumentAndKeepsFinalNewlineState() {
        assertEquals("X\nY\n", apply("a\nb\nc\n", 0, -1, List.of("X", "Y"), "\n"));
        assertEquals("X\nY", apply("a\nb\nc", 0, -1, List.of("X", "Y"), "\n"));
    }

    @Test
    void nullNewLinesIsADeletion() {
        assertEquals("a\nc\n", apply("a\nb\nc\n", 1, 2, null, "\n"));
    }

    @Test
    void emptyDocumentReceivesFirstLine() {
        assertEquals("hello", apply("", 0, 1, List.of("hello"), "\n"));
    }

    // ---- randomized: result must EXACTLY equal the model edit on the line list ----

    @Test
    void randomEditsMatchTheLineListModelExactly() {
        Random random = new Random(20260101L);
        String[] delimiters = { "\n", "\r\n" };
        for (int round = 0; round < 5000; round++) {
            String delim = delimiters[random.nextInt(delimiters.length)];
            boolean finalNewline = random.nextBoolean();
            List<String> model = randomLines(random, 1 + random.nextInt(6), true);
            if (!finalNewline && model.size() > 1 && model.get(model.size() - 1).isEmpty()) {
                // Not representable: "a" + delimiter + "" is the text of ["a"] WITH a final newline.
                model.set(model.size() - 1, "z");
            }
            String doc = LineTextUtil.linesToDocumentText(model, delim, finalNewline);

            int first = random.nextInt(model.size() + 1);
            int last = first + random.nextInt(model.size() - first + 1);
            List<String> replacement = randomLines(random, random.nextInt(4), false);
            boolean wholeBuffer = first == 0 && last == model.size();
            if (wholeBuffer && replacement.isEmpty()) {
                replacement = List.of(""); // a Neovim buffer always keeps at least one line
            }

            List<String> expectedLines = new ArrayList<>(model.subList(0, first));
            expectedLines.addAll(replacement);
            expectedLines.addAll(model.subList(last, model.size()));
            String expected = LineTextUtil.linesToDocumentText(expectedLines, delim, finalNewline);

            String result = apply(doc, first, last, replacement, delim);

            assertEquals(expected, result, "round " + round + " doc=" + escape(doc) + " first=" + first
                    + " last=" + last + " new=" + replacement + " finalNewline=" + finalNewline);
        }
    }

    private static List<String> randomLines(Random random, int count, boolean allowEmpty) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            lines.add(allowEmpty && random.nextInt(4) == 0 ? "" : "l" + random.nextInt(100));
        }
        return lines;
    }

    private static String escape(String s) {
        return s.replace("\r", "\\r").replace("\n", "\\n");
    }

    /** {@code ciw} on "count": the edit covers only the word, so a linked rename around it survives. */
    @Test
    void aChangedWordIsReplacedAloneNotItsWholeLine() {
        String doc = "a\n  int count = 1;\nb\n";
        Edit edit = LineRangeEdit.compute(new StringDoc(doc), 1, 2, List.of("  int  = 1;"), "\n");
        assertEquals(new Edit(8, 5, ""), edit);
    }

    /** Deleting a line still removes the line and its line break, and nothing else. */
    @Test
    void aDeletedLineIsStillOneEdit() {
        Edit edit = LineRangeEdit.compute(new StringDoc("a\nb\nc\n"), 1, 2, List.of(), "\n");
        assertEquals(new Edit(2, 2, ""), edit);
    }
}
