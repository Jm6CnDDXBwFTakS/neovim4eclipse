// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

/**
 * One of Neovim's floating windows as the editor draws it: its cells, and where it lies over the
 * editor's window, the one window of Neovim's layout that is on screen. Made by {@link Grids} at a {@code flush},
 * immutable; {@link FloatPlacement} turns the place into pixels.
 *
 * <p>Only floating windows without the focus are drawn (docs/ARCHITECTURE.md, "Neovim's windows
 * drawn from their grids"): a plug-in's panel of key hints, hover text, a progress window opened in
 * the background. Example, a panel of 5 rows across the bottom of an editor whose Neovim window has
 * 23 rows and 80 columns: {@code FloatWindow(grid 80x5, 18, 0, 23, 80, 1000)}.
 *
 * @param grid     the window's cells, its border included; no cursor, since it has no focus
 * @param row      the row of its top left cell, counted from the editor's window's first row;
 *                 negative above it
 * @param col      the column of its top left cell, counted from the editor's window's first column
 * @param hostRows the rows of the editor's window and of Neovim's screen below it (status line,
 *                 command line), which the editor draws too; 0 when not known
 * @param hostCols its columns, 0 when not known
 * @param zindex   its place in the stack ({@code zindex}): a higher one lies on top
 * @param fromBottom whether its rows are counted from the editor's bottom edge, as the status line
 *                 and the command line under the text are ({@link FloatPlacement}): ui2's windows
 *                 and the {@code :} line's list, which Neovim stacks on those rows. Example: ui2's
 *                 message window right above the status line stays right above it, also when the
 *                 editor's height is not a whole number of rows.
 */
public record FloatWindow(GridSnapshot grid, int row, int col, int hostRows, int hostCols, int zindex,
        boolean fromBottom) {

    /** A float placed by its rows from the window's top: every float but ui2's and the {@code :} line's list. */
    public FloatWindow(GridSnapshot grid, int row, int col, int hostRows, int hostCols, int zindex) {
        this(grid, row, col, hostRows, hostCols, zindex, false);
    }
}
