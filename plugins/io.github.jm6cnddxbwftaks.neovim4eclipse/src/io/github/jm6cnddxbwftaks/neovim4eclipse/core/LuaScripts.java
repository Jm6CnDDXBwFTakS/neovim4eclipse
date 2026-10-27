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
     *   <li>The diagnostics namespace {@code neovim4eclipse} ({@link #SET_DIAGNOSTICS}) draws nothing,
     *       and, unless the user's config has its own, a {@code vim.diagnostic} {@code on_jump} hook
     *       echoes the first line of the problem {@code ]d} lands on.</li>
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
            -- Eclipse's problems arrive as diagnostics in the namespace 'neovim4eclipse' (SET_DIAGNOSTICS),
            -- for Neovim's own ]d [d ]D [D. Nothing of them is drawn: Eclipse already underlines them
            -- and marks the ruler, and Neovim's window is never seen anyway. Other namespaces (a
            -- user's nvim-lint) keep their own display settings.
            local diagnostics_ns = vim.api.nvim_create_namespace('neovim4eclipse')
            vim.diagnostic.config({ virtual_text = false, virtual_lines = false, signs = false,
              underline = false, update_in_insert = false }, diagnostics_ns)
            -- After ]d, the message of the problem landed on goes to the message line, as Eclipse's
            -- status bar shows it after Ctrl+. (Next Annotation). Neovim's on_jump hook (0.12) runs
            -- after the cursor moved. Only when init.lua set no hook of its own (nor the older
            -- jump.float): a user's choice wins. The hook is global, so this echoes any namespace's
            -- diagnostic, ours or nvim-lint's. Only the first line: a message line holds one, and a
            -- longer echo would open the output box on every jump (K shows the whole hover).
            local jump = vim.diagnostic.config().jump or {}
            if jump.on_jump == nil and not jump.float then
              vim.diagnostic.config({ jump = { on_jump = function(diagnostic)
                if diagnostic and diagnostic.message then
                  vim.api.nvim_echo({ { (diagnostic.message:gsub('\\n.*', '')) } }, false, {})
                end
              end } })
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
     *       {@code :Eclipse tabnext 3}, {@code :q!} {@code :Eclipse! quit}. Each command after a
     *       {@code |} gets the same treatment ({@code :Eclipse} is defined with {@code bar}):
     *       {@code :w | q} becomes {@code :w | Eclipse quit}, so Neovim writes through the write
     *       hook ({@link #DOCUMENT_BUFFER}) and Eclipse then closes the editor, asking first if the
     *       save failed. Left to Neovim, that {@code q} would end the embedded Neovim on its last
     *       window. A command that reads {@code |} as part of its argument ({@code :normal},
     *       {@code :g}, {@code :let}; {@code nvim_parse_cmd()} gives it no next command) keeps the
     *       rest of its line.</li>
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
     * {@code :bufdo} runs, or after a {@code |} that {@code nvim_parse_cmd()} does not split
     * ({@code :let x = 1 | q}): those reach Neovim. For the ones that close something
     * there are safety nets, which tell Eclipse but cannot stop Neovim: {@code QuitPre} here (a
     * {@code :quit} Neovim runs; on its last window Neovim ends, as experiment X7 found), and
     * {@code BufDelete} / {@code BufWipeout} on each document buffer ({@link #DOCUMENT_BUFFER}).
     * Both send {@code eclipse_buffer_closed}, and Eclipse closes the editor showing that buffer.
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

            -- The :Eclipse form of one parsed command (p, from nvim_parse_cmd; what follows a | is
            -- not part of it), or nil to leave the command to Neovim.
            local function eclipse_form(p)
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
              return cmd
            end

            -- The line to run instead of `line`, or nil to leave it alone. Each command of a | chain
            -- is looked at, not just the first: in `:w | q` Neovim writes (through Eclipse, the write
            -- hook), then Eclipse closes the editor, asking first if the save failed. Without this
            -- the q would be Neovim's, and on its last window Neovim would end.
            -- Example: `w | q` becomes `w | Eclipse quit`, `q | echo 1` becomes `Eclipse quit | echo 1`.
            -- nvim_parse_cmd gives what follows the | as nextcmd, the end of the line as typed
            -- (`w  |  q` has nextcmd `q`); a command that takes | as an argument (:normal, :g,
            -- :let) has none, so the rest of its line stays as typed.
            local function replacement(line)
              local ok, p = pcall(vim.api.nvim_parse_cmd, line, {})
              if not ok then
                return nil -- Neovim reports it when it runs the line
              end
              local own = eclipse_form(p)
              local rest -- the rest of the chain rewritten, or nil if none of it changed
              local after = p.nextcmd
              if after ~= '' and line:sub(-#after) == after then
                rest = replacement(after)
              end
              if own then
                return after == '' and own or (own .. ' | ' .. (rest or after))
              end
              if rest then
                return line:sub(1, #line - #after) .. rest -- this command as typed, its | included
              end
              return nil
            end

            -- Safety net: a :quit that Neovim runs itself (<Cmd>q, vim.cmd.quit(), :let x = 1 | q) closes the
            -- editor too. It cannot stop the quit (on the last window Neovim ends anyway). Not for the
            -- command-line window, which SETUP closes with :quit, nor while Eclipse ends Neovim (:qa!),
            -- which the session ignores.
            vim.api.nvim_create_autocmd('QuitPre', {
              desc = 'Eclipse closes the editor',
              callback = function()
                if vim.fn.getcmdwintype() == '' then
                  vim.rpcnotify(vim.g.neovim4eclipse_channel, 'eclipse_buffer_closed', vim.api.nvim_get_current_buf())
                end
              end,
            })

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
     * statusline, bufnr, block, full_mode, folds}}. {@code bufnr} tells whether the cursor is still in the
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
     * <p>The first argument, {@code "1"} or {@code "0"}, says whether to evaluate the {@code 'statusline'}
     * (a Vim-like default if the user has none), so that nothing is evaluated that the status bar
     * does not show. The second, {@code "1"} when folding is on ({@link #FOLDS}), adds {@code folds}:
     * the indexes of the outermost closed folds, from the fold module's {@code poll} (the per-key
     * trigger); with {@code "0"} it is an empty list that nothing reads. Messages and the pending command ({@code showcmd}) are not read here: Neovim
     * sends them as UI events ({@code ext_messages}; see {@code StatusTracker}).
     */
    static final String STATE_SNAPSHOT = """
            local want_statusline, want_folds = ...
            want_statusline, want_folds = want_statusline == '1', want_folds == '1'
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
            local folds = {}
            if want_folds and package.loaded['neovim4eclipse.folds'] then
              folds = package.loaded['neovim4eclipse.folds'].poll(vim.api.nvim_get_current_win())
            end
            return { c[1], c[2], s[2], s[3], e[2], e[3], mode, vim.fn.reg_recording(), statusline,
                     vim.api.nvim_get_current_buf(), block, vim.api.nvim_get_mode().mode, folds }
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
     * <p>A name another buffer has already, which {@code bufadd()} would return, gives a new buffer
     * left unnamed instead (the second result is {@code false}): two documents must not share one.
     */
    static final String CREATE_DOCUMENT_BUFFER = """
            local name = select(1, ...)
            if vim.fn.bufexists(name) == 1 then
              return { vim.api.nvim_create_buf(true, true), false }
            end
            local buf = vim.fn.bufadd(name)
            vim.bo[buf].buftype = 'nofile'
            vim.bo[buf].bufhidden = 'hide'
            vim.bo[buf].swapfile = false
            vim.bo[buf].modeline = false
            vim.cmd('noautocmd call bufload(' .. buf .. ')')
            vim.bo[buf].buflisted = true
            return { buf, true }
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
     *   <li>Switching away and back gives two messages before the next key, which the output popup
     *       shows together ({@code util/MessageRoute}); the popup takes the keyboard focus.</li>
     *   <li>Without the focus, Eclipse's Undo does not select the text it brings back (JFace selects
     *       it only in the focused widget), so Neovim's cursor ends up a line off and {@code jdd}
     *       deletes the wrong line ({@code FileInfoEndToEndTest}).</li>
     * </ul>
     * So {@code F} is added for the switch alone, and the user's value is put back right away:
     * Neovim decides about the message while entering the buffer, so this changes nothing about a
     * {@code :b} the user types, which Neovim runs by itself. {@code :silent} would also
     * hide the message, but it would hide every other message of the switch too, such as one from
     * the user's own {@code BufEnter} autocommand. README.md, "Neovim settings the plug-in changes",
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
     * namespace {@code neovim4eclipse} ({@code :help vim.diagnostic.set()}). Arguments: the buffer
     * number, then six per problem: lnum, col, end_lnum, end_col (0-based lines, byte columns),
     * severity (1 error, 2 warning, 3 info), message ({@code util/Diagnostic.toLuaArgs}). With the
     * number alone, the buffer's problems are cleared. A buffer that is gone (its editor closed
     * meanwhile) is left alone.
     *
     * <p>Each call replaces the whole list, so nothing is diffed. Neovim then moves the diagnostics
     * with the text as it is edited (extmarks) until Eclipse sends the next list. Example: arguments
     * {@code 3, 0, 4, 0, 7, 1, "x cannot be resolved"} put one error on line 1 of buffer 3, and
     * {@code ]d} from the start of the buffer goes there.
     */
    static final String SET_DIAGNOSTICS = """
            local args = { ... }
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
     * Installs the fold module, {@code require('neovim4eclipse.folds')}, unless the user's config
     * turned folding off with {@code vim.g.neovim4eclipse_folds = 0} (or {@code false}), and returns
     * whether folding is on. The session runs it once at start, right after {@link #SETUP} (so after
     * {@code init.lua}), and keeps the answer ({@code NeovimSession.foldsEnabled()}): when it is
     * off, no other fold script is ever sent, so the opt-out costs nothing (docs/design/FOLDING.md,
     * 3.6). Changing the variable later needs a Neovim restart.
     *
     * <p><b>What the module does.</b> Eclipse's fold regions become Neovim manual folds
     * ({@code 'foldmethod'=manual}, {@code :{first},{last}fold}) in the window that shows the
     * buffer, and each fold gets an extmark in the namespace {@code neovim4eclipse_folds} whose id is
     * the fold's index in Eclipse's list plus one (extmark ids start at 1). The marks move with the
     * text as the folds do, so a fold can be named to Eclipse after edits. Functions:
     * <ul>
     *   <li>{@code set(buf, folds)}, used by {@link #SET_FOLDS}: replaces the current window's folds.
     *       {@code folds} is a list of {@code {first, last, collapsed}} (1-based lines).</li>
     *   <li>{@code closed(win)}: the indexes (0-based) of the window's <em>outermost</em> closed
     *       folds. A fold inside a closed fold is never listed, whatever its state: Neovim cannot see
     *       it ({@code foldclosed()} gives the outer fold's first line for every line inside), so
     *       Eclipse keeps its own state for it ({@code util/FoldRegions.stateChange}).</li>
     *   <li>{@code watch(fn)} and {@code poll(win)}: the one place that decides <em>when</em> fold
     *       state is read. Neovim has no event for "a fold opened or closed" (no autocommand, and no
     *       UI event without {@code ext_multigrid}), so for now {@link #STATE_SNAPSHOT} calls
     *       {@code poll} after every key; {@code poll} returns {@code closed(win)} and calls every
     *       {@code watch} function when the list differs from the last one for that window and
     *       buffer. Should Neovim add a fold event, only this trigger changes. The first watcher
     *       fires {@code User Neovim4EclipseFolds} (with {@code data = { buf = 3, closed = { 0, 2 } }}),
     *       so a user's config can react to fold changes, for example:
     *       {@code vim.api.nvim_create_autocmd('User', { pattern = 'Neovim4EclipseFolds', callback = ... })}.</li>
     * </ul>
     *
     * <p><b>How {@code set} closes exactly the collapsed folds.</b> Each fold is created open, outer
     * first ({@code :fold} makes a closed fold, and a range over a closed fold would grow to cover all
     * of it: experiment X3). Then the collapsed ones are closed, inner first, each with
     * {@code :{l}foldclose} on one <em>handle line</em> {@code l}: the first line of the fold that no
     * open fold inside it covers. {@code :foldclose} closes the innermost open fold on each line of
     * its range (X11), and on a line covered only by closed inner folds it closes the fold around them
     * (X13). Example: a collapsed class on lines 1-20 whose lines 1-8 are an open method is closed on
     * line 9. When open inner folds cover every line, the first line is used and the innermost of
     * them closes instead; the next snapshot tells Eclipse.
     *
     * <p><b>After {@code zn}.</b> {@code 'nofoldenable'} hides every fold's state from
     * {@code foldclosed()}, and Eclipse, which shows what Neovim asked, has all its regions expanded.
     * A resend (after a reconcile, say) with Eclipse's states would mark them all open, and {@code zi}
     * would then bring back nothing. So while the window has {@code 'nofoldenable'}, {@code set} first
     * reads the states Neovim keeps: it turns folding on for the length of the call (nothing is
     * redrawn inside one call), opens the outermost closed folds level by level, noting each range,
     * and then uses those states for the folds whose range is unchanged; new folds take Eclipse's.
     * {@code 'nofoldenable'} is set again at the end, and {@code zi} restores the user's folds.
     *
     * <p>An empty list means the editor cannot fold: the window gets {@code 'nofoldenable'}
     * (window-local), so a user's {@code 'foldmethod'} cannot hide lines that Eclipse shows, and the
     * marks and {@code b:neovim4eclipse_folds} (the "mirrored" flag) are removed. All options are set
     * window-local ({@code :setlocal}), so other windows and the user's global values are untouched.
     */
    static final String FOLDS = """
            local enabled = vim.g.neovim4eclipse_folds
            if enabled == 0 or enabled == false then
              return false
            end
            local M = {}
            local ns = vim.api.nvim_create_namespace('neovim4eclipse_folds')
            M.ns = ns
            local watchers, last = {}, {}

            local function setlocal(name, value)
              vim.api.nvim_set_option_value(name, value, { scope = 'local', win = 0 })
            end

            -- The marks of buf as { first, last, index } (1-based lines, 0-based index), in index order.
            local function marks(buf)
              local found = {}
              for _, m in ipairs(vim.api.nvim_buf_get_extmarks(buf, ns, 0, -1, { details = true })) do
                found[#found + 1] = { m[2] + 1, m[4].end_row + 1, m[1] - 1 }
              end
              table.sort(found, function(a, b) return a[3] < b[3] end)
              return found
            end

            -- Whether the fold over first..last is closed and not inside another closed fold.
            local function outermost_closed(first, last)
              return vim.fn.foldclosed(first) == first and vim.fn.foldclosedend(first) == last
            end

            function M.closed(win)
              local buf = vim.api.nvim_win_get_buf(win)
              if not vim.b[buf].neovim4eclipse_folds then
                return {}
              end
              return vim.api.nvim_win_call(win, function()
                local ids = {}
                if not vim.wo.foldenable then
                  return ids -- zn: Eclipse shows every fold open, as Neovim does
                end
                for _, m in ipairs(marks(buf)) do
                  if outermost_closed(m[1], m[2]) then
                    ids[#ids + 1] = m[3]
                  end
                end
                return ids
              end)
            end

            function M.watch(fn)
              watchers[#watchers + 1] = fn
            end

            function M.poll(win)
              local ids = M.closed(win)
              local buf = vim.api.nvim_win_get_buf(win)
              local key = win .. ':' .. buf
              local joined = table.concat(ids, ',')
              if (last[key] or '') ~= joined then
                for _, fn in ipairs(watchers) do
                  pcall(fn, ids, buf)
                end
              end
              last[key] = joined
              return ids
            end

            M.watch(function(ids, buf)
              vim.api.nvim_exec_autocmds('User', { pattern = 'Neovim4EclipseFolds', modeline = false,
                data = { buf = buf, closed = ids } })
            end)

            -- zn in effect: the closed state of every existing fold, by 'first:last' (true closed).
            local function states_kept(buf)
              local states = {}
              setlocal('foldenable', true)
              local opened = true
              while opened do
                opened = false
                for _, m in ipairs(marks(buf)) do
                  local key = m[1] .. ':' .. m[2]
                  if states[key] == nil and outermost_closed(m[1], m[2]) then
                    states[key] = true
                    vim.cmd(m[1] .. 'foldopen') -- one level: this fold; the ones inside stay as they are
                    opened = true
                  end
                end
              end
              for _, m in ipairs(marks(buf)) do
                local key = m[1] .. ':' .. m[2]
                if states[key] == nil then
                  states[key] = false
                end
              end
              return states
            end

            -- The first line of folds[i] that no open fold inside it covers (see the comment above).
            local function handle_line(folds, i, closed)
              local f = folds[i]
              local line = f[1]
              local moved = true
              while moved and line <= f[2] do
                moved = false
                for j = i + 1, #folds do
                  local g = folds[j]
                  if g[1] > f[2] then
                    break -- sorted by first line: no later fold is inside f
                  end
                  if not closed[j] and g[2] <= f[2] and g[1] <= line and line <= g[2] then
                    line, moved = g[2] + 1, true
                  end
                end
              end
              return line <= f[2] and line or f[1]
            end

            function M.set(buf, folds)
              local view = vim.fn.winsaveview()
              local kept = nil
              if #folds > 0 and vim.b[buf].neovim4eclipse_folds and not vim.wo.foldenable then
                kept = states_kept(buf)
              end
              vim.api.nvim_buf_clear_namespace(buf, ns, 0, -1)
              if #folds == 0 then
                setlocal('foldenable', false)
                vim.b[buf].neovim4eclipse_folds = nil
                return
              end
              setlocal('foldmethod', 'manual')
              setlocal('foldenable', true)
              setlocal('foldminlines', 0)
              vim.cmd('silent! normal! zE')
              local count = vim.api.nvim_buf_line_count(buf)
              local closed = {}
              for i, f in ipairs(folds) do
                local first, last = f[1], math.min(f[2], count)
                closed[i] = f[3]
                if kept and kept[f[1] .. ':' .. f[2]] ~= nil then
                  closed[i] = kept[f[1] .. ':' .. f[2]]
                end
                if first < last then
                  vim.cmd(first .. ',' .. last .. 'fold')
                  vim.cmd(first .. 'foldopen')
                  vim.api.nvim_buf_set_extmark(buf, ns, first - 1, 0, { id = i, end_row = last - 1, end_col = 0 })
                else
                  closed[i] = false -- past the end of the buffer: no fold, so nothing to close
                end
              end
              for i = #folds, 1, -1 do
                if closed[i] then
                  vim.cmd(handle_line(folds, i, closed) .. 'foldclose')
                end
              end
              vim.b[buf].neovim4eclipse_folds = true
              if kept then
                setlocal('foldenable', false)
              end
              vim.fn.winrestview(view)
            end

            package.loaded['neovim4eclipse.folds'] = M
            return true
            """;

    /**
     * Replaces the folds of the window showing a buffer with Eclipse's (arguments: the buffer
     * number, then first line, last line, {@code "1"} if collapsed, per fold, from
     * {@code util/FoldRegions.toLuaArgs}). Done only when that buffer is the current one: folds
     * belong to a window, and another buffer gets its folds when Eclipse shows it. With the number
     * alone the editor cannot fold, and folding is turned off in the window. How: {@link #FOLDS}'
     * {@code set}. Sent only when folding is on, so the module is always there.
     *
     * <p>Example: {@code 3, 1, 20, 0, 10, 15, 1} gives buffer 3 an open fold over lines 1-20 with a
     * closed one over lines 10-15 inside it; {@code zj} from line 1 then goes to line 10.
     */
    static final String SET_FOLDS = """
            local args = { ... }
            local buf = tonumber(args[1])
            local folds = package.loaded['neovim4eclipse.folds']
            if not folds or not buf or vim.api.nvim_get_current_buf() ~= buf then
              return
            end
            local list = {}
            for i = 2, #args - 2, 3 do
              list[#list + 1] = { tonumber(args[i]), tonumber(args[i + 1]), args[i + 2] == '1' }
            end
            folds.set(buf, list)
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
            -- Like a file just read: not modified. NeovimSession then sets 'modified' to Eclipse's dirty
            -- flag (an editor with unsaved changes stays modified, so :update writes it).
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
     * {@link DocumentWrite}) and, if that worked, clears {@code 'modified'}, as a real write does, and
     * shows the message Eclipse answers, Vim's {@code "a.txt" 12L, 140B written}, which Neovim does
     * not give for such a buffer. So {@code :w}, {@code :update}, {@code :bufdo %s/a/b/g | update},
     * {@code :w other.txt} and {@code vim.cmd.write()} all write through Eclipse. {@code v:cmdarg} passes {@code ++p} along.
     * {@code 'modified'} itself follows Eclipse's dirty flag ({@link #SET_MODIFIED}), so
     * {@code :update} asks for exactly the files Eclipse would save. Neovim pipes {@code :w !cmd}
     * without these hooks (experiment X9 of the cleanup plan).
     *
     * <h2>Which file: the editor's, or a copy</h2>
     * {@code BufWriteCmd} gets the name being written ({@code ev.match}), and the script compares it
     * with the file of the editor, kept in {@code b:neovim4eclipse_file} when the buffer is made a
     * document buffer. Not with the buffer's name: {@code :saveas} and {@code :file} change that.
     * <ul>
     *   <li>The editor's file ({@code :w}, {@code :update}): Eclipse saves the editor (request kind
     *       {@code ""}), and {@code 'modified'} is cleared.</li>
     *   <li>Another file ({@code :w other.txt}, {@code :w! other.txt}): a copy, as in Vim (kind
     *       {@code "copy"}, with the full name). Eclipse writes the document's text there; the editor
     *       stays on its own file and keeps its unsaved changes, so {@code 'modified'} does not change.
     *       Neovim itself refuses an existing file without {@code !} (E13), before the hook.</li>
     *   <li>The buffer's new name after {@code :saveas new.txt}, which renames the buffer and then
     *       writes it: Eclipse saves the document as that file and moves the editor there (kind
     *       {@code "saveas"}); then {@code b:neovim4eclipse_file} is the new file. (Before, Eclipse
     *       saved the editor's old file while Neovim called the buffer by the new name: experiment X1
     *       of the plan "writes under another name".)</li>
     * </ul>
     * The buffer must keep its editor's name, so {@code BufFilePost} checks it once the command is
     * over ({@code vim.schedule}) and puts the old name back after {@code :file new.txt} (with a
     * message saying why) or a {@code :saveas} Eclipse refused (whose error is already shown). The
     * rename back is {@code :keepalt noautocmd file}, which leaves the buffer {@code [Not edited]}
     * until its next write, as any {@code :file} does; the plug-in's own buffers avoid that mark by
     * being named with {@code bufadd()} ({@link #CREATE_DOCUMENT_BUFFER}), which cannot rename.
     *
     * <p>A write or rename under another name leaves a buffer of that name behind, empty and not
     * loaded: Vim's alternate file ({@code :help cpo-A}, {@code cpo-f}). It is wiped once the command
     * is over, since a buffer of that name would make {@link #CREATE_DOCUMENT_BUFFER} leave the buffer
     * of an editor opened on that file later unnamed. So {@code :e #} has no file after these
     * commands (the plug-in's {@code :e} does not expand {@code #} anyway).
     * Some lines ({@code :5,10w part.txt}) run {@code FileWriteCmd} instead, and an append
     * ({@code :w >> log.txt}, {@code :5,10w >> log.txt}) {@code FileAppendCmd}; Neovim marks the lines
     * with {@code '[} and {@code ']}, which the hooks send (request kinds {@code "lines"} and
     * {@code "append"}, with {@code v:cmdbang}: {@code :w! >> new.txt} may create the file). Both are
     * refused for the editor's own file, which Eclipse saves only whole: {@code :5,10w!} would leave
     * only those lines on disk under an editor that has them all. Without such a hook Neovim refused
     * them itself (E140, with {@code !} E676).
     * The names are compared after {@code resolve()} and {@code vim.fs.normalize}: Neovim resolves
     * symbolic links in a buffer's name but not in the written name (on macOS {@code /var} is a link
     * to {@code /private/var}, so the buffer is {@code /private/var/.../A.txt} while {@code :update}
     * writes {@code /var/.../A.txt}), and on Windows it reports the written name with forward slashes
     * while the buffer keeps backslashes.
     *
     * <p>A failed save fails the write, as a real failed write ({@code E212: Can't open file for
     * writing}) does: {@code nvim_echo}'s {@code err = true} (Neovim 0.11) works like
     * {@code :echoerr}, so inside the autocommand it aborts the {@code :write} that triggered it. The
     * error reaches whoever ran the command: through the API ({@code nvim_command}, {@code vim.cmd})
     * the command fails and {@code :bufdo update} stops at that buffer. A typed line, though, goes on
     * after {@code |}: in {@code :w | let g:x = 1} the {@code let} still runs (checked in
     * {@code tests/unit/src/test/lua/document_buffer_spec.lua}, and the same in Neovim's own TUI).
     * {@code v:errmsg} holds the text alone
     * ({@code Eclipse could not save A.txt: read-only}); the message Neovim shows puts its usual line
     * in front, {@code Error in BufWriteCmd Autocommands for "<buffer=3>":}, as for any error in an
     * autocommand. Being two lines, it opens the output popup ({@code util/MessageRoute}), where Vim
     * would show it above a "Press ENTER" prompt. The buffer stays modified, so {@code :update} tries
     * again next time.
     *
     * <h2>Deleted by Neovim: a safety net</h2>
     * A {@code :bd} on Neovim's command line reaches Eclipse as {@code :Eclipse bdelete}
     * ({@link #HOST_COMMANDS}), which closes the editor. One Neovim runs itself ({@code <Cmd>bd<CR>},
     * {@code vim.cmd.bdelete()}, {@code :bufdo bd}) deletes the buffer first; its {@code BufDelete}
     * or {@code BufWipeout} then sends {@code eclipse_buffer_closed}, and Eclipse closes the editor
     * showing it, asking about changes as usual (decision D10 of the cleanup plan). When Eclipse
     * wipes a buffer itself (its last editor closed), no editor shows it any more and the notice is
     * ignored.
     */
    static final String DOCUMENT_BUFFER = """
            local buf = tonumber(select(1, ...))
            vim.bo[buf].buftype = 'acwrite'
            -- The editor's file, as Neovim names it (links resolved). Writes are compared with it, not
            -- with the buffer's name, which :saveas and :file change.
            vim.b[buf].neovim4eclipse_file = vim.api.nvim_buf_get_name(buf)

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
            -- Whether `name` is the editor's file, or the buffer's name (renamed by :saveas or :file).
            local function own(b, name)
              local c = canonical(name)
              return c == canonical(vim.b[b].neovim4eclipse_file) or c == canonical(vim.api.nvim_buf_get_name(b))
            end
            -- Writing or renaming under another name leaves a buffer of that name behind, empty and not
            -- loaded: Vim's alternate file (:help cpo-A, cpo-f). Such a buffer would keep the plug-in from
            -- naming the buffer of an editor opened on that file later (CREATE_DOCUMENT_BUFFER), so it is
            -- wiped once the command is over. A document buffer, or any loaded one, is never touched.
            local function wipe_left_over(keep, names)
              vim.schedule(function()
                for _, other in ipairs(vim.api.nvim_list_bufs()) do
                  local name = vim.api.nvim_buf_get_name(other)
                  if other ~= keep and not vim.api.nvim_buf_is_loaded(other) and not vim.b[other].neovim4eclipse_file
                      and vim.tbl_contains(names, name) then
                    pcall(vim.api.nvim_buf_delete, other, { force = true })
                  end
                end
              end)
            end
            -- Asks Eclipse to write, and shows its answer. kind '' saves the editor; 'copy', 'lines' and
            -- 'append' write to the file `name` (lines first..last). Neovim waits for the answer:
            -- Eclipse writes on its UI thread (see EditorAttachManager). v:cmdarg has the write's
            -- ++opt arguments (" ++p" for :w ++p); v:cmdbang the !, which lets >> create a file.
            local function ask(b, kind, name, first, last)
              local answer = vim.rpcrequest(vim.g.neovim4eclipse_channel, 'eclipse_write', b, vim.v.cmdarg,
                kind, name, first, last, vim.v.cmdbang == 1)
              local err, written = answer[1], answer[2] -- { error, message }, each '' when none
              if err ~= '' then
                fail(err)
                return false
              end
              if written ~= '' then
                -- Vim's own message after a write, which Neovim does not give for an acwrite buffer.
                vim.api.nvim_echo({ { written } }, true, {})
              end
              return true
            end

            -- Buffers whose :saveas asked Eclipse, so their rename is no :file (see BufFilePost below).
            local saveas_tried = {}

            vim.api.nvim_create_autocmd('BufWriteCmd', {
              buf = buf, -- named "buffer" before Neovim 0.12.2
              desc = 'Eclipse saves the document',
              callback = function(ev)
                local file = vim.b[ev.buf].neovim4eclipse_file
                local target = vim.fn.fnamemodify(ev.match, ':p')
                if canonical(target) == canonical(file) then
                  if ask(ev.buf, '', '', 0, 0) then
                    vim.bo[ev.buf].modified = false
                  end
                elseif own(ev.buf, target) then
                  -- :saveas new.txt: Neovim renamed the buffer, then writes it under the new name.
                  -- Eclipse saves the document as that file and moves the editor there.
                  saveas_tried[ev.buf] = true -- the BufFilePost check below then stays quiet
                  if ask(ev.buf, 'saveas', target, 0, 0) then
                    vim.b[ev.buf].neovim4eclipse_file = vim.api.nvim_buf_get_name(ev.buf)
                    vim.bo[ev.buf].modified = false
                  end
                else
                  -- :w other.txt: the text to another file, as in Vim. The editor's changes stay unsaved.
                  ask(ev.buf, 'copy', target, 0, 0)
                  wipe_left_over(ev.buf, { ev.match, target })
                end
              end,
            })
            -- :5,10w part.txt and :w >> log.txt: some or all lines to another file, between the marks
            -- '[ and '] that Neovim sets for the write. Not to the editor's own file, which Eclipse
            -- saves whole: :5,10w! would leave only those lines on disk, and >> would double the file.
            for event, kind in pairs({ FileWriteCmd = 'lines', FileAppendCmd = 'append' }) do
              vim.api.nvim_create_autocmd(event, {
                buf = buf,
                desc = 'Eclipse writes the lines to another file',
                callback = function(ev)
                  local target = vim.fn.fnamemodify(ev.match, ':p')
                  if own(ev.buf, target) then
                    return fail('Eclipse saves this file only whole; use :w')
                  end
                  ask(ev.buf, kind, target, vim.fn.line("'["), vim.fn.line("']"))
                  wipe_left_over(ev.buf, { ev.match, target })
                end,
              })
            end
            -- The buffer keeps the editor's name. :file new.txt renames it without a file (the editor
            -- would still show the old one), and so does a :saveas that Eclipse could not carry out:
            -- once the command is over (scheduled), the old name is put back; only :file says why.
            -- A rename also leaves a buffer behind under the other name (wipe_left_over).
            local names_before = {}
            vim.api.nvim_create_autocmd('BufFilePre', {
              buf = buf,
              callback = function(ev)
                names_before[ev.buf] = vim.api.nvim_buf_get_name(ev.buf)
              end,
            })
            vim.api.nvim_create_autocmd('BufFilePost', {
              buf = buf,
              desc = 'Eclipse keeps the buffer named after its file',
              callback = function(ev)
                local b = ev.buf
                local names = { names_before[b], vim.api.nvim_buf_get_name(b) }
                names_before[b] = nil
                vim.schedule(function()
                  if not vim.api.nvim_buf_is_valid(b) then
                    return
                  end
                  local quiet = saveas_tried[b]
                  saveas_tried[b] = nil
                  local file = vim.b[b].neovim4eclipse_file
                  if canonical(vim.api.nvim_buf_get_name(b)) ~= canonical(file) then
                    vim.api.nvim_buf_call(b, function()
                      vim.cmd('keepalt noautocmd file ' .. vim.fn.fnameescape(file))
                    end)
                    if not quiet then
                      fail('file: Eclipse names this buffer after its file; use :saveas or File > Save As')
                    end
                  end
                  wipe_left_over(b, names)
                end)
              end,
            })
            -- Safety net: Neovim deleting this buffer by itself (<Cmd>bd, Lua, :bufdo bd) closes its
            -- editor too. Eclipse ignores it for a buffer no editor shows (one it wiped itself).
            vim.api.nvim_create_autocmd({ 'BufDelete', 'BufWipeout' }, {
              buf = buf,
              desc = 'Eclipse closes the editor',
              callback = function(ev)
                vim.rpcnotify(vim.g.neovim4eclipse_channel, 'eclipse_buffer_closed', ev.buf)
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

    /**
     * Moves Neovim out of folder argument 1 and everything under it, before Eclipse deletes or
     * closes the project there, and returns how many working folders it moved (0 when none was in
     * it). Each working folder inside it, the global one ({@code :cd}), a tab page's ({@code :tcd})
     * and a window's ({@code :lcd}), becomes the folder's parent; the others stay as they are.
     *
     * <p>Why: Neovim's process works in the current window's folder, the attached editor's project
     * ({@link #SET_WINDOW_FOLDER}), and Windows does not delete a folder a process works in: deleting
     * that project failed with "The process cannot access the file because it is being used by
     * another process".
     *
     * <p>How: each scope's folder is read first and set again afterward where it changed, because
     * setting the global or a tab page's folder also drops the current window's own one
     * ({@code chdir(dir, 'global')} acts as {@code :cd}). A folder outside is left alone when it
     * did not change: it may be gone already (a project deleted earlier), and {@code :lcd} to it
     * would fail with E344. {@code nvim_win_call} sets another window's folder without entering it.
     * Paths are compared after {@code vim.fs.normalize} (forward slashes), and ignoring case on
     * Windows, where {@code C:\Work\app} and {@code c:/work/app} are one folder. Example:
     * the window works in {@code /ws/app/src}; leaving {@code /ws/app} moves it to {@code /ws}.
     */
    static final String LEAVE_FOLDER = """
            local folder = ...
            local win32 = vim.fn.has('win32') == 1
            local function key(path)
              path = vim.fs.normalize(path)
              return win32 and path:lower() or path
            end
            local inner = key(folder)
            local function inside(dir)
              dir = key(dir)
              return dir == inner or vim.startswith(dir, inner .. '/')
            end
            local refuge = vim.fs.dirname(vim.fs.normalize(folder))
            local function kept(dir) return inside(dir) and refuge or dir end
            local silent = { silent = true }
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
                  pcall(vim.cmd[command], { args = { target }, mods = silent })
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
}
