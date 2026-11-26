-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- LuaScripts.STATUS_LINE: each window's status line, evaluated by Neovim (nvim_eval_statusline)
-- and cut into screen cells, for the row each editor draws under its text (editor/StatusLineRow).
-- How the Java side reads an answer is StatusLinesTest; one round trip through the session is
-- NeovimSessionIntegrationTest (theCurrentWindowsStatusLineComesWithTheKeys).
--
-- An answer is { shown, cells, hl, attrs }: attrs[hl[i]] is cell i's highlight, as
-- { fg, bg, reverse, bold, italic, underline, strikethrough } (see the script's Javadoc).

local t = ...

local function lines()
  t.run('STATUS_LINE')
  return package.loaded['neovim4eclipse.statusline']
end

--- The highlight of cell `i` of `line`, as the script gives it.
local function attr_of(line, i)
  return line[4][line[3][i]]
end

--- The highlight Neovim gives `group`, in the script's form, to compare with.
local function expected(group)
  local h = vim.api.nvim_get_hl(0, { name = group, link = false })
  return { h.fg or -1, h.bg or -1, h.reverse == true, h.bold == true, h.italic == true,
           h.underline == true or h.undercurl == true, h.strikethrough == true }
end

t.test('the current window has StatusLine, a window in another tabpage StatusLineNC', function()
  local M = lines()
  vim.o.laststatus = 2
  -- Different colors, so that the two groups cannot be mistaken for each other.
  vim.api.nvim_set_hl(0, 'StatusLine', { fg = 0x111111, bg = 0xEEEEEE, bold = true })
  vim.api.nvim_set_hl(0, 'StatusLineNC', { fg = 0x222222, bg = 0xDDDDDD })
  vim.o.statusline = 'x' -- one plain item: the fill group covers the whole row
  local other = vim.api.nvim_tabpage_get_win(vim.api.nvim_open_tabpage(vim.api.nvim_create_buf(true, false), false, {}))
  local current = M.line(vim.api.nvim_get_current_win())
  t.eq(expected('StatusLine'), attr_of(current, 1), 'the current window')
  local not_current = M.line(other)
  t.eq(true, not_current[1], 'laststatus=2: every window has one')
  t.eq(expected('StatusLineNC'), attr_of(not_current, 1), 'not the current window')
end)

-- Neovim's rule ('laststatus'): 0 never, 1 only with another window in the tabpage, 2 always,
-- 3 one global line, which belongs to the current window.
t.test('laststatus 0, 1, 2 and 3 decide which windows have a line', function()
  local M = lines()
  local first = vim.api.nvim_get_current_win()
  vim.o.laststatus = 0
  t.eq({ false }, M.line(first), '0: none')
  vim.o.laststatus = 1
  t.eq({ false }, M.line(first), '1: alone in its tabpage, none')
  vim.cmd('split')
  local second = vim.api.nvim_get_current_win()
  t.eq(true, M.line(first)[1], '1: with another window, one')
  vim.o.laststatus = 2
  vim.cmd('only')
  t.eq(true, M.line(second)[1], '2: alone too')
  vim.cmd('split')
  vim.o.laststatus = 3
  t.eq(true, M.line(vim.api.nvim_get_current_win())[1], '3: the current window has the global line')
  t.eq({ false }, M.line(second), '3: the others have none')
end)

t.test('the cells fill the window\'s width', function()
  local M = lines()
  vim.o.laststatus = 2
  vim.o.statusline = 'ab'
  local win = vim.api.nvim_get_current_win()
  local line = M.line(win)
  t.eq(vim.api.nvim_win_get_width(win), #line[2], 'one cell per column')
  t.eq(#line[2], #line[3], 'one highlight per cell')
  t.eq({ 'a', 'b', ' ' }, vim.list_slice(line[2], 1, 3), 'the text, then blanks')
end)

t.test('a wide character takes two cells, the second ""', function()
  local M = lines()
  vim.o.laststatus = 2
  vim.o.statusline = 'aあb'
  local line = M.line(vim.api.nvim_get_current_win())
  t.eq({ 'a', 'あ', '', 'b' }, vim.list_slice(line[2], 1, 4))
end)

-- A 'statusline' may ask which window it is drawn for (%{g:statusline_winid}), as lualine does for
-- each window's own sections: it must be the window evaluated, not the current one.
t.test('g:statusline_winid is the window evaluated', function()
  local M = lines()
  vim.o.laststatus = 2
  vim.o.statusline = '%{g:statusline_winid}'
  local other = vim.api.nvim_tabpage_get_win(vim.api.nvim_open_tabpage(vim.api.nvim_create_buf(true, false), false, {}))
  local text = vim.trim(table.concat(M.line(other)[2]))
  t.eq(tostring(other), text)
end)

t.test('send_all notifies eclipse_status_lines with every window\'s line', function()
  local M = lines()
  vim.o.laststatus = 2
  t.forget()
  M.send_all()
  M.send_all() -- a second call before the first was sent adds nothing
  t.wait_for('eclipse_status_lines', function() return #t.sent('eclipse_status_lines') > 0 end)
  local sent = t.sent('eclipse_status_lines')
  t.eq(1, #sent, 'one notification')
  local all = sent[1][1]
  t.eq(#vim.api.nvim_list_wins(), #all, 'one entry per window')
  t.eq(vim.api.nvim_get_current_win(), all[1][1])
  t.eq(true, all[1][2][1])
  t.eq('number', type(all[1][3]), 'each line with its number')
end)

-- Which line is newer: each evaluation takes the next number, in Neovim's order, so Eclipse can
-- drop a line older than one it drew already, whichever arrives last (core/StatusTracker).
t.test('each evaluation takes the next number, and every entry sent carries its own', function()
  local M = lines()
  vim.o.laststatus = 2
  vim.api.nvim_open_win(vim.api.nvim_create_buf(true, false), false, { split = 'right' })
  local before = M.stamp
  local _, stamp = M.line(vim.api.nvim_get_current_win())
  t.eq(before + 1, M.stamp, 'one evaluation, one number')
  t.eq(M.stamp, stamp, 'answered with the line')
  local all = M.all()
  t.eq(#all, M.stamp - before - 1, 'one number for each window\'s line')
  for i, entry in ipairs(all) do
    t.eq(before + 1 + i, entry[3], 'in the order Neovim evaluated them')
  end
end)

-- An editor keeps its row while another has the keyboard (editor/StatusRows): when its window
-- closes, a line of no rows takes the row away, since Neovim sends no line for it any more.
t.test('a closed window\'s line is sent as none', function()
  lines()
  vim.o.laststatus = 2
  local win = vim.api.nvim_open_win(vim.api.nvim_create_buf(true, false), false, { split = 'right' })
  t.forget()
  vim.api.nvim_win_close(win, true)
  local sent = t.sent('eclipse_status_lines')
  t.eq(1, #sent, 'one notification, at once')
  local entry = sent[1][1][1]
  t.eq({ win, { false } }, { entry[1], entry[2] })
  t.eq(package.loaded['neovim4eclipse.statusline'].stamp, entry[3], 'with a number of its own, the newest')
end)

t.test('a closed float sends nothing: it has no line', function()
  lines()
  local float = vim.api.nvim_open_win(vim.api.nvim_create_buf(false, true), false,
    { relative = 'editor', row = 1, col = 1, width = 5, height = 1 })
  t.forget()
  vim.api.nvim_win_close(float, true)
  t.eq(0, #t.sent('eclipse_status_lines'))
end)

-- Every tabpage shares Neovim's one screen, sized to the focused editor: a background editor's
-- window is that wide. Its row says how wide its editor is (set_width), and the line fits that.
t.test('a window that is not current is fitted to the width its editor reported', function()
  local M = lines()
  vim.o.laststatus = 2
  vim.o.statusline = '%=R' -- the ruler-like part at the right edge
  local other = vim.api.nvim_tabpage_get_win(vim.api.nvim_open_tabpage(vim.api.nvim_create_buf(true, false), false, {}))
  M.set_width(other, 20)
  local line = M.line(other)
  t.eq(20, #line[2], 'twenty cells, not the screen\'s width')
  t.eq('R', line[2][20], 'the right part at the editor\'s right edge')
  -- The current window too, before its editor reported, and after.
  local fresh = vim.api.nvim_open_win(vim.api.nvim_create_buf(true, false), true, { split = 'right' })
  t.eq(fresh, vim.api.nvim_get_current_win(), 'the new window is current')
  t.eq(vim.api.nvim_win_get_width(fresh), #M.line(fresh)[2], 'before its editor reported: the window\'s width')
  M.set_width(fresh, 7)
  t.eq(7, #M.line(fresh)[2], 'the current window, fitted to its editor')
  vim.api.nvim_win_close(fresh, true)
end)

-- Neovim draws %#Identifier# (a text color only) over the status line's background.
t.test('a group that sets no background takes the fill group\'s', function()
  local M = lines()
  vim.o.laststatus = 2
  vim.api.nvim_set_hl(0, 'StatusLine', { fg = 0x111111, bg = 0xEEEEEE })
  vim.api.nvim_set_hl(0, 'OnlyText', { fg = 0x00AA00 })
  vim.o.statusline = '%#OnlyText#x'
  local a = attr_of(M.line(vim.api.nvim_get_current_win()), 1)
  t.eq(0x00AA00, a[1], 'its own text color')
  t.eq(0xEEEEEE, a[2], 'StatusLine\'s background')
end)

-- A modeline's 'statusline' (only with 'modelineexpr' on) runs in Neovim's sandbox when Neovim
-- draws it; nvim_eval_statusline cannot tell, so the script draws the default line instead.
t.test('a statusline a modeline set is not evaluated', function()
  local M = lines()
  vim.o.laststatus = 2
  vim.o.modeline, vim.o.modelineexpr = true, true
  local path = vim.fn.tempname()
  -- setbufvar() returns 0 and leaves b:ran behind: a side effect the sandbox would refuse.
  vim.fn.writefile({ 'x', "# vim: stl=%{setbufvar('','ran',1)}MODELINE" }, path)
  vim.cmd('noswapfile edit ' .. vim.fn.fnameescape(path))
  t.eq('%{setbufvar(\'\',\'ran\',1)}MODELINE', vim.wo.statusline, 'the modeline did set it')
  local text = table.concat(M.line(vim.api.nvim_get_current_win())[2])
  t.ok(not text:find('MODELINE'), text)
  t.eq(nil, vim.b.ran, 'the expression did not run')
  vim.cmd('split') -- a new window copies the option, and where it came from with it
  t.ok(not table.concat(M.line(vim.api.nvim_get_current_win())[2]):find('MODELINE'), 'nor in a split')
  t.eq(nil, vim.b.ran, 'nor there')
  vim.cmd('only')
  vim.o.modelineexpr = false
  vim.cmd('bwipeout!')
end)
