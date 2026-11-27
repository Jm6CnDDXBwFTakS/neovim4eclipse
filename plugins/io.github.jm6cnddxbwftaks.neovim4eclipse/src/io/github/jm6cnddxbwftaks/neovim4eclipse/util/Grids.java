// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Neovim's screen grids as {@link GridEvent}s build them, and the snapshots of the windows Eclipse
 * draws. Used on the RPC reader thread only (where the events arrive); what leaves it is immutable
 * ({@link GridSnapshot}, {@link FloatWindow}).
 *
 * <h2>Which grids</h2>
 * Each Neovim window has a grid. Eclipse draws the command-line window's ({@link #draw}), the pages'
 * windows ({@link #drawPage}, {@link #pagesIfChanged}: a page editor shown from this Neovim) and,
 * when asked to ({@link #drawFloats}), those of the floating windows ({@link #floatsIfChanged}); the
 * text editors' windows are shown by the editors themselves. Every
 * grid's size is kept, and the cells of the grids that may be drawn ({@link #wantsLines}): Neovim
 * says which window a grid shows ({@code win_pos}, {@code win_float_pos}) at the end of a redraw,
 * after that grid's lines, so a new grid's lines are kept until its window is known; then those of
 * an editor's window are left out.
 *
 * <h2>Floating windows</h2>
 * A floating window is drawn with or without the focus; the one with Neovim's cursor on its grid
 * is drawn with the cursor. A float's place is counted from the first row and column of the
 * editor's window ({@link FloatWindow}), the one window of the layout that is on screen. Example:
 * a plug-in's panel of key hints (which-key) is two floats, neither entered, drawn without a
 * cursor; a picker's prompt has the cursor in it, and is drawn with it, so that the user sees
 * where the letters typed go.
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

    /**
     * What goes past the limits, in words built from them, the one wording for the log and for the
     * message that closes a float with the cursor past them ({@code LuaScripts.CLOSE_UNDRAWN_FLOAT}):
     * "more than {@value #MAX_FLOATS} floating windows or {@value #MAX_FLOAT_CELLS} cells at once"
     * (the cells grouped by thousands). Not a compile-time constant, so a class using it reads it
     * here, never a copy javac made.
     */
    public static final String PAST_LIMITS = "more than " + MAX_FLOATS + " floating windows or "
            + String.format(Locale.ROOT, "%,d", MAX_FLOAT_CELLS) + " cells at once";

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
    /**
     * The windows of Neovim's ui2 ({@code LuaScripts.UI2}): its command line, messages, pager and
     * prompts. Drawn whether {@link #drawFloats} is on or not, and with the cursor in them. Reader
     * thread.
     */
    private Set<Integer> ui2Windows = Set.of();
    /** ui2's command line window, or -1. */
    private int cmdWindow = -1;
    /** ui2's message window, or -1: with {@link #overlay}, the one ui2 window not lifted. */
    private int msgWindow = -1;
    /**
     * The user's {@code 'cmdheight'} is 0 ({@code LuaScripts.UI2}): the editor keeps no row for the
     * command line, so ui2's line is drawn only while it is open, over the status line.
     */
    private boolean overlay;
    /** Whether Eclipse types the insert now ({@link #eclipseTypes}): Neovim's popup menu is then not drawn. */
    private boolean eclipseTypes;
    private boolean floatsChanged;
    private List<FloatWindow> lastFloats = List.of();
    /** Whether a float left out for the limits was logged: once is enough to explain a missing window. */
    private boolean limitLogged;
    /**
     * The pages' windows drawn ({@link #drawPage}). Changed from any thread (a page editor opening
     * or closing), read on the reader thread, so a concurrent set; everything else about the pages
     * is the reader thread's.
     */
    private final Set<Integer> pages = ConcurrentHashMap.newKeySet();
    /** The pages whose grid may have changed since their last snapshot; added to from any thread too. */
    private final Set<Integer> pagesChanged = ConcurrentHashMap.newKeySet();
    /** Each page's last snapshot, so that one that comes out the same is not sent again. Reader thread. */
    private final Map<Integer, GridSnapshot> lastPage = new HashMap<>();
    /** Each page's last status line row ({@link #pageStatusIfChanged}). Reader thread. */
    private final Map<Integer, GridSnapshot> lastPageStatus = new HashMap<>();

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
     * Draws page window {@code win} from now on: {@link #pagesIfChanged} gives its grid whenever it
     * changes. A page in a tabpage that is not current gets no redraws from Neovim (its grid is kept,
     * as it was last drawn), so its snapshot changes only while the page has the focus. Any thread.
     * Example: the messages page's window 1004 when its editor opens.
     */
    public void drawPage(int win) {
        pages.add(win);
        pagesChanged.add(win); // its grid may be there already: the next flush sends it
    }

    /** Draws page window {@code win} no more (its editor closed). Any thread. */
    public void stopDrawingPage(int win) {
        pages.remove(win);
        pagesChanged.remove(win);
    }

    /**
     * Whether {@code grid}'s lines are worth reading: those of the drawn window's grid, and of a
     * grid whose window is not known yet (it may turn out to be the drawn one). Not those of a grid
     * known to show another window, such as an editor's: its cells are never drawn, and stay as
     * they were. Example: the editor's window 1000 on grid 2, while nothing is drawn: no.
     */
    public boolean wantsLines(int grid) {
        Integer win = windowOfGrid.get(grid);
        return win == null || (drawnWindow != -1 && win == drawnWindow) || pages.contains(win)
                || ((drawFloats || alwaysDrawn(grid)) && isFloat(grid));
    }

    /**
     * Whether floating windows are drawn ({@link #floatsIfChanged}). Off, their lines are not even
     * read. Example: off until the session's start is done ({@code NeovimSession.floatingWindowsDrawn}),
     * then on for good.
     */
    public void drawFloats(boolean on) {
        if (on != drawFloats) {
            drawFloats = on;
            floatsChanged = true;
        }
    }

    /**
     * The windows of ui2, from {@code LuaScripts.UI2} (at start, and whenever ui2 makes one
     * again). They are Neovim's own command line and messages, so the editor draws them as it
     * draws Neovim's popup menu: always ({@link #alwaysDrawn}), except that with {@code overlay}
     * the command line's window {@code cmd} is drawn only while the cursor is in it, a line being
     * typed: the editor has no row for it then, and between lines it would cover the status line
     * with the last line typed. It is drawn too while ui2 has expanded it for output of more than one
     * line ({@code :ls}), until the next key: {@code LuaScripts.UI2} sends such output there with the
     * overlay. Example: {@code [1002, 1003, 1004, 1005]}, 1002, 1003, {@code false}.
     *
     * <p>With {@code overlay} the line's row, Neovim's screen's last, is not on the editor: the line
     * is drawn a row higher, on the status line's row, and so is what Neovim stacks on the line
     * (ui2's prompt and pager windows, which end on the status line's row, and the popup menu of a
     * line being typed), so that none of them overlaps it. With {@code 'laststatus'} 3 ui2 already
     * ends the pager above the global status line, so it then leaves one text row showing under
     * it: a cosmetic difference, not an overlap. ui2's message window ({@code msg}) ends
     * above the status line and stays. Example: {@code :e } then Tab with {@code 'cmdheight'} 0: the
     * list of names ends right above the line, as in Neovim.
     */
    public void ui2Windows(List<Integer> wins, int cmd, int msg, boolean overlay) {
        Set<Integer> now = Set.copyOf(wins);
        if (!now.equals(ui2Windows) || cmd != cmdWindow || msg != msgWindow || overlay != this.overlay) {
            ui2Windows = now;
            cmdWindow = cmd;
            msgWindow = msg;
            this.overlay = overlay;
            floatsChanged = true;
        }
    }

    /**
     * Whether Eclipse types the current insert, not Neovim (the session says so before each
     * redraw). Neovim's popup menu is then left out: Neovim's cursor is not where the user types
     * (Eclipse hands the typed text over at the next sync point), so a list a config opened as text
     * arrives would hang in the wrong place. A list the user asked for ({@code Ctrl-N}) is typed by
     * Neovim, and drawn. Example: {@code vim.fn.complete()} from an autocmd while Eclipse types.
     */
    public void eclipseTypes(boolean on) {
        if (on != eclipseTypes) {
            eclipseTypes = on;
            floatsChanged = true;
        }
    }

    /** The window drawn, -1 for none. */
    public int drawnWindow() {
        return drawnWindow;
    }

    /**
     * Whether the events applied so far end in the middle of a redraw (no {@code flush} yet): the
     * grids are then half drawn, and nothing should be sent until the flush.
     */
    private boolean midRedraw;

    /**
     * Whether the last event applied ended a redraw ({@code flush}), or none came yet: the grids are
     * whole, so what changed outside a redraw ({@link #ui2Windows}) can be sent at once. Example: ui2
     * makes its line's window anew in a redraw, and the plug-in names it after that redraw's flush.
     */
    public boolean betweenRedraws() {
        return !midRedraw;
    }

    /** Applies one event; a {@link GridEvent.Flush} only ends a redraw, see {@link #snapshotIfChanged}. */
    public void apply(GridEvent event) {
        midRedraw = !(event instanceof GridEvent.Flush);
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
                pageTouched(cursorGrid); // the cursor left that page, or moved in it
                pageTouched(e.grid());
                boolean wasDrawn = isDrawn(cursorGrid);
                floatsChanged |= cursorGrid != e.grid(); // the focus moved: into a float, out of one, to another window
                // The cursor moved in a window drawn with it: ui2's line or pager, or a float that has
                // the focus (a picker's prompt).
                floatsChanged |= alwaysDrawn(e.grid()) || (drawFloats && isFloat(e.grid()));
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
                // A hidden float keeps the cursor on its grid, its window no longer known here:
                // unseenCursor names that grid then.
                touched(e.grid());
                windowOfGrid.remove(e.grid());
                floatsChanged |= placeOfGrid.remove(e.grid()) != null;
            }
            case GridEvent.HlAttr e -> {
                attrs.put(e.id(), e);
                pagesChanged.addAll(pages);
                changed |= drawnWindow != -1;
                floatsChanged |= !lastFloats.isEmpty();
            }
            case GridEvent.DefaultColors e -> {
                defaultColors = e;
                pagesChanged.addAll(pages);
                changed |= drawnWindow != -1;
                floatsChanged |= !lastFloats.isEmpty();
            }
            case GridEvent.Flush e -> {
                // the caller asks for the snapshot
            }
            case GridEvent.Viewport e -> {
                // the session's (a search's preview), not the grids'
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
        if (grid == null || !grid.held) {
            return Optional.empty(); // no win_pos yet, or too large to hold: stays changed until that changes
        }
        changed = false;
        GridSnapshot snapshot = snapshot(drawnWindow, gridId, grid);
        if (snapshot.equals(last)) {
            return Optional.empty();
        }
        last = snapshot;
        return Optional.of(snapshot);
    }

    /**
     * The pages' grids that changed since their last snapshot, one snapshot each, with the cursor
     * where it is in that grid (none in a page without the focus); empty when none did. Called at
     * each {@code flush}. A page whose grid is not known yet (no {@code win_pos} for its window) is
     * left for a later flush. Example: the messages page has the focus and scrolls: its snapshot.
     */
    public List<GridSnapshot> pagesIfChanged() {
        lastPage.keySet().retainAll(pages); // a page no longer drawn keeps no snapshot
        if (pagesChanged.isEmpty()) {
            return List.of();
        }
        List<GridSnapshot> changedPages = new ArrayList<>();
        for (Integer win : List.copyOf(pagesChanged)) {
            Integer gridId = pages.contains(win) ? gridOf(win) : null;
            GridCells grid = gridId == null ? null : grids.get(gridId);
            if (!pages.contains(win)) {
                pagesChanged.remove(win);
                lastPage.remove(win);
                continue;
            }
            if (grid == null || !grid.held) {
                continue; // no win_pos yet, or too large to hold: stays changed until that changes
            }
            pagesChanged.remove(win);
            GridSnapshot snapshot = snapshot(win, gridId, grid);
            if (!snapshot.equals(lastPage.get(win))) {
                lastPage.put(win, snapshot);
                changedPages.add(snapshot);
            }
        }
        return changedPages;
    }

    /**
     * The status line row of each page on screen, if it changed: the row of the screen (grid 1)
     * right below the page's window, where Neovim draws the window's status line, with its
     * colors, as one-row snapshots whose {@link GridSnapshot#window()} is the page's. Called at
     * each {@code flush}. Only a page on screen has one: Neovim draws the status lines of the
     * current tabpage only, so a page in the background keeps the one it had, as its text.
     * The screen's last row is the command line's ({@code 'cmdheight'} 1, ui2's line, which the
     * page draws as a float): when the window reaches down to it there is no status line
     * ({@code 'laststatus'} 0, or 1 with the page alone), and a snapshot of no rows
     * ({@link GridSnapshot#height()} 0) says so, once, so that the page hides a row it showed
     * (example: {@code :set laststatus=0} while the page is open; the row goes, and the page's grid
     * takes that height). Example: lualine's line below the messages page, its mode in its own
     * colors.
     */
    public List<GridSnapshot> pageStatusIfChanged() {
        lastPageStatus.keySet().retainAll(pages);
        GridCells screen = grids.get(1);
        if (screen == null || !screen.held || pages.isEmpty()) {
            return List.of();
        }
        List<GridSnapshot> changed = new ArrayList<>();
        for (Integer win : pages) {
            Integer gridId = gridOf(win);
            GridEvent.WinPos place = gridId == null ? null : placeOfGrid.get(gridId);
            if (place == null || place.floating()) {
                continue; // not on screen now: it keeps its last status line
            }
            int row = place.row() + place.height();
            GridSnapshot status = row < 0 || row >= screen.height - 1
                    ? noStatusLine(win) // the window reaches the command line's row ('laststatus' 0)
                    : rowOf(win, screen, row);
            if (!status.equals(lastPageStatus.get(win))) {
                lastPageStatus.put(win, status);
                changed.add(status);
            }
        }
        return changed;
    }

    /** Window {@code win} has no status line: a snapshot of no rows ({@link #pageStatusIfChanged}). */
    private GridSnapshot noStatusLine(int win) {
        return new GridSnapshot(win, 0, 0, List.of(), new int[0], Map.of(), defaultColors, -1, -1);
    }

    /** Row {@code row} of {@code grid}, as a one-row snapshot of window {@code win}, with the highlights it uses. */
    private GridSnapshot rowOf(int win, GridCells grid, int row) {
        int from = row * grid.width;
        List<String> text = new ArrayList<>(Arrays.asList(grid.text).subList(from, from + grid.width));
        int[] hl = Arrays.copyOfRange(grid.hl, from, from + grid.width);
        Map<Integer, GridEvent.HlAttr> usedAttrs = new HashMap<>();
        for (int id : hl) {
            GridEvent.HlAttr attr = attrs.get(id);
            if (attr != null) {
                usedAttrs.put(id, attr);
            }
        }
        return new GridSnapshot(win, grid.width, 1, text, hl, usedAttrs, defaultColors, -1, -1);
    }

    /** The grid number of page window {@code win}, for its mouse events, or -1 while it is not known. */
    public int pageGrid(int win) {
        Integer grid = pages.contains(win) ? gridOf(win) : null;
        return grid == null ? -1 : grid;
    }

    /**
     * The floating windows to draw, lowest in the stack first, if they differ from the last answer;
     * empty otherwise. An answer with no window in it says the last one closed. Called at each
     * {@code flush}. The float with the focus (the cursor is on its grid) is drawn with the
     * cursor ({@link FloatWindow#focused}). Left out: the drawn command-line window, and every
     * float while {@link #drawFloats} is off, except the windows that are always drawn
     * ({@link #alwaysDrawn}): ui2's and Neovim's popup menu, the cursor's included, with the
     * cursor. Example: Neovim's popup menu, drawn while plug-ins' floats are not yet.
     *
     * <p>And what goes past the limits ({@link #MAX_FLOATS}, {@link #MAX_FLOAT_CELLS}): the
     * windows are taken Neovim's own first ({@link #alwaysDrawn}), then the cursor's (the one the
     * keys go to), then highest in the stack,
     * each one if it still fits; one that does not is left out, undrawn as every float was before they were drawn at all, and the lower ones
     * are still tried. Example: three floats of 150,000, 125,000 and 75,000 cells, the first on
     * top: the first and the third are drawn (225,000 cells), the second is not. A single float of
     * more than {@link #MAX_FLOAT_CELLS} cells is never drawn. Its cells are counted before they
     * are copied, and one far past the limit holds none at all ({@link GridCells#MAX_HELD_CELLS}),
     * so a window of any size costs little here.
     *
     * <p>And a plug-in's float that is there only to dim the text below it ({@link #dimsOnly}):
     * every cell blank, in a highlight with {@code blend} ({@code 'winblend'}). Neovim draws the
     * text below through it, darker; the editor draws a window over the text, never through it,
     * so drawn it would hide the editor behind a block of its color. Example: the
     * backdrop lazy.nvim lays under its own window, with a colorscheme that sets {@code Normal}'s
     * background and {@code 'termguicolors'} on (LazyVim): left out, the window above it drawn over
     * the text. A blended float with text (a completion menu's {@code 'winblend'}) is drawn, without
     * the blend, and so is the float with the cursor, blank or not: the keys go to it, so it must
     * be drawn (left out, {@link #unseenCursor} would close it).
     */
    public Optional<List<FloatWindow>> floatsIfChanged() {
        if (!floatsChanged) {
            return Optional.empty();
        }
        floatsChanged = false;
        List<FloatWindow> floats = new ArrayList<>();
        // Each float's compindex, for the order below. By identity: two windows' records can be equal.
        Map<FloatWindow, Integer> compindexOf = new IdentityHashMap<>();
        GridEvent.WinPos host = host();
        int below = rowsBelow(host);
        boolean inCommandLine = cursorInUi2();
        List<Map.Entry<Integer, GridEvent.WinPos>> candidates = new ArrayList<>();
        for (Map.Entry<Integer, GridEvent.WinPos> e : placeOfGrid.entrySet()) {
            GridEvent.WinPos place = e.getValue();
            boolean always = alwaysDrawn(e.getKey());
            if (place.win() == -1 && eclipseTypes && !inCommandLine) {
                continue; // Neovim's popup menu in an insert Eclipse types: see eclipseTypes
            }
            if (overlay && cmdWindow > 0 && place.win() == cmdWindow
                    && !Integer.valueOf(cmdWindow).equals(windowOfGrid.get(cursorGrid))
                    && (!grids.containsKey(e.getKey()) || grids.get(e.getKey()).height <= 1)) {
                continue; // no row for the line, none open, no output expanding it: the status line shows (ui2Windows)
            }
            if (place.floating() && grids.containsKey(e.getKey()) && (drawFloats || always)
                    && (drawnWindow == -1 || place.win() != drawnWindow)
                    && (always || e.getKey() == cursorGrid || !dimsOnly(grids.get(e.getKey())))) {
                candidates.add(e);
            }
        }
        // Neovim's own windows first (ui2's line, the popup menu: without them no command line
        // shows), then the cursor's float, then highest in the stack: when the limits leave some
        // out, the window the keys go to stays, then the ones on top.
        candidates.sort(Comparator.<Map.Entry<Integer, GridEvent.WinPos>>comparingInt(e -> alwaysDrawn(e.getKey()) ? 1 : 0)
                .thenComparingInt(e -> e.getKey() == cursorGrid ? 1 : 0)
                .thenComparingInt(e -> e.getValue().zindex())
                .thenComparingInt(e -> e.getValue().compindex()) // the stack's order, as drawn (below)
                .thenComparingInt(e -> e.getValue().win()).reversed());
        long cells = 0;
        for (Map.Entry<Integer, GridEvent.WinPos> e : candidates) {
            GridEvent.WinPos place = e.getValue();
            GridCells grid = grids.get(e.getKey());
            long size = (long) grid.width * grid.height; // as long: two large ints would wrap
            if (floats.size() >= MAX_FLOATS || cells + size > MAX_FLOAT_CELLS) {
                if (!limitLogged) {
                    limitLogged = true;
                    LOG.info(() -> "a floating window of Neovim's is not drawn: " + PAST_LIMITS
                            + " (window " + place.win() + ", "
                            + grid.width + "x" + grid.height + "); said once per session");
                }
                continue;
            }
            cells += size;
            // ui2's windows hang from the bottom of Neovim's screen, below the editor's window (its
            // status line, the command line). The editor draws those rows too, under its text, as
            // Neovim lays out its screen (editor/StatusLineRow, WindowPane): so the host counts them,
            // and a window that reaches the screen's last row is drawn on the editor's last row,
            // the command line's (FloatPlacement).
            // With the overlay the screen's last row (the line's) is not on the editor: the host is
            // a row shorter, and the line and what Neovim stacks on it are a row higher (ui2Windows).
            boolean lifted = overlay && (place.win() == -1 ? inCommandLine
                    : ui2Windows.contains(place.win()) && place.win() != msgWindow);
            // With the cursor when it is on this grid: ui2's line being typed, a float entered.
            FloatWindow drawn = new FloatWindow(snapshot(place.win(), e.getKey(), grid),
                    place.row() - (host == null ? 0 : host.row()) - (lifted ? 1 : 0),
                    place.col() - (host == null ? 0 : host.col()),
                    host == null ? 0 : host.height() + below - (overlay && below > 0 ? 1 : 0),
                    host == null ? 0 : host.width(), place.zindex(),
                    // fromBottom: ui2's windows and the : line's list. FloatWindow.focused relies on it
                    // to tell Neovim's own windows with the cursor from a plug-in's.
                    ui2Windows.contains(place.win()) || (place.win() == -1 && inCommandLine),
                    // Clicks: a window that lets the mouse in, as its 'mouse' config says. Not the popup
                    // menu, which has no window to click in. One of ui2's only while the cursor is in it
                    // (the pager entered with g<): Neovim enters a window on a click on its grid, even a
                    // focusable = false one, and in ui2's message window the keys would edit its buffer,
                    // with nothing in the editor showing it and no click in the text getting out.
                    place.mouse() && place.win() != -1
                            && (!ui2Windows.contains(place.win()) || e.getKey() == cursorGrid) ? e.getKey() : -1);
            floats.add(drawn);
            compindexOf.put(drawn, place.compindex());
        }
        // In Neovim's drawing order: by zindex, then by compindex, which also orders windows of the
        // same zindex as Neovim draws them (GridEvent.WinPos#compindex: Telescope's content over
        // its frames); the window's number last, for windows Neovim gave no compindex.
        floats.sort(Comparator.comparingInt(FloatWindow::zindex)
                .thenComparingInt(f -> compindexOf.getOrDefault(f, -1))
                .thenComparingInt(f -> f.grid().window()));
        if (floats.equals(lastFloats)) {
            return Optional.empty();
        }
        lastFloats = List.copyOf(floats);
        return Optional.of(lastFloats);
    }

    /**
     * The window of the plug-in's float Neovim's cursor is in, or -1: the cursor's grid is a
     * float's, and not one of Neovim's own ({@link #alwaysDrawn}: ui2's, the popup menu). Whether it
     * is drawn or not ({@link #floatsIfChanged}). Example: a picker's prompt entered: its window.
     * A float its plug-in hid is not one here, as its window is forgotten with {@code win_hide}:
     * leaving the editor therefore does not move Neovim out of it ({@code NeovimSession.inPluginFloat}
     * is false), but the redraw that hid it closes it: {@link #unseenCursor} finds the cursor on its grid.
     */
    public int focusedFloat() {
        Integer win = windowOfGrid.get(cursorGrid);
        return win != null && win != -1 && isFloat(cursorGrid) && !alwaysDrawn(cursorGrid) ? win : -1;
    }

    /**
     * Where Neovim's cursor is, when the editor does not draw it: the cursor's grid and the
     * window last known on it (-1 when not known), or empty. The one check for "keys go only to
     * a window Eclipse draws", made at every redraw flush, whatever route led there. Allowed, a
     * known set: a window of Neovim's layout (no float: an editor's, shown by the editor itself,
     * another editor's in a switch, a page's, the command-line window), Neovim's own floats
     * ({@link #alwaysDrawn}) and the floats of the last {@link #floatsIfChanged}, so call this
     * after that. Anything else is unseen: a plug-in's float past the limits, and a grid whose
     * window is not known, as a float its plug-in hid ({@code win_hide} forgets its place: window
     * -1). Nothing is unseen while floats are not drawn at all ({@link #drawFloats} off, before the
     * session's start is done): one value decides the drawing and this, so a redraw never finds a
     * float left out of the drawing for that reason. The session hands
     * this to Neovim ({@code LuaScripts.CLOSE_UNDRAWN_FLOAT}), which decides from its own state
     * whether it is a plug-in's float to close; for anything else it does nothing (a new float
     * whose place comes with a later redraw: asked again then, with its window).
     * Example: Telescope's prompt too large to draw: its grid and window.
     */
    public Optional<UnseenCursor> unseenCursor() {
        if (!drawFloats || cursorGrid < 0) {
            return Optional.empty(); // before the start no plug-in's float is drawn, nor a key typed
        }
        Integer win = windowOfGrid.get(cursorGrid);
        boolean drawn = win != null && (!isFloat(cursorGrid) || alwaysDrawn(cursorGrid)
                || lastFloats.stream().anyMatch(f -> f.grid().window() == win));
        return drawn ? Optional.empty() : Optional.of(new UnseenCursor(cursorGrid, win == null ? -1 : win));
    }

    /**
     * The cursor on a grid the editor does not draw ({@link #unseenCursor}): the grid, and the
     * window last known on it, or -1. A record, so that the session asks Neovim once for each
     * (the same grid with its window now known is another).
     */
    public record UnseenCursor(int grid, int window) {
    }

    /** {@code grid} as it stands, with the highlights it uses; the cursor when it is on it. */
    private GridSnapshot snapshot(int win, int gridId, GridCells grid) {
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

    /**
     * How many rows Neovim's screen has below {@code host}, the editor's window: its status line
     * and the command line ({@code 'cmdheight'} 1). The screen is the default grid, 1. Example: an
     * editor of 30 rows, a screen of 32: 2. 0 before both are known.
     */
    private int rowsBelow(GridEvent.WinPos host) {
        GridCells screen = grids.get(1);
        return host == null || screen == null ? 0 : Math.max(0, screen.height - host.row() - host.height());
    }

    /** Whether the cursor is in one of ui2's windows: a {@code :} line being typed, or the pager entered. */
    private boolean cursorInUi2() {
        Integer win = windowOfGrid.get(cursorGrid);
        return win != null && ui2Windows.contains(win);
    }

    /**
     * Whether {@code grid} is drawn whatever {@link #drawFloats} says, and with the cursor in it:
     * one of ui2's windows ({@link #ui2Windows}), or Neovim's popup menu, the one float without a
     * window (win -1), which ui2's {@code :} line and insert-mode completion show their lists in.
     * Without them there is no command line to see, so they are drawn from the start, before
     * plug-ins' floats are.
     */
    private boolean alwaysDrawn(int grid) {
        Integer win = windowOfGrid.get(grid);
        return win != null && (ui2Windows.contains(win) || (win == -1 && isFloat(grid)));
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
        pageTouched(grid);
        boolean unknown = !windowOfGrid.containsKey(grid);
        if (drawnWindow != -1 && (isDrawn(grid) || unknown)) {
            changed = true;
        }
        if ((drawFloats && isFloat(grid)) || alwaysDrawn(grid) || unknown) {
            floatsChanged = true; // a new float's lines come before its win_float_pos
        }
    }

    /** Marks the page whose window {@code grid} shows, if any, as changed; a grid not known yet may be one. */
    private void pageTouched(int grid) {
        if (pages.isEmpty() || grid < 0) {
            return;
        }
        Integer win = windowOfGrid.get(grid);
        if (win == null) {
            pagesChanged.addAll(pages); // a new grid's lines come before its win_pos
        } else if (pages.contains(win)) {
            pagesChanged.add(win);
        }
    }

    /**
     * Whether {@code grid} only dims what lies below it: it has cells, every one blank (a space, or
     * nothing: the right half of a wide character), each in a highlight that blends
     * ({@code blend} above 0; the default highlight, 0, never does). Example: a backdrop of 120x40
     * spaces in {@code LazyBackdrop} with {@code 'winblend'} 60: yes; the same with one letter, or
     * with {@code 'winblend'} 0: no.
     */
    private boolean dimsOnly(GridCells grid) {
        if (grid.text.length == 0) {
            return false;
        }
        for (int i = 0; i < grid.text.length; i++) {
            String text = grid.text[i];
            GridEvent.HlAttr attr = attrs.get(grid.hl[i]);
            if ((text != null && !text.isBlank()) || attr == null || attr.blend() <= 0) {
                return false;
            }
        }
        return true;
    }

    private void withGrid(int id, java.util.function.Consumer<GridCells> action) {
        GridCells grid = grids.get(id);
        if (grid != null) {
            action.accept(grid);
        }
    }
}
