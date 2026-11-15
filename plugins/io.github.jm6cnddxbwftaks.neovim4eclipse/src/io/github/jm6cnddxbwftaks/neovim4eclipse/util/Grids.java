// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Neovim's screen grids as {@link GridEvent}s build them, and the snapshot of the one window
 * Eclipse draws. Used on the RPC reader thread only (where the events arrive); what leaves it is a
 * {@link GridSnapshot}, immutable.
 *
 * <h2>Which grids</h2>
 * Each Neovim window has a grid. Eclipse draws only one window's, the command-line window's
 * ({@link #draw}); the editors' windows are shown by the editors themselves. Every grid's size is
 * kept, and the cells of the grids that may be drawn ({@link #wantsLines}): Neovim says which
 * window a grid shows ({@code win_pos}) at the end of a redraw, after that grid's lines, so a new
 * grid's lines are kept until its window is known; then those of an editor's window are left out.
 *
 * <h2>Example</h2>
 * <pre>
 *   grids.draw(1004);                          // the command-line window opened, window 1004
 *   grids.apply(new Resize(4, 120, 7));        // its grid, 4
 *   grids.apply(new Line(4, 0, 0, cells));     // ":w" and blanks
 *   grids.apply(new WinPos(4, 1004, false));   // grid 4 shows window 1004
 *   grids.apply(new Flush());
 *   grids.snapshotIfChanged();                 // the 120x7 grid with ":w" on row 0
 * </pre>
 */
public final class Grids {

    /** One grid: its size and cells. */
    private static final class Grid {
        int width;
        int height;
        String[] text;
        int[] hl;

        Grid(int width, int height) {
            resize(width, height);
        }

        void resize(int newWidth, int newHeight) {
            String[] newText = new String[Math.max(0, newWidth) * Math.max(0, newHeight)];
            int[] newHl = new int[newText.length];
            Arrays.fill(newText, " ");
            for (int r = 0; r < Math.min(height, newHeight); r++) { // what fits stays, as Neovim expects
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

        void clear() {
            Arrays.fill(text, " ");
            Arrays.fill(hl, 0);
        }

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

        /** {@link GridEvent.Scroll}: rows move up by {@code rows} inside the region. */
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

    private final Map<Integer, Grid> grids = new HashMap<>();
    private final Map<Integer, Integer> windowOfGrid = new HashMap<>();
    private final Map<Integer, GridEvent.HlAttr> attrs = new HashMap<>();
    private GridEvent.DefaultColors defaultColors = new GridEvent.DefaultColors(GridEvent.NO_COLOR, GridEvent.NO_COLOR);
    private int cursorGrid = -1;
    private int cursorRow;
    private int cursorCol;
    private int drawnWindow = -1;
    private boolean changed;
    private GridSnapshot last;

    /**
     * Draws window {@code win} from now on: {@link #snapshotIfChanged} gives its grid. Example: the
     * command-line window's ID when it opens.
     */
    public void draw(int win) {
        drawnWindow = win;
        changed = true;
        last = null;
    }

    /** Draws no window: the command-line window closed. */
    public void stopDrawing() {
        drawnWindow = -1;
        changed = false;
        last = null;
    }

    /**
     * Whether {@code grid}'s lines are worth reading: those of the drawn window's grid, and of a
     * grid whose window is not known yet (it may turn out to be the drawn one). Not those of a grid
     * known to show another window, such as an editor's: its cells are never drawn, and stay as
     * they were. Example: the editor's window 1000 on grid 2, while nothing is drawn: no.
     */
    public boolean wantsLines(int grid) {
        Integer win = windowOfGrid.get(grid);
        return win == null || (drawnWindow != -1 && win == drawnWindow);
    }

    /** The window drawn, -1 for none. */
    public int drawnWindow() {
        return drawnWindow;
    }

    /** Applies one event; a {@link GridEvent.Flush} only ends a redraw, see {@link #snapshotIfChanged}. */
    public void apply(GridEvent event) {
        switch (event) {
            case GridEvent.Resize e -> {
                Grid grid = grids.get(e.grid());
                if (grid == null) {
                    grids.put(e.grid(), new Grid(e.width(), e.height()));
                } else {
                    grid.resize(e.width(), e.height());
                }
                touched(e.grid());
            }
            case GridEvent.Clear e -> {
                withGrid(e.grid(), Grid::clear);
                touched(e.grid());
            }
            case GridEvent.Line e -> {
                withGrid(e.grid(), g -> g.line(e.row(), e.colStart(), e.cells()));
                touched(e.grid());
            }
            case GridEvent.Scroll e -> {
                withGrid(e.grid(), g -> g.scroll(e.top(), e.bot(), e.left(), e.right(), e.rows()));
                touched(e.grid());
            }
            case GridEvent.CursorGoto e -> {
                boolean wasDrawn = isDrawn(cursorGrid);
                cursorGrid = e.grid();
                cursorRow = e.row();
                cursorCol = e.col();
                changed |= wasDrawn || isDrawn(cursorGrid);
            }
            case GridEvent.Destroy e -> {
                grids.remove(e.grid());
                windowOfGrid.remove(e.grid());
            }
            case GridEvent.WinPos e -> {
                windowOfGrid.put(e.grid(), e.win());
                touched(e.grid());
            }
            case GridEvent.WinGone e -> {
                touched(e.grid());
                windowOfGrid.remove(e.grid());
            }
            case GridEvent.HlAttr e -> {
                attrs.put(e.id(), e);
                changed |= drawnWindow != -1;
            }
            case GridEvent.DefaultColors e -> {
                defaultColors = e;
                changed |= drawnWindow != -1;
            }
            case GridEvent.Flush e -> {
                // the caller asks for the snapshot
            }
        }
    }

    /**
     * The drawn window's grid, if it differs from the last snapshot and its grid is known; empty
     * otherwise. Called at each {@code flush}, and right after {@link #draw}. A grid whose window
     * is not known yet counts as possibly the drawn one, so a new editor window's lines can make a
     * snapshot that turns out the same as the last: it is left out too.
     */
    public Optional<GridSnapshot> snapshotIfChanged() {
        if (!changed || drawnWindow == -1) {
            return Optional.empty();
        }
        Integer gridId = gridOf(drawnWindow);
        Grid grid = gridId == null ? null : grids.get(gridId);
        if (grid == null) {
            return Optional.empty(); // its win_pos has not come yet: stays changed until it does
        }
        changed = false;
        Set<Integer> used = new HashSet<>();
        for (int id : grid.hl) {
            used.add(id);
        }
        Map<Integer, GridEvent.HlAttr> usedAttrs = new HashMap<>();
        for (int id : used) {
            GridEvent.HlAttr attr = attrs.get(id);
            if (attr != null) {
                usedAttrs.put(id, attr);
            }
        }
        boolean cursorHere = gridId == cursorGrid;
        GridSnapshot snapshot = new GridSnapshot(drawnWindow, grid.width, grid.height, new ArrayList<>(Arrays.asList(grid.text)),
                grid.hl, usedAttrs, defaultColors, cursorHere ? cursorRow : -1, cursorHere ? cursorCol : -1);
        if (snapshot.equals(last)) {
            return Optional.empty();
        }
        last = snapshot;
        return Optional.of(snapshot);
    }

    private Integer gridOf(int win) {
        for (Map.Entry<Integer, Integer> e : windowOfGrid.entrySet()) {
            if (e.getValue() == win) {
                return e.getKey();
            }
        }
        return null;
    }

    private boolean isDrawn(int grid) {
        Integer win = windowOfGrid.get(grid);
        return drawnWindow != -1 && win != null && win == drawnWindow;
    }

    /**
     * Marks the snapshot as changed when {@code grid} may be the drawn window's: its own, or one
     * whose window is not known yet (a new grid's lines come before its {@code win_pos}).
     */
    private void touched(int grid) {
        if (drawnWindow != -1 && (isDrawn(grid) || !windowOfGrid.containsKey(grid))) {
            changed = true;
        }
    }

    private void withGrid(int id, java.util.function.Consumer<Grid> action) {
        Grid grid = grids.get(id);
        if (grid != null) {
            action.accept(grid);
        }
    }
}
