// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GridEvent.Cell;

/** The grids {@link GridEvent}s build, and the snapshot of the drawn window. */
class GridsTest {

    private final Grids grids = new Grids();

    private static List<Cell> text(String s, int hl) {
        List<Cell> cells = new ArrayList<>();
        s.codePoints().forEach(cp -> cells.add(new Cell(Character.toString(cp), hl)));
        return cells;
    }

    /** A 6x3 grid 4 showing window 1004, drawn, rows "a", "b", "c". */
    private GridSnapshot commandWindow() {
        grids.draw(1004);
        grids.apply(new GridEvent.Resize(4, 6, 3));
        grids.apply(new GridEvent.Line(4, 0, 0, text("a", 0)));
        grids.apply(new GridEvent.Line(4, 1, 0, text("b", 0)));
        grids.apply(new GridEvent.Line(4, 2, 0, text("c", 0)));
        grids.apply(new GridEvent.CursorGoto(4, 2, 0));
        grids.apply(new GridEvent.WinPos(4, 1004, false));
        grids.apply(new GridEvent.Flush());
        return grids.snapshotIfChanged().orElseThrow();
    }

    @Test
    void theDrawnWindowsGridComesOnceItsWindowIsKnown() {
        grids.draw(1004);
        grids.apply(new GridEvent.Resize(4, 6, 3));
        grids.apply(new GridEvent.Line(4, 0, 0, text(":w", 0)));
        assertTrue(grids.snapshotIfChanged().isEmpty(), "no win_pos yet: which grid is the window's is not known");
        grids.apply(new GridEvent.WinPos(4, 1004, false)); // at the end of the redraw, after the lines
        GridSnapshot s = grids.snapshotIfChanged().orElseThrow();
        assertEquals(":w    ", s.row(0));
        assertEquals("      ", s.row(1));
        assertEquals(1004, s.window());
        assertTrue(grids.snapshotIfChanged().isEmpty(), "nothing changed since");
    }

    @Test
    void anotherWindowsLinesChangeNothingDrawn() {
        commandWindow();
        grids.apply(new GridEvent.Resize(2, 6, 10));
        grids.apply(new GridEvent.WinPos(2, 1000, false)); // an editor's window
        grids.apply(new GridEvent.Line(2, 0, 0, text("editor", 0)));
        assertTrue(grids.snapshotIfChanged().isEmpty());
    }

    @Test
    void onlyTheLinesOfGridsThatMayBeDrawnAreWanted() {
        assertTrue(grids.wantsLines(2), "a grid whose window is not known yet");
        grids.apply(new GridEvent.Resize(2, 6, 10));
        grids.apply(new GridEvent.WinPos(2, 1000, false));
        assertTrue(!grids.wantsLines(2), "an editor's window, nothing drawn");
        commandWindow();
        assertTrue(grids.wantsLines(4), "the drawn window's grid");
        assertTrue(!grids.wantsLines(2), "still not the editor's");
    }

    @Test
    void theCursorIsShownOnlyOnItsGrid() {
        GridSnapshot s = commandWindow();
        assertEquals(2, s.cursorRow());
        grids.apply(new GridEvent.CursorGoto(2, 5, 5)); // into the editor's grid
        GridSnapshot moved = grids.snapshotIfChanged().orElseThrow();
        assertEquals(-1, moved.cursorRow(), "the cursor left the drawn grid");
        grids.apply(new GridEvent.CursorGoto(2, 6, 6));
        assertTrue(grids.snapshotIfChanged().isEmpty(), "moving on the other grid changes nothing drawn");
    }

    @Test
    void scrollingMovesRowsUpAndDown() {
        commandWindow();
        grids.apply(new GridEvent.Scroll(4, 0, 3, 0, 6, 1));
        GridSnapshot up = grids.snapshotIfChanged().orElseThrow();
        assertEquals(List.of("b     ", "c     ", "c     "), List.of(up.row(0), up.row(1), up.row(2)),
                "the freed row keeps its old cells until Neovim redraws it");
        grids.apply(new GridEvent.Scroll(4, 0, 3, 0, 6, -2));
        GridSnapshot down = grids.snapshotIfChanged().orElseThrow();
        assertEquals("b     ", down.row(2));
    }

