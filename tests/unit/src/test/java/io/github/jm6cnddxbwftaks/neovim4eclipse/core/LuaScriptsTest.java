// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Cheap structural checks; real behavior is covered by the Neovim integration test. Each check
 * reads a script's code only ({@link #code}): the files in {@code core/lua/} carry their comments,
 * and a comment that names what a check looks for must not pass it, nor one that names what a
 * check refuses fail it.
 */
class LuaScriptsTest {

    /**
     * {@code lua} without its comments: on each line, from a {@code --} outside a string to the
     * line's end, and the white space before it. Lines stay where they are. Example:
     * {@code x = 1 -- y} becomes {@code x = 1}.
     *
     * <p>Line comments only: a block comment ({@code --[[ ... ]]}, {@code --[==[ ... ]==]}) is
     * refused, since its lines after the first would read as code here, and a comment could pass a
     * check again. The plug-in's Lua has none; write one as {@code --} lines instead.
     *
     * @throws IllegalArgumentException for a block comment
     */
    static String code(String lua) {
        StringBuilder out = new StringBuilder(lua.length());
        for (String line : lua.split("\n", -1)) {
            int cut = commentStart(line);
            out.append(cut < 0 ? line : line.substring(0, cut).stripTrailing()).append('\n');
        }
        return out.substring(0, out.length() - 1);
    }

    /** The start of a Lua block comment: {@code --[[} or {@code --[=[}, any number of {@code =}. */
    private static final Pattern BLOCK_COMMENT = Pattern.compile("--\\[=*\\[");

