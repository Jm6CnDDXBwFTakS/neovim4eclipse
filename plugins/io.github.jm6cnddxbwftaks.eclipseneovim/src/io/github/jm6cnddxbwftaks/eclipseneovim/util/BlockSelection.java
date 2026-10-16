// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Geometry of a Vim blockwise-visual selection ({@code Ctrl-V}).
 *
 * <p>A block is a rectangle in <em>virtual columns</em> (screen cells), not in characters or bytes:
 * a tab is several cells and a wide (CJK) character is two. Neovim reports the two corners of the
 * selection as line plus UTF-8 byte column; this class converts them to cells, takes the covered
 * cell range, and works out which characters of each line lie inside it. The result is what the
 * editor must highlight and what Neovim's own block commands ({@code d}, {@code y}, {@code I}, ...)
 * will operate on, provided both use the same tab width (the plug-in sets Neovim's
 * {@code 'tabstop'} to Eclipse's tab width on attach).
 *
 * <p>Pure Java, no Eclipse types, so it is unit tested.
 */
public final class BlockSelection {

    /** Characters {@code [startChar, endChar)} of line {@code line0} (0-based) are inside the block. */
    public record LineRange(int line0, int startChar, int endChar) {
    }

    /** Read access to the document's lines (without delimiters). */
    public interface Lines {
        int count();

        String line(int line0);
    }

    private BlockSelection() {
    }

    /**
     * Computes the covered characters of every line the block spans. Lines that are too short to
     * reach the block (or empty) are omitted, as Vim shows nothing for them.
     *
     * @param startLine1 line of one corner (1-based); the corners may be given in either order
     * @param startByteCol UTF-8 byte column of that corner (0-based)
     * @param endLine1 line of the other corner (1-based)
     * @param endByteCol UTF-8 byte column of the other corner (0-based)
     * @param toEndOfLine {@code true} after {@code $}: the block extends to the end of every line
     * @param tabstop tab width in cells (values below 1 are treated as 1)
     */
    public static List<LineRange> compute(Lines lines, int startLine1, int startByteCol, int endLine1,
            int endByteCol, boolean toEndOfLine, int tabstop) {
        int ts = Math.max(1, tabstop);
        int count = lines.count();
        if (count <= 0) {
            return List.of();
        }
        int lineA = clamp(startLine1 - 1, 0, count - 1);
        int lineB = clamp(endLine1 - 1, 0, count - 1);

        int[] a = cornerCells(lines.line(lineA), startByteCol, ts);
        int[] b = cornerCells(lines.line(lineB), endByteCol, ts);
        int left = Math.min(a[0], b[0]);
        int right = toEndOfLine ? Integer.MAX_VALUE : Math.max(a[1], b[1]);

        List<LineRange> result = new ArrayList<>();
        for (int line = Math.min(lineA, lineB); line <= Math.max(lineA, lineB); line++) {
            LineRange range = rangeOnLine(line, lines.line(line), left, right, ts);
            if (range != null) {
                result.add(range);
            }
        }
        return result;
    }

    /** First and last cell (0-based, inclusive) occupied by the character at a byte column. */
    private static int[] cornerCells(String text, int byteCol, int ts) {
        int charIndex = Utf8Columns.byteToCharIndex(text, byteCol);
        int cell = 0;
        int i = 0;
        while (i < charIndex && i < text.length()) {
            int cp = text.codePointAt(i);
            cell += cellWidth(cp, cell, ts);
            i += Character.charCount(cp);
        }
        int width = i < text.length() ? Math.max(1, cellWidth(text.codePointAt(i), cell, ts)) : 1;
        return new int[] { cell, cell + width - 1 };
    }

    private static LineRange rangeOnLine(int line, String text, int left, int right, int ts) {
        int cell = 0;
        int firstChar = -1;
        int endChar = -1;
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            int width = cellWidth(cp, cell, ts);
            int next = i + Character.charCount(cp);
            boolean inside;
            if (width == 0) {
                inside = firstChar >= 0 && endChar == i; // a combining mark follows its base character
            } else {
                inside = cell + width - 1 >= left && cell <= right;
            }
            if (inside) {
                if (firstChar < 0) {
                    firstChar = i;
                }
                endChar = next;
            }
            cell += width;
            i = next;
            // Past the block: stop, but first let zero-width marks attach to the last covered character.
            boolean markFollows = i < text.length() && cellWidth(text.codePointAt(i), cell, ts) == 0;
            if (cell > right && !markFollows) {
                break;
            }
        }
        return firstChar < 0 ? null : new LineRange(line, firstChar, endChar);
    }

    /**
     * Number of screen cells a character occupies when it starts at cell {@code cell}.
     * Tabs advance to the next tab stop; wide East Asian characters take two; combining marks none.
     */
    static int cellWidth(int codePoint, int cell, int tabstop) {
        if (codePoint == '\t') {
            return tabstop - (cell % tabstop);
        }
        int type = Character.getType(codePoint);
        if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK
                || type == Character.FORMAT) {
            return 0;
        }
        return isWide(codePoint) ? 2 : 1;
    }

    private static boolean isWide(int cp) {
        return cp >= 0x1100 && cp <= 0x115F
                || cp >= 0x2E80 && cp <= 0xA4CF && cp != 0x303F
                || cp >= 0xAC00 && cp <= 0xD7A3
                || cp >= 0xF900 && cp <= 0xFAFF
                || cp >= 0xFE30 && cp <= 0xFE6F
                || cp >= 0xFF00 && cp <= 0xFF60
                || cp >= 0xFFE0 && cp <= 0xFFE6
                || cp >= 0x1F300 && cp <= 0x1F64F
                || cp >= 0x1F900 && cp <= 0x1F9FF
                || cp >= 0x20000 && cp <= 0x3FFFD;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