    @Test
    void aResizeKeepsWhatFitsAndAClearBlanks() {
        commandWindow();
        grids.apply(new GridEvent.Resize(4, 3, 2));
        GridSnapshot smaller = grids.snapshotIfChanged().orElseThrow();
        assertEquals(List.of("a  ", "b  "), List.of(smaller.row(0), smaller.row(1)));
        grids.apply(new GridEvent.Clear(4));
        assertEquals("   ", grids.snapshotIfChanged().orElseThrow().row(0));
    }

    @Test
    void aLinePastTheEdgeIsCutAndWideCharactersTakeTwoCells() {
        commandWindow();
        grids.apply(new GridEvent.Line(4, 0, 3, List.of(new Cell("日", 5), new Cell("", 5), new Cell("x", 6), new Cell("y", 6))));
        GridSnapshot s = grids.snapshotIfChanged().orElseThrow();
        assertEquals("a  日x", s.row(0), "y fell off the right edge");
        assertEquals(5, s.hlAt(0, 4));
    }

    @Test
    void theSnapshotCarriesTheHighlightsItUses() {
        commandWindow();
        GridEvent.HlAttr bold = new GridEvent.HlAttr(5, 0x112233, GridEvent.NO_COLOR, false, true, false, false, false);
        grids.apply(bold);
        grids.apply(new GridEvent.HlAttr(9, 0, 0, false, false, false, false, false)); // used nowhere here
        grids.apply(new GridEvent.Line(4, 0, 0, text("x", 5)));
        GridSnapshot s = grids.snapshotIfChanged().orElseThrow();
        assertEquals(bold, s.attr(5));
        assertTrue(!s.attrs().containsKey(9), "only the highlights its cells use");
        assertEquals(GridEvent.NO_COLOR, s.attr(0).foreground(), "0 is the default colors");
    }

    @Test
    void stoppingDrawsNothingAndAClosedWindowsGridGoes() {
        commandWindow();
        grids.apply(new GridEvent.WinGone(4));
        grids.apply(new GridEvent.Destroy(4));
        assertTrue(grids.snapshotIfChanged().isEmpty(), "the grid is gone");
        grids.stopDrawing();
        assertEquals(-1, grids.drawnWindow());
        grids.apply(new GridEvent.Resize(4, 6, 3));
        assertTrue(grids.snapshotIfChanged().isEmpty());
    }

    // ---- floating windows ----

    /** The editor's window 1000 on grid 2, 80x23 from the screen's top left, with the cursor in it. */
    private void editorWindow() {
        grids.apply(new GridEvent.Resize(2, 80, 23));
        grids.apply(new GridEvent.WinPos(2, 1000, false, 0, 0, 80, 23, 0));
        grids.apply(new GridEvent.CursorGoto(2, 4, 0));
    }

    /** A float on {@code grid} showing {@code win}, 6x2, rows "hi" and blank, at a screen cell. */
    private void floatAt(int grid, int win, int row, int col, int zindex) {
        grids.apply(new GridEvent.Resize(grid, 6, 2));
        grids.apply(new GridEvent.Line(grid, 0, 0, text("hi", 0))); // the lines come before the window is known
        grids.apply(new GridEvent.WinPos(grid, win, true, row, col, 0, 0, zindex));
    }

    @Test
    void aFloatWithoutTheFocusIsDrawnWhereNeovimPutItCountedFromTheEditorsWindow() {
        grids.drawFloats(true);
        grids.apply(new GridEvent.Resize(2, 80, 23));
        grids.apply(new GridEvent.WinPos(2, 1000, false, 1, 0, 80, 23, 0)); // under a tab line, say
        grids.apply(new GridEvent.CursorGoto(2, 4, 0));
        floatAt(5, 1002, 19, 10, 50);
        List<FloatWindow> floats = grids.floatsIfChanged().orElseThrow();
        assertEquals(1, floats.size());
        FloatWindow f = floats.get(0);
        assertEquals("hi    ", f.grid().row(0));
        assertEquals(1002, f.grid().window());
        assertEquals(-1, f.grid().cursorRow(), "no cursor: it has no focus");
        assertEquals(18, f.row(), "screen row 19, the window starts at row 1");
        assertEquals(10, f.col());
        assertEquals(23, f.hostRows());
        assertEquals(80, f.hostCols());
        assertTrue(grids.floatsIfChanged().isEmpty(), "nothing changed since");
    }

