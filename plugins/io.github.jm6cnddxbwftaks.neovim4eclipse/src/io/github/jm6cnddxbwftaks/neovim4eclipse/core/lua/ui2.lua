-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- Turns on Neovim's ui2, which draws the command line, its prompts, the pager and messages itself,
-- in four floating windows, so that the editor only copies their cells as it does any float's
-- (docs/ARCHITECTURE.md, "The command line and messages"). Run once at start, after the user's
-- config and after Eclipse's UI attached: ui2 does nothing without a UI. The one place the plug-in
-- talks to ui2 (`vim._core.ui2`, experimental in Neovim 0.12), so that a Neovim upgrade that
-- changes it is met here, and in `ui2_spec.lua`, which pins each ui2 behavior the plug-in relies
-- on.
--
-- ui2's four windows, each a float with its own buffer whose `'filetype'` names it: `cmd` (the
-- command line, and short messages after a command), `msg` (messages while typing), `pager` (`g<`,
-- long output) and `dialog` (prompts such as `confirm()`). ui2 makes them again when one is closed,
-- and fires `FileType` each time.
--
-- What this adds to ui2:
-- - `'cmdheight'` 1, set after ui2 is on. ui2 leaves it at 0 and grows it to the line's height
--   while a `:` line is open; the window above then shrinks a row, and with the cursor on its last
--   row Neovim scrolls (0.12) or moves the cursor (0.13). At 1 the window keeps its height: the
--   screen is one row taller than the editor, and ui2's line lives in that row.
-- - A user's `'cmdheight'` 0 (in `init.lua`, or `:set ch=0` later), Neovim's way to give the line's
--   row to the text, is kept as a wish, not as the option: Neovim stays at 1, for the reason above,
--   and Eclipse is told (`overlay`, the fifth value of `eclipse_ui2_windows`). The editor then
--   keeps no row for the line and draws it over the status line while one is open (`util/Grids`,
--   `editor/StatusLineRow`), as Neovim does with 0. Messages go to ui2's message window then
--   (`msg`, at the bottom right, gone after a few seconds), as they would with nowhere to stay;
--   unless the user's config chose a place for them other than ui2's default. `:set ch=1` or more
--   ends it. Example: `vim.o.cmdheight = 0`: the editor shows one more text row, `:` covers the
--   status line until Enter or Esc, and `:w`'s message shows at the bottom right.
-- - Each window as wide as the screen. ui2 opens them 10,000 cells wide and lets Neovim clip them;
--   a client of multigrid gets the full width, which would pass the editor's limit on a float's
--   cells (`util/Grids`). Set at `FileType` and after a resize; ui2 still narrows the message
--   window to its text.
-- - The windows' ids sent to Eclipse (`eclipse_ui2_windows`: cmd, msg, pager, dialog), at start and
--   each time ui2 makes one again: the editor draws these even with the cursor in them and from the
--   start, before plug-ins' floats are. The plug-in's other scripts (SETUP's `BufEnter`, WINDOWS'
--   safety net) leave ui2's own buffers to it (`ui2.bufs`), not its windows: a file opened in the
--   pager (`gf`, `:e`) is announced, and Eclipse sends Neovim back to the editor, as from any
--   window it does not show.
-- - `:messages` shown in its page editor, not in ui2's pager: the history goes to Eclipse
--   (`eclipse_messages`, each entry as `{kind, text}`), whose page keeps it open, searchable and
--   copyable with the mouse (docs/USER_GUIDE.md, "The page editors"). ui2's handler of the history
--   is wrapped for that; `g<`, which shows the last command's output through the same event, still
--   opens ui2's pager. An empty history shows nothing, as in Vim.
-- - The type of each command line as it opens and closes (`eclipse_cmdline`: `:`, `/`, `?` ...,
--   then `""`): while a search is typed the editor follows the preview Neovim scrolls to
--   (`'incsearch'`).
--
-- Neovim's popup menu (the `:` line's Tab list, insert-mode completion) is Neovim's own too: the UI
-- is attached without `ext_popupmenu` (`NeovimConnection`), so Neovim draws it into a grid of its
-- own, which the editor draws as a float. So the switch that once turned off the plug-in's own
-- completion list was removed in 0.3.0 (README, "Settings", Removed).
--
-- Returns `true`, or the error's text when ui2 cannot be loaded or turned on (a Neovim without it:
-- the session then logs it and shows Neovim's line nowhere).
--
-- Sent as LuaScripts.UI2 (core/LuaModules runs this file as a chunk of its own).

