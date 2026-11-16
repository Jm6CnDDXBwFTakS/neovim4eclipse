// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.CompletionMenu.Item;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.CompletionMenu.Rows;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.CompletionMenu.View;

/** {@link CompletionMenu}: Neovim's completion list laid out in cells, and where it goes. */
class CompletionMenuTest {

    private static View view(int selected, String... words) {
        List<Item> items = new ArrayList<>();
        for (String word : words) {
            items.add(new Item(word, "", ""));
        }
        return new View(items, selected, 1, 0, 0, 0, CompletionMenu.Colors.PLAIN);
    }

    private static List<String> rows(GridSnapshot grid) {
        List<String> rows = new ArrayList<>();
        for (int row = 0; row < grid.height(); row++) {
            rows.add(grid.row(row));
        }
        return rows;
    }

    // ---- the view, from Neovim's events ----

    /** {@code al} completed to {@code alpha}: the cursor is in cell 6 (1-based), the list at cell 0: five cells back. */
    @Test
    void theWordStartsAsManyCellsBackAsTheCursorIsFromTheListsColumn() {
        CmdlineEvent.MenuShow menu = new CmdlineEvent.MenuShow(List.of("alpha", "alphabet"), 0, 2, 4, 0,
                List.of("f", ""), List.of("[m]", ""));
        View view = View.of(menu, new CompletionMenu.Anchor(5, 5, 6, 0, CompletionMenu.Colors.PLAIN));
        assertEquals(5, view.cellsBack());
        assertEquals(5, view.line1());
        assertEquals(5, view.col0());
        assertEquals(List.of(new Item("alpha", "f", "[m]"), new Item("alphabet", "", "")), view.items());
        assertEquals(0, view.selected());
    }

    /** With a number column four cells wide both the list's column and the cursor's cell are four further right. */
    @Test
    void aNumberColumnShiftsBothAndChangesNothing() {
        CmdlineEvent.MenuShow menu = new CmdlineEvent.MenuShow(List.of("alpha"), 0, 2, 4, 4, List.of(), List.of());
        assertEquals(5, View.of(menu, new CompletionMenu.Anchor(5, 5, 10, 0, CompletionMenu.Colors.PLAIN)).cellsBack());
    }

    /** A report that does not fit (the cursor left of the list) is no reason to draw far away: at the cursor. */
    @Test
    void aCursorLeftOfTheListGivesNoCellsBack() {
        CmdlineEvent.MenuShow menu = new CmdlineEvent.MenuShow(List.of("alpha"), 0, 2, 4, 9, List.of(), List.of());
        assertEquals(0, View.of(menu, new CompletionMenu.Anchor(5, 0, 3, 0, CompletionMenu.Colors.PLAIN)).cellsBack());
    }

    /**
     * Neovim's window wraps at 120 cells; the word starts at cell 118 (0-based) and is five cells
     * long, so the cursor is in cell 4 (1-based) of the next screen row: one row's width is added.
     */
    @Test
    void aWordNeovimWrapsStartsOnTheScreenRowBefore() {
        CmdlineEvent.MenuShow menu = new CmdlineEvent.MenuShow(List.of("alpha"), 0, 2, 4, 118, List.of(), List.of());
        assertEquals(5, View.of(menu, new CompletionMenu.Anchor(4, 123, 4, 0, CompletionMenu.Colors.PLAIN, 120)).cellsBack());
        assertEquals(0, View.of(menu, new CompletionMenu.Anchor(4, 123, 4, 0, CompletionMenu.Colors.PLAIN, 0)).cellsBack(),
                "a window that does not wrap: at the cursor");
        CmdlineEvent.MenuShow sameRow = new CmdlineEvent.MenuShow(List.of("alpha"), 0, 2, 4, 10, List.of(), List.of());
        assertEquals(5, View.of(sameRow, new CompletionMenu.Anchor(4, 15, 16, 0, CompletionMenu.Colors.PLAIN, 120)).cellsBack(),
                "on one row nothing is added");
    }

    @Test
    void selectingMovesTheSelectionAndNothingElse() {
        View view = view(0, "alpha", "alphabet");
        assertEquals(1, view.select(1).selected());
        assertEquals(-1, view.select(-1).selected(), "back to the typed text");
        assertEquals(-1, view.select(7).selected(), "an entry that is not there");
        assertEquals(view.items(), view.select(1).items());
    }

