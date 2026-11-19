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
 */
final class GridCells {

    int width;
    int height;
    String[] text;
    int[] hl;

    GridCells(int width, int height) {
        this.text = new String[0];
        this.hl = new int[0];
        resize(width, height);
    }

    /** {@link GridEvent.Resize}: what fits in the new size stays, as Neovim expects; new cells are blank. */
    void resize(int newWidth, int newHeight) {
        String[] newText = new String[Math.max(0, newWidth) * Math.max(0, newHeight)];
        int[] newHl = new int[newText.length];
        Arrays.fill(newText, " ");
        for (int r = 0; r < Math.min(height, newHeight); r++) {
            for (int c = 0; c < Math.min(width, newWidth); c++) {
                newText[r * newWidth + c] = text[r * width + c];
                newHl[r * newWidth + c] = hl[r * width + c];
            }
        }
        width = Math.max(0, newWidth);
        height = Math.max(0, newHeight);
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
        if (row < 0 || row >= height) {
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
