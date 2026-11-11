// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** {@link ViewportSize}: Neovim's grid from the editor's size and what Neovim draws besides the text. */
class ViewportSizeTest {

    @Test
    void theGridIsTheEditorPlusWhatNeovimDrawsAroundItsWindow() {
        // Neovim's default status line (1 row) and set number (4 columns).
        assertEquals(new ViewportSize(104, 41), ViewportSize.forEditor(100, 40, new ViewportSize.Overhead(1, 4)));
        // laststatus=0 and no number column: the grid is the editor.
        assertEquals(new ViewportSize(100, 40), ViewportSize.forEditor(100, 40, ViewportSize.Overhead.NONE));
    }

    /**
     * One column and two rows of text at least: with one row, Neovim could not open the next
     * editor's window (E36, see {@link ViewportSize#MIN_TEXT_ROWS}). Example: an editor being closed
     * measures 0 × 0; with the default status line the grid is 5 × 3, not 5 × 2.
     */
    @Test
    void aCollapsedEditorStillGetsOneColumnAndTwoRowsOfText() {
        assertEquals(new ViewportSize(5, 3), ViewportSize.forEditor(0, 0, new ViewportSize.Overhead(1, 4)));
        assertEquals(new ViewportSize(1, 2), ViewportSize.forEditor(-3, -1, ViewportSize.Overhead.NONE));
        assertEquals(new ViewportSize(100, 3), ViewportSize.forEditor(100, 1, new ViewportSize.Overhead(1, 0)),
                "a one-line editor too");
        assertEquals(new ViewportSize(100, 3), ViewportSize.forEditor(100, 2, new ViewportSize.Overhead(1, 0)),
                "two lines are kept as they are");
    }

    @Test
    void impossibleSizesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new ViewportSize(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new ViewportSize.Overhead(-1, 0));
    }

    /** Whole characters and whole lines: a partly visible last line or column does not count, as in Vim. */
    @Test
    void anEditorsCellsAreWholeCharactersAndWholeLines() {
        assertArrayEquals(new int[] { 80, 40 }, ViewportSize.editorCells(720, 520, 9, 13));
        assertArrayEquals(new int[] { 80, 40 }, ViewportSize.editorCells(728, 525, 9, 13), "8 and 5 pixels left over");
        assertArrayEquals(new int[] { 80, 40 }, ViewportSize.editorCells(600, 520, 7.5, 13), "a fractional width");
    }

    /** Never below one cell: before the font is measured, or with margins that leave no room. */
    @Test
    void anEditorAlwaysHasOneCell() {
        assertArrayEquals(new int[] { 1, 40 }, ViewportSize.editorCells(720, 520, 0, 13), "no character width yet");
        assertArrayEquals(new int[] { 80, 1 }, ViewportSize.editorCells(720, 520, 9, 0), "no line height yet");
        assertArrayEquals(new int[] { 1, 1 }, ViewportSize.editorCells(0, 0, 9, 13));
        assertArrayEquals(new int[] { 1, 1 }, ViewportSize.editorCells(-20, -4, 9, 13), "margins wider than the area");
    }

    @Test
    void itPrintsAsColumnsByRows() {
        assertEquals("104×41", new ViewportSize(104, 41).toString());
    }
}
