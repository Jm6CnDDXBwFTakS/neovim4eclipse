// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

/**
 * Lua snippets executed inside Neovim via {@code nvim_exec_lua}.
 *
 * <p>Kept in one place, with comments, because Lua embedded in Java strings is hard to review.
 * Note {@code string.char(34)} is the {@code "} (unnamed) register name, written that way to avoid
 * Java string escaping.
 */
final class LuaScripts {

    private LuaScripts() {
    }

    /**
     * One-time session setup: options plus autocmds that push state to Eclipse.
     *
     * <ul>
     *   <li>{@code timeoutlen}: how long Neovim waits for the rest of a multi-key mapping or
     *       operator. Keys arrive one RPC at a time, so this must be generous (gg, diw, f{char}).</li>
     *   <li>{@code showmode=false}: Eclipse shows the mode in its own status line.</li>
     *   <li>Autocmds emit {@code eclipse_mode}, {@code eclipse_cursor} (with the buffer number),
     *       {@code eclipse_buf_enter} and {@code eclipse_yank} notifications handled by
     *       {@link NotificationBridge}.</li>
     *   <li>Unless the user's config sets {@code g:clipboard}, a clipboard provider whose copy sends
     *       {@code eclipse_clipboard_copy} and whose paste is the {@code eclipse_clipboard_paste}
     *       request (see {@link ClipboardRegisters}).</li>
     *   <li>The one argument is Eclipse's RPC channel number, which requests are sent to. It is kept
     *       in {@code g:neovim4eclipse_channel} for {@link #DOCUMENT_BUFFER}'s write hook.</li>
     *   <li>Normal-mode mappings for {@code gd}, {@code K}, {@code gt} and {@code gT} send
     *       {@code eclipse_action} instead, so that the IDE does those (see {@link IdeAction}).</li>
     * </ul>
     */
    static final String SETUP = """
            -- vim.rpcrequest needs Eclipse's channel number (rpcnotify(0, ...) below means "every
            -- channel"). Eclipse passes it as the script's argument: inside Lua run through
            -- nvim_exec_lua, nvim_get_chan_info(0) does not know which channel called.
            local chan = tonumber(select(1, ...))
            vim.g.neovim4eclipse_channel = chan
            vim.opt.timeout = true
            vim.opt.timeoutlen = 400
            vim.opt.ttimeoutlen = 50
            vim.opt.hlsearch = true
            vim.opt.incsearch = true
            vim.opt.ignorecase = true
            vim.opt.smartcase = true
            vim.opt.wrapscan = true
            vim.opt.magic = true
            vim.opt.showcmd = true
            vim.opt.showmode = false
            -- Insert-mode text is typed by Eclipse, so the user's insert-mode mappings and abbreviations
            -- never applied. They must not hijack the text that is re-typed into Neovim at Esc (so that
            -- "." can repeat it), e.g. a `jk` -> <Esc> mapping would end the insert in the middle.
            vim.cmd('imapclear')
            vim.cmd('iabclear')
            vim.api.nvim_create_autocmd('ModeChanged', {
              callback = function()
                vim.rpcnotify(0, 'eclipse_mode', vim.api.nvim_get_mode().mode)
              end
            })
            vim.api.nvim_create_autocmd({'CursorMoved', 'CursorMovedI'}, {
              callback = function()
                local p = vim.api.nvim_win_get_cursor(0)
                vim.rpcnotify(0, 'eclipse_cursor', p[1], p[2], vim.api.nvim_get_current_buf())
              end
            })
            -- Each Eclipse document has its own buffer. When Neovim switches buffers by itself
            -- (Ctrl-O across files, 'A, :b), Eclipse follows by activating that file's editor.
            vim.api.nvim_create_autocmd('BufEnter', {
              callback = function(ev)
                vim.rpcnotify(0, 'eclipse_buf_enter', ev.buf)
              end
            })
            vim.api.nvim_create_autocmd('TextYankPost', {
              callback = function()
                -- If the user's config already makes Neovim sync the system clipboard
                -- (clipboard=unnamed/unnamedplus), do not also write it from Java: two writers
                -- race, and a linewise yank can come back as a charwise one (so P pastes inline).
                if vim.o.clipboard:find('unnamed', 1, true) then
                  return
                end
                local e = vim.v.event
                -- "+y and "*y already went to the clipboard provider below; a second write would race.
                if e and (e.regname == '+' or e.regname == '*') then
                  return
                end
                if e and e.regcontents then
                  vim.rpcnotify(0, 'eclipse_yank', e.regcontents, e.regtype or 'v')
                end
              end
            })
            -- Eclipse is Neovim's clipboard provider (:help clipboard, ClipboardRegisters), so "+p and
            -- "*p paste what was copied in any program, with no xclip, wl-paste or pbpaste needed.
            -- A user who sets g:clipboard in init.lua (which runs before this) keeps their own provider.
            if vim.g.clipboard == nil then
              local function copy(reg)
                return function(lines, regtype)
                  vim.rpcnotify(chan, 'eclipse_clipboard_copy', reg, lines, regtype)
                end
              end
              local function paste(reg)
                -- A request: Neovim waits (at most about 2 s, see ClipboardBridge) for the text.
                return function()
                  return vim.rpcrequest(chan, 'eclipse_clipboard_paste', reg)
                end
              end
              vim.g.clipboard = {
                name = 'eclipse',
                copy = { ['+'] = copy('+'), ['*'] = copy('*') },
                paste = { ['+'] = paste('+'), ['*'] = paste('*') },
                cache_enabled = 0,
              }
              -- Neovim reads g:clipboard once, when its clipboard script loads; reload it in case that
              -- already happened (the reload its own comments describe).
              vim.g.loaded_clipboard_provider = nil
              vim.cmd('runtime autoload/provider/clipboard.vim')
            end
            -- Keys only the IDE can carry out (see IdeAction). This runs after the user's init.lua,
            -- so it wins over their own mappings of these keys; `vim.g.neovim4eclipse_ide_keys = 0` (or
            -- false, v:false in Vim script) in init.lua keeps Neovim's meaning instead. vim.v.count
            -- is the count typed before the key.
            local ide_keys = vim.g.neovim4eclipse_ide_keys
            if ide_keys ~= 0 and ide_keys ~= false then
              local function ide(action, key, desc)
                vim.keymap.set('n', key, function()
                  vim.rpcnotify(0, 'eclipse_action', action, vim.v.count)
                end, { desc = 'Eclipse: ' .. desc })
              end
              ide('definition', 'gd', 'Open Declaration')
              ide('hover', 'K', 'Show Tooltip Description')
              ide('next_tab', 'gt', 'next editor tab')
              ide('previous_tab', 'gT', 'previous editor tab')
            end
            """;