    @Test
    void floatsComeLowestInTheStackFirst() {
        grids.drawFloats(true);
        editorWindow();
        floatAt(6, 1003, 5, 0, 1001);
        floatAt(5, 1002, 18, 0, 1000);
        List<FloatWindow> floats = grids.floatsIfChanged().orElseThrow();
        assertEquals(List.of(1002, 1003), floats.stream().map(f -> f.grid().window()).toList());
    }

    @Test
    void aFloatWithTheFocusIsNotDrawnAndComesBackWhenTheFocusLeavesIt() {
        grids.drawFloats(true);
        editorWindow();
        floatAt(5, 1002, 3, 3, 50);
        assertEquals(1, grids.floatsIfChanged().orElseThrow().size());
        grids.apply(new GridEvent.CursorGoto(5, 0, 0)); // the user went into it
        assertEquals(List.of(), grids.floatsIfChanged().orElseThrow());
        grids.apply(new GridEvent.CursorGoto(2, 4, 0));
        FloatWindow back = grids.floatsIfChanged().orElseThrow().get(0);
        assertEquals(3, back.row());
    }

    /**
     * Neovim says where the cursor is only when that changes: a session that began to listen later
     * never heard it. The editor's window is still the one layout window on screen.
     */
    @Test
    void theEditorsWindowIsKnownWithoutTheCursorAndAHiddenTabsWindowDoesNotCount() {
        grids.drawFloats(true);
        grids.apply(new GridEvent.Resize(3, 80, 23));
        grids.apply(new GridEvent.WinPos(3, 1001, false, 0, 0, 80, 23, 0)); // another editor's, then hidden
        grids.apply(new GridEvent.WinGone(3));
        grids.apply(new GridEvent.Resize(2, 60, 20));
        grids.apply(new GridEvent.WinPos(2, 1000, false, 2, 1, 60, 20, 0));
        floatAt(5, 1002, 9, 4, 50);
        FloatWindow f = grids.floatsIfChanged().orElseThrow().get(0);
        assertEquals(7, f.row());
        assertEquals(3, f.col());
        assertEquals(20, f.hostRows());
        assertEquals(60, f.hostCols());
    }

    @Test
    void beforeAnyWindowsPlaceIsKnownAFloatIsCountedFromTheScreenAndThenFromTheWindow() {
        grids.drawFloats(true);
        floatAt(5, 1002, 9, 4, 50);
        FloatWindow early = grids.floatsIfChanged().orElseThrow().get(0);
        assertEquals(9, early.row());
        assertEquals(0, early.hostRows(), "not known: no edge to sit on");
        grids.apply(new GridEvent.WinPos(2, 1000, false, 1, 0, 80, 23, 0));
        assertEquals(8, grids.floatsIfChanged().orElseThrow().get(0).row());
    }

    @Test
    void aClosedOrHiddenFloatIsGoneAndAMovedOneMoves() {
        grids.drawFloats(true);
        editorWindow();
        floatAt(5, 1002, 9, 2, 50);
        grids.floatsIfChanged();
        grids.apply(new GridEvent.WinPos(5, 1002, true, 8, 2, 0, 0, 50)); // the text scrolled a line
        assertEquals(8, grids.floatsIfChanged().orElseThrow().get(0).row());
        grids.apply(new GridEvent.WinGone(5));
        assertEquals(List.of(), grids.floatsIfChanged().orElseThrow());
        assertTrue(grids.floatsIfChanged().isEmpty());
    }

