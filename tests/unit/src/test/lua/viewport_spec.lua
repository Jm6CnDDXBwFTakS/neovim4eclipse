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
  t.eq({ { vim.api.nvim_get_current_buf(), true } }, t.sent('eclipse_wrap'))
  t.eq(true, wo('linebreak'), 'wrap from Neovim breaks at words too')
  t.type(':set nowrap<CR>')
  t.eq({ vim.api.nvim_get_current_buf(), false }, t.sent('eclipse_wrap')[2])
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
