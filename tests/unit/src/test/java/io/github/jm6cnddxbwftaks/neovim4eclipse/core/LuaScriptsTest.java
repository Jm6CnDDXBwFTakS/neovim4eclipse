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
        assertTrue(LuaScripts.DOCUMENT_BUFFER.contains("'eclipse_write'"));
        // Both names are compared canonical: links resolved (macOS /var), slashes normalized (Windows / against \).
        assertTrue(LuaScripts.DOCUMENT_BUFFER.contains("vim.fs.normalize(vim.fn.resolve(name))"));
        assertTrue(LuaScripts.DOCUMENT_BUFFER.contains(
                "canonical(ev.match) ~= canonical(vim.api.nvim_buf_get_name(ev.buf))"));
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
