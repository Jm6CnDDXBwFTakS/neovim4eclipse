-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- LuaScripts.HOST_COMMANDS: which : lines Eclipse carries out. Neovim parses the line
-- (nvim_parse_cmd), CmdlineLeavePre swaps it for ":Eclipse <name> <args>", and that command sends
-- eclipse_command (name, bang, args) to Eclipse. What Eclipse then does with it is HostCommandTest
-- and HostCommandRunnerTest (tests/unit, no Neovim). See harness.lua for how these tests run.

local t = ...

local function start()
  t.setup()
  t.run('HOST_COMMANDS', t.host_rules)
end

t.test('a typed line, a mapping, a macro and @: all reach Eclipse, parsed by Neovim', function()
  start()
  vim.cmd('nnoremap <Space>q :q<CR>')
  vim.fn.setreg('a', ':tabn 3\r')

  t.type(':wa<CR>') -- an abbreviation: Neovim expands it to wall
  t.eq({ { 'wall', false, {} } }, t.sent('eclipse_command'))
  t.eq('wa', vim.fn.histget(':', -1), 'the history keeps the line as typed, not the :Eclipse one')

  t.type('<Space>q')
  t.type('@a')
  t.type('@:') -- repeats the last typed line, :wa
  t.type(':q!<CR>')
  t.eq({
    { 'wall', false, {} },
    { 'quit', false, {} },
    { 'tabnext', false, { '3' } },
    { 'wall', false, {} },
    { 'quit', true, {} },
  }, t.sent('eclipse_command'))
end)

t.test('arguments are passed as typed, a count becomes the argument, and | goes on', function()
  start()
  t.type(':e my\\ file.txt<CR>')
  t.type(':2tabn<CR>')
  t.type(':Eclipse! quit | let g:after = 1<CR>')
  t.eq({
    { 'edit', false, { 'my file.txt' } }, -- the backslash keeps it one argument; :Eclipse's fargs drop it
    { 'tabnext', false, { '2' } },
    { 'quit', true, {} },
  }, t.sent('eclipse_command'))
  t.eq(1, vim.g.after, 'the command after | ran')
end)

t.test('a line Eclipse would refuse stays Neovim\'s', function()
  start()
  vim.cmd('vsplit') -- if :q foo were run, it would close a window, not end the test's Neovim
  t.type(':q foo<CR>') -- :q takes no argument: E488
  t.ok(vim.v.errmsg:find('E488', 1, true), 'Neovim reported it: ' .. vim.v.errmsg)
  t.type(':1,2tabn<CR>') -- a range where only a count fits
  t.type(':Eclipse nope<CR>')
  t.ok(vim.v.errmsg:find('E492: Not an Eclipse command: nope', 1, true), vim.v.errmsg)
  t.eq({}, t.sent('eclipse_command'))
  t.eq(2, #vim.api.nvim_list_wins(), 'nothing was closed')
end)

t.test(':bd with a range or numbers names the listed buffers it covers', function()
  start()
  local one = vim.api.nvim_get_current_buf()
  vim.cmd('badd two | badd three')
  local three = vim.fn.bufnr('three')
  t.type(':%bd<CR>')
  t.type((':%dbd<CR>'):format(three))
  t.type(':bd two<CR>')
  t.eq({
    { 'bdelete', false, { tostring(one), tostring(vim.fn.bufnr('two')), tostring(three) } },
    { 'bdelete', false, { tostring(three) } },
    { 'bdelete', false, { 'two' } },
  }, t.sent('eclipse_command'))
  t.eq(3, #vim.fn.getbufinfo({ buflisted = 1 }), 'Neovim deleted none of them: Eclipse closes the editors')
end)

t.test(':bd of a buffer number nobody has stays Neovim\'s, which says so', function()
  start()
  t.type(':99bd<CR>') -- a number after :bd is a range: no buffer in it
  t.eq({}, t.sent('eclipse_command'))
  t.ok(vim.v.errmsg:find('E16', 1, true), vim.v.errmsg) -- Invalid range
end)

t.test('a :quit Neovim runs itself (<Cmd>q) tells Eclipse through the QuitPre safety net', function()
  start()
  vim.cmd('vsplit') -- two windows, so the quit closes one instead of ending the test's Neovim
  local buf = vim.api.nvim_get_current_buf()
  vim.cmd('nnoremap <Space>q <Cmd>q<CR>')
  t.type('<Space>q')
  t.eq({}, t.sent('eclipse_command'), '<Cmd> lines never reach CmdlineLeavePre')
  t.eq({ { buf } }, t.sent('eclipse_buffer_closed'))
end)

-- Not here: the command-line window (q:) closed by SETUP's CmdwinEnter. t.type ends a pending mode
-- by itself when the keys run out (see harness.lua), so the window would close either way;
-- NeovimSessionIntegrationTest.theCommandLineWindowClosesWithAMessage checks it with keys sent one
-- at a time.