local ok, ui2 = pcall(require, 'vim._core.ui2')
if not ok then
  return 'ui2 not available: ' .. tostring(ui2)
end
local group = vim.api.nvim_create_augroup('neovim4eclipse_ui2', { clear = true })
local kinds = { 'cmd', 'msg', 'pager', 'dialog' }

local function fit(win)
  if vim.api.nvim_win_is_valid(win) then
    pcall(vim.api.nvim_win_set_config, win, { width = vim.o.columns })
  end
end

-- 'cmdheight' 0, the user's wish: Neovim's own stays 1 (see above), the editor draws the
-- line over the status line. Read before ui2 and this script touch the option. Only a 0
-- a script set (init.lua, --cmd: last_set_sid not 0) is the user's: Eclipse's UI attaches
-- with ext_messages, and Neovim then sets 0 itself, with no script (measured on 0.12.6).
local overlay = vim.o.cmdheight == 0
  and vim.api.nvim_get_option_info2('cmdheight', {}).last_set_sid ~= 0

local function send()
  local w = ui2.wins
  vim.rpcnotify(vim.g.neovim4eclipse_channel or 0, 'eclipse_ui2_windows', w.cmd, w.msg, w.pager, w.dialog, overlay)
end

-- With no row for the line, messages go to ui2's message window, which shows them for
-- a while and needs no row of its own; back when the row returns. Only ui2's default
-- target is moved: a user's config that chose 'msg' keeps it, and the plug-in puts
-- back only what it moved. A prompt's list (inputlist(), z=) still stays with its
-- prompt: ui2 moves it into the prompt's dialog window (NeovimUi2IntegrationTest).
local moved_messages = false
local function place_messages()
  if overlay and ui2.cfg.msg.target == 'cmd' then
    ui2.cfg.msg.target = 'msg'
    moved_messages = true
  elseif not overlay and moved_messages then
    ui2.cfg.msg.target = 'cmd'
    moved_messages = false
  end
end

vim.api.nvim_create_autocmd('FileType', {
  group = group,
  pattern = kinds,
  callback = function(ev)
    for _, win in ipairs(vim.fn.win_findbuf(ev.buf)) do
      fit(win)
    end
    -- After ui2 stored the new window's id (it fires FileType while making it).
    vim.schedule(send)
  end,
})
vim.api.nvim_create_autocmd('VimResized', {
  group = group,
  callback = function()
    for _, kind in ipairs({ 'cmd', 'pager', 'dialog' }) do
      fit(ui2.wins[kind])
    end
  end,
})

local enabled, err = pcall(ui2.enable, {})
if not enabled then
  return 'ui2 not turned on: ' .. tostring(err)
end
if not ui2.msg then
  return 'ui2 not turned on (its enable returned early: no UI, or the config set enable = false)'
