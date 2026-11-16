// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Neovim's insert-mode completion list ({@code Ctrl-N}, {@code Ctrl-P}, the {@code Ctrl-X} modes)
 * as the editor draws it: what the list holds, how it is laid out in cells, and where it goes.
 *
 * <h2>Where the list comes from</h2>
 * The plug-in attaches with {@code ext_popupmenu}, so Neovim does not draw the list into a grid;
 * it sends the entries ({@code popupmenu_show}, {@code :help ui-popupmenu}) and leaves the drawing
 * to the UI. The plug-in lays them out here as Vim does, one row per entry:
 * <pre>
 *    alpha     f [buffer]      word, then kind and menu text if any entry has them
 *    alphabet  v [buffer]  █   the selected row in PmenuSel; a scrollbar when rows are cut
 * </pre>
 * and hands the result to the same canvas that draws Neovim's floating windows
 * ({@code editor/GridCanvas}), as a {@link GridSnapshot} with highlight ids of its own
 * ({@link #PMENU} and the others). So the list looks like Neovim's: its cells, the editor's font,
 * the colorscheme's {@code Pmenu} groups.
 *
 * <h2>Where it goes</h2>
 * Under the line of the word being completed, its text lined up with that word, as in Vim; above
 * the line when there is more room there ({@link #rows}, {@link #left}). The place is taken from
 * Eclipse's text, not from the row and column Neovim sends: folding, word wrap and fonts make
 * Eclipse's layout differ from Neovim's screen. Neovim's column only says how many cells before
 * the cursor the word starts ({@link View#cellsBack}); the editor walks that far back in its own
 * line ({@link CellWidth#charsBack}).
 *
 * <p>Pure; unit-tested in {@code CompletionMenuTest}. The session builds a {@link View} from
 * Neovim's events, {@code editor/CompletionMenuPopup} draws it.
 */
public final class CompletionMenu {

    /** Highlight ids of the list's own {@link GridSnapshot}, one per {@code Pmenu} group. */
    public static final int PMENU = 1;
    public static final int PMENU_SEL = 2;
    public static final int PMENU_KIND = 3;
    public static final int PMENU_KIND_SEL = 4;
    public static final int PMENU_EXTRA = 5;
    public static final int PMENU_EXTRA_SEL = 6;
    public static final int PMENU_SBAR = 7;
    public static final int PMENU_THUMB = 8;

    /** The groups in the order of their ids, which is the order {@code LuaScripts.COMPLETION_MENU} sends them in. */
    public static final List<String> GROUPS = List.of("Pmenu", "PmenuSel", "PmenuKind", "PmenuKindSel", "PmenuExtra",
            "PmenuExtraSel", "PmenuSbar", "PmenuThumb");

    /** No list is wider than this many cells: longer texts are cut with an ellipsis. */
    static final int MAX_COLUMNS = 80;
    /** The most combining marks drawn on one character. */
    private static final int MAX_MARKS_PER_CELL = 6;

    private CompletionMenu() {
    }

    /**
     * One entry of the list.
     *
     * @param word what the list shows for it (the item's {@code abbr} if it has one, else the word)
     * @param kind a letter or word for its kind ({@code f}, {@code v}), or empty
     * @param menu extra text shown after it ({@code [buffer]}), or empty
     */
    public record Item(String word, String kind, String menu) {
    }

    /**
     * The colors of the eight {@code Pmenu} groups, in the order of {@link #GROUPS}; a color the
     * colorscheme leaves open is {@link GridEvent#NO_COLOR}, drawn in the editor's own.
     */
    public record Colors(List<GridEvent.HlAttr> groups) {

        /** No colors known: everything in the editor's colors, the selected row reversed. */
        public static final Colors PLAIN = from(List.of());

        public Colors {
            groups = List.copyOf(groups);
        }

        /**
         * From what Neovim sent: one list per group, {@code [foreground, background, bold, italic,
         * underline, reverse]}, colors as {@code 0xRRGGBB} or -1. A group that is missing or malformed is
         * plain, the selected ones reversed, so that the selection shows whatever was sent.
         */
        public static Colors from(List<?> sent) {
            List<GridEvent.HlAttr> groups = new ArrayList<>();
            for (int i = 0; i < GROUPS.size(); i++) {
                int id = i + 1;
                boolean selected = id == PMENU_SEL || id == PMENU_KIND_SEL || id == PMENU_EXTRA_SEL || id == PMENU_THUMB;
                GridEvent.HlAttr attr = new GridEvent.HlAttr(id, GridEvent.NO_COLOR, GridEvent.NO_COLOR, selected, false,
                        false, false, false);
                if (i < sent.size() && sent.get(i) instanceof List<?> g && g.size() >= 6) {
                    attr = new GridEvent.HlAttr(id, color(g.get(0)), color(g.get(1)), Boolean.TRUE.equals(g.get(5)),
                            Boolean.TRUE.equals(g.get(2)), Boolean.TRUE.equals(g.get(3)), Boolean.TRUE.equals(g.get(4)), false);
                }
                groups.add(attr);
            }
            return new Colors(groups);
        }

        private static int color(Object value) {
            return value instanceof Number n && n.intValue() >= 0 ? n.intValue() & 0xFFFFFF : GridEvent.NO_COLOR;
        }

        Map<Integer, GridEvent.HlAttr> attrs() {
            Map<Integer, GridEvent.HlAttr> attrs = new HashMap<>();
            for (GridEvent.HlAttr attr : groups) {
                attrs.put(attr.id(), attr);
            }
            return attrs;
        }
    }

    /**
     * What Neovim says about the cursor and its options when the list shows or its selection
     * moves ({@code LuaScripts.COMPLETION_MENU}); the list's entries come separately, with the
     * redraw.
     *
     * @param line1     the cursor's line, 1-based
     * @param col0      the cursor's column, 0-based UTF-8 bytes
     * @param winCol    the cursor's cell in its window, 1-based ({@code wincol()}), number and
     *                  sign columns included, as the list's column is
     * @param pumHeight {@code 'pumheight'}: the most rows to show, 0 for as many as fit
     * @param textWidth how many cells of text a row of Neovim's window holds while it wraps long
     *                  lines, 0 while it does not; see {@link #cellsBack}
     */
    public record Anchor(int line1, int col0, int winCol, int pumHeight, Colors colors, int textWidth) {

        /** An anchor in a window that does not wrap. */
        public Anchor(int line1, int col0, int winCol, int pumHeight, Colors colors) {
            this(line1, col0, winCol, pumHeight, colors, 0);
        }

        /**
         * How many cells before the cursor the word starts, the list being at cell
         * {@code menuCol} (0-based, as {@code popupmenu_show} says it).
         *
         * <p>Usually the difference of the two cells. Example: {@code al} completed to
         * {@code alpha}, the cursor after it in cell 6 (1-based), the list at cell 0: five.
         *
         * <p><b>A word that Neovim wraps.</b> Neovim's window can wrap a line where Eclipse does
         * not (its window is narrower, or the viewport is off and it is 120 cells wide). When the
         * word then starts at the end of one screen row and the cursor is on the next, the
         * cursor's cell is the smaller one. Example: a row of 120 cells, the word starting at
         * cell 118 and five cells long: the cursor is in cell 4 of the next row, 3 - 118 is -115,
         * and one row's width more gives the five. Without {@code textWidth} (no wrap, or an
         * older report) a negative difference is 0: the list then hangs at the cursor.
         */
        public int cellsBack(int menuCol) {
            int back = winCol - 1 - menuCol;
            if (back < 0 && textWidth > 0) {
                back += textWidth;
            }
            return Math.max(0, back);
        }
    }

    /**
     * The list to draw, or {@link #HIDDEN}.
     *
     * @param selected  the selected entry, -1 for none
     * @param line1     the line of the word being completed (the cursor's), 1-based
     * @param col0      the cursor's column on it, 0-based UTF-8 bytes
     * @param cellsBack how many cells before the cursor the word starts
     * @param maxRows   the most rows to show, 0 for as many as fit
     */
    public record View(List<Item> items, int selected, int line1, int col0, int cellsBack, int maxRows, Colors colors) {

        public static final View HIDDEN = new View(List.of(), -1, 1, 0, 0, 0, Colors.PLAIN);

        public View {
            items = List.copyOf(items);
        }

        /**
         * The view of Neovim's {@code popupmenu_show} with the cursor Neovim reported for it.
         * Example: {@code al} completed to {@code alpha}, the cursor after it in cell 6, the list
         * at cell 0: the word starts five cells before the cursor.
         */
        public static View of(CmdlineEvent.MenuShow menu, Anchor anchor) {
            // Each text cut to what a row can show at most. The list only shows: Neovim inserts the
            // real text. Uncut, a whole-line completion in a file of one huge line would have the
            // editor measure megabytes at each layout.
            List<Item> items = new ArrayList<>();
            for (int i = 0; i < menu.items().size(); i++) {
                items.add(new Item(shown(menu.items().get(i)), shown(menu.kind(i)), shown(menu.menu(i))));
            }
            return new View(items, menu.selected(), anchor.line1(), anchor.col0(),
                    anchor.cellsBack(menu.col()), Math.max(0, anchor.pumHeight()), anchor.colors());
        }

        /** {@code text} if a row can show all of it, else its start with one cell more than fits, so that it is still drawn as cut. */
        private static String shown(String text) {
            return text.length() <= MAX_COLUMNS ? text : CellWidth.fit(text, MAX_COLUMNS + 1);
        }

        public boolean isShowing() {
            return !items.isEmpty();
        }

        /** This list with another entry selected ({@code popupmenu_select}); -1 for none. */
        public View select(int index) {
            return new View(items, index < items.size() ? Math.max(-1, index) : -1, line1, col0, cellsBack, maxRows, colors);
        }
    }

    /**
     * How many rows the list gets and on which side of the line.
     *
     * @param rows  at least 1
     * @param below under the line, else above it
     */
    public record Rows(int rows, boolean below) {
    }

    /**
     * Chooses the side with room for the whole list, the lower one first, else the roomier one,
     * and cuts the list to it. Example: 12 entries, room for 3 rows below and 20 above: 12 rows
     * above. With {@code maxRows} 10: 10 rows above.
     *
     * @param items     the number of entries
     * @param maxRows   {@code 'pumheight'}, 0 for no limit
     * @param roomBelow the rows that fit under the line
     * @param roomAbove the rows that fit above it
     */
    public static Rows rows(int items, int maxRows, int roomBelow, int roomAbove) {
        int wanted = Math.max(1, maxRows > 0 ? Math.min(items, maxRows) : items);
        boolean below = roomBelow >= wanted || roomBelow >= roomAbove;
        return new Rows(Math.max(1, Math.min(wanted, below ? roomBelow : roomAbove)), below);
    }

    /**
     * The first entry shown when only {@code rows} of {@code count} fit: the one shown before, moved
     * just far enough that the selected entry is in view. Example: 5 rows from entry 0, entry 7
     * selected: entries 3 to 7 show.
     *
     * @param previous the first entry shown until now
     * @param selected the selected entry, -1 for none (the view stays)
     */
    public static int firstRow(int previous, int selected, int count, int rows) {
        int first = Math.max(0, Math.min(previous, count - rows));
        if (selected >= 0 && selected < first) {
            first = selected;
        } else if (selected >= first + rows) {
            first = selected - rows + 1;
        }
        return Math.max(0, Math.min(first, count - rows));
    }

    /**
     * The left edge of a list {@code width} pixels wide whose text should line up with the word at
     * {@code wordX}: one cell further left, for the list's padding, but inside the editor.
     */
    public static int left(int wordX, int cellWidth, int width, int areaWidth) {
        return Math.max(0, Math.min(wordX - cellWidth, areaWidth - width));
    }

    /**
     * The list's cells: {@code rows} entries of {@code view} from {@code first} on, laid out as
     * the class comment shows, at most {@code maxColumns} cells wide (and {@link #MAX_COLUMNS}).
     * A scrollbar column is added when not all entries show. A color the groups leave open is
     * the editor's own: the snapshot names no default colors.
     */
    public static GridSnapshot grid(View view, int first, int rows, int maxColumns) {
        List<Item> items = view.items();
        if (items.isEmpty()) { // nothing to lay out: one blank cell, so that a caller need not check first
            items = List.of(new Item("", "", ""));
        }
        int count = items.size();
        int height = Math.max(1, Math.min(rows, count));
        int start = Math.max(0, Math.min(first, count - height));
        boolean scrollbar = height < count;

        int word = 1;
        int kind = 0;
        int menu = 0;
        for (Item item : items) { // all entries, not only the shown ones: scrolling must not change the width
            word = Math.max(word, CellWidth.of(item.word()));
            kind = Math.max(kind, CellWidth.of(item.kind()));
            menu = Math.max(menu, CellWidth.of(item.menu()));
        }
        // Too wide: the extra text gives way first, then the kind, then the word itself.
        int limit = Math.max(4, Math.min(maxColumns, MAX_COLUMNS)) - (scrollbar ? 1 : 0);
        int fixed = 2; // one cell of padding on each side
        menu = shrink(menu, limit - fixed - word - gap(kind) - 1);
        kind = shrink(kind, limit - fixed - word - gap(menu) - 1);
        word = Math.max(1, Math.min(word, limit - fixed - gap(kind) - gap(menu)));
        int width = fixed + word + gap(kind) + gap(menu) + (scrollbar ? 1 : 0);

        List<String> text = new ArrayList<>(width * height);
        int[] hl = new int[width * height];
        int thumbSize = scrollbar ? Math.max(1, height * height / count) : 0;
        int thumbStart = scrollbar ? start * (height - thumbSize) / (count - height) : 0;
        for (int row = 0; row < height; row++) {
            Item item = items.get(start + row);
            boolean selected = start + row == view.selected();
            int base = text.size();
            cells(text, " ", 1);
            cells(text, item.word(), word);
            fill(hl, base, text.size(), selected ? PMENU_SEL : PMENU);
            if (kind > 0) {
                int from = text.size();
                cells(text, " ", 1);
                cells(text, item.kind(), kind);
                fill(hl, from, text.size(), selected ? PMENU_KIND_SEL : PMENU_KIND);
            }
            if (menu > 0) {
                int from = text.size();
                cells(text, " ", 1);
                cells(text, item.menu(), menu);
                fill(hl, from, text.size(), selected ? PMENU_EXTRA_SEL : PMENU_EXTRA);
            }
            int pad = text.size();
            cells(text, " ", 1);
            fill(hl, pad, text.size(), selected ? PMENU_SEL : PMENU);
            if (scrollbar) {
                int bar = text.size();
                cells(text, " ", 1);
                fill(hl, bar, text.size(), row >= thumbStart && row < thumbStart + thumbSize ? PMENU_THUMB : PMENU_SBAR);
            }
        }
        return new GridSnapshot(-1, width, height, text, hl, view.colors().attrs(),
                new GridEvent.DefaultColors(GridEvent.NO_COLOR, GridEvent.NO_COLOR), -1, -1);
    }

    /** The cells a column takes with the space before it; none for a column no entry uses. */
    private static int gap(int column) {
        return column > 0 ? column + 1 : 0;
    }

    /** {@code column} cut to {@code room} cells; dropped when not even two cells are left for it. */
    private static int shrink(int column, int room) {
        return column <= room ? column : room >= 2 ? room : 0;
    }

    /**
     * Adds {@code value} as exactly {@code width} cells: one string per cell, an empty string for
     * the second cell of a wide character (as Neovim's own grids have it), spaces after a short
     * text, an ellipsis at the end of a cut one.
     */
    private static void cells(List<String> out, String value, int width) {
        String shown = CellWidth.of(value) <= width ? value : CellWidth.fit(value, width - 1) + "…";
        int used = 0;
        int marks = 0;
        for (int i = 0; i < shown.length();) {
            int cp = shown.codePointAt(i);
            int w = CellWidth.of(cp);
            String s = new String(Character.toChars(cp));
            i += Character.charCount(cp);
            if (w == 0) {
                // A combining mark joins the cell before it, a few of them at most: text from a file
                // may stack thousands on one letter, which would be drawn across the rows around it.
                // Characters that only direct the layout (bidi overrides, joiners) are left out.
                if (used > 0 && marks < MAX_MARKS_PER_CELL && Character.getType(cp) != Character.FORMAT) {
                    int last = out.size() - 1;
                    while (out.get(last).isEmpty()) {
                        last--;
                    }
                    out.set(last, out.get(last) + s);
                    marks++;
                }
                continue;
            }
            marks = 0;
            out.add(s);
            if (w == 2) {
                out.add("");
            }
            used += w;
        }
        for (; used < width; used++) {
            out.add(" ");
        }
    }

    private static void fill(int[] hl, int from, int to, int id) {
        for (int i = from; i < to; i++) {
            hl[i] = id;
        }
    }
}
