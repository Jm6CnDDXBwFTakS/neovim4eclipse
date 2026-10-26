// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.ArrayList;
import java.util.List;

/**
 * The characters of each line that a blockwise-visual selection ({@code Ctrl-V}) covers, for the
 * editor's highlight.
 *
 * <p>A block is a rectangle in <em>screen cells</em>: a tab is several cells, a wide (CJK)
 * character two, a combining mark none, and after {@code $} each line ends at its own end. Neovim
 * works that out itself: {@code getregionpos()} (Neovim 0.10, called in the state snapshot) returns,
 * for every line of the block, the first and the last byte it covers, the same bytes Neovim's own
 * {@code d}, {@code y} and {@code I} act on. This class only turns those bytes into the line's UTF-16
 * characters. (Until 0.1.7 it repeated Neovim's cell arithmetic in Java, from the two corners,
 * {@code 'tabstop'} and {@code curswant}.)
 *
 * <p>Example: in {@code "a\tbcd"} (tab stop 8) a block over cells 0 to 4 covers {@code a} and the
 * tab, which is only partly inside: Neovim reports bytes 1 to 2 ({@code start col 1, end col 2}),
 * and the highlight is characters 0 to 2, the whole tab, as Vim shows it.
 *
 * <p>Pure Java, no Eclipse types; unit-tested in {@code BlockSelectionTest}.
 */
public final class BlockSelection {

    /** Characters {@code [startChar, endChar)} of line {@code line0} (0-based) are inside the block. */
    public record LineRange(int line0, int startChar, int endChar) {
    }

    /**
     * One line of a block as {@code getregionpos()} reports it: 1-based line, 1-based byte of the
     * first character covered, 1-based byte inside the last character covered (its last byte when
     * the character is covered whole, its first when only partly). A line that does not reach the
     * block has its start past the line's end.
     */
    public record ByteRange(int line1, int startCol1, int endCol1) {
    }

    /** Read access to the document's lines (without delimiters). */
    public interface Lines {
        int count();

        String line(int line0);
    }

    private BlockSelection() {
    }

    /**
     * The characters each range covers, in document lines. A character only partly covered (a tab,
     * a wide character) is included whole; a line too short to reach the block, or empty, is left
     * out, as Vim shows nothing on it; a line the document does not have is left out too (the two
     * can differ for a moment while an edit travels).
     */
    public static List<LineRange> compute(Lines lines, List<ByteRange> ranges) {
        List<LineRange> result = new ArrayList<>();
        for (ByteRange range : ranges) {
            int line0 = range.line1() - 1;
            if (line0 < 0 || line0 >= lines.count()) {
                continue;
            }
            String text = lines.line(line0);
            int start = Utf8Columns.byteToCharIndex(text, range.startCol1() - 1);
            if (start >= text.length() || range.endCol1() < range.startCol1()) {
                continue;
            }
            int last = Math.max(start, Utf8Columns.byteToCharIndex(text, range.endCol1() - 1));
            int end = last >= text.length() ? text.length() : last + Character.charCount(text.codePointAt(last));
            result.add(new LineRange(line0, start, end));
        }
        return result;
    }
}