    @Test
    void aViewWithoutEntriesIsHidden() {
        assertFalse(View.HIDDEN.isShowing());
        assertTrue(view(-1, "a").isShowing());
    }

    // ---- the cells ----

    /** One row per entry, a cell of padding on each side, the words padded to the longest. */
    @Test
    void theWordsArePaddedToOneColumn() {
        GridSnapshot grid = CompletionMenu.grid(view(1, "alpha", "alphabet", "al"), 0, 3, 80);
        assertEquals(List.of(" alpha    ", " alphabet ", " al       "), rows(grid));
        assertEquals(10, grid.width());
        assertEquals(3, grid.height());
    }

    @Test
    void theSelectedRowHasTheSelectedGroupOnEveryCell() {
        GridSnapshot grid = CompletionMenu.grid(view(1, "alpha", "alphabet"), 0, 2, 80);
        for (int col = 0; col < grid.width(); col++) {
            assertEquals(CompletionMenu.PMENU, grid.hlAt(0, col));
            assertEquals(CompletionMenu.PMENU_SEL, grid.hlAt(1, col));
        }
    }

    /** Kind and extra text get columns of their own, in their own groups, only when an entry has them. */
    @Test
    void kindAndMenuTextGetTheirOwnColumnsAndGroups() {
        View view = new View(List.of(new Item("alpha", "f", "[m]"), new Item("alps", "", "")), 0, 1, 0, 0, 0,
                CompletionMenu.Colors.PLAIN);
        GridSnapshot grid = CompletionMenu.grid(view, 0, 2, 80);
        assertEquals(List.of(" alpha f [m] ", " alps        "), rows(grid));
        assertEquals(CompletionMenu.PMENU_SEL, grid.hlAt(0, 1));
        assertEquals(CompletionMenu.PMENU_KIND_SEL, grid.hlAt(0, 7));
        assertEquals(CompletionMenu.PMENU_EXTRA_SEL, grid.hlAt(0, 9));
        assertEquals(CompletionMenu.PMENU_SEL, grid.hlAt(0, 12), "the padding at the end");
        assertEquals(CompletionMenu.PMENU_KIND, grid.hlAt(1, 7));
        assertEquals(CompletionMenu.PMENU_EXTRA, grid.hlAt(1, 9));
    }

    /** A wide character takes two cells: its own and an empty one, as in Neovim's grids. */
    @Test
    void aWideCharacterTakesTwoCells() {
        GridSnapshot grid = CompletionMenu.grid(view(-1, "日本", "abcd"), 0, 2, 80);
        assertEquals(6, grid.width());
        assertEquals("日", grid.textAt(0, 1));
        assertEquals("", grid.textAt(0, 2));
        assertEquals("本", grid.textAt(0, 3));
        assertEquals("", grid.textAt(0, 4));
        assertEquals(" ", grid.textAt(0, 5));
    }

    /** More entries than rows: the rows from the first one on, and a scrollbar whose thumb shows where. */
    @Test
    void aCutListScrollsAndGetsAScrollbar() {
        View view = view(4, "a0", "a1", "a2", "a3", "a4", "a5", "a6", "a7");
        GridSnapshot top = CompletionMenu.grid(view, 0, 4, 80);
        assertEquals(List.of(" a0  ", " a1  ", " a2  ", " a3  "), rows(top));
        assertEquals(CompletionMenu.PMENU_THUMB, top.hlAt(0, 4));
        assertEquals(CompletionMenu.PMENU_THUMB, top.hlAt(1, 4));
        assertEquals(CompletionMenu.PMENU_SBAR, top.hlAt(2, 4));
        GridSnapshot bottom = CompletionMenu.grid(view, 4, 4, 80);
        assertEquals(List.of(" a4  ", " a5  ", " a6  ", " a7  "), rows(bottom));
        assertEquals(CompletionMenu.PMENU_SEL, bottom.hlAt(0, 1));
        assertEquals(CompletionMenu.PMENU_SBAR, bottom.hlAt(1, 4));
        assertEquals(CompletionMenu.PMENU_THUMB, bottom.hlAt(3, 4));
    }

