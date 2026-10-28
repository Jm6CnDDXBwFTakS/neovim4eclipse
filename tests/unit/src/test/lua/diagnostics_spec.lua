-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- Eclipse's problems as Neovim diagnostics: SET_DIAGNOSTICS fills a buffer's list, Neovim's own
-- default ]d [d ]D [D move between them, SETUP's on_jump hook echoes the message landed on, and
-- PROBLEMS reads the opt-out and, off, has ]d say why it finds nothing
-- (docs/design/DIAGNOSTICS.md). How the arguments are made is DiagnosticTest and
-- DiagnosticPositionsTest; when Eclipse sends them, DiagnosticsSyncTest (tests/text).

local t = ...

local function ns()
  return vim.api.nvim_create_namespace('neovim4eclipse')
end

local function cursor_line()
  return vim.api.nvim_win_get_cursor(0)[1]
end

--- A 10-line buffer with problems on lines 3, 6 and 9 (1-based), as Eclipse would send them.
local function buffer_with_problems()
  local buf = vim.api.nvim_get_current_buf()
  local lines = {}
  for i = 1, 10 do
    lines[i] = 'line ' .. i
  end
  vim.api.nvim_buf_set_lines(buf, 0, -1, false, lines)
  t.run('SET_DIAGNOSTICS', tostring(buf),
    '2', '0', '2', '4', '1', 'first error',
    '5', '5', '5', '6', '2', 'a warning\nwith a second line',
    '8', '0', '8', '1', '3', 'some info')
  vim.api.nvim_win_set_cursor(0, { 1, 0 })
  return buf
end

