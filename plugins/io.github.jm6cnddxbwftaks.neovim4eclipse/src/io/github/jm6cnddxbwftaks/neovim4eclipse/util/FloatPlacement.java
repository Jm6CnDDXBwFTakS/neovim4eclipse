// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

/**
 * Where a floating window of Neovim goes in the editor, in pixels.
 *
 * <h2>The rule</h2>
 * One rule for every float: the row and column Neovim worked out for it, counted from the editor's
 * window ({@link FloatWindow}), times Eclipse's cell (the editor font's average character width,
 * the editor's line height). Neovim's window has the editor's rows and columns while the viewport
 * is on ({@code LuaScripts.VIEWPORT}; without it no float is drawn), so a cell of Neovim's is a
 * cell of the editor's.
 *
 * <p>Three corrections, because the editor is measured in pixels and its last row and column are
 * seldom whole:
 * <ul>
 *   <li>A float that reaches the window's last row sits on the editor's bottom edge; one that
 *       reaches its last column, on the right edge. Example: a panel of key hints across the
 *       bottom, 5 rows, in an editor 460 pixels high with rows of 19: by rows it would end at
 *       pixel 437 and leave a strip of text showing under it; it ends at 460.</li>
 *   <li>Any other float that would stick out is moved back in, as far as it fits. One larger than
 *       the editor keeps its top left corner in view; the rest is cut off by the editor's
 *       edges.</li>
 *   <li>Before both: a float that begins at the window's first row or column stays there,
 *       whatever its other end does. Example: the same panel is as wide as Neovim's screen, 80 cells of 10 pixels in
 *       an editor 805 pixels wide. Put on the right edge it would begin at pixel 5, and the
 *       editor's text would show in the strip left of it; it begins at 0, and the 5 pixels that
 *       belong to no whole cell stay at the right, where the lines seldom reach.</li>
 * </ul>
 *
 * <p>Not corrected: a row of Eclipse's that is higher than a line (a code mining above it) or a
 * line Eclipse wraps at another character than Neovim. A float anchored to a text position can
 * then sit a row off.
 */
public final class FloatPlacement {

    /** A rectangle in the editor's client area, in pixels. */
    public record Box(int x, int y, int width, int height) {
    }

    private FloatPlacement() {
    }

    /**
     * The rectangle of {@code window}.
     *
     * @param cellWidth  one cell's width in pixels
     * @param cellHeight one cell's height
     * @param areaWidth  the editor's client area
     * @param areaHeight likewise
     */
    public static Box place(FloatWindow window, int cellWidth, int cellHeight, int areaWidth, int areaHeight) {
        int columns = window.grid().width();
        int rows = window.grid().height();
        int width = columns * cellWidth;
        int height = rows * cellHeight;
        int x = along(window.col(), columns, window.hostCols(), cellWidth, width, areaWidth);
        int y = along(window.row(), rows, window.hostRows(), cellHeight, height, areaHeight);
        return new Box(x, y, width, height);
    }

    /**
     * One axis: {@code start} cells in, unless the float reaches the window's last cell (then at
     * the far edge) or sticks out (then moved back in); never before the near edge, and one that
     * begins at the near edge stays on it.
     */
    private static int along(int start, int cells, int hostCells, int cell, int size, int area) {
        if (start <= 0) {
            return 0;
        }
        int at = start * cell;
        boolean reachesEnd = hostCells > 0 && start + cells >= hostCells;
        if (reachesEnd || at + size > area) {
            at = area - size;
        }
        return Math.max(0, at);
    }
}
