// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.text.BreakIterator;
import java.util.Locale;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.LineRangeEdit.LineSource;

/**
 * Turns Neovim's characterwise ({@code v}) or linewise ({@code V}) visual selection into an Eclipse
 * selection: a start and end offset in the document, and the end the caret belongs at.
 *
 * <p>Neovim reports two corners, the anchor (where {@code v} was pressed) and the cursor, each as a
 * 1-based line and a 0-based UTF-8 byte column. Three things differ from an Eclipse selection:
 * <ul>
 *   <li><b>Order.</b> The anchor may come after the cursor ({@code V} then {@code k}, or after
 *       {@code o}). The corners are ordered first, so that a linewise start is the start of the top
 *       line and its end the end of the bottom line.</li>
 *   <li><b>Inclusive end.</b> Vim's selection includes the character under the cursor; an Eclipse
 *       selection's end is exclusive, so the end moves past that character: past the whole
 *       grapheme cluster, so both halves of an emoji, an accent combined with its letter, and
 *       both chars of a {@code \r\n} line break ({@link #afterCharacter}).</li>
 *   <li><b>Caret.</b> Eclipse shows the caret at one end; it goes where Neovim's cursor is, so that
 *       {@code o} visibly jumps to the other end.</li>
 * </ul>
 *
 * <p>Example: in {@code "alpha\nbeta"}, {@code v} on "l" (line 1, byte 1) and the cursor on "e"
 * (line 2, byte 1) gives start 1, end 8 (after "be"), caret at the end. Blockwise selections come
 * from Neovim line by line ({@link BlockSelection}). Pure; no Eclipse types.
 */
public final class VisualSelectionGeometry {

    /**
     * A selection in document offsets.
     *
     * @param start        first selected offset
     * @param end          offset after the last selected character (exclusive)
     * @param caretAtStart whether Neovim's cursor is at {@code start} (else at {@code end})
     */
    public record Range(int start, int end, boolean caretAtStart) {

        /** The end opposite the caret: where the selection was started from. */
        public int anchor() {
            return caretAtStart ? end : start;
        }

        /** The end the caret is at. */
        public int caret() {
            return caretAtStart ? start : end;
        }
    }

    private VisualSelectionGeometry() {
    }

    /**
     * @param lineWise {@code true} for {@code V}: whole lines, columns ignored
     */
    public static Range of(LineSource doc, int anchorLine1, int anchorByteCol0, int cursorLine1,
            int cursorByteCol0, boolean lineWise) {
        boolean anchorFirst = anchorLine1 < cursorLine1
                || (anchorLine1 == cursorLine1 && anchorByteCol0 <= cursorByteCol0);
        int topLine = anchorFirst ? anchorLine1 : cursorLine1;
        int topCol = anchorFirst ? anchorByteCol0 : cursorByteCol0;
        int bottomLine = anchorFirst ? cursorLine1 : anchorLine1;
        int bottomCol = anchorFirst ? cursorByteCol0 : anchorByteCol0;

        int start;
        int end;
        if (lineWise) {
            start = doc.lineOffset(clampLine(doc, topLine));
            int bottom = clampLine(doc, bottomLine);
            end = doc.lineOffset(bottom) + doc.lineContentLength(bottom);
        } else {
            start = offsetOf(doc, topLine, topCol);
            end = afterCharacterAt(doc, offsetOf(doc, bottomLine, bottomCol));
        }
        return new Range(start, end, !anchorFirst);
    }

    /**
     * A Neovim position as a document offset, clamped to the document: a line past the end is the
     * last line, a column past the end of its line is the line's end.
     */
    public static int offsetOf(LineSource doc, int line1, int byteCol0) {
        int line0 = clampLine(doc, line1);
        int lineOffset = doc.lineOffset(line0);
        String content = doc.text(lineOffset, doc.lineContentLength(line0));
        return lineOffset + Utf8Columns.byteToCharIndex(content, byteCol0);
    }

    private static int clampLine(LineSource doc, int line1) {
        return Math.max(0, Math.min(line1 - 1, doc.lineCount() - 1));
    }

    /**
     * The offset after the character at {@code offset}, read from a window of the document that
     * grows until the character's end is inside it (a grapheme cluster has no length limit, but is
     * almost always a few chars long; the whole rest of a large document is not copied for it).
     */
    private static int afterCharacterAt(LineSource doc, int offset) {
        int window = 16;
        while (offset < doc.length()) {
            int length = Math.min(window, doc.length() - offset);
            String text = doc.text(offset, length);
            int end = afterCharacter(text, 0);
            // The window's end is always a boundary; only one found before it is surely the character's.
            if (end < length || offset + length >= doc.length()) {
                return offset + end;
            }
            window *= 2;
        }
        return offset;
    }

    /**
     * The index after the user-perceived character (grapheme cluster) at {@code index}: what Vim's
     * cursor covers, and so what its inclusive selection end takes. Examples:
     * <ul>
     *   <li>"e" followed by U+0301, a combining acute accent (one "é" on screen): Neovim's cursor
     *       is on the "e", and {@code vy} yanks both, so the selection ends after the accent.</li>
     *   <li>An emoji outside the BMP: both UTF-16 halves; a flag or a family emoji (several code
     *       points joined): all of them.</li>
     *   <li>{@code "\r\n"}: both. A selection that ends on an empty line of a Windows file takes its
     *       line break whole; half of it would leave a lone {@code \n}, a line break of its own.</li>
     * </ul>
     * {@link BreakIterator#getCharacterInstance} follows Unicode's extended grapheme clusters (UAX
     * #29; since Java 20), which also keep {@code \r\n} together; that case is checked explicitly
     * anyway, since a split line break would change the line count.
     *
     * @return {@code text.length()} when {@code index} is at or past the end
     */
    static int afterCharacter(String text, int index) {
        if (index >= text.length()) {
            return text.length();
        }
        if (text.charAt(index) == '\r' && index + 1 < text.length() && text.charAt(index + 1) == '\n') {
            return index + 2;
        }
        BreakIterator characters = BreakIterator.getCharacterInstance(Locale.ROOT);
        characters.setText(text);
        int end = characters.following(index);
        return end == BreakIterator.DONE ? text.length() : end;
    }
}
