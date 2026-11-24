-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- The windows module's rules for plug-ins' floating windows (part of `neovim4eclipse.windows`;
-- init.lua says how its files fit together): which float has the keyboard (M.focused_float,
-- M.entered_float), how Eclipse's routes leave one (M.leave_float closes it, M.move_out_of_float
-- keeps it open), how an insert begun in one ends before Neovim leaves it (M.end_float_insert, the
-- first of two steps, core/DocumentBuffers.leavingFloat), and the one place that closes a float
-- the editor does not draw (M.close_undrawn).
--
-- Example: Telescope's prompt open over an editor, the user clicks the text: the insert ends in
-- the prompt, then Neovim moves to the editor's window, and Telescope closes its picker itself.

local M = package.loaded['neovim4eclipse.windows']
local S = package.loaded['neovim4eclipse.windows.shared']
local group = S.group
local own = S.own

--- The message for a float Neovim would not close (close_float found it refused, as for a
--- changed buffer that would be wiped). Example: "Could not close the floating window of
--- lazy: it is hidden, and it would take the keys unseen".
function M.float_kept_message(name, why)
  return "Could not close the floating window of " .. name .. ": " .. why
end
--- The message for a floating window with the focus that the plug-in closed (M.close_undrawn,
--- M.close_start_floats): its name, and why. Example: "Closed the floating window of lazy: it
--- is hidden, and it would take the keys unseen".
function M.float_closed_message(name, why)
  return "Closed the floating window of " .. name .. ": " .. why
end
-- Why a float the editor does not draw is closed, after the reason it is not drawn.
local UNSEEN = ', and it would take the keys unseen'
-- Why a float with the focus that its plug-in hid is closed (M.close_undrawn).
local HIDDEN = 'it is hidden'

--- Whether Neovim is in a plug-in's float in the tab page of win, an editor's window it is
--- not in: the float that closing win leaves first (leave_float_of), and so the one whose
--- insert ends first (M.end_float_insert). Example: Telescope's prompt open over editor A,
--- A closing: true; B closing in the background: false, the prompt keeps its insert.
local function in_float_of(win)
  local current = vim.api.nvim_get_current_win()
  return current ~= win and M.focused_float(current)
    and vim.api.nvim_win_get_tabpage(current) == vim.api.nvim_win_get_tabpage(win)
end

--- Whether closing win (M.close, M.release) takes Neovim out of the plug-in's float it is
--- in: a float in win's tab page, left first (leave_float_of), or win itself, a float on
--- the buffer being released, left for the home window (leave). Example: an insert in a
--- peek float on b.txt, b.txt's editor closing in the background: true, so that the
--- insert ends in the float and does not go on in the home window's document.
local function closing_leaves_float(win)
  local current = vim.api.nvim_get_current_win()
  return S.closable(win) and (in_float_of(win) or (win == current and M.focused_float(win)))
end

