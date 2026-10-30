-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- LuaScripts.STATE_SNAPSHOT: everything the editor needs after a key, in one answer. Here the
-- blockwise selection, which Neovim works out with getregionpos() (tabs, wide characters, $). How
-- the Java side reads the answer is StateSnapshotTest and BlockSelectionTest; one round trip
-- through the session stays in NeovimSessionIntegrationTest (blockwiseSelectionIsReportedWithBothCorners).

local t = ...

-- The answer's fields, by position (see the STATE_SNAPSHOT comment).
local BUFNR, BLOCK, FULL_MODE, WINID = 10, 11, 12, 14

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

t.test('the answer ends with the current buffer and window, to tell the attached editor from another', function()
  local snap = snapshot_after({ 'abc' }, 'l')
  t.eq(vim.api.nvim_get_current_buf(), snap[BUFNR])
  t.eq(vim.api.nvim_get_current_win(), snap[WINID], 'docs/design/WINDOWS.md')
  t.eq(WINID, #snap, 'the last field')
end)

-- The first argument is the status bar's statusline width: Neovim fits the 'statusline' into it
-- (%= pads the default format's middle to exactly that many columns), and 0 evaluates nothing.
t.test('the statusline is evaluated at the status bar\'s width, or not at all', function()
  local STATUSLINE = 9
  vim.api.nvim_buf_set_lines(0, 0, -1, false, { 'one' })
  t.eq(30, vim.fn.strdisplaywidth(t.run('STATE_SNAPSHOT', '30')[STATUSLINE]), 'filled to 30 columns')
  t.eq(12, vim.fn.strdisplaywidth(t.run('STATE_SNAPSHOT', '12')[STATUSLINE]), 'and cut to 12')
  t.eq('', t.run('STATE_SNAPSHOT', '0')[STATUSLINE], 'not shown: not evaluated')
end)
