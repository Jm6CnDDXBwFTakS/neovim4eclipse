-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- LuaScripts.SETUP, the session's first script: the keys only the IDE can carry out (eclipse_action),
-- the yank hook (eclipse_yank) and Eclipse as Neovim's clipboard provider (eclipse_clipboard_copy,
-- the eclipse_clipboard_paste request). What Eclipse does with them is NotificationBridgeTest and
-- ClipboardRegistersTest; paste round trips through the session stay in ClipboardIntegrationTest.

local t = ...

t.test('gd, K, gt and gT go to the IDE, with the count typed before them', function()
  t.setup()
  vim.api.nvim_buf_set_lines(0, 0, -1, false, { 'a', 'b' })
  t.type('gt')
  t.type('3gT')
  t.type('gd')
  t.type('K')
  t.eq({ { 'next_tab', 0 }, { 'previous_tab', 3 }, { 'definition', 0 }, { 'hover', 0 } }, t.sent('eclipse_action'))
  t.type('ggjdd') -- the g prefix still works for Neovim's own commands
  t.eq({ 'a' }, vim.api.nvim_buf_get_lines(0, 0, -1, false))
end)

t.test('g:neovim4eclipse_ide_keys = 0 or v:false leaves those keys to Neovim', function()
  for _, off in ipairs({ 0, false }) do
    vim.g.neovim4eclipse_ide_keys = off
    t.setup()
    t.eq('', vim.fn.maparg('gd', 'n'), 'gd is not mapped with ' .. vim.inspect(off))
    t.type('gt') -- Neovim's own gt: one tab page, nothing happens
    t.eq({}, t.sent('eclipse_action'))
  end
end)

t.test('ZZ and ZQ close the editor even with the IDE keys off; fZ is Neovim\'s', function()
  vim.g.neovim4eclipse_ide_keys = 0
  t.setup()
  vim.api.nvim_buf_set_lines(0, 0, -1, false, { 'aZb' })
  t.type('0fZx')
  t.eq({ 'ab' }, vim.api.nvim_buf_get_lines(0, 0, -1, false), 'fZ found the Z, x deleted it')
  t.eq({}, t.sent('eclipse_action'), 'fZ is no file command')
  t.type('ZZ')
  t.type('ZQ')
  t.eq({ { 'save_and_close', 0 }, { 'close', 0 } }, t.sent('eclipse_action'))
end)

t.test('a yank goes to Eclipse once: "+y through the provider, other yanks through the hook', function()
  t.setup()
  vim.api.nvim_buf_set_lines(0, 0, -1, false, { 'one', 'two' })
  t.type('"+yy')
  -- Neovim gives the provider a linewise copy with an empty last item: the final line break.
  t.eq({ { '+', { 'one', '' }, 'V' } }, t.sent('eclipse_clipboard_copy'))
  t.eq({}, t.sent('eclipse_yank'), 'the hook stands down for "+ and "*: a second write would race')
  t.type('jyy')
  t.eq({ { { 'two' }, 'V' } }, t.sent('eclipse_yank'))
  t.type('"*yl')
  t.eq({ '*', { 't' }, 'v' }, t.sent('eclipse_clipboard_copy')[2])
end)

t.test('with clipboard=unnamedplus the provider is the only writer', function()
  t.setup()
  vim.o.clipboard = 'unnamedplus'
  vim.api.nvim_buf_set_lines(0, 0, -1, false, { 'abc' })
  t.type('yy')
  t.eq({ { '+', { 'abc', '' }, 'V' } }, t.sent('eclipse_clipboard_copy'))
  t.eq({}, t.sent('eclipse_yank'))
end)

t.test('"+p asks Eclipse for the text and pastes it with its kind', function()
  t.setup()
  t.answer('eclipse_clipboard_paste', function(reg)
    return reg == '+' and { { 'from', 'elsewhere' }, 'V' } or { { '' }, 'v' }
  end)
  vim.api.nvim_buf_set_lines(0, 0, -1, false, { 'abc' })
  t.type('"+p')
  t.eq({ 'abc', 'from', 'elsewhere' }, vim.api.nvim_buf_get_lines(0, 0, -1, false))
end)

t.test('a user\'s own g:clipboard is kept: Eclipse is no provider then', function()
  vim.g.clipboard = false -- Neovim's "use the built-in tools" setting; any g:clipboard counts
  t.setup()
  t.eq(false, vim.g.clipboard)
  vim.api.nvim_buf_set_lines(0, 0, -1, false, { 'abc' })
  pcall(t.type, '"+yy') -- the built-in tools may be missing on a test machine: that error is fine
  t.eq({}, t.sent('eclipse_clipboard_copy'))
end)

t.test('the user\'s config sees g:neovim4eclipse before the setup sets the channel', function()
  -- The harness starts Neovim as the plug-in does, with --cmd "let g:neovim4eclipse=1".
  t.eq(1, vim.g.neovim4eclipse)
  t.eq(nil, vim.g.neovim4eclipse_channel, 'set only by the setup, after init.lua')
  t.setup()
  t.eq(t.channel, vim.g.neovim4eclipse_channel)
end)
