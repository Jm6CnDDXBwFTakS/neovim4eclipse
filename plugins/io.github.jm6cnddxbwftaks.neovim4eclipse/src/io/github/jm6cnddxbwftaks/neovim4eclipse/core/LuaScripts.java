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
     *   <li>The command-line window ({@code q:}) is closed as soon as it opens, with a message:
     *       Eclipse cannot show a second window.</li>
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
            -- ZZ and ZQ save and close the Eclipse editor. Always mapped, whatever
            -- neovim4eclipse_ide_keys says: Neovim's own ZZ (:x) would quit the embedded Neovim,
            -- which serves every editor. Being mappings, Neovim decides when they apply: fZ, rZ
            -- and "Z in a macro" are Neovim's to read, as for any key (no guessing in Java).
            for key, action in pairs({ ZZ = 'save_and_close', ZQ = 'close' }) do
              vim.keymap.set('n', key, function()
                vim.rpcnotify(0, 'eclipse_action', action, 0)
              end, { desc = 'Eclipse: ' .. (key == 'ZZ' and 'save and close the editor' or 'close the editor') })
            end
            -- The command-line window (q:, q/, Ctrl-F on the command line) is a window of its own,
            -- which Eclipse cannot show: close it at once and say why. The : box's Up and Down
            -- browse the same history. Scheduled: the window cannot close inside its own CmdwinEnter.
            vim.api.nvim_create_autocmd('CmdwinEnter', {
              callback = function()
                vim.schedule(function()
                  if vim.fn.getcmdwintype() ~= '' then
                    vim.cmd.quit()
                  end
                  vim.api.nvim_echo({ { 'The command-line window is not available in Eclipse; '
                    .. 'Up and Down in the : box browse the history' } }, true, {})
                end)
              end
            })
            """;

    /**
     * Hands the ex commands Eclipse carries out ({@code :w}, {@code :q}, {@code :e file},
     * {@code :tabn} ...; see {@code util/HostCommand}) from Neovim's command line to Eclipse. The
     * argument names those commands by Neovim's full name, with what each may have after it
     * ({@code HostCommand.rulesForNeovim()}), as words: {@code "edit=any quit=none tabnext=count ..."}.
     *
     * <h2>How</h2>
     * <ol>
     *   <li>{@code :Eclipse[!] {command} {args}} is a user command that sends {@code eclipse_command}
     *       to Eclipse ({@code :Eclipse tabnext 3}). Anyone can use it, in a mapping too.</li>
     *   <li>Just before a {@code :} line runs, {@code CmdlineLeavePre} parses it with
     *       {@code nvim_parse_cmd()}. If it is one of the commands, {@code setcmdline()} replaces it
     *       with the {@code :Eclipse} form, which Neovim then runs instead: {@code :tabn 3} becomes
     *       {@code :Eclipse tabnext 3}, {@code :q!} {@code :Eclipse! quit}. A {@code |} and what
     *       follows are kept ({@code :Eclipse} is defined with {@code bar}).</li>
     *   <li>{@code :Eclipse} puts the line as typed back into the history, in place of its own, so
     *       Up in the {@code :} box recalls {@code tabn 3}.</li>
     * </ol>
     *
     * <p>Why replace the line rather than cancel it ({@code v:event.abort} in {@code CmdlineLeave}):
     * a cancelled line is not "the last command line", so {@code @:} would fail with E30, while the
     * replaced one is, and {@code @:} repeats it. Only {@code :reg :} shows the replacement.
     * (Experiment X7 of the cleanup plan, 2026-10-26.)
     *
     * <p>Not replaced, so left to Neovim: a range the command does not take ({@code :5,10w}),
     * arguments it does not take ({@code :q foo}: Neovim reports E488), a line Neovim cannot parse.
     * A range of buffers ({@code :3,5bd}, {@code :%bd}) becomes the listed buffers it covers, and a
     * count in front of a tab command ({@code :3tabn}) its argument.
     *
     * <p>{@code CmdlineLeavePre} fires on Esc too; {@code v:char} tells which key ended the line, and
     * only Enter runs it. It fires for {@code :} typed by a mapping, a macro, {@code :normal} and
     * {@code feedkeys()} as well, each line on its own, but not for {@code <Cmd>}, for the commands
     * {@code :bufdo} runs, or after {@code |}: those reach Neovim, whose buffer autocommands tell
     * Eclipse ({@code DOCUMENT_BUFFER}).
     */
    static final String HOST_COMMANDS = """
            local rules = {} -- { quit = 'none', edit = 'any', ... }
            for name, rule in select(1, ...):gmatch('(%a+)=(%a+)') do
              rules[name] = rule
            end
            local typed -- the line as typed while its :Eclipse replacement runs (for the history)
            vim.api.nvim_create_user_command('Eclipse', function(o)
              if typed then
                vim.fn.histdel(':', -1) -- the replacement
                vim.fn.histadd(':', typed)
                typed = nil
              end
              local name = o.fargs[1]
              if not rules[name] then
                vim.api.nvim_echo({ { 'E492: Not an Eclipse command: ' .. name } }, true, { err = true })
                return
              end
              vim.rpcnotify(vim.g.neovim4eclipse_channel, 'eclipse_command', name, o.bang, vim.list_slice(o.fargs, 2))
            end, { nargs = '+', bang = true, bar = true, desc = 'Eclipse carries out an ex command (:Eclipse quit)' })

            -- The line to run instead of `line`, or nil to leave it alone.
            local function replacement(line)
              local ok, p = pcall(vim.api.nvim_parse_cmd, line, {})
              if not ok then
                return nil -- Neovim reports it when it runs the line
              end
              local rule = rules[p.cmd]
              if not rule then
                return nil
              end
              local args = vim.deepcopy(p.args)
              local range = p.range or {}
              if #range > 0 then
                if rule == 'buffers' and #args == 0 then
                  -- :3,5bd, :%bd, :bd 3 (a number after :bd is a range too): the listed buffers it covers
                  local last = range[2] or range[1]
                  for _, b in ipairs(vim.fn.getbufinfo({ buflisted = 1 })) do
                    if b.bufnr >= range[1] and b.bufnr <= last then
                      args[#args + 1] = tostring(b.bufnr)
                    end
                  end
                  if #args == 0 then
                    return nil -- no such buffer: Neovim says so
                  end
                elseif rule == 'count' and #args == 0 and #range == 1 then
                  args = { tostring(range[1]) } -- :3tabn is :tabn 3
                else
                  return nil
                end
              end
              if rule == 'none' and #args > 0 then
                return nil
              end
              if rule == 'count' and (#args > 1 or (#args == 1 and not args[1]:match('^%d+$'))) then
                return nil
              end
              local cmd = 'Eclipse' .. (p.bang and '!' or '') .. ' ' .. p.cmd
              if #args > 0 then
                cmd = cmd .. ' ' .. table.concat(args, ' ') -- as typed: a name keeps its backslashes
              end
              if p.nextcmd ~= '' then
                cmd = cmd .. ' | ' .. p.nextcmd
              end
              return cmd
            end

            vim.api.nvim_create_autocmd('CmdlineLeavePre', {
              desc = 'Eclipse carries out :w, :q, :e, :tabn ...',
              callback = function()
                -- Only a : line, and only when Enter runs it (v:char is the key that ends the line:
                -- CR or NL, spelled as string.char because a backslash escape is Java's too).
                if vim.fn.getcmdtype() ~= ':' or (vim.v.char ~= string.char(13) and vim.v.char ~= string.char(10)) then
                  return
                end
                local line = vim.fn.getcmdline()
                local cmd = replacement(line)
                if cmd then
                  typed = line
                  vim.fn.setcmdline(cmd)
                end
              end,
            })
            """;

    /**
     * Everything the UI needs after a key, in ONE round trip:
     * {@code {cursor_line, cursor_col, vstart_line, vstart_col, vend_line, vend_col, mode, recording,
     * statusline, bufnr, block, full_mode}}. {@code bufnr} tells whether the cursor is still in the
     * buffer Eclipse shows (Neovim may just have jumped to another file). {@code full_mode} is
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
     * {@code {1, 1, 2, 2, 1, 2}}. 5000 lines take about 3 ms (experiment X5).
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
            local statusline = ''
            if want_statusline then
              local fmt = vim.o.statusline
              if fmt == nil or fmt == '' then fmt = '%<%f %h%m%r%=%-14.(%l,%c%V%) %P' end
              local ok, res = pcall(vim.api.nvim_eval_statusline, fmt, { maxwidth = 300 })
              if ok and type(res) == 'table' then statusline = res.str or '' end
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
            return { c[1], c[2], s[2], s[3], e[2], e[3], mode, vim.fn.reg_recording(), statusline,
                     vim.api.nvim_get_current_buf(), block, vim.api.nvim_get_mode().mode }
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
     * {@code init.lua} all save through Eclipse. ({@code :w} on Neovim's command line never gets here:
     * it is handed to Eclipse as {@code :Eclipse write}, see {@link #HOST_COMMANDS}.)
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
     * One mouse event for Neovim's own mouse handling ({@code nvim_input_mouse}): arguments button
     * ({@code "left"}), action ({@code "press"}, {@code "drag"}, {@code "release"}), modifiers
     * ({@code "S"}, {@code "A"}, {@code ""}), line (1-based) and byte column (0-based, both as text) of the
     * character clicked in the document. Returns whether the event was handed to Neovim's mouse.
     *
     * <p><b>Why a script.</b> {@code nvim_input_mouse} takes a <em>screen</em> cell, not a buffer
     * position, and the plug-in draws nothing of Neovim's screen: Neovim's window (40 rows, see
     * {@code NeovimConnection}) does not scroll with the editor. So the script asks Neovim where the
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
     * there does in Neovim. Without the mouse ({@code 'mouse'} lacks {@code n} or {@code v}, or
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
              return pos.row - 1, pos.col - 1 + (past and 1 or 0)
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

    /** Pastes argument 1 at the cursor, in any mode, as a paste rather than typed keys. */
    static final String PASTE = "vim.api.nvim_paste(select(1, ...), false, -1)";

    /**
     * Sets the current window's working folder to argument 1 ({@code :lcd}): {@code :e} completion,
     * {@code :!} and {@code :find} then start in the attached editor's project. Silent: Neovim echoes
     * the new folder, a message that would replace the one the user is reading (the write message
     * of the {@code :w} that just switched editors, say).
     */
    static final String SET_WINDOW_FOLDER = "vim.cmd.lcd({ args = { select(1, ...) }, mods = { silent = true } })";

    static final String GET_CLIPBOARD_OPTION = "return vim.o.clipboard";

    /** Evaluates the Vim expression in argument 1 ({@code bufnr('a.txt')}) and returns its value. */
    static final String EVAL = "return vim.fn.eval(select(1, ...))";
}
