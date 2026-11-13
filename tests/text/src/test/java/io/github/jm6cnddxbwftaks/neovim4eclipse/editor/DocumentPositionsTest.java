// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.junit.jupiter.api.Test;

/** Offsets (UTF-16 chars) versus Neovim positions (1-based line, UTF-8 byte column) on a real document. */
class DocumentPositionsTest {

    private final IDocument doc = new Document("aé b\nline2\n");

    @Test
    void offsetToCursorCountsUtf8Bytes() {
        assertArrayEquals(new int[] { 1, 0 }, DocumentPositions.offsetToCursor(doc, 0));
        // "aé" is 2 chars but 3 bytes, so the space after it is char 2 and byte 3.
        assertArrayEquals(new int[] { 1, 3 }, DocumentPositions.offsetToCursor(doc, 2));
        assertArrayEquals(new int[] { 2, 2 }, DocumentPositions.offsetToCursor(doc, 7));
    }

    @Test
    void offsetsOutsideTheDocumentAreClamped() {
        assertArrayEquals(new int[] { 1, 0 }, DocumentPositions.offsetToCursor(doc, -5));
        assertArrayEquals(new int[] { 2, 5 }, DocumentPositions.offsetToCursor(doc, 999), "the end of line2");
        assertArrayEquals(new int[] { 1, 0 }, DocumentPositions.offsetToCursor(null, 3));
    }

    /**
     * The empty line Eclipse shows after a final newline is no line of Neovim's: Neovim's cursor
     * goes to the end of the last line instead, in bytes.
     */
    @Test
    void theCursorStaysOnALineNeovimHas() {
        IDocument text = new Document("alpha\nbeté\n");
        assertArrayEquals(new int[] { 2, 5 }, DocumentPositions.offsetToCursor(text, text.getLength()), "the empty third line");
        assertArrayEquals(new int[] { 2, 5 }, DocumentPositions.offsetToCursor(text, 999));
        assertArrayEquals(new int[] { 2, 5 }, DocumentPositions.offsetToCursor(text, 10), "after the é: the same place");
        assertArrayEquals(new int[] { 1, 2 }, DocumentPositions.offsetToCursor(text, 2));
        IDocument noFinalNewline = new Document("alpha\nbeta");
        assertArrayEquals(new int[] { 2, 4 }, DocumentPositions.offsetToCursor(noFinalNewline, 999));
        assertArrayEquals(new int[] { 1, 0 }, DocumentPositions.offsetToCursor(null, 3));
    }

    /**
     * Every offset on a line Neovim has comes back as itself. The last one, the empty line after the
     * final newline, is the end of the line above for Neovim (the test before).
     */
    @Test
    void byteColumnsRoundTrip() throws Exception {
        for (int offset = 0; offset < doc.getLength(); offset++) {
            int[] lineCol = DocumentPositions.offsetToCursor(doc, offset);
            assertEquals(offset, DocumentPositions.byteColToOffset(doc, lineCol[0], lineCol[1]), "offset " + offset);
        }
    }

    @Test
    void byteColumnsRoundTripOnTextWithTabsAndWideCharacters() throws Exception {
        IDocument sample = VimText.parse(VimText.SAMPLE).document();
        String text = sample.get();
        for (int offset = 0; offset < sample.getLength(); offset++) { // the last: the empty line after the final newline
            if (offset > 0 && Character.isHighSurrogate(text.charAt(offset - 1))) {
                continue; // between the two halves of 😀: not a place a caret can be
            }
            int[] lineCol = DocumentPositions.offsetToCursor(sample, offset);
            assertEquals(offset, DocumentPositions.byteColToOffset(sample, lineCol[0], lineCol[1]), "offset " + offset);
        }
    }

    @Test
    void anEmojiIsFourBytes() {
        VimText t = VimText.parse("smile 😀| end");
        assertArrayEquals(new int[] { 1, 10 }, DocumentPositions.offsetToCursor(t.document(), t.caret()));
    }

    @Test
    void documentLinesExposeTheTextWithoutDelimiters() {
        DocumentPositions.DocumentLines lines = new DocumentPositions.DocumentLines(doc);
        assertEquals(3, lines.count());
        assertEquals("aé b", lines.line(0));
        assertEquals("", lines.line(2));
        assertEquals("", lines.line(42));
    }
}
