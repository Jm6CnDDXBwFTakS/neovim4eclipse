// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.List;
import java.util.Map;

/**
 * One Neovim window's grid as it stood at a {@code flush}, for the UI thread to paint: immutable,
 * so the RPC thread can go on changing {@link Grids} while Eclipse draws this one. Example: the
 * command-line window, 120 columns and 7 rows, its cursor on row 6, column 0.
 *
 * @param window        the window's ID ({@code win_getid()})
 * @param width         columns
 * @param height        rows
 * @param text          one string per cell, row by row ({@code text.get(row * width + col)}):
 *                      a character, or {@code ""} right of a double-width one
 * @param hl            each cell's highlight id, in the same order; 0 is the default colors
 * @param attrs         what the highlight ids used here look like
 * @param defaultColors Neovim's default text and background colors
 * @param cursorRow     the cursor's row on this grid, -1 when the cursor is on another grid
 * @param cursorCol     the cursor's column, -1 likewise
 */
public record GridSnapshot(int window, int width, int height, List<String> text, int[] hl,
        Map<Integer, GridEvent.HlAttr> attrs, GridEvent.DefaultColors defaultColors, int cursorRow, int cursorCol) {

    public GridSnapshot {
        text = List.copyOf(text);
        hl = hl.clone();
        attrs = Map.copyOf(attrs);
    }

    /** The text of cell {@code (row, col)}. */
    public String textAt(int row, int col) {
        return text.get(row * width + col);
    }

    /** The highlight id of cell {@code (row, col)}. */
    public int hlAt(int row, int col) {
        return hl[row * width + col];
    }

    /**
     * The text and background colors of highlight {@code id}, {@code {foreground, background}} as
     * {@code 0xRRGGBB}. A color the highlight leaves to the default is the editor's own, not
     * Neovim's default: Eclipse's ground under Neovim's groups, so the window looks part of the
     * editor whatever Neovim's colorscheme says its background is. {@code reverse} swaps the two.
     * Example: a highlight with text color {@code 0x8CF8F7} and no background, in an editor of
     * text {@code 0x000000} on {@code 0xFFFFFF}, gives {@code {0x8CF8F7, 0xFFFFFF}}.
     */
    public int[] colors(int id, int editorForeground, int editorBackground) {
        GridEvent.HlAttr a = attr(id);
        int fg = a.foreground() == GridEvent.NO_COLOR ? editorForeground : a.foreground();
        int bg = a.background() == GridEvent.NO_COLOR ? editorBackground : a.background();
        return a.reverse() ? new int[] { bg, fg } : new int[] { fg, bg };
    }

    /** A row's text, the cells joined; for tests and messages. Example: {@code ":w    "}. */
    public String row(int row) {
        return String.join("", text.subList(row * width, (row + 1) * width));
    }

    /** The highlight of {@code id}; the default colors' (no attributes) when it is 0 or unknown. */
    public GridEvent.HlAttr attr(int id) {
        GridEvent.HlAttr attr = attrs.get(id);
        return attr != null ? attr
                : new GridEvent.HlAttr(id, GridEvent.NO_COLOR, GridEvent.NO_COLOR, false, false, false, false, false);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof GridSnapshot s && window == s.window && width == s.width && height == s.height
                && text.equals(s.text) && java.util.Arrays.equals(hl, s.hl) && attrs.equals(s.attrs)
                && defaultColors.equals(s.defaultColors) && cursorRow == s.cursorRow && cursorCol == s.cursorCol;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(window, width, height, text, java.util.Arrays.hashCode(hl), cursorRow, cursorCol);
    }

    @Override
    public String toString() {
        return "GridSnapshot[window=" + window + ", " + width + "x" + height + ", cursor=" + cursorRow + "," + cursorCol + "]";
    }
}
