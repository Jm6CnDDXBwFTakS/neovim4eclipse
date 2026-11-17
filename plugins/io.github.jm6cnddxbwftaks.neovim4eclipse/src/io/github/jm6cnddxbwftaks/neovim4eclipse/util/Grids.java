// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Neovim's screen grids as {@link GridEvent}s build them, and the snapshots of the windows Eclipse
 * draws. Used on the RPC reader thread only (where the events arrive); what leaves it is immutable
 * ({@link GridSnapshot}, {@link FloatWindow}).
 *
 * <h2>Which grids</h2>
 * Each Neovim window has a grid. Eclipse draws the command-line window's ({@link #draw}) and, when
 * asked to ({@link #drawFloats}), those of the floating windows without the focus
 * ({@link #floatsIfChanged}); the editors' windows are shown by the editors themselves. Every
 * grid's size is kept, and the cells of the grids that may be drawn ({@link #wantsLines}): Neovim
 * says which window a grid shows ({@code win_pos}, {@code win_float_pos}) at the end of a redraw,
 * after that grid's lines, so a new grid's lines are kept until its window is known; then those of
 * an editor's window are left out.
 *
 * <h2>Floating windows</h2>
 * A floating window is drawn while it does not have the focus: Neovim's cursor is on another
 * grid. A float's place is counted from the first row and column of the editor's window
 * ({@link FloatWindow}), the one window of the layout that is on screen. Example: a plug-in's
 * panel of key hints (which-key) is two floats, neither entered; a picker's prompt is a float
 * with the cursor in it, and is not drawn.
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

    private static final Logger LOG = Logger.getLogger(Grids.class.getName());

    /**
     * The most floating windows drawn at once. A plug-in's panel of key hints is two; a stack of
     * notifications, a handful. Each drawn window is a widget of its own in the editor.
     */
    public static final int MAX_FLOATS = 16;

    /**
     * The most cells drawn in floating windows, all of them together. Every changed redraw copies a
     * drawn window's cells for the UI thread, so this bounds that work and its memory, however the
     * cells are split among windows. For scale: a panel of key hints is about 2,000 cells (80 by
     * 26), a window covering an editor on a 4K screen without scaling about 46,000 (420 by 110), so
     * the limit is some five such screens: room for a dimming backdrop behind a large window. A
     * window can be far larger than the screen: Neovim does not shrink one a plug-in asks for.
     */
    public static final int MAX_FLOAT_CELLS = 250_000;

    private final Map<Integer, GridCells> grids = new HashMap<>();
    private final Map<Integer, Integer> windowOfGrid = new HashMap<>();
    /** Where each grid's window is on Neovim's screen, as last said; a float's and a layout window's alike. */
    private final Map<Integer, GridEvent.WinPos> placeOfGrid = new HashMap<>();
    private final Map<Integer, GridEvent.HlAttr> attrs = new HashMap<>();
    private GridEvent.DefaultColors defaultColors = new GridEvent.DefaultColors(GridEvent.NO_COLOR, GridEvent.NO_COLOR);
    private int cursorGrid = -1;
    private int cursorRow;
    private int cursorCol;
    private int drawnWindow = -1;
    private boolean changed;
    private GridSnapshot last;
    private boolean drawFloats;
    private boolean floatsChanged;
    private List<FloatWindow> lastFloats = List.of();
    /** Whether a float left out for the limits was logged: once is enough to explain a missing window. */
    private boolean limitLogged;

    /**
     * Draws window {@code win} from now on: {@link #snapshotIfChanged} gives its grid. Example: the
     * command-line window's ID when it opens.
     */
    public void draw(int win) {
        drawnWindow = win;
        changed = true;
        last = null;
        floatsChanged = true; // the drawn window is neither a float nor the window the floats lie over
    }

    /** Draws no window: the command-line window closed. */
    public void stopDrawing() {
        drawnWindow = -1;
        changed = false;
        last = null;
        floatsChanged = true;
    }

    /**
     * Whether {@code grid}'s lines are worth reading: those of the drawn window's grid, and of a
     * grid whose window is not known yet (it may turn out to be the drawn one). Not those of a grid
     * known to show another window, such as an editor's: its cells are never drawn, and stay as
     * they were. Example: the editor's window 1000 on grid 2, while nothing is drawn: no.
     */
    public boolean wantsLines(int grid) {
        Integer win = windowOfGrid.get(grid);
        return win == null || (drawnWindow != -1 && win == drawnWindow) || (drawFloats && isFloat(grid));
    }

    /**
     * Whether floating windows are drawn ({@link #floatsIfChanged}). Off, their lines are not even
     * read. Example: off when the user's config turns them off, or while Neovim's screen is not the
     * editor's size, so that a float's place would mean nothing.
     */
    public void drawFloats(boolean on) {
        if (on != drawFloats) {
            drawFloats = on;
            floatsChanged = true;
        }
    }

    /** The window drawn, -1 for none. */
    public int drawnWindow() {
        return drawnWindow;
    }

    /** Applies one event; a {@link GridEvent.Flush} only ends a redraw, see {@link #snapshotIfChanged}. */
    public void apply(GridEvent event) {
        switch (event) {
            case GridEvent.Resize e -> {
                GridCells grid = grids.get(e.grid());
                if (grid == null) {
                    grids.put(e.grid(), new GridCells(e.width(), e.height()));
                } else {
                    grid.resize(e.width(), e.height());
                }
                touched(e.grid());
            }
            case GridEvent.Clear e -> {
                withGrid(e.grid(), GridCells::clear);
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
                floatsChanged |= cursorGrid != e.grid(); // the focus moved: into a float, out of one, to another window
                cursorGrid = e.grid();
                cursorRow = e.row();
                cursorCol = e.col();
                changed |= wasDrawn || isDrawn(cursorGrid);
            }
            case GridEvent.Destroy e -> {
                grids.remove(e.grid());
                windowOfGrid.remove(e.grid());
                floatsChanged |= placeOfGrid.remove(e.grid()) != null;
            }
            case GridEvent.WinPos e -> {
                windowOfGrid.put(e.grid(), e.win());
                placeOfGrid.put(e.grid(), e);
                floatsChanged = true; // a float moved, or the window the floats are counted from
                touched(e.grid());
            }
            case GridEvent.WinGone e -> {
                touched(e.grid());
                windowOfGrid.remove(e.grid());
                floatsChanged |= placeOfGrid.remove(e.grid()) != null;
            }
            case GridEvent.HlAttr e -> {
                attrs.put(e.id(), e);
                changed |= drawnWindow != -1;
                floatsChanged |= !lastFloats.isEmpty();
            }
            case GridEvent.DefaultColors e -> {
                defaultColors = e;
                changed |= drawnWindow != -1;
                floatsChanged |= !lastFloats.isEmpty();
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
        GridCells grid = gridId == null ? null : grids.get(gridId);
        if (grid == null) {
            return Optional.empty(); // its win_pos has not come yet: stays changed until it does
        }
        changed = false;
        GridSnapshot snapshot = snapshot(drawnWindow, gridId, grid, true);
        if (snapshot.equals(last)) {
            return Optional.empty();
        }
        last = snapshot;
        return Optional.of(snapshot);
    }

    /**
     * The floating windows to draw, lowest in the stack first, if they differ from the last answer;
     * empty otherwise. An answer with no window in it says the last one closed. Called at each
     * {@code flush}. Left out: a float with the focus (the cursor is on its grid), the drawn
     * command-line window, and everything while {@link #drawFloats} is off.
     *
     * <p>And what goes past the limits ({@link #MAX_FLOATS}, {@link #MAX_FLOAT_CELLS}): the
     * windows are taken highest in the stack first, each one if it still fits; one that does not
     * is left out, undrawn as every float was before they were drawn at all, and the lower ones
     * are still tried. Example: three floats of 150,000, 125,000 and 75,000 cells, the first on
     * top: the first and the third are drawn (225,000 cells), the second is not. A single float of
     * more than {@link #MAX_FLOAT_CELLS} cells is never drawn. Its cells are counted before they
     * are copied, so a window of any size costs nothing here.
     */
    public Optional<List<FloatWindow>> floatsIfChanged() {
        if (!floatsChanged) {
            return Optional.empty();
        }
        floatsChanged = false;
        List<FloatWindow> floats = new ArrayList<>();
        if (drawFloats) {
            GridEvent.WinPos host = host();
            List<Map.Entry<Integer, GridEvent.WinPos>> candidates = new ArrayList<>();
            for (Map.Entry<Integer, GridEvent.WinPos> e : placeOfGrid.entrySet()) {
                GridEvent.WinPos place = e.getValue();
                if (place.floating() && grids.containsKey(e.getKey()) && e.getKey() != cursorGrid
                        && place.win() != drawnWindow) {
                    candidates.add(e);
                }
            }
            // Highest in the stack first: when the limits leave some out, the ones on top stay.
            candidates.sort(Comparator.<Map.Entry<Integer, GridEvent.WinPos>>comparingInt(e -> e.getValue().zindex())
                    .thenComparingInt(e -> e.getValue().win()).reversed());
            long cells = 0;
            for (Map.Entry<Integer, GridEvent.WinPos> e : candidates) {
                GridEvent.WinPos place = e.getValue();
                GridCells grid = grids.get(e.getKey());
                long size = (long) grid.width * grid.height; // as long: two large ints would wrap
                if (floats.size() >= MAX_FLOATS || cells + size > MAX_FLOAT_CELLS) {
                    if (!limitLogged) {
                        limitLogged = true;
                        LOG.info(() -> "a floating window of Neovim's is not drawn: more than " + MAX_FLOATS
                                + " windows or " + MAX_FLOAT_CELLS + " cells at once (window " + place.win() + ", "
                                + grid.width + "x" + grid.height + "); said once per session");
                    }
                    continue;
                }
                cells += size;
                floats.add(new FloatWindow(snapshot(place.win(), e.getKey(), grid, false),
                        place.row() - (host == null ? 0 : host.row()), place.col() - (host == null ? 0 : host.col()),
                        host == null ? 0 : host.height(), host == null ? 0 : host.width(), place.zindex()));
            }
            floats.sort(Comparator.comparingInt(FloatWindow::zindex).thenComparingInt(f -> f.grid().window()));
        }
        if (floats.equals(lastFloats)) {
            return Optional.empty();
        }
        lastFloats = List.copyOf(floats);
        return Optional.of(lastFloats);
    }

    /** {@code grid} as it stands, with the highlights it uses; the cursor only when asked for and on it. */
    private GridSnapshot snapshot(int win, int gridId, GridCells grid, boolean withCursor) {
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
        boolean cursorHere = withCursor && gridId == cursorGrid;
        return new GridSnapshot(win, grid.width, grid.height, new ArrayList<>(Arrays.asList(grid.text)), grid.hl,
                usedAttrs, defaultColors, cursorHere ? cursorRow : -1, cursorHere ? cursorCol : -1);
    }

    /** The drawn window's grid number, or -1 while it is not known (nothing drawn, no win_pos yet). */
    public int drawnGrid() {
        Integer grid = drawnWindow == -1 ? null : gridOf(drawnWindow);
        return grid == null ? -1 : grid;
    }

    private Integer gridOf(int win) {
        for (Map.Entry<Integer, Integer> e : windowOfGrid.entrySet()) {
            if (e.getValue() == win) {
                return e.getKey();
            }
        }
        return null;
    }

    /**
     * The window the floats lie over, the editor's: the one window of the layout that is on
     * screen. Each editor's window is alone in its tab page, and a window of another tab page is
     * hidden ({@code win_hide}), so there is one; the command-line window, open below it, does
     * not count. Should there be more, the one the cursor is in, else the largest. {@code null}
     * before Neovim has said where any window is; the floats are then counted from the screen's
     * first cell, until it does.
     * Not asked of the cursor alone: Neovim says where the cursor is only when that changes, which
     * can be long after this session began to listen.
     */
    private GridEvent.WinPos host() {
        GridEvent.WinPos best = null;
        for (Map.Entry<Integer, GridEvent.WinPos> e : placeOfGrid.entrySet()) {
            GridEvent.WinPos place = e.getValue();
            if (place.floating() || place.win() == drawnWindow) {
                continue; // not the command-line window either: it lies below the editor's, and is drawn apart
            }
            if (e.getKey() == cursorGrid) {
                return place;
            }
            if (best == null || place.width() * place.height() > best.width() * best.height()) {
                best = place;
            }
        }
        return best;
    }

    private boolean isFloat(int grid) {
        GridEvent.WinPos place = placeOfGrid.get(grid);
        return place != null && place.floating();
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
        boolean unknown = !windowOfGrid.containsKey(grid);
        if (drawnWindow != -1 && (isDrawn(grid) || unknown)) {
            changed = true;
        }
        if (drawFloats && (isFloat(grid) || unknown)) {
            floatsChanged = true; // a new float's lines come before its win_float_pos
        }
    }

    private void withGrid(int id, java.util.function.Consumer<GridCells> action) {
        GridCells grid = grids.get(id);
        if (grid != null) {
            action.accept(grid);
        }
    }
}
