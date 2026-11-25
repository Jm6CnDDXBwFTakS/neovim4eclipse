-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- One-time session setup: options plus autocmds that push state to Eclipse.
-- - `timeout` and `timeoutlen` are left alone: Neovim's default (1000 ms) or the user's `init.lua`
--   decides how long Neovim waits for a mapping's second key. A shorter wait made a slowly typed
--   `[d` Vim's older `[d` (search for a macro definition, E388) instead of the diagnostics mapping,
--   as it does in plain Neovim when the pause is longer than the wait.
-- - `showmode=false`: Eclipse shows the mode in its own status line.
-- - Autocmds emit `eclipse_mode` (the mode, and whether the current window is a plug-in's focused
--   floating window: LuaScripts.WINDOWS' `focused_float`), `eclipse_cursor` (with the buffer
--   number, the window's top line, `line('w0')`, and the window id), `eclipse_buf_enter` (the
--   buffer and the window id: with a window per editor, docs/design/WINDOWS.md, the window tells
--   two editors on one file apart; whether the move is in the jump history; and the file of a
--   project's buffer without an editor, from LuaScripts.PROJECT_FILES, or `""`) and `eclipse_yank`
--   notifications handled by NotificationBridge.
-- - Unless the user's config sets `g:clipboard`, a clipboard provider whose copy sends
--   `eclipse_clipboard_copy` and whose paste is the `eclipse_clipboard_paste` request (see
--   ClipboardRegisters).
-- - The one argument is Eclipse's RPC channel number, which requests are sent to. It is kept in
--   `g:neovim4eclipse_channel` for LuaScripts.DOCUMENT_BUFFER_MODULE's write hook.
-- - The mappings of `gd`, `K`, the `gr*` keys, `gt` and `gT`, which send `eclipse_action` so that
--   the IDE does those, are LuaScripts.IDE_KEYS, run right after this script; `ZZ`, `ZQ` and the
--   history keys below are here.
-- - `Ctrl-O` and `Ctrl-I` past the end of a window's jumps are Eclipse's Back and Forward; a trail
--   of the `Ctrl-O` steps (module `neovim4eclipse.history`, LuaScripts.HISTORY_NOWHERE) lets
--   `Ctrl-I` undo the newest step first.
-- - The diagnostics namespace `neovim4eclipse` (LuaScripts.SET_DIAGNOSTICS) draws nothing, and,
--   unless the user's config has its own, a `vim.diagnostic` `on_jump` hook echoes the first line
--   of the problem `]d` lands on.
-- - The command-line window (`q:`, `q/`, `Ctrl-F` on the command line) sends `eclipse_cmdwin` with
--   its window and type when it opens, and ` eclipse_cmdwin_closed` when it closes: Eclipse draws
--   it from its grid, over the bottom of the editor (`editor/NeovimWindowPanel`). Its buffer sends
--   no ` eclipse_buf_enter`: Eclipse would take Neovim back to the editor's window, which Neovim
--   refuses while the command-line window is open (E11).
-- - Two guards against files a cloned repository brings, both kept when your config already
--   decided:
--   - `g:rmd_dynamic_fenced_languages` is off. Neovim 0.12.6's R Markdown syntax file runs a
--     backtick part of a crafted chunk header in a shell (Vim advisory GHSA-x2w5-pj55-7hpc, fixed
--     in Vim 9.2.1178; no stable Neovim has the fix yet). Neovim highlights only files it reads
--     itself (not files of open projects), and Eclipse shows none of its highlighting; the setting
--     only stops Neovim guessing each chunk's language from the file. Drop this once the pinned
--     Neovim has the fix (docs/UPGRADING.md).
--   - `package.path` and `package.cpath` lose their `./` entries. Neovim's folder is the project's
--     (`:lcd`), so a `require` of a module not on the runtimepath (a config's `pcall(require,
--     'optional')`) would load `./optional.lua` from the cloned repository.
--
-- Sent as LuaScripts.SETUP (core/LuaModules runs this file as a chunk of its own).

-- vim.rpcrequest needs Eclipse's channel number (rpcnotify(0, ...) below means "every
-- channel"). Eclipse passes it as the script's argument: inside Lua run through
-- nvim_exec_lua, nvim_get_chan_info(0) does not know which channel called.
local chan = tonumber(select(1, ...))
vim.g.neovim4eclipse_channel = chan
-- Guards against files of a cloned repository (see this file's header): no R Markdown chunk
-- language guessing, which runs a crafted header's backticks in Neovim 0.12.6, and no
-- Lua modules from the working folder (the project's).
if vim.g.rmd_dynamic_fenced_languages == nil then
  vim.g.rmd_dynamic_fenced_languages = false
end
local function without_working_folder(path)
  local kept = {}
  for entry in path:gmatch('[^;]+') do
    if not entry:match('^%.[/' .. string.char(92) .. ']') then -- ./?.lua; with a backslash on Windows
      kept[#kept + 1] = entry
    end
  end
  return table.concat(kept, ';')
end
package.path = without_working_folder(package.path)
package.cpath = without_working_folder(package.cpath)
vim.opt.hlsearch = true
vim.opt.incsearch = true
vim.opt.ignorecase = true
vim.opt.smartcase = true
vim.opt.wrapscan = true
vim.opt.magic = true
vim.opt.showcmd = true
vim.opt.showmode = false
-- The user's insert-mode mappings and abbreviations stay: the text Eclipse typed is
-- re-typed into Neovim with nvim_feedkeys(text, 'n'), which applies none of them, so a
-- `jk` -> <Esc> mapping cannot end that re-typing half way (docs/design/INSERT_CTRL_KEYS.md, section 3).
-- The second value: whether the current window is a focused floating window of a
-- plug-in (a picker's prompt, :Lazy), as WINDOWS' focused_float says. Sent with every
-- mode, not on its own at WinEnter: the plug-in switches windows quietly in places
-- ('eventignore'), so only a value read at the mode change itself is sure to be current
-- when Eclipse decides who types an insert that begins (KeyInput.onModeReport).
-- Also at every SafeState, as Neovim is about to wait for a key, and even when the mode is
-- the one reported last: ModeChanged does not fire for a change made inside another
-- autocommand that is not ++nested (a plug-in's `:normal! v` in its WinEnter, an insert a
-- prompt buffer begins and ends there), and Eclipse may have read such a passing mode with
-- nvim_get_mode in between. Example: Neovim reports n, i, n as it leaves a picker, Eclipse's
-- own nvim_get_mode answers i after that, and nothing more changes: without this report
-- Eclipse would keep i. So whenever Neovim waits for a key, the last mode Eclipse has from
-- it is its mode (core/StateSync, "A report beats an answer"), with the exceptions of
-- SafeState itself (:help SafeState): not while an operator is pending, during Insert-mode
-- or command-line completion, and not in Terminal mode at all (measured), where a mode that
-- passed unreported stands until the next ModeChanged.
vim.api.nvim_create_autocmd({ 'ModeChanged', 'SafeState' }, {
  callback = function()
    local windows = package.loaded['neovim4eclipse.windows']
    vim.rpcnotify(0, 'eclipse_mode', vim.api.nvim_get_mode().mode,
      windows ~= nil and windows.focused_float(0))
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
    -- The command-line window's own buffer: Eclipse draws that window (eclipse_cmdwin
    -- below), and taking Neovim back to the editor's window is refused while it is open.
    if vim.fn.getcmdwintype() ~= '' then
      return
    end
    -- One of ui2's own buffers (UI2): its pager or prompt, entered for a moment. The
    -- editor draws it where it is; Eclipse is not told, or it would send Neovim back.
    -- The buffer, not the window: a file opened in the pager (gf, :e) is announced,
    -- and Eclipse sends Neovim back to the editor, as from any window it does not show
    -- (util/EnterDecision), so the file is never edited where Eclipse cannot see it.
    local ui2 = package.loaded['vim._core.ui2']
    if ui2 then
      for _, buf in pairs(ui2.bufs) do
        if buf == ev.buf then
          return
        end
      end
    end
    local left, jumped = vim.fn.bufnr('#'), false
    if left > 0 and left ~= ev.buf then
      for _, jump in ipairs(vim.fn.getjumplist()[1]) do
        if jump.bufnr == left then
          jumped = true
          break
        end
      end
    end
    -- The fourth value: the file of a buffer that has no editor yet (gf, 'A, quickfix:
    -- PROJECT_FILES), in a project or not, whose editor Eclipse opens; '' for any other.
    local project = package.loaded['neovim4eclipse.project_files']
    -- The fifth: whether a help page's window took this help buffer as its page
    -- (Ctrl-] in the help editor: WINDOWS' follow_page), so Eclipse leaves it there.
    local windows = package.loaded['neovim4eclipse.windows']
    local win = vim.api.nvim_get_current_win()
    local follows = windows ~= nil and windows.follow_page(win, ev.buf)
    -- The sixth: whether win is a plug-in's float Neovim stays in (WINDOWS' entered_float:
    -- a picker's prompt), which Eclipse leaves Neovim in rather than sending it back, so
    -- that the insert its plug-in starts begins there.
    vim.rpcnotify(0, 'eclipse_buf_enter', ev.buf, win, jumped,
      project and project.file_of(ev.buf) or '', follows, windows ~= nil and windows.entered_float(win))
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
-- After ]d, the message of the problem landed on is shown by Neovim, as Eclipse's
-- status bar shows it after Ctrl+. (Next Annotation). Neovim's on_jump hook (0.12) runs
-- after the cursor moved. Only when init.lua set no hook of its own (nor the older
-- jump.float): a user's choice wins. The hook is global, so this echoes any namespace's
-- diagnostic, ours or nvim-lint's. Only the first line: Neovim's line holds one, and a
-- longer echo would open the output box on every jump (K shows the whole hover).
local jump = vim.diagnostic.config().jump or {}
if jump.on_jump == nil and not jump.float then
  vim.diagnostic.config({ jump = { on_jump = function(diagnostic)
    if diagnostic and diagnostic.message then
      vim.api.nvim_echo({ { (diagnostic.message:gsub('\n.*', '')) } }, false, {})
    end
  end } })
end
-- The command-line window (q:, q/, Ctrl-F on the command line) is a window of its own,
-- with no editor behind it: Eclipse draws it from its grid (ext_multigrid) over the
-- bottom of the editor, and sends it every key while it is open. The window's ID tells
-- Eclipse which grid to draw; its type (':', '/', '?') is for the panel's title.
vim.api.nvim_create_autocmd('CmdwinEnter', {
  callback = function()
    vim.rpcnotify(0, 'eclipse_cmdwin', vim.api.nvim_get_current_win(), vim.fn.getcmdwintype())
  end
})
vim.api.nvim_create_autocmd('CmdwinLeave', {
  callback = function()
    vim.rpcnotify(0, 'eclipse_cmdwin_closed')
  end
})
