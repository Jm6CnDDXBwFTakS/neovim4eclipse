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

t.test('a file opened in the pager is announced, so Eclipse can send Neovim back', function()
  local ui2 = start()
  vim.api.nvim_set_current_win(ui2.wins.pager)
  t.forget()
  local file = vim.fn.tempname()
  vim.fn.writefile({ 'x' }, file)
  vim.cmd.edit(vim.fn.fnameescape(file)) -- as gf or :e in the pager would
  local sent = t.sent('eclipse_buf_enter')
  t.eq(1, #sent, 'one buf enter, for the file')
  t.eq(vim.fn.bufnr(file), sent[1][1])
end)

t.test("ui2 turned on by the user's config first: its windows are fitted and sent too", function()
  local list_uis = vim.api.nvim_list_uis
  vim.api.nvim_list_uis = function()
    return { { width = vim.o.columns, height = vim.o.lines } }
  end
  t.setup()
  local ui2 = require('vim._core.ui2')
  ui2.enable({}) -- as an init.lua would, before the plug-in's scripts
  ui2.check_targets() -- its windows, which it makes at its first event
  t.eq(true, t.run('UI2'))
  vim.api.nvim_list_uis = list_uis
  for _, kind in ipairs({ 'cmd', 'pager', 'dialog' }) do
    t.eq(vim.o.columns, vim.api.nvim_win_get_width(ui2.wins[kind]), kind .. ' fitted')
  end
  t.eq(ui2.wins.pager, sent_windows()[3])
end)

t.test('entering the pager tells Eclipse nothing, so it does not send Neovim back', function()
  local ui2 = start()
  local editor = vim.api.nvim_get_current_win()
  t.forget()
  -- As ui2 enters its pager: it clears the window's 'eventignorewin' first, so BufEnter fires.
  vim.wo[ui2.wins.pager].eventignorewin = ''
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

t.test(':messages goes to Eclipse, each message with its kind, and not to the pager', function()
  local ui2 = start()
  vim.api.nvim_echo({ { 'a note' } }, true, {})
  vim.api.nvim_echo({ { 'it broke' } }, true, { err = true })
  t.forget()
  t.type(':messages<CR>')
  t.wait_for('the history sent', function()
    return #t.sent('eclipse_messages') > 0
  end)
  local entries = t.sent('eclipse_messages')[1][1]
  local last = entries[#entries]
  t.eq('it broke', last[2])
  t.eq('echoerr', last[1])
  t.ok(vim.api.nvim_get_current_win() ~= ui2.wins.pager, 'the pager was not entered')
end)

t.test('the type of each command line goes to Eclipse as it opens and closes', function()
  start()
  t.forget()
  t.type('/abc<Esc>')
  t.type(':echo<CR>')
  t.eq({ { '/' }, { '' }, { ':' }, { '' } }, t.sent('eclipse_cmdline'))
end)

t.test('a line inside a line: when the inner one ends, the outer one is still open', function()
  start()
  t.forget()
  t.type(':echo <C-r>=1<CR><Esc>')
  t.eq({ { ':' }, { '=' }, { ':' }, { '' } }, t.sent('eclipse_cmdline'))
end)

--- The fifth value of the last eclipse_ui2_windows: whether the user's 'cmdheight' is 0.
local function sent_overlay()
  local sent = t.sent('eclipse_ui2_windows')
  return sent[#sent][5]
end

t.test("a user's 'cmdheight' 0 stays a wish: Neovim's is 1, Eclipse hears it, messages move", function()
  vim.o.cmdheight = 0 -- as init.lua does, before the plug-in's script
  local ui2 = start()
  t.eq(1, vim.o.cmdheight, "Neovim's own is 1: a ':' line shrinks no window")
  t.eq(true, sent_overlay())
  t.eq('msg', ui2.cfg.msg.target, "messages to ui2's message window: the line has no row to stay on")
end)

t.test("no 'cmdheight' set by the user: the row stays, messages stay on it", function()
  local ui2 = start()
  t.eq(false, sent_overlay())
  t.eq('cmd', ui2.cfg.msg.target)
end)

t.test(':set ch=0 and :set ch=1 later switch both ways, and Neovim goes back to 1 after the 0', function()
  local ui2 = start()
  vim.cmd('set cmdheight=0')
  t.wait_for("'cmdheight' back at 1", function()
    return vim.o.cmdheight == 1
  end)
  t.eq(true, sent_overlay())
  t.eq('msg', ui2.cfg.msg.target)
  t.ok(not vim.api.nvim_win_get_config(ui2.wins.cmd).hide, "ui2's line window shown again at the 1")
  vim.cmd('set cmdheight=1')
  t.eq(false, sent_overlay())
  t.eq('cmd', ui2.cfg.msg.target, 'moved back')
end)

t.test(":set ch=0 then ch=2 in one line ends at 2: the restore is for a 0 still there", function()
  start()
  vim.cmd('set cmdheight=0 | set cmdheight=2')
  local after = false
  vim.schedule(function() -- runs after the restore the 0 scheduled
    after = true
  end)
  t.wait_for('the event loop', function()
    return after
  end)
  t.eq(2, vim.o.cmdheight)
  t.eq(false, sent_overlay())
end)

t.test("a config's own message target is kept when 'cmdheight' goes from 0 to 1", function()
  vim.o.cmdheight = 0
  require('vim._core.ui2').cfg.msg.target = 'msg'
  local ui2 = start()
  vim.cmd('set cmdheight=1')
  t.eq('msg', ui2.cfg.msg.target, "the config's choice, not moved back to 'cmd'")
end)