--- Before win, an editor's window, closes: a plug-in's float with the cursor in win's tab
--- page is left first (M.leave_float), closed as Eclipse mode closes one, so that its insert
--- ends and its plug-in cleans up (Telescope's BufLeave). Closing the tab quietly would
--- close the float with it, and Neovim would go on in insert mode in another window.
--- Example: Telescope's prompt open in editor A, the user closes A; the next keys are
--- normal-mode keys in editor B. And a float on a file with unsaved changes that the tab's
--- close takes along keeps its buffer, hidden, whatever 'hidden' and 'bufhidden' say.
local function leave_float_of(win)
  local tab = vim.api.nvim_win_get_tabpage(win)
  if in_float_of(win) then
    M.leave_float()
  end
  -- A float leaving could not close (close_float: Neovim refused, closing would unload
  -- or wipe a file's unsaved changes) closes with the tab page now: its buffer is kept,
  -- hidden ('bufhidden' hide), so that the changes stay (:ls), as leaving promised.
  for _, w in ipairs(vim.api.nvim_tabpage_list_wins(tab)) do
    local buf = vim.api.nvim_win_get_buf(w)
    if not own[w] and vim.api.nvim_win_get_config(w).relative ~= ''
        and vim.bo[buf].buftype == '' and vim.bo[buf].modified then
      vim.bo[buf].bufhidden = 'hide'
    end
  end
end
S.leave_float_of = leave_float_of

--- Whether win (0: the current one) is a floating window of a plug-in, not the
--- plug-in's own and not one of ui2's: a picker's prompt (Telescope), :Lazy, a hover
--- float the user entered. An insert that begins in one is Neovim's to its end
--- (SETUP's ModeChanged report): its buffer is no document, so Eclipse must not type it.
function M.focused_float(win)
  win = win == 0 and vim.api.nvim_get_current_win() or win
  return not own[win] and vim.fn.win_gettype(win) == 'popup' and not S.shows_ui2_buffer(win)
end

--- The window Eclipse's scripts for the editor's view act on (VIEWPORT_OVERHEAD, SET_FOLDS,
--- SET_WRAP, SET_TOPLINE, SCROLL_LINES): the current one, or, while Neovim is in a
--- plug-in's float (a picker's prompt, lazy.nvim's installer), the window of the
--- plug-in's own under it, the editor's or page's of that tab page. They run in it with
--- nvim_win_call, which fires no autocommands, so the float keeps the focus. Example: the
--- editor resized while :Lazy is open: Neovim's screen is measured on the editor's window.
function M.editor_window()
  local current = vim.api.nvim_get_current_win()
  if not M.focused_float(current) then
    return current
  end
  for _, w in ipairs(vim.api.nvim_tabpage_list_wins(vim.api.nvim_win_get_tabpage(current))) do
    if own[w] and vim.api.nvim_win_get_config(w).relative == '' then
      return w
    end
  end
  return current
end

--- Whether Neovim entered win, a plug-in's float, to stay: one rule for every plug-in's
--- float with the cursor (a picker's prompt as it opens, :Lazy, a hover float entered
--- by a second K), current, entered outside an insert. SETUP's BufEnter report says so, and Eclipse leaves Neovim there
--- (util/EnterDecision) instead of sending it back to the editor's window, where the
--- insert a picker starts next would begin and Eclipse would type it. Not the buffer's
--- kind: Telescope makes its prompt buffer a prompt only after entering the window
--- (plenary's popup opens a scratch buffer, then Telescope sets 'buftype'). One entered
--- during an insert (a plug-in's timer while Eclipse types) is sent back, as before:
--- the insert is the editor's. Unless that insert began in a plug-in's float
--- (insert_float, set at InsertEnter; not the previous window, which a plug-in can pass
--- through without an autocommand): a click from a picker's prompt on a second float
--- enters that one still in Insert mode, and sent back, the next letters would land in
--- the file. Leaving the editor moves Neovim out of the float (M.move_out_of_float). Whether
--- the editor draws it is not asked here: one it does not draw (hidden, past the limits)
--- is closed in the one place that decides it, M.close_undrawn.
function M.entered_float(win)
  if not M.focused_float(win) or win ~= vim.api.nvim_get_current_win() then
    return false
  end
  if not vim.api.nvim_get_mode().mode:match('^[iR]') then
    return true
  end
  return S.insert_float ~= nil
end

--- Closes win, a plug-in's float, as leaving it does: not by force, so that a buffer of
--- the user's with unsaved changes is not lost. With 'hidden' (Neovim's default) the
--- window closes and the buffer stays, hidden and changed (:ls lists it); Neovim refuses
--- only when closing would unload or wipe it ('bufhidden', 'nohidden'). A prompt, a
--- scratch or terminal buffer never counts as changed: Telescope's and lazy.nvim's close.
--- Refused, Neovim leaves the float for the editor's window of its tab, and the float
--- stays, drawn without the focus. Whether it closed.
local function close_float(win)
  -- Asked of the window, not of the call: a plug-in's WinClosed, BufWinLeave or BufHidden
  -- handler that throws makes nvim_win_close fail after the window has gone.
  if pcall(vim.api.nvim_win_close, win, false) or not vim.api.nvim_win_is_valid(win) then
    return true
  end
  if win == vim.api.nvim_get_current_win() then
    for _, w in ipairs(vim.api.nvim_tabpage_list_wins(vim.api.nvim_win_get_tabpage(win))) do
      if own[w] then
        pcall(vim.api.nvim_set_current_win, w)
        break
      end
    end
  end
  return false
end

--- Whether win, the float a route of Eclipse's names (M.leave_float, M.move_out_of_float),
--- is another than current, the window Neovim is in: then Neovim has gone on since, and
--- stays where it is. nil, 0 or -1 name none.
local function names_other(win, current)
  return win ~= nil and win > 0 and win ~= current
end

--- Leaves the plug-in's float the cursor is in, closing it (close_float): the user turned
--- Neovim mode off, its editor is closing, or the editor does not draw it. For the user
--- clicking away, M.move_out_of_float keeps it open. An insert in it ends first. Again
--- while the window Neovim is in then is a plug-in's float too (a picker's results,
--- should its plug-in leave the focus there), so that the cursor ends outside every one;
--- a plug-in closes its other windows itself when its prompt closes (Telescope does).
--- win (optional): the float to close instead, though Neovim may have gone on since; only
--- that one closes, and an insert Neovim carried out of it ends. Whether one was closed.
--- Example: Telescope's prompt open, Eclipse mode turned on: the prompt closes, Telescope
--- closes its results and preview, Neovim is back in the editor's window.
function M.leave_float(win)
  local current = vim.api.nvim_get_current_win()
  local named = names_other(win, current)
  local target = named and win or current
  -- The insert begun in the float ends, also when Neovim already went on with it and the
  -- float closed itself on leaving (a plug-in's input that closes on BufLeave); not an
  -- insert begun elsewhere since (Eclipse's, in the editor the user went to). With a float
  -- named, only that float's insert: Neovim is no longer in it, and a float it is in now
  -- may be the next part's picker, whose own insert began since.
  local stop = S.in_insert() and S.insert_float ~= nil
    and (S.insert_float == target or (not named and M.focused_float(current)))
  local closed = false
  if vim.api.nvim_win_is_valid(target) and M.focused_float(target) then
    if named then
      closed = close_float(target)
    else
      for _ = 1, 10 do -- a bound: each round closes a window, so a plug-in reopening one cannot loop
        local w = vim.api.nvim_get_current_win()
        if not M.focused_float(w) or not close_float(w) then
          break
        end
        closed = true
      end
    end
  end
  if stop then
    S.stop_insert_in_place() -- in the window Neovim is in by now, the editor's
  end
  return closed
end

--- Moves Neovim out of the plug-in's float it is in, to the editor's or page's window of that
--- tab page, and leaves the float open: what a click outside a float does in a terminal.
--- The plug-in decides whether its window survives that: Telescope closes its picker on
--- BufLeave, and :Lazy stays, drawn without the focus, until it is clicked or closed. For
--- the user clicking the text or leaving for another part of Eclipse
--- (editor/LineOnFocusLoss, NeovimEditorController). An insert begun in the float ends, as
--- in M.leave_float. win (optional): the float the cursor was in when the keyboard left a
--- part; when Neovim has gone on since, there is nothing to move, and only that float's
--- insert ends. Whether Neovim moved.
function M.move_out_of_float(win)
  local current = vim.api.nvim_get_current_win()
  local named = names_other(win, current)
  local target = named and win or current
  local stop = S.in_insert() and S.insert_float ~= nil
    and (S.insert_float == target or (not named and M.focused_float(current)))
  local moved = false
  if not named and M.focused_float(current) then
    local to = M.editor_window() -- the float itself when its tab page has no window of ours
    moved = to ~= current and pcall(vim.api.nvim_set_current_win, to)
  end
  if stop then
    S.stop_insert_in_place() -- in the window Neovim is in by now, the editor's
  end
  return moved
end

--- Why the window Neovim is in, a plug-in's float, is one the editor does not draw, as
--- M.close_undrawn words it, or nil when it is none (see there).
local function undrawn_why(win, past_limits)
  local current = vim.api.nvim_get_current_win()
  if not M.focused_float(current) then
    return nil
  elseif vim.api.nvim_win_get_config(current).hide then
    return HIDDEN
  elseif win == current then
    return 'the editor cannot draw it' .. (past_limits and ' (' .. past_limits .. ')' or '')
  end
  return nil
end

--- Closes the plug-in's float Neovim is in when the editor does not draw it, with a message:
--- keys go only to a window Eclipse draws, and these would go into one nobody sees. The one
--- place that decides it, for every way a float can be undrawn: the session calls it from the
--- redraw flush that finds Neovim's cursor on a grid it does not draw (util/Grids.unseenCursor),
--- whatever route led there (a float that opens with the focus, one entered later, one its
--- plug-in hides), and it acts only on what it finds here itself: the current window, a
--- plug-in's float (M.focused_float), and why it is undrawn: hidden by its plug-in (config
--- hide), or past util/Grids' limits, which only the session can tell: then win, the window
--- the session saw on that grid, is this one, and past_limits words the limits. Else
--- nothing: a float the session has not placed yet (its position comes with a later redraw),
--- or Neovim has moved on. Not by force (M.leave_float, close_float): a changed buffer stays,
--- and an insert begun in the float ends; a float Neovim refuses to close stays, with a
--- message. Whether it closed.
--- Example: Telescope's prompt hidden by a plug-in of its own while Neovim is in it: closed,
--- with "Closed the floating window of TelescopePrompt: it is hidden, and it would take the
--- keys unseen".
function M.close_undrawn(win, past_limits)
  local why = undrawn_why(win, past_limits)
  if not why then
    return false
  end
  local current = vim.api.nvim_get_current_win()
  local name = S.float_name(current)
  if not M.leave_float() then
    -- Said whether Neovim got out of it or, with no editor's window in its tab page to
    -- go to, is still in it: then this message is the only sign of where the keys go.
    vim.api.nvim_echo({ { M.float_kept_message(name, why .. UNSEEN) } }, true, { err = true })
    return false
  end
  vim.api.nvim_echo({ { M.float_closed_message(name, why .. UNSEEN) } }, true, { err = true })
  return true
end

--- For each route of Eclipse's that can take Neovim out of a float, with the arguments of
--- its script (core/LuaScripts.FLOAT_EXITS names the route of each script): whether that
--- script will leave the plug-in's float Neovim is in now. The same rule as the script's
--- own, in one place each (closable, in_float_of, names_other, undrawn_why), so that the
--- first step (M.end_float_insert) ends an insert only where the second leaves its float.
--- Example: a background editor closing while the user types in Telescope's prompt over
--- another: close is false, and the prompt keeps its insert.
local leaves = {
  -- FOCUS_WINDOW (win, from): enters win, a page's window, when it is still there.
  focus = function(win)
    return win ~= nil and vim.api.nvim_win_is_valid(win)
  end,
  -- SHOW_WINDOW (win, buf): enters win (-1: the home window) unless M.show refuses.
  show = function(win, buf)
    return S.shows(win, buf)
  end,
  -- CLOSE_WINDOW (win, fallback): leaves a float in win's tab page (M.close).
  close = function(win)
    return closing_leaves_float(win)
  end,
  -- RELEASE_BUFFER (buf, fallback): leaves a float in the tab page of a window it closes,
  -- or a float that shows buf itself (M.release).
  release = function(buf)
    for _, win in ipairs(vim.api.nvim_buf_is_valid(buf) and vim.fn.win_findbuf(buf) or {}) do
      if closing_leaves_float(win) then
        return true
      end
    end
    return false
  end,
  -- LEAVE_FLOAT, MOVE_OUT_OF_FLOAT (win), LEAVE_FLOAT_WITH_LINE (): the current float,
  -- unless another is named.
  move = function(win)
    return not names_other(win, vim.api.nvim_get_current_win())
  end,
  -- CLOSE_UNDRAWN_FLOAT (win, past_limits): the current float, when it is undrawn.
  undrawn = function(win, past_limits)
    return undrawn_why(win, past_limits) ~= nil
  end,
}

--- The first of two steps for every route of Eclipse's that takes Neovim out of a float
--- (core/DocumentBuffers.leavingFloat): ends the insert begun in the float while Neovim is
--- still in it, when the second step, route's script with these arguments, will leave it
--- (leaves). The second step, the move or the close, is the next call: :stopinsert takes
--- effect as this call returns, before Neovim reads the next one. Why not in one step: an
--- insert that has only auto-indented ('o', nothing typed) ends by removing the white space
--- at the cursor of the window Neovim is in, and ended from the editor's window that is the
--- document's line. Example: 'o' in a float, a click on the text: the float's empty line
--- loses its indent, the document keeps its own. Also an insert waiting to go on after
--- Ctrl-O: Neovim reports Visual, Select or the command line then ('i<C-o>v', 'i<C-o>:'),
--- not insert, so only plain Normal mode ('n', where no insert waits) is left alone;
--- elsewhere :stopinsert ends the insert or the wait for it, and else only Terminal mode
--- (a terminal in the float), which the second step leaves anyway.
--- Not the float waiting for the first editor (settle_start_float), which keeps the
--- keyboard and its insert. An unknown route is an error, a script without its entry.
--- Whether :stopinsert ran.
function M.end_float_insert(route, ...)
  local leaving = leaves[route]
  if not leaving then
    error('no float exit named ' .. tostring(route))
  end
  local current = vim.api.nvim_get_current_win()
  if S.insert_float == nil or current == S.pending_start_float or not M.focused_float(current)
      or vim.api.nvim_get_mode().mode == 'n' or not leaving(...) then
    return false
  end
  vim.cmd.stopinsert()
  return true
end
-- Where each insert begins (S.insert_float, init.lua).
vim.api.nvim_create_autocmd('InsertEnter', {
  group = group,
  callback = function()
    local w = vim.api.nvim_get_current_win()
    S.insert_float = M.focused_float(w) and w or nil
  end,
})
