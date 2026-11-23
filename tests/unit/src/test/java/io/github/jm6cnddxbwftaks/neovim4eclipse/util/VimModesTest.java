// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class VimModesTest {

    private static final String BLOCK = "\u0016";

    @Test
    void visualModes() {
        assertTrue(VimModes.isVisual("v"));
        assertTrue(VimModes.isVisual("V"));
        assertTrue(VimModes.isVisual(BLOCK));
        assertFalse(VimModes.isVisual("n"));
        assertFalse(VimModes.isVisual("i"));
        assertFalse(VimModes.isVisual(null));
        assertFalse(VimModes.isVisual(""));
    }

    /** Ex mode ({@code gQ}) is {@code cv} and {@code cvr}; a plain command line is not. */
    @Test
    void exMode() {
        assertTrue(VimModes.isExMode("cv"));
        assertTrue(VimModes.isExMode("cvr"));
        for (String m : new String[] { "c", "cr", "n", "", null }) {
            assertFalse(VimModes.isExMode(m), String.valueOf(m));
        }
    }

    @Test
    void operatorPendingBlockwiseIsNotVisual() {
        // Regression: "no<C-v>" contains Ctrl-V but is operator-pending, not visual.
        assertFalse(VimModes.isVisual("no" + BLOCK));
        assertTrue(VimModes.isOperatorPending("no" + BLOCK));
    }

    @Test
    void typingModesAreInsertAndReplaceIncludingSubModes() {
        for (String m : new String[] { "i", "ic", "ix", "R", "Rc", "Rv", "Rx" }) {
            assertTrue(VimModes.isTyping(m), m);
        }
        for (String m : new String[] { "n", "no", "niI", "v", "c", "t", "", null }) {
            assertFalse(VimModes.isTyping(m), String.valueOf(m));
        }
    }

    @Test
    void cmdlineAndThinCaret() {
        assertTrue(VimModes.isCmdline("c"));
        assertTrue(VimModes.isCmdline("cv"));
        assertFalse(VimModes.isCmdline("n"));
        assertTrue(VimModes.usesThinCaret("i"));
        assertTrue(VimModes.usesThinCaret("c"));
        assertFalse(VimModes.usesThinCaret("n"));
        assertFalse(VimModes.usesThinCaret("v"));
    }

    @Test
    void blockVisual() {
        assertTrue(VimModes.isBlockVisual(BLOCK));
        assertFalse(VimModes.isBlockVisual("v"));
        assertFalse(VimModes.isBlockVisual(null));
    }

    /** {@code ic}, {@code ix}: Neovim completes, or waits after Ctrl-X; with or without a list on the screen. */
    @Test
    void completingIsInsertOrReplaceWithACompletionUnderWay() {
        assertTrue(VimModes.isCompleting("ic"));
        assertTrue(VimModes.isCompleting("ix"));
        assertTrue(VimModes.isCompleting("Rc"));
        assertTrue(VimModes.isCompleting("Rx"));
        assertTrue(VimModes.isCompleting("Rvc"), "virtual replace, completing");
        assertTrue(VimModes.isCompleting("Rvx"));
        assertFalse(VimModes.isCompleting("i"));
        assertFalse(VimModes.isCompleting("R"));
        assertFalse(VimModes.isCompleting("Rv"), "virtual replace, not a completion");
        assertFalse(VimModes.isCompleting("c"), "the command line");
        assertFalse(VimModes.isCompleting("niI"));
        assertFalse(VimModes.isCompleting(null));
        assertFalse(VimModes.isCompleting(""));
    }

    /** Terminal mode and Normal mode in a terminal buffer; Normal and operator-pending are neither. */
    @Test
    void terminalModes() {
        for (String mode : List.of("t", "nt", "ntT")) {
            assertTrue(VimModes.isTerminal(mode), mode);
        }
        for (String mode : List.of("n", "no", "nov", "niI", "i", "c")) {
            assertFalse(VimModes.isTerminal(mode), mode);
        }
        assertFalse(VimModes.isTerminal(null));
    }
}
