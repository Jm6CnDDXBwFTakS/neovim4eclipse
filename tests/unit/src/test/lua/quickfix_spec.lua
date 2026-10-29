-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- LuaScripts.QUICKFIX: the quickfix lists of Eclipse's problems and tasks (docs/design/DIAGNOSTICS.md,
-- section 5): the commands :Eproblems and :Etasks, the list Eclipse sends back (SET_QUICKFIX), which
-- list is ours (QUICKFIX_CURRENT), and :copen through the WINDOWS safety net. Eclipse's side (which
-- markers, the order, the flat arguments) is QuickfixEntriesTest; reading the workspace and keeping
-- the list current is the UI test QuickfixEndToEndTest.

local t = ...

--- SETUP, PROJECT_FILES and QUICKFIX, as the session runs them, and three files on disk.
local function start()
  t.setup()
  t.run('PROJECT_FILES')
  t.run('QUICKFIX')
  local dir = vim.fn.tempname()
  vim.fn.mkdir(dir, 'p')
  local files = {}
  for i = 1, 3 do
    files[i] = dir .. '/f' .. i .. '.txt'
    vim.fn.writefile({ 'line 1', '    line 2 of f' .. i, 'line 3', '  line 4' }, files[i])
  end
  t.forget()
  return files
end

--- The seven strings of one entry (util/QuickfixEntries.toLuaArgs).
local function entry(file, lnum, col, type, text, bufnr)
  return { tostring(bufnr or 0), file, vim.fn.fnamemodify(file, ':t'), tostring(lnum), tostring(col), type, text }
end

--- Runs SET_QUICKFIX with the head and the entries.
local function set(kind, arg, mode, jump, entries)
  local args = { kind, arg, mode, jump and '1' or '0' }
  for _, e in ipairs(entries) do
    vim.list_extend(args, e)
  end
  t.run('SET_QUICKFIX', unpack(args))
end

