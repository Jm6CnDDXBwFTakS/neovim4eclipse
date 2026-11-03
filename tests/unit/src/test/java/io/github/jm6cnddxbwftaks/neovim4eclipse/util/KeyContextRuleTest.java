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

    /** Neovim's completion popup is not drawn: while Eclipse types, Ctrl-N and Ctrl-P are Eclipse's Word Completion. */
    @Test
    void ctrlNAndCtrlPRunWordCompletionWhileEclipseTypes() {
        assertEquals(VimCtrlKeys.completionContextId('n'), KeyContextRule.contextId('n', Column.INSERT, true));
        assertEquals(VimCtrlKeys.completionContextId('p'), KeyContextRule.contextId('p', Column.INSERT, true));
        assertEquals(VimCtrlKeys.contextId('w'), KeyContextRule.contextId('w', Column.INSERT, true));
    }

    @Test
    void ctrlNAndCtrlPAreNeovimsWhereNeovimTypesOrInNormalMode() {
        assertEquals(VimCtrlKeys.contextId('n'), KeyContextRule.contextId('n', Column.INSERT, false), "Neovim drives");
        assertEquals(VimCtrlKeys.contextId('n'), KeyContextRule.contextId('n', Column.NORMAL, true), "normal mode");
    }

    @Test
    void theCommandLineBoxUsesTheNormalChoices() {
        assertEquals(Column.NORMAL, KeyContextRule.column("i", true));
        assertEquals(Column.NORMAL, KeyContextRule.column("c", true));
    }
}
