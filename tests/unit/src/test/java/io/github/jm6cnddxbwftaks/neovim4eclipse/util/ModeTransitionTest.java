// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.util.ModeTransition.ENTER_INSERT;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.util.ModeTransition.ENTER_NEOVIM_INSERT;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.util.ModeTransition.LEAVE_NEOVIM_INSERT;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.util.ModeTransition.NONE;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.util.ModeTransition.TEXT_CHANGED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Each case names the Vim keys that produce it. */
class ModeTransitionTest {

    private static final String BLOCK = String.valueOf(VimModes.CTRL_V);

    private static ModeTransition after(String before, String now, String key) {
        return ModeTransition.after(before, now, false, false, false, key);
    }

    @Test
    void enteringInsertFromNormalOrVisualIsTypedByEclipse() {
        assertEquals(ENTER_INSERT, after("n", "i", "o"));
        assertEquals(ENTER_INSERT, after("n", "R", "R"));
        assertEquals(ENTER_INSERT, after("v", "i", "c"));
        assertEquals(ENTER_INSERT, after("no", "i", "w")); // cw: the motion ends operator-pending
    }

    @Test
    void enteringInsertFromABlockSelectionIsTypedByNeovim() {
        assertEquals(ENTER_NEOVIM_INSERT, after(BLOCK, "i", "I"));
        assertEquals(ENTER_NEOVIM_INSERT, after(BLOCK, "i", "A"));
    }

    @Test
    void enteringInsertWhileRecordingIsTypedByNeovim() {
        assertEquals(ENTER_NEOVIM_INSERT, ModeTransition.after("n", "i", false, true, false, "i"));
    }

    /**
     * A picker's prompt (Telescope): the float is current when the insert begins.
     * Its buffer is no document, so Neovim types the whole insert, as after a block selection.
     */
    @Test
    void enteringInsertInAFocusedFloatIsTypedByNeovim() {
        assertEquals(ENTER_NEOVIM_INSERT, ModeTransition.after("n", "i", false, false, true, "i"));
        assertEquals(ENTER_NEOVIM_INSERT, ModeTransition.after("c", "i", false, false, true, "<CR>")); // :Telescope<CR>
        assertEquals(ENTER_INSERT, ModeTransition.after("n", "i", false, false, false, "i"), "the editor's window: Eclipse types");
    }

    /** The float matters only where an insert begins: one going on stays as it is. */
    @Test
    void aFocusedFloatChangesNoInsertAlreadyGoingOn() {
        assertEquals(NONE, ModeTransition.after("i", "i", false, false, true, "a"));
        assertEquals(LEAVE_NEOVIM_INSERT, ModeTransition.after("i", "n", true, false, true, "<Esc>"));
    }

    @Test
    void leavingANeovimTypedInsertResyncs() {
        assertEquals(LEAVE_NEOVIM_INSERT, ModeTransition.after("i", "n", true, false, false, "<Esc>"));
    }

    @Test
    void leavingAnEclipseTypedInsertNeedsNothingHere() {
        // Eclipse's text reaches Neovim by the push at Esc, not by a sync from Neovim.
        assertEquals(NONE, ModeTransition.after("i", "n", false, false, false, "<Esc>"));
    }

    @Test
    void textChangingKeysInNormalModeResync() {
        for (String key : new String[] { "p", "P", "x", "X", "~", ".", "u", "<C-r>" }) {
            assertEquals(TEXT_CHANGED, after("n", "n", key), key);
        }
        assertEquals(TEXT_CHANGED, after("v", "n", "x")); // x on a selection
    }

    @Test
    void motionsAndStayingInInsertNeedNothing() {
        assertEquals(NONE, after("n", "n", "j"));
        assertEquals(NONE, after("n", "v", "v"));
        assertEquals(NONE, ModeTransition.after("i", "i", true, false, false, "a")); // Neovim typing on
    }

    @Test
    void keysTypedByNeovimInsideInsertAreNotTextChanges() {
        // "x" while Neovim types the insert is text, but line events carry it.
        assertEquals(NONE, ModeTransition.after("i", "i", true, false, false, "x"));
    }

    @Test
    void enteringInsertWinsOverATextChangingKey() {
        assertEquals(ENTER_INSERT, after("n", "i", "s")); // s deletes and enters insert
    }

    @Test
    void onlyNoneSkipsTheSync() {
        assertFalse(NONE.syncsBuffer());
        for (ModeTransition t : ModeTransition.values()) {
            if (t != NONE) {
                assertTrue(t.syncsBuffer(), t.name());
            }
        }
    }
}
