-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- LuaScripts.STATE_SNAPSHOT: everything the editor needs after a key, in one answer. Here the
-- blockwise selection, which Neovim works out with getregionpos() (tabs, wide characters, $). How
-- the Java side reads the answer is StateSnapshotTest and BlockSelectionTest; one round trip
-- through the session stays in NeovimSessionIntegrationTest (blockwiseSelectionIsReportedWithBothCorners).

local t = ...

-- The answer's fields, by position (see the STATE_SNAPSHOT comment).
local BUFNR, BLOCK, FULL_MODE, WINID, STAMP = 10, 11, 12, 14, 15

local function snapshot_after(lines, keys)
  vim.api.nvim_buf_set_lines(0, 0, -1, false, lines)
  return t.during(keys, function()
    return t.run('STATE_SNAPSHOT', '0')
  end)
end

t.test('a block gives each line\'s first and last byte, 1-based', function()
  local snap = snapshot_after({ 'abcd', 'efgh', 'ijkl' }, 'gg<C-v>jl')
  t.eq(string.char(22), snap[FULL_MODE], 'blockwise visual mode')
  t.eq({ 1, 1, 2, 2, 1, 2 }, snap[BLOCK], 'line, first byte, last byte per line')
end)

t.test('$ makes the block reach each line\'s end', function()
  local snap = snapshot_after({ 'abcd', 'ef', 'ijklmn' }, 'gg<C-v>jj$')
  t.eq({ 1, 1, 4, 2, 1, 2, 3, 1, 6 }, snap[BLOCK])
end)

t.test('a block over a tab and wide characters is counted in screen cells by Neovim', function()
  -- l onto あ (cells 1-2): the block is cells 0-2. Line 1: a and the tab (only partly inside);
  -- line 2: abc; line 3: a and あ (3 bytes).
  local snap = snapshot_after({ 'a\tbcdef', 'abcdefghij', 'aあいうb' }, 'gg<C-v>jjl')
  t.eq({ 1, 1, 2, 2, 1, 3, 3, 1, 4 }, snap[BLOCK])
end)

t.test('outside blockwise visual mode there is no block', function()
  local snap = snapshot_after({ 'abcd', 'efgh' }, 'ggvj')
  t.eq('v', snap[FULL_MODE])
  t.ok(snap[BLOCK] == nil or vim.tbl_isempty(snap[BLOCK]), vim.inspect(snap[BLOCK]))
end)

t.test('the answer holds the current buffer and window, to tell the attached editor from another', function()
  local snap = snapshot_after({ 'abc' }, 'l')
  t.eq(vim.api.nvim_get_current_buf(), snap[BUFNR])
  t.eq(vim.api.nvim_get_current_win(), snap[WINID], 'docs/design/WINDOWS.md')
  t.eq(STAMP, #snap, 'the status line\'s number is the last field')
end)

-- The first argument asks for the current window's status line (LuaScripts.STATUS_LINE, tested in
-- status_line_spec.lua): "1" adds it in slot 9, "0" leaves { false } there, nothing evaluated.
t.test('the current window\'s status line is added with "1", not with "0"', function()
  local STATUSLINE = 9
  t.run('STATUS_LINE')
  vim.o.laststatus = 2
  vim.api.nvim_buf_set_lines(0, 0, -1, false, { 'one' })
  local line = t.run('STATE_SNAPSHOT', '1')[STATUSLINE]
  t.eq(true, line[1], 'shown')
  t.eq(vim.api.nvim_win_get_width(0), #line[2], 'one cell per column of the window')
  t.eq({ false }, t.run('STATE_SNAPSHOT', '0')[STATUSLINE], 'not asked for: not evaluated')
end)

-- The line's number (status_line_spec.lua, "Which line is newer"): the evaluation's own, -1 when
-- no line was asked for.
t.test('the status line comes with its evaluation\'s number, -1 without a line', function()
  t.run('STATUS_LINE')
  local M = package.loaded['neovim4eclipse.statusline']
  local snap = t.run('STATE_SNAPSHOT', '1')
  t.eq(M.stamp, snap[STAMP], 'the number of the evaluation it holds')
  local later = t.run('STATE_SNAPSHOT', '1')
  t.ok(later[STAMP] > snap[STAMP], 'a later snapshot\'s line has a higher number')
  t.eq(-1, t.run('STATE_SNAPSHOT', '0')[STAMP], 'no line, no number')
end)