    /**
     * Everything the UI needs after a key, in ONE round trip:
     * {@code {cursor_line, cursor_col, vstart_line, vstart_col, vend_line, vend_col, mode, curswant,
     * tabstop, recording, statusline, bufnr}}. {@code bufnr} tells whether the cursor is still in the
     * buffer Eclipse shows (Neovim may just have jumped to another file).
     *
     * <p>The cursor is {@code nvim_win_get_cursor} (1-based line, 0-based byte column); the visual
     * bounds are {@code getpos} (1-based lines and byte columns) and only meaningful in visual mode.
     * {@code curswant} is 2147483647 after {@code $} (a block then extends to each line's end).
     * {@code recording} is the register being recorded into ({@code reg_recording()}), or "".
     *
     * <p>The argument, {@code "1"} or {@code "0"}, says whether to evaluate the {@code 'statusline'}
     * (a Vim-like default if the user has none), so that nothing is evaluated that the status bar
     * does not show. Messages and the pending command ({@code showcmd}) are not read here: Neovim
     * sends them as UI events ({@code ext_messages}; see {@code StatusTracker}).
     */
    static final String STATE_SNAPSHOT = """
            local want_statusline = select(1, ...) == '1'
            local c = vim.api.nvim_win_get_cursor(0)
            local s = vim.fn.getpos('v')
            local e = vim.fn.getpos('.')
            local view = vim.fn.winsaveview()
            local statusline = ''
            if want_statusline then
              local fmt = vim.o.statusline
              if fmt == nil or fmt == '' then fmt = '%<%f %h%m%r%=%-14.(%l,%c%V%) %P' end
              local ok, res = pcall(vim.api.nvim_eval_statusline, fmt, { maxwidth = 300 })
              if ok and type(res) == 'table' then statusline = res.str or '' end
            end
            return { c[1], c[2], s[2], s[3], e[2], e[3], vim.fn.mode(), view.curswant, vim.bo.tabstop,
                     vim.fn.reg_recording(), statusline, vim.api.nvim_get_current_buf() }
            """;

