// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;
import java.util.function.ToIntFunction;

/**
 * One event of Neovim's UI protocol about its screen grids. The plug-in attaches with {@code
 * ext_linegrid} and {@code ext_multigrid} ({@code core/NeovimConnection}): Neovim then draws each of
 * its windows into a grid of its own, a rectangle of cells, and says where each window's grid
 * goes. Eclipse shows its documents in its own editors and ignores the grids of their windows; it
 * draws the grids of the windows that have no editor behind them, such as the command-line window
 * ({@code q:}), from these events ({@link Grids}).
 *
 * <h2>The protocol ({@code :help ui-linegrid}, {@code :help ui-multigrid})</h2>
 * <pre>
 * ["grid_resize", grid, width, height]          a grid's size; a grid seen for the first time is new
 * ["grid_clear", grid]                          every cell blank, in the default colors
 * ["grid_cursor_goto", grid, row, col]          the cursor is on this grid, at this cell
 * ["grid_line", grid, row, col_start, cells, wrap]
 *                                               cells from col_start on; each is [text, hl_id, repeat]
 * ["grid_scroll", grid, top, bot, left, right, rows, cols]
 *                                               rows top..bot-1 move up by rows (down if negative)
 * ["grid_destroy", grid]                        the grid is gone
 * ["win_pos", grid, win, start_row, start_col, width, height]
 *                                               the grid shows window win, at that place
 * ["win_float_pos", grid, win, anchor, anchor_grid, anchor_row, anchor_col, mouse_enabled, zindex,
 *                   compindex, screen_row, screen_col]
 *                                               the grid shows window win, a floating window: the
 *                                               corner it hangs by and what it hangs on, whether the
 *                                               mouse can enter it, its place in the stack, and the
 *                                               screen cell Neovim worked out for its top left
 * ["win_hide", grid] / ["win_close", grid]      the window is hidden (another tab) or closed
 * ["hl_attr_define", id, rgb_attr, cterm_attr, info]
 *                                               what highlight id means: colors and styles
 * ["default_colors_set", rgb_fg, rgb_bg, rgb_sp, cterm_fg, cterm_bg]
 * ["flush"]                                     the end of one redraw: the screen is consistent
 * </pre>
 * A cell of {@code grid_line} leaves out its {@code hl_id} when it is the previous cell's, and its
 * {@code repeat} when it is 1; a double-width character is its text followed by a cell with the
 * text {@code ""}. Example, the command-line window's first line {@code :w}, drawn from column 0
 * in highlight 0, then 118 blanks in highlight 7 (the window is 120 columns wide):
 * <pre>
 *   ["grid_line", [4, 0, 0, [[":", 0], ["w"], [" ", 7, 118]], false]]
 * </pre>
 * A batch has one entry per event name and one argument list per event:
 * {@code ["grid_line", args1, args2]}.
 */
public sealed interface GridEvent {

    /** {@code grid_resize}. */
    record Resize(int grid, int width, int height) implements GridEvent {
    }

    /** {@code grid_clear}. */
    record Clear(int grid) implements GridEvent {
    }

    /** {@code grid_cursor_goto}: 0-based row and column on {@code grid}. */
    record CursorGoto(int grid, int row, int col) implements GridEvent {
    }

    /**
     * {@code grid_line}, its cells spelled out: a repeated cell is there that many times, and each
     * cell carries its highlight id. Example: {@code [[":", 0], ["w"], [" ", 7, 2]]} gives {@code
     * [(":", 0), ("w", 0), (" ", 7), (" ", 7)]}.
     */
    record Line(int grid, int row, int colStart, List<Cell> cells) implements GridEvent {
        public Line {
            cells = List.copyOf(cells);
        }
    }

    /** One cell: its text (a character, or {@code ""} right of a double-width one) and highlight id. */
    record Cell(String text, int hl) {
    }

    /**
     * {@code grid_scroll}: in the region of rows {@code top} to {@code bot - 1} and columns {@code
     * left} to {@code right - 1}, the rows move up by {@code rows} (down when negative); the rows
     * that come free are redrawn by later {@code grid_line}s. Example: {@code top 0, bot 7, rows 1}
     * moves rows 1..6 to 0..5.
     */
    record Scroll(int grid, int top, int bot, int left, int right, int rows) implements GridEvent {
    }

