-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- LuaScripts.CTRL_X_GUARD: insert mode's Ctrl-X Ctrl-V, Ctrl-X Ctrl-Q and Ctrl-X Ctrl-O are
-- refused in Neovim itself, whoever sends the keys. Ctrl-X Ctrl-V completes the line as a Vim
-- command, and that expands `...` by running it in the shell: text of a file would run.

local t = ...

local function keys(text) -- as t.type
  vim.api.nvim_feedkeys(vim.api.nvim_replace_termcodes(text, true, false, true), 'tx', false)
end

--- A buffer whose first line asks the shell to create `marker` when completed as a command.
local function trap()
  local marker = vim.fn.tempname()
  vim.api.nvim_buf_set_lines(0, 0, -1, false, { 'e `touch ' .. marker .. '`', 'alpha beta', 'al' })
  vim.api.nvim_win_set_cursor(0, { 1, 0 })
  return marker
end

local function ran(marker)
  return vim.uv.fs_stat(marker) ~= nil
end

t.test('without the guard Ctrl-X Ctrl-V runs the command in the line (what the guard is for)', function()
  if vim.fn.has('win32') == 1 or vim.fn.executable('touch') == 0 then
    t.skip('needs a shell with touch; the tests after this one need none, they check that nothing ran')
  end
  local marker = trap()
  keys('A<C-x><C-v><Esc>')
  t.ok(ran(marker), 'this Neovim expands backticks in command-line completion')
  vim.fn.delete(marker)
end)

t.test('Ctrl-X Ctrl-V and Ctrl-X Ctrl-Q are dropped and nothing runs', function()
  t.run('CTRL_X_GUARD')
  local marker = trap()
  keys('A<C-x><C-v><Esc>')
  keys('A<C-x><C-q><Esc>')
  t.ok(not ran(marker), 'the command did not run')
  t.eq('e `touch ' .. marker .. '`', vim.api.nvim_buf_get_lines(0, 0, 1, false)[1], 'the line is unchanged')
  t.eq('n', vim.api.nvim_get_mode().mode)
end)

t.test('also after a completion has started, in replace mode, and from a macro', function()
  t.run('CTRL_X_GUARD')
  local marker = trap()
  keys('A<C-n><C-x><C-v><Esc>')
  keys('$R<C-x><C-v><Esc>')
  vim.fn.setreg('q', vim.api.nvim_replace_termcodes('ggA<C-x><C-v><Esc>', true, false, true))
  vim.cmd('normal @q')
  t.ok(not ran(marker), 'the command did not run')
end)

t.test('Ctrl-V with Shift or another modifier is refused too', function()
  t.run('CTRL_X_GUARD')
  local marker = trap()
  for _, key in ipairs({ '<C-S-v>', '<S-C-V>', '<C-S-q>', '<D-C-v>', '<C-S-D-v>' }) do
    keys('A<C-x>' .. key .. '<Esc>')
    t.ok(not ran(marker), key .. ' did not run the command')
  end
end)

t.test('in the command-line window the command is still completed (Neovim maps Tab to Ctrl-X Ctrl-V there)', function()
  t.run('CTRL_X_GUARD')
  local seen = t.during('q:iechoms<C-x><C-v>', function()
    return { vim.fn.getcmdwintype(), vim.api.nvim_get_current_line() }
  end)
  if vim.fn.getcmdwintype() ~= '' then -- still open: close it, not Neovim
    pcall(vim.cmd, 'stopinsert')
    pcall(vim.cmd, 'quit')
  end
  t.eq({ ':', 'echomsg' }, seen)
end)

t.test('in the command-line window of searches it is refused: a pattern is no command', function()
  t.run('CTRL_X_GUARD')
  local seen = t.during('q/iechoms<C-x><C-v>', function()
    return { vim.fn.getcmdwintype(), vim.api.nvim_get_current_line() }
  end)
  if vim.fn.getcmdwintype() ~= '' then
    pcall(vim.cmd, 'stopinsert')
    pcall(vim.cmd, 'quit')
  end
  t.eq({ '/', 'echoms' }, seen)
end)

t.test('the refused key ends the Ctrl-X mode: the next key is typed as usual', function()
  t.run('CTRL_X_GUARD')
  trap()
  vim.api.nvim_win_set_cursor(0, { 3, 0 })
  keys('A<C-x><C-v>x<Esc>')
  t.eq('alx', vim.api.nvim_buf_get_lines(0, 2, 3, false)[1])
end)

t.test('Ctrl-X Ctrl-O does not call omnifunc', function()
  t.run('CTRL_X_GUARD')
  trap()
  _G.omni_calls = 0
  function _G.count_omni(findstart)
    _G.omni_calls = _G.omni_calls + 1
    return findstart == 1 and 0 or {}
  end
  vim.bo.omnifunc = 'v:lua.count_omni'
  keys('A<C-x><C-o><Esc>')
  t.eq(0, _G.omni_calls)
end)

t.test('the other Ctrl-X completions still work, and Ctrl-V, Ctrl-Q, Ctrl-O outside Ctrl-X', function()
  t.run('CTRL_X_GUARD')
  trap()
  vim.api.nvim_win_set_cursor(0, { 3, 0 })
  keys('A<C-x><C-l><C-y><Esc>')
  t.eq('alpha beta', vim.api.nvim_buf_get_lines(0, 2, 3, false)[1], 'whole-line completion')
  keys('A<C-v>-<Esc>') -- not a letter Ctrl-V reads as the start of a character code (x, u, o)
  t.eq('alpha beta-', vim.api.nvim_buf_get_lines(0, 2, 3, false)[1], 'Ctrl-V inserts the next key literally')
  keys('A<C-o>0X<Esc>')
  t.eq('Xalpha beta-', vim.api.nvim_buf_get_lines(0, 2, 3, false)[1], 'Ctrl-O runs one normal-mode command')
end)
