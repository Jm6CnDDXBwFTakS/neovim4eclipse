// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
