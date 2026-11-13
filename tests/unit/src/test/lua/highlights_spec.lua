-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- The yank flash and window matches (docs/design/SEARCH_HIGHLIGHTING.md, "The yank flash and window
-- matches"): LuaScripts.HIGHLIGHTS works out the window's highlights in its visible lines and sends
-- them as eclipse_highlights(buf, ranges, win), six numbers each. Without a UI there is no redraw,
-- so no on_win; the tests call update() as the scheduled callback would.

local t = ...

-- Colors of the tests' own, so that no test depends on Neovim's default colorscheme.
local BG, FG = 0x112233, 0xAABBCC

--- The module, installed as the session does: SETUP (the channel), then HIGHLIGHTS.
local function start(lines)
  t.setup()
  t.eq(true, t.run('HIGHLIGHTS'), 'the highlights are on by default')
  vim.cmd('enew!')
  vim.api.nvim_buf_set_lines(0, 0, -1, false, lines)
  vim.api.nvim_set_hl(0, 'Mine', { bg = BG })
  vim.api.nvim_set_hl(0, 'Paired', { bg = BG, fg = FG })
  t.forget()
  return package.loaded['neovim4eclipse.highlights']
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

--- The highlights update() sends now, as {line, startbyte, endline, endbyte, bg, fg} lists.
local function highlights(m)
  local flat = m.update()
  local out = {}
  for i = 1, #flat, 6 do
    out[#out + 1] = vim.list_slice(flat, i, i + 5)
  end
  return out
end

--- Has every yank flash, for a minute, in group Paired: as a user's TextYankPost autocommand does.
local function flash_on_yank()
  vim.api.nvim_create_autocmd('TextYankPost', {
    callback = function()
      vim.hl.on_yank({ higroup = 'Paired', timeout = 60000 })
    end,
  })
end

t.test('vim.g.neovim4eclipse_highlights = 0 turns them off', function()
  t.setup()
  vim.g.neovim4eclipse_highlights = 0
  t.eq(false, t.run('HIGHLIGHTS'))
  vim.g.neovim4eclipse_highlights = nil
end)

t.test('the flash of a yank is sent with its colors, and nothing once it is gone', function()
  local m = start({ 'foo bar', 'second' })
  flash_on_yank()
  t.type('wyiw')
  t.eq({ { 1, 4, 1, 7, BG, FG } }, highlights(m), 'bar, in Paired with its text color')
  vim.api.nvim_buf_clear_namespace(0, vim.api.nvim_create_namespace('nvim.hlyank'), 0, -1)
  t.eq({}, highlights(m), 'the timeout took it away')
  local sent = t.sent('eclipse_highlights')
  t.eq(2, #sent, 'one notification each')
  t.eq(vim.api.nvim_get_current_buf(), sent[1][1], 'the buffer')
  t.eq(vim.api.nvim_get_current_win(), sent[1][3], 'the window')
  t.eq({}, sent[2][2], 'an empty list clears them')
end)

t.test('a line-wise flash ends at the end of its last line, the file last line too', function()
  local m = start({ 'abc', 'défg' })
  flash_on_yank()
  t.type('Gyy')
  t.eq({ { 2, 0, 2, 5, BG, FG } }, highlights(m), 'défg is five bytes; line 3 does not exist')
  t.type('ggyj')
  t.eq({ { 1, 0, 2, 5, BG, FG } }, highlights(m), 'both lines')
end)

t.test('a :match pattern is found in the visible lines, in exact case whatever ignorecase says', function()
  local m = start({ 'x X x', 'none', 'X' })
  vim.o.ignorecase = true
  vim.cmd('match Mine /X/')
  t.eq({ { 1, 2, 1, 3, BG, -1 }, { 3, 0, 3, 1, BG, -1 } }, highlights(m), 'only X; no text color: Eclipse keeps its own')
  vim.cmd('match none')
  t.eq({}, highlights(m))
end)

t.test('matchaddpos() positions are sent, a line alone as the whole line', function()
  local m = start({ 'abcdef', 'whole line' })
  vim.fn.matchaddpos('Mine', { { 1, 2, 3 }, { 2 } })
  t.eq({ { 1, 1, 1, 4, BG, -1 }, { 2, 0, 2, 10, BG, -1 } }, highlights(m))
end)

t.test('matchparen and groups without a 24-bit background are left out', function()
  local m = start({ 'foo(bar)' })
  vim.fn.matchadd('MatchParen', 'foo')
  vim.api.nvim_set_hl(0, 'TextOnly', { fg = FG })
  vim.fn.matchadd('TextOnly', 'bar')
  t.eq({}, highlights(m))
end)

t.test('the highest priority comes last, so Eclipse paints it on top', function()
  local m = start({ 'foo bar' })
  flash_on_yank()
  vim.fn.matchadd('Mine', 'foo', 20)
  t.type('yiw')
  t.eq({ { 1, 0, 1, 3, BG, -1 }, { 1, 0, 1, 3, BG, FG } }, highlights(m), 'the match (20), then the flash (200)')
end)

t.test('the same highlights are sent once; an edit sends them again', function()
  local m = start({ 'foo', 'bar' })
  vim.cmd('match Mine /foo/')
  m.update()
  m.update()
  t.eq(1, #t.sent('eclipse_highlights'), 'no change, no notification')
  vim.api.nvim_buf_set_lines(0, 1, 2, false, { 'baz' })
  m.update()
  t.eq(2, #t.sent('eclipse_highlights'), 'the editor may have dropped one the edit touched')
end)

t.test('the editor\'s own lines are looked at too, and resend sends again', function()
  local lines = {}
  for i = 1, 300 do
    lines[i] = i == 250 and 'target' or 'line'
  end
  local m = start(lines)
  vim.cmd('match Mine /target/')
  t.eq({}, highlights(m), 'line 250 is not in the window')
  m.set_view(nil, 240, 260)
  flush()
  t.eq({ { 250, 0, 250, 6, BG, -1 } }, highlights(m), 'the editor shows it')
  t.forget()
  m.resend()
  flush()
  t.eq(1, #t.sent('eclipse_highlights'), 'sent though nothing changed')
end)

t.test('a group given new colors after its match sends them', function()
  local m = start({ 'foo' })
  vim.cmd('match Mine /foo/')
  m.update()
  vim.api.nvim_set_hl(0, 'Mine', { bg = 0x445566 }) -- :highlight, or a new colorscheme
  m.update()
  local sent = t.sent('eclipse_highlights')
  t.eq(2, #sent, 'a change')
  t.eq({ 1, 0, 1, 3, 0x445566, -1 }, sent[2][2], 'read again')
end)

t.test('SEARCH_RESEND and SEARCH_VIEW reach the highlights too', function()
  local m = start({ 'foo' })
  vim.cmd('match Mine /foo/')
  m.update()
  t.forget()
  t.run('SEARCH_RESEND', tostring(vim.api.nvim_get_current_buf()))
  flush()
  t.eq(1, #t.sent('eclipse_highlights'), 'resent without the search module')
  t.run('SEARCH_VIEW', tostring(vim.api.nvim_get_current_buf()), '1', '1')
  flush()
end)
