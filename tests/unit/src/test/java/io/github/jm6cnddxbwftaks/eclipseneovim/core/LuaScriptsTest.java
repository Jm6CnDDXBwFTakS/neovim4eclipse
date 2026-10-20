// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Cheap structural checks; real behavior is covered by the Neovim integration test. */
class LuaScriptsTest {

    @Test
    void setupScriptRegistersTheNotificationsTheBridgeHandles() {
        for (String name : new String[] { "eclipse_mode", "eclipse_cursor", "eclipse_buf_enter", "eclipse_yank",
                "eclipse_clipboard_copy", "eclipse_clipboard_paste" }) {
            assertTrue(LuaScripts.SETUP.contains("'" + name + "'"), name);
        }
    }

    /** Document buffers are written only through Eclipse: acwrite, and a write hook that asks it to save. */
    @Test
    void documentBuffersAreWrittenThroughEclipse() {
        assertTrue(LuaScripts.DOCUMENT_BUFFER.contains("buftype = 'acwrite'"));
        assertTrue(LuaScripts.DOCUMENT_BUFFER.contains("'BufWriteCmd'"));
        assertTrue(LuaScripts.DOCUMENT_BUFFER.contains("'eclipse_write'"));
        assertTrue(LuaScripts.DOCUMENT_BUFFER.contains("vim.fs.normalize(ev.match)"), "Windows: / against \\");
        assertTrue(LuaScripts.SETUP.contains("vim.g.eclipse_channel = chan"), "the channel the request goes to");
    }

    /** See the COMPLETE_CMDLINE comment for why each of these matters. */
    @Test
    void completionKeysAreNotTypedAndEverythingChangedIsRestored() {
        String script = LuaScripts.COMPLETE_CMDLINE;
        assertTrue(script.contains("'xn', false)"), "not typed: no macro recording, no '=' history");
        assertTrue(script.contains("nvim_feedkeys(':' .. line, 'n', true)"), "the line fed as text");
        assertTrue(script.contains("vim.o.wildcharm = 9"), "Tab completes in keys that are not typed");
        assertTrue(script.contains("[[<C-\\>ev:lua.__eclipse_capture()<CR><C-c>]]"));
        assertTrue(script.contains("mode.mode ~= 'n' or mode.blocking"), "':' is typed in normal mode only");
        assertTrue(script.contains("local ok, err = pcall(complete)"));
        for (String restore : new String[] { "vim.o.wildmode = saved.wildmode", "vim.o.wildcharm = saved.wildcharm",
                "vim.o.eventignore = saved.eventignore", "_G.__eclipse_capture = nil", "noautocmd lcd ' .. back",
                "noautocmd tcd ' .. back", "noautocmd cd ' .. back" }) {
            assertTrue(script.contains(restore), restore);
        }
    }

    @Test
    void yankHookStandsDownWhenNeovimSyncsTheClipboardItself() {
        // Regression guard for linewise yanks turning charwise (P pasting inline) when two writers raced.
        assertTrue(LuaScripts.SETUP.contains("vim.o.clipboard:find('unnamed'"));
        // "+y goes to the provider; the yank hook must not write the clipboard a second time.
        assertTrue(LuaScripts.SETUP.contains("e.regname == '+' or e.regname == '*'"));
    }

    @Test
    void theClipboardProviderIsInstalledOnlyWhenTheUserHasNone() {
        assertTrue(LuaScripts.SETUP.contains("if vim.g.clipboard == nil then"));
        assertTrue(LuaScripts.SETUP.contains("vim.g.loaded_clipboard_provider = nil"));
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
