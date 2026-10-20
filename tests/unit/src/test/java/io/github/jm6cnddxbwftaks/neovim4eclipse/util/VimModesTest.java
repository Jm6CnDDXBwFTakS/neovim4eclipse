// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void statusTextShowsTheRecordingRegister() {
        assertEquals("-- NORMAL --  recording @a", VimModes.statusText("n", "a"));
        assertEquals("-- INSERT --  recording @q", VimModes.statusText("i", "q"));
        assertEquals("-- NORMAL --", VimModes.statusText("n", ""));
        assertEquals("-- NORMAL --", VimModes.statusText("n", null));
    }

    @Test
    void statusText() {
        assertEquals("-- NORMAL --", VimModes.statusText("n"));
        assertEquals("-- NORMAL --", VimModes.statusText(null));
        assertEquals("-- NORMAL --", VimModes.statusText(""));
        assertEquals("-- OPERATOR --", VimModes.statusText("no"));
        assertEquals("-- OPERATOR --", VimModes.statusText("nov"));
        assertEquals("-- INSERT --", VimModes.statusText("i"));
        assertEquals("-- INSERT --", VimModes.statusText("ic"));
        assertEquals("-- REPLACE --", VimModes.statusText("R"));
        assertEquals("-- VISUAL --", VimModes.statusText("v"));
        assertEquals("-- VISUAL LINE --", VimModes.statusText("V"));
        assertEquals("-- VISUAL BLOCK --", VimModes.statusText(BLOCK));
        assertEquals("-- SELECT --", VimModes.statusText("s"));
        assertEquals("-- COMMAND --", VimModes.statusText("c"));
        assertEquals("-- TERMINAL --", VimModes.statusText("t"));
        assertEquals("-- ? --", VimModes.statusText("?"));
    }
}