local function last_message()
  local lines = vim.split(vim.fn.execute('messages'), '\n', { trimempty = true })
  return lines[#lines] or ''
end

local function current()
  return vim.fn.getqflist({ nr = 0, idx = 0, title = 0, context = 0, size = 0 })
end

t.test(':Eproblems asks Eclipse, with the severity and the bang', function()
  start()
  vim.cmd('Eproblems')
  vim.cmd('Eproblems! warning')
  vim.cmd('Eproblems error')
  t.eq({ { 'problems', '', false }, { 'problems', 'warning', true }, { 'problems', 'error', false } },
    t.sent('eclipse_quickfix'))
end)

t.test(':Eproblems refuses a severity Eclipse has not, and completes the three', function()
  start()
  vim.cmd('Eproblems hint')
  t.eq({}, t.sent('eclipse_quickfix'))
  t.ok(last_message():find('E475: Invalid argument: hint', 1, true), last_message())
  t.eq({ 'warning' }, vim.fn.getcompletion('Eproblems w', 'cmdline'))
  t.eq({ 'error', 'warning', 'info' }, vim.fn.getcompletion('Eproblems ', 'cmdline'))
end)

t.test(':Etasks sends the tags of vim.g.neovim4eclipse_task_tags, read as it runs', function()
  start()
  vim.g.neovim4eclipse_task_tags = 'JM6, FIXME'
  vim.cmd('Etasks')
  vim.g.neovim4eclipse_task_tags = { 'TODO', 'XXX' }
  vim.cmd('Etasks!')
  t.eq({ { 'tasks', 'JM6, FIXME', false }, { 'tasks', 'TODO,XXX', true } }, t.sent('eclipse_quickfix'))
end)

t.test(':Etasks without tags says how to set them and asks Eclipse nothing', function()
  start()
  for _, value in ipairs({ vim.NIL, '', ' , ', {} }) do
    vim.g.neovim4eclipse_task_tags = value
    vim.cmd('Etasks')
  end
  t.eq({}, t.sent('eclipse_quickfix'))
  t.ok(last_message():find('vim.g.neovim4eclipse_task_tags', 1, true), last_message())
end)

t.test('a command makes the list, titled and marked as ours, and jumps to the first entry', function()
  local f = start()
  set('problems', '', 'command', true, { entry(f[2], 2, 0, 'E', 'bad'), entry(f[1], 4, 3, 'W', 'meh') })
  local list = current()
  t.eq('Eclipse problems', list.title)
  t.eq({ neovim4eclipse = 'problems', arg = '' }, list.context)
  t.eq(1, list.idx)
  t.eq(f[2], vim.api.nvim_buf_get_name(0), 'the first entry, as :make does')
  t.eq({ 2, 4 }, vim.api.nvim_win_get_cursor(0), 'column 0: the first non-blank')
  t.eq('(1 of 2) error: bad', last_message(), "Neovim's own message for the entry")
  t.eq('f2.txt', vim.fn.getqflist()[1].module, ':clist shows the workspace path')
  t.eq({ 'problems', '' }, t.run('QUICKFIX_CURRENT'))
end)

t.test('with ! the list is made without a jump, and the count is shown', function()
  local f = start()
  local before = vim.api.nvim_get_current_buf()
  set('problems', 'warning', 'command', false,
    { entry(f[1], 1, 0, 'E', 'a'), entry(f[1], 2, 0, 'E', 'b'), entry(f[2], 3, 0, 'W', 'c') })
  t.eq(before, vim.api.nvim_get_current_buf())
  t.eq('Eclipse problems (warning): 3 (2 errors, 1 warning)', last_message())
  t.eq('Eclipse problems (warning)', current().title)
  set('tasks', 'JM6', 'command', true, {})
  t.eq('Eclipse tasks (JM6): none', last_message(), 'nothing to jump to')
end)

t.test('a buffer number that exists names the entry; one that does not falls back to the file', function()
  local f = start()
  vim.cmd.edit(f[1])
  local buf = vim.api.nvim_get_current_buf()
  set('problems', '', 'command', false, { entry(f[1], 2, 5, 'E', 'x', buf), entry(f[2], 1, 0, 'E', 'y', 999) })
  local items = vim.fn.getqflist()
  t.eq(buf, items[1].bufnr)
  t.eq(f[2], vim.api.nvim_buf_get_name(items[2].bufnr))
  t.eq(false, vim.bo[items[2].bufnr].buflisted, 'an unlisted buffer, as Neovim makes for any entry')
  t.ok(require('neovim4eclipse.project_files') ~= nil)
end)

t.test('a command again reuses our list in the stack and makes it current, rather than push a copy', function()
  local f = start()
  set('problems', '', 'command', false, { entry(f[1], 1, 0, 'E', 'a') })
  vim.fn.setqflist({}, ' ', { title = 'vimgrep', items = { { filename = f[3], lnum = 1, text = 'grep' } } })
  set('tasks', 'JM6', 'command', false, { entry(f[2], 1, 0, '', 'JM6 t') })
  t.eq(3, vim.fn.getqflist({ nr = '$' }).nr)
  set('problems', 'error', 'command', false, { entry(f[1], 2, 0, 'E', 'b') })
  t.eq(3, vim.fn.getqflist({ nr = '$' }).nr, 'no fourth list')
  local list = current()
  t.eq(1, list.nr, 'the problems list is current again')
  t.eq('Eclipse problems (error)', list.title)
  t.eq({ neovim4eclipse = 'problems', arg = 'error' }, list.context)
  t.eq('b', vim.fn.getqflist()[1].text)
end)

t.test('an update keeps the entry the user is on', function()
  local f = start()
  local a, b, c = entry(f[1], 1, 0, 'E', 'a'), entry(f[2], 2, 0, 'E', 'b'), entry(f[3], 3, 0, 'E', 'c')
  set('problems', '', 'command', false, { a, b, c })
  vim.cmd('silent cc 2')
  set('problems', '', 'update', false, { entry(f[1], 1, 0, 'W', 'new'), a, b, c })
  t.eq(3, current().idx, 'still on b')
  t.eq(f[2], vim.api.nvim_buf_get_name(0), 'no jump')
  set('problems', '', 'update', false, { a, c })
  t.eq(2, current().size, 'b fixed: gone from the list')
end)

t.test('an update leaves a list of the user\'s alone, and ours with another severity', function()
  local f = start()
  set('problems', 'error', 'command', false, { entry(f[1], 1, 0, 'E', 'a') })
  set('problems', '', 'update', false, { entry(f[2], 1, 0, 'W', 'all') })
  t.eq('a', vim.fn.getqflist()[1].text, ':Eproblems error is not :Eproblems')
  vim.fn.setqflist({}, ' ', { title = 'Eclipse problems', items = { { filename = f[3], lnum = 1, text = 'mine' } } })
  set('problems', 'error', 'update', false, { entry(f[2], 1, 0, 'E', 'eclipse') })
  t.eq('mine', vim.fn.getqflist()[1].text, 'the same title, but not our context')
  t.eq(vim.NIL, t.run('QUICKFIX_CURRENT') or vim.NIL)
end)

t.test('the jump that opened a file moves on to the column once the editor gives it', function()
  local f = start()
  set('problems', '', 'command', true, { entry(f[1], 2, 0, 'E', 'x'), entry(f[2], 3, 0, 'E', 'y') })
  t.eq({ 2, 4 }, vim.api.nvim_win_get_cursor(0))
  local buf = vim.api.nvim_get_current_buf()
  set('problems', '', 'update', false, { entry(f[1], 2, 10, 'E', 'x', buf), entry(f[2], 3, 0, 'E', 'y') })
  t.eq({ 2, 9 }, vim.api.nvim_win_get_cursor(0), 'byte column 10 is cursor column 9')
  -- Once the entry has its column, a later update moves nothing.
  vim.api.nvim_win_set_cursor(0, { 2, 4 })
  set('problems', '', 'update', false, { entry(f[1], 2, 12, 'E', 'x', buf), entry(f[2], 3, 0, 'E', 'y') })
  t.eq({ 2, 4 }, vim.api.nvim_win_get_cursor(0))
end)

t.test('a cursor the user moved after the jump stays where it is', function()
  local f = start()
  set('problems', '', 'command', true, { entry(f[1], 2, 0, 'E', 'x') })
  local buf = vim.api.nvim_get_current_buf()
  vim.api.nvim_win_set_cursor(0, { 3, 2 })
  set('problems', '', 'update', false, { entry(f[1], 2, 10, 'E', 'x', buf) })
  t.eq({ 3, 2 }, vim.api.nvim_win_get_cursor(0))
end)

t.test(':copen on our list asks Eclipse for its view; on another list it shows :clist', function()
  local f = start()
  local home_buf = vim.api.nvim_create_buf(false, true)
  vim.api.nvim_set_current_buf(home_buf)
  t.run('WINDOWS', tostring(home_buf))
  set('tasks', 'JM6', 'command', false, { entry(f[1], 1, 0, '', 'JM6 later') })
  t.forget()
  vim.cmd.copen()
  t.wait_for('the view request', function() return #t.sent('eclipse_show_view') == 1 end)
  t.eq({ { 'tasks' } }, t.sent('eclipse_show_view'))
  t.eq(1, #vim.api.nvim_tabpage_list_wins(0), 'the window is closed, as before')
  t.ok(not vim.v.errmsg:find('cannot show the quickfix window', 1, true), 'no message: Eclipse shows it')

  vim.fn.setqflist({}, ' ', { title = 'vimgrep', items = { { filename = f[2], lnum = 3, text = 'found it' } } })
  vim.cmd.cwindow()
  t.wait_for('the window to close', function() return #vim.api.nvim_tabpage_list_wins(0) == 1 end)
  t.ok(vim.fn.execute('messages'):find('f2.txt:3: found it', 1, true), vim.fn.execute('messages'))
  t.eq(1, #t.sent('eclipse_show_view'), 'nothing more asked of Eclipse')
end)

t.test(':lopen shows the location list as :llist', function()
  local f = start()
  local home_buf = vim.api.nvim_create_buf(false, true)
  vim.api.nvim_set_current_buf(home_buf)
  t.run('WINDOWS', tostring(home_buf))
  vim.fn.setloclist(0, {}, ' ', { title = 'loc', items = { { filename = f[3], lnum = 2, text = 'local one' } } })
  vim.cmd.lopen()
  t.wait_for('the window to close', function() return #vim.api.nvim_tabpage_list_wins(0) == 1 end)
  t.ok(vim.fn.execute('messages'):find('f3.txt:2: local one', 1, true), vim.fn.execute('messages'))
  t.eq({}, t.sent('eclipse_show_view'))
end)