    @Test
    void aFloatsNewLinesGiveANewAnswerAndTheEditorsDoNot() {
        grids.drawFloats(true);
        editorWindow();
        floatAt(5, 1002, 9, 2, 50);
        grids.floatsIfChanged();
        assertTrue(grids.wantsLines(5));
        assertTrue(!grids.wantsLines(2), "the editor's window is never drawn");
        grids.apply(new GridEvent.Line(2, 0, 0, text("typed", 0)));
        assertTrue(grids.floatsIfChanged().isEmpty());
        grids.apply(new GridEvent.Line(5, 1, 0, text("more", 0)));
        assertEquals("more  ", grids.floatsIfChanged().orElseThrow().get(0).grid().row(1));
    }

    @Test
    void withFloatsOffNothingIsDrawnAndTheirLinesAreNotWanted() {
        editorWindow();
        floatAt(5, 1002, 9, 2, 50);
        assertTrue(grids.floatsIfChanged().isEmpty());
        assertTrue(!grids.wantsLines(5));
        grids.drawFloats(true);
        assertEquals(1, grids.floatsIfChanged().orElseThrow().size());
        grids.drawFloats(false);
        assertEquals(List.of(), grids.floatsIfChanged().orElseThrow());
    }

    /** A float of {@code width} by {@code height} blank cells on {@code grid}, showing {@code win}. */
    private void floatOfSize(int grid, int win, int width, int height, int zindex) {
        grids.apply(new GridEvent.Resize(grid, width, height));
        grids.apply(new GridEvent.WinPos(grid, win, true, 0, 0, 0, 0, zindex));
    }

    private List<Integer> drawnWindows() {
        return grids.floatsIfChanged().orElseThrow().stream().map(f -> f.grid().window()).toList();
    }

    /** 150,000 + 125,000 is over the 250,000 cells; the lowest, 75,000, still fits beside the top one. */
    @Test
    void pastTheCellLimitTheWindowsOnTopStayAndASmallerLowerOneStillFits() {
        grids.drawFloats(true);
        editorWindow();
        floatOfSize(5, 1002, 750, 100, 10); // 75,000 cells, lowest
        floatOfSize(6, 1003, 1250, 100, 20); // 125,000
        floatOfSize(7, 1004, 1500, 100, 30); // 150,000, on top
        assertEquals(List.of(1002, 1004), drawnWindows());
        grids.apply(new GridEvent.WinGone(7)); // the top one closes: now the other two fit
        assertEquals(List.of(1002, 1003), drawnWindows());
    }

    @Test
    void aSingleWindowOverTheCellLimitIsNeverDrawnAndOneJustUnderItIs() {
        grids.drawFloats(true);
        editorWindow();
        floatOfSize(5, 1002, 1000, 251, 50); // 251,000 cells
        assertTrue(grids.floatsIfChanged().isEmpty(), "nothing to draw, as before it opened");
        grids.apply(new GridEvent.Resize(5, 1000, 250)); // exactly the limit
        assertEquals(List.of(1002), drawnWindows());
    }

    @Test
    void pastTheWindowLimitTheLowestAreLeftOut() {
        grids.drawFloats(true);
        editorWindow();
        for (int i = 0; i < Grids.MAX_FLOATS + 2; i++) {
            floatOfSize(10 + i, 2000 + i, 4, 1, i); // zindex 0 is the lowest
        }
        List<Integer> drawn = drawnWindows();
        assertEquals(Grids.MAX_FLOATS, drawn.size());
        assertEquals(2002, drawn.get(0), "the two lowest, 2000 and 2001, are the ones left out");
        assertEquals(2000 + Grids.MAX_FLOATS + 1, drawn.get(drawn.size() - 1));
    }

    @Test
    void theCommandLineWindowIsNotAFloatAndStaysItsOwn() {
        grids.drawFloats(true);
        editorWindow();
        GridSnapshot window = commandWindow();
        assertEquals("a     ", window.row(0));
        assertTrue(grids.floatsIfChanged().isEmpty(), "no float anywhere: nothing to say");
    }

    // ---- pages' windows (drawPage) ----

    /** Page window 1005 on grid 5, 8x2, "msg 1" and "msg 2", with the cursor on it. */
    private void pageDrawn() {
        grids.drawPage(1005);
        grids.apply(new GridEvent.Resize(5, 8, 2));
        grids.apply(new GridEvent.Line(5, 0, 0, text("msg 1", 0)));
        grids.apply(new GridEvent.Line(5, 1, 0, text("msg 2", 0)));
        grids.apply(new GridEvent.CursorGoto(5, 1, 0));
        grids.apply(new GridEvent.WinPos(5, 1005, false));
        grids.apply(new GridEvent.Flush());
    }

