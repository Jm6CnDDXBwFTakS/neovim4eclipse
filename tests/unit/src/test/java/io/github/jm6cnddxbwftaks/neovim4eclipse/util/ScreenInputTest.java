// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The Neovim view's size in cells and its title. */
class ScreenInputTest {

    @Test
    void onlyWholeCellsCountAndNeverFewerThanOne() {
        assertEquals(100, ScreenInput.cells(803, 8));
        assertEquals(1, ScreenInput.cells(0, 8), "a view squeezed to nothing");
        assertEquals(1, ScreenInput.cells(5, 8));
        assertEquals(40, ScreenInput.cells(40, 0), "a cell of no size counts as one pixel");
    }

    @Test
    void theTitleSaysWhatTheViewHolds() {
        assertEquals("Neovim: messages", ScreenInput.title("messages"));
        assertEquals("Neovim: checkhealth", ScreenInput.title("checkhealth"));
        assertEquals("Neovim", ScreenInput.title(""));
        assertEquals("Neovim", ScreenInput.title(null));
    }
}
