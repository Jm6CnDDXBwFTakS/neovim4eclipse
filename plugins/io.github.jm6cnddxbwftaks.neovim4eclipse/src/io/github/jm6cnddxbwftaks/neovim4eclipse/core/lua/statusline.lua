-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- Neovim's status line of each editor's window, for the row the editor draws under its text
-- (`editor/StatusLineRow`; docs/ARCHITECTURE.md, "The status line in the editor").
--
-- Why Neovim evaluates it, and not the screen
-- Each editor's window is alone in a hidden tabpage (LuaScripts.WINDOWS), and Neovim redraws only
-- the current tabpage: its screen holds the focused editor's status line only, and a background
-- editor's would stay as it was, in the colors of the window with the cursor. So each window's line
-- is evaluated (`nvim_eval_statusline` with `winid`): Neovim then draws a window that is not
-- current in `StatusLineNC`; and `g:statusline_winid` and `g:actual_curwin`, which the call does
-- not set but Neovim's own drawing does, are set around it, so that a 'statusline' expression
-- that reads them draws such a window as on Neovim's own screen (Neovim's default reads
-- `g:actual_curwin`, a `%!` or `%{%...%}` Lua line often `g:statusline_winid`). lualine is not
-- one of them: it writes each window's line as finished text, refreshed for the current tabpage
-- only, so an editor in the background shows the line lualine last wrote for its window.
--
-- What `line(win)` answers
-- `{shown, cells, hl, attrs}`: whether the window has a status line (`'laststatus'`: 0 none; 1 only
-- with another window in its tabpage, so none for an editor's window alone; 2 always; 3 one for the
-- screen, the current window's); the line's cells, one string per screen cell, a wide character's
-- second cell `""`, padded with spaces to the editor's width (sent by the editor's row,
-- `set_width`; the window's own width until it is known); the index into `attrs` of each cell's
-- highlight (1-based); and per highlight `{fg, bg, reverse, bold, italic, underline,
-- strikethrough}`, colors as `0xRRGGBB`, a color the group leaves unset taken from `StatusLine` or
-- `StatusLineNC` (as Neovim draws it), -1 when that leaves it too.
--
-- A `'statusline'` that a modeline set (with `'modelineexpr'` on) is not evaluated: Neovim runs
-- such a one in its sandbox, and `nvim_eval_statusline` cannot tell where its string came from. The
-- default line is drawn instead. Example: `notes.txt` in a window 12 columns wide: cells `n, o, t,
-- e, s, ., t, x, t, " ", " ", " "`, every hl 1, attrs `StatusLine`'s colors.
--
-- When it is sent
-- The focused editor's line comes with each key's state snapshot (LuaScripts.STATE_SNAPSHOT). Every
-- window's line comes as `eclipse_status_lines` (`{{win, line, stamp}, ...}`) when what a background line
-- shows may have changed: the cursor entered another window (the left one turns `StatusLineNC`), a
-- buffer's modified flag, name or file changed, the colorscheme, `'laststatus'` or `'statusline'`,
-- a window's size. Several events in one moment send once (`vim.schedule`); so does the current
-- directory, a filetype or the diagnostics changing, and an editor's width (`set_width`).
--
-- Which line is newer
-- Every evaluation, and every line of a closed window, takes the next number of one counter
-- (`M.stamp`), and the number goes with the line: in the state snapshot beside it, in each
-- `eclipse_status_lines` entry as `{win, line, stamp}`. Eclipse draws a window's line only if its
-- number is above the last it drew for that window (core/StatusTracker). Why: the snapshot's answer
-- and a notification travel apart, and Eclipse handles them on two threads, so either can arrive
-- last. Neovim evaluates one line at a time, so the number orders them as Neovim made them.
-- Example: lualine sets 'statusline' to INSERT 16 ms after `i`, the notice carrying it is number
-- 8, the snapshot read just before carries NORMAL as number 7: drawn last or not, 7 never replaces 8.
--
-- Sent as LuaScripts.STATUS_LINE (core/LuaModules runs this file as a chunk of its own).

local M = {}
M.stamp = 0
local DEFAULT = '%<%f %h%m%r%=%-14.(%l,%c%V%) %P'
-- Each editor's width in columns, sent by its row (set_width): every tabpage shares one
-- screen, sized to the focused editor, so a background editor's window is that wide,
-- not as wide as its own editor.
M.widths = {}

function M.set_width(win, cols)
  if M.widths[win] ~= cols then
    M.widths[win] = cols
    M.send_all()
  end
end

local function shown(win)
  if vim.api.nvim_win_get_config(win).relative ~= '' then return false end -- a float has none
  local ls = vim.o.laststatus
  if ls == 0 then return false end
  if ls == 3 then return win == vim.api.nvim_get_current_win() end
  if ls == 2 then return true end
  local others = 0 -- 1: only with another (not floating) window in its tabpage
  for _, w in ipairs(vim.api.nvim_tabpage_list_wins(vim.api.nvim_win_get_tabpage(win))) do
    if vim.api.nvim_win_get_config(w).relative == '' then others = others + 1 end
  end
  return others > 1
end

-- A group's colors; what it leaves unset is the fill group's (StatusLine or StatusLineNC),
-- as Neovim draws %#Identifier# (a text color only) over the status line's background.
local function attr(group, fill)
  local h = vim.api.nvim_get_hl(0, { name = group, link = false })
  local f = group == fill and {} or vim.api.nvim_get_hl(0, { name = fill, link = false })
  return { h.fg or f.fg or -1, h.bg or f.bg or -1, (h.reverse or f.reverse) == true,
           h.bold == true, h.italic == true, h.underline == true or h.undercurl == true,
           h.strikethrough == true }
end

-- Answers the line and its evaluation's number (see "Which line is newer"), taken as it starts:
-- a 'statusline' expression that runs the event loop could evaluate another line inside this one.
function M.line(win)
  M.stamp = M.stamp + 1
  local stamp = M.stamp
  if not vim.api.nvim_win_is_valid(win) or not shown(win) then
    return { false }, stamp
  end
  local current = win == vim.api.nvim_get_current_win()
  local width = M.widths[win] or vim.api.nvim_win_get_width(win)
  local fmt = vim.api.nvim_get_option_value('statusline', { win = win })
  -- A 'statusline' a modeline set (last_set_sid -1, possible with 'modelineexpr' on):
  -- Neovim evaluates such a one in the sandbox, nvim_eval_statusline does not know where
  -- its string came from. So a file's own line is not evaluated: the default is.
  local set_by = vim.api.nvim_get_option_info2('statusline', { win = win }).last_set_sid
  if fmt == '' or set_by == -1 then fmt = DEFAULT end
  -- Neovim sets these two while it draws a status line, and nvim_eval_statusline does
  -- not: a line's expression may read g:statusline_winid to know which window it draws
  -- (its inactive sections for one that is not current), Neovim's default line reads
  -- g:actual_curwin. Set as Neovim does, put back after.
  local old_winid, old_curwin = vim.g.statusline_winid, vim.g.actual_curwin
  vim.g.statusline_winid, vim.g.actual_curwin = win, vim.api.nvim_get_current_win()
  local ok, res = pcall(vim.api.nvim_eval_statusline, fmt,
    { winid = win, maxwidth = width, highlights = true })
  vim.g.statusline_winid, vim.g.actual_curwin = old_winid, old_curwin
  if not ok or type(res) ~= 'table' then
    res = { str = '', highlights = {} }
  end
  local marks = res.highlights or {}
  local fill = current and 'StatusLine' or 'StatusLineNC'
  if #marks == 0 then marks = { { start = 0, group = fill } } end
  local attrs, index = {}, {}
  local function id(group)
    if not index[group] then
      attrs[#attrs + 1] = attr(group, fill)
      index[group] = #attrs
    end
    return index[group]
  end
  local cells, hl = {}, {}
  local str, byte, mark = res.str or '', 0, 1
  local group = marks[1].group
  for _, ch in ipairs(vim.fn.split(str, [[\zs]])) do
    while marks[mark + 1] and marks[mark + 1].start <= byte do
      mark = mark + 1
      group = marks[mark].group
    end
    byte = byte + #ch
    local w = vim.fn.strdisplaywidth(ch)
    if w > 0 and #cells + w <= width then
      cells[#cells + 1] = ch
      hl[#hl + 1] = id(group)
      for _ = 2, w do
        cells[#cells + 1] = ''
        hl[#hl + 1] = id(group)
      end
    elseif w == 0 and #cells > 0 then
      cells[#cells] = cells[#cells] .. ch -- a combining mark joins its character
    end
  end
  while #cells < width do -- Neovim fills the rest of the row in the last group
    cells[#cells + 1] = ' '
    hl[#hl + 1] = id(group)
  end
  return { true, cells, hl, attrs }, stamp
end

function M.all()
  local lines = {}
  for _, win in ipairs(vim.api.nvim_list_wins()) do
    if vim.api.nvim_win_get_config(win).relative == '' then
      local line, stamp = M.line(win)
      lines[#lines + 1] = { win, line, stamp }
    end
  end
  return lines
end

local pending = false
function M.send_all()
  if pending then return end
  pending = true
  vim.schedule(function()
    pending = false
    vim.rpcnotify(vim.g.neovim4eclipse_channel or 0, 'eclipse_status_lines', M.all())
  end)
end

local group = vim.api.nvim_create_augroup('neovim4eclipse_status_line', { clear = true })
vim.api.nvim_create_autocmd({ 'WinEnter', 'BufModifiedSet', 'BufWritePost', 'BufFilePost',
                              'BufEnter', 'ColorScheme', 'VimResized', 'WinResized',
                              'DirChanged', 'FileType', 'DiagnosticChanged' },
  { group = group, callback = M.send_all })
vim.api.nvim_create_autocmd('WinClosed', { group = group, callback = function(ev)
  local win = tonumber(ev.match)
  M.widths[win] = nil -- window ids are not reused: only a closed one's width goes
  local ok, config = pcall(vim.api.nvim_win_get_config, win)
  if not ok or config.relative ~= '' then return end -- a float (ui2's, a picker) has no line
  -- An editor keeps its row while another has the keyboard (editor/StatusRows): a
  -- closed window's line is a line of no rows, which takes its row away.
  M.stamp = M.stamp + 1
  vim.rpcnotify(vim.g.neovim4eclipse_channel or 0, 'eclipse_status_lines', { { win, { false }, M.stamp } })
end })
vim.api.nvim_create_autocmd('OptionSet', { group = group,
  pattern = { 'laststatus', 'statusline' }, callback = M.send_all })
package.loaded['neovim4eclipse.statusline'] = M
return true
