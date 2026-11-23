// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.Arrays;
import java.util.List;

/**
 * The cells of one Neovim grid: its size, each cell's text and highlight id, changed by the grid
 * events ({@link GridEvent}), for {@link Grids} (the editing Neovim's many grids, one per window).
 * Not thread-safe: used on the RPC reader thread only, and copied into a {@link GridSnapshot}
 * before anything leaves it.
 *
 * <p>Cells are kept row by row in two flat arrays: cell {@code (row, col)} is at
 * {@code row * width + col}. Example: a 3x2 grid after {@code line(1, 0, [("a", 5)])} holds
 * {@code text = [" ", " ", " ", "a", " ", " "]} and {@code hl = [0, 0, 0, 5, 0, 0]}.
 *
 * <p><b>A grid past {@link #MAX_HELD_CELLS} holds no cells</b>, only its size. A plug-in can ask for
 * a window of any size, and Neovim does not shrink it; the arrays are made here, at its
 * {@code grid_resize}, before {@link Grids} can tell whose grid it is (a new float's lines come
 * before its place). Example: a 5000x5000 float would cost about 200 MB here; it holds nothing, and
 * {@link Grids} leaves it undrawn, as it does any float past its limits.
 */
final class GridCells {

    /**
     * The most cells one grid holds: four times what all floats together may draw
     * ({@link Grids#MAX_FLOAT_CELLS}), since this also bounds the screen's grid and the editors'
     * windows, which are as large as the monitor allows. For scale: an 8K monitor in a 6x12 pixel
     * font is 1280 by 360, some 460,000 cells.
     */
    static final int MAX_HELD_CELLS = 4 * Grids.MAX_FLOAT_CELLS;

    int width;
    int height;
    /** Whether the arrays hold this grid's cells; {@code false} past {@link #MAX_HELD_CELLS}. */
    boolean held;
    String[] text;
    int[] hl;

    GridCells(int width, int height) {
        this.text = new String[0];
        this.hl = new int[0];
        resize(width, height);
    }

    /** {@link GridEvent.Resize}: what fits in the new size stays, as Neovim expects; new cells are blank. */
    void resize(int newWidth, int newHeight) {
        int w = Math.max(0, newWidth);
        int h = Math.max(0, newHeight);
        boolean fits = (long) w * h <= MAX_HELD_CELLS; // as long: two large ints would wrap
        String[] newText = new String[fits ? w * h : 0];
        int[] newHl = new int[newText.length];
        Arrays.fill(newText, " ");
        if (fits && held) {
            for (int r = 0; r < Math.min(height, h); r++) {
                for (int c = 0; c < Math.min(width, w); c++) {
                    newText[r * w + c] = text[r * width + c];
                    newHl[r * w + c] = hl[r * width + c];
                }
            }
        }
        width = w;
        height = h;
        held = fits;
        text = newText;
        hl = newHl;
    }

    /** {@link GridEvent.Clear}: every cell blank, in the default colors (highlight 0). */
    void clear() {
        Arrays.fill(text, " ");
        Arrays.fill(hl, 0);
    }

    /** {@link GridEvent.Line}: cells from {@code colStart} on; cells outside the grid are dropped. */
    void line(int row, int colStart, List<GridEvent.Cell> cells) {
        if (!held || row < 0 || row >= height) {
            return;
        }
        for (int i = 0; i < cells.size(); i++) {
            int col = colStart + i;
            if (col >= 0 && col < width) {
                text[row * width + col] = cells.get(i).text();
                hl[row * width + col] = cells.get(i).hl();
            }
        }
    }

    /** {@link GridEvent.Scroll}: rows move up by {@code rows} inside the region (down if negative). */
    void scroll(int top, int bot, int left, int right, int rows) {
        if (!held) {
            return;
        }
        top = Math.max(0, top);
        bot = Math.min(height, bot);
        left = Math.max(0, left);
        right = Math.min(width, right);
        if (rows > 0) {
            for (int r = top; r < bot - rows; r++) {
                copyRow(r + rows, r, left, right);
            }
        } else if (rows < 0) {
            for (int r = bot - 1; r >= top - rows; r--) {
                copyRow(r + rows, r, left, right);
            }
        }
    }

    private void copyRow(int from, int to, int left, int right) {
        System.arraycopy(text, from * width + left, text, to * width + left, right - left);
        System.arraycopy(hl, from * width + left, hl, to * width + left, right - left);
    }
}
