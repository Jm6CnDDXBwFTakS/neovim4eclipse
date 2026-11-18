-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- LuaScripts.SCREEN_SETUP, which the second Neovim of a page editor runs once it started
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
  local answer = t.run('SCREEN_SETUP', tostring(t.channel))
  t.eq(2, answer.readers)
  t.ok(answer.first_reader == '(no group):BufReadCmd:term:*' or answer.first_reader == '(no group):SourceCmd:*',
    'the first reader left is named: ' .. tostring(answer.first_reader))
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

t.test('Ctrl-W moves among its own windows first, and goes on to Eclipse at the edge', function()
  as_started()
  t.run('SCREEN_SETUP', tostring(t.channel))
  vim.cmd('only')
  vim.cmd('belowright split') -- the list below the page, as :copen leaves it
  local below = vim.api.nvim_get_current_win()
  t.type('<C-w>k')
  t.ok(vim.api.nvim_get_current_win() ~= below, 'k: the window above, its own')
  t.eq({}, t.sent('eclipse_screen_window'))
  t.type('<C-w>k')
  t.eq({ { 'k', 0 } }, t.sent('eclipse_screen_window'), 'k from the top window: on to Eclipse')
  t.type('<C-w>j')
  t.eq(below, vim.api.nvim_get_current_win(), 'j: the window below')
  t.type('<C-w>w')
  t.eq({ { 'k', 0 }, { 'w', 0 } }, t.sent('eclipse_screen_window'), 'w from the last window: on to Eclipse, no wrap')
  t.type('<C-w>W')
  t.ok(vim.api.nvim_get_current_win() ~= below, 'W: the previous window, its own')
  t.type('<C-w>p')
  t.eq(below, vim.api.nvim_get_current_win(), 'p: the previous window, its own')
  t.type('<C-w>t')
  t.eq({ { 'k', 0 }, { 'w', 0 }, { 't', 0 } }, t.sent('eclipse_screen_window'), 't: always Eclipse\'s')
  t.type('1<C-w>w')
  t.ok(vim.api.nvim_get_current_win() ~= below, '1 Ctrl-W w: its own window 1')
  t.type('5<C-w>w')
  t.eq(below, vim.api.nvim_get_current_win(), '5 Ctrl-W w with two windows: the last, as in Vim')
  t.eq(3, #t.sent('eclipse_screen_window'), 'nothing more to Eclipse')
  vim.cmd('only')
end)

t.test(':help typed in a text page is not run there but handed to Eclipse, with its topic', function()
  as_started()
  t.run('SCREEN_SETUP', tostring(t.channel))
  vim.g.neovim4eclipse_help_page = nil
  t.forget()
  t.type(':help telescope<CR>')
  t.eq({ { 'telescope' } }, t.sent('eclipse_screen_help'))
  t.type(':vert h<CR>')
  t.eq({ { 'telescope' }, { '' } }, t.sent('eclipse_screen_help'), ':help alone: no topic')
  t.type(':echo 1<CR>')
  t.eq(2, #t.sent('eclipse_screen_help'), 'other commands run as typed')
  for _, win in ipairs(vim.api.nvim_list_wins()) do
    t.ok(vim.bo[vim.api.nvim_win_get_buf(win)].buftype ~= 'help', 'no help window here')
  end
end)

t.test("in the help editor's own Neovim, :help is its own", function()
  as_started()
  t.run('SCREEN_SETUP', tostring(t.channel))
  vim.g.neovim4eclipse_help_page = true
  t.forget()
  t.type(':help help-context<CR>')
  t.eq({}, t.sent('eclipse_screen_help'))
  t.eq('help', vim.bo.buftype)
  vim.cmd('helpclose')
  vim.g.neovim4eclipse_help_page = nil
end)
