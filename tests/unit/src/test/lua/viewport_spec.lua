-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- Neovim's window follows the editor (docs/design/VIEWPORT.md): LuaScripts.VIEWPORT (the module
-- and the opt-out), VIEWPORT_OVERHEAD (what the screen has besides the window's text) and SET_WRAP
-- ('wrap' and 'linebreak' from Eclipse's word wrap, D2 and D7). The grid size itself is
-- ViewportSizeTest; nvim_ui_try_resize needs an attached UI, so it is tested through the session
-- (NeovimSessionIntegrationTest).

local t = ...

--- The viewport module, installed as the session does: SETUP (the channel), then VIEWPORT.
local function start()
  t.setup()
  t.eq(true, t.run('VIEWPORT'), 'the viewport is on by default')
  t.forget()
end

--- SET_WRAP as the session sends it: the buffer number and "1" or "0", as strings.
local function wrap(on)
  t.run('SET_WRAP', tostring(vim.api.nvim_get_current_buf()), on and '1' or '0')
end

local function wo(name)
  return vim.api.nvim_get_option_value(name, { scope = 'local', win = 0 })
end

local function messages()
  return vim.api.nvim_exec2('messages', { output = true }).output
end

t.test('vim.g.neovim4eclipse_viewport = 0 turns it off', function()
  t.setup()
  vim.g.neovim4eclipse_viewport = 0
  t.eq(false, t.run('VIEWPORT'))
  t.eq(nil, package.loaded['neovim4eclipse.viewport'], 'no module, so no other script is sent')
  vim.cmd('set wrap')
  t.eq({}, t.sent('eclipse_wrap'), "'wrap' is left to the user")
end)

t.test('the overhead is the status line rows and the number columns, as the user has them', function()
  start()
  -- Without a UI the command line keeps its row (with the plug-in's, ext_messages, 'cmdheight'
  -- is 0), so the rows are compared with laststatus=0, where only the command line is left.
  vim.o.laststatus = 0
  local base = t.run('VIEWPORT_OVERHEAD')
  t.eq(vim.o.lines - vim.fn.winheight(0), base[1], '&lines - winheight(0)')
  t.eq(0, base[2], 'no number column')
  vim.o.laststatus = 2
  t.eq({ base[1] + 1, 0 }, t.run('VIEWPORT_OVERHEAD'), 'the status line takes a row')
  vim.wo.number = true
  t.eq({ base[1] + 1, 4 }, t.run('VIEWPORT_OVERHEAD'), "set number: 'numberwidth' 4, three digits and a space")
end)

t.test("word wrap on sets 'linebreak' and turns off breakindent and showbreak; off gives the user's back", function()
  start()
  vim.wo.breakindent = true
  vim.wo.showbreak = '>> '
  wrap(true)
  t.eq(true, wo('wrap'))
  t.eq(true, wo('linebreak'), 'Eclipse breaks at words')
  t.eq(false, wo('breakindent'), 'Eclipse does not indent the wrapped part')
  t.eq('NONE', wo('showbreak'), 'nor marks it')
  wrap(false)
  t.eq(false, wo('wrap'))
  t.eq(false, wo('linebreak'))
  t.eq(true, wo('breakindent'), "the user's own value")
  t.eq('>> ', wo('showbreak'))
end)

t.test('what Eclipse sets is not sent back; a :set wrap typed in Neovim is', function()
  start()
  wrap(true)
  wrap(false)
  t.eq({}, t.sent('eclipse_wrap'), 'no echo')
  t.type(':set wrap<CR>')
  t.eq({ { vim.api.nvim_get_current_buf(), true, vim.api.nvim_get_current_win() } }, t.sent('eclipse_wrap'))
  t.eq(true, wo('linebreak'), 'wrap from Neovim breaks at words too')
  t.type(':set nowrap<CR>')
  t.eq({ vim.api.nvim_get_current_buf(), false, vim.api.nvim_get_current_win() }, t.sent('eclipse_wrap')[2])
end)

t.test("'nolinebreak' while wrap is on is set back, with a message only the first time", function()
  start()
  wrap(true)
  t.type(':set nolinebreak<CR>')
  t.eq(true, wo('linebreak'), 'set back')
  t.ok(messages():find("'linebreak' stays as it is while word wrap is on", 1, true), messages())
  vim.cmd('messages clear')
  t.type(':set breakindent<CR>')
  t.eq(false, wo('breakindent'), 'set back again')
  t.eq('', messages(), 'silently: a filetype plug-in would otherwise show it for every file')
  wrap(false)
  t.eq(false, wo('linebreak'), 'what the user asked for applies once wrap is off')
  t.eq(true, wo('breakindent'))
end)

t.test('a modeline that sets nolinebreak is set back too', function()
  start()
  local file = vim.fn.tempname() .. '.txt'
  vim.fn.writefile({ 'text', 'vim: set nolinebreak:' }, file)
  vim.o.modeline = true
  wrap(true)
  vim.cmd.edit(file)
  -- The window's local values come along to the new buffer; the modeline then runs OptionSet.
  t.eq(true, wo('wrap'))
  t.eq(true, wo('linebreak'), 'OptionSet fires for a modeline, so it is set back')
end)

t.test('an option that changes the overhead tells Eclipse once per batch', function()
  start()
  t.type(':set number relativenumber<CR>')
  t.wait_for('the notification', function()
    return #t.sent('eclipse_viewport') > 0
  end)
  t.eq({ {} }, t.sent('eclipse_viewport'), 'two options, one notification')
end)

t.test('SET_WRAP leaves the window alone when it shows another buffer by now', function()
  start()
  vim.o.wrap = false -- global and local: Neovim's default is wrap, and a new buffer gets the global value
  local first = vim.api.nvim_get_current_buf()
  vim.api.nvim_buf_set_lines(first, 0, -1, false, { 'text' }) -- :enew would reuse an empty buffer
  vim.cmd.enew()
  t.ok(vim.api.nvim_get_current_buf() ~= first, 'another buffer')
  t.run('SET_WRAP', tostring(first), '1')
  t.eq(false, wo('wrap'))
end)

-- ---- the top line (commit 2: Neovim's window scrolls, the editor follows) ----

--- A buffer of `count` lines, "line 1" to "line {count}", with the cursor on line `cursor`.
local function long_buffer(count, cursor)
  local lines = {}
  for i = 1, count do
    lines[i] = 'line ' .. i
  end
  vim.api.nvim_buf_set_lines(0, 0, -1, false, lines)
  vim.api.nvim_win_set_cursor(0, { cursor, 0 })
end

--- SET_TOPLINE as the session sends it: the buffer number and the 1-based line, as strings.
local function topline(line, buf)
  t.run('SET_TOPLINE', tostring(buf or vim.api.nvim_get_current_buf()), tostring(line), 'keep')
end

t.test("SET_TOPLINE scrolls the window to the editor's top line and keeps the cursor", function()
  start()
  vim.o.scrolloff = 0
  long_buffer(200, 50)
  topline(40)
  t.eq(40, vim.fn.line('w0'), 'H now goes to line 40, as in the editor')
  t.eq(50, vim.fn.line('.'), 'the cursor stays')
end)

t.test('SET_TOPLINE cannot hide the cursor: Neovim keeps it on the screen', function()
  start()
  vim.o.scrolloff = 0
  long_buffer(200, 1)
  topline(100)
  t.eq(1, vim.fn.line('.'))
  t.eq(1, vim.fn.line('w0'), 'clamped; WinScrolled would report what Neovim chose')
end)

t.test("SET_TOPLINE leaves another buffer's window alone", function()
  start()
  long_buffer(200, 1)
  local other = vim.api.nvim_get_current_buf()
  vim.cmd.enew()
  long_buffer(200, 150)
  local before = vim.fn.line('w0')
  topline(140, other)
  t.eq(before, vim.fn.line('w0'), 'the window shows another editor\'s document by now')
end)

-- The cursor report's top line and WinScrolled's eclipse_topline come from autocmds that fire in
-- Neovim's main loop, which t.type's feedkeys does not reach: NeovimViewportIntegrationTest.

--- SET_TOPLINE for a scroll in the editor: the cursor is dragged into the new view.
local function scroll(line)
  t.run('SET_TOPLINE', tostring(vim.api.nvim_get_current_buf()), tostring(line), 'drag')
end

t.test('a scroll in the editor drags the cursor down into the view, as Ctrl-E does', function()
  start()
  vim.o.scrolloff = 0
  long_buffer(200, 5)
  scroll(40)
  t.eq(40, vim.fn.line('w0'), 'the view is the editor\'s, not clamped to the cursor')
  t.eq(40, vim.fn.line('.'), 'the cursor on the first line of the view')
end)

t.test("a scroll keeps 'scrolloff' lines between the dragged cursor and the edge", function()
  start()
  vim.o.scrolloff = 3
  long_buffer(200, 5)
  scroll(40)
  t.eq(40, vim.fn.line('w0'))
  t.eq(43, vim.fn.line('.'))
  vim.api.nvim_win_set_cursor(0, { 190, 0 })
  scroll(10)
  t.eq(10, vim.fn.line('w0'))
  t.eq(vim.fn.line('w$') - 3, vim.fn.line('.'), 'scrolled up: the cursor dragged up to the bottom')
end)

t.test('a scroll that keeps the cursor in view leaves it where it is, column included', function()
  start()
  vim.o.scrolloff = 0
  long_buffer(200, 45)
  vim.api.nvim_win_set_cursor(0, { 45, 3 })
  scroll(40)
  t.eq(40, vim.fn.line('w0'))
  t.eq({ 45, 3 }, vim.api.nvim_win_get_cursor(0))
end)

--- SCROLL_LINES as the session sends it for the mouse wheel: the buffer number and the lines.
local function wheel(n)
  t.run('SCROLL_LINES', tostring(vim.api.nvim_get_current_buf()), tostring(n))
end

t.test('the wheel scrolls by lines and drags the cursor along, as Ctrl-E and Ctrl-Y', function()
  start()
  vim.o.scrolloff = 0
  long_buffer(200, 1)
  wheel(3)
  t.eq(4, vim.fn.line('w0'), 'three lines down')
  t.eq(4, vim.fn.line('.'), 'the cursor dragged to the top line')
  wheel(-2)
  t.eq(2, vim.fn.line('w0'))
  wheel(-10)
  t.eq(1, vim.fn.line('w0'), 'stops at the first line')
end)

t.test('a closed fold is one line for the wheel', function()
  start()
  vim.o.scrolloff = 0
  long_buffer(200, 1)
  vim.wo.foldmethod = 'manual'
  vim.cmd('5,10fold')
  wheel(5)
  t.eq(11, vim.fn.line('w0'), '2, 3, 4, the fold, then 11')
  wheel(-1)
  t.eq(5, vim.fn.line('w0'), 'back onto the fold, at its first line')
end)

t.test('the wheel stops with the last line at the top, as Ctrl-E does', function()
  start()
  long_buffer(30, 1)
  wheel(100)
  t.eq(30, vim.fn.line('w0'))
end)

--- SET_TOPLINE at Esc: a cursor in the editor's view stays, one outside it is dragged in.
local function show(line)
  t.run('SET_TOPLINE', tostring(vim.api.nvim_get_current_buf()), tostring(line), 'show')
end

t.test("at Esc a cursor the editor scrolled away from is dragged into the editor's view", function()
  start()
  vim.o.scrolloff = 0
  long_buffer(200, 10)
  show(60)
  t.eq(60, vim.fn.line('w0'), 'the view stays where the wheel left it')
  t.eq(60, vim.fn.line('.'), 'as Neovim\'s wheel would have dragged the cursor')
end)

t.test('at Esc a cursor still in the view stays where it is', function()
  start()
  vim.o.scrolloff = 5
  long_buffer(200, 70)
  show(60)
  t.eq(70, vim.fn.line('.'), "not moved for 'scrolloff', unlike a drag")
end)
