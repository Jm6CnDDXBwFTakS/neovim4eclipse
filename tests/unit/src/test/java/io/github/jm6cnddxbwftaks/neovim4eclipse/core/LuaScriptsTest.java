// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
        // Some lines (:5,10w part.txt) and appends (:w >> log.txt) have hooks of their own.
        assertTrue(LuaScripts.DOCUMENT_BUFFER.contains("FileWriteCmd = 'lines', FileAppendCmd = 'append'"));
        assertTrue(LuaScripts.DOCUMENT_BUFFER.contains("'eclipse_write'"));
        // Both names are compared canonical: links resolved (macOS /var), slashes normalized (Windows / against \).
        assertTrue(LuaScripts.DOCUMENT_BUFFER.contains("vim.fs.normalize(vim.fn.resolve(name))"));
        // Writes are compared with the editor's file, kept when the buffer is made, not with the
        // buffer's name, which :saveas and :file change.
        assertTrue(LuaScripts.DOCUMENT_BUFFER.contains("vim.b[buf].neovim4eclipse_file = vim.api.nvim_buf_get_name(buf)"));
        assertTrue(LuaScripts.SETUP.contains("vim.g.neovim4eclipse_channel = chan"), "the channel the request goes to");
    }

    /**
     * The plug-in's own errors are shown with {@code nvim_echo}'s {@code err = true}, which sets
     * {@code v:errmsg} and marks the message as an error ({@code msg_show} kind {@code "echoerr"}).
     * Setting {@code v:errmsg} by hand with an {@code ErrorMsg} echo would arrive as an ordinary
     * message ({@code "echomsg"}) for a UI that reads message kinds.
     */
    @Test
    void errorsAreEchoedAsErrors() {
        for (String script : new String[] { LuaScripts.NORMAL_WITHOUT_MAPPINGS, LuaScripts.DOCUMENT_BUFFER }) {
            assertTrue(script.contains("vim.api.nvim_echo({ { msg } }, true, { err = true })"), script);
            assertFalse(script.contains("vim.v.errmsg = msg"), "no v:errmsg set by hand: " + script);
            assertFalse(script.contains("'ErrorMsg'"), "err = true picks the highlight: " + script);
        }
    }

    /** {@code q:} opens a window Eclipse cannot show: closed at once, with a message saying so. */
    @Test
    void theCommandLineWindowIsClosedWithAMessage() {
        assertTrue(LuaScripts.SETUP.contains("'CmdwinEnter'"));
        assertTrue(LuaScripts.SETUP.contains("vim.schedule(function()"), "it cannot close inside its own CmdwinEnter");
        assertTrue(LuaScripts.SETUP.contains("vim.cmd.quit()"));
        assertTrue(LuaScripts.SETUP.contains("Up and Down in the : box browse the history"));
    }

    @Test
    void pasteAndTheWindowFolderUseNeovimsOwnCalls() {
        assertTrue(LuaScripts.PASTE.contains("vim.api.nvim_paste(select(1, ...), false, -1)"));
        assertTrue(LuaScripts.SET_WINDOW_FOLDER.contains("vim.cmd.lcd({ args = { select(1, ...) }, mods = { silent = true } })"),
                "silent: Neovim would echo the folder over the message being read");
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
    void snapshotOnlyEvaluatesTheStatuslineWhenRequested() {
        assertTrue(LuaScripts.STATE_SNAPSHOT.contains("select(1, ...) == '1'"));
        assertTrue(LuaScripts.STATE_SNAPSHOT.contains("if want_statusline then"));
        assertFalse(LuaScripts.STATE_SNAPSHOT.contains("select(2"), "one argument");
    }

    /**
     * Messages and showcmd arrive as UI events (ext_messages): the snapshot no longer reads or clears
     * v:statusmsg and v:errmsg, nor evaluates %S, and 'showcmdloc' stays at its default ("last"),
     * where Neovim sends msg_showcmd.
     */
    @Test
    void messagesAndShowcmdAreNotPolled() {
        assertTrue(LuaScripts.STATE_SNAPSHOT.contains("nvim_eval_statusline"));
        for (String gone : new String[] { "statusmsg", "errmsg", "'%S'" }) {
            assertFalse(LuaScripts.STATE_SNAPSHOT.contains(gone), gone);
        }
        assertFalse(LuaScripts.SETUP.contains("showcmdloc"));
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
        assertTrue(LuaScripts.STATE_SNAPSHOT.contains("block, vim.api.nvim_get_mode().mode }"), "the full mode, last");
        // A block's lines come from Neovim; no curswant or tabstop for Java to compute them from.
        assertTrue(LuaScripts.STATE_SNAPSHOT.contains("vim.fn.getregionpos(s, e, { type = mode, eol = true })"));
        assertTrue(LuaScripts.STATE_SNAPSHOT.contains("if mode == string.char(22) then"), "blocks only");
        assertFalse(LuaScripts.STATE_SNAPSHOT.contains("curswant"));
        assertFalse(LuaScripts.STATE_SNAPSHOT.contains("tabstop"));
        assertTrue(LuaScripts.STATE_SNAPSHOT.contains("reg_recording()"));
    }

    @Test
    void cursorReportsSayWhichBufferTheyAreAbout() {
        assertTrue(LuaScripts.STATE_SNAPSHOT.contains("nvim_get_current_buf()"));
        assertTrue(LuaScripts.SETUP.contains("'eclipse_cursor', p[1], p[2], vim.api.nvim_get_current_buf()"));
    }
}
