// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GridEvent.Cell;

/** The one screen of a Neovim attached without {@code ext_multigrid} (the Neovim view's). */
class ScreenGridTest {

    private final ScreenGrid screen = new ScreenGrid();

    private static List<Cell> text(String s, int hl) {
        List<Cell> cells = new ArrayList<>();
        s.codePoints().forEach(cp -> cells.add(new Cell(Character.toString(cp), hl)));
        return cells;
    }

    @Test
    void theScreenComesAtFlushOnceItsSizeIsKnown() {
        screen.apply(new GridEvent.Line(ScreenGrid.SCREEN, 0, 0, text("OK", 0)));
        screen.apply(new GridEvent.Flush());
        assertTrue(screen.snapshotIfChanged().isEmpty(), "no size yet: nothing to draw, and the line had no cells to land in");

        screen.apply(new GridEvent.Resize(ScreenGrid.SCREEN, 4, 2));
        screen.apply(new GridEvent.Line(ScreenGrid.SCREEN, 1, 1, text("OK", 0)));
        screen.apply(new GridEvent.CursorGoto(ScreenGrid.SCREEN, 1, 1));
        screen.apply(new GridEvent.Flush());
        GridSnapshot s = screen.snapshotIfChanged().orElseThrow();
        assertEquals("    ", s.row(0));
        assertEquals(" OK ", s.row(1));
        assertEquals(1, s.cursorRow());
        assertEquals(1, s.cursorCol());
        assertEquals(ScreenGrid.WHOLE_SCREEN, s.window(), "the whole screen, no window of Neovim's");
        assertTrue(screen.snapshotIfChanged().isEmpty(), "nothing changed since");
    }

    @Test
    void aRedrawThatChangesNothingGivesNoSnapshot() {
        screen.apply(new GridEvent.Resize(ScreenGrid.SCREEN, 3, 1));
        screen.apply(new GridEvent.Line(ScreenGrid.SCREEN, 0, 0, text("abc", 0)));
        screen.snapshotIfChanged().orElseThrow();
        screen.apply(new GridEvent.Line(ScreenGrid.SCREEN, 0, 0, text("abc", 0))); // the same cells again
        assertTrue(screen.snapshotIfChanged().isEmpty());
    }

    @Test
    void anotherGridsEventsAreNotPartOfTheScreen() {
        screen.apply(new GridEvent.Resize(ScreenGrid.SCREEN, 3, 1));
        screen.snapshotIfChanged().orElseThrow();
        screen.apply(new GridEvent.Resize(2, 9, 9));
        screen.apply(new GridEvent.Line(2, 0, 0, text("xyz", 0)));
        screen.apply(new GridEvent.WinPos(2, 1000, false));
        assertTrue(screen.snapshotIfChanged().isEmpty(), "grid 2 is no part of a single-grid screen");
    }

    @Test
    void resizeScrollAndClearWorkAsOnEveryGrid() {
        screen.apply(new GridEvent.Resize(ScreenGrid.SCREEN, 2, 3));
        screen.apply(new GridEvent.Line(ScreenGrid.SCREEN, 0, 0, text("a", 0)));
        screen.apply(new GridEvent.Line(ScreenGrid.SCREEN, 1, 0, text("b", 0)));
        screen.apply(new GridEvent.Line(ScreenGrid.SCREEN, 2, 0, text("c", 0)));
        screen.apply(new GridEvent.Scroll(ScreenGrid.SCREEN, 0, 3, 0, 2, 1)); // up by one row
        GridSnapshot s = screen.snapshotIfChanged().orElseThrow();
        assertEquals("b ", s.row(0));
        assertEquals("c ", s.row(1));

        screen.apply(new GridEvent.Resize(ScreenGrid.SCREEN, 3, 1)); // what fits stays
        s = screen.snapshotIfChanged().orElseThrow();
        assertEquals(3, s.width());
        assertEquals("b  ", s.row(0));

        screen.apply(new GridEvent.Clear(ScreenGrid.SCREEN));
        assertEquals("   ", screen.snapshotIfChanged().orElseThrow().row(0));
    }

    @Test
    void aSnapshotCarriesTheHighlightsItsCellsUse() {
        screen.apply(new GridEvent.HlAttr(7, 0xFF0000, GridEvent.NO_COLOR, false, true, false, false, false));
        screen.apply(new GridEvent.HlAttr(8, 0x00FF00, GridEvent.NO_COLOR, false, false, false, false, false));
        screen.apply(new GridEvent.Resize(ScreenGrid.SCREEN, 2, 1));
        screen.apply(new GridEvent.Line(ScreenGrid.SCREEN, 0, 0, text("E", 7)));
        GridSnapshot s = screen.snapshotIfChanged().orElseThrow();
        assertEquals(0xFF0000, s.attr(7).foreground());
        assertTrue(s.attr(7).bold());
        assertTrue(!s.attrs().containsKey(8), "highlight 8 is on no cell");
    }

    @Test
    void aCursorOffTheScreenIsLeftOut() {
        screen.apply(new GridEvent.Resize(ScreenGrid.SCREEN, 2, 2));
        screen.apply(new GridEvent.CursorGoto(ScreenGrid.SCREEN, 5, 0)); // before a resize that will make room
        GridSnapshot s = screen.snapshotIfChanged().orElseThrow();
        assertEquals(-1, s.cursorRow());
        assertEquals(-1, s.cursorCol());
    }
}
