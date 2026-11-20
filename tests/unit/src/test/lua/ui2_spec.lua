-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- LuaScripts.UI2: Neovim's ui2 draws the command line, prompts, pager and messages in four
-- floating windows, and the editor copies their cells. ui2 is experimental (Neovim 0.12), so each
-- behavior of it the plug-in relies on is pinned here: a Neovim upgrade that changes one fails
-- here before it reaches a user (docs/UPGRADING.md, "Neovim"). See harness.lua for how these
-- tests run.
--
-- ui2 turns itself on only with a UI attached (nvim_list_uis), and the harness runs headless. The
-- spec answers that one question as if a UI were there; ui2's own windows, buffers and events
-- work without one, since ui2 is a Lua UI (vim.ui_attach), which needs no screen. What ui2 shows
-- comes with a redraw, which a headless Neovim does not make while keys are fed: the typed line in
-- the cmd window, the pager after g<, a prompt in the dialog window are checked with a real UI
-- (core/NeovimUi2IntegrationTest).

local t = ...

local function start()
  local list_uis = vim.api.nvim_list_uis
  vim.api.nvim_list_uis = function()
    return { { width = vim.o.columns, height = vim.o.lines } }
  end
  t.setup()
  -- As the session runs them: the windows module (its safety net) before ui2.
  local home_buf = vim.api.nvim_create_buf(false, true)
  vim.api.nvim_set_current_buf(home_buf)
  t.run('WINDOWS', tostring(home_buf))
  local ok = t.run('UI2')
  vim.api.nvim_list_uis = list_uis
  t.eq(true, ok, 'UI2 turned ui2 on')
  return require('vim._core.ui2')
end

--- The ids the last eclipse_ui2_windows sent: { cmd, msg, pager, dialog }.
local function sent_windows()
  local sent = t.sent('eclipse_ui2_windows')
  t.ok(#sent > 0, 'eclipse_ui2_windows was sent')
  local last = sent[#sent]
  return { last[1], last[2], last[3], last[4] }
end

t.test('four floating windows, named by their filetype, are sent to Eclipse', function()
  local ui2 = start()
  local ids = {}
  for i, kind in ipairs({ 'cmd', 'msg', 'pager', 'dialog' }) do
    local win = ui2.wins[kind]
    t.ok(vim.api.nvim_win_is_valid(win), kind .. ' window exists')
    t.ok(vim.api.nvim_win_get_config(win).relative ~= '', kind .. ' window floats')
    t.eq(kind, vim.bo[vim.api.nvim_win_get_buf(win)].filetype, kind .. ' window filetype')
    ids[i] = win
  end
  t.eq(ids, sent_windows())
end)

t.test("'cmdheight' is 1, so a ':' line does not shrink the window above it", function()
  start()
  t.eq(1, vim.o.cmdheight)
  local height = vim.api.nvim_win_get_height(0)
  t.during(':echo', function()
    t.eq(1, vim.o.cmdheight, "'cmdheight' while the line is open")
    t.eq(height, vim.api.nvim_win_get_height(0), 'the window keeps its height')
  end)
end)

t.test('the windows are as wide as the screen, not 10,000 cells', function()
  local ui2 = start()
  for _, kind in ipairs({ 'cmd', 'pager', 'dialog' }) do
    t.eq(vim.o.columns, vim.api.nvim_win_get_width(ui2.wins[kind]), kind .. ' width')
  end
  t.ok(vim.api.nvim_win_get_width(ui2.wins.msg) <= vim.o.columns, 'msg width')
end)

t.test('a window ui2 lost comes back, and its new id is sent', function()
  local ui2 = start()
  local old = ui2.wins.pager
  vim.api.nvim_win_close(old, true)
  t.forget()
  t.type(':echo "again"<CR>') -- ui2 checks its windows at each event
  t.wait_for('the new id sent', function()
    return #t.sent('eclipse_ui2_windows') > 0
  end)
  local new = ui2.wins.pager
  t.ok(new ~= old and vim.api.nvim_win_is_valid(new), 'a new pager window')
  t.eq(new, sent_windows()[3])
  t.eq(vim.o.columns, vim.api.nvim_win_get_width(new), 'the new window is fitted too')
end)

t.test("each of ui2's windows is marked for the plug-in's other scripts", function()
  local ui2 = start()
  for _, kind in ipairs({ 'cmd', 'msg', 'pager', 'dialog' }) do
    t.eq(kind, vim.w[ui2.wins[kind]].neovim4eclipse_ui2, kind)
  end
end)

t.test('entering the pager tells Eclipse nothing, so it does not send Neovim back', function()
  local ui2 = start()
  local editor = vim.api.nvim_get_current_win()
  t.forget()
  vim.api.nvim_set_current_win(ui2.wins.pager)
  t.eq({}, t.sent('eclipse_buf_enter'), 'no buf enter for the pager')
  vim.api.nvim_set_current_win(editor)
  t.eq(1, #t.sent('eclipse_buf_enter'), 'back in the editor, as before')
end)

t.test("the safety net leaves ui2's window open while the cursor is in it", function()
  local ui2 = start()
  vim.api.nvim_set_current_win(ui2.wins.pager)
  -- Any new window makes the net look at every window; a float the user is in would be closed.
  local other = vim.api.nvim_open_win(vim.api.nvim_create_buf(false, true), false,
    { relative = 'editor', row = 1, col = 1, width = 5, height = 1 })
  -- The net runs scheduled, from WinNew; a callback scheduled after it runs after it.
  local after = false
  vim.schedule(function()
    after = true
  end)
  t.wait_for('the net ran', function()
    return after
  end)
  t.ok(vim.api.nvim_win_is_valid(ui2.wins.pager), 'the pager is still open')
  t.eq(ui2.wins.pager, vim.api.nvim_get_current_win(), 'and still has the cursor')
  vim.api.nvim_win_close(other, true)
end)
