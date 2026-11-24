-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- Installs the viewport module, `require('neovim4eclipse.viewport')`. The session runs it once at
-- start, after LuaScripts.SETUP (so after `init.lua`): Neovim's window then follows the editor, as
-- in a terminal it follows the terminal's size (docs/design/VIEWPORT.md).
--
-- What it is for. Neovim's window is made the size of the editor (`nvim_ui_try_resize`, sent by the
-- session), so that `H`, `L`, `Ctrl-D` and `'scrolloff'` work on the lines the editor shows. The
-- module supplies what the session cannot know, and keeps `'wrap'` the same as Eclipse's word wrap:
-- - `overhead()`, used by LuaScripts.VIEWPORT_OVERHEAD: `{ &lines - winheight(0), textoff }`, the
--   rows and columns Neovim's screen has besides the window's text (status line, line numbers,
--   signs). The grid is made larger by them (`util/ViewportSize`). Example: `{ 1, 4 }` with the
--   default status line and `set number`.
-- - `set_wrap(on)`, used by LuaScripts.SET_WRAP: Eclipse's word wrap for the current window
--   (decision D2: Eclipse's state wins on attach).
-- - `set_topline(line, mode)`, used by LuaScripts.SET_TOPLINE: the editor's top line. `'keep'`
--   (attach): the cursor stays and Neovim keeps it visible, which may change the top line. `'drag'`
--   (the user scrolled the editor: the scroll bar, the wheel): the cursor is dragged into the new
--   view as `Ctrl-E` and `Ctrl-Y` drag it, `'scrolloff'` lines from its edges, so that the view is
--   kept. `'show'` (Esc, after Eclipse scrolled while it typed): a cursor in the view stays, one
--   outside it is dragged in, so the view is kept as Neovim's wheel would have kept it.
-- - `scroll_lines(n)`, used by LuaScripts.SCROLL_LINES: the mouse wheel. Scrolls `n` lines down
--   (negative: up) as `n` times `Ctrl-E` (`Ctrl-Y`) would, a closed fold counting as one line, and
--   drags the cursor along.
--
-- `'linebreak'` while wrap is on (D7). Eclipse wraps a long line at a word boundary; Neovim,
-- without `'linebreak'`, in the middle of a word. The line would then take a different number of
-- screen rows on each side, and `gj`, `H` or `Ctrl-D` would land on another row than the one shown.
-- So while wrap is on the window gets `'linebreak'`, no `'breakindent'` and `'showbreak'` `NONE`
-- (Eclipse draws neither an indent nor a marker for a wrapped part); the user's own values are kept
-- in `w:neovim4eclipse_saved` and come back when wrap goes off. A change of one of the three while
-- wrap is on is set back, with a message the first time in each Neovim session only: a filetype
-- plug-in or a modeline that sets them would otherwise show it for every file. The value the user
-- asked for is remembered, so it applies once wrap is off.
--
-- Changes made in Neovim reach Eclipse by notification, on the plug-in's channel
-- (`g:neovim4eclipse_channel`):
-- - `eclipse_wrap(buf, on, win)`: `:set wrap` or `:set nowrap`, which Eclipse turns into the
--   editor's word wrap. An option the plug-in set itself is not reported back (the `applying`
--   flag), as `CaretSync` ignores its own caret moves.
-- - `eclipse_viewport()`: an option that changes the overhead changed (`:set number`, `laststatus`,
--   ...); the session measures it again and resizes. Sent once per batch (scheduled), so `:set nu
--   rnu` resizes once.
-- - `eclipse_topline(buf, line, win)`: the current window scrolled without the cursor moving
--   (`Ctrl-E`, `zt`, a resize); the editor scrolls to `line`. A scroll that moves the cursor
--   (`50G`, `j` at the bottom) is reported again here, after `eclipse_cursor` already carried the
--   same top line; the second changes nothing. `WinScrolled` fires for every window that scrolled;
--   only the current one is the editor's.
--
-- Sent as LuaScripts.VIEWPORT (core/LuaModules runs this file as a chunk of its own).

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

local group = vim.api.nvim_create_augroup('neovim4eclipse_follow_editor', { clear = true })
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
