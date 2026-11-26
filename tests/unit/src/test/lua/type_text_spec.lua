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

-- A pasted line that looks like a command, with its line
-- break, is re-typed at Esc (InsertReplay turns the break into \r, the Enter key) and stays text, and
-- dot repeats it as text. A ":lua" line stands in for ":!echo hi": it is just as much a
-- command, and its effect can be checked (vim.g.n4e_ran stays unset).
t.test('a pasted command line and its line break stay text, and dot repeats them as text', function()
  t.setup()
  vim.g.n4e_ran = nil
  vim.api.nvim_buf_set_lines(0, 0, -1, false, { 'keep' })
  vim.api.nvim_win_set_cursor(0, { 1, 0 })
  local typed = t.during('o', function() return t.run('TYPE_TEXT', ':lua vim.g.n4e_ran = 1\rx') end)
  t.eq(true, typed)
  t.type('.')
  t.eq({ 'keep', ':lua vim.g.n4e_ran = 1', 'x', ':lua vim.g.n4e_ran = 1', 'x' }, lines(),
    'the text, twice: once re-typed, once by the dot')
  t.eq(nil, vim.g.n4e_ran, 'nothing ran')
end)

-- LuaScripts.INSERT_KEY_MAPPED: asked right before a completion key goes to Neovim; a mapped one
-- (a completion plug-in's Ctrl-N) leaves the rest of the insert to Neovim (KeyInput.mappedInInsert).
t.test('a mapping that takes a completion key is told apart from none: global, buffer-local, Lua, empty, prefix', function()
  t.setup()
  vim.keymap.set('n', '<C-n>', 'j')
  t.eq(false, t.run('INSERT_KEY_MAPPED', '<C-n>'), 'a Normal-mode mapping is not one')
  vim.keymap.set('i', '<C-n>', function() end)
  t.eq(true, t.run('INSERT_KEY_MAPPED', '<C-n>'), 'a Lua callback, as nvim-cmp maps it')
  vim.keymap.del('i', '<C-n>')
  vim.keymap.set('i', '<C-p>', '<C-p>', { buffer = 0 })
  t.eq(true, t.run('INSERT_KEY_MAPPED', '<C-p>'), 'a buffer-local one, as blink.cmp maps it')
  vim.api.nvim_create_buf(true, false)
  vim.cmd('enew')
  t.eq(false, t.run('INSERT_KEY_MAPPED', '<C-p>'), 'not in another buffer')
  vim.keymap.set('i', '<C-x>', '', { noremap = true })
  t.eq(true, t.run('INSERT_KEY_MAPPED', '<C-x><C-l>'), 'a mapping to nothing of the start takes the key too')
  vim.keymap.del('i', '<C-x>')
  vim.keymap.set('i', '<C-x><C-o>x', 'y')
  t.eq(true, t.run('INSERT_KEY_MAPPED', '<C-x><C-o>'), 'a longer mapping the key starts')
  t.eq(false, t.run('INSERT_KEY_MAPPED', '<C-x><C-l>'), 'not a key that starts no mapping')
  vim.keymap.del('i', '<C-x><C-o>x')
  vim.keymap.del('n', '<C-n>')
end)

-- KeyInput asks INSERT_KEY_MAPPED only for the completion keys, since Neovim maps none of them
-- itself; it does map other insert Ctrl keys (Ctrl-W to <C-G>u<C-W>). This fails if a release
-- starts to map one by default: Eclipse would then stop typing after every plain Ctrl-N.
t.test("Neovim's own default mappings take no completion key", function()
  t.setup()
  t.eq('<C-G>u<C-W>', vim.fn.mapcheck('<C-w>', 'i'), 'the defaults are loaded here (--clean keeps them)')
  for _, key in ipairs({ '<C-n>', '<C-p>', '<C-x>' }) do
    t.eq(false, t.run('INSERT_KEY_MAPPED', key), key)
  end
end)