    @Test
    void aPagesGridComesOnceItsWindowIsKnownAndAgainOnlyWhenItChanges() {
        pageDrawn();
        List<GridSnapshot> pages = grids.pagesIfChanged();
        assertEquals(1, pages.size());
        assertEquals(1005, pages.get(0).window());
        assertEquals("msg 2   ", pages.get(0).row(1));
        assertEquals(5, grids.pageGrid(1005));
        assertTrue(grids.pagesIfChanged().isEmpty(), "nothing changed since");
        grids.apply(new GridEvent.Line(5, 0, 0, text("MSG", 0)));
        assertEquals("MSG 1   ", grids.pagesIfChanged().get(0).row(0));
    }

    @Test
    void aPagesLinesAreWantedAndAnEditorsStayUnread() {
        grids.apply(new GridEvent.Resize(2, 6, 10));
        grids.apply(new GridEvent.WinPos(2, 1000, false)); // an editor's window
        pageDrawn();
        assertTrue(grids.wantsLines(5));
        assertTrue(!grids.wantsLines(2));
        grids.apply(new GridEvent.Line(2, 0, 0, text("editor", 0)));
        grids.pagesIfChanged();
        assertTrue(grids.pagesIfChanged().isEmpty(), "the editor's lines changed no page");
    }

    /** A page in a tabpage that is not current keeps its last cells: hidden, nothing is sent again. */
    @Test
    void aHiddenPageKeepsItsLastSnapshot() {
        pageDrawn();
        grids.pagesIfChanged();
        grids.apply(new GridEvent.WinGone(5)); // win_hide: an editor's tabpage became current
        grids.apply(new GridEvent.CursorGoto(2, 0, 0));
        grids.apply(new GridEvent.Flush());
        assertTrue(grids.pagesIfChanged().isEmpty(), "the page editor keeps showing what it has");
    }

    @Test
    void aPageNoLongerDrawnSendsNothing() {
        pageDrawn();
        grids.pagesIfChanged();
        grids.stopDrawingPage(1005);
        grids.apply(new GridEvent.Line(5, 0, 0, text("late", 0)));
        assertTrue(grids.pagesIfChanged().isEmpty());
        assertEquals(-1, grids.pageGrid(1005));
    }

    /** A page drawn from now on whose grid is there already goes out at the next flush. */
    @Test
    void aPageDrawnLateComesAtTheNextFlush() {
        grids.apply(new GridEvent.Resize(5, 8, 2));
        grids.apply(new GridEvent.Line(5, 0, 0, text("msg 1", 0)));
        grids.apply(new GridEvent.WinPos(5, 1005, false));
        grids.apply(new GridEvent.Flush());
        grids.drawPage(1005);
        assertEquals(1, grids.pagesIfChanged().size());
    }

    // ---- a page's status line row (pageStatusIfChanged) ----

    /** The screen, grid 1, 8x7, with the page window 1005 at its top, 5 rows high: its status line on row 5. */
    private void screenWithPage() {
        grids.drawPage(1005);
        grids.apply(new GridEvent.Resize(1, 8, 7));
        grids.apply(new GridEvent.Line(1, 5, 0, text("status", 3)));
        grids.apply(new GridEvent.Resize(5, 8, 5));
        grids.apply(new GridEvent.WinPos(5, 1005, false, 0, 0, 8, 5, 0));
        grids.apply(new GridEvent.Flush());
    }

    @Test
    void aPagesStatusLineIsTheScreensRowBelowItsWindow() {
        screenWithPage();
        List<GridSnapshot> rows = grids.pageStatusIfChanged();
        assertEquals(1, rows.size());
        assertEquals(1005, rows.get(0).window());
        assertEquals("status  ", rows.get(0).row(0));
        assertEquals(3, rows.get(0).hlAt(0, 0), "with its colors");
        assertTrue(grids.pageStatusIfChanged().isEmpty(), "unchanged: not sent again");
        grids.apply(new GridEvent.Line(1, 5, 0, text("STATUS", 3)));
        assertEquals("STATUS  ", grids.pageStatusIfChanged().get(0).row(0));
    }

