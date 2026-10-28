-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- A Neovim window per Eclipse editor, each alone in its own tabpage (docs/design/WINDOWS.md):
-- LuaScripts.WINDOWS (the module and its WinClosed safety net) and the scripts that call it,
-- OPEN_WINDOW, SHOW_WINDOW, TRAVEL_WINDOW, CLOSE_WINDOW and RELEASE_BUFFER. What the session does
-- with the answers (which token holds which window, the attached window) is EditorWindowsTest and
-- NeovimWindowsIntegrationTest.

local t = ...

--- SETUP (the BufEnter and CursorMoved reports), then WINDOWS with a home buffer, as the session
--- does. Returns the home window and the home buffer.
local function start()
  t.setup()
  local home_buf = vim.api.nvim_create_buf(false, true)
  vim.api.nvim_set_current_buf(home_buf)
  local home = t.run('WINDOWS', tostring(home_buf))
  t.forget()
  return home, home_buf
end

--- A listed scratch buffer with `n` numbered lines, as a document's buffer.
local function buffer(n)
  local buf = vim.api.nvim_create_buf(true, true)
  local lines = {}
  for i = 1, n do
    lines[i] = 'line ' .. i
  end
  vim.api.nvim_buf_set_lines(buf, 0, -1, false, lines)
  return buf
end

local function open(buf, folder)
  return t.run('OPEN_WINDOW', tostring(buf), folder or '')
end

local function show(win, buf)
  return t.run('SHOW_WINDOW', tostring(win), tostring(buf))
end

local function cwd(win)
  return vim.api.nvim_win_call(win, function()
    return { vim.fn.getcwd(), vim.fn.haslocaldir() }
  end)
end

t.test('WINDOWS returns the current window as the home window and hides the tab line', function()
  t.setup()
  vim.o.showtabline = 2
  local win = vim.api.nvim_get_current_win()
  t.eq(win, t.run('WINDOWS', tostring(vim.api.nvim_get_current_buf())))
  t.eq(0, vim.o.showtabline, 'a tab line would take a row from the editor')
end)