    /** Where the comment on {@code line} starts, or -1: a {@code --} outside quotes and {@code [[ ]]}. */
    private static int commentStart(String line) {
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++; // an escaped character, a quote among them
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (line.startsWith("[[", i)) {
                int end = line.indexOf("]]", i + 2);
                if (end < 0) {
                    return -1; // a long string going on past this line: no comment to find here
                }
                i = end + 1;
            } else if (line.startsWith("--", i)) {
                if (BLOCK_COMMENT.matcher(line).region(i, line.length()).lookingAt()) {
                    throw new IllegalArgumentException("A block comment, which code() cannot read: " + line);
                }
                return i;
            }
        }
        return -1;
    }

    /** The helper itself: comments go, strings that hold -- stay, and so does every line. */
    @Test
    void aScriptsCodeIsItsTextWithoutComments() {
        assertEquals("x = 1\n\ns = '--' .. \"a--\" .. [[--]]\nt = 'it\\'s'",
                code("x = 1 -- y\n-- z\ns = '--' .. \"a--\" .. [[--]] -- c\nt = 'it\\'s' -- q"));
        // A block comment is refused; a line comment that starts with - or a space before [[ is not one.
        assertThrows(IllegalArgumentException.class, () -> code("x = 1 --[[ a\nvim.v.errmsg = msg ]]"));
        assertThrows(IllegalArgumentException.class, () -> code("--[==[ a ]==]"));
        assertEquals("", code("---[[ a"));
        assertEquals("", code("-- [[ a"));
    }

    /**
     * The modules the plug-in's Lua may load with {@code require}: Neovim's own, never one of the
     * plug-in's. A known set, so that the check below does not depend on spotting every spelling
     * of a plug-in module (a name built at run time, an alias of {@code require}, a long string).
     */
    private static final Set<String> REQUIRED_MODULES = Set.of("vim._core.ui2");

    /**
     * {@code require} given one module name as a quoted string, and nothing else:
     * {@code require('x')}, {@code require "x"} and {@code pcall(require, 'x')}. The name is
     * group 2, 4 or 6. With parentheses the call must close after the name, so
     * {@code require('x' .. y)} is not one; without them Lua's grammar ends the call at the string
     * itself ({@code require 'x' .. y} is {@code (require 'x') .. y}, a load of {@code x}).
     */
    private static final Pattern REQUIRE_OF_A_NAME = Pattern.compile("\\brequire\\b(?:"
            + "\\s*\\(\\s*(['\"])([^'\"\\\\]+)\\1\\s*\\)" // require('x')
            + "|\\s*(['\"])([^'\"\\\\]+)\\3" // require 'x'
            + "|\\s*,\\s*(['\"])([^'\"\\\\]+)\\5\\s*\\))"); // pcall(require, 'x')

    /**
     * The first use of {@code require} in {@code lua} that is not a load of a module of
     * {@link #REQUIRED_MODULES}, or {@code null}. Any other use counts: one of the plug-in's
     * modules, another name, a name built at run time ({@code require('neovim4eclipse.' .. m)}),
     * {@code require} kept under another name ({@code local r = require}). Strings are not left
     * out: the word {@code require} in a message ({@code 'needs require'}) fails too, loudly, and
     * is reworded; {@code required} and the like are other words.
     */
    private static String disallowedRequire(String lua) {
        String code = code(lua);
        Matcher use = Pattern.compile("\\brequire\\b").matcher(code);
        while (use.find()) {
            Matcher call = REQUIRE_OF_A_NAME.matcher(code).region(use.start(), code.length());
            String name = null; // the module's name, when the use is a load of one
            if (call.lookingAt()) {
                for (int group : new int[] {2, 4, 6}) {
                    name = call.group(group) != null ? call.group(group) : name;
                }
            }
            if (name == null || !REQUIRED_MODULES.contains(name)) {
                return code.substring(use.start()).lines().findFirst().orElse("");
            }
        }
        return null;
    }

    /**
     * No script finds one of the plug-in's modules with {@code require}, nor any module outside
     * {@link #REQUIRED_MODULES}; each of the plug-in's own is read from
     * {@code package.loaded}, where the session put it ({@code docs/LEARNING.md}, "Change or add Lua
     * that runs in Neovim"). Should the module be missing (its install failed, or a config removed
     * it), {@code require} would search the runtimepath and {@code ./?.lua}, where a file of the
     * user's or of a cloned repository could answer; {@code package.loaded} gives an error instead.
     * Every file in {@code core/lua/} is in a script's text ({@code LuaModulesTest}), so this reads
     * them too. The only {@code require} allowed is of a module in {@link #REQUIRED_MODULES}.
     */
    @Test
    void requireLoadsOnlyTheListedModules() throws IllegalAccessException {
        for (String script : LuaModulesTest.scriptsOfLuaScripts()) {
            String found = disallowedRequire(script);
            assertNull(found, () -> "a use of require other than a load of a module of REQUIRED_MODULES"
                    + " (a call, an alias, or the word in a string): " + found);
        }
        // The check itself: every other use is found; a comment, a longer name and an allowed module not.
        for (String use : new String[] {"require('neovim4eclipse.windows').open()",
                "local w = require \"neovim4eclipse.windows\"", "pcall(require, 'neovim4eclipse.folds')",
                "require [[neovim4eclipse.x]]", "require('neovim4eclipse.' .. m)", "local r = require",
                "require(name)", "require('vim._core.ui2' .. x)", "require('other')",
                "error('needs require')"}) {
            assertNotNull(disallowedRequire(use), use);
        }
        for (String other : new String[] {"x = 1 -- require('neovim4eclipse.windows')",
                "local ok, ui2 = pcall(require, 'vim._core.ui2')", "require('vim._core.ui2').cfg",
                "my_require('neovim4eclipse.windows')", "return nil, 'E471: Argument required'",
                "local s = require 'vim._core.ui2' .. ''"}) {
            assertNull(disallowedRequire(other), other);
        }
    }

    /**
     * Each route of {@link LuaScripts#FLOAT_EXITS} is a key of the windows module's table
     * {@code leaves}, which decides it: the names are the one copy across Java and Lua, and a
     * route Lua does not know would make every first step of it fail (logged, the insert kept).
     */
    @Test
    void everyFloatExitRouteIsDecidedInTheWindowsModule() {
        String windows = code(LuaModules.text("windows/floats.lua"));
        String table = windows.substring(windows.indexOf("local leaves = {"));
        table = table.substring(0, table.indexOf("\n}\n"));
        for (String route : LuaScripts.FLOAT_EXITS.values()) {
            assertTrue(table.contains("\n  " + route + " = function("), route);
        }
    }

    @Test
    void setupScriptRegistersTheNotificationsTheBridgeHandles() {
        for (String name : new String[] { "eclipse_mode", "eclipse_cursor", "eclipse_buf_enter", "eclipse_yank",
                "eclipse_clipboard_copy", "eclipse_clipboard_paste" }) {
            assertTrue(code(LuaScripts.SETUP).contains("'" + name + "'"), name);
        }
    }

    /** Document buffers are written only through Eclipse: acwrite, and a write hook that asks it to save. */
    @Test
    void documentBuffersAreWrittenThroughEclipse() {
        assertTrue(code(LuaScripts.DOCUMENT_BUFFER_MODULE).contains("buftype = 'acwrite'"));
        assertTrue(code(LuaScripts.DOCUMENT_BUFFER_MODULE).contains("'BufWriteCmd'"));
        // Some lines (:5,10w part.txt) and appends (:w >> log.txt) have hooks of their own.
        assertTrue(code(LuaScripts.DOCUMENT_BUFFER_MODULE).contains("FileWriteCmd = 'lines', FileAppendCmd = 'append'"));
        assertTrue(code(LuaScripts.DOCUMENT_BUFFER_MODULE).contains("'eclipse_write'"));
        // Both names are compared by the one rule for paths (PATHS: links resolved, macOS /var;
        // slashes, Windows / against \; case where names ignore it), not a copy of it.
        assertTrue(code(LuaScripts.DOCUMENT_BUFFER_MODULE).contains("local paths = package.loaded['neovim4eclipse.paths']"));
        assertTrue(code(LuaScripts.DOCUMENT_BUFFER_MODULE).contains("local canonical = paths.canonical"));
        assertFalse(code(LuaScripts.DOCUMENT_BUFFER_MODULE).contains("require("), "PATHS as the session sent it");
        assertFalse(code(LuaScripts.DOCUMENT_BUFFER_MODULE).contains("vim.fn.resolve("), "no comparison of its own");
        // Writes are compared with the editor's file, kept when the buffer is made, not with the
        // buffer's name, which :saveas and :file change.
        assertTrue(code(LuaScripts.DOCUMENT_BUFFER_MODULE).contains("vim.b[buf].neovim4eclipse_file = vim.api.nvim_buf_get_name(buf)"));
        assertTrue(code(LuaScripts.SETUP).contains("vim.g.neovim4eclipse_channel = chan"), "the channel the request goes to");
        // Each buffer's call reaches the module the session sent, never one require would find.
        assertTrue(code(LuaScripts.DOCUMENT_BUFFER).contains("local module = package.loaded['neovim4eclipse.document_buffer']"));
        assertTrue(code(LuaScripts.DOCUMENT_BUFFER).contains("module.setup(tonumber((select(1, ...))))"));
        assertFalse(code(LuaScripts.DOCUMENT_BUFFER).contains("require("));
    }

    /**
     * The plug-in's own errors are shown with {@code nvim_echo}'s {@code err = true}, which sets
     * {@code v:errmsg} and marks the message as an error ({@code msg_show} kind {@code "echoerr"}).
     * Setting {@code v:errmsg} by hand with an {@code ErrorMsg} echo would arrive as an ordinary
     * message ({@code "echomsg"}) for a UI that reads message kinds.
     */
    @Test
    void errorsAreEchoedAsErrors() {
        for (String script : new String[] { LuaScripts.NORMAL_WITHOUT_MAPPINGS, LuaScripts.DOCUMENT_BUFFER_MODULE }) {
            assertTrue(code(script).contains("vim.api.nvim_echo({ { msg } }, true, { err = true })"), script);
            assertFalse(code(script).contains("vim.v.errmsg = msg"), "no v:errmsg set by hand: " + script);
            assertFalse(code(script).contains("'ErrorMsg'"), "err = true picks the highlight: " + script);
        }
    }

    /** {@code q:} opens a window Eclipse cannot show: closed at once, with a message saying so. */
    @Test
    void theCommandLineWindowIsToldToEclipse() {
        assertTrue(code(LuaScripts.SETUP).contains("'CmdwinEnter'"));
        assertTrue(code(LuaScripts.SETUP).contains("'eclipse_cmdwin', vim.api.nvim_get_current_win(), vim.fn.getcmdwintype()"));
        assertTrue(code(LuaScripts.SETUP).contains("'CmdwinLeave'"));
        assertTrue(code(LuaScripts.SETUP).contains("'eclipse_cmdwin_closed'"));
    }

    @Test
    void pasteUsesNeovimsOwnCall() {
        assertTrue(code(LuaScripts.PASTE).contains("vim.api.nvim_paste(select(1, ...), false, -1)"));
    }

    @Test
    void yankHookStandsDownWhenNeovimSyncsTheClipboardItself() {
        // Regression guard for linewise yanks turning charwise (P pasting inline) when two writers raced.
        assertTrue(code(LuaScripts.SETUP).contains("vim.o.clipboard:find('unnamed'"));
        // "+y goes to the provider; the yank hook must not write the clipboard a second time.
        assertTrue(code(LuaScripts.SETUP).contains("e.regname == '+' or e.regname == '*'"));
    }

    @Test
    void theClipboardProviderIsInstalledOnlyWhenTheUserHasNone() {
        assertTrue(code(LuaScripts.SETUP).contains("if vim.g.clipboard == nil then"));
        assertTrue(code(LuaScripts.SETUP).contains("vim.g.loaded_clipboard_provider = nil"));
    }

    @Test
    void snapshotOnlyAddsTheStatusLineAndReadsFoldsWhenRequested() {
        // The line itself is STATUS_LINE's (status_line_spec.lua); the snapshot asks it for the current window.
        assertTrue(code(LuaScripts.STATE_SNAPSHOT).contains("want_line, want_folds = want_line == '1', want_folds == '1'"));
        assertTrue(code(LuaScripts.STATE_SNAPSHOT).contains("if want_line and lines then"));
        assertTrue(code(LuaScripts.STATE_SNAPSHOT).contains("if want_folds and package.loaded['neovim4eclipse.folds'] then"));
    }

    /**
     * Messages and showcmd arrive as UI events (ext_messages): the snapshot no longer reads or clears
     * v:statusmsg and v:errmsg, nor evaluates %S, and 'showcmdloc' stays at its default ("last"),
     * where Neovim sends msg_showcmd.
     */
    @Test
    void messagesAndShowcmdAreNotPolled() {
        assertTrue(code(LuaScripts.STATUS_LINE).contains("nvim_eval_statusline"));
        for (String gone : new String[] { "statusmsg", "errmsg", "'%S'" }) {
            assertFalse(code(LuaScripts.STATE_SNAPSHOT).contains(gone), gone);
            assertFalse(code(LuaScripts.STATUS_LINE).contains(gone), gone);
        }
        assertFalse(code(LuaScripts.SETUP).contains("showcmdloc"));
    }

    /** The user's insert mappings stay: the re-typing applies none (nvim_feedkeys 'n'), so nothing clears them. */
    @Test
    void insertModeMappingsAndAbbreviationsAreKept() {
        assertFalse(code(LuaScripts.SETUP).contains("imapclear"));
        assertFalse(code(LuaScripts.SETUP).contains("iabclear"));
    }

    @Test
    void stateSnapshotIsOneScriptReturningCursorVisualBoundsAndMode() {
        assertTrue(code(LuaScripts.STATE_SNAPSHOT).contains("nvim_win_get_cursor"));
        assertTrue(code(LuaScripts.STATE_SNAPSHOT).contains("getpos('v')"));
        assertTrue(code(LuaScripts.STATE_SNAPSHOT).contains("getpos('.')"));
        assertTrue(code(LuaScripts.STATE_SNAPSHOT).contains("vim.fn.mode()"));
        assertTrue(code(LuaScripts.STATE_SNAPSHOT).contains("block, vim.api.nvim_get_mode().mode, folds,\n"),
                "the full mode, then the folds");
        assertTrue(code(LuaScripts.STATE_SNAPSHOT).contains("vim.api.nvim_get_current_win() }"), "the window last");
        // A block's lines come from Neovim; no curswant or tabstop for Java to compute them from.
        assertTrue(code(LuaScripts.STATE_SNAPSHOT).contains("vim.fn.getregionpos(s, e, { type = mode, eol = true })"));
        assertTrue(code(LuaScripts.STATE_SNAPSHOT).contains("if mode == string.char(22) then"), "blocks only");
        assertFalse(code(LuaScripts.STATE_SNAPSHOT).contains("curswant"));
        assertFalse(code(LuaScripts.STATE_SNAPSHOT).contains("tabstop"));
        assertTrue(code(LuaScripts.STATE_SNAPSHOT).contains("reg_recording()"));
    }

    @Test
    void cursorReportsSayWhichBufferTheyAreAbout() {
        assertTrue(code(LuaScripts.STATE_SNAPSHOT).contains("nvim_get_current_buf()"));
        assertTrue(code(LuaScripts.SETUP).contains("'eclipse_cursor', p[1], p[2], vim.api.nvim_get_current_buf()"));
    }
}