end
-- Windows ui2 made before this ran (the user's init.lua turned ui2 on first): no
-- FileType comes for them, so they are fitted here.
for _, kind in ipairs(kinds) do
  fit(ui2.wins[kind])
end

-- :messages to its page editor; g< (prev_cmd) to ui2's pager as before.
local messages = ui2.msg
local history_show = messages.msg_history_show
messages.msg_history_show = function(entries, prev_cmd, ...)
  if prev_cmd then
    return history_show(entries, prev_cmd, ...)
  end
  if #entries == 0 then
    return
  end
  local sent = {}
  for _, entry in ipairs(entries) do
    local text = {}
    for _, chunk in ipairs(entry[2] or {}) do
      text[#text + 1] = chunk[2]
    end
    sent[#sent + 1] = { entry[1], table.concat(text) }
  end
  vim.rpcnotify(vim.g.neovim4eclipse_channel or 0, 'eclipse_messages', sent)
end

-- With the overlay, output of more than one line (:ls, :reg, :!cmd, a plug-in's report)
-- goes to ui2's line as with a row, not to the message window, which would drop it
-- after a few seconds: ui2 then expands the line upward until the next key, as Neovim
-- with 'cmdheight' 0 keeps such output on screen, and the editor draws the expanded
-- line (util/Grids). Only while the plug-in moved the target (a config's own choice
-- stands), and not errors: on the line ui2 shows an error's first row only, with
-- "[+x]", and that row is the one the overlay hides; in the message window it is
-- whole. One-line messages stay in the message window.
local msg_show = messages.msg_show
local errors = { rpc_error = true, emsg = true, echoerr = true, lua_error = true }
messages.msg_show = function(kind, content, ...)
  if moved_messages and not errors[kind] then
    local text = {}
    for _, chunk in ipairs(content or {}) do
      text[#text + 1] = chunk[2]
    end
    if table.concat(text):find(string.char(10), 1, true) then -- a line break (a backslash escape is Java's too)
      -- ui2 forgets the previous message only for windows of its target, now 'msg':
      -- the same output again would count as a repeat and not expand the line.
      if not messages.cmd_on_key then
        messages.prev_msg = ''
      end
      ui2.cfg.msg.target = 'cmd'
      local ok, err = pcall(msg_show, kind, content, ...)
      ui2.cfg.msg.target = 'msg'
      if not ok then
        error(err, 0)
      end
      return
    end
  end
  return msg_show(kind, content, ...)
end
-- The "[+N]" ui2 keeps for the line's rows past its one is put where its target says,
-- 'msg' again by the time the expanded line collapses: it would show beside the next
-- message. With the overlay the line's one row is hidden, so the mark has no place.
local set_pos = messages.set_pos
messages.set_pos = function(tgt, ...)
  local result = set_pos(tgt, ...)
  if moved_messages and tgt == 'cmd' then
    messages.virt.msg[messages.virt.idx.spill][1] = nil
  end
  return result
end

-- A stack, for a line opened inside another (<C-r>= on a : line): when the inner one
-- closes, Eclipse hears the outer one's type again, not "closed".
local open_lines = {}
vim.api.nvim_create_autocmd({ 'CmdlineEnter', 'CmdlineLeave' }, {
  group = group,
  callback = function(ev)
    if ev.event == 'CmdlineEnter' then
      open_lines[#open_lines + 1] = vim.fn.getcmdtype()
    else
      open_lines[#open_lines] = nil
    end
    vim.rpcnotify(vim.g.neovim4eclipse_channel or 0, 'eclipse_cmdline', open_lines[#open_lines] or '')
  end,
})
vim.o.cmdheight = 1
place_messages()

-- :set ch=0 later turns the overlay on, :set ch=1 (or more) off; Neovim's own goes back to
-- 1 after a 0. Made after the line above, so that its own set is not taken for the
-- user's; `restoring` does the same for the set back to 1. Scheduled: setting an option
-- inside its own OptionSet is not seen by the other handlers (ui2's hides its line
-- window at 0 and shows it again at the 1).
local restoring = false
-- Counts the user's sets: a restore scheduled for a 0 runs only if no set came after
-- it (:set ch=0 | set ch=2 ends at 2). Not the option's value: ui2 changes that itself,
-- without autocommands, while a line is open, so a line opened before the restore
-- would make it look done, and ui2 would then leave 0 for good.
local sets = 0
-- Neovim's own back to 1, the user's 0 kept as the overlay; Eclipse hears it.
local function restore()
  restoring = true
  pcall(function()
    vim.o.cmdheight = 1
  end)
  restoring = false
  send()
end
vim.api.nvim_create_autocmd('OptionSet', {
  group = group,
  pattern = 'cmdheight',
  callback = function()
    if restoring then
      return
    end
    sets = sets + 1
    local mine = sets
    local zero = tonumber(vim.v.option_new) == 0
    if zero ~= overlay then
      overlay = zero
      place_messages()
    end
    if zero then
      vim.schedule(function()
        if sets ~= mine then
          return -- set again meanwhile (:set ch=0 | set ch=2): that one stands
        end
        restore()
      end)
    else
      send()
    end
  end,
})
-- A 0 set where OptionSet does not fire (:noautocmd, an autocommand without `nested`,
-- such as a config's RecordingLeave that sets ch=0) is taken the same way, at the next
-- moment Neovim waits for input: one check every route passes, not one per route.
-- Neovim's own is never 0 otherwise once this script ran (ui2 goes to 0 only after a
-- 0 it heard through OptionSet, which the handler above sets back to 1).
-- Nested: the set back to 1 must fire OptionSet, so that ui2 hears it (its own handler
-- would otherwise keep 0 and set Neovim's option to 0 again at each line's end); the
-- plug-in's own handler skips it (`restoring`).
vim.api.nvim_create_autocmd('SafeState', {
  group = group,
  nested = true,
  callback = function()
    if vim.o.cmdheight ~= 0 or restoring then
      return
    end
    sets = sets + 1 -- a restore still scheduled for an earlier 0 is this one's
    if not overlay then
      overlay = true
      place_messages()
    end
    restore()
  end,
})
send()
return true
