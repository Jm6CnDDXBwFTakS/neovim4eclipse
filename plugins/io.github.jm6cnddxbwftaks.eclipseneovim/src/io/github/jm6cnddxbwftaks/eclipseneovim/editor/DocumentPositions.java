// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.editor;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;

import io.github.jm6cnddxbwftaks.eclipseneovim.util.BlockSelection;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.Utf8Columns;

/**
 * Converts between Eclipse document offsets and Neovim positions.
 *
 * <p>An Eclipse {@link IDocument} addresses text by one <em>offset</em>: the number of UTF-16
 * characters from the start. Neovim addresses it by a 1-based line and a 0-based UTF-8
 * <em>byte</em> column. On a line containing {@code é} (two bytes in UTF-8, one char in UTF-16) the
 * two columns differ, so every conversion goes through {@link Utf8Columns}. Never pass a Neovim
 * column straight to an Eclipse offset.
 */
final class DocumentPositions {

    private DocumentPositions() {
    }

    /**
     * Eclipse offset to Neovim position.
     *
     * @return {@code {line1Based, byteColumn0Based}}; the column is a UTF-8 byte offset, clamped to the line
     */
    static int[] offsetToLineCol(IDocument doc, int offset) {
        if (doc == null) {
            return new int[] { 1, 0 };
        }
        try {
            int clamped = Math.max(0, Math.min(offset, doc.getLength()));
            int line0 = doc.getLineOfOffset(clamped);
            IRegion line = doc.getLineInformation(line0);
            int charCol = Math.min(clamped - line.getOffset(), line.getLength());
            String content = doc.get(line.getOffset(), line.getLength());
            return new int[] { line0 + 1, Utf8Columns.charToByteIndex(content, charCol) };
        } catch (BadLocationException e) {
            return new int[] { 1, 0 };
        }
    }

    /**
     * Neovim position to Eclipse offset: converts the UTF-8 byte column to a char column and
     * clamps line and column to the document.
     *
     * @param line1 1-based line (clamped into the document)
     * @param byteCol0 0-based UTF-8 byte column
     */
    static int byteColToOffset(IDocument doc, int line1, int byteCol0) throws BadLocationException {
        int lineCount = doc.getNumberOfLines();
        int line0 = Math.max(0, Math.min(line1 - 1, lineCount - 1));
        IRegion line = doc.getLineInformation(line0);
        String content = doc.get(line.getOffset(), line.getLength());
        return line.getOffset() + Utf8Columns.byteToCharIndex(content, byteCol0);
    }

    /** {@link BlockSelection.Lines} view of an {@link IDocument}. */
    static final class DocumentLines implements BlockSelection.Lines {
        private final IDocument doc;

        DocumentLines(IDocument doc) {
            this.doc = doc;
        }

        @Override
        public int count() {
            return doc.getNumberOfLines();
        }

        @Override
        public String line(int line0) {
            try {
                IRegion info = doc.getLineInformation(line0);
                return doc.get(info.getOffset(), info.getLength());
            } catch (BadLocationException e) {
                return "";
            }
        }
    }
}