    /** Scrolling never changes the width: it is measured over all entries, not the shown ones. */
    @Test
    void theWidthIsTheSameWhereverTheListIsScrolledTo() {
        View view = view(0, "a", "b", "a-very-long-word", "c");
        assertEquals(CompletionMenu.grid(view, 0, 2, 80).width(), CompletionMenu.grid(view, 2, 2, 80).width());
    }

    /** Too wide for the editor: the extra text is cut, then dropped, then a long word is cut with an ellipsis. */
    @Test
    void aListTooWideGivesUpItsExtraTextThenCutsTheWord() {
        View view = new View(List.of(new Item("alphabetical", "f", "[a long remark]")), -1, 1, 0, 0, 0,
                CompletionMenu.Colors.PLAIN);
        assertEquals(List.of(" alphabetical f [a\u2026 "), rows(CompletionMenu.grid(view, 0, 1, 20)), "the remark cut");
        assertEquals(List.of(" alphabetical f "), rows(CompletionMenu.grid(view, 0, 1, 17)), "no room for the remark");
        assertEquals(List.of(" alphabe… "), rows(CompletionMenu.grid(view, 0, 1, 10)));
    }

    @Test
    void aViewWithoutEntriesLaysOutAsOneBlankRow() {
        GridSnapshot grid = CompletionMenu.grid(View.HIDDEN, 0, 5, 80);
        assertEquals(1, grid.height());
        assertEquals("   ", grid.row(0));
    }

    @Test
    void anEmptyAreaStillGivesAUsableGrid() {
        GridSnapshot grid = CompletionMenu.grid(view(0, "alpha"), 0, 0, 0);
        assertEquals(1, grid.height());
        assertTrue(grid.width() >= 3);
    }

    /**
     * A whole-line completion in a file of one huge line: the list keeps only what a row can show
     * (Neovim inserts the real text), so that laying it out never measures megabytes.
     */
    @Test
    void aTextLongerThanARowCanShowIsCutWhenTheViewIsMade() {
        String huge = "x".repeat(1_000_000);
        CmdlineEvent.MenuShow menu = new CmdlineEvent.MenuShow(List.of(huge, "short"), 0, 2, 0, 0,
                List.of(huge, ""), List.of(huge, ""));
        View view = View.of(menu, new CompletionMenu.Anchor(1, 0, 1, 0, CompletionMenu.Colors.PLAIN));
        Item cut = view.items().get(0);
        assertEquals(CompletionMenu.MAX_COLUMNS + 1, cut.word().length(), "one cell more than fits: still drawn as cut");
        assertEquals(CompletionMenu.MAX_COLUMNS + 1, cut.kind().length());
        assertEquals(CompletionMenu.MAX_COLUMNS + 1, cut.menu().length());
        assertEquals("short", view.items().get(1).word());
        String row = CompletionMenu.grid(view, 0, 2, 200).row(0);
        assertTrue(row.contains("\u2026"), "the row shows that the text goes on");
        assertTrue(row.length() <= CompletionMenu.MAX_COLUMNS);
    }

    /**
     * Text from a file may stack any number of combining marks on one letter, which would be drawn
     * across the rows around it: a few are kept. Characters that only direct the layout (a
     * right-to-left override) are left out.
     */
    @Test
    void stackedMarksAreLimitedAndLayoutCharactersLeftOut() {
        GridSnapshot grid = CompletionMenu.grid(view(-1, "a" + "\u0301".repeat(500) + "b\u202Ec"), 0, 1, 80);
        assertEquals("a" + "\u0301".repeat(6), grid.textAt(0, 1));
        assertEquals("b", grid.textAt(0, 2));
        assertEquals("c", grid.textAt(0, 3), "the override between them is not drawn");
        assertEquals(5, grid.width(), "three cells of text and the padding");
    }

    // ---- rows, scrolling, the left edge ----

    @Test
    void theListGoesBelowTheLineWhenItFitsThere() {
        assertEquals(new Rows(5, true), CompletionMenu.rows(5, 0, 10, 30));
    }

    @Test
    void itGoesAboveWhenOnlyThereIsRoomForAllOfIt() {
        assertEquals(new Rows(12, false), CompletionMenu.rows(12, 0, 3, 20));
    }

