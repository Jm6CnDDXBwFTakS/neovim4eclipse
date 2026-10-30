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
     *   <li>{@code timeout} and {@code timeoutlen} are left alone: Neovim's default (1000 ms) or the
     *       user's {@code init.lua} decides how long Neovim waits for a mapping's second key. A
     *       shorter wait made a slowly typed {@code [d} Vim's older {@code [d} (search for a macro
     *       definition, E388) instead of the diagnostics mapping, as it does in plain Neovim when
     *       the pause is longer than the wait.</li>
     *   <li>{@code showmode=false}: Eclipse shows the mode in its own status line.</li>
     *   <li>Autocmds emit {@code eclipse_mode}, {@code eclipse_cursor} (with the buffer number,
     *       the window's top line, {@code line('w0')}, and the window id),
     *       {@code eclipse_buf_enter} (the buffer and the window id: with a window per editor,
     *       docs/design/WINDOWS.md, the window tells two editors on one file apart; whether the move
     *       is in the jump history; and the file of a project's buffer without an editor, from
     *       {@link #PROJECT_FILES}, or {@code ""}) and {@code eclipse_yank} notifications handled by
     *       {@link NotificationBridge}.</li>
     *   <li>Unless the user's config sets {@code g:clipboard}, a clipboard provider whose copy sends
     *       {@code eclipse_clipboard_copy} and whose paste is the {@code eclipse_clipboard_paste}
     *       request (see {@link ClipboardRegisters}).</li>
     *   <li>The one argument is Eclipse's RPC channel number, which requests are sent to. It is kept
     *       in {@code g:neovim4eclipse_channel} for {@link #DOCUMENT_BUFFER}'s write hook.</li>
     *   <li>The mappings of {@code gd}, {@code K}, the {@code gr*} keys, {@code gt} and {@code gT},
     *       which send {@code eclipse_action} so that the IDE does those, are {@link #IDE_KEYS}, run
     *       right after this script; {@code ZZ}, {@code ZQ} and the history keys below are here.</li>
     *   <li>{@code Ctrl-O} and {@code Ctrl-I} past the end of a window's jumps are Eclipse's Back and
     *       Forward; a trail of the {@code Ctrl-O} steps (module {@code neovim4eclipse.history},
     *       {@link #HISTORY_NOWHERE}) lets {@code Ctrl-I} undo the newest step first.</li>
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
            -- The window's top line comes along: Neovim has already scrolled when CursorMoved fires,
            -- so the editor can scroll first and then place the caret (docs/design/VIEWPORT.md, 3.2).
            vim.api.nvim_create_autocmd({'CursorMoved', 'CursorMovedI'}, {
              callback = function()
                local p = vim.api.nvim_win_get_cursor(0)
                vim.rpcnotify(0, 'eclipse_cursor', p[1], p[2], vim.api.nvim_get_current_buf(), vim.fn.line('w0'),
                    vim.api.nvim_get_current_win())
              end
            })
            -- Each Eclipse document has its own buffer. When Neovim switches buffers by itself
            -- (Ctrl-O across files, 'A, :b), Eclipse follows by activating that file's editor.
            -- The third value: whether the move is in the window's jump history (it has an entry in
            -- the buffer left, the alternate one), as for :b, Ctrl-O or :bufdo, but not for
            -- :keepjumps or a script's nvim_win_set_buf with jumps kept out. Only then is Eclipse's
            -- record of the switch a repeat that Back may step over (util/HistorySteps).
            vim.api.nvim_create_autocmd('BufEnter', {
              callback = function(ev)
                local left, jumped = vim.fn.bufnr('#'), false
                if left > 0 and left ~= ev.buf then
                  for _, jump in ipairs(vim.fn.getjumplist()[1]) do
                    if jump.bufnr == left then
                      jumped = true
                      break
                    end
                  end
                end
                -- The fourth value: the file of a project's buffer that has no editor yet (gf, 'A,
                -- quickfix: PROJECT_FILES), whose editor Eclipse opens; '' for any other buffer.
                local project = package.loaded['neovim4eclipse.project_files']
                vim.rpcnotify(0, 'eclipse_buf_enter', ev.buf, vim.api.nvim_get_current_win(), jumped,
                  project and project.file_of(ev.buf) or '')
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
            -- Keys only the IDE can carry out (gd, K, the gr* keys, gt): IDE_KEYS, run right after
            -- this script, from the one table core/IdeKeys.
            -- Ctrl-O and Ctrl-I are Neovim's, until its jump history has nothing further that
            -- way: then they are Eclipse's Back and Forward (Alt+Left, Alt+Right), which also
            -- record editor switches made in Eclipse (a tab click, Ctrl+F6, F3). Each editor is a
            -- Neovim window with its own jump history (docs/design/WINDOWS.md, E2), and an
            -- Eclipse switch is no Neovim jump, so without this Ctrl-O after a tab click did
            -- nothing. Example: G in a.txt, click b.txt's tab, Ctrl-O: back in a.txt's editor
            -- on its last line. getjumplist() gives { entries, position }: the position is the
            -- entry Ctrl-O went to last, or #entries when it was not used since the last jump.
            -- A key init.lua maps itself is left alone, and that is the opt-out (no switch: the
            -- fallback acts only where Neovim would do nothing). vim.g.neovim4eclipse_ide_keys does
            -- not cover it: wanting Vim's gd is no reason to lose Ctrl-O back to the last editor.
            --
            -- Ctrl-I retraces Ctrl-O. The trail is what each Ctrl-O step did, newest last: a jump
            -- in a window ({ 'jump', n, win }, n jumps) or a Back step ({ 'back', id }, one entry
            -- per step). Ctrl-I undoes the newest steps: Neovim's Ctrl-I for jumps, Eclipse's
            -- Forward for Back steps, sent with the id of the oldest one to undo
            -- (editor/NavigationHistorySteps finds the way back; util/HistorySteps on why ids). Without
            -- the trail, Ctrl-I took a window's newer jump first, wherever it came in the run.
            -- Example: in b.txt, Ctrl-O jumps into a.txt's file (the window moves into a.txt's
            -- editor), then Backs go to b.txt's and a.txt's editors again; Ctrl-I in a.txt's editor
            -- must undo the last Back, not the first jump. A count goes as far as the newest run of
            -- one kind: 3<C-i> after two Back steps does nothing, as Vim's does past its history.
            -- The trail is forgotten when a key is typed between (anything but Ctrl-O, Ctrl-I and a
            -- count), when Eclipse says the user switched editors (history.forget), and when a jump
            -- step's window is no longer current or has no newer jump; then the keys work as
            -- without it.
            local trail = {}
            local next_id = 1
            local MAX_TRAIL = 100 -- a held-down key: the oldest steps go first
            local history = {}
            --- Eclipse reports Back steps that went nowhere (the start of its history, or nothing
            --- visible changed): steps first .. first + n - 1 leave the trail, so Ctrl-I does not
            --- have to undo them. A step Ctrl-I already took is simply not found.
            function history.nowhere(first, n)
              for i = #trail, 1, -1 do
                local s = trail[i]
                if s[1] == 'back' and s[2] >= first and s[2] < first + n then
                  table.remove(trail, i)
                end
              end
            end
            function history.forget()
              trail = {}
            end
            function history.trail()
              return vim.deepcopy(trail)
            end
            package.loaded['neovim4eclipse.history'] = history
            vim.on_key(function(_, typed)
              if typed ~= nil and typed ~= '' and #trail > 0 then
                -- typed can hold several keys (a key held down): each must be one of these.
                local other = vim.fn.keytrans(typed):gsub('<C%-O>', ''):gsub('<C%-I>', ''):gsub('<Tab>', '')
                if other:find('[^%d]') then
                  trail = {}
                end
              end
            end, vim.api.nvim_create_namespace('neovim4eclipse_history'))
            local function push(entry)
              trail[#trail + 1] = entry
              if #trail > MAX_TRAIL then
                table.remove(trail, 1)
              end
            end
            local function mapped(key)
              return vim.fn.maparg(key, 'n') ~= ''
            end
            if not mapped('<C-o>') then
              vim.keymap.set('n', '<C-o>', function()
                local n = math.max(1, vim.v.count)
                local _, place = unpack(vim.fn.getjumplist())
                if place > 0 then
                  if place >= n then -- else Neovim does nothing: a count larger than the history
                    local win, last = vim.api.nvim_get_current_win(), trail[#trail]
                    if last and last[1] == 'jump' and last[3] == win then
                      last[2] = last[2] + n
                    else
                      push({ 'jump', n, win })
                    end
                  end
                  return '<C-o>' -- not remapped (noremap): Neovim's own key, with the count typed
                end
                local first = next_id
                for id = first, first + n - 1 do
                  push({ 'back', id })
                end
                next_id = first + n
                vim.rpcnotify(0, 'eclipse_action', 'history_back', vim.v.count, first)
                return ''
              end, { expr = true, desc = 'Eclipse: older jump, else Back' })
            end
            if not mapped('<C-i>') then
              vim.keymap.set('n', '<C-i>', function()
                local n = math.max(1, vim.v.count)
                local list, place = unpack(vim.fn.getjumplist())
                local newer = place + 1 < #list
                local last = trail[#trail]
                if last and last[1] == 'jump' and (last[3] ~= vim.api.nvim_get_current_win() or not newer) then
                  trail, last = {}, nil
                end
                if not last then
                  if newer then
                    return '<C-i>'
                  end
                  vim.rpcnotify(0, 'eclipse_action', 'history_forward', vim.v.count, 0)
                  return ''
                end
                if last[1] == 'jump' then
                  if n > last[2] then
                    return '' -- past this run of jumps
                  end
                  last[2] = last[2] - n
                  if last[2] == 0 then
                    trail[#trail] = nil
                  end
                  return '<C-i>' -- with the count typed: n jumps
                end
                for i = #trail - n + 1, #trail do
                  if i < 1 or trail[i][1] ~= 'back' then
                    return '' -- past this run of Back steps
                  end
                end
                local oldest = trail[#trail - n + 1][2]
                for _ = 1, n do
                  trail[#trail] = nil
                end
                vim.rpcnotify(0, 'eclipse_action', 'history_forward', vim.v.count, oldest)
                return ''
              end, { expr = true, desc = 'Eclipse: newer jump, else Forward' })
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
     *   <li><b>Refused commands.</b> Other scripts can make a built-in command an error where it
     *       must not run, through {@code require('neovim4eclipse.host_commands').refuse(name, why)}:
     *       {@code why(p)} returns the reason, or {@code nil} to let the command run; {@code p} is
     *       the command as {@code nvim_parse_cmd()} gives it, or {@code nil} for an {@code :Eclipse
     *       name} typed or mapped as such. Such a line
     *       becomes {@code :Eclipse name}, which shows {@code name: reason} as an error and tells
     *       Eclipse nothing. A built-in command cannot be mapped, so this is the only way to stop
     *       one. Example: {@link #FOLDS} refuses {@code :fold} in buffers
     *       whose folds are Eclipse's, so {@code :3,5fold} shows
     *       {@code fold: Eclipse defines the folds in this editor}; {@link #WINDOW_COMMANDS}
     *       refuses {@code :tabclose 2}, but not {@code :tabclose}.</li>
     *   <li><b>Commands left to Neovim.</b> {@code leave_to_neovim(fn)}: when {@code fn(name)}
     *       returns true, the command is not replaced and runs as Neovim's, and the {@code QuitPre}
     *       safety net below says nothing for a {@code :quit}. Example: {@link #WINDOW_COMMANDS}
     *       leaves {@code :q} and {@code :close} typed in a floating window to Neovim, which closes
     *       that window, not the editor.</li>
     * </ol>
     *
     * <p>Why replace the line rather than cancel it ({@code v:event.abort} in {@code CmdlineLeave}):
     * a cancelled line is not "the last command line", so {@code @:} would fail with E30, while the
     * replaced one is, and {@code @:} repeats it. Only {@code :reg :} shows the replacement.
     * (Experiment X7 of the cleanup plan, 2026-10-26.)
     *
     * <p><b>A file name arrives as a full path</b> ({@code :e name}, {@code :wq name},
     * {@code :x name}). {@code :Eclipse} expands it as Neovim's own command would
     * ({@code expandcmd()}: {@code %:h/x}, {@code #}, {@code ~}) and makes it absolute against the
     * window's folder ({@code fnamemodify(':p')}, then {@code vim.fs.normalize}), so Eclipse uses the file Neovim would, and
     * {@code :e x}, {@code :w x} and {@code :wq x} mean the same file. {@code ./x} is that folder
     * too, as in Neovim; the file's own folder is {@code %:h/x}.
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
            local refusals = {} -- built-in command name -> function(p) returning why it may not run, or nil
            local leaves = {} -- functions(name) returning true to leave that command to Neovim
            -- The reasons eclipse_form found in the line about to run, by name: its :Eclipse form
            -- carries only the name, and the reason may depend on the rest (:tabclose 2).
            local refusing = {}
            package.loaded['neovim4eclipse.host_commands'] = {
              refuse = function(name, why)
                refusals[name] = why
              end,
              leave_to_neovim = function(fn)
                leaves[#leaves + 1] = fn
              end,
            }
            local function refused(name, p)
              return refusals[name] and refusals[name](p) or nil
            end
            local function left_to_neovim(name)
              for _, fn in ipairs(leaves) do
                if fn(name) then
                  return true
                end
              end
              return false
            end
            local file_commands = { edit = true, wq = true, xit = true, exit = true } -- take a file name
            vim.api.nvim_create_user_command('Eclipse', function(o)
              if typed then
                vim.fn.histdel(':', -1) -- the replacement
                vim.fn.histadd(':', typed)
                typed = nil
              end
              local name = o.fargs[1]
              local why = refusing[name] or refused(name)
              refusing[name] = nil
              if why then
                vim.api.nvim_echo({ { name .. ': ' .. why } }, true, { err = true })
                return
              end
              if not rules[name] then
                vim.api.nvim_echo({ { 'E492: Not an Eclipse command: ' .. name } }, true, { err = true })
                return
              end
              local args = vim.list_slice(o.fargs, 2)
              -- A file name (:e name, :wq name, :x name): Neovim turns it into the full path its
              -- own :edit or :write would use, and Eclipse uses that. expandcmd() expands what those
              -- commands expand (%, #, ~, $HOME; E194 when there is no alternate file for #), and
              -- fnamemodify(':p') makes the name absolute against the window's folder (getcwd(0):
              -- the project's root, or a folder you went to with :cd or :lcd), as Neovim's own
              -- :w other.txt does. ++opt words (++p) are left as they are. Example, editing
              -- src/Main.java in /ws/app: `:e notes.txt` and `:e ./notes.txt` send
              -- /ws/app/notes.txt, `:wq %:h/Copy.java` sends /ws/app/src/Copy.java.
              if file_commands[name] then
                for i, word in ipairs(args) do
                  if word:sub(1, 1) ~= '+' then
                    local ok, expanded = pcall(vim.fn.expandcmd, word, { errmsg = true })
                    if not ok then
                      vim.api.nvim_echo({ { (tostring(expanded):gsub('^Vim:', '')) } }, true, { err = true })
                      return
                    end
                    -- normalize: ../ resolved, as :edit shows the name (fnamemodify keeps it)
                    args[i] = vim.fs.normalize(vim.fn.fnamemodify(expanded, ':p'))
                  end
                end
              end
              vim.rpcnotify(vim.g.neovim4eclipse_channel, 'eclipse_command', name, o.bang, args)
            end, { nargs = '+', bang = true, bar = true, desc = 'Eclipse carries out an ex command (:Eclipse quit)' })

            -- The buffer of the document another editor has open when :e name is for its file, else
            -- nil: then Neovim runs :buffer of it (not the :edit as typed; see below). It enters that
            -- document's buffer, and Eclipse follows into the file's editor
            -- with the window, as for :b and Ctrl-^ (docs/design/WINDOWS.md, E2): the window's jump
            -- history and alternate file go along, so Ctrl-O goes back and :e # toggles between
            -- the two editors, as in one Vim window. Example: Nested.txt and root.txt open, :e
            -- root.txt, :e #, :e #: root.txt, Nested.txt, root.txt. Eclipse still opens a file
            -- with no editor yet (only Eclipse can make an editor), and runs :e! name, :e +cmd name
            -- and :e ++opt name, and :e of the current file (Neovim's would re-read the buffer).
            -- The name is read as :Eclipse reads it (expandcmd, fnamemodify ':p'); a # with no
            -- alternate file stays '#' and matches nothing, so Eclipse reports it.
            -- :buffer, not :edit: the one rule (PATHS) found the buffer, so Neovim must not match the
            -- name again its own way. It would not always agree: on Windows a junction to a file not
            -- on disk yet gives a new buffer, since Neovim matches such a name as written (found
            -- 2026-10-29). :buffer is a jump and sets the alternate file as :edit does.
            local canonical = require('neovim4eclipse.paths').canonical -- PATHS: case, links, separators
            local function open_document_edited(p)
              if p.cmd ~= 'edit' or p.bang or #p.args ~= 1 or p.args[1]:sub(1, 1) == '+' then
                return nil
              end
              local ok, expanded = pcall(vim.fn.expandcmd, p.args[1])
              if not ok or expanded == '' then
                return nil
              end
              local target = canonical(vim.fn.fnamemodify(expanded, ':p'))
              local current = vim.api.nvim_get_current_buf()
              for _, b in ipairs(vim.api.nvim_list_bufs()) do
                local file = vim.b[b].neovim4eclipse_file -- a document's buffer (DOCUMENT_BUFFER)
                -- Loaded only: entering a buffer Neovim has not read would run its read instead.
                if b ~= current and file and vim.api.nvim_buf_is_loaded(b) and canonical(file) == target then
                  return b
                end
              end
              return nil
            end

            -- The :Eclipse form of one parsed command (p, from nvim_parse_cmd; what follows a | is
            -- not part of it), or nil to leave the command to Neovim.
            local function eclipse_form(p)
              local why = refused(p.cmd, p)
              if why then
                refusing[p.cmd] = why
                return 'Eclipse ' .. p.cmd -- with any range and arguments: none of it runs
              end
              if left_to_neovim(p.cmd) then
                return nil -- :q in a floating window: Neovim closes that window
              end
              local open = open_document_edited(p)
              if open then
                return 'buffer ' .. open -- :e of a file another editor has open: Neovim enters it (see above)
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
                -- Nor for a quit left to Neovim (leave_to_neovim): it closes a floating window, whose
                -- buffer may be a document's (a plug-in's preview of another file).
                if vim.fn.getcmdwintype() == '' and not left_to_neovim('quit') then
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
                refusing = {} -- a line that never got to its :Eclipse (an error before it) left some
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
     * {@code {1, 1, 2, 2, 1, 2}}. 5000 lines take about 3 ms (experiment X5).
     *
     * <p>The first argument is the status bar's statusline width in columns, {@code "0"} when it shows
     * no statusline (then nothing is evaluated). Neovim evaluates the {@code 'statusline'} (a Vim-like
     * default if the user has none) at that width ({@code maxwidth}), so {@code %<} shortens and
     * {@code %=} fills to the label's size. Example: {@code "60"} gives at most 60 columns. The second, {@code "1"} when folding is on ({@link #FOLDS}), adds {@code folds}:
     * the indexes of the outermost closed folds, from the fold module's {@code poll} (the per-key
     * trigger); with {@code "0"} it is an empty list that nothing reads. Messages and the pending command ({@code showcmd}) are not read here: Neovim
     * sends them as UI events ({@code ext_messages}; see {@code StatusTracker}).
     */
    static final String STATE_SNAPSHOT = """
            local statusline_width, want_folds = ...
            statusline_width, want_folds = tonumber(statusline_width) or 0, want_folds == '1'
            local c = vim.api.nvim_win_get_cursor(0)
            local s = vim.fn.getpos('v')
            local e = vim.fn.getpos('.')
            local statusline = ''
            if statusline_width > 0 then
              local fmt = vim.o.statusline
              if fmt == nil or fmt == '' then fmt = '%<%f %h%m%r%=%-14.(%l,%c%V%) %P' end
              local ok, res = pcall(vim.api.nvim_eval_statusline, fmt, { maxwidth = statusline_width })
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
                     vim.api.nvim_get_current_buf(), block, vim.api.nvim_get_mode().mode, folds,
                     vim.api.nvim_get_current_win() }
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
     * <p><b>A buffer of that name already</b> ({@code bufadd()} returns it) is the same file, and is
     * reused, as Vim's {@code :e} reuses the buffer {@code :bd} kept (docs/design/BUFFER_NUMBERS.md):
     * same number, marks and last cursor position. Example: a.txt was buffer 1, its editor closed
     * (CLOSE_DOCUMENT_BUFFER unloaded it), and opening it again gives buffer 1, not 3. It may be
     * loaded already, with the file's text from disk, when Neovim moved into it by itself
     * ({@code :b 1}, {@code Ctrl-^}); Eclipse's text replaces that. Only a buffer that is another
     * document's now ({@code acwrite}, see {@link #DOCUMENT_BUFFER}), or a special one (help, a
     * terminal), is not reused: a new buffer is made, left unnamed (the second result is
     * {@code false}), since two documents must not share one. The third result says whether the
     * buffer was reused: its first push then puts the cursor on {@code '"} ({@link #GO_TO_LAST_POSITION}).
     */
    static final String CREATE_DOCUMENT_BUFFER = """
            local name = select(1, ...)
            local buf
            local reused = vim.fn.bufexists(name) == 1
            if reused then
              buf = vim.fn.bufadd(name) -- the existing one, by its exact name
              if vim.bo[buf].buftype ~= '' then
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
     * Puts the cursor of the current window on the current buffer's {@code '"} mark, where the cursor
     * was when its window last left it, and returns whether it did (not when the mark is unset or
     * past the end). Run after the first push into a reused buffer (CREATE_DOCUMENT_BUFFER): Vim puts
     * the cursor there on {@code :e} of a buffer {@code :bd} kept, and the editor's caret follows
     * (docs/design/BUFFER_NUMBERS.md, Q2). Example: a.txt closed with the cursor on line 40; opened
     * again, its caret is on line 40, not line 1.
     */
    static final String GO_TO_LAST_POSITION = """
            local mark = vim.api.nvim_buf_get_mark(0, '"')
            if mark[1] < 1 or mark[1] > vim.api.nvim_buf_line_count(0) then
              return false
            end
            return pcall(vim.api.nvim_win_set_cursor, 0, mark)
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
     * Installs the module {@code neovim4eclipse.windows}: a Neovim window per Eclipse editor, each
     * alone in its own tabpage (docs/design/WINDOWS.md; Java side {@code DocumentBuffers} and
     * {@code EditorWindows}). Argument: the home buffer's number. Returns the <em>home window</em>'s
     * id: the window the plug-in started in, which shows a document when its editor has no window of
     * its own, and is never closed by the plug-in.
     *
     * <p>Why tabpages: one window per tab fills Neovim's screen, which is the attached editor's size
     * (VIEWPORT.md), so {@code H}, {@code L} and {@code Ctrl-D} match the editor, and window commands
     * stay inside the editor's tab. {@code 'showtabline'} is set to 0, a setting the plug-in changes
     * (README, "Neovim settings the plug-in changes"): Neovim's default, 1, would show a tab line as
     * soon as a second editor has a window, and take a row from the editor.
     *
     * <p>The functions, each called by one of the scripts below:
     * <ul>
     *   <li>{@code open(buf, folder)}: {@link #OPEN_WINDOW}.</li>
     *   <li>{@code show(win, buf)}: {@link #SHOW_WINDOW}.</li>
     *   <li>{@code travel(win, buf, line, col)}: {@link #TRAVEL_WINDOW}.</li>
     *   <li>{@code close(win, fallback)}: {@link #CLOSE_WINDOW}.</li>
     *   <li>{@code release(buf, fallback)}: {@link #RELEASE_BUFFER}.</li>
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
     */
    static final String WINDOWS = """
            local home_buf = tonumber((select(1, ...)))
            local M = {}
            local home = vim.api.nvim_get_current_win()
            -- The windows the plug-in opened and has not closed (the home window and the editors'):
            -- any other plain window is a stray one (the WinNew safety net below), and a floating
            -- or special one is a side window (M.is_side_window).
            local own = { [home] = true }
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

            --- The home window, opened again (quietly, in a new tab) if the user closed it.
            function M.home()
              if not vim.api.nvim_win_is_valid(home) then
                local buf = vim.api.nvim_buf_is_valid(home_buf) and home_buf or vim.api.nvim_create_buf(false, true)
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

            function M.open(buf, folder)
              -- Not entered: fires WinNew, TabNew, BufWinEnter and WinScrolled only (E7), so the
              -- user's BufWinEnter autocommands run, and Eclipse sees no switch.
              local win = vim.api.nvim_tabpage_get_win(vim.api.nvim_open_tabpage(buf, false, {}))
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
                if folder ~= nil and folder ~= '' and not vim.o.autochdir and vim.fn.isdirectory(folder) == 1 then
                  pcall(vim.cmd.lcd, { args = { folder }, mods = { silent = true } })
                end
              end)
              return win
            end

            function M.show(win, buf)
              if win < 0 then
                win = M.home()
              elseif not vim.api.nvim_win_is_valid(win) then
                return -1 -- closed in Neovim: Eclipse opens a new one
              end
              local current = vim.api.nvim_get_current_win()
              set_buf(win, buf)
              if win ~= current then
                vim.api.nvim_set_current_win(win) -- BufEnter, with the buffer Eclipse expects
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

            --- Leaves win (quietly) for the home window showing fallback, if win is the current one.
            local function leave(win, fallback)
              if win == vim.api.nvim_get_current_win() then
                local to = M.home()
                quietly(function()
                  vim.api.nvim_set_current_win(to)
                  if vim.api.nvim_buf_is_valid(fallback) then
                    put_buf(to, fallback)
                  end
                end)
              end
            end

            --- Closes an editor's window quietly. It is forgotten here too: being quiet, the close
            --- fires no WinClosed, which would.
            local function close_quietly(win)
              quietly(vim.api.nvim_win_close, win, true)
              own[win] = nil
            end

            function M.close(win, fallback)
              if vim.api.nvim_win_is_valid(win) and win ~= home then
                leave(win, fallback)
                close_quietly(win)
              end
              return M.home()
            end

            function M.release(buf, fallback)
              local closed = {}
              for _, win in ipairs(vim.fn.win_findbuf(buf)) do
                if win == home then
                  set_buf(win, fallback)
                else
                  leave(win, fallback)
                  close_quietly(win)
                  closed[#closed + 1] = win
                end
              end
              return closed
            end

            --- Whether win (0: the current one) is a window Neovim opened beside an editor's for a
            --- moment, which :q, :close and Ctrl-W c close in Neovim, not the editor: a floating
            --- window the user entered (hover documentation, a plug-in's picker) or one of Neovim's
            --- special windows the safety net below leaves open (the command-line window).
            --- Example: Ctrl-W w into a hover float, then :q closes the float.
            function M.is_side_window(win)
              win = win == 0 and vim.api.nvim_get_current_win() or win
              return not own[win] and vim.fn.win_gettype(win) ~= ''
            end

            --- Why Vim's split and tab commands do not run (WINDOW_COMMANDS refuses them).
            M.REFUSED = "Eclipse shows one window per editor: use Window > Editor > Toggle Split Editor, or Window > Editor > Clone"
            --- Why the safety net closed a window, by win_gettype(): '' for a plain one (a split).
            local why_closed = {
              [''] = M.REFUSED,
              quickfix = 'Eclipse cannot show the quickfix window; :cnext, :cprevious and :cc still work',
              loclist = 'Eclipse cannot show the location list window; :lnext, :lprevious and :ll still work',
              preview = 'Eclipse cannot show the preview window',
            }

            local group = vim.api.nvim_create_augroup('neovim4eclipse_windows', { clear = true })
            vim.api.nvim_create_autocmd('WinClosed', {
              group = group,
              callback = function(ev)
                own[tonumber(ev.match)] = nil
                vim.rpcnotify(vim.g.neovim4eclipse_channel or 0, 'eclipse_window_closed', tonumber(ev.match))
              end,
            })
            -- The second safety net: a window opened next to an editor's, in its tab, by something the
            -- refusals do not see (a plug-in's :split, <Cmd>split, vim.cmd.split()). Eclipse would not
            -- show it, but it would take the keys and half the editor's rows. Closed once the
            -- command that opened it is over (it cannot close inside its own WinNew, where the
            -- current window is still the old one). The quickfix, location list and preview windows
            -- (:copen, :lopen, :pedit) are closed too: Eclipse cannot show them either, and in the
            -- editor's tab they would take its rows, so H, L and scrolling would no longer match
            -- the editor; the lists themselves stay, for :cnext and the like. Left alone: the
            -- plug-in's own windows, floating windows (hover, completion documentation; their
            -- win_gettype() is 'popup'), the windows of 'autocmd' (gone at once), and the
            -- command-line window ('command'), which has its own rule (SETUP).
            -- A closed quickfix or location list window is shown the Eclipse way instead of the
            -- message, when the quickfix module is there (QUICKFIX: the Problems or Tasks view, or
            -- the list's :clist in the output popup), so :copen does what it is for.
            vim.api.nvim_create_autocmd('WinNew', {
              group = group,
              callback = function()
                vim.schedule(function()
                  local why, list
                  local quickfix = package.loaded['neovim4eclipse.quickfix']
                  for _, win in ipairs(vim.api.nvim_list_wins()) do
                    local wintype = vim.fn.win_gettype(win)
                    local reason = not own[win] and why_closed[wintype]
                    if reason and pcall(vim.api.nvim_win_close, win, true) then
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
                end)
              end,
            })
            package.loaded['neovim4eclipse.windows'] = M
            return home
            """;

    /**
     * Refuses Vim's commands that would open a second window next to an editor's, or a tab, or
     * close the other editors' tabs (docs/design/WINDOWS.md, E4), through the host commands'
     * {@code refuse} hook ({@link #HOST_COMMANDS}), and maps the {@code Ctrl-W} keys that do the
     * same. Run after {@link #HOST_COMMANDS} and {@link #WINDOWS}.
     *
     * <ul>
     *   <li>Refused, with {@code name: Eclipse shows one window per editor: use Window > Editor >
     *       Toggle Split Editor, or Window > Editor > Clone}: {@code :split}, {@code :vsplit},
     *       {@code :new}, {@code :vnew}, {@code :sview}, {@code :sfind}, {@code :sbuffer},
     *       {@code :tabnew}, {@code :tabedit}, {@code :tabfind}, {@code :tabonly}. A second
     *       window would be invisible and take the keys; {@code :tabonly} would close every other
     *       editor's window. Mapping them onto Eclipse's split editor is a later plan.</li>
     *   <li>{@code :close} and {@code :tabclose} close the editor: {@code util/HostCommand}.
     *       {@code :tabclose} with a tab number or count ({@code :tabclose 2}, {@code :2tabclose},
     *       {@code :tabclose +}) is refused: Neovim would close another editor's window, and that
     *       editor would stay open in Eclipse without its jumps, folds and options.</li>
     *   <li>{@code Ctrl-W s}, {@code S}, {@code Ctrl-S}, {@code v}, {@code Ctrl-V}, {@code n},
     *       {@code Ctrl-N} and {@code T} show the same error; {@code Ctrl-W q}, {@code Ctrl-Q} and
     *       {@code c} close the editor ({@code :Eclipse quit}). Only where the user's config maps none
     *       of them itself. These keys reach Neovim only when Ctrl+W goes to Neovim in the
     *       preferences (docs/KEYBOARD.md).</li>
     *   <li>In a <em>side window</em> ({@code is_side_window} of {@link #WINDOWS}: a floating
     *       window the user entered, such as hover documentation), {@code :q}, {@code :close},
     *       {@code Ctrl-W q} and {@code Ctrl-W c} are Neovim's again, through the host commands'
     *       {@code leave_to_neovim} hook: they close that window, as in Neovim, not the editor.</li>
     * </ul>
     *
     * <p>What gets past (a plug-in's {@code :split}) is closed by the {@code WinNew} safety net of
     * {@link #WINDOWS}.
     */
    static final String WINDOW_COMMANDS = """
            local windows = require('neovim4eclipse.windows')
            local why = windows.REFUSED
            local host = require('neovim4eclipse.host_commands')
            for _, name in ipairs({ 'split', 'vsplit', 'new', 'vnew', 'sview', 'sfind', 'sbuffer',
                                    'tabnew', 'tabedit', 'tabfind', 'tabonly' }) do
              host.refuse(name, function() return why end)
            end
            -- p is the parsed command (nil for :Eclipse tabclose typed or mapped as such).
            host.refuse('tabclose', function(p)
              if p and (#p.args > 0 or (p.range and #p.range > 0)) then
                return 'closes this editor only; close another with its tab, or with :bd and its buffer number'
              end
            end)
            host.leave_to_neovim(function(name)
              return (name == 'quit' or name == 'close') and windows.is_side_window(0)
            end)
            local function refused()
              vim.api.nvim_echo({ { why } }, true, { err = true })
            end
            --- Closes the editor, or in a side window, the window: the key as Neovim has it (the
            --- mapping is not recursive, so the key is Neovim's own there).
            local function quit(key)
              return function()
                return windows.is_side_window(0) and key or '<Cmd>Eclipse quit<CR>'
              end
            end
            local keys = {
              ['<C-w>s'] = refused, ['<C-w>S'] = refused, ['<C-w><C-s>'] = refused,
              ['<C-w>v'] = refused, ['<C-w><C-v>'] = refused, ['<C-w>n'] = refused,
              ['<C-w><C-n>'] = refused, ['<C-w>T'] = refused,
            }
            local quits = { '<C-w>q', '<C-w><C-q>', '<C-w>c' }
            for lhs, rhs in pairs(keys) do
              if vim.fn.maparg(lhs, 'n') == '' then
                vim.keymap.set('n', lhs, rhs, { desc = 'Eclipse: one window per editor' })
              end
            end
            for _, lhs in ipairs(quits) do
              if vim.fn.maparg(lhs, 'n') == '' then
                vim.keymap.set('n', lhs, quit(lhs), { expr = true, desc = 'Eclipse: close the editor' })
              end
            end
            """;

    /**
     * Opens the window of an editor (arguments: the buffer number, and the folder for {@code :lcd},
     * "" to keep the one it copies) in a new tabpage, without entering it, and returns its id. Its
     * jump history starts empty. See {@link #WINDOWS}.
     */
    static final String OPEN_WINDOW = """
            local buf, folder = tonumber((select(1, ...))), (select(2, ...))
            return require('neovim4eclipse.windows').open(buf, folder)
            """;

    /**
     * Makes a window the current one, showing a buffer (arguments: the window id, -1 for the home
     * window, and the buffer number), and returns the id of the window shown, or -1 without doing
     * anything when that window was closed in Neovim (Eclipse then opens a new one). Example: {@code ("1001", "3")} enters editor B's
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
     * home window shows if the closed window was the current one), and returns the home window's
     * id (it may have been opened again). See {@link #WINDOWS}.
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
     * Neovim drops the marks of an {@code acwrite} buffer when it unloads it (X4); then unload and
     * unlist. {@code b:neovim4eclipse_file} stays. Nothing for a buffer that is gone.
     */
    static final String CLOSE_DOCUMENT_BUFFER = """
            local buf = tonumber((select(1, ...)))
            if not vim.api.nvim_buf_is_valid(buf) then
              return
            end
            pcall(vim.api.nvim_clear_autocmds, { group = 'neovim4eclipse_document', buf = buf })
            vim.bo[buf].buftype = ''
            vim.api.nvim_buf_delete(buf, { force = true, unload = true }) -- force: unsaved changes live in Eclipse
            vim.bo[buf].buflisted = false
            """;

    /**
     * Module {@code neovim4eclipse.paths}: the one rule for comparing file paths in Neovim, used by
     * every script that compares them ({@link #PROJECT_FILES}, {@link #HOST_COMMANDS},
     * {@link #DOCUMENT_BUFFER}, {@link #LEAVE_FOLDER}) and by the Lua tests ({@code t.same_file}).
     * Java's {@code util/FilePaths} follows the same rule; both are checked against one table of
     * cases ({@code tests/unit/src/test/resources/path-cases.tsv}). Run first, before any other script.
     *
     * <p><b>Why one rule.</b> The same file reaches Neovim in many spellings: Windows writes {@code \}
     * or {@code /} and any case ({@code C:\WS\a.txt}, {@code c:/ws/A.TXT}); macOS ignores case and
     * links {@code /var} to {@code /private/var}; a Windows junction or 8.3 short name
     * ({@code C:\PROGRA~1}) points elsewhere; {@code ..} and doubled slashes. Neovim stores a buffer's
     * name with links resolved, but a written name ({@code :w}) as typed. Each script once had its own
     * comparison, and each platform's fix landed in one of them only.
     *
     * <p><b>The rule</b> ({@code canonical(path)}): absolute ({@code fnamemodify(':p')} for a relative
     * name), then resolved ({@code fs_realpath}: symbolic links, junctions, 8.3 names, macOS's NFD
     * names; for a file that does not exist yet, its nearest existing folder resolved plus the
     * rest), with {@code /} separators and {@code .} and {@code ..} resolved ({@code vim.fs.normalize}, environment
     * variables not expanded: a folder may be named {@code $HOME}), and lower-cased where
     * {@code 'fileignorecase'} is on (Windows, macOS). Two paths name one file when their canonical
     * forms are equal ({@code same(a, b)}); {@code inside(path, folder)} when the path's is the
     * folder's or starts with it and a {@code /}. Example on Windows: {@code C:\link\App\a.txt} and
     * {@code c:/ws/app/A.TXT}, with {@code C:\link} a junction to {@code C:\ws}, are both
     * {@code c:/ws/app/a.txt}.
     *
     * <p><b>For tests, any platform anywhere.</b> {@code rules(platform)} gives the same three
     * functions for a platform given as {@code { win32 = bool, ignorecase = bool, realpath = fn }},
     * so the Windows and macOS rules are tested on Linux with a fake {@code realpath}; the module's
     * own functions use this machine's ({@code current()}).
     */
    static final String PATHS = """
            local M = {}

            --- Whether `path` is absolute under the platform's rule (C:\\ or \\\\server on Windows).
            local function absolute(path, win32)
              if win32 then
                return path:match('^%a:[/\\\\]') ~= nil or path:match('^[/\\\\][/\\\\]') ~= nil
              end
              return path:sub(1, 1) == '/'
            end

            function M.current()
              return { win32 = vim.fn.has('win32') == 1, ignorecase = vim.o.fileignorecase, realpath = vim.uv.fs_realpath }
            end

            function M.rules(platform)
              local R = {}
              local opts = { win = platform.win32, expand_env = false }
              function R.canonical(path)
                if path == nil or path == '' then
                  return ''
                end
                if not absolute(path, platform.win32) then
                  path = vim.fn.fnamemodify(path, ':p')
                end
                local full = vim.fs.normalize(path, opts)
                -- Not there (yet): its nearest existing folder resolved, plus the rest. The nearest,
                -- not just the parent: on macOS /home is a link, and with only the parent tried
                -- /home/user (parent /home exists) and /home/user/notes.txt (parent /home/user does
                -- not) would resolve differently. Java's FilePaths.linksResolved walks the same way.
                local folder, rest = full, nil
                local real = platform.realpath(folder)
                while not real do
                  local up = vim.fs.dirname(folder)
                  if up == folder or up == '.' then
                    break -- not even the root resolves
                  end
                  local name = vim.fs.basename(folder)
                  rest = rest and (name .. '/' .. rest) or name
                  folder = up
                  real = platform.realpath(folder)
                end
                real = real and (rest and (real .. '/' .. rest) or real) or full
                real = vim.fs.normalize(real, opts)
                return platform.ignorecase and real:lower() or real
              end
              function R.same(a, b)
                return R.canonical(a) == R.canonical(b)
              end
              --- Whether canonical form `c` is the canonical folder `f` or inside it.
              function R.within(c, f)
                local prefix = f:sub(-1) == '/' and f or f .. '/' -- the root: / or C:/
                return c == f or c:sub(1, #prefix) == prefix
              end
              function R.inside(path, folder)
                return R.within(R.canonical(path), R.canonical(folder))
              end
              return R
            end

            -- This machine's rule, read as each call is made ('fileignorecase' may change).
            for _, name in ipairs({ 'canonical', 'same', 'inside', 'within' }) do
              M[name] = function(...)
                local rule = M.rules(M.current())
                return rule[name](...)
              end
            end
            package.loaded['neovim4eclipse.paths'] = M
            """;

    /**
     * Module {@code neovim4eclipse.project_files}: Neovim never reads a file of an open Eclipse
     * project by itself (docs/design/BUFFER_NUMBERS.md, 2.3 and Q3).
     *
     * <p><b>Why.</b> When Neovim reads a file ({@code gf}, {@code 'A}, {@code :b} into a closed file,
     * a quickfix entry), it runs filetype detection, the filetype plug-ins ({@code indentexpr},
     * {@code commentstring}) and the user's {@code FileType} autocommands, which may start a language
     * server inside Eclipse's Neovim (Eclipse is that server already: jdtls is Eclipse's JDT). The
     * buffers the plug-in fills from Eclipse's text never do. So such a file is read by a
     * {@code BufReadCmd} of the plug-in's instead, which reads only the text; Eclipse then opens the
     * file's editor ({@code EditorAttachManager}) and the attach takes the buffer over, as for a
     * document's kept buffer.
     *
     * <p><b>Which buffers.</b> Eclipse sends the open projects' folders ({@link #SET_PROJECT_FOLDERS}).
     * Each new buffer ({@code BufNew}, also sent after a rename) whose file is inside one gets a
     * buffer-local {@code BufReadCmd}, which stays with the buffer while it is unloaded. A buffer
     * added to the list is checked too ({@code BufAdd}), unless {@code BufNew} checked it under the
     * same name: {@code :vimgrep} makes the buffers of the files it matched while it searches,
     * without {@code BufNew}, and its jump into one fires only {@code BufAdd} before the read (found
     * 2026-10-30, on macOS: {@code :vimgrep} into closed files opened no editor; any system, for
     * files without a buffer yet). Its first match is loaded by the search itself, so the hook
     * cannot read it: {@code BufAdd} turns its {@code 'swapfile'} off instead. The paths
     * are compared by {@link #PATHS}'s rule (resolved: symbolic links, Windows junctions and 8.3
     * short names, macOS's NFD names; {@code /} separators; lower case where
     * {@code 'fileignorecase'} is on, Windows and macOS). Autocommand patterns were tried first and cannot match a
     * junction, an 8.3 name or a folder named {@code $HOME} on Windows (BUFFER_NUMBERS.md, X18d).
     * Example: with project {@code /ws/app}, {@code gf} on {@code src/B.java} in {@code /ws/app/A.java}
     * is read by the hook; {@code /etc/hosts} and {@code /ws/app2/C.java} are read by Neovim.
     *
     * <p><b>The read.</b> {@code 'swapfile'} off first: with a swap file left by an earlier Neovim,
     * the read would stop at the E325 question, which nothing can answer under {@code noautocmd}
     * (X18c). Nothing for a file that does not exist. Then {@code :read ++edit} (Neovim's own decoding
     * of encodings, line endings and a BOM) without autocommands, the empty first line removed, under
     * {@code lockmarks} (the marks stay on their lines) and with {@code 'undolevels'} -1 (no undo
     * step). A closed document's buffer then has its cursor put on {@code '"}, where Vim's {@code :b}
     * puts it; a mark jump or a quickfix entry moves it on afterwards. A buffer that another
     * {@code BufReadCmd} filled first (Neovim's {@code zip.vim} lists an archive's files, read-only)
     * is left to it: reading the raw bytes on top of it failed with E21.
     *
     * <p><b>A document's buffer</b> ({@code 'buftype'} {@code acwrite}, an editor shows it) is left
     * alone: {@link #DOCUMENT_BUFFER} gives every document's buffer, inside a project or not, a
     * {@code BufReadCmd} of its own, which asks Eclipse for the text instead.
     *
     * <p>Functions: {@code set_folders(list)}; {@code file_of(buf)}, the buffer's file name when the
     * hook is on it, else {@code ""} (sent with {@code eclipse_buf_enter}, {@link #SETUP});
     * {@code inside(path)}.
     */
    static final String PROJECT_FILES = """
            local M = {}
            local group = vim.api.nvim_create_augroup('neovim4eclipse_projects', { clear = true })
            local paths = require('neovim4eclipse.paths') -- the one rule for comparing paths (PATHS)
            local roots = {} -- the folders, as paths.canonical() gives them
            local hooked = {} -- buffer number -> true, for the buffers that have the read hook
            local checked = {} -- buffer number -> the name check() last looked at

            function M.inside(path)
              if path == '' or #roots == 0 then
                return false
              end
              local c = paths.canonical(path)
              for _, root in ipairs(roots) do
                if paths.within(c, root) then
                  return true
                end
              end
              return false
            end

            local function read(ev)
              local buf = ev.buf
              vim.bo[buf].swapfile = false -- first: a stale swap file would stop the read at E325
              if vim.bo[buf].buftype == 'acwrite' then
                return -- a document's buffer: its own hook asks Eclipse for the text (DOCUMENT_BUFFER)
              end
              local name = ev.match
              if vim.fn.filereadable(name) == 0 then
                return
              end
              -- Another reader came first (Neovim's zip.vim or tar.vim browse an archive with a
              -- BufReadCmd of their own, defined before this one): the buffer is theirs.
              local first = vim.api.nvim_buf_get_lines(buf, 0, 1, false)[1]
              if not vim.bo[buf].modifiable or vim.api.nvim_buf_line_count(buf) > 1 or (first and first ~= '') then
                return
              end
              local saved = vim.bo[buf].undolevels
              vim.bo[buf].undolevels = -1
              local ok, err = pcall(vim.cmd, 'lockmarks keepalt noautocmd silent read ++edit ' .. vim.fn.fnameescape(name))
              if ok then
                vim.cmd('lockmarks keepjumps noautocmd silent 1delete _')
              end
              vim.bo[buf].undolevels = saved
              vim.bo[buf].modified = false
              if not ok then
                vim.api.nvim_echo({ { tostring(err), 'ErrorMsg' } }, true, {})
                return
              end
              if vim.b[buf].neovim4eclipse_file and vim.api.nvim_get_current_buf() == buf then
                local mark = vim.api.nvim_buf_get_mark(buf, '"')
                if mark[1] >= 1 and mark[1] <= vim.api.nvim_buf_line_count(buf) then
                  pcall(vim.api.nvim_win_set_cursor, 0, mark)
                end
              end
            end

            local function hook(buf)
              if not hooked[buf] then
                hooked[buf] = true
                vim.api.nvim_create_autocmd('BufReadCmd', { group = group, buffer = buf, callback = read })
              end
            end

            local function unhook(buf)
              if hooked[buf] then
                hooked[buf] = nil
                pcall(vim.api.nvim_clear_autocmds, { group = group, buffer = buf, event = 'BufReadCmd' })
              end
            end

            local function check(buf)
              local name = vim.api.nvim_buf_get_name(buf)
              checked[buf] = name
              if M.inside(name) then
                hook(buf)
              else
                unhook(buf)
              end
            end

            function M.set_folders(folders)
              roots = {}
              for _, folder in ipairs(folders) do
                roots[#roots + 1] = paths.canonical(folder)
              end
              for _, buf in ipairs(vim.api.nvim_list_bufs()) do
                check(buf)
              end
            end

            function M.file_of(buf)
              return hooked[buf] and vim.api.nvim_buf_get_name(buf) or ''
            end

            vim.api.nvim_create_autocmd('BufNew', { group = group, callback = function(ev)
              check(ev.buf)
            end })
            -- BufAdd too: :vimgrep makes the buffers of the files it matched while it searches, with
            -- no BufNew (Neovim 0.12.6), and the jump to one fires only BufAdd before reading it.
            -- Most buffers had BufNew just before: those are not resolved again (fs_realpath).
            vim.api.nvim_create_autocmd('BufAdd', { group = group, callback = function(ev)
              local buf = ev.buf
              if checked[buf] ~= vim.api.nvim_buf_get_name(buf) then
                check(buf)
              end
              -- :vimgrep's first match is loaded already, by its search, with a swap file; off,
              -- as the hook's read would have done (turning it off deletes the file).
              if hooked[buf] and vim.api.nvim_buf_is_loaded(buf) and vim.bo[buf].buftype == '' then
                vim.bo[buf].swapfile = false
              end
            end })
            vim.api.nvim_create_autocmd('BufWipeout', { group = group, callback = function(ev)
              hooked[ev.buf] = nil
              checked[ev.buf] = nil
            end })
            package.loaded['neovim4eclipse.project_files'] = M
            """;

    /**
     * Tells {@link #PROJECT_FILES} the open projects' folders (arguments: the folders, one each, as
     * Eclipse gives them: {@code /ws/app}, {@code C:\ws\app}, a linked folder's target), and gives
     * the read hook to the buffers inside them, or takes it from those no longer inside. Sent at the
     * first attach and whenever a project or a linked folder comes or goes.
     */
    static final String SET_PROJECT_FOLDERS = """
            require('neovim4eclipse.project_files').set_folders({ ... })
            """;

    /**
     * Loads a document's text into its buffer again (arguments: the buffer number, then the lines),
     * as the first load does ({@link #LOAD_WITHOUT_UNDO}: no undo step, marks kept): after Neovim
     * loaded it empty by itself, with no text kept from the unload ({@link #DOCUMENT_BUFFER},
     * "Reloaded by Neovim"). Nothing for a buffer that is gone.
     */
    static final String RELOAD_DOCUMENT_BUFFER = "local function load(...)\n" + LuaScripts.LOAD_WITHOUT_UNDO + """
            end
            local buf = tonumber((select(1, ...)))
            local lines = { select(2, ...) }
            if vim.api.nvim_buf_is_valid(buf) then
              vim.api.nvim_buf_call(buf, function() load(unpack(lines)) end)
            end
            """;

    /**
     * Before a document's buffer is closed (arguments: its number, and the buffer shown instead):
     * closes the editor windows that show it, quietly, and returns their ids; the home window shows
     * the other buffer instead. Unloading a buffer shown in a window would make Neovim close that
     * window or put some other buffer in it, by itself. See {@link #WINDOWS}.
     */
    static final String RELEASE_BUFFER = """
            local buf, fallback = tonumber((select(1, ...))), tonumber((select(2, ...)))
            return require('neovim4eclipse.windows').release(buf, fallback)
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
     * {@code gt}; see {@link IdeAction}): each sends {@code eclipse_action} with its action and Vim's
     * count ({@code v:count}, the count typed before the key). Arguments: four per key, lhs, modes,
     * action, description, from the one table {@link IdeKeys} ({@code IdeKeys.toLuaArgs()}), which
     * Eclipse's side reads too. Example: {@code "gra", "nx", "quick_fix", "Quick Fix"} maps
     * {@code gra} in normal and visual mode, described {@code Eclipse: Quick Fix}.
     *
     * <p>Run right after {@link #SETUP}, so after the user's {@code init.lua}: it wins over their own
     * mappings of these keys. {@code vim.g.neovim4eclipse_ide_keys = 0} (or {@code false},
     * {@code v:false} in Vim script) in {@code init.lua} keeps Neovim's meaning of all of them instead.
     */
    static final String IDE_KEYS = """
            local ide_keys = vim.g.neovim4eclipse_ide_keys
            if ide_keys == 0 or ide_keys == false then
              return
            end
            local args = { ... }
            for i = 1, #args - 3, 4 do
              local lhs, modes, action, desc = args[i], args[i + 1], args[i + 2], args[i + 3]
              vim.keymap.set(vim.split(modes, ''), lhs, function()
                vim.rpcnotify(vim.g.neovim4eclipse_channel or 0, 'eclipse_action', action, vim.v.count)
              end, { desc = 'Eclipse: ' .. desc })
            end
            """;

    /**
     * Module {@code neovim4eclipse.quickfix}: the quickfix lists of the workspace's problems and tasks
     * (docs/design/DIAGNOSTICS.md, section 5). Run once at start, after {@link #WINDOWS}, whose
     * safety net calls {@code window()} below.
     *
     * <p><b>The commands.</b> {@code :Eproblems [error|warning|info]} and {@code :Etasks}, each with
     * {@code !}, only ask Eclipse ({@code eclipse_quickfix} with the kind, the argument and the bang):
     * the markers live there. {@code :Etasks} sends the tags of {@code vim.g.neovim4eclipse_task_tags}
     * (a string {@code 'JM6,FIXME'}, or a list), read as it runs, so {@code :let} changes them at once;
     * without tags it says how to set them and asks nothing. A severity other than the three is
     * refused with Vim's {@code E475}. Eclipse answers with {@link #SET_QUICKFIX}.
     *
     * <p><b>Which list is ours.</b> The list's {@code context} ({@code :help quickfix-context}) holds
     * {@code { neovim4eclipse = 'problems', arg = 'warning' }}: the kind, and what to read again for
     * an update. The title ({@code Eclipse problems}) is only shown, never matched, so a list of the
     * user's with the same title is never touched.
     *
     * <p><b>{@code set(kind, arg, mode, jump, entries...)}</b>, seven strings per entry
     * ({@code util/QuickfixEntries.toLuaArgs}). An entry names its document's buffer when Eclipse
     * sends a buffer number that exists, else the file, for which Neovim makes an unlisted buffer
     * ({@code PROJECT_FILES} gives it the read hook). Mode {@code command}: the newest list of this
     * kind in the stack is updated and made current ({@code :chistory}), or a new one is made, as
     * {@code vim.diagnostic.setqflist()} does (decided, Q10); then {@code :cfirst} unless
     * {@code jump} is {@code 0} ({@code :Eproblems!}, which shows the count instead). Mode
     * {@code update}: only when the current list is this kind's with the same argument; any other
     * current list ({@code :vimgrep}'s) is left alone. Both update with action {@code 'u'}, which keeps
     * the entry the user is on (Neovim matches it by its content).
     *
     * <p><b>The column, once known.</b> A closed file's entries have the line only (column 0: Vim puts
     * the cursor on the first non-blank). When its editor opens, Eclipse sends the list again with
     * columns from the editor (Q11). If the current entry had no column before and has one now, is in
     * the current buffer, and the cursor is still where the line-only jump put it (that line, its
     * first non-blank), the cursor moves on to the column: the jump that opened the file ends where
     * the problem is. A cursor the user moved meanwhile is left alone, and so is one in insert or
     * visual mode (only normal mode moves).
     *
     * <p>An entry with neither a buffer nor a file (a marker on a folder or project) is text only:
     * {@code :clist} shows it, {@code :cnext} steps over it. With only such entries a command does not
     * jump (Vim's {@code :cfirst} would say {@code E42: No Errors}) and shows the count instead.
     *
     * <p><b>{@code window(type)}</b>, from the {@code WinNew} safety net of {@link #WINDOWS} after it
     * closed a {@code quickfix} or {@code loclist} window ({@code :copen}, {@code :cwindow},
     * {@code :lopen}, also from a mapping or a plug-in): with one of our lists current,
     * {@code eclipse_show_view} with its kind, and Eclipse shows the Problems or Tasks view; else the
     * list's {@code :clist} (or {@code :llist}) as output, which opens the output popup.
     *
     * <p>{@code current()}: the current list's kind and argument when it is ours, else nothing
     * ({@link #QUICKFIX_CURRENT}). Neovim has no event for a change of the current list
     * ({@code :colder}, {@code :chistory}), so Eclipse asks before an update.
     */
    static final String QUICKFIX = """
            local M = {}
            local titles = { problems = 'Eclipse problems', tasks = 'Eclipse tasks' }
            local severities = { 'error', 'warning', 'info' }
            local function channel()
              return vim.g.neovim4eclipse_channel or 0
            end
            local function echo(text, highlight)
              vim.api.nvim_echo({ { text, highlight } }, true, {})
            end

            --- The kind of a list's context when the plug-in made the list, else nil.
            local function kind_of(context)
              return type(context) == 'table' and titles[context.neovim4eclipse] and context.neovim4eclipse or nil
            end

            function M.current()
              local list = vim.fn.getqflist({ nr = 0, context = 0 })
              local kind = kind_of(list.context)
              if kind then
                return { kind, list.context.arg or '' }
              end
            end

            --- The id and number of the newest list of `kind` in the stack, or nil.
            local function find(kind)
              for nr = vim.fn.getqflist({ nr = '$' }).nr, 1, -1 do
                local list = vim.fn.getqflist({ nr = nr, id = 0, context = 0 })
                if kind_of(list.context) == kind then
                  return list.id, nr
                end
              end
            end

            --- The current entry of list `id`, or nil.
            local function entry(id)
              local idx = vim.fn.getqflist({ id = id, idx = 0 }).idx
              return idx > 0 and vim.fn.getqflist({ id = id, idx = idx, items = 0 }).items[1] or nil
            end

            --- The column fix-up of the class comment: `before` is the current entry before the update.
            local function move_to_column(id, before)
              if not before or before.col ~= 0 or before.lnum == 0 then
                return
              end
              -- Normal mode only: in insert or visual mode the user is already doing something there
              -- (moving the cursor would put typed text, or the selection's end, elsewhere).
              if vim.api.nvim_get_mode().mode ~= 'n' then
                return
              end
              local after = entry(id)
              local buf = vim.api.nvim_get_current_buf()
              if not after or after.col == 0 or after.bufnr ~= buf or before.bufnr ~= buf then
                return
              end
              local cursor = vim.api.nvim_win_get_cursor(0)
              local line = vim.api.nvim_buf_get_lines(buf, cursor[1] - 1, cursor[1], false)[1] or ''
              local first = (line:find('%S') or 1) - 1 -- where the line-only jump put the cursor
              if cursor[1] == before.lnum and cursor[2] == first then
                pcall(vim.api.nvim_win_set_cursor, 0, { after.lnum, after.col - 1 })
              end
            end

            --- 'Eclipse problems: 12 (3 errors, 9 warnings)'.
            local function summary(title, items)
              local counts, names = {}, { E = 'error', W = 'warning', I = 'info' }
              for _, item in ipairs(items) do
                counts[item.type] = (counts[item.type] or 0) + 1
              end
              local parts = {}
              for _, letter in ipairs({ 'E', 'W', 'I' }) do
                local n = counts[letter]
                if n then
                  parts[#parts + 1] = n .. ' ' .. names[letter] .. (n == 1 and '' or 's')
                end
              end
              if #items == 0 then
                return title .. ': none'
              end
              return title .. ': ' .. #items .. (#parts > 0 and ' (' .. table.concat(parts, ', ') .. ')' or '')
            end

            function M.set(kind, arg, mode, jump, ...)
              local flat, items = { ... }, {}
              for i = 1, #flat - 6, 7 do
                local bufnr = tonumber(flat[i]) or 0
                local item = {
                  module = flat[i + 2] ~= '' and flat[i + 2] or nil,
                  lnum = tonumber(flat[i + 3]),
                  col = tonumber(flat[i + 4]),
                  type = flat[i + 5],
                  text = flat[i + 6],
                }
                if bufnr > 0 and vim.api.nvim_buf_is_valid(bufnr) then
                  item.bufnr = bufnr
                elseif flat[i + 1] ~= '' then
                  item.filename = flat[i + 1]
                end -- neither: a marker on a folder or project, text only (:cnext steps over it)
                items[#items + 1] = item
              end
              local title = titles[kind]
              if arg ~= '' then
                title = title .. ' (' .. arg .. ')'
              end
              local context = { neovim4eclipse = kind, arg = arg }
              if mode == 'update' then
                local current = vim.fn.getqflist({ nr = 0, id = 0, context = 0 })
                if kind_of(current.context) ~= kind or (current.context.arg or '') ~= arg then
                  return -- not ours, or ours with another severity: left as it is
                end
                local before = entry(current.id)
                vim.fn.setqflist({}, 'u', { id = current.id, title = title, context = context, items = items })
                move_to_column(current.id, before)
                return
              end
              local id, nr = find(kind)
              if id then
                vim.fn.setqflist({}, 'u', { id = id, title = title, context = context, items = items })
                if nr ~= vim.fn.getqflist({ nr = 0 }).nr then
                  vim.cmd('silent ' .. nr .. 'chistory')
                end
              else
                vim.fn.setqflist({}, ' ', { title = title, context = context, items = items })
              end
              -- Jump only when there is somewhere to go: entries that are all text only (markers on a
              -- folder or project) would make :cfirst say "E42: No Errors" about a list that has some.
              local valid = false
              for _, item in ipairs(items) do
                if item.bufnr or item.filename then
                  valid = true
                  break
                end
              end
              if jump == '1' and valid then
                local ok, err = pcall(vim.cmd, 'cfirst') -- shows "(1 of 12): ..." as :make does
                if not ok then
                  echo((tostring(err):gsub('^.-Vim%(%a+%):', '')), 'ErrorMsg')
                end
              else
                echo(summary(title, items))
              end
            end

            function M.window(wintype)
              local kind = wintype == 'quickfix' and M.current() or nil
              if kind then
                vim.rpcnotify(channel(), 'eclipse_show_view', kind[1])
                return
              end
              local command = wintype == 'loclist' and 'llist' or 'clist'
              local ok, output = pcall(vim.fn.execute, command)
              output = ok and output or tostring(output)
              output = output:gsub('^%s*\\n', '')
              echo(output, ok and nil or 'ErrorMsg')
            end

            --- vim.g.neovim4eclipse_task_tags as one comma-separated string: 'JM6,FIXME', or '*'.
            local function task_tags()
              local tags = vim.g.neovim4eclipse_task_tags
              if type(tags) == 'table' then
                tags = table.concat(vim.tbl_map(tostring, tags), ',')
              end
              return vim.trim(type(tags) == 'string' and tags or '')
            end

            vim.api.nvim_create_user_command('Eproblems', function(o)
              local arg = vim.trim(o.args)
              if arg ~= '' and not vim.tbl_contains(severities, arg) then
                echo('E475: Invalid argument: ' .. arg .. ' (error, warning or info)', 'ErrorMsg')
                return
              end
              vim.rpcnotify(channel(), 'eclipse_quickfix', 'problems', arg, o.bang)
            end, {
              nargs = '?',
              bang = true,
              complete = function(lead)
                return vim.tbl_filter(function(s) return s:sub(1, #lead) == lead end, severities)
              end,
              desc = "Eclipse's problems in the quickfix list (at least the given severity)",
            })
            vim.api.nvim_create_user_command('Etasks', function(o)
              local tags = task_tags()
              if tags == '' or tags:gsub('[%s,]', '') == '' then
                echo([[Etasks: no task tags; set them in init.lua: vim.g.neovim4eclipse_task_tags = 'TODO,FIXME' (or '*' for all)]], 'WarningMsg')
                return
              end
              vim.rpcnotify(channel(), 'eclipse_quickfix', 'tasks', tags, o.bang)
            end, { nargs = 0, bang = true, desc = "Eclipse's tasks with your tags (vim.g.neovim4eclipse_task_tags) in the quickfix list" })
            package.loaded['neovim4eclipse.quickfix'] = M
            """;

    /**
     * Fills or updates a quickfix list of Eclipse's (arguments: {@code util/QuickfixEntries.toLuaArgs});
     * see {@link #QUICKFIX}.
     */
    static final String SET_QUICKFIX = "require('neovim4eclipse.quickfix').set(...)";

    /**
     * The current quickfix list's kind and argument ({@code {"problems", "warning"}}) when it is one
     * of Eclipse's, else {@code nil}; see {@link #QUICKFIX}.
     */
    static final String QUICKFIX_CURRENT = "return require('neovim4eclipse.quickfix').current()";

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
     * <p><b>Folds by hand are refused</b> in a mirrored buffer: Eclipse decides where folds are,
     * and its next reconcile would replace them. {@code z} alone gets a buffer-local mapping that
     * reads the next key without a time limit: {@code zf}, {@code zF}, {@code zd}, {@code zD} and
     * {@code zE} (and {@code zf}, {@code zd}, {@code zD} in visual mode) show
     * {@code zf: Eclipse defines the folds in this editor}; any other key goes on to the user's
     * mapping of {@code z} and that key, if there is one, or to Neovim's {@code z} command, with the
     * count. (Mappings of the two keys would time out after {@code 'timeoutlen'}, and a slow
     * {@code zE} was Neovim's.) {@code :fold}, which cannot be mapped, is refused through
     * {@link #HOST_COMMANDS}' hook,
     * so this script must run after it. Elsewhere (an editor that cannot fold) they are Neovim's.
     * Only typed command lines are caught, as for the other host commands: {@code vim.cmd.fold()}
     * from Lua runs. (Neovim has no {@code :folddelete}: deleting is {@code zd} only, and
     * {@code :foldd} is {@code :folddoopen}, which opens folds and stays Neovim's.)
     *
     * <p>The old folds are deleted with {@code :noautocmd normal! zE}: Eclipse can send folds while
     * Neovim is in insert mode, and a plain {@code :normal} passes through normal mode, firing
     * {@code ModeChanged}, which made the plug-in take insert mode as ended.
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

            -- Making or deleting folds by hand: Eclipse decides where folds are, and its next
            -- reconcile would replace them. The commands get a refusal in HOST_COMMANDS (built-in
            -- commands cannot be mapped); the keys a buffer-local mapping of z alone, which reads the
            -- key after it with getcharstr(), without a time limit.
            -- Why not mappings of zf, zd, zE ...: Neovim waits only 'timeoutlen' (1000 ms by default)
            -- for a mapping's second key. A slower zE then ran Neovim's own zE and deleted every fold.
            -- The key after z: refused (f F d D E; f d D in visual mode), else passed on. A mapping
            -- of the user's for z and that key ({ 'zR' }, say) runs as Vim would run it; anything
            -- else is Neovim's z command, with the count: 3zj, zz, z=, z{height}<CR>.
            local REFUSED = 'Eclipse defines the folds in this editor'
            local REFUSED_AFTER_Z = { n = { f = true, F = true, d = true, D = true, E = true },
                                      x = { f = true, d = true, D = true } }
            local ESC = string.char(27)

            -- The pending command for Eclipse's status bar. Neovim reports a half-typed command
            -- (msg_showcmd) by itself, but not a z that this mapping is reading the next key for:
            -- to Neovim, no command is pending then. So the mapping reports it itself, through the
            -- same status-bar slot (NotificationBridge, eclipse_showcmd), and clears it after the key.
            local function showcmd(text)
              vim.rpcnotify(vim.g.neovim4eclipse_channel or 0, 'eclipse_showcmd', text)
            end

            -- Runs the user's mapping m (a maparg() dictionary) for z and the next key.
            local function run_users_mapping(m, count)
              local keys
              if m.callback then
                local result = m.callback()
                if m.expr ~= 1 then
                  return
                end
                keys = result or ''
              elseif m.expr == 1 then
                keys = vim.fn.eval(m.rhs)
              else
                keys = vim.api.nvim_replace_termcodes(m.rhs, true, true, true)
              end
              if m.expr == 1 and m.replace_keycodes == 1 then
                keys = vim.api.nvim_replace_termcodes(keys, true, true, true)
              end
              -- As Vim does: a right-hand side starting with z does not use this z mapping again.
              local remap = m.noremap ~= 1 and keys:sub(1, 1) ~= 'z'
              vim.api.nvim_feedkeys(count .. keys, remap and 'mi' or 'ni', false)
            end

            local function z_key(mode)
              return function()
                local count = vim.v.count > 0 and tostring(vim.v.count) or ''
                showcmd(count .. 'z') -- e.g. "3z", as Neovim would show it
                local ok, key = pcall(vim.fn.getcharstr)
                showcmd('')
                if not ok or key == ESC then
                  if mode == 'x' then
                    vim.api.nvim_feedkeys(ESC, 'n', false)
                  end
                  return -- Esc or Ctrl-C after z: nothing, as for Neovim's z
                end
                if REFUSED_AFTER_Z[mode][key] then
                  vim.api.nvim_echo({ { 'z' .. key .. ': ' .. REFUSED } }, true, { err = true })
                  if mode == 'x' then
                    vim.api.nvim_feedkeys(ESC, 'n', false) -- leave visual mode, as zf does
                  end
                  return
                end
                local m = vim.fn.maparg('z' .. key, mode, false, true) -- {} when there is none
                if next(m) ~= nil then
                  run_users_mapping(m, count)
                  return
                end
                vim.api.nvim_feedkeys(count .. 'z' .. key, 'ni', false)
              end
            end

            local function refuse_keys(buf, on)
              for _, mode in ipairs({ 'n', 'x' }) do
                if on then
                  vim.keymap.set(mode, 'z', z_key(mode), { buffer = buf, nowait = true,
                    desc = 'z commands; Eclipse defines the folds here (neovim4eclipse)' })
                else
                  pcall(vim.keymap.del, mode, 'z', { buffer = buf })
                end
              end
            end
            local host = package.loaded['neovim4eclipse.host_commands']
            if host then
              host.refuse('fold', function()
                return vim.b.neovim4eclipse_folds and REFUSED or nil
              end)
            end

            -- Deletes every manual fold of the current window (zE), without ModeChanged.
            -- Why: Eclipse may send folds while Neovim is in insert mode, and :normal passes through
            -- normal mode on the way, which fires ModeChanged; the plug-in heard "normal" and took
            -- insert mode as ended (a method typed in Eclipse then went missing). noautocmd keeps
            -- that event (and the user's own autocommands) out; Neovim stays in insert mode. (Not
            -- foldmethod=expr with foldexpr=0 and back: in insert mode Neovim does not recompute
            -- expression folds, so the old folds stayed.)
            local function delete_all_folds()
              vim.cmd('silent! noautocmd normal! zE')
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
                if vim.b[buf].neovim4eclipse_folds then
                  refuse_keys(buf, false)
                end
                vim.b[buf].neovim4eclipse_folds = nil
                return
              end
              setlocal('foldmethod', 'manual')
              setlocal('foldenable', true)
              setlocal('foldminlines', 0)
              delete_all_folds()
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
              if not vim.b[buf].neovim4eclipse_folds then
                refuse_keys(buf, true)
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
     * Installs the viewport module, {@code require('neovim4eclipse.viewport')}, unless the user's
     * config turned the feature off with {@code vim.g.neovim4eclipse_viewport = 0} (or
     * {@code false}), and returns whether it is on. The session runs it once at start, after
     * {@link #SETUP} (so after {@code init.lua}), and keeps the answer
     * ({@code NeovimSession.viewportEnabled()}): when off, Neovim keeps its fixed 120 × 40 screen and
     * no other viewport script is sent (docs/design/VIEWPORT.md, D5). Changing the variable later
     * needs a Neovim restart.
     *
     * <p><b>What it is for.</b> Neovim's window is made the size of the editor
     * ({@code nvim_ui_try_resize}, sent by the session), so that {@code H}, {@code L},
     * {@code Ctrl-D} and {@code 'scrolloff'} work on the lines the editor shows. The module supplies
     * what the session cannot know, and keeps {@code 'wrap'} the same as Eclipse's word wrap:
     * <ul>
     *   <li>{@code overhead()}, used by {@link #VIEWPORT_OVERHEAD}: {@code { &lines - winheight(0),
     *       textoff }}, the rows and columns Neovim's screen has besides the window's text (status
     *       line, line numbers, signs). The grid is made larger by them ({@code util/ViewportSize}).
     *       Example: {@code { 1, 4 }} with the default status line and {@code set number}.</li>
     *   <li>{@code set_wrap(on)}, used by {@link #SET_WRAP}: Eclipse's word wrap for the current
     *       window (decision D2: Eclipse's state wins on attach).</li>
     *   <li>{@code set_topline(line, mode)}, used by {@link #SET_TOPLINE}: the editor's top line.
     *       {@code 'keep'} (attach): the cursor stays and Neovim keeps it visible, which may change
     *       the top line. {@code 'drag'} (the user scrolled the editor: the scroll bar, the wheel):
     *       the cursor is dragged into the new view as {@code Ctrl-E} and {@code Ctrl-Y} drag it,
     *       {@code 'scrolloff'} lines from its edges, so that the view is kept. {@code 'show'} (Esc,
     *       after Eclipse scrolled while it typed): a cursor in the view stays, one outside it is
     *       dragged in, so the view is kept as Neovim's wheel would have kept it.</li>
     *   <li>{@code scroll_lines(n)}, used by {@link #SCROLL_LINES}: the mouse wheel. Scrolls {@code n}
     *       lines down (negative: up) as {@code n} times {@code Ctrl-E} ({@code Ctrl-Y}) would, a
     *       closed fold counting as one line, and drags the cursor along.</li>
     * </ul>
     *
     * <p><b>{@code 'linebreak'} while wrap is on</b> (D7). Eclipse wraps a long line at a word
     * boundary; Neovim, without {@code 'linebreak'}, in the middle of a word. The line would then take
     * a different number of screen rows on each side, and {@code gj}, {@code H} or {@code Ctrl-D}
     * would land on another row than the one shown. So while wrap is on the window gets
     * {@code 'linebreak'}, no {@code 'breakindent'} and {@code 'showbreak'} {@code NONE} (Eclipse
     * draws neither an indent nor a marker for a wrapped part); the user's own values are kept in
     * {@code w:neovim4eclipse_saved} and come back when wrap goes off. A change of one of the three
     * while wrap is on is set back, with a message the first time in each Neovim session only: a
     * filetype plug-in or a modeline that sets them would otherwise show it for every file. The value
     * the user asked for is remembered, so it applies once wrap is off.
     *
     * <p><b>Changes made in Neovim reach Eclipse</b> by notification, on the plug-in's channel
     * ({@code g:neovim4eclipse_channel}):
     * <ul>
     *   <li>{@code eclipse_wrap(buf, on, win)}: {@code :set wrap} or {@code :set nowrap}, which Eclipse
     *       turns into the editor's word wrap. An option the plug-in set itself is not reported back
     *       (the {@code applying} flag), as {@code CaretSync} ignores its own caret moves.</li>
     *   <li>{@code eclipse_viewport()}: an option that changes the overhead changed
     *       ({@code :set number}, {@code laststatus}, ...); the session measures it again and resizes.
     *       Sent once per batch (scheduled), so {@code :set nu rnu} resizes once.</li>
     *   <li>{@code eclipse_topline(buf, line, win)}: the current window scrolled without the cursor moving
     *       ({@code Ctrl-E}, {@code zt}, a resize); the editor scrolls to {@code line}. A scroll that
     *       moves the cursor ({@code 50G}, {@code j} at the bottom) is reported again here, after
     *       {@code eclipse_cursor} already carried the same top line; the second changes nothing.
     *       {@code WinScrolled} fires for every window that scrolled; only the current one is the
     *       editor's.</li>
     * </ul>
     */
    static final String VIEWPORT = """
            local enabled = vim.g.neovim4eclipse_viewport
            if enabled == 0 or enabled == false then
              return false
            end
            local M = {}
            -- True while the plug-in sets options itself: their OptionSet is not the user's.
            local applying = false
            -- The 'linebreak' message is shown once per Neovim session (see the Java comment).
            local told = false
            -- While wrap is on: break at words as Eclipse does, no indent and no marker for the
            -- wrapped part (Eclipse draws neither). showbreak is global-local: NONE overrides the
            -- global value for this window, '' would fall back to it.
            local BREAK = { linebreak = true, breakindent = false, showbreak = 'NONE' }

            local function getlocal(win, name)
              return vim.api.nvim_get_option_value(name, { scope = 'local', win = win })
            end
            local function setlocal(win, name, value)
              vim.api.nvim_set_option_value(name, value, { scope = 'local', win = win })
            end
            local function notify(...)
              local chan = vim.g.neovim4eclipse_channel
              if chan then
                vim.rpcnotify(chan, ...)
              end
            end

            -- Wrap on: keep the user's three values once, then set the plug-in's. Wrap off: give
            -- them back. A window variable, so each window keeps its own.
            local function apply_break(win, on)
              local saved = vim.w[win].neovim4eclipse_saved
              if on then
                if not saved then
                  saved = {}
                  for name in pairs(BREAK) do
                    saved[name] = getlocal(win, name)
                  end
                  vim.w[win].neovim4eclipse_saved = saved
                end
                for name, value in pairs(BREAK) do
                  setlocal(win, name, value)
                end
              elseif saved then
                for name, value in pairs(saved) do
                  setlocal(win, name, value)
                end
                vim.w[win].neovim4eclipse_saved = nil
              end
            end

            function M.overhead()
              local win = vim.api.nvim_get_current_win()
              return { vim.o.lines - vim.fn.winheight(win), vim.fn.getwininfo(win)[1].textoff }
            end

            function M.set_wrap(on)
              local win = vim.api.nvim_get_current_win()
              applying = true
              local ok, err = pcall(function()
                setlocal(win, 'wrap', on)
                apply_break(win, on)
              end)
              applying = false
              assert(ok, err)
            end

            local group = vim.api.nvim_create_augroup('neovim4eclipse_viewport', { clear = true })
            vim.api.nvim_create_autocmd('OptionSet', {
              group = group,
              pattern = 'wrap',
              callback = function()
                if applying then
                  return
                end
                local win = vim.api.nvim_get_current_win()
                local on = vim.wo[win].wrap
                applying = true
                pcall(apply_break, win, on)
                applying = false
                notify('eclipse_wrap', vim.api.nvim_get_current_buf(), on, win)
              end,
            })
            vim.api.nvim_create_autocmd('OptionSet', {
              group = group,
              pattern = { 'linebreak', 'breakindent', 'showbreak' },
              callback = function(ev)
                local win = vim.api.nvim_get_current_win()
                local saved = vim.w[win].neovim4eclipse_saved
                if applying or not saved or not vim.wo[win].wrap then
                  return
                end
                local name = ev.match
                saved[name] = getlocal(win, name) -- the user's wish, for when wrap is off again
                vim.w[win].neovim4eclipse_saved = saved
                applying = true
                pcall(setlocal, win, name, BREAK[name])
                applying = false
                if not told then
                  told = true
                  vim.api.nvim_echo({ { "neovim4eclipse: '" .. name .. "' stays as it is while word wrap is"
                    .. ' on, so that lines wrap where Eclipse wraps them', 'WarningMsg' } }, true, {})
                end
              end,
            })
            local pending = false
            vim.api.nvim_create_autocmd('OptionSet', {
              group = group,
              pattern = { 'number', 'relativenumber', 'numberwidth', 'signcolumn', 'foldcolumn',
                          'statuscolumn', 'laststatus', 'winbar', 'showtabline', 'cmdheight' },
              callback = function()
                if pending then
                  return
                end
                pending = true
                vim.schedule(function()
                  pending = false
                  notify('eclipse_viewport')
                end)
              end,
            })

            -- 'keep': winrestview keeps the cursor, and a top line that would hide it is clamped
            -- (Neovim's own rule); WinScrolled then reports the line Neovim chose, which the editor
            -- takes. 'drag': the cursor goes into the new view first, as Ctrl-E moves it. 'show':
            -- 'drag' for a cursor outside the new view, 'keep' for one inside it.
            -- Example, 20 rows, 'scrolloff' 0, cursor on 5: set_topline(40, 'drag') leaves the
            -- cursor on 40; set_topline(40, 'keep') scrolls only as far as the cursor allows (topline 5).
            function M.set_topline(line, mode)
              local last = vim.api.nvim_buf_line_count(0)
              line = math.max(1, math.min(line, last))
              if mode == 'keep' then
                vim.fn.winrestview({ topline = line })
                return
              end
              local view = vim.fn.winsaveview()
              -- The cursor on the top line first: nothing clamps, and line('w$') is the new view's.
              vim.fn.winrestview({ topline = line, lnum = line, col = 0 })
              local bottom = vim.fn.line('w$')
              if mode == 'show' and view.lnum >= line and view.lnum <= bottom then
                vim.fn.winrestview({ topline = line, lnum = view.lnum, col = view.col, curswant = view.curswant })
                return
              end
              local so = vim.wo.scrolloff
              if so < 0 then
                so = vim.o.scrolloff -- global-local: -1 means the global value
              end
              so = math.min(so, math.floor((bottom - line) / 2))
              local lo = line == 1 and 1 or line + so
              local hi = bottom == last and bottom or math.max(lo, bottom - so)
              local lnum = math.max(lo, math.min(view.lnum, hi))
              vim.fn.winrestview({ topline = line, lnum = lnum, col = view.col, curswant = view.curswant })
            end

            -- n lines from top, a closed fold counting as one, as Ctrl-E and Ctrl-Y step. Stops at
            -- the first line, and at the last (Ctrl-E can put it at the top). Example: top 1, a
            -- closed fold on lines 5-10, n = 5: 2, 3, 4, 5, then 11.
            function M.scroll_lines(n)
              local top, last = vim.fn.line('w0'), vim.api.nvim_buf_line_count(0)
              for _ = 1, math.abs(n) do
                if n > 0 then
                  local fold_end = vim.fn.foldclosedend(top)
                  local next_top = (fold_end ~= -1 and fold_end or top) + 1
                  if next_top > last then
                    break
                  end
                  top = next_top
                else
                  if top <= 1 then
                    break
                  end
                  top = top - 1
                  local fold_start = vim.fn.foldclosed(top)
                  if fold_start ~= -1 then
                    top = fold_start
                  end
                end
              end
              M.set_topline(top, 'drag')
            end

            vim.api.nvim_create_autocmd('WinScrolled', {
              group = group,
              callback = function()
                local win = vim.api.nvim_get_current_win()
                if vim.v.event[tostring(win)] == nil then
                  return -- another window (a split, a float) scrolled
                end
                notify('eclipse_topline', vim.api.nvim_get_current_buf(), vim.fn.line('w0', win), win)
              end,
            })
            package.loaded['neovim4eclipse.viewport'] = M
            return true
            """;

    /**
     * The rows and columns Neovim's screen has besides the current window's text,
     * {@code { rows, columns }}; see {@link #VIEWPORT}'s {@code overhead}. Sent only when the
     * viewport is on, so the module is there.
     */
    static final String VIEWPORT_OVERHEAD = """
            return require('neovim4eclipse.viewport').overhead()
            """;

    /**
     * Sets {@code 'wrap'} (and with it {@code 'linebreak'}, see {@link #VIEWPORT}) in the current
     * window to Eclipse's word wrap (arguments: the buffer number, {@code "1"} for wrap or {@code "0"}).
     * Nothing when that buffer is not the current one: the window shows another editor's document
     * by now, whose own word wrap is sent when it is attached.
     */
    static final String SET_WRAP = """
            local buf, on = tonumber((select(1, ...))), (select(2, ...))
            if vim.api.nvim_get_current_buf() ~= buf then
              return
            end
            require('neovim4eclipse.viewport').set_wrap(on == true or on == 1 or on == '1')
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
            if vim.api.nvim_get_current_buf() ~= buf or not line then
              return
            end
            require('neovim4eclipse.viewport').set_topline(line, mode)
            """;

    /**
     * Scrolls the current window by the mouse wheel (arguments: the buffer number and the lines,
     * positive down, as strings); see {@link #VIEWPORT}'s {@code scroll_lines} and
     * {@code util/WheelScroll}. Nothing when that buffer is not the current one. Example:
     * {@code ("3", "-3")} scrolls three lines up, as {@code 3 Ctrl-Y}.
     */
    static final String SCROLL_LINES = """
            local buf, n = tonumber((select(1, ...))), tonumber((select(2, ...)))
            if vim.api.nvim_get_current_buf() ~= buf or not n or n == 0 then
              return
            end
            require('neovim4eclipse.viewport').scroll_lines(n)
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
            package.loaded['neovim4eclipse.load'] = { ... }
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
     *       Neovim itself refuses an existing file without {@code !} (E13), before the hook. An editor
     *       without a file of its own (an untitled one) has nothing to keep: Eclipse saves it as
     *       {@code other.txt} instead and answers with that file, and the buffer takes its name
     *       ({@code nvim_buf_set_name}), as Vim names a buffer without a file after the file it
     *       writes ({@code :help cpo-F}, which Neovim does not apply to an {@code acwrite} buffer).
     *       Renamed so, the buffer is {@code [Not edited]} until its next write, which works as
     *       usual. Eclipse alone decides copy or save as ({@code editor/OtherFileWrites.copyOrSaveAs});
     *       the hook only follows the answer. Should the rename fail (a loaded buffer has the name),
     *       the buffer keeps its name and file, so a later {@code :w} is the editor's own save.</li>
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
     * The names are compared by {@link #PATHS}'s rule (which also ignores case on Windows and macOS,
     * so {@code :w notes.txt} in {@code Notes.txt} is a save there, not a copy): Neovim resolves
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
     *
     * <h2>Reloaded by Neovim</h2>
     * A typed {@code :e!} goes to Eclipse, which reverts the editor ({@link #HOST_COMMANDS}). A
     * script's or a plug-in's {@code :edit!} (one that reloads files after a {@code git checkout},
     * say) runs inside Neovim: it unloads the buffer and reads the file from disk, over Eclipse's
     * text and its unsaved changes, and the unload ends the buffer's line events
     * ({@code nvim_buf_attach}), so later changes in Neovim would never reach the document. Every
     * document's buffer, whether its file is inside a project or not (a file opened with <i>File,
     * Open File...</i>), has two buffer-local autocommands instead:
     * <ol>
     *   <li>{@code BufUnload} keeps the buffer's lines, {@code 'modified'} and its marks (the text
     *       is still there at that moment; Neovim drops an {@code acwrite} buffer's marks on unload,
     *       where Vim's {@code :edit!} keeps them), until Neovim's next loop.</li>
     *   <li>{@code BufReadCmd} replaces the read: it puts those lines back, under {@code :lockmarks}
     *       and without an undo step, and sends {@code eclipse_reload(buf, true)}. The buffer is never
     *       empty, so the cursor keeps its line (Neovim puts it back after the read, as for any
     *       {@code :edit!}) and a script reading the buffer right after its {@code :edit!} sees the
     *       text. Eclipse attaches the buffer again, with the whole buffer sent first, and applies
     *       any difference: keys Neovim handled between the unload and the new attach reach the
     *       document ({@code NeovimSession}, "re-attach after a reload").</li>
     * </ol>
     * A read with nothing kept (the buffer unloaded on its own, by a plug-in's {@code :bunload}, and
     * read later) sends {@code eclipse_reload(buf, false)}, and Eclipse loads the document's text
     * into it ({@link #RELOAD_DOCUMENT_BUFFER}). Example: {@code a.txt} has an unsaved {@code x} and
     * the cursor on line 3; after {@code :lua vim.cmd('edit!')} the buffer still holds the {@code x},
     * the cursor is still on line 3, and typing in Neovim still changes the document.
     */
    static final String DOCUMENT_BUFFER = """
            local buf = tonumber(select(1, ...))
            vim.bo[buf].buftype = 'acwrite'
            -- The plug-in's autocommands for this buffer, in one group: CLOSE_DOCUMENT_BUFFER removes
            -- them when the last editor closes, and a buffer reused for a new editor gets them once.
            local group = vim.api.nvim_create_augroup('neovim4eclipse_document', { clear = false })
            vim.api.nvim_clear_autocmds({ group = group, buf = buf })
            -- The editor's file, as Neovim names it (links resolved). Writes are compared with it, not
            -- with the buffer's name, which :saveas and :file change.
            vim.b[buf].neovim4eclipse_file = vim.api.nvim_buf_get_name(buf)

            -- An error message, as Neovim shows its own: err = true sets v:errmsg and the ErrorMsg
            -- highlight, and marks the message as an error (msg_show kind "echoerr").
            local function fail(msg)
              vim.api.nvim_echo({ { msg } }, true, { err = true })
            end
            -- PATHS's rule: the buffer name has its links resolved, ev.match not (/private/var/x and
            -- /var/x on macOS); on Windows ev.match has / where the buffer name has \\ (C:/x/a.txt,
            -- C:\\x\\a.txt); and on Windows and macOS a name in other case is the same file.
            local canonical = require('neovim4eclipse.paths').canonical
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
                -- Compared canonically: on Windows Neovim reports a written name with forward slashes
                -- (C:/tmp/other.txt) while the buffer left behind keeps backslashes.
                local wanted = vim.tbl_map(canonical, names)
                for _, other in ipairs(vim.api.nvim_list_bufs()) do
                  local name = vim.api.nvim_buf_get_name(other)
                  if other ~= keep and not vim.api.nvim_buf_is_loaded(other) and not vim.b[other].neovim4eclipse_file
                      and name ~= '' and vim.tbl_contains(wanted, canonical(name)) then
                    pcall(vim.api.nvim_buf_delete, other, { force = true })
                  end
                end
              end)
            end
            -- Asks Eclipse to write, and shows its answer. kind '' saves the editor; 'copy', 'lines' and
            -- 'append' write to the file `name` (lines first..last). Neovim waits for the answer:
            -- Eclipse writes on its UI thread (see EditorAttachManager). v:cmdarg has the write's
            -- ++opt arguments (" ++p" for :w ++p); v:cmdbang the !, which lets >> create a file.
            -- Returns whether it was written, and the file the editor now shows when Eclipse saved
            -- the document as another file (:saveas, or a 'copy' of an editor without a file), else ''.
            local function ask(b, kind, name, first, last)
              local answer = vim.rpcrequest(vim.g.neovim4eclipse_channel, 'eclipse_write', b, vim.v.cmdarg,
                kind, name, first, last, vim.v.cmdbang == 1)
              -- { error, message, saved_as }, each '' when none
              local err, written, saved_as = answer[1], answer[2], answer[3] or ''
              if err ~= '' then
                fail(err)
                return false, ''
              end
              if written ~= '' then
                -- Vim's own message after a write, which Neovim does not give for an acwrite buffer.
                vim.api.nvim_echo({ { written } }, true, {})
              end
              return true, saved_as
            end

            -- Buffers whose :saveas asked Eclipse, so their rename is no :file (see BufFilePost below).
            local saveas_tried = {}

            vim.api.nvim_create_autocmd('BufWriteCmd', {
              buf = buf, -- named "buffer" before Neovim 0.12.2
              group = group,
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
                  local old = vim.api.nvim_buf_get_name(ev.buf)
                  local ok, saved_as = ask(ev.buf, 'copy', target, 0, 0)
                  if ok and saved_as ~= '' then
                    -- An editor without a file of its own (an untitled one): Eclipse saved it as the
                    -- file instead (OtherFileWrites.copyOrSaveAs), and the editor shows that file now.
                    -- Vim too names a buffer without a file after the file it writes (:help cpo-F),
                    -- which Neovim does not do for an acwrite buffer. Renamed here, the buffer is
                    -- "[Not edited]" until its next write. As in Vim, an unloaded buffer of that name
                    -- (Neovim's alternate file, or the buffer kept from a closed editor of the file)
                    -- is wiped by the rename; a loaded one makes it fail (E95).
                    saveas_tried[ev.buf] = true -- the BufFilePost check below then stays quiet
                    local renamed, rename_err = pcall(vim.api.nvim_buf_set_name, ev.buf, saved_as)
                    if renamed then
                      vim.b[ev.buf].neovim4eclipse_file = saved_as
                      vim.bo[ev.buf].modified = false
                    else
                      -- The buffer keeps its name and its file, so a later :w is still the editor's
                      -- own save (Eclipse saves the file the editor shows), never a save as under the
                      -- old name.
                      saveas_tried[ev.buf] = nil
                      fail('Eclipse saved ' .. vim.fn.fnamemodify(saved_as, ':t')
                        .. ', but Neovim could not name this buffer after it: ' .. tostring(rename_err))
                    end
                  end
                  wipe_left_over(ev.buf, { ev.match, target, old })
                end
              end,
            })
            -- :5,10w part.txt and :w >> log.txt: some or all lines to another file, between the marks
            -- '[ and '] that Neovim sets for the write. Not to the editor's own file, which Eclipse
            -- saves whole: :5,10w! would leave only those lines on disk, and >> would double the file.
            for event, kind in pairs({ FileWriteCmd = 'lines', FileAppendCmd = 'append' }) do
              vim.api.nvim_create_autocmd(event, {
                buf = buf,
                group = group,
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
              group = group,
              callback = function(ev)
                names_before[ev.buf] = vim.api.nvim_buf_get_name(ev.buf)
              end,
            })
            vim.api.nvim_create_autocmd('BufFilePost', {
              buf = buf,
              group = group,
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
            -- editor too. Eclipse ignores it for a buffer no editor shows. (When Eclipse closes the
            -- last editor, CLOSE_DOCUMENT_BUFFER removes this autocommand first.)
            vim.api.nvim_create_autocmd({ 'BufDelete', 'BufWipeout' }, {
              buf = buf,
              group = group,
              desc = 'Eclipse closes the editor',
              callback = function(ev)
                vim.rpcnotify(vim.g.neovim4eclipse_channel, 'eclipse_buffer_closed', ev.buf)
              end,
            })
            -- Reloaded by Neovim (a script's or a plug-in's :edit!): nothing is read from disk, which
            -- would replace Eclipse's text and its unsaved changes. The text the unload took is put
            -- back at once, so the buffer is never empty, and Eclipse attaches again
            -- (eclipse_reload, true). Kept only until Neovim's next loop: a buffer unloaded on its own
            -- (:bunload) and read much later gets Eclipse's text instead (eclipse_reload, false).
            -- The marks too: Neovim drops an acwrite buffer's a-z marks when it unloads it, and puts
            -- its A-Z marks on line 1 (BUFFER_NUMBERS.md, X4), where Vim's :edit! keeps them.
            local kept
            vim.api.nvim_create_autocmd('BufUnload', {
              buf = buf,
              group = group,
              desc = 'Eclipse keeps the text for a reload',
              callback = function(ev)
                local marks = {}
                for c in ('abcdefghijklmnopqrstuvwxyz'):gmatch('.') do
                  local m = vim.api.nvim_buf_get_mark(ev.buf, c)
                  if m[1] > 0 then
                    marks[c] = m
                  end
                end
                for c in ('ABCDEFGHIJKLMNOPQRSTUVWXYZ'):gmatch('.') do
                  local m = vim.api.nvim_get_mark(c, {})
                  if m[3] == ev.buf and m[1] > 0 then
                    marks[c] = { m[1], m[2] }
                  end
                end
                kept = { lines = vim.api.nvim_buf_get_lines(ev.buf, 0, -1, false), modified = vim.bo[ev.buf].modified,
                         marks = marks }
                vim.schedule(function()
                  kept = nil
                end)
              end,
            })
            vim.api.nvim_create_autocmd('BufReadCmd', {
              buf = buf,
              group = group,
              desc = 'Eclipse keeps the document',
              callback = function(ev)
                local k = kept
                kept = nil
                if k then
                  local saved = vim.bo[ev.buf].undolevels
                  vim.bo[ev.buf].undolevels = -1 -- no undo step, as for a file just read
                  package.loaded['neovim4eclipse.load'] = { buf = ev.buf, lines = k.lines }
                  pcall(vim.cmd, "lockmarks lua local l = package.loaded['neovim4eclipse.load'] "
                    .. "vim.api.nvim_buf_set_lines(l.buf, 0, -1, false, l.lines)")
                  package.loaded['neovim4eclipse.load'] = nil
                  vim.bo[ev.buf].undolevels = saved
                  vim.bo[ev.buf].modified = k.modified
                  for c, m in pairs(k.marks) do
                    pcall(vim.api.nvim_buf_set_mark, ev.buf, c, m[1], m[2], {})
                  end
                end
                vim.rpcnotify(vim.g.neovim4eclipse_channel, 'eclipse_reload', ev.buf, k ~= nil)
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
     * position, and the plug-in draws nothing of Neovim's screen. Neovim's window shows what the
     * editor shows ({@link #VIEWPORT}), but not always: with the viewport off (a fixed 40 rows),
     * past the end of the document, or while a scroll is on its way. So the script asks Neovim where the
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