    @Test
    void noStatusLineWhenTheWindowReachesTheScreensBottom() {
        grids.drawPage(1005);
        grids.apply(new GridEvent.Resize(1, 8, 7));
        grids.apply(new GridEvent.WinPos(5, 1005, false, 0, 0, 8, 7, 0)); // 'laststatus' 0: no row below it
        List<GridSnapshot> rows = grids.pageStatusIfChanged();
        assertEquals(1, rows.size());
        assertEquals(0, rows.get(0).height(), "a snapshot of no rows: no status line");
        assertTrue(grids.pageStatusIfChanged().isEmpty(), "said once");
    }

    /**
     * The screen's last row is ui2's command line ({@code 'cmdheight'} 1): a window that reaches
     * down to it has no status line, though a row is left below it.
     */
    @Test
    void theCommandLinesRowIsNoStatusLine() {
        screenWithPage();
        grids.pageStatusIfChanged();
        grids.apply(new GridEvent.WinPos(5, 1005, false, 0, 0, 8, 6, 0)); // 'laststatus' 0: rows 0-5, the line on row 6
        List<GridSnapshot> rows = grids.pageStatusIfChanged();
        assertEquals(1, rows.size());
        assertEquals(0, rows.get(0).height(), "row 6 is the command line's");
    }

    /** {@code :set laststatus=0} while the page shows its status line: the row is taken back. */
    @Test
    void aStatusLineThatGoesIsSaidToBeGone() {
        screenWithPage();
        assertEquals(1, grids.pageStatusIfChanged().get(0).height());
        grids.apply(new GridEvent.WinPos(5, 1005, false, 0, 0, 8, 7, 0)); // the window grows to the bottom
        List<GridSnapshot> rows = grids.pageStatusIfChanged();
        assertEquals(1, rows.size());
        assertEquals(1005, rows.get(0).window());
        assertEquals(0, rows.get(0).height());
        grids.apply(new GridEvent.WinPos(5, 1005, false, 0, 0, 8, 5, 0)); // :set laststatus=2
        assertEquals("status  ", grids.pageStatusIfChanged().get(0).row(0), "and back");
    }

    @Test
    void aPageNotOnScreenKeepsItsLastStatusLine() {
        screenWithPage();
        grids.pageStatusIfChanged();
        grids.apply(new GridEvent.WinGone(5));
        grids.apply(new GridEvent.Line(1, 5, 0, text("other", 3))); // another window's status line now
        assertTrue(grids.pageStatusIfChanged().isEmpty());
    }

    // ---- ui2's windows and Neovim's popup menu ----

    /**
     * Neovim's screen of 25 rows (the default grid, 1) with the editor's window of 23 at its top:
     * its status line and ui2's command line below it. ui2's command line, window 1010, on grid 8 at
     * the screen's last row, as wide as the screen.
     */
    private void screenWithUi2Line() {
        grids.apply(new GridEvent.Resize(1, 80, 25));
        editorWindow();
        grids.ui2Windows(List.of(1010, 1011, 1012, 1013), 1010, false);
        grids.apply(new GridEvent.Resize(8, 80, 1));
        grids.apply(new GridEvent.Line(8, 0, 0, text(":ech", 0)));
        grids.apply(new GridEvent.WinPos(8, 1010, true, 24, 0, 0, 0, 200));
    }

