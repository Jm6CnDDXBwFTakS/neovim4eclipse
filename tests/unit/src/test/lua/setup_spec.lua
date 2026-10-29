-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- LuaScripts.SETUP, the session's first script, and IDE_KEYS after it: the keys only the IDE can
-- carry out (eclipse_action; the table is core/IdeKeys, IdeKeysTest),
-- the yank hook (eclipse_yank) and Eclipse as Neovim's clipboard provider (eclipse_clipboard_copy,
-- the eclipse_clipboard_paste request). What Eclipse does with them is NotificationBridgeTest and
-- ClipboardRegistersTest; paste round trips through the session stay in ClipboardIntegrationTest.

local t = ...

t.test('gd, Ctrl-], K, gt and gT go to the IDE, with the count typed before them', function()
  t.setup()
  vim.api.nvim_buf_set_lines(0, 0, -1, false, { 'a', 'b' })
  t.type('gt')
  t.type('3gT')
  t.type('gd')
  t.type('<C-]>')
  t.type('K')
  t.eq({ { 'next_tab', 0 }, { 'previous_tab', 3 }, { 'definition', 0 }, { 'definition', 0 }, { 'hover', 0 } },
    t.sent('eclipse_action'))
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

t.test('Ctrl-O and Ctrl-I are Neovim\'s while its jump history goes on, then Eclipse\'s Back and Forward', function()
  t.setup()
  vim.cmd('clearjumps')
  vim.api.nvim_buf_set_lines(0, 0, -1, false, { 'a', 'b', 'c' })
  t.type('gg')
  vim.cmd('clearjumps')
  t.type('<C-o>') -- no older jump: an editor switch made in Eclipse is in Eclipse's history only
  t.eq({ { 'history_back', 0, 1 } }, t.sent('eclipse_action'), 'with the id of its Back step')
  t.forget()
  t.type('G<C-o>') -- G is a jump: Neovim's own Ctrl-O goes back to line 1
  t.eq(1, vim.fn.line('.'))
  t.eq({}, t.sent('eclipse_action'))
  t.type('<C-i>') -- and Ctrl-I forward again, to line 3
  t.eq(3, vim.fn.line('.'))
  t.eq({}, t.sent('eclipse_action'))
  t.type('2<C-i>') -- nothing newer: Forward, with the count (no step: G forgot the Back)
  t.eq({ { 'history_forward', 2, 0 } }, t.sent('eclipse_action'))
  t.eq(3, vim.fn.line('.'))
end)

t.test('a Ctrl-O mapped in init.lua is left alone', function()
  vim.keymap.set('n', '<C-o>', '<Nop>')
  t.setup()
  t.eq('<Nop>', vim.fn.maparg('<C-o>', 'n'))
  t.eq(true, vim.fn.maparg('<C-i>', 'n') ~= '', 'Ctrl-I, not mapped by the user, still falls back')
end)

t.test('the IDE keys switch does not cover Ctrl-O and Ctrl-I: they still fall back', function()
  vim.g.neovim4eclipse_ide_keys = 0
  t.setup()
  vim.cmd('clearjumps')
  t.type('<C-o><C-i>')
  t.eq({ { 'history_back', 0, 1 }, { 'history_forward', 0, 1 } }, t.sent('eclipse_action'))
end)

t.test('a count larger than the jump history does nothing, as in Neovim', function()
  t.setup()
  vim.api.nvim_buf_set_lines(0, 0, -1, false, { '1', '2', '3', '4', '5', '6', '7', '8', '9' })
  vim.cmd('clearjumps')
  t.type('3G6G9G')
  t.forget()
  t.type('5<C-o>')
  t.eq(9, vim.fn.line('.'))
  t.eq({}, t.sent('eclipse_action'), 'some older jump exists: Neovim\'s key, which does nothing')
end)

-- The trail of Ctrl-O steps (SETUP): Ctrl-I undoes the newest first. Here Back lands nowhere (no
-- Eclipse), so the window still has its newer jumps when Ctrl-I comes; without the trail, Ctrl-I
-- would take them first, before undoing the Back.
local function lines_and_jumps()
  t.setup()
  vim.api.nvim_buf_set_lines(0, 0, -1, false, { '1', '2', '3', '4', '5', '6' })
  t.type('gg')
  vim.cmd('clearjumps')
  t.type('3G6G')
  t.forget()
end

local function trail()
  return require('neovim4eclipse.history').trail()
end

t.test('Ctrl-I undoes a Back before the jumps Ctrl-O made before it', function()
  lines_and_jumps()
  t.type('<C-o><C-o>')
  t.eq(1, vim.fn.line('.'))
  t.type('<C-o>') -- no older jump: Back step 1
  t.eq({ { 'history_back', 0, 1 } }, t.sent('eclipse_action'))
  t.forget()
  t.type('<C-i>')
  t.eq({ { 'history_forward', 0, 1 } }, t.sent('eclipse_action'), 'the Back first, by its id')
  t.eq(1, vim.fn.line('.'))
  t.forget()
  t.type('<C-i><C-i>')
  t.eq(6, vim.fn.line('.'), 'then the two jumps')
  t.eq({}, t.sent('eclipse_action'))
end)

t.test('a count is that many steps, each Back step with its own id', function()
  lines_and_jumps()
  t.type('2<C-o>')
  t.eq(1, vim.fn.line('.'))
  t.type('3<C-o>')
  t.eq({ { 'history_back', 3, 1 } }, t.sent('eclipse_action'), 'steps 1, 2 and 3')
  t.eq({ { 'jump', 2, vim.api.nvim_get_current_win() }, { 'back', 1 }, { 'back', 2 }, { 'back', 3 } }, trail())
  t.forget()
  t.type('2<C-i>')
  t.eq({ { 'history_forward', 2, 2 } }, t.sent('eclipse_action'), 'undo steps 3 and 2')
  t.forget()
  t.type('<C-i>')
  t.eq({ { 'history_forward', 0, 1 } }, t.sent('eclipse_action'))
  t.type('<C-i>')
  t.eq(3, vim.fn.line('.'), 'one of the two jump steps')
end)

t.test('a count past the newest run of one kind does nothing', function()
  lines_and_jumps()
  t.type('<C-o><C-o><C-o>') -- two jumps, a Back
  t.forget()
  t.type('2<C-i>')
  t.eq({}, t.sent('eclipse_action'), 'one Back, then jumps: no Forward')
  t.eq(1, vim.fn.line('.'), 'and no jump')
  t.type('<C-i>3<C-i>')
  t.eq(1, vim.fn.line('.'), 'two jumps in the run, not three')
  t.type('2<C-i>')
  t.eq(6, vim.fn.line('.'))
end)

t.test('a key typed between forgets the trail', function()
  lines_and_jumps()
  t.type('<C-o><C-o><C-o>')
  t.forget()
  t.type('l<C-i>')
  t.eq({}, t.sent('eclipse_action'), 'Neovim\'s own Ctrl-I: the window has a newer jump')
  t.eq(3, vim.fn.line('.'))
end)

t.test('Back steps Eclipse could not take leave the trail by their ids', function()
  lines_and_jumps()
  t.type('<C-o><C-o><C-o><C-o><C-o>') -- at once, as a key held down sends them: two jumps, steps 1-3
  t.run('HISTORY_NOWHERE', '2', '2') -- what Eclipse reports at the start of its history: 2 and 3
  t.eq({ { 'jump', 2, vim.api.nvim_get_current_win() }, { 'back', 1 } }, trail())
  t.forget()
  t.type('<C-i>')
  t.eq({ { 'history_forward', 0, 1 } }, t.sent('eclipse_action'))
  t.run('HISTORY_NOWHERE', '1', '1') -- late, after Ctrl-I took step 1: nothing else goes
  t.eq({ { 'jump', 2, vim.api.nvim_get_current_win() } }, trail())
end)

t.test('Eclipse\'s forget, after an editor switch of the user\'s, clears the trail', function()
  lines_and_jumps()
  t.type('<C-o><C-o><C-o>')
  t.run('HISTORY_FORGET')
  t.eq({}, trail())
  t.forget()
  t.type('<C-i>')
  t.eq({}, t.sent('eclipse_action'), 'Neovim\'s own Ctrl-I: the window has a newer jump')
  t.eq(3, vim.fn.line('.'))
end)

t.test('a jump step whose window is no longer current forgets the trail', function()
  lines_and_jumps()
  t.type('<C-o>')
  vim.cmd('new | clearjumps') -- another window, as after an editor switch in Eclipse
  t.forget()
  t.type('<C-i>')
  t.eq({ { 'history_forward', 0, 0 } }, t.sent('eclipse_action'), 'the new window has no jumps: Forward')
  t.eq({}, trail())
end)

t.test('BufEnter says whether the move is in the jump history', function()
  t.setup()
  local a = vim.api.nvim_get_current_buf()
  local b = vim.api.nvim_create_buf(true, false)
  local c = vim.api.nvim_create_buf(true, false)
  vim.cmd('clearjumps')
  t.forget()
  vim.cmd('buffer ' .. b)
  vim.cmd('keepjumps buffer ' .. c)
  local win = vim.api.nvim_get_current_win()
  t.eq({ { b, win, true, '' }, { c, win, false, '' } }, t.sent('eclipse_buf_enter'), ':b is a jump, :keepjumps b not')
  t.eq(true, a > 0)
end)
