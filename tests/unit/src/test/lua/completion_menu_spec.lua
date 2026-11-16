-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- Neovim's completion list drawn in the editor (docs/ARCHITECTURE.md, "The completion list"):
-- LuaScripts.COMPLETION_MENU, the opt-out and what it tells the editor at each change of the list
-- (the cursor, 'pumheight', the Pmenu groups' colors). How the list is laid out and placed is
-- CompletionMenuTest; the keys and the list's entries through a session with a UI attached,
-- NeovimSessionIntegrationTest (the entries come with the redraw, which this headless Neovim,
-- having no UI, does not send).

local t = ...

local function enabled()
  return t.run('COMPLETION_MENU')
end

local function module()
  return package.loaded['neovim4eclipse.completion_menu']
end

--- The reports sent while `keys` are typed in insert mode on a buffer of `lines`.
local function reports(lines, keys)
  vim.api.nvim_buf_set_lines(0, 0, -1, false, lines)
  vim.api.nvim_win_set_cursor(0, { #lines, 0 })
  t.forget()
  return t.during(keys, function()
    return t.sent('eclipse_complete')
  end)
end

-- ---- the opt-out ----

t.test('the list is on unless the config says 0 or false', function()
  t.eq(true, enabled(), 'not set')
  vim.g.neovim4eclipse_completion_menu = 0
  t.eq(false, enabled(), '0')
  vim.g.neovim4eclipse_completion_menu = false
  t.eq(false, enabled(), 'false')
  vim.g.neovim4eclipse_completion_menu = 1
  t.eq(true, enabled(), '1')
end)

t.test('turned off, nothing is installed and nothing is sent', function()
  vim.g.neovim4eclipse_completion_menu = 0
  enabled()
  t.eq(nil, module())
  t.eq({}, reports({ 'alpha', 'alphabet', '' }, 'ial<C-n>'))
end)

-- ---- what is sent ----

t.test('a list reports the cursor: line, byte column, its cell in the window', function()
  enabled()
  local sent = reports({ 'alpha', 'alphabet', '' }, 'ial<C-n>')
  t.ok(#sent >= 1, 'a report for the list')
  local last = sent[#sent]
  -- "al" completed to the first match, "alpha": the cursor is after it.
  t.eq(3, last[1], 'line')
  t.eq(5, last[2], 'byte column')
  t.eq(6, last[3], 'cell, 1-based')
end)

t.test('a window that wraps reports how many cells of text a row holds, one that does not reports 0', function()
  enabled()
  vim.wo.wrap = true
  vim.wo.number = true
  vim.wo.numberwidth = 4
  local sent = reports({ 'alpha', 'alphabet', '' }, 'ial<C-n>')
  t.eq(vim.api.nvim_win_get_width(0) - 4, sent[#sent][6], 'the width without the number column')
  vim.cmd('stopinsert')
  vim.wo.wrap = false
  sent = reports({ 'alpha', 'alphabet', '' }, 'ial<C-n>')
  t.eq(0, sent[#sent][6], 'nowrap')
end)

t.test('the cell counts the number column, the byte column does not', function()
  enabled()
  vim.wo.number = true
  vim.wo.numberwidth = 4
  local sent = reports({ 'alpha', 'alphabet', '' }, 'ial<C-n>')
  local last = sent[#sent]
  t.eq(5, last[2], 'byte column')
  t.eq(10, last[3], 'cell: four cells of line number first')
end)

t.test('a wide character is three bytes and two cells', function()
  enabled()
  local sent = reports({ 'alpha', 'alphabet', '' }, 'i日 al<C-n>')
  local last = sent[#sent]
  t.eq(9, last[2], 'byte column: 3 + 1 + 5')
  t.eq(9, last[3], 'cell: 2 + 1 + 5, and 1-based')
end)

t.test("'pumheight' comes along", function()
  enabled()
  vim.o.pumheight = 7
  local sent = reports({ 'alpha', 'alphabet', '' }, 'ial<C-n>')
  t.eq(7, sent[#sent][4])
end)

t.test('moving the selection reports again', function()
  enabled()
  local sent = reports({ 'alpha', 'alphabet', 'alpine', '' }, 'ial<C-n><C-n>')
  t.ok(#sent >= 2, 'one report per change of the list')
  t.eq(8, sent[#sent][2], 'the cursor after "alphabet"')
end)

-- ---- the colors ----

--- The group's colors as sent, { fg, bg, bold, italic, underline, reverse }, by its name.
local function sent_colors()
  local order = { 'Pmenu', 'PmenuSel', 'PmenuKind', 'PmenuKindSel', 'PmenuExtra', 'PmenuExtraSel', 'PmenuSbar',
    'PmenuThumb' }
  local list, by_name = module().colors(), {}
  for i, name in ipairs(order) do
    by_name[name] = list[i]
  end
  t.eq(#order, #list, 'one entry per group')
  return by_name
end

local function scheme(groups)
  vim.cmd('highlight clear')
  for name, value in pairs(groups) do
    vim.api.nvim_set_hl(0, name, value)
  end
  vim.api.nvim_exec_autocmds('ColorScheme', {})
end

t.test('a group that sets its colors is sent as it is', function()
  enabled()
  scheme({ Pmenu = { fg = 0xC0C0C0, bg = 0x202020 }, PmenuSel = { fg = 0x000000, bg = 0x80A0FF, bold = true } })
  local colors = sent_colors()
  t.eq({ 0xC0C0C0, 0x202020, false, false, false, false }, colors.Pmenu)
  t.eq({ 0x000000, 0x80A0FF, true, false, false, false }, colors.PmenuSel)
end)

t.test('a group that leaves a color open takes it from the group it is drawn over', function()
  enabled()
  scheme({
    Pmenu = { fg = 0xC0C0C0, bg = 0x202020 },
    PmenuSel = { bg = 0x80A0FF },
    PmenuKind = { fg = 0xFF0000 },
    PmenuKindSel = { fg = 0x00FF00 },
  })
  local colors = sent_colors()
  t.eq({ 0xC0C0C0, 0x80A0FF }, { colors.PmenuSel[1], colors.PmenuSel[2] }, 'the text color of Pmenu')
  t.eq({ 0xFF0000, 0x202020 }, { colors.PmenuKind[1], colors.PmenuKind[2] }, 'the background of Pmenu')
  t.eq({ 0x00FF00, 0x80A0FF }, { colors.PmenuKindSel[1], colors.PmenuKindSel[2] }, 'the background of PmenuSel')
end)

t.test('a selection that is only "reverse" is sent with Pmenu\'s colors and the flag', function()
  enabled()
  scheme({ Pmenu = { fg = 0xC0C0C0, bg = 0x202020 }, PmenuSel = { reverse = true } })
  t.eq({ 0xC0C0C0, 0x202020, false, false, false, true }, sent_colors().PmenuSel)
end)

t.test('reverse is a flag, not a toggle: a group over a reversed one is reversed once', function()
  enabled()
  -- The default colorscheme: PmenuSel is only "reverse", and the Sel forms of kind and extra link to it.
  scheme({
    Pmenu = { fg = 0xC0C0C0, bg = 0x202020 },
    PmenuSel = { reverse = true },
    PmenuExtraSel = { link = 'PmenuSel' },
    PmenuKindSel = { fg = 0xFF0000 },
  })
  local colors = sent_colors()
  t.eq({ 0xC0C0C0, 0x202020, false, false, false, true }, colors.PmenuExtraSel, 'as PmenuSel: the row is selected throughout')
  t.eq({ 0xFF0000, 0x202020, false, false, false, true }, colors.PmenuKindSel, 'its own text color, reversed with the row')
  t.eq(false, colors.PmenuKind[6], 'an unselected row is not reversed')
end)

t.test('a link is followed', function()
  enabled()
  scheme({ Pmenu = { fg = 0xC0C0C0, bg = 0x202020 }, Visual = { bg = 0x445566 }, PmenuSel = { link = 'Visual' } })
  local selected = sent_colors().PmenuSel
  t.eq(0x445566, selected[2])
  t.eq(0xC0C0C0, selected[1], "the link's group names no text color: Pmenu's")
end)

t.test('a color nobody sets, not even Normal, is sent as -1', function()
  enabled()
  scheme({ Pmenu = {}, Normal = {} })
  local pmenu = sent_colors().Pmenu
  t.eq({ -1, -1 }, { pmenu[1], pmenu[2] })
end)

t.test('the colors are looked up again after a colorscheme change', function()
  enabled()
  scheme({ Pmenu = { fg = 0x111111, bg = 0x222222 } })
  t.eq(0x222222, sent_colors().Pmenu[2])
  scheme({ Pmenu = { fg = 0x111111, bg = 0x333333 } })
  t.eq(0x333333, sent_colors().Pmenu[2])
end)

t.test('the colors come with each report', function()
  enabled()
  scheme({ Pmenu = { fg = 0xC0C0C0, bg = 0x202020 } })
  local sent = reports({ 'alpha', 'alphabet', '' }, 'ial<C-n>')
  t.eq(0x202020, sent[#sent][5][1][2])
end)
