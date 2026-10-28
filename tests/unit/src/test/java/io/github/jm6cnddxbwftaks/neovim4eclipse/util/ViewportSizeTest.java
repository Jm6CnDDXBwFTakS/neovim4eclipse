// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

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

    @Test
    void aCollapsedEditorStillGetsOneCellOfText() {
        assertEquals(new ViewportSize(5, 2), ViewportSize.forEditor(0, 0, new ViewportSize.Overhead(1, 4)));
        assertEquals(new ViewportSize(1, 1), ViewportSize.forEditor(-3, -1, ViewportSize.Overhead.NONE));
    }

    @Test
    void impossibleSizesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new ViewportSize(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new ViewportSize.Overhead(-1, 0));
    }

    @Test
    void itPrintsAsColumnsByRows() {
        assertEquals("104×41", new ViewportSize(104, 41).toString());
    }
}
