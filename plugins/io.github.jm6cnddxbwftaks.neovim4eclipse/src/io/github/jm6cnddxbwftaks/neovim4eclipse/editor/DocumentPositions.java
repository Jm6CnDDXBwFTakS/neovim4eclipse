// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;

import io.github.jm6cnddxbwftaks.neovim4eclipse.sync.FinalNewlines;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.BlockSelection;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.Utf8Columns;

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
     * Eclipse offset to a position in Eclipse's lines, in Neovim's units: the step before
     * {@link #offsetToCursor}. Private, so that every position sent to Neovim goes through that
     * method and stays on a line Neovim has: this one also gives the empty line Eclipse shows after
     * a final newline, which Neovim does not have.
     *
     * @return {@code {line1Based, byteColumn0Based}}; the column is a UTF-8 byte offset, clamped to the line
     */
    private static int[] offsetToLineCol(IDocument doc, int offset) {
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
     * Eclipse offset to where Neovim's cursor goes: a 1-based line and a UTF-8 byte column, kept
     * inside Neovim's lines. The one conversion from an Eclipse offset to a Neovim position, for every
     * place that moves Neovim's cursor to an Eclipse position (a click, the caret moved by an Eclipse
     * command, an attach, the re-typing at Esc).
     *
     * <p>Why: a file that ends with a line break shows one more line in Eclipse than Neovim has, an
     * empty one after the final newline ({@link FinalNewlines#neovimLineCount}). Eclipse puts the
     * caret there (a click below the text, {@code Ctrl+End}, {@code Down} from the last line), but
     * Neovim refuses a cursor on a line it does not have, so the cursor stayed where it was, and the
     * next {@code x} edited that line instead. That position goes to the end of Neovim's last line
     * instead, the nearest place Neovim has, as a click below the text does in Vim.
     *
     * <p>Example: in {@code "alpha\nbeta\n"}, offset 11 (the empty third line Eclipse shows) is
     * {@code {2, 4}}, the end of {@code beta}; Neovim puts its normal-mode cursor on the {@code a}.
     */
    static int[] offsetToCursor(IDocument doc, int offset) {
        int[] lineCol = offsetToLineCol(doc, offset);
        if (doc == null) {
            return lineCol;
        }
        int lines = FinalNewlines.neovimLineCount(doc);
        if (lineCol[0] <= lines) {
            return lineCol;
        }
        try {
            IRegion last = doc.getLineInformation(lines - 1);
            String content = doc.get(last.getOffset(), last.getLength());
            return new int[] { lines, Utf8Columns.charToByteIndex(content, content.length()) };
        } catch (BadLocationException e) {
            return lineCol;
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
