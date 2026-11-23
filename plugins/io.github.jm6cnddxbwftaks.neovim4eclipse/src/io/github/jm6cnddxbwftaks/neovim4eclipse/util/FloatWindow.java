// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

/**
 * One of Neovim's floating windows as the editor draws it: its cells, and where it lies over the
 * editor's window, the one window of Neovim's layout that is on screen. Made by {@link Grids} at a {@code flush},
 * immutable; {@link FloatPlacement} turns the place into pixels.
 *
 * <p>Floating windows are drawn with or without the focus (docs/ARCHITECTURE.md, "Neovim's windows
 * drawn from their grids"): a plug-in's panel of key hints, hover text, a progress window opened in
 * the background, and a float the cursor is in ({@link #focused}: a picker's prompt, hover
 * documentation entered with a second {@code K}). Example, a panel of 5 rows across the bottom of an editor whose Neovim window has
 * 23 rows and 80 columns: {@code FloatWindow(grid 80x5, 18, 0, 23, 80, 1000)}.
 *
 * @param grid     the window's cells, its border included; with Neovim's cursor when the cursor
 *                 is on this window's grid, else none
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
 * @param mouseGrid  the grid a click or the wheel on this window goes to ({@code nvim_input_mouse}),
 *                 or -1 when it takes none: a float with {@code mouse = false} (as one with
 *                 {@code focusable = false} is by default), ui2's windows and the popup menu.
 *                 Example: Telescope's results, grid 7: a click on its row 3 is
 *                 {@code ("left", "press", "", 7, 3, col)}.
 */
public record FloatWindow(GridSnapshot grid, int row, int col, int hostRows, int hostCols, int zindex,
        boolean fromBottom, int mouseGrid) {

    /**
     * Whether this is a plug-in's float with Neovim's cursor in it: a picker's prompt,
     * {@code :Lazy}, hover documentation entered with a second {@code K}. The editor's caret is hidden meanwhile, so that one cursor shows where the keys go. Not one of ui2's windows, which also carry the cursor (a {@code :}
     * line being typed, the pager entered) but are Neovim's command line, with the editor's caret
     * shown as before; ui2's windows are the ones placed {@link #fromBottom}, and so is the
     * {@code :} line's list, which never has the cursor.
     */
    public boolean focused() {
        return grid.cursorRow() >= 0 && !fromBottom;
    }

    /** A float that takes no clicks. */
    public FloatWindow(GridSnapshot grid, int row, int col, int hostRows, int hostCols, int zindex,
            boolean fromBottom) {
        this(grid, row, col, hostRows, hostCols, zindex, fromBottom, -1);
    }

    /** A float placed by its rows from the window's top: every float but ui2's and the {@code :} line's list. */
    public FloatWindow(GridSnapshot grid, int row, int col, int hostRows, int hostCols, int zindex) {
        this(grid, row, col, hostRows, hostCols, zindex, false);
    }
}
