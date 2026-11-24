-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- The windows module's floats at Neovim's start (part of `neovim4eclipse.windows`; init.lua says
-- how its files fit together): a float a config opened while Neovim started (lazy.nvim's
-- installer) is brought over the first editor Eclipse shows (M.bring_start_floats), and keys
-- typed meanwhile go where they would in a terminal, as long as the editor draws that window
-- (M.close_start_floats, run by LuaScripts.CLOSE_START_FLOAT). A float the editor cannot draw is
-- closed, with a message.
--
-- Example: LazyVim's first start: its installer stays over the first editor, with the keyboard,
-- and `q` closes it.

local M = package.loaded['neovim4eclipse.windows']
local S = package.loaded['neovim4eclipse.windows.shared']
local group = S.group
local own = S.own

-- Why a float with the keyboard that the editor does not draw is closed as the start ends
-- (M.close_start_floats). On M too, for the tests that look for the message
-- (float_closed_message(name, M.AT_START)).
local AT_START = "it had the keyboard while Neovim started and the editor does not show it,"
  .. " so the keys typed meanwhile go to your file (its plug-in's command opens it again)"
M.AT_START = AT_START

--- Says that a float was closed, or refused to close, as the start ended. Once the caller
--- is over (vim.schedule): an error message from inside UIEnter gets Neovim's "Error in
--- UIEnter Autocommands for "*":" above it, as if the check had failed. Said as an error
--- still, as the other closes are (and ui2 shows a long one in full).
local function say_at_start(message)
  vim.schedule(function()
    vim.api.nvim_echo({ { message } }, true, { err = true })
  end)
end

--- The first editor is shown in win: the float waiting for it gets the keyboard, as it
--- has in a terminal, if the editor draws it now (M.bring_start_floats brought it to win's
--- tab page; one hanging on a window or the cursor stays behind). If not, it is closed,
--- not by force, with a message: keys go only to a window Eclipse draws.
local function settle_start_float(win)
  local float = S.pending_start_float
  S.pending_start_float = nil
  if not float then
    return
  end
  local there = vim.api.nvim_win_is_valid(float) and M.focused_float(float)
  if there and not vim.api.nvim_win_get_config(float).hide
      and vim.api.nvim_win_get_tabpage(float) == vim.api.nvim_win_get_tabpage(win)
      and pcall(vim.api.nvim_set_current_win, float)
      and vim.api.nvim_get_current_win() == float then
    return
  end
  if there then
    local name = S.float_name(float)
    if pcall(vim.api.nvim_win_close, float, false) then
      say_at_start(M.float_closed_message(name, AT_START))
    else
      say_at_start(M.float_kept_message(name, AT_START))
    end
  end
end
S.settle_start_float = settle_start_float