    @Test
    void whereNeitherSideHoldsItTheRoomierOneIsUsedAndTheListCut() {
        assertEquals(new Rows(8, false), CompletionMenu.rows(40, 0, 5, 8));
        assertEquals(new Rows(8, true), CompletionMenu.rows(40, 0, 8, 8), "a tie: below, as Vim");
    }

    /** 'pumheight' limits the rows on either side. */
    @Test
    void pumheightLimitsTheRows() {
        assertEquals(new Rows(10, true), CompletionMenu.rows(40, 10, 12, 30));
        assertEquals(new Rows(3, true), CompletionMenu.rows(3, 10, 12, 30));
    }

    @Test
    void thereIsAlwaysOneRow() {
        assertEquals(1, CompletionMenu.rows(5, 0, 0, 0).rows());
    }

    @Test
    void theViewScrollsJustFarEnoughToShowTheSelection() {
        assertEquals(0, CompletionMenu.firstRow(0, 3, 10, 5), "in view: stays");
        assertEquals(3, CompletionMenu.firstRow(0, 7, 10, 5), "below: the selection becomes the last row");
        assertEquals(2, CompletionMenu.firstRow(5, 2, 10, 5), "above: it becomes the first");
        assertEquals(5, CompletionMenu.firstRow(5, -1, 10, 5), "nothing selected: stays");
        assertEquals(5, CompletionMenu.firstRow(9, 9, 10, 5), "never past the end");
        assertEquals(0, CompletionMenu.firstRow(3, 1, 4, 5), "everything fits");
    }

    /** The list's text lines up with the word: the list starts one cell left of it, for its padding. */
    @Test
    void theListStartsOneCellLeftOfTheWordButInsideTheEditor() {
        assertEquals(90, CompletionMenu.left(100, 10, 200, 800));
        assertEquals(0, CompletionMenu.left(4, 10, 200, 800), "at the left edge");
        assertEquals(600, CompletionMenu.left(790, 10, 200, 800), "at the right edge: moved in");
        assertEquals(0, CompletionMenu.left(100, 10, 900, 800), "wider than the editor: from the left");
    }

    // ---- colors ----

    /** What Neovim sends per group: foreground, background, bold, italic, underline, reverse. */
    @Test
    void colorsComeFromWhatNeovimSends() {
        CompletionMenu.Colors colors = CompletionMenu.Colors.from(List.of(
                List.of(0xC0C0C0, 0x202020, false, false, false, false),
                List.of(-1, -1, true, false, false, true)));
        GridEvent.HlAttr pmenu = colors.groups().get(0);
        assertEquals(CompletionMenu.PMENU, pmenu.id());
        assertEquals(0xC0C0C0, pmenu.foreground());
        assertEquals(0x202020, pmenu.background());
        GridEvent.HlAttr selected = colors.groups().get(1);
        assertEquals(GridEvent.NO_COLOR, selected.foreground(), "left open: the editor's own");
        assertTrue(selected.reverse());
        assertTrue(selected.bold());
    }

    /** Groups that were not sent, or not as expected, are plain, and the selected ones reversed so the selection shows. */
    @Test
    void missingOrMalformedColorsStillShowTheSelection() {
        CompletionMenu.Colors colors = CompletionMenu.Colors.from(List.of("nonsense", List.of(1, 2)));
        assertEquals(CompletionMenu.GROUPS.size(), colors.groups().size());
        assertFalse(colors.groups().get(CompletionMenu.PMENU - 1).reverse());
        assertTrue(colors.groups().get(CompletionMenu.PMENU_SEL - 1).reverse());
        assertEquals(GridEvent.NO_COLOR, colors.groups().get(0).background());
    }

    @Test
    void theGridCarriesTheGroupsColors() {
        CompletionMenu.Colors colors = CompletionMenu.Colors.from(List.of(List.of(0x111111, 0x222222, false, false, false, false)));
        View view = new View(List.of(new Item("a", "", "")), -1, 1, 0, 0, 0, colors);
        GridSnapshot grid = CompletionMenu.grid(view, 0, 1, 80);
        int[] shown = grid.colors(grid.hlAt(0, 0), 0xFFFFFF, 0x000000);
        assertEquals(0x111111, shown[0]);
        assertEquals(0x222222, shown[1]);
    }
}
