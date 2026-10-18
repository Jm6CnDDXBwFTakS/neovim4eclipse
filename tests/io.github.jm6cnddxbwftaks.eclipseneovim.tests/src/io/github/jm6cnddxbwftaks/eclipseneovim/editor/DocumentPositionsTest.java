// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.editor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.junit.jupiter.api.Test;

/** Offsets (UTF-16 chars) versus Neovim positions (1-based line, UTF-8 byte column) on a real document. */
class DocumentPositionsTest {

    private final IDocument doc = new Document("aé b\nline2\n");

    @Test
    void offsetToLineColCountsUtf8Bytes() {
        assertArrayEquals(new int[] { 1, 0 }, DocumentPositions.offsetToLineCol(doc, 0));
        // "aé" is 2 chars but 3 bytes, so the space after it is char 2 and byte 3.
        assertArrayEquals(new int[] { 1, 3 }, DocumentPositions.offsetToLineCol(doc, 2));
        assertArrayEquals(new int[] { 2, 2 }, DocumentPositions.offsetToLineCol(doc, 7));
    }

    @Test
    void offsetsOutsideTheDocumentAreClamped() {
        assertArrayEquals(new int[] { 1, 0 }, DocumentPositions.offsetToLineCol(doc, -5));
        assertArrayEquals(new int[] { 3, 0 }, DocumentPositions.offsetToLineCol(doc, 999));
        assertArrayEquals(new int[] { 1, 0 }, DocumentPositions.offsetToLineCol(null, 3));
    }

    @Test
    void byteColumnsRoundTrip() throws Exception {
        for (int offset = 0; offset <= doc.getLength(); offset++) {
            int[] lineCol = DocumentPositions.offsetToLineCol(doc, offset);
            assertEquals(offset, DocumentPositions.byteColToOffset(doc, lineCol[0], lineCol[1]), "offset " + offset);
        }
    }

    @Test
    void byteColumnsRoundTripOnTextWithTabsAndWideCharacters() throws Exception {
        IDocument sample = VimText.parse(VimText.SAMPLE).document();
        String text = sample.get();
        for (int offset = 0; offset <= sample.getLength(); offset++) {
            if (offset > 0 && Character.isHighSurrogate(text.charAt(offset - 1))) {
                continue; // between the two halves of 😀: not a place a caret can be
            }
            int[] lineCol = DocumentPositions.offsetToLineCol(sample, offset);
            assertEquals(offset, DocumentPositions.byteColToOffset(sample, lineCol[0], lineCol[1]), "offset " + offset);
        }
    }

    @Test
    void anEmojiIsFourBytes() {
        VimText t = VimText.parse("smile 😀| end");
        assertArrayEquals(new int[] { 1, 10 }, DocumentPositions.offsetToLineCol(t.document(), t.caret()));
    }

    @Test
    void linewiseVisualCornersCoverWholeLines() throws Exception {
        assertEquals(5, DocumentPositions.visualToOffset(doc, 2, 3, true, true));
        assertEquals(10, DocumentPositions.visualToOffset(doc, 2, 0, true, false));
        assertEquals(8, DocumentPositions.visualToOffset(doc, 2, 3, false, true));
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