--- While Neovim is still starting (v:vim_did_enter 0: a configuration still running,
--- lazy.nvim installing missing plug-ins in vim.wait), brings the plug-ins' floating
--- windows of the other tab pages into win's, the editor Eclipse shows: a float belongs to
--- one tab page, and one the configuration opened before any editor was there (lazy.nvim's
--- installer and its backdrop, in the home window's tab page) would otherwise be drawn
--- nowhere, the install looking like Eclipse hung. Moved as they are, placed on the screen
--- (relative 'editor'; one hanging on a window or the cursor stays where it is), with
--- the focus where it was. Once Neovim has started, floats stay in their own tab page,
--- as in Neovim. Neither the plug-in's own windows nor ui2's are moved. What closes such
--- a float at the start's end is close_start_floats. Example: lazy.nvim's window 1001 in
--- tab page 1, the first editor shown in tab page 2: 1001 moves to tab page 2, drawn
--- over the editor with its progress. starting: whether Neovim is still starting (M.show
--- passes v:vim_did_enter ~= 1; a spec passes it, being past the start).
function M.bring_start_floats(win, starting)
  if not starting or not vim.api.nvim_win_is_valid(win) then
    return
  end
  local tab = vim.api.nvim_win_get_tabpage(win)
  for _, w in ipairs(vim.api.nvim_list_wins()) do
    local config = vim.api.nvim_win_get_config(w)
    if not own[w] and config.relative == 'editor' and not S.shows_ui2_buffer(w)
        and vim.api.nvim_win_get_tabpage(w) ~= tab then
      config.win = win
      pcall(vim.api.nvim_win_set_config, w, config)
    end
  end
end

--- The one rule for keys typed while Neovim starts: they go where they would in a terminal,
--- to the window Neovim is in when it reads them, as long as the editor draws that window. A
--- config's init can keep running while Eclipse is set up and an editor attached (lazy.nvim
--- installing missing plug-ins waits with vim.wait, in rounds), and a plug-in can open a float
--- with the focus meanwhile and have VimEnter enter it again (lazy.nvim's window); the keys the
--- user types then wait in Neovim's input, which Neovim reads only once its start is over. So
--- at UIEnter, after every VimEnter and before any of those keys is read, the check looks at
--- where Neovim is. A float in the tab page of the window Eclipse has attached (asked of
--- Eclipse there and then, eclipse_attached_window), and not hidden, is drawn: it keeps the
--- keyboard, and the one the config left the focus in before the plug-in set Neovim's windows
--- up (LEAVE_START_FLOAT hands it over) is entered again, since only the plug-in took Neovim
--- out of it. A float the editor does not draw is closed (not by force, a changed buffer
--- stays), with one message naming the first, and Neovim goes to the attached window: keys go
--- only to a window Eclipse draws. When Neovim has already entered (an init that does not
--- wait: the usual start), it happens at once. With no editor shown yet, the float cannot be
--- drawn anywhere: it waits for the first one, whose M.show brings it along and enters it
--- (settle_start_float). A float opened after that is any other plug-in's, and
--- M.close_undrawn watches it. As in a terminal, keys typed during an install
--- are lazy.nvim's then: U, S, X and R are its commands.
--- Whether it closed one now (false: at UIEnter, or nothing to close). Example: LazyVim's
--- first start: its installer stays over the first editor, with the keyboard, and q closes it.
function M.close_start_floats(left)
  local function close()
    -- Eclipse's own answer, not a copy kept here: the editor it attached last (the home
    -- window before any). No channel: not Eclipse's Neovim (the Lua specs).
    local chan = vim.g.neovim4eclipse_channel
    local ok, attached = false, nil
    if chan then
      ok, attached = pcall(vim.rpcrequest, chan, 'eclipse_attached_window')
    end
    local has_attached = ok and type(attached) == 'number' and attached > 0
      and vim.api.nvim_win_is_valid(attached)
    local shown_tab = has_attached and vim.api.nvim_win_get_tabpage(attached)
      or vim.api.nvim_get_current_tabpage()
    local function drawn(w)
      return vim.api.nvim_win_is_valid(w) and M.focused_float(w)
        and not vim.api.nvim_win_get_config(w).hide and vim.api.nvim_win_get_tabpage(w) == shown_tab
    end
    local name
    if left and left > 0 and vim.api.nvim_win_is_valid(left) then
      if drawn(left) then
        if not M.focused_float(vim.api.nvim_get_current_win()) then
          if S.editor_shown then
            pcall(vim.api.nvim_set_current_win, left)
          else
            S.pending_start_float = left
          end
        end
      else
        local n = S.float_name(left)
        if pcall(vim.api.nvim_win_close, left, false) then
          name = n
        end
      end
    end
    local current = vim.api.nvim_get_current_win()
    if M.focused_float(current) and not drawn(current) then
      local n = S.float_name(current)
      if M.leave_float() then
        name = name or n
      end
    elseif M.focused_float(current) and not S.editor_shown then
      S.pending_start_float = current
    end
    if not drawn(vim.api.nvim_get_current_win()) and has_attached
        and attached ~= vim.api.nvim_get_current_win() then
      pcall(vim.api.nvim_set_current_win, attached)
    end
    if name then
      say_at_start(M.float_closed_message(name, AT_START)) -- the keys typed meanwhile come first
    end
    return name ~= nil
  end
  if vim.v.vim_did_enter == 1 then
    return close()
  end
  vim.api.nvim_create_autocmd('UIEnter', { group = group, once = true, callback = function() close() end })
  return false
end
