-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- The Lua of a page editor besides SCREEN_SETUP (screen_setup_spec.lua): SCREEN_SHOW_TEXT, the
-- output profile in the second Neovim; and, in the editing Neovim, HEALTH_CHECK (the plug-in's
-- own :checkhealth neovim4eclipse) and SET_REGISTER_FROM_VIEW (a yank in the view, in a register).

local t = ...

--- SCREEN_SHOW_TEXT as core/OutputProfile calls it.
local function show(lines, filetype, highlights, background, cursor, title)
  return t.run('SCREEN_SHOW_TEXT', lines, filetype, highlights, background, cursor, title or 'messages')
end

t.test('the text replaces what was shown, unchangeable, its error lines colored', function()
  vim.api.nvim_buf_set_lines(0, 0, -1, false, { 'old', 'text', 'here' })
  t.eq(2, show({ 'plain', 'E486: x' }, '', { { 1, 'ErrorMsg' } }, 'dark', 'end'))
  t.eq({ 'plain', 'E486: x' }, vim.api.nvim_buf_get_lines(0, 0, -1, false))
  t.eq(false, vim.bo.modifiable)
  t.eq('nofile', vim.bo.buftype)
  t.eq('dark', vim.o.background)
  t.eq({ 2, 0 }, vim.api.nvim_win_get_cursor(0), ':messages: the newest message, last')
  local ns = vim.api.nvim_create_namespace('neovim4eclipse.view')
  local marks = vim.api.nvim_buf_get_extmarks(0, ns, 0, -1, { details = true })
  t.eq(1, #marks)
  t.eq(1, marks[1][2], 'on the error line')
  t.eq('ErrorMsg', marks[1][4].line_hl_group)
end)

t.test('new text clears the old highlights, sets its filetype and starts at the top', function()
  show({ 'a', 'E1: b' }, '', { { 1, 'ErrorMsg' } }, 'light', 'end')
  show({ 'vim.health: OK', '- OK fine' }, 'checkhealth', {}, 'light', 'top')
  local ns = vim.api.nvim_create_namespace('neovim4eclipse.view')
  t.eq({}, vim.api.nvim_buf_get_extmarks(0, ns, 0, -1, {}))
  t.eq('checkhealth', vim.bo.filetype)
  t.eq({ 1, 0 }, vim.api.nvim_win_get_cursor(0))
  t.eq('light', vim.o.background)
end)

t.test('the status line names what the view shows, not the scratch buffer', function()
  show({ 'x' }, '', {}, 'dark', 'top', 'checkhealth')
  t.eq(' checkhealth%=%l,%c%V  %P ', vim.wo.statusline)
  show({ 'x' }, '', {}, 'dark', 'top', '100% sure')
  t.eq(' 100%% sure%=%l,%c%V  %P ', vim.wo.statusline, 'a % shows as it is')
end)

t.test('q quits, as in Neovim\'s own :checkhealth', function()
  show({ 'x' }, '', {}, 'dark', 'top')
  t.eq('<Cmd>quit<CR>', vim.fn.maparg('q', 'n'))
end)

t.test('a highlight past the text is skipped, not an error', function()
  t.eq(1, show({ 'only line' }, '', { { 5, 'ErrorMsg' } }, 'dark', 'top'))
end)

--- HEALTH_CHECK with the stub written where :checkhealth looks for it, then the report's lines.
local function report(info)
  local folder = vim.fn.tempname()
  vim.fn.mkdir(folder .. '/lua/neovim4eclipse', 'p')
  vim.fn.writefile({ "return { check = function() vim.health.error('stub') end }" },
    folder .. '/lua/neovim4eclipse/health.lua')
  t.eq(true, t.run('HEALTH_CHECK', folder, info))
  local rtp = vim.opt.runtimepath:get()
  t.same_file(folder, rtp[#rtp], 'appended, last')
  vim.cmd('checkhealth neovim4eclipse')
  return table.concat(vim.api.nvim_buf_get_lines(vim.fn.bufnr('health://'), 0, -1, false), '\n')
end

local function info(program)
  return { minimum = '0.12.6', program = program, plugin = '0.2.27', eclipse = '4.38.0',
    readers = { 'loaded_netrw', 'loaded_zipPlugin' } }
end

t.test(':checkhealth neovim4eclipse: the dedicated Neovim, and what is off for the files you open', function()
  vim.g.loaded_netrw = 1
  vim.g.loaded_zipPlugin = 1
  vim.go.modeline = false
  local text = report(info('dedicated'))
  t.ok(not text:find('stub', 1, true), 'the registered check ran, not the file: ' .. text)
  t.ok(text:find('OK Neovim ' .. tostring(vim.version()):match('^%d+%.%d+%.%d+'), 1, true), text)
  t.ok(text:find('OK The dedicated Neovim', 1, true), text)
  t.ok(text:find('Neovim for Eclipse 0.2.27', 1, true), text)
  t.ok(text:find('Eclipse 4.38.0', 1, true), text)
  t.ok(text:find('OK Modelines are off', 1, true), text)
  t.ok(text:find('OK g:loaded_netrw is set', 1, true), text)
end)

t.test(':checkhealth neovim4eclipse warns of a program of your own and of what is on again', function()
  vim.g.loaded_netrw = nil
  vim.g.loaded_zipPlugin = 1
  vim.go.modeline = true
  local text = report(info('own'))
  t.ok(text:find('WARNING A Neovim program of your own', 1, true), text)
  t.ok(text:find('WARNING Modelines are on again', 1, true), text)
  t.ok(text:find('WARNING g:loaded_netrw is unset', 1, true), text)
  vim.go.modeline = false
end)

t.test(':checkhealth neovim4eclipse: a Neovim older than the minimum is an error', function()
  local text = report({ minimum = '99.0.0', program = 'dedicated', plugin = '0.2.27', eclipse = '',
    readers = {} })
  t.ok(text:find('ERROR Neovim ', 1, true) and text:find('older than 99.0.0', 1, true), text)
  t.ok(text:find('Eclipse version unknown', 1, true), text)
end)

t.test('a yank from the view fills the register of its name; the unnamed one follows clipboard', function()
  t.eq(true, t.run('SET_REGISTER_FROM_VIEW', 'a', 'V', { 'one' }))
  t.eq('one\n', vim.fn.getreg('a'))
  t.eq('V', vim.fn.getregtype('a'))
  vim.o.clipboard = ''
  t.eq(true, t.run('SET_REGISTER_FROM_VIEW', '', 'v', { 'plain' }))
  t.eq('plain', vim.fn.getreg('"'))
  t.eq(false, t.run('SET_REGISTER_FROM_VIEW', '=', 'v', { '1+1' }), 'no yank fills the expression register')
  t.eq(false, t.run('SET_REGISTER_FROM_VIEW', 'ab', 'v', { 'x' }), 'a register is one character')
end)