    /**
     * The user's {@code 'cmdheight'} 0: the editor keeps no row for the line, so ui2's line is drawn
     * only while it is open (the cursor in it), on the editor's bottom edge over the status line;
     * between lines the status line shows, not the last line typed.
     */
    @Test
    void withCmdheightZeroUi2sLineIsDrawnOnlyWhileOpen() {
        grids.drawFloats(true);
        screenWithUi2Line();
        grids.ui2Windows(List.of(1010, 1011, 1012, 1013), 1010, true);
        grids.apply(new GridEvent.CursorGoto(2, 3, 0)); // the cursor in the editor's window
        assertEquals(List.of(), grids.floatsIfChanged().orElse(List.of()), "no line open: nothing over the status line");
        grids.apply(new GridEvent.CursorGoto(8, 0, 4)); // ':ech' being typed
        List<FloatWindow> floats = grids.floatsIfChanged().orElseThrow();
        assertEquals(1, floats.size());
        assertEquals(floats.get(0).row() + 1, floats.get(0).hostRows(), "on the screen's last row: the editor's bottom edge");
        grids.apply(new GridEvent.CursorGoto(2, 3, 0)); // Enter: back in the window
        assertEquals(List.of(), grids.floatsIfChanged().orElseThrow());
        grids.ui2Windows(List.of(1010, 1011, 1012, 1013), 1010, false); // :set ch=1
        assertEquals(1, grids.floatsIfChanged().orElseThrow().size(), "with its row back, the line stays drawn");
    }

    /** The {@code :} line being typed: the cursor is in ui2's line, which is drawn with it. */
    @Test
    void ui2sLineIsDrawnWithTheCursorInItOnTheCommandLinesRow() {
        grids.drawFloats(true);
        screenWithUi2Line();
        grids.apply(new GridEvent.CursorGoto(8, 0, 4));
        List<FloatWindow> floats = grids.floatsIfChanged().orElseThrow();
        assertEquals(1, floats.size());
        FloatWindow line = floats.get(0);
        assertEquals(":ech", line.grid().row(0).strip());
        assertEquals(0, line.grid().cursorRow());
        assertEquals(4, line.grid().cursorCol());
        assertEquals(24, line.row(), "screen row 24: under the editor's window and its status line, as on Neovim's screen");
        assertEquals(line.row() + 1, line.hostRows(), "the host counts the rows the editor draws under its window: the line is its last");
        grids.apply(new GridEvent.CursorGoto(8, 0, 2)); // the cursor moved left, no line changed
        assertEquals(2, grids.floatsIfChanged().orElseThrow().get(0).grid().cursorCol(), "drawn again with the cursor");
    }

    /** vim.g.neovim4eclipse_floating_windows = 0 hides plug-ins' floats, not Neovim's own line. */
    @Test
    void ui2sWindowsAreDrawnWithPlugInsFloatsTurnedOff() {
        grids.drawFloats(false);
        screenWithUi2Line();
        floatAt(5, 1002, 3, 3, 50); // a plug-in's panel
        assertTrue(grids.wantsLines(8), "ui2's lines are read");
        assertFalse(grids.wantsLines(5), "a plug-in float's are not");
        List<FloatWindow> floats = grids.floatsIfChanged().orElseThrow();
        assertEquals(List.of(1010), floats.stream().map(f -> f.grid().window()).toList());
    }

    /**
     * Neovim's popup menu, the one float with no window (-1): drawn with plug-ins' floats off too.
     * While a {@code :} line is typed it hangs from that line, where Neovim put it; in insert mode
     * it stays by the text.
     */
    @Test
    void thePopupMenuHangsFromTheCommandLineOrStaysByTheText() {
        grids.drawFloats(false);
        screenWithUi2Line();
        grids.apply(new GridEvent.Resize(9, 16, 5));
        grids.apply(new GridEvent.Line(9, 0, 0, text(" echo", 0)));
        grids.apply(new GridEvent.WinPos(9, -1, true, 19, 0, 0, 0, 250)); // its last row just above the line
        grids.apply(new GridEvent.CursorGoto(8, 0, 4));
        List<FloatWindow> typing = grids.floatsIfChanged().orElseThrow();
        assertEquals(List.of(1010, -1), typing.stream().map(f -> f.grid().window()).toList());
        assertEquals(19, typing.get(1).row(), "screen row 19, just above the line, as Neovim put it");
        grids.apply(new GridEvent.CursorGoto(2, 4, 0)); // insert-mode completion, by the text
        List<FloatWindow> inserting = grids.floatsIfChanged().orElseThrow();
        assertEquals(19, inserting.get(1).row(), "where Neovim put it");
    }
}