    /**
     * Deletes the empty, unnamed buffer Neovim starts with (argument: the number of the buffer that
     * replaced it), so that {@code :ls} lists only the files open in Eclipse. Buffers with text or a
     * name, e.g. created by the user's config, are left alone.
     */
    static final String DELETE_STARTUP_BUFFER = """
            local keep = tonumber(select(1, ...))
            for _, b in ipairs(vim.api.nvim_list_bufs()) do
              if b ~= keep and vim.api.nvim_buf_get_name(b) == '' and not vim.bo[b].modified
                  and vim.api.nvim_buf_line_count(b) == 1 and vim.api.nvim_buf_get_lines(b, 0, 1, false)[1] == '' then
                pcall(vim.api.nvim_buf_delete, b, { force = true })
              end
            end
            """;

    /**
     * Sets the current buffer's indentation options (arguments: tabstop, "1" to indent with tabs,
     * indent width, "1" to follow braces, cinoptions). See {@code util/Indentation} for why.
     * {@code 'shiftwidth'} is one indent level, used by {@code >>}, {@code o} and {@code cindent};
     * {@code 'softtabstop'} 0 makes Tab and Backspace in Neovim's insert mode work per character.
     */
    static final String SET_INDENTATION = """
            local tabstop, use_tabs, width, braces, cinoptions = ...
            tabstop = tonumber(tabstop)
            if tabstop >= 1 and tabstop <= 64 then
              vim.bo.tabstop = tabstop
            end
            vim.bo.expandtab = use_tabs ~= '1'
            vim.bo.shiftwidth = tonumber(width)
            vim.bo.softtabstop = 0
            vim.bo.autoindent = true
            vim.bo.cindent = braces == '1'
            if braces == '1' then
              vim.bo.cinoptions = cinoptions
            end
            """;

    /**
     * Replaces the current buffer's text without an undo step (arguments: the lines, one each). Setting
     * {@code 'undolevels'} to -1 for one change is Vim's documented way to do that (see
     * {@code :help clear-undo}); it also clears any earlier history. The buffer-local value is put
     * back afterwards, which may be "use the global value" (-123456).
     */
    static final String LOAD_WITHOUT_UNDO = """
            local lines = { ... }
            local saved = vim.bo.undolevels
            vim.bo.undolevels = -1
            local ok, err = pcall(vim.api.nvim_buf_set_lines, 0, 0, -1, false, lines)
            vim.bo.undolevels = saved
            if not ok then
              error(err)
            end
            -- Like a file just read: not modified, so :update (and :bufdo update) skips it.
            vim.bo.modified = false
            """;

