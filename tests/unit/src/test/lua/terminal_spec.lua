-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- :terminal (HostCommand.Kind.TERMINAL, EclipseTerminal): a typed :terminal is handed to Eclipse
-- before Neovim runs it, so no program starts in Neovim; a terminal started any other way runs on
-- and LuaScripts.TERMINALS names it, unless it is in a plug-in's floating window that is shown,
-- which the editor draws. What Eclipse does with :Eclipse terminal is
-- EclipseTerminalTest and HostCommandRunnerTest. See harness.lua for how these tests run.

local t = ...

local function start()
  t.setup()
  -- As the session sends them: the windows module, whose test for a plug-in's float TERMINALS uses.
  local home_buf = vim.api.nvim_create_buf(false, true)
  vim.api.nvim_set_current_buf(home_buf)
  t.run('WINDOWS', tostring(home_buf))
  t.run('HOST_COMMANDS', t.host_rules)
  t.run('TERMINALS')
end

--- A program that ends at once, for a terminal of its own.
local function quick()
  return { vim.v.progpath, '--clean', '--headless', '-c', 'qa!' }
end

--- The terminal buffers there are.
local function terminals()
  return vim.tbl_filter(function(b)
    return vim.api.nvim_buf_is_valid(b) and vim.bo[b].buftype == 'terminal'
  end, vim.api.nvim_list_bufs())
end

--- The messages since the test began, as :messages shows them.
local function messages()
  return vim.api.nvim_exec2('messages', { output = true }).output
end

t.test('a typed :terminal starts nothing in Neovim and reaches Eclipse, in every spelling', function()
  start()
  local marker = vim.fn.tempname()
  t.type(':terminal touch ' .. marker .. '<CR>')
  t.type(':te<CR>')
  t.type(':vert term<CR>')
  t.type(':tab ter ++close ls<CR>')
  t.type(':echo 1 | terminal ls<CR>')
  t.type(':terminal make | tee log<CR>') -- | is part of :terminal's command
  t.type('i<C-O>:terminal<CR><Esc>') -- from insert mode, for one command
  vim.cmd('normal :terminal ls\r') -- :normal types the line too
  t.eq({
    { 'terminal', false, { 'touch', marker } },
    { 'terminal', false, {} },
    { 'terminal', false, {} },
    { 'terminal', false, { '++close', 'ls' } },
    { 'terminal', false, { 'ls' } },
    { 'terminal', false, { 'make', '|', 'tee', 'log' } },
    { 'terminal', false, {} },
    { 'terminal', false, { 'ls' } },
  }, t.commands())
  t.eq({}, terminals(), 'no terminal was started')
  t.eq(nil, vim.uv.fs_stat(marker), 'its command did not run')
  local kept = false
  for i = 1, vim.fn.histnr(':') do
    kept = kept or vim.fn.histget(':', i) == 'terminal touch ' .. marker
  end
  t.ok(kept, 'the history keeps the line as typed, not the :Eclipse one')
end)

t.test('a terminal started another way runs on, and the message names it', function()
  start()
  vim.cmd('enew') -- jobstart(term) needs an unmodified buffer to turn into the terminal
  local marker = vim.fn.tempname()
  -- A plug-in's terminal: started by Lua, not on the : line. Its program must not be stopped.
  vim.fn.jobstart({ vim.v.progpath, '--clean', '--headless', '-c', 'call writefile([], "' .. marker:gsub('\\', '/') .. '") | qa!' },
    { term = true })
  t.wait_for('the program to finish its work', function()
    return vim.uv.fs_stat(marker) ~= nil
  end)
  t.eq({}, t.commands(), 'not handed to Eclipse')
  t.ok(messages():find('is running hidden: Eclipse cannot show a Neovim terminal. It stops when it exits or Neovim does.', 1, true),
    'the message: ' .. messages())
  -- A list is named by its program alone, as a full path (its form is the system's: check the name only).
  t.ok(messages():find(vim.fn.fnamemodify(vim.v.progpath, ':t:r'), 1, true), 'it names the program: ' .. messages())
end)

t.test('a terminal that runs no program (nvim_open_term) gets no message', function()
  start()
  local buf = vim.api.nvim_create_buf(false, true)
  vim.api.nvim_open_term(buf, {})
  t.eq(nil, messages():find('running hidden', 1, true), 'no message: ' .. messages())
end)

t.test('a terminal in a floating window gets no message: the editor draws it', function()
  start()
  -- As fzf-lua opens its list: a float on a new buffer, then the program started in it.
  local win = vim.api.nvim_open_win(vim.api.nvim_create_buf(false, true), true,
    { relative = 'editor', row = 1, col = 1, width = 40, height = 5 })
  vim.fn.jobstart(quick(), { term = true })
  t.eq(1, #terminals(), 'Neovim started it')
  t.eq(nil, messages():find('running hidden', 1, true), 'no message: ' .. messages())
  vim.api.nvim_win_close(win, true)
end)

t.test('a terminal started in a hidden buffer through nvim_buf_call gets the message', function()
  start()
  -- Neovim runs the call in its autocommand window, a float of type 'autocmd' that closes after.
  local buf = vim.api.nvim_create_buf(false, true)
  vim.api.nvim_buf_call(buf, function()
    vim.fn.jobstart(quick(), { term = true })
  end)
  t.eq(1, #terminals(), 'Neovim started it')
  t.ok(messages():find('running hidden', 1, true), 'the message: ' .. messages())
end)

t.test('a terminal in a hidden float gets the message: nothing draws it', function()
  start()
  local win = vim.api.nvim_open_win(vim.api.nvim_create_buf(false, true), false,
    { relative = 'editor', row = 1, col = 1, width = 40, height = 5, hide = true })
  vim.api.nvim_win_call(win, function()
    vim.fn.jobstart(quick(), { term = true })
  end)
  t.eq(1, #terminals(), 'Neovim started it')
  t.ok(messages():find('running hidden', 1, true), 'the message: ' .. messages())
  vim.api.nvim_win_close(win, true)
end)

t.test('a <Cmd> mapping is not a : line: its terminal starts, with the message', function()
  start()
  vim.cmd('enew')
  vim.keymap.set('n', '<Space>t', '<Cmd>terminal ' .. vim.fn.shellescape(vim.v.progpath) .. ' --version<CR>')
  t.type('<Space>t')
  t.eq(1, #terminals(), 'Neovim started it')
  t.eq({}, t.commands())
  t.ok(messages():find('--version" is running hidden', 1, true), 'named with its command: ' .. messages())
end)
