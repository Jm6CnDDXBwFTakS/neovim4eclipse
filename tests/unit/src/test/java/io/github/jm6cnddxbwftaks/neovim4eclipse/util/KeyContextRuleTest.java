// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.KeyContextRule.Column;

/** When the Vim key contexts are on, and which letters ({@link KeyContextRule}). */
class KeyContextRuleTest {

    @Test
    void onWhileNeovimOwnsTheKeysOfTheFocusedEditor() {
        assertTrue(KeyContextRule.wanted(true, true, false));
        assertFalse(KeyContextRule.wanted(false, true, false), "no focus");
        assertFalse(KeyContextRule.wanted(true, false, false), "Eclipse mode");
    }

    @Test
    void theCommandLineBoxKeepsThemOn() {
        assertTrue(KeyContextRule.wanted(false, true, true));
        assertFalse(KeyContextRule.wanted(false, false, true), "Eclipse mode");
    }

    /** Ctrl-W in insert mode is Vim's delete word only if its Insert box is ticked. */
    @Test
    void insertAndReplaceModeUseTheInsertChoices() {
        assertEquals(Column.INSERT, KeyContextRule.column("i", false));
        assertEquals(Column.INSERT, KeyContextRule.column("ic", false), "completion submode");
        assertEquals(Column.INSERT, KeyContextRule.column("R", false));
    }

    @Test
    void otherModesUseTheNormalChoices() {
        assertEquals(Column.NORMAL, KeyContextRule.column("n", false));
        assertEquals(Column.NORMAL, KeyContextRule.column("niI", false), "Ctrl-O's one normal command");
        assertEquals(Column.NORMAL, KeyContextRule.column("v", false));
        assertEquals(Column.NORMAL, KeyContextRule.column("no", false));
        assertEquals(Column.NORMAL, KeyContextRule.column(null, false), "mode not known yet");
    }

    /** With Neovim's completion list turned off: while Eclipse types, Ctrl-N and Ctrl-P are Eclipse's Word Completion. */
    @Test
    void ctrlNAndCtrlPRunWordCompletionWhileEclipseTypesWithNeovimsListOff() {
        assertEquals(VimCtrlKeys.completionContextId('n'), KeyContextRule.contextId('n', Column.INSERT, true, false));
        assertEquals(VimCtrlKeys.completionContextId('p'), KeyContextRule.contextId('p', Column.INSERT, true, false));
        assertEquals(VimCtrlKeys.contextId('w'), KeyContextRule.contextId('w', Column.INSERT, true, false));
    }

    /** With Neovim's completion, the letters' own bindings send them to the plug-in, which starts it. */
    @Test
    void ctrlNAndCtrlPAreNeovimsWithItsCompletion() {
        assertEquals(VimCtrlKeys.contextId('n'), KeyContextRule.contextId('n', Column.INSERT, true, true));
        assertEquals(VimCtrlKeys.contextId('p'), KeyContextRule.contextId('p', Column.INSERT, true, true));
    }

    @Test
    void ctrlNAndCtrlPAreNeovimsWhereNeovimTypesOrInNormalMode() {
        assertEquals(VimCtrlKeys.contextId('n'), KeyContextRule.contextId('n', Column.INSERT, false, false), "Neovim drives");
        assertEquals(VimCtrlKeys.contextId('n'), KeyContextRule.contextId('n', Column.NORMAL, true, false), "normal mode");
    }

    /**
     * While Neovim completes, every letter is its own in insert mode: Ctrl-Y accepts and Ctrl-E
     * cancels, though neither is ticked on the Ctrl Keys page. Not in normal mode, and a ticked
     * letter stays ticked.
     */
    @Test
    void everyLetterGoesToNeovimWhileItCompletes() {
        assertTrue(KeyContextRule.toNeovim(false, Column.INSERT, true, false), "Ctrl-Y, not ticked, while completing");
        assertFalse(KeyContextRule.toNeovim(false, Column.INSERT, false, false), "Ctrl-Y otherwise: Eclipse's Redo");
        assertFalse(KeyContextRule.toNeovim(false, Column.NORMAL, true, false), "the choice for normal mode stands");
        assertTrue(KeyContextRule.toNeovim(true, Column.INSERT, false, false));
        assertTrue(KeyContextRule.toNeovim(true, Column.NORMAL, false, false));
    }

    /**
     * In a plug-in's window every letter is Neovim's, in every mode, also one left to Eclipse:
     * {@code Ctrl+F} in a picker's prompt must not open Find/Replace on the document behind it.
     */
    @Test
    void everyLetterGoesToNeovimInAPluginsWindow() {
        assertTrue(KeyContextRule.toNeovim(false, Column.INSERT, false, true), "the prompt, in its insert");
        assertTrue(KeyContextRule.toNeovim(false, Column.NORMAL, false, true), "a picker's prompt after Esc");
    }

    @Test
    void theCommandLineBoxUsesTheNormalChoices() {
        assertEquals(Column.NORMAL, KeyContextRule.column("i", true));
        assertEquals(Column.NORMAL, KeyContextRule.column("c", true));
    }
}