    /**
     * Makes a new buffer (argument: its number) a document buffer, which only Eclipse writes.
     *
     * <h2>The concept: {@code buftype=acwrite}</h2>
     * Eclipse owns the files: a {@code :w} by Neovim itself would write its copy behind Eclipse's
     * back (no save actions, no refresh, a "file changed on disk" dialog later). A buffer with
     * {@code buftype=acwrite} is never written by Neovim; every write runs the buffer's
     * {@code BufWriteCmd} autocommands instead ({@code :help acwrite}, {@code :help BufWriteCmd}).
     * The one here asks Eclipse to save the document (the {@code eclipse_write} request, answered by
     * {@link NotificationBridge}) and, if that worked, clears {@code 'modified'}, as a real write does.
     * So {@code :bufdo %s/a/b/g | update}, {@code :wall} and a {@code <leader>w} mapping in
     * {@code init.lua} all save through Eclipse. ({@code :w} typed in the {@code :} box never gets here:
     * Eclipse handles it before Neovim sees it, see {@code util/HostExCommand}.)
     *
     * <p>A write under another name ({@code :w other.txt}) is refused: Eclipse saves a document only
     * as its own file. The names are compared after {@code resolve()} and {@code vim.fs.normalize}:
     * Neovim resolves symbolic links in a buffer's name but not in the written name (on macOS
     * {@code /var} is a link to {@code /private/var}, so the buffer is {@code /private/var/.../A.txt}
     * while {@code :update} writes {@code /var/.../A.txt}), and on Windows it reports the written
     * name with forward slashes while the buffer keeps backslashes.
     *
     * <p>A failed save fails the write, as a real failed write ({@code E212: Can't open file for
     * writing}) does: {@code nvim_echo}'s {@code err = true} (Neovim 0.11) works like
     * {@code :echoerr}, so inside the autocommand it aborts the {@code :write} that triggered it. The
     * error reaches whoever ran the command, {@code :w | q} does not go on to quit, and
     * {@code :bufdo update} stops at that buffer. {@code v:errmsg} holds the text alone
     * ({@code Eclipse could not save A.txt: read-only}); the message Neovim shows puts its usual line
     * in front, {@code Error in BufWriteCmd Autocommands for "<buffer=3>":}, as for any error in an
     * autocommand. Being two lines, it opens the output popup ({@code util/MessageRoute}), where Vim
     * would show it above a "Press ENTER" prompt. The buffer stays modified, so {@code :update} tries
     * again next time.
     */
    static final String DOCUMENT_BUFFER = """
            local buf = tonumber(select(1, ...))
            vim.bo[buf].buftype = 'acwrite'
            vim.api.nvim_create_autocmd('BufWriteCmd', {
              buf = buf, -- named "buffer" before Neovim 0.12.2
              desc = 'Eclipse saves the document',
              callback = function(ev)
                -- An error message, as Neovim shows its own: err = true sets v:errmsg and the ErrorMsg
                -- highlight, and marks the message as an error (msg_show kind "echoerr").
                local function fail(msg)
                  vim.api.nvim_echo({ { msg } }, true, { err = true })
                end
                -- Resolved: the buffer name has its links resolved, ev.match not (/private/var/x, /var/x on macOS).
                -- Normalized: on Windows ev.match has / where the buffer name has \\ (C:/x/a.txt, C:\\x\\a.txt).
                local function canonical(name)
                  return vim.fs.normalize(vim.fn.resolve(name))
                end
                if canonical(ev.match) ~= canonical(vim.api.nvim_buf_get_name(ev.buf)) then
                  return fail('Eclipse saves a file only under its own name; use File > Save As')
                end
                -- Neovim waits for the answer: Eclipse saves on its UI thread (see EditorAttachManager).
                local err = vim.rpcrequest(vim.g.neovim4eclipse_channel, 'eclipse_write', ev.buf)
                if type(err) == 'string' and err ~= '' then
                  return fail(err)
                end
                vim.bo[ev.buf].modified = false
              end,
            })
            """;

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
     * Completes a {@code :} command line the way Neovim's own command line does, for Tab in the
     * {@code :} box (see {@code docs/design/TAB_COMPLETION.md}). Returns {@code {line, total, candidates}}:
     * the completed line, how many candidates there are, and the first {@code cap} of them; or
     * {@code nil} when Neovim is not in normal mode (it would not be safe to type {@code :} then).
     *
     * <p>Arguments: the line (without {@code :}); the steps, one letter per key, {@code T} for the
     * next match and {@code P} for the previous one; two folders to complete {@code :e} file names
     * in ({@code ""} when the line is not an {@code :e}); the cap.
     *
     * <h2>How</h2>
     * It types {@code :}, the line and the steps into Neovim, then {@code <C-\>e} with an expression
     * that reads {@code getcmdline()} and returns {@code ""}, then {@code <C-c>}: the command line is
     * read and cancelled, and Neovim is back in normal mode when the call returns. Every press of
     * Tab in the box re-types the whole sequence, so Neovim is never left in command-line mode.
     * Some details, all found by experiment (Neovim 0.12):
     * <ul>
     *   <li>The keys are not "typed" ({@code nvim_feedkeys} without {@code t}): typed keys would be
     *       recorded into a macro being recorded, and the expression would land in the {@code =}
     *       history. Keys that are not typed do not trigger {@code 'wildchar'}, so
     *       {@code 'wildcharm'} is set to Tab for the call; {@code <S-Tab>} does not work either, so
     *       going back is {@code <C-p>} (and from the start, Tab then two {@code <C-p>}).</li>
     *   <li>The line is fed separately, with {@code escape_ks}, so that {@code <} in it is just a
     *       character and not the start of a key name.</li>
     *   <li>{@code 'wildmode'} is the user's, without its {@code list} parts: listing would send the
     *       candidates as a message, which would open the output popup over the box's own list.</li>
     *   <li>{@code 'eventignore'} keeps {@code CmdlineEnter}, {@code CmdlineChanged},
     *       {@code CmdlineLeave} and {@code ModeChanged} from firing: user plug-ins should not react,
     *       and Eclipse should not hear of a trip to command-line mode.</li>
     *   <li>{@code :e} names: Neovim completes file names in its working directory, while the
     *       plug-in's {@code :e} looks in the edited file's folder, then the project root
     *       ({@code util/EditPath}). So the window's directory is set to the first of those folders
     *       that completes something, with {@code noautocmd lcd}, and put back afterwards: with
     *       {@code lcd}, {@code tcd} or {@code cd}, depending on which directory was in effect.</li>
     * </ul>
     * Options, directory and the helper function are restored even when a step fails.
     */
    static final String COMPLETE_CMDLINE = """
            local line, steps, dir1, dir2, cap = ...
            cap = tonumber(cap) or 200
            local mode = vim.api.nvim_get_mode()
            if mode.mode ~= 'n' or mode.blocking then
              return nil
            end
            local saved = { wildmode = vim.o.wildmode, wildcharm = vim.o.wildcharm, eventignore = vim.o.eventignore }
            local cwd = vim.fn.getcwd()
            local win_local = vim.fn.haslocaldir() == 1
            local tab_local = vim.fn.haslocaldir(-1, 0) == 1
            local moved = false
            local result = {}
            local function complete()
              for _, dir in ipairs({ dir1, dir2 }) do
                if dir ~= '' and vim.fn.isdirectory(dir) == 1 then
                  vim.cmd('noautocmd lcd ' .. vim.fn.fnameescape(dir))
                  moved = true
                  if #vim.fn.getcompletion(line, 'cmdline') > 0 then
                    break
                  end
                end
              end
              local parts = {}
              for item in saved.wildmode:gmatch('[^,]+') do
                local keep = {}
                for word in item:gmatch('[^:]+') do
                  if word ~= 'list' then
                    keep[#keep + 1] = word
                  end
                end
                if #keep > 0 then
                  parts[#parts + 1] = table.concat(keep, ':')
                end
              end
              vim.o.wildmode = #parts > 0 and table.concat(parts, ',') or 'full'
              vim.o.wildcharm = 9
              local ignore = 'CmdlineEnter,CmdlineChanged,CmdlineLeave,ModeChanged'
              vim.o.eventignore = saved.eventignore == '' and ignore or (saved.eventignore .. ',' .. ignore)
              _G.__eclipse_capture = function()
                result.line = vim.fn.getcmdline()
                return ''
              end
              local keys = (steps:gsub('T', '<Tab>'):gsub('P', '<C-p>'))
                  .. [[<C-\\>ev:lua.__eclipse_capture()<CR><C-c>]]
              vim.api.nvim_feedkeys(':' .. line, 'n', true)
              vim.api.nvim_feedkeys(vim.api.nvim_replace_termcodes(keys, true, true, true), 'xn', false)
              local all = vim.fn.getcompletion(line, 'cmdline')
              result.total = #all
              result.list = {}
              for i = 1, math.min(cap, #all) do
                result.list[i] = all[i]
              end
            end
            local ok, err = pcall(complete)
            vim.o.wildmode = saved.wildmode
            vim.o.wildcharm = saved.wildcharm
            vim.o.eventignore = saved.eventignore
            _G.__eclipse_capture = nil
            if moved then
              local back = vim.fn.fnameescape(cwd)
              if win_local then
                vim.cmd('noautocmd lcd ' .. back)
              elseif tab_local then
                vim.cmd('noautocmd tcd ' .. back)
              else
                vim.cmd('noautocmd cd ' .. back)
              end
            end
            if not ok then
              error(err, 0)
            end
            if result.line == nil then
              return nil
            end
            return { result.line, result.total, result.list }
            """;

    /** The user's {@code 'clipboard'} option (e.g. {@code unnamedplus}), evaluated after their init file ran. */
    static final String GET_CLIPBOARD_OPTION = "return vim.o.clipboard";

    /** Evaluates the Vim expression in argument 1 ({@code bufnr('a.txt')}) and returns its value. */
    static final String EVAL = "return vim.fn.eval(select(1, ...))";

    static final String GET_CMDLINE = "return vim.fn.getcmdline()";

    static final String GET_CMDTYPE = "return vim.fn.getcmdtype()";
}
