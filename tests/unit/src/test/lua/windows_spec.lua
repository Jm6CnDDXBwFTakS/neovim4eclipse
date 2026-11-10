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

--- Makes Neovim's screen as small as the editor's `rows` of text need, as the session's resize
--- does: the rows, the status line (laststatus=2, Neovim's default) and the command line. No tab
--- line (WINDOWS hides it). Counted from the options, not measured with winheight(): in this
--- harness (nvim -l) Neovim does not lay out its current tab again for new 'lines', though a new
--- tabpage gets the new size.
local function screen_for(rows)
  vim.o.laststatus = 2
  vim.o.lines = rows + 1 + vim.o.cmdheight
end

-- Why ViewportSize.MIN_TEXT_ROWS is 2: a closed or minimized editor measures 0 rows, and on a
-- screen with one text row the next editor's window could not be opened (E36), so its document
-- was not shown in Neovim. If Neovim ever opens it on one row, the floor could go back to 1.
t.test('a window opens on the smallest screen the plug-in sends (two text rows), not on one', function()
  start()
  screen_for(2)
  local win = open(buffer(3))
  t.eq(2, #vim.api.nvim_list_tabpages(), 'opened')
  t.ok(vim.api.nvim_win_is_valid(win))

  screen_for(1)
  local ok, err = pcall(open, buffer(3))
  t.eq(false, ok, 'one text row leaves no room')
  t.ok(tostring(err):find('E36', 1, true), 'E36: Not enough room, not ' .. tostring(err))
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

--- The window's jump history: its length and where Ctrl-O starts from, as "2:2".
local function jumps(win)
  return vim.api.nvim_win_call(win, function()
    local list, pos = unpack(vim.fn.getjumplist())
    return #list .. ':' .. pos
  end)
end

t.test('putting another buffer in a window (a travel, a show) adds no jump to its history', function()
  start()
  local a, b = buffer(30), buffer(30)
  local win = open(a)
  vim.api.nvim_win_call(win, function()
    vim.cmd('normal! 10G')
    vim.cmd('normal! 20G')
  end)
  local before = jumps(win)
  t.eq('2:2', before, 'the window has two jumps of its own')

  t.run('TRAVEL_WINDOW', tostring(win), tostring(b), '5', '0') -- the window left behind by a travel
  t.eq(b, vim.api.nvim_win_get_buf(win))
  t.eq(before, jumps(win), 'no stray jump: Ctrl-O there would find one more for ever')
  show(win, a) -- Eclipse shows its document there again
  t.eq(before, jumps(win))
end)

t.test("the folder is set once with :lcd; without one the window keeps the folder it copied", function()
  start()
  local dir = vim.fn.fnamemodify(vim.fn.tempname(), ':p')
  vim.fn.mkdir(dir, 'p')
  local other = vim.fn.fnamemodify(vim.fn.tempname(), ':p')
  vim.fn.mkdir(other, 'p')
  local with = open(buffer(1), dir)
  t.same_file(dir, cwd(with)[1], ':lcd in the new window')
  t.eq(1, cwd(with)[2], 'its own folder (:lcd)')
  vim.cmd.lcd(other)
  local without = open(buffer(1), '')
  t.eq(cwd(0), cwd(without), "the current window's :lcd, copied as Vim copies it")
  t.ok(not vim.fn.execute('messages'):find(vim.fs.basename(vim.fs.normalize(dir)), 1, true),
    'silent: no message replaces the one the user reads')
end)

-- A project folder named with backticks: :lcd would expand `...` as a shell command (0.2.1
-- security review); chdir() takes the name as it is.
t.test('a folder name with backticks is used as it is, nothing runs', function()
  if vim.fn.has('win32') == 1 then
    t.skip('the name holds the marker\'s full path, and a Windows one (C:\\...) cannot be part of a name')
  end
  start()
  local base = vim.fn.fnamemodify(vim.fn.tempname(), ':p')
  local marker = base .. 'MARKER'
  local dir = base .. 'evil`touch ' .. marker .. '`'
  vim.fn.mkdir(dir, 'p')
  local win = open(buffer(1), dir)
  t.same_file(dir, cwd(win)[1], 'the window works in that very folder')
  t.eq(0, vim.fn.filereadable(marker), 'the backtick command did not run')
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
  t.eq({ { a, win, false, '' } }, t.sent('eclipse_buf_enter'))
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
  t.eq({ { a, win, false, '' } }, t.sent('eclipse_buf_enter'), 'one BufEnter, with the buffer Eclipse expects')
end)

t.test('a window closed in Neovim is not shown: -1, and nothing changes', function()
  local home = start()
  local a = buffer(3)
  local win = open(a)
  vim.api.nvim_win_close(win, true)
  t.eq(-1, show(win, a))
  t.eq(home, vim.api.nvim_get_current_win())
end)

t.test('a buffer wiped in Neovim is not shown: -2, and nothing changes', function()
  local home = start()
  local a, b = buffer(3), buffer(3)
  local win = open(a)
  vim.api.nvim_buf_delete(b, { force = true })
  t.eq(-2, show(win, b))
  t.eq(-2, show(-1, b))
  t.eq(home, vim.api.nvim_get_current_win())
  t.eq(a, vim.api.nvim_win_get_buf(win))
end)

t.test('no window is opened on a buffer wiped in Neovim: -2, and nothing changes', function()
  local home = start()
  local gone = buffer(3)
  vim.api.nvim_buf_delete(gone, { force = true })
  local tabs = #vim.api.nvim_list_tabpages()
  t.eq(-2, open(gone))
  t.eq(tabs, #vim.api.nvim_list_tabpages(), 'no tabpage')
  t.eq(home, vim.api.nvim_get_current_win())
end)

t.test('a wiped home buffer is made anew, once, and the home window reopened on it', function()
  local home, home_buf = start()
  t.eq(home_buf, t.run('HOME_BUFFER'), 'the one made at start while it lives')
  local other = buffer(1)
  vim.api.nvim_win_set_buf(home, other)
  vim.api.nvim_buf_delete(home_buf, { force = true })
  local new = t.run('HOME_BUFFER')
  t.eq(true, new ~= home_buf and vim.api.nvim_buf_is_valid(new), 'a new buffer')
  t.eq(false, vim.bo[new].buflisted, 'unlisted')
  t.eq('nofile', vim.bo[new].buftype, 'scratch')
  t.eq(new, t.run('HOME_BUFFER'), 'remembered: the same one next time')
  -- The home window, closed, is opened again on that buffer, not on yet another one.
  vim.cmd('tabnew')
  vim.api.nvim_win_close(home, true)
  local reopened = show(-1, new)
  t.eq(new, vim.api.nvim_win_get_buf(reopened))
  t.eq(new, t.run('HOME_BUFFER'))
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
  t.eq({ home, home_buf }, t.run('CLOSE_WINDOW', tostring(win), tostring(home_buf)), 'the home window and buffer come back')
  t.ok(not vim.api.nvim_win_is_valid(win))
  t.eq(1, #vim.api.nvim_list_tabpages(), 'its tab is gone')
  t.eq(home, vim.api.nvim_get_current_win())
  t.eq(home_buf, vim.api.nvim_win_get_buf(home))
  t.eq({}, t.sent('eclipse_window_closed'), "the plug-in's own close is not the safety net's business")
  t.eq({}, t.sent('eclipse_buf_enter'))
  t.ok(vim.api.nvim_buf_is_valid(a), 'the buffer stays, for the other editors on the file')
end)

t.test('closing the current window after the home buffer was wiped shows a new home buffer', function()
  local home, home_buf = start()
  local a = buffer(3)
  local win = open(a)
  show(win, a)
  -- A plug-in's :bwipeout 2: Neovim closes the home window, which showed it, as well.
  vim.api.nvim_buf_delete(home_buf, { force = true })
  t.forget()
  local answer = t.run('CLOSE_WINDOW', tostring(win), tostring(home_buf))
  local again, new = answer[1], answer[2]
  t.eq(true, again ~= home and vim.api.nvim_win_is_valid(again), 'the home window, opened again')
  t.eq(true, new ~= home_buf and vim.api.nvim_buf_is_valid(new), 'a new home buffer, named in the answer')
  t.eq(new, vim.api.nvim_win_get_buf(again), 'in the home window, not a buffer Neovim chose')
  t.eq(again, vim.api.nvim_get_current_win())
end)

t.test('releasing a buffer the home window shows after the home buffer was wiped shows a new one', function()
  local home, home_buf = start()
  local a = buffer(3)
  show(-1, a) -- a document whose own window was closed in Neovim
  vim.api.nvim_buf_delete(home_buf, { force = true })
  t.forget()
  local answer = t.run('RELEASE_BUFFER', tostring(a), tostring(home_buf)) -- no E86
  local new = answer[2]
  t.eq({}, answer[1], 'no editor window closed')
  t.eq(true, new ~= home_buf and vim.api.nvim_buf_is_valid(new), 'made anew, named in the answer')
  t.eq(new, vim.api.nvim_win_get_buf(home))
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
  local closed, home_now = unpack(t.run('RELEASE_BUFFER', tostring(a), tostring(home_buf)))
  t.eq(home_buf, home_now, 'the home buffer, as it was')
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

--- A full path as Eclipse gets it (vim.fs.normalize'd: / only; see host_commands_spec.lua).
local function sent_path(path)
  return vim.fs.normalize(path, { expand_env = false })
end

--- The words of each window command sent since the last t.forget(), as { name, args... }.
local function sent_windows()
  local found = {}
  for _, c in ipairs(t.sent('eclipse_command')) do
    found[#found + 1] = vim.list_extend({ c[1] }, c[3])
  end
  return found
end

-- Vim's rule for the side: above and left, unless 'splitbelow' or 'splitright', or a modifier.
t.test(':split, :vsplit, :new and :vnew go to Eclipse, on the side Vim would put them', function()
  commands()
  vim.o.splitbelow, vim.o.splitright = false, false
  for _, line in ipairs({ 'split', 'vs', 'new', 'vnew', 'vertical new', 'belowright split', 'botright vsplit' }) do
    t.type(':' .. line .. '<CR>')
  end
  vim.o.splitbelow, vim.o.splitright = true, true
  for _, line in ipairs({ 'sp', 'vsp', 'aboveleft split', 'topleft vnew' }) do
    t.type(':' .. line .. '<CR>')
  end
  t.eq({
    { 'window', 'split', 'above', 'same' },
    { 'window', 'split', 'left', 'same' },
    { 'window', 'split', 'above', 'new' },
    { 'window', 'split', 'left', 'new' },
    { 'window', 'split', 'left', 'new' },
    { 'window', 'split', 'below', 'same' },
    { 'window', 'split', 'right', 'same' },
    { 'window', 'split', 'below', 'same' },
    { 'window', 'split', 'right', 'same' },
    { 'window', 'split', 'above', 'same' },
    { 'window', 'split', 'left', 'new' },
  }, sent_windows())
  t.eq(1, windows_in_tab(), 'Neovim opened no window itself')
  t.eq('topleft vnew', vim.fn.histget(':', -1), 'the history keeps the line as typed')
  t.forget()
  t.type('@:')
  t.eq({ { 'window', 'split', 'left', 'new' } }, sent_windows(), '@: repeats it')
end)

t.test('a split of a file sends its full path; of this buffer\'s own file, a clone', function()
  commands()
  local _, name = t.document('a.txt', { 'a' })
  t.forget()
  vim.o.splitbelow = false
  t.type(':split b.txt<CR>')
  t.type(':sview my\\ b.txt<CR>')
  t.type(':split %<CR>')
  t.type(':split ++ff=unix<CR>')
  t.type(':new c.txt<CR>')
  t.eq({
    { 'window', 'split', 'above', 'file', sent_path(vim.fn.getcwd() .. '/b.txt') },
    { 'window', 'split', 'above', 'file', sent_path(vim.fn.getcwd() .. '/my b.txt') },
    { 'window', 'split', 'above', 'same' },
    { 'window', 'split', 'above', 'same' },
    { 'window', 'split', 'above', 'file', sent_path(vim.fn.getcwd() .. '/c.txt') },
  }, sent_windows())
  t.same_file(name, vim.api.nvim_buf_get_name(0), 'still in a.txt: Eclipse opens the new editor')
  vim.v.errmsg = ''
  t.forget()
  t.type(':split +10 b.txt<CR>')
  t.ok(vim.v.errmsg:find('+cmd is not supported', 1, true), vim.v.errmsg)
  t.eq({}, sent_windows())
end)

t.test(':sfind looks in \'path\', as Vim does', function()
  commands()
  local dir = vim.fn.tempname()
  vim.fn.mkdir(dir, 'p')
  vim.fn.writefile({ 'x' }, dir .. '/found.txt')
  vim.o.path = dir
  t.type(':sfind found.txt<CR>')
  t.eq({ { 'window', 'split', 'above', 'file', sent_path(dir .. '/found.txt') } }, sent_windows())
  t.forget()
  local _, name = t.document('a.txt', { 'a' })
  vim.fn.writefile({ 'n' }, vim.fs.dirname(name) .. '/near.txt')
  t.forget()
  t.type(':sfind %:h/near.txt<CR>') -- expanded first, as Vim's :sfind does
  t.eq({ { 'window', 'split', 'above', 'file', sent_path(vim.fs.dirname(name) .. '/near.txt') } }, sent_windows())
  t.forget()
  vim.v.errmsg = ''
  t.type(':sfind missing.txt<CR>')
  t.ok(vim.v.errmsg:find('E345', 1, true), vim.v.errmsg)
  t.eq({}, sent_windows())
end)

t.test(':sbuffer sends the buffer\'s file; a buffer without one is refused', function()
  commands()
  local other, other_name = t.document('other.txt', { 'o' })
  local _, name = t.document('a.txt', { 'a' })
  local scratch = buffer(1)
  t.forget()
  t.type(':sbuffer ' .. other .. '<CR>')
  t.type(':sb other<CR>') -- by name, as :buffer matches it
  t.type(':sbuffer<CR>')
  t.type(':sbuffer ' .. vim.api.nvim_get_current_buf() .. '<CR>')
  t.eq({
    { 'window', 'split', 'above', 'file', sent_path(other_name) },
    { 'window', 'split', 'above', 'file', sent_path(other_name) },
    { 'window', 'split', 'above', 'same' },
    { 'window', 'split', 'above', 'same' },
  }, sent_windows())
  t.forget()
  vim.v.errmsg = ''
  t.type(':sbuffer 999<CR>')
  t.ok(vim.v.errmsg:find('E86', 1, true), vim.v.errmsg)
  t.type(':sbuffer ' .. scratch .. '<CR>')
  t.ok(vim.v.errmsg:find('has no file', 1, true), vim.v.errmsg)
  t.eq({}, sent_windows())
  t.same_file(name, vim.api.nvim_buf_get_name(0))
end)

-- A buffer named after a cloned repository's file with a line break in its name: the :Eclipse line
-- would end at the break, and the rest of the name would run as a command.
t.test('a split of a name with a line break is refused, and nothing of it runs', function()
  if vim.fn.has('win32') == 1 then
    t.skip('Windows names cannot hold a line break')
  end
  commands()
  local buf = vim.api.nvim_create_buf(true, false)
  vim.api.nvim_buf_set_name(buf, vim.fn.tempname() .. '/x' .. string.char(10) .. 'lua vim.g.ran = 1')
  t.forget()
  vim.v.errmsg = ''
  t.type(':sbuffer ' .. buf .. '<CR>')
  t.ok(vim.v.errmsg:find('line break', 1, true), vim.v.errmsg)
  t.eq(nil, vim.g.ran, 'the rest of the name did not run')
  t.eq({}, sent_windows())
end)

t.test('the Ctrl-W keys send their window command, with the count', function()
  commands()
  vim.o.splitbelow, vim.o.splitright = false, false
  for _, keys in ipairs({ '<C-w>s', '<C-w>v', '<C-w>n', '<C-w>l', '3<C-w>w', '<C-w><C-j>', '<C-w><Up>',
                          '<C-w>W', '<C-w>t', '<C-w>b', '<C-w>p', '<C-w>o', '5<C-w>+', '<C-w>-', '<C-w>=',
                          '2<C-w><', '<C-w>>', '<C-w>_', '10<C-w>|', '<C-w>x', '<C-w>r', '2<C-w>R' }) do
    t.type(keys)
  end
  t.eq({
    { 'window', 'split', 'above', 'same' },
    { 'window', 'split', 'left', 'same' },
    { 'window', 'split', 'above', 'new' },
    { 'window', 'focus', 'l', '0' },
    { 'window', 'focus', 'w', '3' },
    { 'window', 'focus', 'j', '0' },
    { 'window', 'focus', 'k', '0' },
    { 'window', 'focus', 'W', '0' },
    { 'window', 'focus', 't', '0' },
    { 'window', 'focus', 'b', '0' },
    { 'window', 'focus', 'p', '0' },
    { 'window', 'only' },
    { 'window', 'size', '+', '5' },
    { 'window', 'size', '-', '0' },
    { 'window', 'size', '=', '0' },
    { 'window', 'size', '<', '2' },
    { 'window', 'size', '>', '0' },
    { 'window', 'size', '_', '0' },
    { 'window', 'size', '|', '10' },
    { 'window', 'exchange', '0' },
    { 'window', 'rotate', 'down', '0' },
    { 'window', 'rotate', 'up', '2' },
  }, sent_windows())
  t.eq(1, windows_in_tab())
  t.forget()
  t.type('<C-w>q')
  t.type('<C-w>c')
  t.eq({ { 'quit', false, {} }, { 'quit', false, {} } }, t.sent('eclipse_command'))
end)

t.test('Ctrl-W ^ splits onto the alternate file, Ctrl-W f onto the file under the cursor', function()
  commands()
  local dir = vim.fn.tempname()
  vim.fn.mkdir(dir, 'p')
  vim.fn.writefile({ 'x' }, dir .. '/named.txt')
  local _, first = t.document('first.txt', { 'f' })
  t.document('second.txt', { 'named.txt' })
  vim.o.path = dir
  t.forget()
  t.type('<C-w>^')
  t.type('gg<C-w>f')
  t.eq({
    { 'window', 'split', 'above', 'file', sent_path(first) },
    { 'window', 'split', 'above', 'file', sent_path(dir .. '/named.txt') },
  }, sent_windows())
  t.forget()
  vim.v.errmsg = ''
  vim.api.nvim_buf_set_lines(0, 0, -1, false, { 'missing.txt' })
  t.type('gg<C-w>f')
  t.ok(vim.v.errmsg:find('E447', 1, true), vim.v.errmsg)
  t.eq({}, sent_windows())
end)

t.test(':wincmd, :only and :resize send the same as the keys', function()
  commands()
  for _, line in ipairs({ 'wincmd l', '2wincmd w', 'only', 'only!', 'resize +5', 'resize -2', 'resize 10',
                          'resize', 'vertical resize -3', 'vertical resize 80', 'wincmd <C-v><C-w>' }) do
    t.type(':' .. line .. '<CR>')
  end
  t.eq({
    { 'window', 'focus', 'l', '0' },
    { 'window', 'focus', 'w', '2' },
    { 'window', 'only' },
    { 'window', 'only' },
    { 'window', 'size', '+', '5' },
    { 'window', 'size', '-', '2' },
    { 'window', 'size', '_', '10' },
    { 'window', 'size', '_', '0' },
    { 'window', 'size', '<', '3' },
    { 'window', 'size', '|', '80' },
    { 'window', 'focus', 'w', '0' }, -- a typed Ctrl-W character, as :wincmd takes it
  }, sent_windows())
  t.forget()
  t.type(':wincmd q<CR>')
  t.eq({ { 'quit', false, {} } }, t.sent('eclipse_command'))
end)

t.test('tab commands and splits that jump to a tag are refused, with why', function()
  commands()
  local tabs = #vim.api.nvim_list_tabpages()
  for _, keys in ipairs({ ':tabnew<CR>', ':tabe x.txt<CR>', ':tabonly<CR>', ':tab split<CR>', '<C-w>T' }) do
    vim.v.errmsg = ''
    t.type(keys)
    t.ok(vim.v.errmsg:find('editor tabs are the windows', 1, true), keys .. ': ' .. vim.v.errmsg)
  end
  for _, keys in ipairs({ ':stag main<CR>', '<C-w>]', '<C-w><C-]>', '<C-w>F' }) do
    vim.v.errmsg = ''
    t.type(keys)
    t.ok(vim.v.errmsg:find('jumps to a tag', 1, true), keys .. ': ' .. vim.v.errmsg)
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

t.test('Toggle Split Editor asks for :split or :vsplit, on the side the options say', function()
  commands()
  vim.o.splitbelow, vim.o.splitright = true, false
  t.run('SPLIT_WINDOW', '0')
  t.run('SPLIT_WINDOW', '1')
  t.eq({ { 'window', 'split', 'below', 'same' }, { 'window', 'split', 'left', 'same' } }, sent_windows())
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

t.test('the quickfix and location list windows are closed too: they would take the editor\'s rows', function()
  local win = commands()
  vim.fn.setqflist({ { bufnr = vim.api.nvim_win_get_buf(win), lnum = 2, text = 'x' } })
  vim.v.errmsg = ''
  vim.cmd.copen()
  t.eq(2, windows_in_tab())
  t.wait_for('the quickfix window to close', function() return windows_in_tab() == 1 end)
  t.ok(vim.api.nvim_win_is_valid(win), "the editor's window stays")
  t.ok(vim.v.errmsg:find('cannot show the quickfix window', 1, true), vim.v.errmsg)
  t.eq(1, #vim.fn.getqflist(), 'the list stays, for :cnext')
end)

--- A floating window entered, as Ctrl-W w enters hover documentation.
local function enter_float()
  return vim.api.nvim_open_win(buffer(1), true, { relative = 'editor', row = 1, col = 1, width = 5, height = 1 })
end

t.test('only floating and special windows are side windows', function()
  local win, home = commands()
  local float = enter_float()
  local side = require('neovim4eclipse.windows').is_side_window
  t.ok(side(0), 'the float is current')
  t.ok(side(float))
  t.ok(not side(win), "an editor's window")
  t.ok(not side(home), 'the home window')
end)

t.test(':q, :close, Ctrl-W q and Ctrl-W c in a floating window close it, not the editor', function()
  local win = commands()
  for _, keys in ipairs({ ':q<CR>', ':close<CR>', '<C-w>q', '<C-w>c' }) do
    local float = enter_float()
    t.type(keys)
    t.ok(not vim.api.nvim_win_is_valid(float), keys .. ' closed the float')
    t.eq(win, vim.api.nvim_get_current_win(), keys .. ': back in the editor\'s window')
  end
  t.eq({}, t.sent('eclipse_command'))
  t.eq({}, t.sent('eclipse_buffer_closed'), 'no QuitPre report either')
  t.type(':q<CR>') -- in the editor's window again: Eclipse's
  t.eq({ { 'quit', false, {} } }, t.sent('eclipse_command'))
end)

t.test(':tabclose with a tab number or count is refused; :tabclose alone closes the editor', function()
  commands()
  local tabs = #vim.api.nvim_list_tabpages()
  for _, line in ipairs({ 'tabclose 1', '1tabclose', 'tabclose +', '$tabc' }) do
    vim.v.errmsg = ''
    t.type(':' .. line .. '<CR>')
    t.ok(vim.v.errmsg:find('closes this editor only', 1, true), line .. ': ' .. vim.v.errmsg)
  end
  t.eq(tabs, #vim.api.nvim_list_tabpages(), 'Neovim closed no tab')
  t.eq({}, t.sent('eclipse_command'))
  t.type(':tabclose<CR>')
  t.eq({ { 'tabclose', false, {} } }, t.sent('eclipse_command'), 'the refusal of the earlier line is not left over')
end)

t.test("the plug-in's own closes forget the window, though they fire no WinClosed", function()
  local win, home, home_buf = commands()
  local side = require('neovim4eclipse.windows').is_side_window
  t.ok(not side(win), 'one of the plug-in\'s windows while open')
  t.run('CLOSE_WINDOW', tostring(win), tostring(home_buf))
  -- A closed window's type is 'unknown': it counts as the plug-in's no more.
  t.ok(side(win), 'forgotten')
  t.eq(home, vim.api.nvim_get_current_win())
  t.eq({}, t.sent('eclipse_window_closed'))
end)
