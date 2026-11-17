-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- LuaScripts.SCREEN_SETUP, which the second Neovim of the Neovim view runs once it started
-- (core/ScreenSession): the options the one rule rests on (it never writes a file), the yank
-- report and the end report. What ScreenSession does with the answer is ScreenSessionTest; the
-- real second Neovim, started with its fixed arguments, is ScreenSessionIntegrationTest.

local t = ...

--- The options as ScreenSession.FIXED_ARGS leave them: this harness's Neovim starts with --clean
--- only, so the test sets the rest itself.
local function as_started()
  vim.o.write = false
  vim.o.modeline = false
  vim.o.updatecount = 0
  -- The readers NvimExecutable.READERS_OFF keeps from loading; loaded here, so cleared.
  for _, group in ipairs({ 'Network', 'tar', 'zip', 'nvim.net.remotefile' }) do
    pcall(vim.api.nvim_del_augroup_by_name, group)
  end
end

t.test('the answer is the options the one rule rests on, as Neovim has them', function()
  as_started()
  local answer = t.run('SCREEN_SETUP', tostring(t.channel))
  t.eq('boolean', type(answer.ui2), 'whether ui2 could be turned on; this headless harness has no UI to show it')
  answer.ui2 = nil
  t.eq({ write = false, modeline = false, updatecount = 0, undofile = false, shadafile = 'NONE', readers = 0 },
    answer)
end)

t.test("the term:// reader --clean keeps is off, so gf on shown text runs nothing", function()
  as_started()
  t.ok(#vim.api.nvim_get_autocmds({ event = 'BufReadCmd', pattern = 'term://*' }) > 0,
    'Neovim sets the reader up even with --clean; without it this test proves nothing')
  t.eq(0, t.run('SCREEN_SETUP', tostring(t.channel)).readers)
  t.eq(0, #vim.api.nvim_get_autocmds({ event = 'BufReadCmd', pattern = 'term://*' }))
end)

t.test('a reader left, whatever its pattern, is counted, so ScreenSession refuses the start', function()
  as_started()
  vim.api.nvim_create_autocmd('BufReadCmd', { pattern = 'term:*', command = 'echo' })
  vim.api.nvim_create_autocmd('SourceCmd', { pattern = '*', command = 'echo' })
  t.eq(2, t.run('SCREEN_SETUP', tostring(t.channel)).readers)
  vim.api.nvim_clear_autocmds({ event = { 'BufReadCmd', 'SourceCmd' }, pattern = { 'term:*', '*' } })
end)

t.test('man:// and ShaDa readers are kept, as in the editing Neovim', function()
  as_started()
  t.ok(#vim.api.nvim_get_autocmds({ group = 'nvim.man' }) > 0, 'this Neovim has the man:// reader')
  t.eq(0, t.run('SCREEN_SETUP', tostring(t.channel)).readers)
end)

t.test('an option left on shows in the answer, so ScreenSession refuses the start', function()
  as_started()
  vim.o.write = true
  t.eq(true, t.run('SCREEN_SETUP', tostring(t.channel)).write)
end)

t.test('a yank reports its register, type and lines', function()
  as_started()
  t.run('SCREEN_SETUP', tostring(t.channel))
  vim.api.nvim_buf_set_lines(0, 0, -1, false, { 'OK one', 'two' })
  t.type('gg"ayy')
  t.type('jyw')
  t.eq({ { 'a', 'V', { 'OK one' } }, { '', 'v', { 'two' } } }, t.sent('eclipse_screen_yank'))
end)

t.test('leaving Neovim asks Eclipse first, so the end is expected', function()
  as_started()
  local asked = 0
  t.answer('eclipse_screen_leaving', function()
    asked = asked + 1
    return vim.NIL
  end)
  t.run('SCREEN_SETUP', tostring(t.channel))
  vim.api.nvim_exec_autocmds('VimLeavePre', {})
  t.eq(1, asked)
end)

t.test('running it twice leaves one set of reports', function()
  as_started()
  t.run('SCREEN_SETUP', tostring(t.channel))
  t.run('SCREEN_SETUP', tostring(t.channel))
  t.type('yy')
  t.eq(1, #t.sent('eclipse_screen_yank'))
end)