    /** {@code grid_destroy}. */
    record Destroy(int grid) implements GridEvent {
    }

    /**
     * {@code win_pos} and {@code win_float_pos}: {@code grid} shows window {@code win} (its window
     * ID, as {@code win_getid()} gives it), and where on Neovim's screen its top left cell is.
     *
     * <p>A window of the layout ({@code win_pos}) also says its size. A floating window
     * ({@code win_float_pos}) does not (its grid's size is its own, border included), but says
     * where it lies in the stack of floats, and whether the mouse can enter it ({@code mouse_enabled},
     * the float's {@code mouse} option, off by default for one with {@code focusable = false}):
     * only then does a click on it go to Neovim ({@link FloatWindow#mouseGrid}); it is drawn
     * either way ({@link Grids#floatsIfChanged}). Its place is the one
     * Neovim worked out ({@code screen_row}, {@code screen_col}), not the anchor it was opened
     * with: the anchor's corner, the window or float it hangs on and the cursor are all settled in
     * those two numbers. Examples, from a screen of 80 columns:
     * <pre>
     *   ["win_pos", [2, win 1000, 0, 0, 80, 23]]
     *       -> WinPos(2, 1000, false, 0, 0, 80, 23, 0)
     *   ["win_float_pos", [5, win 1002, "NW", 1, 18.0, 0.0, false, 1000, 1, 18, 0]]   which-key's panel
     *       -> WinPos(5, 1002, true, 18, 0, 0, 0, 1000, false, 1)
     * </pre>
     *
     * @param row       the screen row of the top left cell, 0-based
     * @param col       its screen column
     * @param width     a layout window's columns; 0 for a floating window
     * @param height    a layout window's rows; 0 for a floating window
     * @param zindex    a floating window's place in the stack, the higher on top; 0 for a layout window
     * @param mouse     whether the mouse can enter it ({@code mouse_enabled}); {@code true} for a
     *                  layout window
     * @param compindex where Neovim draws a floating window among the others ({@code compindex}):
     *                  the exact order, a higher one over a lower one, also between windows of the
     *                  same {@code zindex}; -1 for a layout window, or when Neovim does not say.
     *                  Why it matters: a plug-in that draws a frame in a window of its own, at the
     *                  same {@code zindex} as its content (Telescope, through plenary), opens the
     *                  frame after the content; Neovim draws the content over it, and only
     *                  {@code compindex} tells. Ordered by the windows' numbers, the frame's blank
     *                  inside would cover the content.
     */
    record WinPos(int grid, int win, boolean floating, int row, int col, int width, int height, int zindex,
            boolean mouse, int compindex) implements GridEvent {

        /** A window whose place in Neovim's drawing order is not known (a layout window, a test's). */
        public WinPos(int grid, int win, boolean floating, int row, int col, int width, int height, int zindex,
                boolean mouse) {
            this(grid, win, floating, row, col, width, height, zindex, mouse, -1);
        }

        /** A window the mouse can enter. */
        public WinPos(int grid, int win, boolean floating, int row, int col, int width, int height, int zindex) {
            this(grid, win, floating, row, col, width, height, zindex, true);
        }

        /** A window whose place does not matter to the caller (tests of what is drawn, not where). */
        public WinPos(int grid, int win, boolean floating) {
            this(grid, win, floating, 0, 0, 0, 0, 0);
        }
    }

    /**
     * {@code win_viewport}: where the cursor of window {@code win} (on {@code grid}) is, 0-based
     * line and byte column. Read for one thing: while a search is typed with {@code 'incsearch'},
     * Neovim moves the editor's window's cursor to the match it previews, and the editor follows
     * ({@code NeovimSession}); a redraw also brings one for each of ui2's windows, which is why
     * the window counts. Example: {@code ["win_viewport", [2, win 1000, 0, 23, 41, 4, 60, 0]]}
     * gives {@code Viewport(2, 1000, 41, 4)}.
     */
    record Viewport(int grid, int win, int curline, int curcol) implements GridEvent {
    }

