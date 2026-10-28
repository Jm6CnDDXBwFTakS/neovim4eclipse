// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

/**
 * The size of Neovim's screen (its UI "grid"), in cells, that makes Neovim's window show as many
 * lines and columns as the attached editor does (docs/design/VIEWPORT.md, 3.1).
 *
 * <p>Why the editor's size is not simply the grid's: Neovim's window is smaller than its screen.
 * The rows below it hold the status line ({@code 'laststatus'}), above it maybe a tab line or a
 * window bar; the columns on its left the line numbers, signs and fold column. The plug-in draws
 * none of these (Eclipse has its own), so the grid is made larger by exactly what they take, which
 * {@code LuaScripts.VIEWPORT} measures in Neovim as an {@link Overhead}. Example: an editor showing
 * 40 lines of 100 columns, with Neovim's default status line (1 row) and {@code set number} (4
 * columns: three digits and a space) needs a grid of 104 × 41; Neovim's window is then 100 × 40,
 * and {@code L} goes to the editor's last line.
 *
 * <p>The user's {@code 'number'}, {@code 'laststatus'} and the rest stay theirs: they are measured,
 * not turned off (VIEWPORT.md, section 1).
 *
 * @param columns the grid's width, at least 1
 * @param rows    the grid's height, at least 1
 */
public record ViewportSize(int columns, int rows) {

    public ViewportSize {
        if (columns < 1 || rows < 1) {
            throw new IllegalArgumentException("a grid has at least 1 × 1 cells, not " + columns + " × " + rows);
        }
    }

    /**
     * What Neovim's screen has besides the window's text, read in Neovim:
     * {@code rows = &lines - winheight(0)} (status line, tab line, window bar, command line) and
     * {@code columns = getwininfo(win)[1].textoff} (number, sign and fold columns).
     */
    public record Overhead(int rows, int columns) {

        /** Nothing besides the window: the grid is the editor's size (before Neovim has answered). */
        public static final Overhead NONE = new Overhead(0, 0);

        public Overhead {
            if (rows < 0 || columns < 0) {
                throw new IllegalArgumentException("negative overhead: " + rows + " rows, " + columns + " columns");
            }
        }
    }

    /**
     * The grid for an editor of {@code editorColumns} × {@code editorRows} cells. A tiny or
     * collapsed editor (0 rows while its part is minimized) still gets at least one cell of text, so
     * Neovim always has a window to put the cursor in.
     */
    public static ViewportSize forEditor(int editorColumns, int editorRows, Overhead overhead) {
        return new ViewportSize(Math.max(1, editorColumns) + overhead.columns(),
                Math.max(1, editorRows) + overhead.rows());
    }

    @Override
    public String toString() {
        return columns + "×" + rows;
    }
}
