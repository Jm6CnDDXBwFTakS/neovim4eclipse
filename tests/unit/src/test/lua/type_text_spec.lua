-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- LuaScripts.TYPE_TEXT: the re-typing of what Eclipse typed (core/KeyInput), only into an open
-- insert. Outside one the same characters would be Normal-mode commands: a pasted ":!cmd" would run.
-- What KeyInput does when it is refused is KeyInputTest.

local t = ...

local function lines()
  return vim.api.nvim_buf_get_lines(0, 0, -1, false)
end

t.test('in insert mode the text is typed as raw characters, < and all, with no mapping', function()
  t.setup()
  vim.keymap.set('i', 'jk', '<Esc>')
  local typed = t.during('i', function() return t.run('TYPE_TEXT', '<b>jk:x') end)
  vim.keymap.del('i', 'jk')
  t.eq(true, typed)
  t.eq({ '<b>jk:x' }, lines(), 'jk stays two letters, <b> is not key notation')
end)

t.test('in replace mode the text is typed too', function()
  t.setup()
  vim.api.nvim_buf_set_lines(0, 0, -1, false, { 'abcdef' })
  vim.api.nvim_win_set_cursor(0, { 1, 0 })
  t.eq(true, t.during('R', function() return t.run('TYPE_TEXT', 'XY') end))
  t.eq({ 'XYcdef' }, lines())
end)

t.test('in normal mode nothing is typed, so the text cannot run as commands', function()
  t.setup()
  vim.api.nvim_buf_set_lines(0, 0, -1, false, { 'keep' })
  t.eq(false, t.run('TYPE_TEXT', 'ddihello'))
  t.type('') -- runs whatever was queued: nothing
  t.eq({ 'keep' }, lines())
end)

t.test('after insert-mode Ctrl-O (niI) nothing is typed', function()
  t.setup()
  vim.api.nvim_buf_set_lines(0, 0, -1, false, { 'keep' })
  local result = t.during('A<C-o>', function()
    return { vim.api.nvim_get_mode().mode, t.run('TYPE_TEXT', 'dd') }
  end)
  t.eq({ 'niI', false }, result)
  t.eq({ 'keep' }, lines())
end)