t.test('a window is opened in its own tab without entering it, so Eclipse sees no switch', function()
  local home = start()
  local a = buffer(3)
  local win = open(a)
  t.eq(home, vim.api.nvim_get_current_win(), 'the current window stays')
  t.eq(2, #vim.api.nvim_list_tabpages())
  t.eq({ win }, vim.api.nvim_tabpage_list_wins(vim.api.nvim_win_get_tabpage(win)), 'alone in its tab')
  t.eq(a, vim.api.nvim_win_get_buf(win))
  t.eq({}, t.sent('eclipse_buf_enter'))
  t.eq({}, t.sent('eclipse_cursor'))
end)

t.test("a new window's jump history starts empty instead of copying the current window's", function()
  start()
  local a = buffer(30)
  vim.api.nvim_set_current_buf(a)
  t.type('10G20G')
  t.ok(#vim.fn.getjumplist()[1] > 0, 'the current window has jumps')
  local win = open(a)
  t.eq({}, vim.api.nvim_win_call(win, vim.fn.getjumplist)[1])
end)

t.test("the folder is set once with :lcd; without one the window keeps the folder it copied", function()
  start()
  local dir = vim.fn.fnamemodify(vim.fn.tempname(), ':p')
  vim.fn.mkdir(dir, 'p')
  local other = vim.fn.fnamemodify(vim.fn.tempname(), ':p')
  vim.fn.mkdir(other, 'p')
  local with = open(buffer(1), dir)
  t.eq({ vim.fs.normalize(vim.fn.resolve(dir)), 1 },
    { vim.fs.normalize(vim.fn.resolve(cwd(with)[1])), cwd(with)[2] }, ':lcd in the new window')
  vim.cmd.lcd(other)
  local without = open(buffer(1), '')
  t.eq(cwd(0), cwd(without), "the current window's :lcd, copied as Vim copies it")
  t.ok(not vim.fn.execute('messages'):find(vim.fs.basename(vim.fs.normalize(dir)), 1, true),
    'silent: no message replaces the one the user reads')
end)

t.test('a folder that does not exist yet is skipped: the window opens with the copied one', function()
  start()
  local win = open(buffer(1), vim.fn.tempname() .. '/missing')
  t.eq(cwd(0), cwd(win))
end)

t.test("putting a buffer in a window quietly gives no file info, even without 'shortmess' F", function()
  start()
  vim.o.shortmess = vim.o.shortmess:gsub('F', '')
  local a, b = vim.fn.bufadd(vim.fn.tempname() .. '/a.txt'), buffer(3)
  vim.fn.bufload(a)
  local other = open(b)
  vim.cmd('messages clear')
  t.run('TRAVEL_WINDOW', tostring(other), tostring(a), '1', '0')
  t.eq('', vim.trim(vim.api.nvim_exec2('messages', { output = true }).output))
  t.ok(not vim.o.shortmess:find('F', 1, true), "the user's 'shortmess' is back")
end)

t.test("with 'autochdir' the plug-in leaves the folder to Neovim", function()
  start()
  vim.o.autochdir = true
  local win = open(buffer(1), vim.fn.tempname())
  t.eq(0, cwd(win)[2], 'no :lcd of its own')
end)

t.test('showing a window enters it, and BufEnter says which window', function()
  local home = start()
  local a = buffer(3)
  local win = open(a)
  t.eq(win, show(win, a))
  t.eq(win, vim.api.nvim_get_current_win())
  t.eq({ { a, win } }, t.sent('eclipse_buf_enter'))
  t.forget()
  t.eq(home, show(-1, a), '-1 is the home window')
  t.eq(a, vim.api.nvim_win_get_buf(home), 'the home window shows the buffer asked for')
end)

t.test('a window showing another buffer gets the right one quietly before it is entered', function()
  start()
  local a, b = buffer(3), buffer(3)
  local win = open(b)
  show(win, a)
  t.eq(a, vim.api.nvim_win_get_buf(win))
  t.eq({ { a, win } }, t.sent('eclipse_buf_enter'), 'one BufEnter, with the buffer Eclipse expects')
end)

t.test('a window closed in Neovim is not shown: -1, and nothing changes', function()
  local home = start()
  local a = buffer(3)
  local win = open(a)
  vim.api.nvim_win_close(win, true)
  t.eq(-1, show(win, a))
  t.eq(home, vim.api.nvim_get_current_win())
end)

t.test('a window closed in Neovim by itself sends eclipse_window_closed: the safety net', function()
  local home = start()
  local win = open(buffer(3))
  show(win, vim.api.nvim_win_get_buf(win))
  t.forget()
  vim.cmd('tabclose')
  t.eq({ { win } }, t.sent('eclipse_window_closed'))
  t.eq(home, vim.api.nvim_get_current_win(), 'Neovim entered the other tab')
end)

t.test('the travel swap gives the other window the buffer left behind and its cursor, quietly', function()
  start()
  local a, b = buffer(30), buffer(30)
  local mover, other = open(a), open(b)
  show(mover, a)
  vim.api.nvim_set_current_buf(b) -- the jump into b in the mover's window
  t.forget()
  t.run('TRAVEL_WINDOW', tostring(other), tostring(a), '20', '3')
  t.eq(a, vim.api.nvim_win_get_buf(other))
  t.eq({ 20, 3 }, vim.api.nvim_win_get_cursor(other))
  t.eq(mover, vim.api.nvim_get_current_win(), 'the mover stays current, now on b')
  t.eq({}, t.sent('eclipse_buf_enter'), 'no autocommand: Eclipse must not take it for a jump')
  t.run('TRAVEL_WINDOW', tostring(other), tostring(a), '99', '0')
  t.eq({ 30, 0 }, vim.api.nvim_win_get_cursor(other), 'a line past the end is the last line')
end)

t.test('closing the current window enters the home window quietly with the buffer given', function()
  local home, home_buf = start()
  local a = buffer(3)
  local win = open(a)
  show(win, a)
  t.forget()
  t.eq(home, t.run('CLOSE_WINDOW', tostring(win), tostring(home_buf)), 'the home window id comes back')
  t.ok(not vim.api.nvim_win_is_valid(win))
  t.eq(1, #vim.api.nvim_list_tabpages(), 'its tab is gone')
  t.eq(home, vim.api.nvim_get_current_win())
  t.eq(home_buf, vim.api.nvim_win_get_buf(home))
  t.eq({}, t.sent('eclipse_window_closed'), "the plug-in's own close is not the safety net's business")
  t.eq({}, t.sent('eclipse_buf_enter'))
  t.ok(vim.api.nvim_buf_is_valid(a), 'the buffer stays, for the other editors on the file')
end)

t.test('the home window is never closed, and is opened again when the user closed it', function()
  local home, home_buf = start()
  local win = open(buffer(3))
  t.run('CLOSE_WINDOW', tostring(home), tostring(home_buf))
  t.ok(vim.api.nvim_win_is_valid(home), 'CLOSE_WINDOW leaves the home window alone')
  show(win, vim.api.nvim_win_get_buf(win))
  vim.api.nvim_win_close(home, true)
  local again = show(-1, home_buf)
  t.ok(again ~= home and vim.api.nvim_win_is_valid(again), 'a new home window')
  t.eq(again, vim.api.nvim_get_current_win())
end)

t.test("before a buffer is wiped, the editor windows on it close and the home window shows another", function()
  local home, home_buf = start()
  local a, b = buffer(3), buffer(3)
  local w1, w2, w3 = open(a), open(a), open(b)
  show(w1, a)
  vim.api.nvim_win_set_buf(home, a)
  t.forget()
  local closed = t.run('RELEASE_BUFFER', tostring(a), tostring(home_buf))
  table.sort(closed)
  t.eq({ w1, w2 }, closed)
  t.ok(vim.api.nvim_win_is_valid(w3), 'a window on another buffer stays')
  t.eq(home, vim.api.nvim_get_current_win(), 'the current window was one of them')
  t.eq(home_buf, vim.api.nvim_win_get_buf(home))
  t.eq({}, vim.fn.win_findbuf(a), 'nothing shows it: the wipe makes Neovim enter nothing')
  t.eq({}, t.sent('eclipse_window_closed'))
end)

--- The window commands as the session installs them: HOST_COMMANDS, then WINDOW_COMMANDS.
local function commands()
  local home, home_buf = start()
  t.run('HOST_COMMANDS', t.host_rules)
  t.run('WINDOW_COMMANDS')
  local a = buffer(3)
  local win = open(a)
  show(win, a)
  t.forget()
  return win, home, home_buf
end

local function windows_in_tab()
  return #vim.api.nvim_tabpage_list_wins(0)
end

t.test(':split, :vsplit, :tabnew and :tabonly are refused with Eclipse\'s way to do it', function()
  commands()
  local tabs = #vim.api.nvim_list_tabpages()
  for _, line in ipairs({ 'split', 'vs', 'new', 'tabnew', 'tabe x.txt', 'tabonly', 'tab split' }) do
    vim.v.errmsg = ''
    t.type(':' .. line .. '<CR>')
    t.ok(vim.v.errmsg:find('Toggle Split Editor', 1, true), line .. ': ' .. vim.v.errmsg)
  end
  t.eq(1, windows_in_tab(), 'no second window')
  t.eq(tabs, #vim.api.nvim_list_tabpages(), 'no tab opened or closed')
  t.eq({}, t.sent('eclipse_command'))
end)

t.test(':close and :tabclose go to Eclipse, which closes the editor', function()
  commands()
  t.type(':close<CR>')
  t.type(':tabc!<CR>')
  t.eq({ { 'close', false, {} }, { 'tabclose', true, {} } }, t.sent('eclipse_command'))
  t.eq(2, #vim.api.nvim_list_tabpages(), 'Neovim closed nothing itself')
end)

t.test('Ctrl-W s and v are refused, Ctrl-W q and c close the editor', function()
  commands()
  vim.v.errmsg = ''
  t.type('<C-w>s')
  t.type('<C-w>v')
  t.eq(1, windows_in_tab())
  t.type('<C-w>q')
  t.type('<C-w>c')
  t.eq({ { 'quit', false, {} }, { 'quit', false, {} } }, t.sent('eclipse_command'))
end)

t.test("a Ctrl-W key the user's config maps is left to it", function()
  start()
  t.run('HOST_COMMANDS', t.host_rules)
  vim.keymap.set('n', '<C-w>s', '<Cmd>let g:mine = 1<CR>')
  t.run('WINDOW_COMMANDS')
  t.type('<C-w>s')
  t.eq(1, vim.g.mine)
end)

t.test('a split a plug-in opens in an editor\'s tab is closed again: the safety net', function()
  local win = commands()
  vim.cmd.split() -- not the command line: no refusal sees it
  t.eq(2, windows_in_tab())
  t.wait_for('the stray window to close', function() return windows_in_tab() == 1 end)
  t.ok(vim.api.nvim_win_is_valid(win), "the editor's window stays")
  t.ok(vim.v.errmsg:find('Window closed', 1, true), vim.v.errmsg)
end)

t.test('floating windows are left alone', function()
  commands()
  local float = vim.api.nvim_open_win(buffer(1), false, { relative = 'editor', row = 1, col = 1, width = 5, height = 1 })
  -- The net's check is scheduled; one scheduled after it runs after it.
  local after = false
  vim.schedule(function() after = true end)
  t.wait_for("the net's check", function() return after end)
  t.ok(vim.api.nvim_win_is_valid(float))
end)