    /** {@code win_hide} or {@code win_close}: {@code grid} shows no window now. */
    record WinGone(int grid) implements GridEvent {
    }

    /**
     * {@code hl_attr_define}: what highlight {@code id} looks like, from its RGB attributes (the
     * plug-in attaches with {@code rgb}). Colors are {@code 0xRRGGBB}, {@link #NO_COLOR} when the
     * highlight leaves them to the default colors. {@code blend} is how much of what lies below
     * shows through, 0 to 100 (a window's {@code 'winblend'}, the popup menu's {@code 'pumblend'}):
     * the editor draws no window see-through, and uses it only to leave out a window that is
     * there only to dim the text below ({@link Grids#floatsIfChanged}). Example: lazy.nvim's
     * backdrop, every cell blank with {@code blend} 60.
     */
    record HlAttr(int id, int foreground, int background, boolean reverse, boolean bold, boolean italic,
            boolean underline, boolean strikethrough, int blend) implements GridEvent {

        /** A highlight nothing shows through ({@code blend} 0). */
        public HlAttr(int id, int foreground, int background, boolean reverse, boolean bold, boolean italic,
                boolean underline, boolean strikethrough) {
            this(id, foreground, background, reverse, bold, italic, underline, strikethrough, 0);
        }
    }

    /** {@code default_colors_set}: Neovim's default text and background colors, or {@link #NO_COLOR}. */
    record DefaultColors(int foreground, int background) implements GridEvent {
    }

    /** {@code flush}: the end of one redraw. */
    record Flush() implements GridEvent {
    }

    /** A color that is not set: the default colors apply. */
    int NO_COLOR = -1;

    /**
     * The grid events of one {@code redraw} notification, in order; other events (the command line,
     * messages) are left out, and so is any event too short to read.
     *
     * @param batches  the notification's argument list, as the RPC layer decodes it
     * @param windowId a window's ID from its handle in {@code win_pos}: the RPC layer decodes
     *                 handles to a type of {@code core/rpc}, which {@code util} does not know
     */
    static List<GridEvent> fromRedraw(List<?> batches, ToIntFunction<Object> windowId) {
        return fromRedraw(batches, windowId, grid -> true);
    }

    /**
     * Like {@link #fromRedraw(List, ToIntFunction)}, leaving out the {@code grid_line}s of the grids
     * {@code wantsLines} declines: their cells are not even spelled out. Example: the editors'
     * windows' grids, which are never drawn ({@link Grids#wantsLines}); a redraw of a full screen
     * would otherwise make thousands of {@link Cell}s per key.
     */
    static List<GridEvent> fromRedraw(List<?> batches, ToIntFunction<Object> windowId, IntPredicate wantsLines) {
        List<GridEvent> events = new ArrayList<>();
        for (Object batch : batches) {
            if (!(batch instanceof List<?> entry) || entry.isEmpty() || !(entry.get(0) instanceof String name)) {
                continue;
            }
            if ("flush".equals(name)) {
                events.add(new Flush());
                continue;
            }
            for (int i = 1; i < entry.size(); i++) {
                if (entry.get(i) instanceof List<?> args) {
                    if ("grid_line".equals(name) && !args.isEmpty() && !wantsLines.test(number(args, 0))) {
                        continue;
                    }
                    GridEvent event = event(name, args, windowId);
                    if (event != null) {
                        events.add(event);
                    }
                }
            }
        }
        return events;
    }

