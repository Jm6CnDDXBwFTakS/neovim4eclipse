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

--- A window's folder and whether it is its own (:lcd), as { folder, 1 or 0 }; win 0 is the current
--- one. Read by window and tabpage number, not inside nvim_win_call(): there getcwd() on a window
--- of another tabpage answers with another window's folder (probed on 0.12.6).
local function cwd(win)
  win = win == 0 and vim.api.nvim_get_current_win() or win
  local at = vim.fn.win_id2tabwin(win)
  return { vim.fn.getcwd(at[2], at[1]), vim.fn.haslocaldir(at[2], at[1]) }
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
  t.eq({ { a, win, false, '', false, false } }, t.sent('eclipse_buf_enter'))
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
  t.eq({ { a, win, false, '', false, false } }, t.sent('eclipse_buf_enter'), 'one BufEnter, with the buffer Eclipse expects')
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

-- WINDOW_COMMANDS finds the modules it calls in package.loaded, as the session sent them first
-- (PATHS, WINDOWS, HOST_COMMANDS): without one of them the install fails at once and leaves nothing,
-- rather than require searching the runtimepath for it.
t.test('the window commands are not installed without the modules they call', function()
  start()
  t.run('HOST_COMMANDS', t.host_rules)
  for _, name in ipairs({ 'neovim4eclipse.paths', 'neovim4eclipse.windows', 'neovim4eclipse.host_commands' }) do
    local module = package.loaded[name]
    package.loaded[name] = nil
    local ok, err = pcall(t.run, 'WINDOW_COMMANDS')
    package.loaded[name] = module
    t.ok(not ok and tostring(err):find('LuaScripts.PATHS, WINDOWS, HOST_COMMANDS', 1, true) ~= nil,
      name .. ': ' .. tostring(err))
    t.eq(nil, package.loaded['neovim4eclipse.window_commands'], name)
  end
  t.run('WINDOW_COMMANDS')
  t.ok(package.loaded['neovim4eclipse.window_commands'] ~= nil, 'installed once all three are there')
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

-- A file's name in an expected window command, compared by the plug-in's rule (t.same_file), never
-- as text: on macOS a temporary file is /var/... or /private/var/..., on Windows \ or /. Eclipse
-- gets it vim.fs.normalize'd, with / only (see host_commands_spec.lua), which is checked as well.
local FILE = {}
local function file_at(path)
  return setmetatable({ path = path }, FILE)
end

--- The words of each window command sent since the last t.forget(), as { name, args... }.
local function sent_windows()
  local found = {}
  for _, c in ipairs(t.commands()) do
    found[#found + 1] = vim.list_extend({ c[1] }, c[3])
  end
  return found
end

-- Checks that the window commands sent since the last t.forget() are expected, word by word; a
-- file_at(path) word is compared as a file.
local function expect_windows(expected)
  local actual = sent_windows()
  t.eq(#expected, #actual, vim.inspect(actual))
  for i, words in ipairs(expected) do
    t.eq(#words, #actual[i], vim.inspect(actual[i]))
    for j, word in ipairs(words) do
      if getmetatable(word) == FILE then
        t.same_file(word.path, actual[i][j], 'not the same file in ' .. vim.inspect(actual[i]))
        t.ok(not actual[i][j]:find('\\', 1, true), 'sent with / only: ' .. actual[i][j])
      else
        t.eq(word, actual[i][j], vim.inspect(actual[i]))
      end
    end
  end
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
  expect_windows({
    { 'window', 'split', 'above', 'file', file_at(vim.fn.getcwd() .. '/b.txt') },
    { 'window', 'split', 'above', 'file', file_at(vim.fn.getcwd() .. '/my b.txt') },
    { 'window', 'split', 'above', 'same' },
    { 'window', 'split', 'above', 'same' },
    { 'window', 'split', 'above', 'file', file_at(vim.fn.getcwd() .. '/c.txt') },
  })
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
  expect_windows({ { 'window', 'split', 'above', 'file', file_at(dir .. '/found.txt') } })
  t.forget()
  local _, name = t.document('a.txt', { 'a' })
  vim.fn.writefile({ 'n' }, vim.fs.dirname(name) .. '/near.txt')
  t.forget()
  t.type(':sfind %:h/near.txt<CR>') -- expanded first, as Vim's :sfind does
  expect_windows({ { 'window', 'split', 'above', 'file', file_at(vim.fs.dirname(name) .. '/near.txt') } })
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
  expect_windows({
    { 'window', 'split', 'above', 'file', file_at(other_name) },
    { 'window', 'split', 'above', 'file', file_at(other_name) },
    { 'window', 'split', 'above', 'same' },
    { 'window', 'split', 'above', 'same' },
  })
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

t.test('a Ctrl-W key names the window it was typed in', function()
  commands()
  t.type('<C-w>l')
  local sent = t.sent('eclipse_command')
  t.eq({ 'window', vim.api.nvim_get_current_win() }, { sent[1][1], sent[1][4] })
end)

t.test('the Ctrl-W keys send their window command, with the count', function()
  commands()
  vim.o.splitbelow, vim.o.splitright = false, false
  for _, keys in ipairs({ '<C-w>s', '<C-w>v', '<C-w>n', '<C-w>l', '3<C-w>w', '<C-w><C-j>', '<C-w><Up>',
                          '<C-w>W', '<C-w>t', '<C-w>b', '<C-w>p', '<C-w>o', '5<C-w>+', '<C-w>-', '<C-w>=',
                          '2<C-w><', '<C-w>>', '<C-w>_', '10<C-w>|', '<C-w>x', '<C-w>r', '2<C-w>R', '<C-w>T' }) do
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
    { 'window', 'totab' },
  }, sent_windows())
  t.eq(1, windows_in_tab())
  t.forget()
  t.type('<C-w>q')
  t.type('<C-w>c')
  t.eq({ { 'quit', false, {} }, { 'quit', false, {} } }, t.commands())
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
  expect_windows({
    { 'window', 'split', 'above', 'file', file_at(first) },
    { 'window', 'split', 'above', 'file', file_at(dir .. '/named.txt') },
  })
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
  t.eq({ { 'quit', false, {} } }, t.commands())
end)

-- Vim's tab pages are Eclipse's editor tabs: a new editor tab of this stack, with the same rules
-- for the file as a split's.
t.test(':tabedit, :tabnew, :tabfind and :tab split open a new editor tab', function()
  commands()
  local dir = vim.fn.tempname()
  vim.fn.mkdir(dir, 'p')
  vim.fn.writefile({ 'x' }, dir .. '/found.txt')
  vim.o.path = dir
  for _, line in ipairs({ 'tabedit b.txt', 'tabe', 'tabnew c.txt', 'tabnew', 'tabfind found.txt', 'tab split',
                          'tab new', 'tab sbuffer' }) do
    t.type(':' .. line .. '<CR>')
  end
  expect_windows({
    { 'window', 'tab', 'file', file_at(vim.fn.getcwd() .. '/b.txt') },
    { 'window', 'tab', 'new' },
    { 'window', 'tab', 'file', file_at(vim.fn.getcwd() .. '/c.txt') },
    { 'window', 'tab', 'new' },
    { 'window', 'tab', 'file', file_at(dir .. '/found.txt') },
    { 'window', 'tab', 'same' },
    { 'window', 'tab', 'new' },
    { 'window', 'tab', 'same' },
  })
  t.eq(2, #vim.api.nvim_list_tabpages(), 'no tab page opened')
  t.forget()
  vim.v.errmsg = ''
  t.type(':tabfind<CR>')
  t.ok(vim.v.errmsg:find('E471', 1, true), vim.v.errmsg)
  t.eq({}, sent_windows())
end)

t.test(':tabonly closes the other editor tabs; :tabonly! and a count in front are refused', function()
  commands()
  for _, line in ipairs({ 'tabonly', 'tabo 2', 'tabonly $', 'tabonly -1' }) do
    t.type(':' .. line .. '<CR>')
  end
  t.eq({
    { 'window', 'tabonly', '.' },
    { 'window', 'tabonly', '2' },
    { 'window', 'tabonly', '$' },
    { 'window', 'tabonly', '-1' },
  }, sent_windows())
  t.forget()
  local tabs = #vim.api.nvim_list_tabpages()
  vim.v.errmsg = ''
  t.type(':tabonly!<CR>')
  t.ok(vim.v.errmsg:find('discard every other', 1, true), vim.v.errmsg)
  vim.v.errmsg = ''
  t.type(':2tabonly<CR>')
  t.ok(vim.v.errmsg:find('write the tab after', 1, true), vim.v.errmsg)
  t.eq(tabs, #vim.api.nvim_list_tabpages(), 'Neovim closed no tab page')
  t.eq({}, t.commands())
end)

t.test('splits that jump to a tag are refused, with why', function()
  commands()
  for _, keys in ipairs({ ':stag main<CR>', '<C-w>]', '<C-w><C-]>', '<C-w>F' }) do
    vim.v.errmsg = ''
    t.type(keys)
    t.ok(vim.v.errmsg:find('jumps to a tag', 1, true), keys .. ': ' .. vim.v.errmsg)
  end
  t.eq(1, windows_in_tab(), 'no second window')
  t.eq({}, t.commands())
end)

t.test(':close and :tabclose go to Eclipse, which closes the editor', function()
  commands()
  t.type(':close<CR>')
  t.type(':tabc!<CR>')
  t.eq({ { 'close', false, {} }, { 'tabclose', true, {} } }, t.commands())
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

--- Lets the nets' checks for the windows opened so far run, so that none is pending below: one
--- would find a window opened since, and a spec of another route to the net would prove nothing.
local function earlier_checks_done()
  local settled = false
  vim.schedule(function() settled = true end)
  t.wait_for('the earlier checks', function() return settled end)
end

t.test('a split made with :noautocmd is closed too, as Neovim is about to wait for a key', function()
  local win = commands()
  earlier_checks_done()
  vim.v.errmsg = ''
  vim.cmd('noautocmd split') -- no WinNew
  t.eq(2, windows_in_tab())
  vim.api.nvim_exec_autocmds('SafeState', {})
  t.wait_for('the stray window to close', function() return windows_in_tab() == 1 end)
  t.eq(win, vim.api.nvim_get_current_win(), "Neovim back in the editor's window")
  t.ok(vim.v.errmsg:find('Window closed', 1, true), vim.v.errmsg)
end)

--- A file on disk, with three lines, for a plug-in's split to show.
local function file_on_disk()
  local path = vim.fn.tempname() .. '.txt'
  vim.fn.writefile({ 'one', 'two', 'three' }, path)
  return path
end

--- The `window` commands sent since the last t.forget(), once `n` have come and the net has run.
local function handed_over(n)
  t.wait_for('the window handed to Eclipse', function() return #sent_windows() >= n end)
  return sent_windows()
end

t.test("a plug-in's split of a file becomes an Eclipse split on the side Neovim put it", function()
  local win = commands()
  local path = file_on_disk()
  for _, case in ipairs({ { 'aboveleft vsplit', 'left' }, { 'belowright vsplit', 'right' },
    { 'aboveleft split', 'above' }, { 'belowright split', 'below' } }) do
    t.forget()
    vim.cmd(case[1] .. ' ' .. vim.fn.fnameescape(path)) -- not the command line: no translation sees it
    local words = handed_over(1)[1]
    t.eq({ 'window', 'split', case[2], 'file' }, vim.list_slice(words, 1, 4), case[1])
    t.same_file(path, words[5])
    t.eq(1, windows_in_tab(), 'its window closed')
    t.eq(win, vim.api.nvim_get_current_win(), "Neovim back in the editor's window")
  end
end)

t.test('a file split made with :noautocmd becomes an Eclipse split too', function()
  local win = commands()
  earlier_checks_done()
  local path = file_on_disk()
  t.forget()
  vim.cmd('noautocmd belowright split ' .. vim.fn.fnameescape(path))
  vim.api.nvim_exec_autocmds('SafeState', {})
  t.eq({ 'window', 'split', 'below', 'file' }, vim.list_slice(handed_over(1)[1], 1, 4))
  t.eq(win, vim.api.nvim_get_current_win())
end)

t.test('a tab page on a file made with :noautocmd becomes an editor tab too', function()
  local win = commands()
  earlier_checks_done()
  local path = file_on_disk()
  vim.v.errmsg = ''
  t.forget()
  vim.cmd('noautocmd tabedit ' .. vim.fn.fnameescape(path))
  vim.api.nvim_exec_autocmds('SafeState', {})
  local words = handed_over(1)[1]
  t.eq({ 'window', 'tab', 'file' }, vim.list_slice(words, 1, 3))
  t.same_file(path, words[4])
  t.eq(win, vim.api.nvim_get_current_win())
  t.eq('', vim.v.errmsg, 'no "Window closed" message: the file went somewhere')
end)

t.test("a plug-in's tab page on a file becomes an editor tab", function()
  local win = commands()
  local path = file_on_disk()
  local tabs = #vim.api.nvim_list_tabpages()
  vim.v.errmsg = ''
  t.forget()
  vim.cmd('tabedit ' .. vim.fn.fnameescape(path))
  local words = handed_over(1)[1]
  t.eq({ 'window', 'tab', 'file' }, vim.list_slice(words, 1, 3))
  t.same_file(path, words[4])
  t.eq(tabs, #vim.api.nvim_list_tabpages(), 'its tab page closed')
  t.eq(win, vim.api.nvim_get_current_win())
  t.eq('', vim.v.errmsg, 'no "Window closed" message: the file went somewhere')
end)

t.test("the new editor starts at the cursor the plug-in's window had", function()
  commands()
  local path = file_on_disk()
  t.forget()
  vim.cmd('vsplit ' .. vim.fn.fnameescape(path))
  vim.api.nvim_win_set_cursor(0, { 3, 2 })
  handed_over(1)
  vim.cmd('edit ' .. vim.fn.fnameescape(path)) -- stands in for the new editor's window
  t.ok(package.loaded['neovim4eclipse.host_commands'].take_open_command(path), 'a position is waiting')
  t.eq({ 3, 2 }, vim.api.nvim_win_get_cursor(0))
end)

t.test('two files opened by one action each start at their own cursor', function()
  commands()
  local first, second = file_on_disk(), file_on_disk()
  t.forget()
  vim.cmd('vsplit ' .. vim.fn.fnameescape(first))
  vim.api.nvim_win_set_cursor(0, { 2, 1 })
  vim.cmd('vsplit ' .. vim.fn.fnameescape(second))
  vim.api.nvim_win_set_cursor(0, { 3, 2 })
  handed_over(2)
  local host = package.loaded['neovim4eclipse.host_commands']
  for _, case in ipairs({ { first, { 2, 1 } }, { second, { 3, 2 } } }) do
    vim.cmd('edit ' .. vim.fn.fnameescape(case[1])) -- stands in for the new editor's window
    t.ok(host.take_open_command(case[1]), 'a position is waiting')
    t.eq(case[2], vim.api.nvim_win_get_cursor(0))
  end
end)

t.test('every tab page of one action becomes an editor tab', function()
  local win = commands()
  for _, open_second in ipairs({ 'tabnew | setlocal bufhidden=wipe | edit ', 'vsplit ' }) do
    local first, second = file_on_disk(), file_on_disk()
    vim.v.errmsg = ''
    t.forget()
    vim.cmd('tabnew | setlocal bufhidden=wipe | edit ' .. vim.fn.fnameescape(first)) -- as fzf-lua's action
    vim.cmd(open_second .. vim.fn.fnameescape(second))
    local words = handed_over(2)
    t.eq({ 'window', 'tab', 'file' }, vim.list_slice(words[1], 1, 3), open_second)
    t.eq({ 'window', 'tab', 'file' }, vim.list_slice(words[2], 1, 3), open_second)
    -- In the order of Neovim's window list: a vsplit to the left comes before the window it split.
    local files = { first, second }
    table.sort(files)
    table.sort(words, function(x, y) return x[4] < y[4] end)
    t.same_file(files[1], words[1][4])
    t.same_file(files[2], words[2][4])
    t.eq(win, vim.api.nvim_get_current_win(), "Neovim back in the editor's window")
    t.eq('', vim.v.errmsg, 'no "Window closed" message')
  end
end)

--- Two editors, with Neovim moved from the first into the second, and the nets' checks done.
local function in_second_editor()
  start()
  t.run('HOST_COMMANDS', t.host_rules)
  t.run('WINDOW_COMMANDS')
  local a, b = buffer(3), buffer(3)
  local wa, wb = open(a), open(b)
  show(wa, a)
  show(wb, b)
  t.eq(wb, vim.api.nvim_get_current_win())
  earlier_checks_done()
  t.forget()
  return wb
end

t.test('a file window opened without entering it goes beside the editor Neovim is in', function()
  local wb = in_second_editor()
  local buf = vim.fn.bufadd(file_on_disk())
  vim.fn.bufload(buf)
  vim.api.nvim_open_win(buf, false, { split = 'right', win = wb })
  t.eq({ 'window', 'split', 'right', 'file' }, vim.list_slice(handed_over(1)[1], 1, 4))
  t.eq(wb, vim.api.nvim_get_current_win(), 'Neovim stays in the editor Eclipse has active')
end)

t.test('a file window opened behind a float with the keyboard is closed; the keys stay in the float', function()
  local wb = in_second_editor()
  for _, keys in ipairs({ '', 'i' }) do -- Normal and Insert mode
    local float = vim.api.nvim_open_win(vim.api.nvim_create_buf(false, true), true,
      { relative = 'editor', row = 1, col = 1, width = 10, height = 2 })
    local mode = vim.api.nvim_get_mode().mode
    vim.v.errmsg = ''
    t.forget()
    local after = t.during(keys, function()
      local before = vim.api.nvim_get_mode().mode
      local buf = vim.fn.bufadd(file_on_disk())
      vim.fn.bufload(buf)
      local behind = vim.api.nvim_open_win(buf, false, { split = 'right', win = wb })
      t.wait_for('the window closed', function() return not vim.api.nvim_win_is_valid(behind) end)
      return { before = before, mode = vim.api.nvim_get_mode().mode, win = vim.api.nvim_get_current_win() }
    end)
    t.eq(keys == 'i' and 'i' or mode, after.before)
    t.eq(after.before, after.mode, 'the mode is the float\'s still')
    t.eq(float, after.win, 'the cursor stays in the float')
    t.eq({}, sent_windows(), 'nothing handed to Eclipse')
    t.ok(vim.v.errmsg:find('Window closed', 1, true), vim.v.errmsg)
    vim.api.nvim_win_close(float, true)
  end
end)

t.test('a tab page opened from a float that was entered later comes back to its editor', function()
  local wb = in_second_editor()
  local float = vim.api.nvim_open_win(vim.api.nvim_create_buf(false, true), false,
    { relative = 'editor', row = 1, col = 1, width = 10, height = 2 })
  vim.api.nvim_set_current_win(float)
  vim.cmd('tabedit ' .. vim.fn.fnameescape(file_on_disk()))
  t.eq({ 'window', 'tab', 'file' }, vim.list_slice(handed_over(1)[1], 1, 3))
  t.eq(wb, vim.api.nvim_get_current_win())
  vim.api.nvim_win_close(float, true)
end)

t.test('a help window opened without entering it leaves Neovim in its editor', function()
  local wb = in_second_editor()
  local buf = vim.fn.bufadd(vim.env.VIMRUNTIME .. '/doc/help.txt')
  vim.fn.bufload(buf)
  vim.bo[buf].buftype = 'help'
  vim.api.nvim_open_win(buf, false, { split = 'right', win = wb })
  t.wait_for('the help page', function() return #t.sent('eclipse_view_page') == 1 end)
  t.eq(wb, vim.api.nvim_get_current_win())
end)

t.test("a changed buffer outlives the net's close, whatever its 'bufhidden'", function()
  commands()
  for _, bufhidden in ipairs({ 'wipe', 'delete', 'unload' }) do
    local path = file_on_disk()
    vim.cmd('split ' .. vim.fn.fnameescape(path))
    local buf = vim.api.nvim_get_current_buf()
    vim.bo[buf].bufhidden = bufhidden
    vim.api.nvim_buf_set_lines(buf, 0, 1, false, { 'changed' })
    t.wait_for('the stray window to close', function() return windows_in_tab() == 1 end)
    t.ok(vim.api.nvim_buf_is_valid(buf) and vim.api.nvim_buf_is_loaded(buf), bufhidden)
    t.eq('changed', vim.api.nvim_buf_get_lines(buf, 0, 1, false)[1], bufhidden)
    vim.api.nvim_buf_delete(buf, { force = true })
  end
end)

t.test("a plug-in's split of the editor's own file is a clone of it", function()
  commands()
  local path = file_on_disk()
  vim.bo.buftype = ''
  vim.api.nvim_buf_set_name(0, path)
  vim.bo.modified = false
  t.forget()
  vim.cmd('belowright split')
  t.eq({ 'window', 'split', 'below', 'same' }, handed_over(1)[1])
end)

t.test('a window on anything but a readable, unchanged file is closed as before', function()
  commands()
  local changed = file_on_disk()
  for _, open in ipairs({
    function() vim.cmd.split() end, -- a scratch buffer, the editor's in these specs
    function() vim.cmd('split ' .. vim.fn.fnameescape(vim.fn.tempname() .. '-missing')) end,
    function()
      vim.cmd.new()
      vim.api.nvim_buf_set_name(0, 'fugitive:///repo/.git//0/a.txt')
    end,
    function()
      vim.cmd('split ' .. vim.fn.fnameescape(changed))
      vim.api.nvim_buf_set_lines(0, 0, 1, false, { 'changed' })
    end,
  }) do
    vim.v.errmsg = ''
    t.forget()
    open()
    t.wait_for('the stray window to close', function() return windows_in_tab() == 1 end)
    t.eq({}, sent_windows(), 'nothing handed to Eclipse')
    t.ok(vim.v.errmsg:find('Window closed', 1, true), vim.v.errmsg)
  end
end)

t.test(':checkhealth hands its report buffer to a page editor and leaves no window or message', function()
  local win = commands()
  vim.v.errmsg = ''
  t.forget()
  vim.cmd('checkhealth vim.health') -- opens in a new tab: the editor's buffer is not empty
  t.wait_for('the report', function() return #t.sent('eclipse_view_page') == 1 end)
  local kind, buf, line = unpack(t.sent('eclipse_view_page')[1])
  t.eq({ 'checkhealth', 1 }, { kind, line })
  t.ok(vim.api.nvim_buf_is_valid(buf), 'the report is kept, for the page window')
  t.eq({ 'checkhealth', 'hide', false }, { vim.bo[buf].filetype, vim.bo[buf].bufhidden, vim.bo[buf].buflisted },
    'unlisted, so :ls and :bnext never meet it')
  t.ok(table.concat(vim.api.nvim_buf_get_lines(buf, 0, -1, false), '\n'):find('vim.health', 1, true))
  t.eq({}, vim.fn.win_findbuf(buf), 'no window shows it until Eclipse opens the page')
  t.eq(win, vim.api.nvim_get_current_win(), "back in the editor's window")
  t.eq('', vim.v.errmsg, 'no "Window closed" message: the report went somewhere')
end)

t.test(':checkhealth from an editor whose tab is not the last comes back to it, not the next tab', function()
  start()
  t.run('HOST_COMMANDS', t.host_rules)
  t.run('WINDOW_COMMANDS')
  local a, b = buffer(3), buffer(3)
  local wa = open(a)
  local wb = open(b) -- OPEN_WINDOW puts each new tab before the current one: wb's tab is not last
  show(wb, b)
  t.eq(wb, vim.api.nvim_get_current_win())
  t.ok(vim.api.nvim_win_get_tabpage(wb) ~= vim.api.nvim_list_tabpages()[#vim.api.nvim_list_tabpages()],
    "wb's tab is not the last, or closing the report's tab would come back to it anyway")
  -- The nets' checks for the windows just opened run first, so none of them is pending below.
  local settled = false
  vim.schedule(function() settled = true end)
  t.wait_for('the earlier checks', function() return settled end)
  t.forget()
  vim.cmd('checkhealth vim.health')
  t.wait_for('the report', function() return #t.sent('eclipse_view_page') == 1 end)
  t.eq(wb, vim.api.nvim_get_current_win(), "back in the editor the command ran in")
  for _, enter in ipairs(t.sent('eclipse_buf_enter')) do
    t.ok(enter[2] ~= wa, 'never in the other editor: ' .. vim.inspect(t.sent('eclipse_buf_enter')))
  end
end)

t.test('a buffer the user set to the checkhealth filetype is no report: not taken', function()
  commands()
  local mine = buffer(2)
  vim.bo[mine].filetype = 'checkhealth'
  vim.cmd('split')
  vim.api.nvim_win_set_buf(0, mine)
  t.wait_for('the stray split to close', function() return windows_in_tab() == 1 end)
  t.eq({}, t.sent('eclipse_view_page'))
  t.ok(vim.api.nvim_buf_is_valid(mine), 'the buffer stays')
end)

t.test(':checkhealth as a float (vim.g.health style) keeps its buffer when the float closes', function()
  commands()
  vim.g.health = { style = 'float' }
  t.forget()
  vim.cmd('checkhealth vim.health')
  t.wait_for('the report', function() return #t.sent('eclipse_view_page') == 1 end)
  local buf = t.sent('eclipse_view_page')[1][2]
  t.ok(vim.api.nvim_buf_is_valid(buf), 'a float would wipe it: kept')
  t.ok(vim.api.nvim_buf_line_count(buf) > 1, 'with its lines')
  vim.g.health = nil
end)

t.test(':help hands the help buffer to a page editor and leaves no window or message', function()
  local win = commands()
  vim.v.errmsg = ''
  t.forget()
  vim.cmd('help help-context')
  t.wait_for('the page', function() return #t.sent('eclipse_view_page') == 1 end)
  local kind, buf, line = unpack(t.sent('eclipse_view_page')[1])
  t.eq('help', kind)
  t.eq({ 'help', true }, { vim.bo[buf].buftype, vim.api.nvim_buf_is_loaded(buf) }, 'the help buffer, kept loaded')
  t.eq('helphelp.txt', vim.fn.fnamemodify(vim.api.nvim_buf_get_name(buf), ':t'))
  t.ok(vim.api.nvim_buf_get_lines(buf, line - 1, line, false)[1]:find('*help-context*', 1, true),
    'the line :help put the cursor on: ' .. tostring(line))
  t.eq(win, vim.api.nvim_get_current_win(), "back in the editor's window")
  t.eq(1, windows_in_tab())
  t.eq('', vim.v.errmsg, 'no "Window closed" message: the page went somewhere')
end)

t.test(':vert help and :tab help are handed over too, back in the editor they came from', function()
  local win = commands()
  for _, command in ipairs({ 'vert help help-context', 'tab help help-context' }) do
    t.forget()
    vim.cmd(command)
    t.wait_for('the page of ' .. command, function() return #t.sent('eclipse_view_page') == 1 end)
    t.eq(win, vim.api.nvim_get_current_win(), command)
  end
end)

t.test('an editor on a help file no window shows gets the file\'s name, not an unnamed buffer', function()
  commands()
  t.forget()
  vim.cmd('help helphelp.txt')
  t.wait_for('the page', function() return #t.sent('eclipse_view_page') == 1 end)
  local name = vim.fn.fnamemodify(vim.env.VIMRUNTIME .. '/doc/helphelp.txt', ':p')
  local buf, named = unpack(t.run('CREATE_DOCUMENT_BUFFER', name))
  t.eq(true, named)
  t.eq(name, vim.api.nvim_buf_get_name(buf))
end)

t.test(':helpgrep and :cnext from the code hand each match over, on its line', function()
  commands()
  t.forget()
  vim.cmd('silent helpgrep runtimepath')
  t.wait_for('the first match', function() return #t.sent('eclipse_view_page') == 1 end)
  local list = vim.fn.getqflist()
  t.ok(#list > 3, 'matches to go through')
  t.eq(list[1].lnum, t.sent('eclipse_view_page')[1][3])
  for n = 2, 4 do
    vim.cmd('silent cnext')
    t.wait_for('match ' .. n, function() return #t.sent('eclipse_view_page') == n end)
    t.eq(n, vim.fn.getqflist({ idx = 0 }).idx, 'the list moved on')
    t.eq(list[n].lnum, t.sent('eclipse_view_page')[n][3], 'the line of match ' .. n)
  end
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

--- Runs the work scheduled so far, the safety net's check among it: a barrier, not a sleep.
local function flush()
  local after = false
  vim.schedule(function() after = true end)
  t.wait_for("the net's check", function() return after end)
end

--- A floating window entered by the user, as Ctrl-W w enters hover documentation: opened without
--- the focus (hover is), the net's check done, then entered.
local function enter_float()
  local float = vim.api.nvim_open_win(buffer(1), false, { relative = 'editor', row = 1, col = 1, width = 5, height = 1 })
  flush()
  vim.api.nvim_set_current_win(float)
  return float
end

--- The modes SETUP reported since the last t.forget(), each as { mode, in a focused float }.
local function modes()
  return t.sent('eclipse_mode')
end

--- Fires SETUP's ModeChanged report as a mode change would. (A real insert cannot be begun and
--- held open from inside a test; that the report of an insert begun in a float comes after the
--- WinEnter into it, its mode i, is the session's integration test, FloatInsertIntegrationTest.)
local function report_mode()
  vim.api.nvim_exec_autocmds('ModeChanged', {})
end

t.test('a mode report made in a focused floating window says so', function()
  commands()
  enter_float()
  t.forget()
  report_mode()
  t.eq({ { 'n', true } }, modes(), 'an insert begun here is Neovim\'s')
end)

t.test("a mode report made in an editor's window says it is no float", function()
  commands()
  t.forget()
  report_mode()
  t.eq({ { 'n', false } }, modes(), 'an insert begun here is typed by Eclipse')
end)

--- The sixth value of each BufEnter report since the last t.forget(): Neovim entered a plug-in's
--- float to stay (WINDOWS' entered_float: a picker's prompt, a hover float entered).
local function entered_floats()
  local found = {}
  for _, sent in ipairs(t.sent('eclipse_buf_enter')) do
    found[#found + 1] = sent[6]
  end
  return found
end

t.test('a float opened with the focus is reported as entered, for Eclipse to leave Neovim there', function()
  commands()
  t.forget()
  vim.api.nvim_open_win(buffer(1), true, { relative = 'editor', row = 1, col = 1, width = 5, height = 1 })
  t.eq({ true }, entered_floats())
end)

t.test("Telescope's order is reported too: opened without the focus, entered, made a prompt after", function()
  commands()
  local buf = vim.api.nvim_create_buf(false, true) -- plenary's popup: a scratch buffer
  local float = vim.api.nvim_open_win(buf, false, { relative = 'editor', row = 1, col = 1, width = 5, height = 1 })
  t.forget()
  vim.api.nvim_set_current_win(float)
  vim.bo[buf].buftype = 'prompt'
  t.eq({ true }, entered_floats())
end)

t.test('a float entered later is reported too, as a hover float focused by a second K', function()
  commands()
  local float = vim.api.nvim_open_win(buffer(1), false, { relative = 'editor', row = 1, col = 1, width = 5, height = 1 })
  flush() -- the net has seen it
  t.forget()
  vim.api.nvim_set_current_win(float)
  t.eq({ true }, entered_floats())
  flush()
  t.ok(vim.api.nvim_win_is_valid(float), 'and stays')
end)

--- Enters `win` from an Insert-mode <F5>, so that its BufEnter fires during the insert; first
--- `through`, if given, without autocommands, as a plug-in can.
local function enter_in_insert(win, through)
  vim.keymap.set('i', '<F5>', function()
    if through then
      vim.cmd('noautocmd call nvim_set_current_win(' .. through .. ')')
    end
    vim.api.nvim_set_current_win(win)
  end)
  t.forget()
  vim.api.nvim_feedkeys(vim.keycode('i<F5><Esc>'), 'mx', false)
  vim.keymap.del('i', '<F5>')
end

t.test("a float entered in another float's insert is reported: a click from a prompt stays there", function()
  commands()
  vim.api.nvim_open_win(buffer(1), true, { relative = 'editor', row = 1, col = 1, width = 5, height = 1 })
  local other = vim.api.nvim_open_win(buffer(1), false, { relative = 'editor', row = 4, col = 1, width = 5, height = 1 })
  flush()
  enter_in_insert(other)
  t.eq({ true }, entered_floats())
end)

t.test("a float entered in the editor's insert is not: that insert is Eclipse's", function()
  commands()
  local float = vim.api.nvim_open_win(buffer(1), false, { relative = 'editor', row = 1, col = 1, width = 5, height = 1 })
  flush()
  enter_in_insert(float)
  t.eq({ false }, entered_floats())
end)

t.test("a float passed through quietly does not make the editor's insert a float's", function()
  commands()
  local quiet = vim.api.nvim_open_win(buffer(1), false, { relative = 'editor', row = 1, col = 1, width = 5, height = 1 })
  local float = vim.api.nvim_open_win(buffer(1), false, { relative = 'editor', row = 4, col = 1, width = 5, height = 1 })
  flush()
  enter_in_insert(float, quiet)
  t.eq({ false }, entered_floats())
end)

t.test("'mouse' is told at each change, a :noautocmd one at the next mode change, h as a in help", function()
  start()
  local function last()
    local sent = t.sent('eclipse_mouse_option')
    return sent[#sent] and sent[#sent][1]
  end
  vim.o.mouse = 'nv'
  t.eq('nv', last())
  t.forget()
  vim.cmd('noautocmd set mouse=i')
  t.eq(nil, last(), 'no autocommand heard it')
  vim.api.nvim_feedkeys(vim.keycode('v<Esc>'), 'nx', false)
  t.eq('i', last(), 'the next mode change does')
  vim.o.mouse = 'h'
  t.eq('h', last())
  vim.bo.buftype = 'help'
  t.eq('ha', last(), 'h is every mode in a help buffer')
  vim.bo.buftype = ''
  t.eq('h', last())
  vim.o.mouse = 'nvi'
end)

t.test('before WINDOWS runs, no window is a focused float', function()
  t.setup()
  t.forget()
  report_mode()
  t.eq({ { 'n', false } }, modes())
end)

t.test("the plug-in's own windows and plain windows are no focused floats", function()
  local win, home = commands()
  local focused_float = package.loaded['neovim4eclipse.windows'].focused_float
  t.eq(false, focused_float(win), "an editor's window")
  t.eq(false, focused_float(home), 'the home window')
  local float = enter_float()
  t.eq(true, focused_float(0), 'the float, current')
  t.eq(true, focused_float(float))
end)

t.test('a floating window that opens with the focus stays', function()
  local win = commands()
  local float = vim.api.nvim_open_win(buffer(1), true, { relative = 'editor', row = 1, col = 1, width = 5, height = 1 })
  flush()
  t.ok(vim.api.nvim_win_is_valid(float))
  t.eq(float, vim.api.nvim_get_current_win())
  t.ok(win ~= float)
end)

t.test('leaving closes the float the cursor is in, and the next one it lands in, ending an insert', function()
  local win = commands()
  local leave_float = package.loaded['neovim4eclipse.windows'].leave_float
  t.eq(false, leave_float(), 'nothing to leave in the editor\'s window')
  t.eq(win, vim.api.nvim_get_current_win())
  local results = vim.api.nvim_open_win(buffer(1), true, { relative = 'editor', row = 3, col = 1, width = 5, height = 1 })
  local prompt = vim.api.nvim_open_win(buffer(1), true, { relative = 'editor', row = 1, col = 1, width = 5, height = 1 })
  -- Closing the prompt puts Neovim in the results float, the window last current.
  t.eq(true, leave_float())
  t.ok(not vim.api.nvim_win_is_valid(prompt) and not vim.api.nvim_win_is_valid(results), 'both closed')
  t.ok(not package.loaded['neovim4eclipse.windows'].focused_float(0), 'the cursor outside every plug-in float')
  local panel = vim.api.nvim_open_win(buffer(1), false, { relative = 'editor', row = 5, col = 1, width = 5, height = 1 })
  t.eq(false, leave_float())
  t.ok(vim.api.nvim_win_is_valid(panel), 'a float without the cursor stays')
end)

t.test('a float on a buffer with unsaved changes is not closed by leaving: Neovim leaves it', function()
  local win = commands()
  local buf = vim.api.nvim_create_buf(true, false)
  vim.api.nvim_buf_set_name(buf, vim.fn.tempname())
  vim.api.nvim_buf_set_lines(buf, 0, -1, false, { 'typed by the user' })
  vim.bo[buf].bufhidden = 'wipe'
  local float = vim.api.nvim_open_win(buf, true, { relative = 'editor', row = 1, col = 1, width = 5, height = 1 })
  t.eq(false, package.loaded['neovim4eclipse.windows'].leave_float())
  t.ok(vim.api.nvim_win_is_valid(float), 'kept: closing would wipe the changes')
  t.eq(win, vim.api.nvim_get_current_win(), "Neovim in the editor's window of the tab")
  t.eq({ 'typed by the user' }, vim.api.nvim_buf_get_lines(buf, 0, -1, false))
  vim.bo[buf].modified = false
  pcall(vim.api.nvim_win_close, float, true)
end)

t.test("while Neovim is in a plug-in's float, the editor's view scripts act on the editor's window", function()
  local win = commands()
  t.run('VIEWPORT')
  local expected = t.run('VIEWPORT_OVERHEAD')
  local float = vim.api.nvim_open_win(buffer(1), true, { relative = 'editor', row = 1, col = 1, width = 5, height = 1 })
  t.eq(win, package.loaded['neovim4eclipse.windows'].editor_window())
  t.eq(expected, t.run('VIEWPORT_OVERHEAD'), "measured on the editor's window, not the float")
  local buf = vim.api.nvim_win_get_buf(win)
  t.run('SET_WRAP', tostring(buf), '0')
  t.eq(false, vim.wo[win].wrap)
  t.run('SET_WRAP', tostring(buf), '1')
  t.eq(true, vim.wo[win].wrap, "the editor's wrap set from inside the float")
  t.eq(float, vim.api.nvim_get_current_win(), 'the float keeps the focus')
  vim.api.nvim_set_current_win(win)
  t.eq(win, package.loaded['neovim4eclipse.windows'].editor_window(), 'outside a float: the current window')
end)

t.test('a hidden float entered, or one that opens with the focus hidden, is closed by the one check', function()
  commands()
  local hidden = vim.api.nvim_open_win(buffer(1), false,
    { relative = 'editor', row = 1, col = 1, width = 5, height = 1, hide = true })
  flush()
  t.forget()
  vim.api.nvim_set_current_win(hidden)
  t.eq({ true }, entered_floats(), 'entered: whether it is drawn is close_undrawn\'s to decide')
  vim.v.errmsg = ''
  -- -1: a hidden float's window is not known on its grid (util/Grids.unseenCursor).
  t.eq(true, t.run('CLOSE_UNDRAWN_FLOAT', '-1', 'the limits'))
  t.ok(not vim.api.nvim_win_is_valid(hidden))
  t.ok(vim.v.errmsg:find(': it is hidden, and', 1, true), vim.v.errmsg)
  local opened = vim.api.nvim_open_win(buffer(1), true,
    { relative = 'editor', row = 1, col = 1, width = 5, height = 1, hide = true })
  flush()
  t.ok(vim.api.nvim_win_is_valid(opened), 'the net leaves floats alone')
  t.eq(true, t.run('CLOSE_UNDRAWN_FLOAT', '-1', 'the limits'))
  t.ok(not vim.api.nvim_win_is_valid(opened))
end)

t.test('leaving closes the float named too, the one the cursor was in as the keyboard left', function()
  local win = commands()
  local leave_float = package.loaded['neovim4eclipse.windows'].leave_float
  local prompt = vim.api.nvim_open_win(buffer(1), true, { relative = 'editor', row = 1, col = 1, width = 5, height = 1 })
  vim.api.nvim_set_current_win(win) -- Neovim already in the part that took the keyboard
  t.eq(true, leave_float(prompt))
  t.ok(not vim.api.nvim_win_is_valid(prompt))
  t.eq(win, vim.api.nvim_get_current_win())
  t.eq(false, leave_float(win), "an editor's window is no float to close")
  t.ok(vim.api.nvim_win_is_valid(win))
end)

t.test('a float with the cursor the editor cannot draw is closed with a message', function()
  commands()
  local buf = buffer(1)
  vim.bo[buf].filetype = 'huge'
  local float = vim.api.nvim_open_win(buf, true, { relative = 'editor', row = 1, col = 1, width = 5, height = 1 })
  local close_undrawn = package.loaded['neovim4eclipse.windows'].close_undrawn
  vim.v.errmsg = ''
  t.eq(true, t.run('CLOSE_UNDRAWN_FLOAT', tostring(float), 'the limits, worded by util/Grids'))
  t.ok(not vim.api.nvim_win_is_valid(float))
  t.ok(vim.v.errmsg:find('Closed the floating window of huge: the editor cannot draw it (the limits, worded by util/Grids), and', 1, true), vim.v.errmsg)
  t.eq(false, close_undrawn(float), 'gone already: nothing')
end)

t.test('the one check closes nothing it cannot tell is undrawn', function()
  local win = commands()
  local float = vim.api.nvim_open_win(buffer(1), true, { relative = 'editor', row = 1, col = 1, width = 5, height = 1 })
  t.eq(false, t.run('CLOSE_UNDRAWN_FLOAT', '-1', 'the limits'),
    'drawn, shown, its window not known to the session yet: placed with a later redraw')
  t.eq(false, t.run('CLOSE_UNDRAWN_FLOAT', tostring(win), 'the limits'), 'the session saw another window there')
  t.ok(vim.api.nvim_win_is_valid(float))
  vim.api.nvim_set_current_win(win)
  t.eq(false, t.run('CLOSE_UNDRAWN_FLOAT', tostring(win), 'the limits'), "an editor's window: no float")
  t.ok(vim.api.nvim_win_is_valid(win) and vim.api.nvim_win_is_valid(float))
end)

t.test('a float its plug-in hides while it has the cursor is closed, the message saying so', function()
  commands()
  local float = vim.api.nvim_open_win(buffer(1), true, { relative = 'editor', row = 1, col = 1, width = 5, height = 1 })
  flush()
  vim.api.nvim_win_set_config(float, { hide = true }) -- the cursor stays on its grid, its window forgotten
  vim.v.errmsg = ''
  t.eq(true, t.run('CLOSE_UNDRAWN_FLOAT', '-1', 'the limits'))
  t.ok(not vim.api.nvim_win_is_valid(float))
  t.ok(vim.v.errmsg:find('Closed the floating window of a plug-in: it is hidden', 1, true), vim.v.errmsg)
end)

t.test('a float on a changed buffer closes by leaving, with hidden on; the changes stay', function()
  local win = commands()
  vim.o.hidden = true
  local buf = vim.api.nvim_create_buf(true, false)
  vim.api.nvim_buf_set_name(buf, vim.fn.tempname())
  vim.api.nvim_buf_set_lines(buf, 0, -1, false, { 'typed by the user' })
  local float = vim.api.nvim_open_win(buf, true, { relative = 'editor', row = 1, col = 1, width = 5, height = 1 })
  t.eq(true, package.loaded['neovim4eclipse.windows'].leave_float())
  t.ok(not vim.api.nvim_win_is_valid(float))
  t.eq(win, vim.api.nvim_get_current_win())
  t.ok(vim.api.nvim_buf_is_loaded(buf) and vim.bo[buf].modified, 'hidden, still changed (:ls)')
  t.eq({ 'typed by the user' }, vim.api.nvim_buf_get_lines(buf, 0, -1, false))
  vim.bo[buf].modified = false
  vim.api.nvim_buf_delete(buf, { force = true })
end)

t.test("closing an editor keeps the changes of a float leaving could not close, in a hidden buffer", function()
  local win, _, home_buf = commands()
  vim.o.hidden = false
  local buf = vim.api.nvim_create_buf(true, false)
  vim.api.nvim_buf_set_name(buf, vim.fn.tempname())
  vim.api.nvim_buf_set_lines(buf, 0, -1, false, { 'typed by the user' })
  vim.bo[buf].bufhidden = 'wipe'
  local float = vim.api.nvim_open_win(buf, true, { relative = 'editor', row = 1, col = 1, width = 5, height = 1 })
  t.eq(false, package.loaded['neovim4eclipse.windows'].leave_float(), "refused: 'nohidden' would unload it")
  t.ok(vim.api.nvim_win_is_valid(float))
  t.run('CLOSE_WINDOW', tostring(win), tostring(home_buf))
  t.ok(not vim.api.nvim_win_is_valid(float), 'gone with the tab page')
  t.ok(vim.api.nvim_buf_is_loaded(buf) and vim.bo[buf].modified, 'hidden, still changed (:ls)')
  t.eq({ 'typed by the user' }, vim.api.nvim_buf_get_lines(buf, 0, -1, false))
  vim.o.hidden = true
  vim.api.nvim_buf_delete(buf, { force = true })
end)

t.test("closing an editor leaves its tab's float first, as leaving does, so its plug-in cleans up", function()
  local win, home, home_buf = commands()
  local float = vim.api.nvim_open_win(buffer(1), true, { relative = 'editor', row = 1, col = 1, width = 5, height = 1 })
  local closed = {}
  local group = vim.api.nvim_create_augroup('spec_float_closed', { clear = true })
  vim.api.nvim_create_autocmd('WinClosed', { group = group, callback = function(ev) closed[#closed + 1] = tonumber(ev.match) end })
  t.run('CLOSE_WINDOW', tostring(win), tostring(home_buf))
  t.ok(not vim.api.nvim_win_is_valid(float) and not vim.api.nvim_win_is_valid(win))
  t.eq({ float }, closed, "the float's close was seen (Telescope's cleanup); the editor's stays quiet")
  t.eq(home, vim.api.nvim_get_current_win())
  vim.api.nvim_del_augroup_by_id(group)
end)

t.test('a floating window the user enters later stays, so :q in it stays Neovim\'s', function()
  commands()
  local float = enter_float()
  flush()
  t.ok(vim.api.nvim_win_is_valid(float))
  t.eq(float, vim.api.nvim_get_current_win())
end)

t.test("while Neovim starts, the plug-ins' floats come along to the editor shown; once started they stay", function()
  -- As lazy.nvim's installer and its backdrop: opened in the home window's tab page before any
  -- editor was there. M.show passes starting = (v:vim_did_enter ~= 1); a spec runs past the start,
  -- so it says so itself.
  local home = start()
  local windows = package.loaded['neovim4eclipse.windows']
  local float_at = { relative = 'editor', row = 2, col = 2, width = 10, height = 2 }
  local installer = vim.api.nvim_open_win(vim.api.nvim_create_buf(false, true), false, float_at)
  local on_window = vim.api.nvim_open_win(vim.api.nvim_create_buf(false, true), false,
    { relative = 'win', win = home, row = 1, col = 1, width = 5, height = 1 })
  local editor = open(buffer(3))
  t.eq(editor, show(editor, buffer(3)))
  local editor_tab = vim.api.nvim_win_get_tabpage(editor)
  t.ok(vim.api.nvim_win_get_tabpage(installer) ~= editor_tab, 'past the start (the spec): not moved by M.show')
  windows.bring_start_floats(editor, true)
  t.eq(editor_tab, vim.api.nvim_win_get_tabpage(installer), "starting: in the editor's tab page")
  t.eq({ 2, 2 }, { vim.api.nvim_win_get_config(installer).row, vim.api.nvim_win_get_config(installer).col },
    'where it was on the screen')
  t.ok(vim.api.nvim_win_get_tabpage(on_window) ~= editor_tab, 'one hanging on a window stays with it')
  t.ok(vim.api.nvim_win_get_tabpage(home) ~= editor_tab, "the plug-in's own windows never move")
  t.eq(editor, vim.api.nvim_get_current_win(), 'the focus where it was')
  windows.bring_start_floats(home, false)
  t.eq(editor_tab, vim.api.nvim_win_get_tabpage(installer), 'started: it stays')
  vim.api.nvim_win_close(installer, true)
  vim.api.nvim_win_close(on_window, true)
end)

t.test('a floating window the config left the focus in is left before the home buffer goes anywhere, then settled', function()
  -- The session's order: LEAVE_START_FLOAT, then SHOW_BUFFER with the home buffer, then WINDOWS;
  -- then CLOSE_START_FLOAT, once ui2 shows messages: here Neovim has entered, so it acts at once.
  -- Drawn, the float gets the keyboard back, as in a terminal; hidden, it is closed, with a message.
  t.setup()
  local plain = vim.api.nvim_get_current_win()
  local buf = buffer(1)
  vim.bo[buf].filetype = 'lazy'
  local float = vim.api.nvim_open_win(buf, true, { relative = 'editor', row = 1, col = 1, width = 5, height = 1 })
  t.eq(float, t.run('LEAVE_START_FLOAT'))
  t.ok(vim.api.nvim_win_is_valid(float), 'the float stays, for the session to close')
  t.eq(plain, vim.api.nvim_get_current_win())
  local home_buf = vim.api.nvim_create_buf(false, true)
  t.run('SHOW_BUFFER', tostring(home_buf))
  local home = t.run('WINDOWS', tostring(home_buf))
  t.eq(plain, home, 'the plain window is the home window')
  t.eq(home_buf, vim.api.nvim_win_get_buf(home), 'and shows the home buffer')
  vim.v.errmsg = ''
  t.eq(false, t.run('CLOSE_START_FLOAT', tostring(float)), 'drawn: nothing closed')
  t.eq(home, vim.api.nvim_get_current_win(), 'no editor yet: it waits for the first')
  t.eq('', vim.v.errmsg, 'nothing to say')
  show(home, home_buf)
  t.eq(float, vim.api.nvim_get_current_win(), 'the first editor gives it the keyboard')
  vim.api.nvim_set_current_win(home)
  vim.api.nvim_win_set_config(float, { hide = true })
  t.eq(true, t.run('CLOSE_START_FLOAT', tostring(float)))
  t.ok(not vim.api.nvim_win_is_valid(float))
  t.eq(home, vim.api.nvim_get_current_win())
  local windows = package.loaded['neovim4eclipse.windows']
  local message = windows.float_closed_message('lazy', windows.AT_START)
  t.ok(not message:find(':Lazy', 1, true), 'said of any plug-in, so it names no command of one')
  t.wait_for('the message, said once the call is over', function()
    return vim.v.errmsg:find(message, 1, true) ~= nil
  end)
  t.eq(false, t.run('CLOSE_START_FLOAT', tostring(float)), 'closed already: nothing')
end)

t.test('an undrawn float Neovim will not close says so', function()
  local home = start()
  local windows = package.loaded['neovim4eclipse.windows']
  local function changed_float()
    local changed = vim.api.nvim_create_buf(true, false)
    vim.api.nvim_buf_set_name(changed, vim.fn.tempname())
    vim.api.nvim_buf_set_lines(changed, 0, -1, false, { 'unsaved' })
    vim.bo[changed].bufhidden = 'wipe' -- closing would discard the change, so Neovim refuses
    return vim.api.nvim_open_win(changed, true, { relative = 'editor', row = 1, col = 1, width = 10, height = 1 }),
      changed
  end
  local float, buf = changed_float()
  vim.v.errmsg = ''
  t.eq(false, windows.close_undrawn(float), 'not closed')
  t.eq(home, vim.api.nvim_get_current_win(), 'the keys no longer go to a window nobody sees')
  t.ok(vim.api.nvim_win_is_valid(float), 'the float stays')
  t.eq({ 'unsaved' }, vim.api.nvim_buf_get_lines(buf, 0, -1, false))
  t.ok(vim.v.errmsg:find('Could not close', 1, true), vim.v.errmsg)
  vim.api.nvim_win_close(float, true)
  -- With no editor's window in its tab page Neovim has nowhere to go: the message still says so.
  vim.cmd('noautocmd tabnew')
  float = changed_float()
  vim.v.errmsg = ''
  t.eq(false, windows.close_undrawn(float), 'not closed')
  t.eq(float, vim.api.nvim_get_current_win())
  t.ok(vim.v.errmsg:find('Could not close', 1, true), vim.v.errmsg)
  vim.api.nvim_win_close(float, true)
  vim.cmd('noautocmd tabclose')
end)

t.test("an undrawn float counts as closed though a plug-in's close handler fails", function()
  local home = start()
  local windows = package.loaded['neovim4eclipse.windows']
  for _, event in ipairs({ 'WinClosed', 'BufWinLeave', 'BufHidden' }) do
    local buf = vim.api.nvim_create_buf(false, true)
    local float = vim.api.nvim_open_win(buf, true, { relative = 'editor', row = 1, col = 1, width = 10, height = 1 })
    local handler = vim.api.nvim_create_autocmd(event, {
      buf = event ~= 'WinClosed' and buf or nil,
      pattern = event == 'WinClosed' and tostring(float) or nil,
      callback = function() error('a handler that fails (expected by the test)') end,
    })
    vim.v.errmsg = ''
    t.eq(true, windows.close_undrawn(float), event)
    t.ok(not vim.api.nvim_win_is_valid(float), event .. ': gone')
    t.eq(home, vim.api.nvim_get_current_win(), event)
    t.ok(vim.v.errmsg:find('Closed the floating window', 1, true), vim.v.errmsg)
    pcall(vim.api.nvim_del_autocmd, handler)
  end
end)

t.test('closing the floats that have the keyboard at start never discards a changed buffer', function()
  -- Both kinds close_start_floats closes when the editor does not draw them (here hidden), the
  -- float the config left the focus in (left) and the plug-in's float Neovim is in, each on a file
  -- of the user's with unsaved changes that closing would wipe ('bufhidden' wipe): Neovim refuses,
  -- so both stay, their changes kept, and Neovim goes on in a plain window.
  t.setup()
  local function changed_file()
    local buf = vim.api.nvim_create_buf(true, false)
    vim.api.nvim_buf_set_name(buf, vim.fn.tempname())
    vim.api.nvim_buf_set_lines(buf, 0, -1, false, { 'unsaved' })
    vim.bo[buf].bufhidden = 'wipe'
    t.ok(vim.bo[buf].modified)
    return buf
  end
  local float_at = { relative = 'editor', row = 1, col = 1, width = 10, height = 1 }
  local left_buf = changed_file()
  local left = vim.api.nvim_open_win(left_buf, true, float_at)
  t.eq(left, t.run('LEAVE_START_FLOAT'))
  vim.api.nvim_win_set_config(left, { hide = true })
  local home_buf = vim.api.nvim_create_buf(false, true)
  t.run('SHOW_BUFFER', tostring(home_buf))
  t.run('WINDOWS', tostring(home_buf))
  local current_buf = changed_file()
  local current = vim.api.nvim_open_win(current_buf, true, float_at)
  vim.api.nvim_win_set_config(current, { hide = true })
  t.eq(false, t.run('CLOSE_START_FLOAT', tostring(left)), 'nothing closed')
  for _, f in ipairs({ { left, left_buf }, { current, current_buf } }) do
    t.ok(vim.api.nvim_win_is_valid(f[1]), 'the float stays')
    t.ok(vim.api.nvim_buf_is_valid(f[2]) and vim.bo[f[2]].modified, 'its buffer keeps its changes')
    t.eq({ 'unsaved' }, vim.api.nvim_buf_get_lines(f[2], 0, -1, false))
  end
  t.eq('', vim.api.nvim_win_get_config(0).relative, 'Neovim in a plain window')
  for _, f in ipairs({ left, current }) do
    vim.api.nvim_win_close(f, true)
  end
end)

t.test('a float left at the start goes along with a first editor in a tab page of its own', function()
  -- The usual start: Neovim has entered when CLOSE_START_FLOAT runs, and no editor is shown yet.
  -- One on the screen comes along and gets the keyboard; one hanging on a window cannot, so it
  -- would be undrawn: it is closed, with a message.
  for _, case in ipairs({ { config = { relative = 'editor' }, kept = true }, { config = { relative = 'win' }, kept = false } }) do
    t.setup()
    local buf = buffer(1)
    vim.bo[buf].filetype = 'lazy'
    local float = vim.api.nvim_open_win(buf, true, vim.tbl_extend('force',
      { row = 1, col = 1, width = 5, height = 1 }, case.config))
    t.eq(float, t.run('LEAVE_START_FLOAT'))
    local home_buf = vim.api.nvim_create_buf(false, true)
    t.run('SHOW_BUFFER', tostring(home_buf))
    t.run('WINDOWS', tostring(home_buf))
    t.eq(false, t.run('CLOSE_START_FLOAT', tostring(float)))
    vim.v.errmsg = ''
    local a = buffer(3)
    local win = open(a)
    show(win, a)
    if case.kept then
      t.eq(float, vim.api.nvim_get_current_win(), 'in the float, as in a terminal')
      t.eq(vim.api.nvim_win_get_tabpage(win), vim.api.nvim_win_get_tabpage(float), "in the editor's tab page")
      vim.api.nvim_win_close(float, true)
    else
      t.ok(not vim.api.nvim_win_is_valid(float), 'closed: the editor could not draw it')
      t.eq(win, vim.api.nvim_get_current_win())
      t.wait_for('the message', function() return vim.v.errmsg:find('Closed the floating window', 1, true) ~= nil end)
    end
  end
end)

t.test('a start float in Insert mode that closes on leaving does not carry its insert into the document', function()
  t.setup()
  local home_buf = vim.api.nvim_create_buf(false, true)
  t.run('SHOW_BUFFER', tostring(home_buf))
  t.run('WINDOWS', tostring(home_buf))
  local float_buf = buffer(1)
  local float = vim.api.nvim_open_win(float_buf, true, { relative = 'editor', row = 1, col = 1, width = 9, height = 1 })
  vim.api.nvim_create_autocmd('BufLeave', { buf = float_buf, once = true, nested = true,
    callback = function() vim.schedule(function() pcall(vim.api.nvim_win_close, float, true) end) end })
  t.eq(false, t.run('CLOSE_START_FLOAT', '0'))
  local doc = buffer(3)
  -- The first editor is shown during the float's insert; the z after it would be typed into the
  -- document if that insert went on there.
  vim.keymap.set('i', '<F6>', function()
    local win = open(doc)
    vim.api.nvim_win_close(float, true) -- closed by its plug-in as Neovim leaves it
    show(win, doc)
  end)
  vim.api.nvim_feedkeys(vim.keycode('i<F6>z<Esc>'), 'mx', false)
  vim.keymap.del('i', '<F6>')
  t.eq({ 'line 1', 'line 2', 'line 3' }, vim.api.nvim_buf_get_lines(doc, 0, -1, false))
  t.eq('n', vim.api.nvim_get_mode().mode)
end)

t.test('a float opened after the start check does not carry its insert into the first editor', function()
  t.setup()
  local home_buf = vim.api.nvim_create_buf(false, true)
  t.run('SHOW_BUFFER', tostring(home_buf))
  t.run('WINDOWS', tostring(home_buf))
  t.eq(false, t.run('CLOSE_START_FLOAT', '0'), 'nothing there yet, so nothing is waiting')
  vim.api.nvim_open_win(buffer(1), true, { relative = 'editor', row = 1, col = 1, width = 9, height = 1 })
  local doc = buffer(3)
  vim.keymap.set('i', '<F6>', function()
    local win = open(doc)
    show(win, doc)
  end)
  vim.api.nvim_feedkeys(vim.keycode('i<F6>z<Esc>'), 'mx', false)
  vim.keymap.del('i', '<F6>')
  t.eq({ 'line 1', 'line 2', 'line 3' }, vim.api.nvim_buf_get_lines(doc, 0, -1, false))
  t.eq('n', vim.api.nvim_get_mode().mode)
end)

-- 'o' on an indented line, nothing typed: as the insert ends, Neovim removes the white space at the
-- cursor of the window it is in. Ended in the float (the first of the two steps), that is the float's.
for _, leave in ipairs({ 'move_out_of_float', 'leave_float' }) do
  for _, text in ipairs({ { '    keep indent', 'two' }, { '    ', '  ' } }) do
    t.test("ending a float's auto-indented insert keeps the document's line: " .. leave .. ', ' .. text[2], function()
      local win = commands()
      local doc = vim.api.nvim_win_get_buf(win)
      vim.api.nvim_buf_set_lines(doc, 0, -1, false, text)
      vim.api.nvim_win_set_cursor(win, { 1, 0 })
      local b = buffer(1)
      vim.api.nvim_buf_set_lines(b, 0, -1, false, { '  indented' })
      vim.bo[b].autoindent = true
      vim.api.nvim_open_win(b, true, { relative = 'editor', row = 3, col = 1, width = 20, height = 3 })
      local windows = package.loaded['neovim4eclipse.windows']
      vim.keymap.set('i', '<F6>', function() t.eq(true, windows.end_float_insert('move', -1)) end)
      vim.keymap.set('n', '<F7>', function() windows[leave](-1) end)
      vim.api.nvim_feedkeys(vim.keycode('o<F6><F7>'), 'mx', false)
      vim.keymap.del('i', '<F6>')
      vim.keymap.del('n', '<F7>')
      t.eq(text, vim.api.nvim_buf_get_lines(doc, 0, -1, false))
      t.eq(win, vim.api.nvim_get_current_win())
      t.eq({ 1, 0 }, vim.api.nvim_win_get_cursor(win), "the document's cursor stays")
      t.eq('n', vim.api.nvim_get_mode().mode)
    end)
  end
end

-- The first step (END_FLOAT_INSERT) is told the route and the arguments of the script sent next,
-- and ends the float's insert only where that script leaves the float (the windows module's
-- leaves): a route that leaves Neovim where it is must not turn the next letters into commands.
t.test("the first step ends a float's insert only where the next script leaves the float", function()
  local win = commands()
  local doc = vim.api.nvim_win_get_buf(win)
  local other_doc = buffer(2)
  local other = open(other_doc) -- another editor, in a tab page of its own
  vim.api.nvim_set_current_win(win)
  local named = vim.api.nvim_open_win(buffer(1), false, { relative = 'editor', row = 4, col = 1, width = 9, height = 1 })
  local float = enter_float()
  local answers
  vim.keymap.set('i', '<F6>', function()
    answers = {
      t.run('END_FLOAT_INSERT', 'close', tostring(other), tostring(doc)), -- a background editor closes
      t.run('END_FLOAT_INSERT', 'release', tostring(other_doc), tostring(doc)), -- and its document
      t.run('END_FLOAT_INSERT', 'move', tostring(named)), -- another float named: Neovim went on since
      t.run('END_FLOAT_INSERT', 'undrawn', '-1', 'past the limits'), -- drawn: another window's grid
      t.run('END_FLOAT_INSERT', 'show', tostring(other), '99999'), -- a wiped buffer: no show
      t.run('END_FLOAT_INSERT', 'focus', '99999'), -- a page's window closed in Neovim
      vim.api.nvim_get_mode().mode,
    }
  end)
  vim.api.nvim_feedkeys(vim.keycode('i<F6>z<Esc>'), 'mx', false)
  vim.keymap.del('i', '<F6>')
  t.eq({ false, false, false, false, false, false, 'i' }, answers)
  t.eq({ 'zline 1' }, vim.api.nvim_buf_get_lines(vim.api.nvim_win_get_buf(float), 0, -1, false),
    'the insert went on in the float')
  -- Where the script leaves the float, the insert ends.
  for _, route in ipairs({
    { 'close', tostring(win), tostring(doc) },
    { 'release', tostring(doc), tostring(doc) },
    { 'move', '-1' },
    { 'move', tostring(float) },
    { 'undrawn', tostring(float), 'past the limits' },
    { 'show', tostring(other), tostring(other_doc) },
    { 'focus', tostring(other) },
  }) do
    local answer
    vim.keymap.set('i', '<F6>', function() answer = t.run('END_FLOAT_INSERT', unpack(route)) end)
    vim.api.nvim_feedkeys(vim.keycode('i<F6>'), 'mx', false)
    vim.keymap.del('i', '<F6>')
    t.eq(true, answer, table.concat(route, ' '))
    t.eq(float, vim.api.nvim_get_current_win(), 'the first step only ends the insert')
  end
end)

-- A float on a document another editor has open (a peek window), the user typing in it, and that
-- editor closing in the background: the release closes the float too and leaves for the home
-- window, so step one must count the float itself, or the insert goes on in the home window's
-- document. Typed: 'Z' after an insert; or 'o', only auto-indented, on a line of only white space.
for _, case in ipairs({ { keys = 'i', after = 'Z', text = { 'line 1', 'line 2', 'line 3' } },
    { keys = 'o', after = '', text = { '\t\t' } } }) do
  t.test("releasing the buffer a float shows ends the float's insert first: " .. case.keys, function()
    local win = commands()
    local a = vim.api.nvim_win_get_buf(win)
    vim.api.nvim_buf_set_lines(a, 0, -1, false, case.text)
    local b = buffer(2)
    vim.bo[b].autoindent = true
    vim.api.nvim_buf_set_lines(b, 0, -1, false, { '  peek' })
    open(b) -- b's own editor, in a tab page of its own
    vim.api.nvim_set_current_win(win)
    vim.api.nvim_open_win(b, true, { relative = 'editor', row = 3, col = 1, width = 20, height = 3 })
    local answer
    vim.keymap.set('i', '<F6>', function() answer = t.run('END_FLOAT_INSERT', 'release', tostring(b), tostring(a)) end)
    vim.keymap.set({ 'i', 'n' }, '<F7>', function() t.run('RELEASE_BUFFER', tostring(b), tostring(a)) end)
    vim.api.nvim_feedkeys(vim.keycode(case.keys .. '<F6><F7>' .. case.after .. '<Esc>'), 'mx', false)
    vim.keymap.del('i', '<F6>')
    vim.keymap.del({ 'i', 'n' }, '<F7>')
    t.eq(true, answer)
    t.eq(a, vim.api.nvim_get_current_buf(), "the home window shows the attached document")
    t.eq(case.text, vim.api.nvim_buf_get_lines(a, 0, -1, false))
  end)
end

-- After Ctrl-O in an insert, Neovim reports Visual or Select mode while the insert waits to go on:
-- leaving the float then must end that wait, or the insert goes on in the document.
for _, keys in ipairs({ 'v', 'gh' }) do
  t.test('an insert waiting after Ctrl-O ' .. keys .. ' in a float does not go on in the document', function()
    local win = commands()
    local doc = vim.api.nvim_win_get_buf(win)
    enter_float()
    local windows = package.loaded['neovim4eclipse.windows']
    local answer
    vim.keymap.set({ 'x', 's' }, '<F6>', function() answer = t.run('END_FLOAT_INSERT', 'move', '-1') end)
    vim.keymap.set({ 'x', 's' }, '<F7>', function() windows.move_out_of_float(-1) end)
    -- The space would be typed into the document if the insert went on there.
    vim.api.nvim_feedkeys(vim.keycode('i<C-o>' .. keys .. '<F6><F7><Space><Esc>'), 'mx', false)
    vim.keymap.del({ 'x', 's' }, '<F6>')
    vim.keymap.del({ 'x', 's' }, '<F7>')
    t.eq(true, answer)
    t.eq(win, vim.api.nvim_get_current_win())
    t.eq({ 'line 1', 'line 2', 'line 3' }, vim.api.nvim_buf_get_lines(doc, 0, -1, false))
  end)
end

-- A plug-in that takes Neovim out of its float during an insert ('o', nothing typed yet): the
-- insert ends from the document's window, its cursor waiting on text so that the document's line
-- keeps its indentation (stop_insert_in_place), and back where it was afterwards. Eclipse's route
-- after it: the editor shown again, or a click naming the float Neovim was in.
for _, route in ipairs({ 'show', 'move' }) do
  t.test("an auto-indented insert a plug-in carries out of its float keeps the document's indentation: " .. route, function()
    local win = commands()
    local doc = vim.api.nvim_win_get_buf(win)
    vim.api.nvim_buf_set_lines(doc, 0, -1, false, { '    keep indent', 'two' })
    vim.api.nvim_win_set_cursor(win, { 1, 0 })
    local b = buffer(1)
    vim.api.nvim_buf_set_lines(b, 0, -1, false, { '  indented' })
    vim.bo[b].autoindent = true
    local float = vim.api.nvim_open_win(b, true, { relative = 'editor', row = 3, col = 1, width = 20, height = 3 })
    local windows = package.loaded['neovim4eclipse.windows']
    vim.keymap.set('i', '<F6>', function() vim.api.nvim_set_current_win(win) end) -- the plug-in's move
    vim.keymap.set('i', '<F7>', function()
      if route == 'show' then
        show(win, doc)
      else
        windows.move_out_of_float(float)
      end
    end)
    vim.api.nvim_feedkeys(vim.keycode('o<F6><F7>'), 'mx', false)
    vim.keymap.del('i', '<F6>')
    vim.keymap.del('i', '<F7>')
    t.eq({ '    keep indent', 'two' }, vim.api.nvim_buf_get_lines(doc, 0, -1, false))
    t.eq({ 1, 0 }, vim.api.nvim_win_get_cursor(win), "the document's cursor stays")
    t.eq('n', vim.api.nvim_get_mode().mode)
  end)
end

-- close_start_floats on a start float the editor does not draw (in another tab page than the
-- attached editor's), Neovim in it in an insert that has only auto-indented ('o'): both branches,
-- the float the config left the focus in closed with Neovim back in it (left, as lazy.nvim's
-- VimEnter does), and the one Neovim is in left (current). Neovim then goes to the attached
-- editor's window, where the insert ends: its document keeps its indentation and its cursor, and
-- the window Neovim left the float for keeps its own. And one hidden in the editor's own tab page
-- (hidden here), left for the editor's window, where both the leave and the close's own check stop
-- the insert: the second changes nothing, so the caret stays too. Eclipse's answer
-- (eclipse_attached_window) is the editor's window here.
for _, branch in ipairs({ 'left', 'current', 'hidden here' }) do
  t.test("an insert close_start_floats ends keeps the attached document's indentation: " .. branch, function()
    local win, home, home_buf = commands()
    local doc = vim.api.nvim_win_get_buf(win)
    vim.api.nvim_buf_set_lines(doc, 0, -1, false, { '    keep indent', 'two' })
    vim.api.nvim_win_set_cursor(win, { 1, 0 })
    vim.api.nvim_buf_set_lines(home_buf, 0, -1, false, { '  home indent' })
    vim.api.nvim_set_current_win(home)
    vim.api.nvim_win_set_cursor(home, { 1, 0 })
    t.ok(vim.api.nvim_win_get_tabpage(home) ~= vim.api.nvim_win_get_tabpage(win), 'the float not in the editor\'s tab page')
    local b = buffer(1)
    vim.api.nvim_buf_set_lines(b, 0, -1, false, { '  indented' })
    vim.bo[b].autoindent = true
    local config = { relative = 'editor', row = 3, col = 1, width = 20, height = 3 }
    if branch == 'hidden here' then
      vim.api.nvim_set_current_win(win)
      config.hide = true
    end
    local float = vim.api.nvim_open_win(b, true, config)
    local rpcrequest = vim.rpcrequest
    vim.rpcrequest = function(chan, method, ...)
      if method == 'eclipse_attached_window' then
        return win
      end
      return rpcrequest(chan, method, ...)
    end
    local ok, err = pcall(function()
      vim.keymap.set('i', '<F7>', function()
        t.run('CLOSE_START_FLOAT', branch == 'left' and tostring(float) or '0')
      end)
      vim.api.nvim_feedkeys(vim.keycode('o<F7>'), 'mx', false)
    end)
    vim.rpcrequest = rpcrequest
    pcall(vim.keymap.del, 'i', '<F7>')
    t.ok(ok, err)
    t.ok(not vim.api.nvim_win_is_valid(float), 'the float closed')
    t.eq(win, vim.api.nvim_get_current_win(), "Neovim in the attached editor's window")
    t.eq('n', vim.api.nvim_get_mode().mode, 'the insert over')
    t.eq({ '    keep indent', 'two' }, vim.api.nvim_buf_get_lines(doc, 0, -1, false))
    t.eq({ 1, 0 }, vim.api.nvim_win_get_cursor(win), "the document's cursor stays")
    t.eq({ '  home indent' }, vim.api.nvim_buf_get_lines(home_buf, 0, -1, false))
    t.eq({ 1, 0 }, vim.api.nvim_win_get_cursor(home), "the home window's cursor back too")
  end)
end

-- Closing the editor Neovim is in, or whose float Neovim is in, during an insert begun in a float
-- that has only auto-indented ('o'), with no first step (Eclipse's, end_float_insert, failed):
-- carried, the plug-in moved Neovim into the editor's window; in the float, Neovim still there.
-- The insert ends in the home window, where the close leaves Neovim, its buffer (fallback)
-- keeping its indentation, and so does the document.
for _, where in ipairs({ 'carried', 'in the float' }) do
  t.test("an insert going on as its editor closes keeps the home buffer's indentation: " .. where, function()
    local win, home = commands()
    local doc = vim.api.nvim_win_get_buf(win)
    vim.api.nvim_buf_set_lines(doc, 0, -1, false, { '    keep indent', 'two' })
    vim.api.nvim_win_set_cursor(win, { 1, 0 })
    local fallback = buffer(1)
    vim.api.nvim_buf_set_lines(fallback, 0, -1, false, { '    fallback' })
    local b = buffer(1)
    vim.api.nvim_buf_set_lines(b, 0, -1, false, { '  indented' })
    vim.bo[b].autoindent = true
    vim.api.nvim_open_win(b, true, { relative = 'editor', row = 3, col = 1, width = 20, height = 3 })
    vim.keymap.set('i', '<F6>', function() vim.api.nvim_set_current_win(win) end) -- the plug-in's move
    vim.keymap.set('i', '<F7>', function() t.run('CLOSE_WINDOW', tostring(win), tostring(fallback)) end)
    vim.api.nvim_feedkeys(vim.keycode(where == 'carried' and 'o<F6><F7>' or 'o<F7>'), 'mx', false)
    vim.keymap.del('i', '<F6>')
    vim.keymap.del('i', '<F7>')
    t.eq(home, vim.api.nvim_get_current_win(), 'Neovim in the home window')
    t.eq(fallback, vim.api.nvim_win_get_buf(home))
    t.eq('n', vim.api.nvim_get_mode().mode, 'the insert over')
    t.eq({ '    fallback' }, vim.api.nvim_buf_get_lines(fallback, 0, -1, false))
    t.eq({ 1, 0 }, vim.api.nvim_win_get_cursor(home), "the home window's cursor back")
    t.eq({ '    keep indent', 'two' }, vim.api.nvim_buf_get_lines(doc, 0, -1, false))
  end)
end

t.test('moving out of a float leaves it open, unless its plug-in closes it on leaving', function()
  local win = commands()
  local windows = package.loaded['neovim4eclipse.windows']
  t.eq(false, windows.move_out_of_float(-1), "nothing to move out of in the editor's window")
  local float = enter_float()
  t.eq(true, windows.move_out_of_float(-1))
  t.eq(win, vim.api.nvim_get_current_win(), "Neovim in the editor's window")
  t.ok(vim.api.nvim_win_is_valid(float), 'the float stays, as after a click outside it in a terminal')
  vim.api.nvim_set_current_win(float)
  vim.api.nvim_set_current_win(win)
  t.eq(false, windows.move_out_of_float(float), 'named, and Neovim has moved on: nothing to move')
  t.ok(vim.api.nvim_win_is_valid(float), 'and it still stays')
  vim.api.nvim_win_close(float, true)
  -- A picker: Telescope closes its windows on BufLeave.
  local picker = enter_float()
  vim.api.nvim_create_autocmd('BufLeave', { buf = vim.api.nvim_win_get_buf(picker), once = true,
    callback = function() vim.schedule(function() pcall(vim.api.nvim_win_close, picker, true) end) end })
  t.eq(true, windows.move_out_of_float(-1))
  t.wait_for('the plug-in closed its window', function() return not vim.api.nvim_win_is_valid(picker) end)
  t.eq(win, vim.api.nvim_get_current_win())
end)

t.test('without a floating window at start there is nothing to leave', function()
  t.setup()
  local win = vim.api.nvim_get_current_win()
  t.eq(0, t.run('LEAVE_START_FLOAT'))
  t.eq(win, vim.api.nvim_get_current_win())
end)

t.test('only floating and special windows are side windows', function()
  local win, home = commands()
  local float = enter_float()
  local side = package.loaded['neovim4eclipse.windows'].is_side_window
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
  t.eq({}, t.commands())
  t.eq({}, t.sent('eclipse_buffer_closed'), 'no QuitPre report either')
  t.type(':q<CR>') -- in the editor's window again: Eclipse's
  t.eq({ { 'quit', false, {} } }, t.commands())
end)

-- A count in front reaches Eclipse already turned into one of Neovim's hidden tab pages, which says
-- nothing about the editor tabs; the tab written after the command does.
t.test(':tabclose 2 names an editor tab; a count in front is refused; :tabclose alone closes the editor', function()
  commands()
  local tabs = #vim.api.nvim_list_tabpages()
  for _, line in ipairs({ 'tabclose 2', 'tabclose +', 'tabc! $', 'tabclose -2' }) do
    t.type(':' .. line .. '<CR>')
  end
  t.eq({
    { 'window', 'tabclose', '2', '0' },
    { 'window', 'tabclose', '+', '0' },
    { 'window', 'tabclose', '$', '1' },
    { 'window', 'tabclose', '-2', '0' },
  }, sent_windows())
  t.forget()
  for _, line in ipairs({ '1tabclose', '$tabc', 'tabclose x' }) do
    vim.v.errmsg = ''
    t.type(':' .. line .. '<CR>')
    t.ok(vim.v.errmsg:find('tabclose', 1, true), line .. ': ' .. vim.v.errmsg)
  end
  t.eq(tabs, #vim.api.nvim_list_tabpages(), 'Neovim closed no tab')
  t.eq({}, t.commands())
  t.type(':tabclose<CR>')
  t.eq({ { 'tabclose', false, {} } }, t.commands(), 'the refusal of the earlier line is not left over')
end)

t.test("the plug-in's own closes forget the window, though they fire no WinClosed", function()
  local win, home, home_buf = commands()
  local side = package.loaded['neovim4eclipse.windows'].is_side_window
  t.ok(not side(win), 'one of the plug-in\'s windows while open')
  t.run('CLOSE_WINDOW', tostring(win), tostring(home_buf))
  -- A closed window's type is 'unknown': it counts as the plug-in's no more.
  t.ok(side(win), 'forgotten')
  t.eq(home, vim.api.nvim_get_current_win())
  t.eq({}, t.sent('eclipse_window_closed'))
end)

-- Pages' windows (open_page): a page editor shown from this Neovim, as the messages page will be.

local function open_page(buf)
  return t.run('OPEN_PAGE_WINDOW', tostring(buf))
end

t.test('a page window opens quietly in a tab of its own, with a page\'s options', function()
  local home = start()
  vim.wo.number, vim.wo.signcolumn, vim.wo.list, vim.wo.wrap = true, 'yes', true, false
  local fired = {}
  vim.api.nvim_create_autocmd({ 'WinNew', 'BufWinEnter', 'TabNew' }, {
    callback = function(ev) fired[#fired + 1] = ev.event end,
  })
  local page = buffer(3)
  local win = open_page(page)
  t.eq(home, vim.api.nvim_get_current_win(), 'not entered')
  t.eq({}, fired, "no autocommand: the user's would run with the editor's window current")
  t.ok(vim.api.nvim_win_get_tabpage(win) ~= vim.api.nvim_win_get_tabpage(home), 'a tab of its own')
  t.eq({ false, 'no', false, true }, { vim.wo[win].number, vim.wo[win].signcolumn, vim.wo[win].list, vim.wo[win].wrap },
    "a page's options, not the copied ones")
  t.eq({ true, 'yes' }, { vim.wo[home].number, vim.wo[home].signcolumn }, "the editor's stay")
  t.eq(true, package.loaded['neovim4eclipse.windows'].is_page(win))
  t.eq(false, package.loaded['neovim4eclipse.windows'].is_page(home))
  t.eq(-2, open_page(99999), 'a buffer that is gone')
end)

t.test('the WinNew net leaves a page window, and :q in it is no side window\'s', function()
  start()
  local win = open_page(buffer(3))
  vim.api.nvim_set_current_win(win) -- with autocommands, as the focus moving there does
  vim.cmd('doautocmd WinNew') -- the net looks at every window it has not seen
  vim.wait(50)
  t.ok(vim.api.nvim_win_is_valid(win), 'still open')
  t.eq(false, package.loaded['neovim4eclipse.windows'].is_side_window(win), ':q is the page\'s host command')
end)

t.test('restore_page puts the page back after Neovim put another buffer in its window', function()
  start()
  local page = buffer(3)
  local win = open_page(page)
  local other = buffer(5)
  vim.api.nvim_win_set_buf(win, other)
  t.eq(page, t.run('RESTORE_PAGE_WINDOW', tostring(win)))
  t.eq(page, vim.api.nvim_win_get_buf(win))
  t.eq(-1, t.run('RESTORE_PAGE_WINDOW', tostring(vim.api.nvim_get_current_win())), 'no page window')
end)

t.test("in a page's buffer the IDE keys are Neovim's, but gt stays Eclipse's", function()
  start()
  local page = buffer(3)
  vim.api.nvim_buf_set_lines(page, 0, -1, false, { 'local foo = 1', '', 'print(foo)' })
  local win = open_page(page)
  vim.api.nvim_set_current_win(win)
  vim.api.nvim_win_set_cursor(win, { 3, 7 })
  t.forget()
  t.type('gd')
  t.eq({ 1, 6 }, vim.api.nvim_win_get_cursor(win), "Neovim's gd: to the first foo")
  t.eq({}, t.sent('eclipse_action'), 'not Eclipse\'s definition')
  local grn = vim.fn.maparg('grn', 'n', false, true)
  t.eq(1, grn.buffer, 'grn is mapped for the page buffer')
  t.ok(grn.desc ~= nil and not grn.desc:find('Eclipse', 1, true), "and it is Neovim's own default again: " .. tostring(grn.desc))
  t.type('gt')
  t.eq({ { 'next_tab', 0 } }, t.sent('eclipse_action'), 'gt moves among the editor tabs')
end)

-- PAGE_TEXT: a page's text in the editing Neovim (the messages page).

local function page_text(lines, highlights, cursor)
  return t.run('PAGE_TEXT', 'messages', lines, highlights or {}, cursor or 'end')
end

t.test('PAGE_TEXT makes the page buffer once, quietly, and fills it read-only', function()
  start()
  local fired = {}
  vim.api.nvim_create_autocmd({ 'BufNew', 'BufAdd', 'BufFilePost', 'TextChanged' }, {
    callback = function(ev) fired[#fired + 1] = ev.event end,
  })
  local buf = page_text({ 'E486: Pattern not found: x', '3 lines yanked' }, { { 0, 'ErrorMsg' } })
  t.eq({}, fired, "no autocommand: the user's and the plug-in's buffer hooks are about files")
  t.eq('neovim4eclipse://messages', vim.api.nvim_buf_get_name(buf))
  t.eq({ 'nofile', false, false, false }, { vim.bo[buf].buftype, vim.bo[buf].modifiable, vim.bo[buf].buflisted, vim.bo[buf].swapfile })
  t.eq({ 'E486: Pattern not found: x', '3 lines yanked' }, vim.api.nvim_buf_get_lines(buf, 0, -1, false))
  local marks = vim.api.nvim_buf_get_extmarks(buf, -1, 0, -1, { details = true })
  t.eq(1, #marks)
  t.eq({ 0, 'ErrorMsg' }, { marks[1][2], marks[1][4].line_hl_group })
  t.eq(buf, page_text({ 'only one now' }), 'the same buffer again')
  t.eq({ 'only one now' }, vim.api.nvim_buf_get_lines(buf, 0, -1, false))
  t.eq(0, #vim.api.nvim_buf_get_extmarks(buf, -1, 0, -1, {}), 'the old colors are gone')
end)

t.test("q in the page's window asks Eclipse to close the page, naming the window", function()
  start()
  t.run('HOST_COMMANDS', t.host_rules)
  local buf = page_text({ 'one', 'two', 'three' }, {}, 'end')
  local win = open_page(buf)
  vim.api.nvim_set_current_win(win)
  page_text({ 'one', 'two', 'three', 'four' }, {}, 'end')
  t.eq(4, vim.api.nvim_win_get_cursor(win)[1], ':messages again: the cursor on the newest message')
  local second = open_page(buf)
  t.eq(4, vim.api.nvim_win_get_cursor(second)[1], 'a window opened after the fill: on the newest message too')
  t.forget()
  t.type('q')
  local sent = t.sent('eclipse_command')
  t.eq({ 'quit', win }, { sent[1][1], sent[1][4] })
  t.ok(vim.api.nvim_win_is_valid(win), 'Neovim closed nothing itself')
end)

t.test('a q the user mapped for the page buffer is kept', function()
  start()
  local buf = page_text({ 'one' })
  vim.keymap.set('n', 'q', '<Cmd>let g:mine = 1<CR>', { buffer = buf })
  page_text({ 'two' })
  t.eq('<Cmd>let g:mine = 1<CR>', vim.api.nvim_buf_call(buf, function() return vim.fn.maparg('q', 'n') end))
end)

t.test("entering a page gives its window the attached editor's folder", function()
  start()
  local function folder_of(w)
    return cwd(w)[1]
  end
  local code = open(buffer(3), vim.fn.fnamemodify(vim.fn.tempname(), ':h'))
  local folder = folder_of(code)
  local page = open_page(page_text({ 'one' }))
  -- The page starts in the root folder: '/' on Linux and macOS, the drive's root (C:\) on Windows,
  -- so the setup only checks that it differs from the code's folder, by the one rule for paths.
  vim.api.nvim_win_call(page, function() vim.fn.chdir('/', 'window') end)
  t.ok(not package.loaded['neovim4eclipse.paths'].same(folder, folder_of(page)), 'the page starts elsewhere')
  t.eq(page, t.run('FOCUS_WINDOW', tostring(page), tostring(code)))
  t.same_file(folder, folder_of(page), ":e b.txt in the page means the code's b.txt")
  t.eq(-1, t.run('FOCUS_WINDOW', '99999', tostring(code)), 'a window that is gone')
end)

t.test('a buffer that only took the page\'s name is not taken for the page', function()
  start()
  local other = vim.api.nvim_create_buf(true, false)
  vim.api.nvim_buf_set_name(other, 'neovim4eclipse://messages')
  vim.api.nvim_buf_set_lines(other, 0, -1, false, { 'not the page' })
  local buf = page_text({ 'the page' })
  t.ok(buf ~= other, 'a buffer of its own')
  t.eq({ 'not the page' }, vim.api.nvim_buf_get_lines(other, 0, -1, false), 'the other buffer is untouched')
  t.eq(buf, page_text({ 'again' }), 'and the page is found again by its mark')
end)

t.test("a folder with $ in its name is not given: chdir() would expand it, even for a moment", function()
  start()
  local root = vim.fn.tempname()
  vim.fn.mkdir(root .. '/e$HOME', 'p')
  local expanded = root .. '/e' .. vim.env.HOME
  vim.fn.mkdir(expanded, 'p') -- where chdir() would land, expanding $HOME
  local code = open(buffer(3), root)
  local page = open_page(page_text({ 'one' }))
  local visited = {}
  local au = vim.api.nvim_create_autocmd('DirChanged', { callback = function()
    visited[#visited + 1] = vim.v.event.cwd
  end })
  t.eq(false, package.loaded['neovim4eclipse.windows'].set_folder(page, root .. '/e$HOME'))
  t.eq({}, visited, 'no chdir() at all, so no DirChanged sees the expanded folder')
  t.ok(not package.loaded['neovim4eclipse.paths'].same(cwd(page)[1], expanded), 'not the folder the name expands to: ' .. cwd(page)[1])
  vim.api.nvim_del_autocmd(au)
  t.eq(true, package.loaded['neovim4eclipse.windows'].set_folder(page, root), 'a plain folder is set')
  t.same_file(root, cwd(page)[1])
  local editor = open(buffer(3), root .. '/e$HOME')
  t.ok(not package.loaded['neovim4eclipse.paths'].same(cwd(editor)[1], expanded), "an editor's window does not land there either")
  t.ok(code ~= editor)
  vim.fn.delete(root, 'rf')
end)

t.test("an editor's window gets a local folder even when Neovim's folder is that folder already", function()
  start()
  local root = vim.fn.resolve(vim.fn.tempname())
  vim.fn.mkdir(root, 'p')
  vim.cmd.cd(root) -- the global folder, as when Eclipse was started in the project
  local editor = open(buffer(3), root)
  t.same_file(root, cwd(editor)[1])
  t.eq(1, cwd(editor)[2], 'a window-local folder, so a later :cd elsewhere does not move it')
  vim.cmd.cd('/')
  t.same_file(root, cwd(editor)[1], 'still the project folder after a global :cd')
  vim.fn.delete(root, 'rf')
end)

t.test('every write of the page that runs autocommands is refused, and leaves no file', function()
  start()
  local buf = page_text({ 'one', 'two' })
  local win = open_page(buf)
  vim.api.nvim_set_current_win(win)
  local dir = vim.fn.tempname()
  vim.fn.mkdir(dir, 'p')
  vim.fn.writefile({ 'kept' }, dir .. '/old.txt')
  for _, cmd in ipairs({ 'w ' .. dir .. '/a.txt', '1w ' .. dir .. '/b.txt', 'w >> ' .. dir .. '/old.txt',
                         'w! ' .. dir .. '/old.txt', 'w' }) do
    local ok, err = pcall(vim.cmd, cmd)
    t.eq(false, ok, ':' .. cmd .. ' is refused')
    t.ok(tostring(err):find('E382', 1, true), ':' .. cmd .. ': ' .. tostring(err))
    t.ok(not tostring(err):find('traceback', 1, true), 'Vim\'s own words, no Lua stack: ' .. tostring(err))
  end
  t.eq(0, vim.fn.filereadable(dir .. '/a.txt'))
  t.eq(0, vim.fn.filereadable(dir .. '/b.txt'))
  t.eq({ 'kept' }, vim.fn.readfile(dir .. '/old.txt'), 'an existing file is untouched')
  vim.fn.delete(dir, 'rf')
end)

-- Help and checkhealth pages: a page window on a buffer Neovim made itself (show_page), which a help
-- page changes as Neovim's help moves on (follow_page).

local function help_buffer(topic)
  vim.cmd('help ' .. topic)
  local buf, line = vim.api.nvim_get_current_buf(), vim.fn.line('.')
  vim.cmd('helpclose')
  return buf, line
end

local function show_page(win, buf, line, kind)
  return t.run('SHOW_PAGE_BUFFER', tostring(win), tostring(buf), tostring(line), kind)
end

t.test('a help page opens in a page window with its topic\'s line at the top', function()
  local home = start()
  local buf, line = help_buffer('help-context')
  local win = show_page(-1, buf, line, 'help')
  t.ok(win > 0, 'a window')
  t.eq(true, package.loaded['neovim4eclipse.windows'].is_page(win))
  t.eq(buf, vim.api.nvim_win_get_buf(win))
  t.eq(line, vim.api.nvim_win_call(win, function() return vim.fn.line('w0') end), 'the topic at the top, as :help puts it')
  t.eq(home, vim.api.nvim_get_current_win(), 'not entered')
  t.eq(-2, show_page(-1, 99999, 1, 'help'), 'a buffer that is gone')
end)

t.test('the help page shows the next help in the same window', function()
  start()
  local first, l1 = help_buffer('help-context')
  local win = show_page(-1, first, l1, 'help')
  local second, l2 = help_buffer('options')
  t.eq(win, show_page(win, second, l2, 'help'), 'the same window')
  t.eq(second, vim.api.nvim_win_get_buf(win))
  t.ok(vim.api.nvim_buf_is_valid(first), 'the page before stays, for Ctrl-T and Ctrl-O')
  t.eq(second, t.run('RESTORE_PAGE_WINDOW', tostring(win)), 'the page is the new help now')
end)

t.test('a new report replaces the old one, which is wiped when no window shows it', function()
  start()
  local function report()
    local buf = vim.api.nvim_create_buf(false, true) -- as :checkhealth makes one: scratch, its filetype
    vim.bo[buf].filetype = 'checkhealth'
    vim.api.nvim_buf_set_lines(buf, 0, -1, false, { 'vim.health: OK' })
    return buf
  end
  local old = report()
  local win = show_page(-1, old, 1, 'checkhealth')
  local new = report()
  t.eq(win, show_page(win, new, 1, 'checkhealth'))
  t.eq(new, vim.api.nvim_win_get_buf(win))
  t.eq(false, vim.api.nvim_buf_is_valid(old), 'the old report is gone')
end)

t.test('help follows help in its page window; anything else is put back', function()
  start()
  local buf, line = help_buffer('help-context')
  local win = show_page(-1, buf, line, 'help')
  t.eq(true, t.run('FOCUS_WINDOW', tostring(win), tostring(vim.api.nvim_get_current_win())) == win)
  t.forget()
  vim.cmd('help options') -- in a help window, :help uses that window: no WinNew, no hand-over
  t.eq(win, vim.api.nvim_get_current_win())
  local options = vim.api.nvim_get_current_buf()
  t.eq('options.txt', vim.fn.expand('%:t'))
  local enter = t.sent('eclipse_buf_enter')
  t.eq({ options, win, true }, { enter[#enter][1], enter[#enter][2], enter[#enter][5] }, 'Eclipse is told it follows')
  t.eq({}, t.sent('eclipse_view_page'), 'nothing handed over: it is already the page')
  t.eq(options, t.run('RESTORE_PAGE_WINDOW', tostring(win)), 'the page is options.txt now')
  local other = buffer(2)
  vim.api.nvim_win_set_buf(win, other)
  t.eq(false, t.sent('eclipse_buf_enter')[#t.sent('eclipse_buf_enter')][5], 'a scratch buffer is no help: not followed')
  t.eq(options, t.run('RESTORE_PAGE_WINDOW', tostring(win)))
  t.eq(options, vim.api.nvim_win_get_buf(win), 'put back')
end)

t.test('a messages page never follows help into its window', function()
  start()
  local page = open_page(page_text({ 'one' }))
  local help = help_buffer('help-context')
  t.eq(false, package.loaded['neovim4eclipse.windows'].follow_page(page, help))
end)

t.test('every write from a help page is refused, and q closes the page editor', function()
  start()
  t.run('HOST_COMMANDS', t.host_rules)
  local buf, line = help_buffer('help-context')
  show_page(-1, buf, line, 'help')
  local target = vim.fn.tempname()
  vim.v.errmsg = ''
  vim.api.nvim_buf_call(buf, function()
    pcall(vim.cmd, 'write ' .. vim.fn.fnameescape(target))
    pcall(vim.cmd, 'write!')
  end)
  t.eq(0, vim.fn.filereadable(target), 'nothing written')
  t.ok(vim.fn.execute('messages'):find('E382', 1, true), vim.fn.execute('messages'))
  local q = vim.api.nvim_buf_call(buf, function() return vim.fn.maparg('q', 'n') end)
  t.eq('<Cmd>Eclipse quit<CR>', q)
end)

-- A real plug-in's help, as a user's help page would show it: the pinned flash
-- (t.pinned_plugin), its doc folder copied with a tags file made there, as a plug-in manager makes
-- one with :helptags. How a user's own plug-in manager puts plug-ins on 'runtimepath' is their
-- config's, and not tested.
t.test("a real plug-in's help page follows its links in the page window, and back", function()
  start()
  local plugin = t.pinned_plugin('flash')
  local copy = vim.fn.tempname()
  vim.fn.mkdir(copy .. '/doc', 'p')
  for _, file in ipairs(vim.fn.glob(plugin .. '/doc/*.txt', false, true)) do
    vim.fn.writefile(vim.fn.readfile(file, 'b'), copy .. '/doc/' .. vim.fn.fnamemodify(file, ':t'), 'b')
  end
  vim.cmd.helptags(copy .. '/doc')
  local before = vim.o.runtimepath
  vim.o.runtimepath = before .. ',' .. copy
  local buf = help_buffer('flash.nvim.txt')
  local win = show_page(-1, buf, 1, 'help')
  t.run('FOCUS_WINDOW', tostring(win), tostring(vim.api.nvim_get_current_win()))
  local link = vim.fn.search('|flash.nvim-flash.nvim-api|')
  t.ok(link > 0, 'a link in the table of contents')
  local column = vim.fn.getline(link):find('flash.nvim-flash.nvim-api', 1, true)
  vim.api.nvim_win_set_cursor(0, { link, column })
  t.type('<C-]>')
  t.eq(win, vim.api.nvim_get_current_win(), 'still the page window')
  t.ok(vim.fn.getline('.'):find('*flash.nvim-flash.nvim-api*', 1, true) ~= nil, 'Ctrl-] went to the tag: ' .. vim.fn.getline('.'))
  t.type('<C-t>')
  t.eq(link, vim.fn.line('.'), 'Ctrl-T came back to the link')
  vim.o.runtimepath = before
  vim.fn.delete(copy, 'rf')
end)

t.test(':checkhealth again keeps the page window and shows the new report there', function()
  commands()
  t.forget()
  vim.cmd('checkhealth vim.health')
  t.wait_for('the first report', function() return #t.sent('eclipse_view_page') == 1 end)
  local first = t.sent('eclipse_view_page')[1][2]
  local win = show_page(-1, first, 1, 'checkhealth')
  t.ok(vim.api.nvim_buf_get_name(first) ~= 'health://', 'renamed, so the next run does not wipe it')
  vim.cmd('checkhealth vim.health') -- Neovim wipes whatever is named health:// first
  t.wait_for('the second report', function() return #t.sent('eclipse_view_page') == 2 end)
  t.ok(vim.api.nvim_win_is_valid(win), 'the page window stays')
  for _, closed in ipairs(t.sent('eclipse_window_closed')) do
    t.ok(closed[1] ~= win, 'the page window was never closed (only the report windows the net closes)')
  end
  local second = t.sent('eclipse_view_page')[2][2]
  t.eq(win, show_page(win, second, 1, 'checkhealth'))
  t.eq(second, vim.api.nvim_win_get_buf(win))
  t.eq(false, vim.api.nvim_buf_is_valid(first), 'the first report is gone once no window shows it')
end)

t.test("a document's buffer that :help took is never a page", function()
  commands()
  local folder = vim.fn.tempname()
  vim.fn.mkdir(folder .. '/doc', 'p')
  local text = { '*myplug.txt*  My plug-in', '', '*myplug-tag*', 'Text.' }
  vim.fn.writefile(text, folder .. '/doc/myplug.txt')
  vim.cmd.helptags(folder .. '/doc')
  vim.o.runtimepath = vim.o.runtimepath .. ',' .. folder
  local name = vim.fn.fnamemodify(folder .. '/doc/myplug.txt', ':p')
  local doc = unpack(t.run('CREATE_DOCUMENT_BUFFER', name))
  t.run('DOCUMENT_BUFFER', tostring(doc))
  vim.api.nvim_buf_set_lines(doc, 0, -1, false, text) -- Eclipse's text, as its first push puts it
  vim.bo[doc].tabstop, vim.bo[doc].iskeyword = 4, '@,48-57,_'
  vim.b[doc].neovim4eclipse_tabstop = 4 -- as SET_INDENTATION keeps Eclipse's tab width
  t.run('DOCUMENT_BUFFER', tostring(doc)) -- again: keeps the 'iskeyword' set above
  t.forget()
  vim.cmd('help myplug-tag') -- reuses the document's buffer, as Neovim does for a loaded file
  local settled = false
  vim.schedule(function() settled = true end)
  t.wait_for('the net', function() return settled end)
  t.eq({}, t.sent('eclipse_view_page'), 'not handed to the help editor')
  t.eq({ 'acwrite', true, true, false }, { vim.bo[doc].buftype, vim.bo[doc].modifiable, vim.bo[doc].buflisted,
    vim.bo[doc].readonly }, 'the document is a document again: its editor keeps working')
  t.eq({ 4, '@,48-57,_' }, { vim.bo[doc].tabstop, vim.bo[doc].iskeyword }, "its tab width and word characters, not help's")
  t.wait_for('the message', function() return vim.v.errmsg:find('is open in its editor', 1, true) ~= nil end)
  t.eq(1, windows_in_tab(), 'the help window is closed')
  t.eq(-2, show_page(-1, doc, 1, 'help'), 'refused as a page')
  t.eq(nil, vim.api.nvim_buf_call(doc, function() return vim.fn.maparg('q', 'n', false, true).desc end),
    "the code's q is not the page's")
  -- Its editor closed (CLOSE_DOCUMENT_BUFFER takes its hooks off): its help is a page again.
  t.run('CLOSE_DOCUMENT_BUFFER', tostring(doc))
  t.eq(false, package.loaded['neovim4eclipse.windows'].is_document(doc))
  t.forget()
  vim.cmd('help myplug-tag')
  t.wait_for('the page', function() return #t.sent('eclipse_view_page') == 1 end)
end)

t.test('while the help editor shows a help file, an editor on that file gets a buffer of its own', function()
  start()
  local buf, line = help_buffer('helphelp.txt')
  show_page(-1, buf, line, 'help')
  local name = vim.api.nvim_buf_get_name(buf)
  local doc, named = unpack(t.run('CREATE_DOCUMENT_BUFFER', name))
  t.ok(doc ~= buf, 'not the help page')
  t.eq(false, named, 'left unnamed while the page shows it')
  t.ok(vim.api.nvim_buf_is_valid(buf), 'the page keeps its buffer')
end)

t.test("help reached from the help editor that is an open document's file stays the document's", function()
  start()
  local folder = vim.fn.tempname()
  vim.fn.mkdir(folder .. '/doc', 'p')
  local text = { '*myplug.txt*  My plug-in', '', '*myplug-tag*', 'Text.' }
  vim.fn.writefile(text, folder .. '/doc/myplug.txt')
  vim.cmd.helptags(folder .. '/doc')
  vim.o.runtimepath = vim.o.runtimepath .. ',' .. folder
  local doc = unpack(t.run('CREATE_DOCUMENT_BUFFER', vim.fn.fnamemodify(folder .. '/doc/myplug.txt', ':p')))
  t.run('DOCUMENT_BUFFER', tostring(doc))
  vim.api.nvim_buf_set_lines(doc, 0, -1, false, text) -- Eclipse's text, as its first push puts it
  local help, line = help_buffer('help-context')
  local win = show_page(-1, help, line, 'help')
  t.run('FOCUS_WINDOW', tostring(win), tostring(vim.api.nvim_get_current_win()))
  t.eq(win, vim.api.nvim_get_current_win())
  vim.bo[doc].tabstop, vim.bo[doc].iskeyword = 4, '@,48-57,_'
  vim.b[doc].neovim4eclipse_tabstop = 4 -- as SET_INDENTATION keeps Eclipse's tab width
  t.run('DOCUMENT_BUFFER', tostring(doc)) -- again: keeps the 'iskeyword' set above
  t.forget()
  vim.cmd('help myplug-tag') -- in the help editor: the same window, as Ctrl-] on |myplug-tag| would
  local enter = t.sent('eclipse_buf_enter')
  t.eq({ doc, win, false }, { enter[#enter][1], enter[#enter][2], enter[#enter][5] },
    'not followed: Eclipse puts the page back (restore_page)')
  t.eq({ 'acwrite', true, true, false }, { vim.bo[doc].buftype, vim.bo[doc].modifiable, vim.bo[doc].buflisted,
    vim.bo[doc].readonly }, 'the document is a document again')
  t.wait_for('the message, after the command', function() return vim.v.errmsg:find('is open in its editor', 1, true) ~= nil end)
  t.eq({ 4, '@,48-57,_' }, { vim.bo[doc].tabstop, vim.bo[doc].iskeyword }, "its tab width and word characters, not help's")
  t.eq(help, t.run('RESTORE_PAGE_WINDOW', tostring(win)), 'the page is still the help it showed')
  t.eq(help, vim.api.nvim_win_get_buf(win), 'put back')
  t.eq(text, vim.api.nvim_buf_get_lines(doc, 0, -1, false), "the document's text untouched")
  t.eq(nil, vim.api.nvim_buf_call(doc, function() return vim.fn.maparg('q', 'n', false, true).desc end),
    "the code's q is not the page's")
end)

t.test('the module is registered only once every file has loaded', function()
  -- WINDOWS is five files, each a chunk of its own (core/lua/windows/init.lua): a later one that
  -- fails to load must leave no half-made module for the other scripts to call. Here the last,
  -- net.lua, fails as it makes its WinClosed autocommand.
  local create = vim.api.nvim_create_autocmd
  vim.api.nvim_create_autocmd = function(event, opts)
    if event == 'WinClosed' then
      error('net.lua cannot load')
    end
    return create(event, opts)
  end
  local home_buf = vim.api.nvim_create_buf(false, true)
  local ok, err = pcall(t.run, 'WINDOWS', tostring(home_buf))
  vim.api.nvim_create_autocmd = create
  t.ok(not ok and tostring(err):find('net.lua cannot load', 1, true) ~= nil, tostring(err))
  t.eq(nil, package.loaded['neovim4eclipse.windows'])
  -- Loaded whole, it is there, the same table the files share.
  t.run('WINDOWS', tostring(home_buf))
  t.ok(package.loaded['neovim4eclipse.windows'] ~= nil, 'registered')
  t.ok(package.loaded['neovim4eclipse.windows'] == package.loaded['neovim4eclipse.windows.shared'].module,
    'the module the files share')
end)
