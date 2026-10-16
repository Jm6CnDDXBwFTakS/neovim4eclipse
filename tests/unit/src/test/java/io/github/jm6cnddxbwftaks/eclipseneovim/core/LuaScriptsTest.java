// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Cheap structural checks; real behavior is covered by the Neovim integration test. */
class LuaScriptsTest {

    @Test
    void setupScriptRegistersTheNotificationsTheBridgeHandles() {
        for (String name : new String[] { "eclipse_mode", "eclipse_cursor", "eclipse_buf_enter", "eclipse_yank" }) {
            assertTrue(LuaScripts.SETUP.contains("'" + name + "'"), name);
        }
    }

    @Test
    void yankHookStandsDownWhenNeovimSyncsTheClipboardItself() {
        // Regression guard for linewise yanks turning charwise (P pasting inline) when two writers raced.
        assertTrue(LuaScripts.SETUP.contains("vim.o.clipboard:find('unnamed'"));
    }

    @Test
    void snapshotOnlyEvaluatesTheStatusPartsThatAreRequested() {
        assertTrue(LuaScripts.STATE_SNAPSHOT.contains("select(1, ...) == '1'"));
        assertTrue(LuaScripts.STATE_SNAPSHOT.contains("select(2, ...) == '1'"));
        assertTrue(LuaScripts.STATE_SNAPSHOT.contains("select(3, ...) == '1'"));
        assertTrue(LuaScripts.STATE_SNAPSHOT.contains("if want_statusline then"));
        assertTrue(LuaScripts.STATE_SNAPSHOT.contains("if want_cmd then"));
        assertTrue(LuaScripts.STATE_SNAPSHOT.contains("if want_msg then"));
    }

    @Test
    void snapshotUsesNeovimsOwnStatuslineAndShowcmdAndClearsMessagesAfterReading() {
        assertTrue(LuaScripts.STATE_SNAPSHOT.contains("nvim_eval_statusline"));
        assertTrue(LuaScripts.STATE_SNAPSHOT.contains("'%S'"));
        assertTrue(LuaScripts.STATE_SNAPSHOT.contains("vim.v.statusmsg = ''"));
        assertTrue(LuaScripts.STATE_SNAPSHOT.contains("vim.v.errmsg = ''"));
    }

    @Test
    void showcmdIsRoutedToTheStatuslineSoItCanBeRead() {
        assertTrue(LuaScripts.SETUP.contains("showcmdloc"));
    }

    @Test
    void insertModeMappingsAndAbbreviationsAreClearedSoReplayedTextIsNotHijacked() {
        assertTrue(LuaScripts.SETUP.contains("imapclear"));
        assertTrue(LuaScripts.SETUP.contains("iabclear"));
    }

    @Test
    void stateSnapshotIsOneScriptReturningCursorVisualBoundsAndMode() {
        assertTrue(LuaScripts.STATE_SNAPSHOT.contains("nvim_win_get_cursor"));
        assertTrue(LuaScripts.STATE_SNAPSHOT.contains("getpos('v')"));
        assertTrue(LuaScripts.STATE_SNAPSHOT.contains("getpos('.')"));
        assertTrue(LuaScripts.STATE_SNAPSHOT.contains("vim.fn.mode()"));
        assertTrue(LuaScripts.STATE_SNAPSHOT.contains("curswant"));
        assertTrue(LuaScripts.STATE_SNAPSHOT.contains("vim.bo.tabstop"));
        assertTrue(LuaScripts.STATE_SNAPSHOT.contains("reg_recording()"));
    }

    @Test
    void cursorReportsSayWhichBufferTheyAreAbout() {
        assertTrue(LuaScripts.STATE_SNAPSHOT.contains("nvim_get_current_buf()"));
        assertTrue(LuaScripts.SETUP.contains("'eclipse_cursor', p[1], p[2], vim.api.nvim_get_current_buf()"));
    }
}
