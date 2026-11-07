-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- Search highlighting (docs/design/SEARCH_HIGHLIGHTING.md): LuaScripts.SEARCH works out the
-- matches of Neovim's search in the window's visible lines and sends them as eclipse_search(buf,
-- ranges, win). Without a UI there is no redraw, so no on_win; the tests call update() as the
-- scheduled callback would.

local t = ...

--- The search module, installed as the session does: SETUP (the channel), then SEARCH.
local function start(lines)
  t.setup()
  t.eq(true, t.run('SEARCH'), 'search highlighting is on by default')
  vim.cmd('enew!')
  vim.api.nvim_buf_set_lines(0, 0, -1, false, lines)
  vim.o.hlsearch = true
  vim.o.incsearch = true
  vim.o.ignorecase = false
  vim.o.smartcase = false
  vim.cmd('nohlsearch')
  t.forget()
  return package.loaded['neovim4eclipse.search']
end

--- Runs the work scheduled so far (vim.schedule runs in order): a barrier, not a sleep.
local function flush()
  local done = false
  vim.schedule(function()
    done = true
  end)
  t.wait_for('the scheduled work', function()
    return done
  end)
end

--- The ranges update() sends now, as {line, startbyte, endline, endbyte} lists.
local function matches(m)
  local flat = m.update()
  local out = {}
  for i = 1, #flat, 4 do
    out[#out + 1] = { flat[i], flat[i + 1], flat[i + 2], flat[i + 3] }
  end
  return out
end

t.test('vim.g.neovim4eclipse_search = 0 turns it off', function()
  t.setup()
  vim.g.neovim4eclipse_search = 0
  t.eq(false, t.run('SEARCH'))
  vim.g.neovim4eclipse_search = nil
end)

t.test('after a search, the matches are sent once; :noh sends an empty list', function()
  local m = start({ 'a foo b foo', 'none', 'foo' })
  t.type('/foo<CR>')
  t.eq({ { 1, 2, 1, 5 }, { 1, 8, 1, 11 }, { 3, 0, 3, 3 } }, matches(m))
  local sent = t.sent('eclipse_search')
  t.eq(1, #sent, 'one notification')
  t.eq(vim.api.nvim_get_current_buf(), sent[1][1], 'the buffer')
  t.eq(vim.api.nvim_get_current_win(), sent[1][3], 'the window')
  m.update()
  t.eq(1, #t.sent('eclipse_search'), 'the same matches are not sent again (n, j)')
  vim.cmd('nohlsearch')
  t.eq({}, matches(m), ':noh')
  t.eq({}, t.sent('eclipse_search')[2][2], 'sent as an empty list')
end)

t.test("'nohlsearch' shows nothing after the search", function()
  local m = start({ 'foo' })
  vim.o.hlsearch = false
  t.type('/foo<CR>')
  t.eq({}, matches(m))
end)

t.test('the columns are bytes: a match after an accented letter', function()
  local m = start({ 'é foo' })
  t.type('/foo<CR>')
  t.eq({ { 1, 3, 1, 6 } }, matches(m), 'é is two bytes, the space one')
end)

t.test('a match of no width is left out', function()
  local m = start({ 'foo', 'bar' })
  t.type('/^<CR>')
  t.eq({}, matches(m))
end)

t.test('smartcase: an upper-case letter makes the search exact, but not after *', function()
  local m = start({ 'Foo foo FOO' })
  vim.o.ignorecase = true
  vim.o.smartcase = true
  t.type('/Foo<CR>')
  t.eq({ { 1, 0, 1, 3 } }, matches(m), '/Foo: only Foo')
  t.type('/foo<CR>')
  t.eq(3, #matches(m), '/foo: every case')
  t.type('0*')
  flush() -- the scheduled check of register /
  t.eq(3, #matches(m), '* on Foo: every case, as Vim never makes * smart')
  t.type('n')
  t.eq(3, #matches(m), 'n after * keeps it')
  t.type('/Foo<CR>')
  t.eq(1, #matches(m), 'a typed search is smart again')
  vim.o.smartcase = false
  t.type('/Foo<CR>')
  t.eq(3, #matches(m), 'ignorecase alone')
  vim.o.ignorecase = false
  t.type('/foo<CR>')
  t.eq(1, #matches(m), 'noignorecase: exact')
  t.type('/\\cfoo<CR>')
  t.eq(3, #matches(m), '\\c in the pattern wins')
end)

t.test('the letter after a backslash does not count for smartcase', function()
  local m = start({ 'a1 b2' })
  vim.o.ignorecase = true
  vim.o.smartcase = true
  t.eq('\\ca\\S', m.effective('a\\S', false), '\\S is a class, not a capital S')
  t.eq('\\CA\\S', m.effective('A\\S', false))
  t.eq('\\cA', m.effective('A', true), 'from *')
end)

t.test('while a search is typed, the matches of what is typed so far, without the offset', function()
  local m = start({ 'foo fob' })
  local typing
  t.during('/fo', function()
    typing = matches(m)
  end)
  t.eq({ { 1, 0, 1, 2 }, { 1, 4, 1, 6 } }, typing, 'fo, as typed')
  t.during('/foo/e', function()
    typing = matches(m)
  end)
  t.eq({ { 1, 0, 1, 3 } }, typing, '/e is an offset, not part of the pattern')
  vim.o.incsearch = false
  t.during('/fo', function()
    typing = matches(m)
  end)
  t.eq({}, typing, "'noincsearch': nothing while typing")
end)

t.test('a pattern across lines is found with its end on the next line', function()
  local m = start({ 'one foo', 'bar two', 'foo', 'bar' })
  t.type('/foo\\nbar<CR>')
  t.eq({ { 1, 4, 2, 3 }, { 3, 0, 4, 3 } }, matches(m))
  t.eq(1, vim.fn.line('.'), 'the cursor is where the search left it')
end)

t.test('only the visible lines are searched', function()
  local lines = {}
  for i = 1, 200 do
    lines[i] = 'foo ' .. i
  end
  local m = start(lines)
  t.type('/foo<CR>')
  local found = matches(m)
  t.ok(#found > 0 and #found < 200, 'some, not all 200: ' .. #found)
  t.eq(vim.fn.line('w0'), found[1][1], 'from the top line')
  t.eq(vim.fn.line('w$'), found[#found][1], 'to the bottom line')
end)

t.test('entering a window, or resend, sends the same matches again', function()
  local m = start({ 'foo' })
  t.type('/foo<CR>')
  m.update()
  t.forget()
  m.update()
  t.eq({}, t.sent('eclipse_search'), 'unchanged: nothing')
  vim.cmd('split')
  vim.cmd('wincmd p')
  m.update()
  t.eq(1, #t.sent('eclipse_search'), 'after WinEnter: sent again')
  t.forget()
  m.resend()
  t.wait_for('the scheduled resend', function()
    return #t.sent('eclipse_search') == 1
  end)
  vim.cmd('only')
end)

t.test('a search made with * stays one after :w, a cancelled / and n', function()
  local m = start({ 'Foo foo FOO *' })
  vim.o.ignorecase = true
  vim.o.smartcase = true
  t.type('0*')
  flush() -- the scheduled check of register /
  t.eq(3, #matches(m), '* on Foo: every case')
  t.type(':echo 1<CR>')
  t.eq(3, #matches(m), 'a : line that sets no pattern keeps it')
  t.type('/x<Esc>')
  t.eq(3, #matches(m), 'a cancelled search keeps it')
end)

t.test('* as the argument of another command is not a search', function()
  local m = start({ 'Foo foo FOO *' })
  vim.o.ignorecase = true
  vim.o.smartcase = true
  t.type('/Foo<CR>')
  t.type('0f*')
  flush()
  t.eq(1, #matches(m), 'f* did not change register /: the typed search stays smart')
end)

t.test("smartcase's capitals are Vim's: none after \\_ or \\%, accented ones count", function()
  local m = start({ '' })
  t.eq(false, m.has_upper('a\\S'))
  t.eq(false, m.has_upper('\\_Sfoo'), '\\_S is a class')
  t.eq(false, m.has_upper('\\%Vfoo'), '\\%V is an atom')
  t.eq(true, m.has_upper('Éte'), 'É is a capital')
  t.eq(false, m.has_upper('éte'))
  t.eq(true, m.has_upper('\\_sFoo'))
end)

t.test('the pattern of :s, :g and :v lines, as typed', function()
  local m = start({ '' })
  t.eq('foo', m.ex_pattern('%s/foo/bar'))
  t.eq('Foo', m.ex_pattern("'<,'>s/Foo"))
  t.eq('a\\/b', m.ex_pattern('10,20s/a\\/b/c'), 'the escaped delimiter stays escaped, as Vim reads it')
  t.eq('x', m.ex_pattern('g/x'))
  t.eq('z', m.ex_pattern('v!/z'))
  t.eq('a', m.ex_pattern('substitute#a#b'))
  t.eq(nil, m.ex_pattern('s'), 'no pattern yet')
  t.eq(nil, m.ex_pattern('set hls'), 'another command')
  t.eq(nil, m.ex_pattern('sort /x/'), ':sort is not one of them')
end)

t.test('while a :s line is typed, the matches of its pattern', function()
  local m = start({ 'foo fob bar' })
  t.type('/bar<CR>')
  local typing
  t.during(':%s/fo', function()
    typing = matches(m)
  end)
  t.eq({ { 1, 0, 1, 2 }, { 1, 4, 1, 6 } }, typing, 'fo, as typed after :%s/')
  t.during(':echo', function()
    typing = matches(m)
  end)
  t.eq({ { 1, 8, 1, 11 } }, typing, 'another command: the last search')
end)

t.test('a match across lines that begins above the view is found', function()
  local lines = {}
  for i = 1, 100 do
    lines[i] = 'x'
  end
  lines[50], lines[51] = 'foo', 'bar'
  local m = start(lines)
  t.type('/foo\\nbar<CR>')
  vim.fn.winrestview({ topline = 51, lnum = 60 })
  t.eq(51, vim.fn.line('w0'))
  t.eq({ { 50, 0, 51, 3 } }, matches(m), 'it ends on the top line')
end)

t.test('the lines the editor reports are searched too', function()
  local lines = {}
  for i = 1, 300 do
    lines[i] = 'foo ' .. i
  end
  local m = start(lines)
  t.type('/foo<CR>')
  t.forget()
  m.set_view(nil, 200, 210)
  t.wait_for('the matches after set_view', function()
    return #t.sent('eclipse_search') == 1
  end)
  local flat = t.sent('eclipse_search')[1][2]
  local lines_sent = {}
  for i = 1, #flat, 4 do
    lines_sent[flat[i]] = true
  end
  t.ok(lines_sent[1] and lines_sent[205] and lines_sent[210], 'the window\'s lines and the editor\'s')
  t.ok(not lines_sent[250], 'not past the editor\'s last line')
  t.ok(not lines_sent[100], 'not the lines between the two: two searches, not one over all')
end)

t.test('after an edit the matches are sent again, even where none moved', function()
  local m = start({ 'foo', 'other' })
  t.type('/foo<CR>')
  m.update()
  t.forget()
  vim.api.nvim_buf_set_lines(0, 1, 2, true, { 'changed' })
  m.update()
  t.eq(1, #t.sent('eclipse_search'), 'the editor may have dropped one the edit touched')
  vim.cmd('nohlsearch')
  m.update()
  t.forget()
  vim.api.nvim_buf_set_lines(0, 1, 2, true, { 'again' })
  m.update()
  t.eq({}, t.sent('eclipse_search'), 'without matches an edit sends nothing')
end)

t.test('pressing / keeps the last search shown until something is typed', function()
  local m = start({ 'foo bar' })
  t.type('/foo<CR>')
  local typing
  t.during('/', function()
    typing = matches(m)
  end)
  t.eq({ { 1, 0, 1, 3 } }, typing, "the last search's matches, as Neovim shows them")
end)

t.test('a typed search in the same batch of keys as a * is not the star\'s', function()
  local m = start({ 'Foo foo FOO' })
  vim.o.ignorecase = true
  vim.o.smartcase = true
  t.type('0*:echo 1<CR>/Foo<CR>') -- one batch, as a macro's keys: no scheduled work runs in between
  flush()
  t.eq(1, #matches(m), '/Foo stays smart: only Foo')
end)

t.test('a match across the lines between two searched ranges is sent once', function()
  local lines = {}
  for i = 1, 200 do
    lines[i] = 'x'
  end
  local m = start(lines)
  local b = vim.fn.line('w$')
  vim.api.nvim_buf_set_lines(0, b - 1, b + 2, true, { 'foo', 'x', 'bar' })
  t.type('/foo\\_.\\{-}bar<CR>')
  vim.fn.winrestview({ topline = 1, lnum = 1 })
  m.set_view(nil, b + 2, b + 8) -- apart from the window's lines by one: two searches
  flush()
  t.eq({ { b, 0, b + 2, 3 } }, matches(m), 'found by both walks, kept once')
end)

t.test('an escaped backslash before c is not the case flag', function()
  local m = start({ '' })
  vim.o.ignorecase = true
  vim.o.smartcase = true
  t.eq('\\CC:\\\\Code', m.effective('C:\\\\Code', false), 'a literal backslash, then a capital C: exact case')
  t.eq('a\\cB', m.effective('a\\cB', false), 'a real \\c is kept as it is')
end)
