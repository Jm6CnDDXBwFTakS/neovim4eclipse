-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- LuaScripts.MOUSE: which screen cell a click on a document position becomes. The script hands the
-- cell to nvim_input_mouse, whose event Neovim reads from its input queue, and a headless test
-- Neovim never reads that queue while the test runs (nvim_feedkeys works on the typeahead, not on
-- it). So nvim_input_mouse is replaced by a recorder here, and the checks are on the cell it gets;
-- what Neovim does with a cell is Neovim's own mouse code.

local t = ...

--- Runs MOUSE as the session sends it (every argument as text) and returns the recorded
--- nvim_input_mouse calls, each { button, action, mods, grid, row, col }.
local function click(action, lnum, col)
  local calls = {}
  vim.api.nvim_input_mouse = function(...)
    calls[#calls + 1] = { ... }
  end
  t.run('MOUSE', 'left', action, '', tostring(lnum), tostring(col))
  return calls
end

local function lines(text)
  vim.o.mouse = 'a'
  vim.api.nvim_buf_set_lines(0, 0, -1, false, text)
  vim.cmd('redraw')
end

t.test('a click on a character goes to its first cell', function()
  lines({ 'ab\tc', 'x中y' })
  t.eq({ { 'left', 'press', '', 0, 0, 1 } }, click('press', 1, 1), 'b')
  t.eq({ { 'left', 'press', '', 0, 0, 8 } }, click('press', 1, 3), 'c, after the Tab (cells 2 to 7)')
  t.eq({ { 'left', 'press', '', 0, 1, 1 } }, click('press', 2, 1), '中, the first of its two cells')
  t.eq({ { 'left', 'press', '', 0, 1, 3 } }, click('press', 2, 4), 'y, after 中 (bytes 1 to 3)')
end)

t.test('a click past the end of a line goes to the cell after all of the last character', function()
  -- Cell 3 (the Tab's second cell) or 2 (中's second) would put the cursor before the last
  -- character in insert mode, and leave it out of a visual drag.
  lines({ 'ab\t', 'x中', 'plain', '' })
  t.eq({ { 'left', 'press', '', 0, 0, 8 } }, click('press', 1, 3), 'after a Tab (cells 2 to 7)')
  t.eq({ { 'left', 'drag', '', 0, 1, 3 } }, click('drag', 2, 4), 'after 中 (cells 1 and 2)')
  t.eq({ { 'left', 'press', '', 0, 2, 5 } }, click('press', 3, 5), 'after plain')
  t.eq({ { 'left', 'press', '', 0, 3, 0 } }, click('press', 4, 0), 'an empty line: its first cell')
end)

t.test('past the end of a line that fills the window, the click stays in the window', function()
  vim.o.mouse = 'a'
  vim.cmd('vsplit')
  vim.wo.wrap = false
  local width = vim.api.nvim_win_get_width(0)
  vim.api.nvim_buf_set_lines(0, 0, -1, false, { string.rep('x', width) })
  vim.cmd('redraw')
  local calls = click('press', 1, width)
  t.eq(width - 1, calls[1][6], "the window's last column, not the separator or the window beside")
end)
