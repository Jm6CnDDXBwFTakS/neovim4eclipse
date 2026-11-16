// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Collections;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.FloatPlacement.Box;

/**
 * Where a floating window goes in the editor: cells of 10x20 pixels in an editor of 805x470, whose
 * Neovim window has 80 columns and 23 rows (the last 5 and 10 pixels belong to no whole cell).
 */
class FloatPlacementTest {

    private static final int CELL_W = 10;
    private static final int CELL_H = 20;
    private static final int AREA_W = 805;
    private static final int AREA_H = 470;

    private static Box place(int columns, int rows, int row, int col) {
        GridSnapshot grid = new GridSnapshot(1002, columns, rows, Collections.nCopies(columns * rows, " "),
                new int[columns * rows], Map.of(), new GridEvent.DefaultColors(GridEvent.NO_COLOR, GridEvent.NO_COLOR), -1, -1);
        return FloatPlacement.place(new FloatWindow(grid, row, col, 23, 80, 50), CELL_W, CELL_H, AREA_W, AREA_H);
    }

    @Test
    void aFloatInTheMiddleGoesByItsCells() {
        assertEquals(new Box(40, 120, 140, 40), place(14, 2, 6, 4));
    }

    /** which-key's panel: the screen's full width, the window's last 5 rows. */
    @Test
    void aFloatThatReachesTheLastRowSitsOnTheBottomEdge() {
        assertEquals(new Box(5, 370, 800, 100), place(80, 5, 18, 0));
    }

    @Test
    void aFloatThatReachesTheLastColumnSitsOnTheRightEdge() {
        assertEquals(new Box(645, 0, 160, 80), place(16, 4, 0, 64));
    }

    /** Rows 20 and 21 of 23: not the last row, but Eclipse's area ends before row 22 would. */
    @Test
    void aFloatThatWouldStickOutIsMovedBackIn() {
        Box box = FloatPlacement.place(new FloatWindow(place0(14, 2), 20, 4, 0, 0, 50), CELL_W, CELL_H, AREA_W, 430);
        assertEquals(new Box(40, 390, 140, 40), box);
    }

    @Test
    void aFloatLargerThanTheEditorKeepsItsTopLeftCornerInView() {
        assertEquals(new Box(0, 0, 2000, 1200), place(200, 60, 0, 0));
    }

    @Test
    void aFloatAboveOrLeftOfTheWindowStartsAtTheEdge() {
        assertEquals(new Box(0, 0, 140, 40), place(14, 2, -1, -3));
    }

    private static GridSnapshot place0(int columns, int rows) {
        return new GridSnapshot(1002, columns, rows, Collections.nCopies(columns * rows, " "), new int[columns * rows],
                Map.of(), new GridEvent.DefaultColors(GridEvent.NO_COLOR, GridEvent.NO_COLOR), -1, -1);
    }
}