--- Records what is echoed with nvim_echo (what reaches Eclipse's message line, through
--- ext_messages); the hook echoes without history, so :messages would not show it.
local echoed = {}
local real_echo = vim.api.nvim_echo
vim.api.nvim_echo = function(chunks, history, opts)
  echoed[#echoed + 1] = table.concat(vim.tbl_map(function(c) return c[1] end, chunks))
  return real_echo(chunks, history, opts)
end

t.test('SET_DIAGNOSTICS puts the problems in the neovim4eclipse namespace', function()
  local buf = buffer_with_problems()
  local got = vim.diagnostic.get(buf, { namespace = ns() })
  t.eq(3, #got)
  t.eq({ 2, 0, 2, 4, vim.diagnostic.severity.ERROR, 'first error', 'Eclipse' },
    { got[1].lnum, got[1].col, got[1].end_lnum, got[1].end_col, got[1].severity, got[1].message, got[1].source })
  t.eq(vim.diagnostic.severity.WARN, got[2].severity)
  t.eq('a warning\nwith a second line', got[2].message, 'a line break in a message arrives as it is')
  t.eq(vim.diagnostic.severity.INFO, got[3].severity)
end)

t.test('each call replaces the list, and the buffer number alone clears it', function()
  local buf = buffer_with_problems()
  t.run('SET_DIAGNOSTICS', tostring(buf), '0', '0', '0', '1', '1', 'only this one')
  t.eq({ 'only this one' }, vim.tbl_map(function(d) return d.message end, vim.diagnostic.get(buf)))
  t.run('SET_DIAGNOSTICS', tostring(buf))
  t.eq({}, vim.diagnostic.get(buf))
end)

t.test('a buffer that is gone is left alone, and a problem past the end is accepted', function()
  t.run('SET_DIAGNOSTICS', '999', '0', '0', '0', '1', '1', 'x') -- no error
  local buf = vim.api.nvim_get_current_buf()
  vim.api.nvim_buf_set_lines(buf, 0, -1, false, { 'a' })
  t.run('SET_DIAGNOSTICS', tostring(buf), '7', '3', '9', '0', '1', 'stale')
  t.eq(1, #vim.diagnostic.get(buf))
end)

t.test('Neovim\'s ]d and [d move between them, with a count', function()
  t.setup()
  buffer_with_problems()
  t.type(']d')
  t.eq(3, cursor_line())
  t.type('2]d')
  t.eq(9, cursor_line(), '2]d skips the warning on line 6')
  t.type('[d')
  t.eq(6, cursor_line())
  t.type('[D')
  t.eq(3, cursor_line(), '[D: the first')
  t.type(']D')
  t.eq(9, cursor_line(), ']D: the last')
end)

t.test(']d is a jump: Ctrl-O comes back', function()
  t.setup()
  buffer_with_problems()
  vim.api.nvim_win_set_cursor(0, { 2, 0 })
  t.type(']d')
  t.eq(3, cursor_line())
  t.type('<C-o>')
  t.eq(2, cursor_line())
end)

t.test('the message landed on is echoed, its first line only', function()
  t.setup()
  buffer_with_problems()
  t.type(']d')
  t.wait_for('the echo of the first error', function() return echoed[#echoed] == 'first error' end)
  t.type(']d')
  t.wait_for('the echo of the warning', function() return #echoed == 2 end)
  t.eq('a warning', echoed[2], 'only the first line')
end)

t.test('a user\'s own on_jump is kept', function()
  local mine = function() end
  vim.diagnostic.config({ jump = { on_jump = mine } })
  t.setup()
  t.eq(mine, vim.diagnostic.config().jump.on_jump)
end)

t.test('nothing of Eclipse\'s problems is drawn; other namespaces keep their display', function()
  t.setup()
  local config = vim.diagnostic.config(nil, ns())
  t.eq({ false, false, false, false, false },
    { config.virtual_text, config.virtual_lines, config.signs, config.underline, config.update_in_insert })
  t.ok(vim.diagnostic.config().underline ~= false, 'the global setting is untouched')
end)

-- ---- the opt-out: vim.g.neovim4eclipse_problems = 0 (LuaScripts.PROBLEMS) ----

--- What ]d is mapped to now: its description and its function.
local function bracket_d()
  local m = vim.fn.maparg(']d', 'n', false, true)
  return m.desc, m.callback
end

t.test('problems are sent unless the config says 0 or false; on, Neovim\'s keys are untouched', function()
  local _, neovims = bracket_d()
  t.eq(true, t.run('PROBLEMS'), 'not set')
  vim.g.neovim4eclipse_problems = 1
  t.eq(true, t.run('PROBLEMS'), '1')
  local desc, callback = bracket_d()
  t.eq(neovims, callback, 'still Neovim\'s own ]d')
  t.eq('Jump to the next diagnostic in the current buffer', desc)
  vim.g.neovim4eclipse_problems = false
  t.eq(false, t.run('PROBLEMS'), 'false')
end)

t.test('off, ]d [d ]D [D say why they find nothing, and do not move', function()
  vim.g.neovim4eclipse_problems = 0
  t.eq(false, t.run('PROBLEMS'))
  local buf = vim.api.nvim_get_current_buf()
  vim.api.nvim_buf_set_lines(buf, 0, -1, false, { 'a', 'b', 'c' })
  for _, key in ipairs({ ']d', '[d', ']D', '[D' }) do
    t.type(key)
    t.eq(key .. ": Eclipse's problems are not sent to Neovim (vim.g.neovim4eclipse_problems = 0)",
      echoed[#echoed])
    t.eq(1, cursor_line(), key .. ' does not move')
  end
end)

t.test('off, diagnostics of the user\'s own still work through Neovim\'s ]d, with a count', function()
  vim.g.neovim4eclipse_problems = 0
  t.run('PROBLEMS')
  t.setup() -- the on_jump echo, as in the session
  local buf = vim.api.nvim_get_current_buf()
  vim.api.nvim_buf_set_lines(buf, 0, -1, false, { 'a', 'b', 'c', 'd', 'e' })
  local mine = vim.api.nvim_create_namespace('mine') -- as nvim-lint would
  vim.diagnostic.set(mine, buf, {
    { lnum = 1, col = 0, message = 'lint one' },
    { lnum = 3, col = 0, message = 'lint two' },
  })
  vim.api.nvim_win_set_cursor(0, { 1, 0 })
  t.type('2]d')
  t.eq(4, cursor_line(), '2]d: the count reaches Neovim\'s own ]d')
  t.wait_for('the on_jump echo', function() return echoed[#echoed] == 'lint two' end)
end)

t.test('off, a ]d of the user\'s that copies Neovim\'s description is left alone too', function()
  local mine = function() end
  vim.keymap.set('n', ']d', mine, { desc = 'Jump to the next diagnostic in the current buffer' })
  vim.g.neovim4eclipse_problems = 0
  t.run('PROBLEMS')
  t.eq(mine, vim.fn.maparg(']d', 'n', false, true).callback, 'its function names this file, not Neovim\'s runtime')
end)

t.test('off, a ]d the user mapped is left alone', function()
  local mine = function() end
  vim.keymap.set('n', ']d', mine, { desc = 'mine' })
  vim.g.neovim4eclipse_problems = 0
  t.run('PROBLEMS')
  t.eq('mine', (bracket_d()))
  t.eq(mine, vim.fn.maparg(']d', 'n', false, true).callback)
end)