    private static GridEvent event(String name, List<?> a, ToIntFunction<Object> windowId) {
        return switch (name) {
            case "grid_resize" -> a.size() < 3 ? null : new Resize(number(a, 0), number(a, 1), number(a, 2));
            case "grid_clear" -> a.isEmpty() ? null : new Clear(number(a, 0));
            case "grid_cursor_goto" -> a.size() < 3 ? null : new CursorGoto(number(a, 0), number(a, 1), number(a, 2));
            case "grid_line" -> a.size() < 4 || !(a.get(3) instanceof List<?> cells) ? null
                    : new Line(number(a, 0), number(a, 1), number(a, 2), cells(cells));
            case "grid_scroll" -> a.size() < 6 ? null
                    : new Scroll(number(a, 0), number(a, 1), number(a, 2), number(a, 3), number(a, 4), number(a, 5));
            case "grid_destroy" -> a.isEmpty() ? null : new Destroy(number(a, 0));
            case "win_pos" -> a.size() < 2 ? null
                    : new WinPos(number(a, 0), windowId.applyAsInt(a.get(1)), false, numberOr(a, 2, 0), numberOr(a, 3, 0),
                            numberOr(a, 4, 0), numberOr(a, 5, 0), 0);
            // The screen cell (9, 10) is what Neovim worked out; without it (an older Neovim), the
            // anchor's row and column, right for a float hung by its top left on the screen itself.
            case "win_float_pos" -> a.size() < 2 ? null
                    : new WinPos(number(a, 0), windowId.applyAsInt(a.get(1)), true, numberOr(a, 9, numberOr(a, 4, 0)),
                            numberOr(a, 10, numberOr(a, 5, 0)), 0, 0, numberOr(a, 7, 0),
                            !(a.size() > 6 && Boolean.FALSE.equals(a.get(6))), numberOr(a, 8, -1));
            case "win_hide", "win_close" -> a.isEmpty() ? null : new WinGone(number(a, 0));
            case "hl_attr_define" -> a.size() < 2 ? null : hlAttr(number(a, 0), a.get(1));
            case "default_colors_set" -> a.size() < 2 ? null : new DefaultColors(color(a.get(0)), color(a.get(1)));
            case "win_viewport" -> a.size() < 6 ? null
                    : new Viewport(number(a, 0), windowId.applyAsInt(a.get(1)), numberOr(a, 4, 0), numberOr(a, 5, 0));
            default -> null; // mode_change, msg_set_pos ...
        };
    }

    /** The cells of a {@code grid_line}, spelled out (see {@link Line}). */
    private static List<Cell> cells(List<?> raw) {
        List<Cell> cells = new ArrayList<>(raw.size());
        int hl = 0; // the first cell always names its highlight; 0 is the default, should it not
        for (Object item : raw) {
            if (!(item instanceof List<?> cell) || cell.isEmpty() || !(cell.get(0) instanceof String text)) {
                continue;
            }
            if (cell.size() > 1 && cell.get(1) instanceof Number id) {
                hl = id.intValue();
            }
            int repeat = cell.size() > 2 && cell.get(2) instanceof Number n ? Math.max(0, n.intValue()) : 1;
            for (int r = 0; r < repeat; r++) {
                cells.add(new Cell(text, hl));
            }
        }
        return cells;
    }

    private static HlAttr hlAttr(int id, Object rgb) {
        Map<?, ?> m = rgb instanceof Map<?, ?> map ? map : Map.of();
        return new HlAttr(id, color(m.get("foreground")), color(m.get("background")), flag(m, "reverse"),
                flag(m, "bold"), flag(m, "italic"),
                flag(m, "underline") || flag(m, "undercurl") || flag(m, "underdouble") || flag(m, "underdotted")
                        || flag(m, "underdashed"),
                flag(m, "strikethrough"), m.get("blend") instanceof Number n ? Math.max(0, n.intValue()) : 0);
    }

    private static boolean flag(Map<?, ?> m, String key) {
        return Boolean.TRUE.equals(m.get(key));
    }

    /** A color as Neovim sends it: a number {@code 0xRRGGBB}, or -1 (or nothing) for none. */
    private static int color(Object value) {
        return value instanceof Number n && n.longValue() >= 0 ? (int) (n.longValue() & 0xFFFFFF) : NO_COLOR;
    }

    /**
     * The number at {@code index}, rounded down (an anchor's row may come as 18.0); {@code fallback}
     * when there is none.
     */
    private static int numberOr(List<?> args, int index, int fallback) {
        return index < args.size() && args.get(index) instanceof Number n ? (int) Math.floor(n.doubleValue()) : fallback;
    }

    private static int number(List<?> args, int index) {
        return args.get(index) instanceof Number n ? n.intValue() : 0;
    }
}
