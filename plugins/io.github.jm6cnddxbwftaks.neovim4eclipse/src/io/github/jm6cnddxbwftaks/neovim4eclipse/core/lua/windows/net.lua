-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- The windows module's safety nets (part of `neovim4eclipse.windows`; init.lua says how its files
-- fit together), loaded last. Eclipse shows each Neovim window as an editor, so a window that
-- opened some other way is looked at once the command that opened it is over: a `:checkhealth`
-- report or a help window goes to a page editor (pages.lua), a window on a file (a picker's split
-- action) becomes an Eclipse split or editor tab, and any other plain or special window is closed,
-- with a message. `WinClosed` tells Eclipse of a window closed in Neovim, so that its editor gets
-- a new one.
--
-- Example: `:copen` closes the quickfix window again and shows the list the Eclipse way (the
-- Problems or Tasks view); `:split` from a plug-in's `vim.cmd` becomes an editor beside this one.

local S = package.loaded['neovim4eclipse.windows.shared']
local M = S.module
local from_editor = S.from_editor
local group = S.group
local opened_from = S.opened_from
local own = S.own
local page_buf = S.page_buf
local page_kind = S.page_kind
local seen = S.seen

--- Why the safety net closed a window, by win_gettype(): '' for a plain one (a split
--- opened without the command line, which Eclipse never heard of).
local why_closed = {
  [''] = 'Eclipse shows each window as an editor; :split and :vsplit make one',
  quickfix = 'Eclipse cannot show the quickfix window; :cnext, :cprevious and :cc still work',
  loclist = 'Eclipse cannot show the location list window; :lnext, :lprevious and :ll still work',
  preview = 'Eclipse cannot show the preview window',
}
vim.api.nvim_create_autocmd('WinClosed', {
  group = group,
  callback = function(ev)
    own[tonumber(ev.match)] = nil
    page_buf[tonumber(ev.match)] = nil
    page_kind[tonumber(ev.match)] = nil
    seen[tonumber(ev.match)] = nil
    opened_from[tonumber(ev.match)] = nil
    from_editor[tonumber(ev.match)] = nil
    vim.rpcnotify(vim.g.neovim4eclipse_channel or 0, 'eclipse_window_closed', tonumber(ev.match))
  end,
})
-- The second safety net: a window opened next to an editor's, in its tab, by something the
-- refusals do not see (a plug-in's :split, <Cmd>split, vim.cmd.split()). On a file it
-- becomes an Eclipse split or editor tab (take_file_window). Any other Eclipse would not
-- show, but it would take the keys and half the editor's rows. Closed once the
-- command that opened it is over, not inside its own WinNew, while that command still
-- runs (and still fills the window, as :checkhealth does). The quickfix, location list and preview windows
-- (:copen, :lopen, :pedit) are closed too: Eclipse cannot show them either, and in the
-- editor's tab they would take its rows, so H, L and scrolling would no longer match
-- the editor; the lists themselves stay, for :cnext and the like. Left alone: the
-- plug-in's own windows, floating windows (hover, completion documentation; their
-- win_gettype() is 'popup'), the windows of 'autocmd' (gone at once), and the
-- command-line window ('command'), which has its own rule (SETUP).
-- A closed quickfix or location list window is shown the Eclipse way instead of the
-- message, when the quickfix module is there (QUICKFIX: the Problems or Tasks view, or
-- the list's :clist, shown by Neovim), so :copen does what it is for.
-- Floating windows are left alone here, the one that opened with the focus too (a
-- picker's prompt, :Lazy, a dashboard): drawn, it takes the keys, and leaving the editor
-- moves Neovim out of it (M.move_out_of_float); one the editor does not draw is closed by
-- M.close_undrawn.
-- A :checkhealth report opens in a window of its own however it is asked for (a new
-- tab, a split with :vert, a float with vim.g.health's style 'float'), and Neovim has
-- filled it and set its filetype by the time this net runs (after the command). Its
-- buffer goes to a page editor instead (eclipse_view_page, then M.show_page): kept
-- when its window closes ('bufhidden' hide: a float's would be wiped), unlisted, so
-- that :ls and :bnext never meet it, and renamed health://<number>, since the next
-- :checkhealth wipes whatever is named health://. Matched by all three marks Neovim's
-- report has (filetype, scratch buffer, its name), so a file the user gave the
-- filetype by hand is never taken.
-- Neovim goes back to the window the report's was opened from first, then the window
-- closes. Closed while current, its tab would hand Neovim to the next tab, another
-- editor's.
local function take_health_report(win)
  -- An earlier close in this pass (or an autocmd it fired) may have closed this window
  -- too; reading it would throw and end the whole net, leaving later windows open.
  if not vim.api.nvim_win_is_valid(win) then
    return false
  end
  local before = opened_from[win]
  local buf = vim.api.nvim_win_get_buf(win)
  if vim.bo[buf].filetype ~= 'checkhealth' or vim.bo[buf].buftype ~= 'nofile'
      or vim.api.nvim_buf_get_name(buf) ~= 'health://' then
    return false
  end
  vim.bo[buf].bufhidden, vim.bo[buf].buflisted = 'hide', false
  -- A name of its own: Neovim's next :checkhealth wipes the buffer named health://
  -- first, which would close the page window showing this one.
  pcall(vim.api.nvim_buf_set_name, buf, 'health://' .. buf)
  -- A rename leaves the old name behind as an empty unlisted buffer (the alternate
  -- file, as :file does): wiped, so :ls! shows no stray health://.
  for _, b in ipairs(vim.api.nvim_list_bufs()) do
    if b ~= buf and vim.api.nvim_buf_get_name(b) == 'health://' and not vim.bo[b].buflisted
        and #vim.fn.win_findbuf(b) == 0 and vim.api.nvim_buf_line_count(b) <= 1 then
      pcall(vim.api.nvim_buf_delete, b, { force = true })
    end
  end
  if before and own[before] and vim.api.nvim_win_is_valid(before) then
    pcall(vim.api.nvim_set_current_win, before)
  end
  pcall(vim.api.nvim_win_close, win, true)
  vim.rpcnotify(vim.g.neovim4eclipse_channel or 0, 'eclipse_view_page', 'checkhealth', buf, 1)
  return true
end
-- A help window (:help, :helpgrep, :cnext through a :helpgrep list, a plug-in's
-- vim.cmd.help, in a split, a tab or with :vert): Eclipse shows help in a page editor
-- instead, a page window of this Neovim on the same help buffer (docs/ARCHITECTURE.md,
-- decision 10), so Ctrl-], :helpgrep and the user's own help pages work as in a
-- terminal. By the time this net runs, :help has set 'buftype' help and put the cursor
-- on the topic: the buffer and that line go to Eclipse (eclipse_view_page, then
-- M.show_page). Then, as for a report, back to the window it came from and the window
-- closed; the buffer is kept ('bufhidden' hide, whatever 'hidden' says). Inside the
-- help editor :help reuses its own window and never reaches this net (M.follow_page).
local function take_help(win)
  if not vim.api.nvim_win_is_valid(win) or vim.fn.win_gettype(win) ~= '' then
    return false
  end
  local buf = vim.api.nvim_win_get_buf(win)
  local document = M.is_document(buf) and vim.bo[buf].buftype == 'help'
  if not document and not S.page_ok(buf, 'help') then
    return false
  end
  local line = vim.api.nvim_win_get_cursor(win)[1]
  if not document then
    vim.bo[buf].bufhidden = 'hide'
  end
  local before = opened_from[win]
  if before and own[before] and vim.api.nvim_win_is_valid(before) then
    pcall(vim.api.nvim_set_current_win, before)
  end
  pcall(vim.api.nvim_win_close, win, true)
  if document then
    S.give_document_back(buf) -- the window closed, no page: the editor keeps its document
  else
    vim.rpcnotify(vim.g.neovim4eclipse_channel or 0, 'eclipse_view_page', 'help', buf, line)
  end
  return true
end
--- Before win closes with force: a changed buffer stays, hidden. Without this its
--- 'bufhidden' (wipe, delete, unload) would discard the changes with its last window.
--- Only a buffer that can be written: a scratch buffer's 'modified' protects nothing.
local function keep_changes(win)
  local buf = vim.api.nvim_win_get_buf(win)
  local buftype = vim.bo[buf].buftype
  if vim.bo[buf].modified and (buftype == '' or buftype == 'acwrite')
      and #vim.fn.win_findbuf(buf) == 1 then
    vim.bo[buf].bufhidden = 'hide'
  end
end
--- The file of buf if a plug-in's window on it can become an Eclipse editor: an open
--- document, or a plain buffer of a readable file without unsaved changes. Nothing else
--- (a scratch or terminal buffer, a name like fugitive://, an untitled editor's), so
--- that what passes is what :split file could open. Closing a window on a document
--- loses nothing, since the document's own editor keeps showing its buffer.
local function file_to_hand_over(buf)
  local document = M.is_document(buf)
  local name = vim.b[buf].neovim4eclipse_file or vim.api.nvim_buf_get_name(buf)
  if not document and (vim.bo[buf].buftype ~= '' or vim.bo[buf].modified) then
    return nil
  end
  if name == '' or vim.fn.isabsolutepath(name) == 0 or name:match('^%a[%w+.-]*://')
      or vim.fn.filereadable(name) ~= 1 then
    return nil
  end
  return name
end
--- A plug-in's window on a file (a picker's Ctrl-V, Ctrl-X or Ctrl-T, a plain
--- vim.cmd.split(file)) becomes an Eclipse split or editor tab, on the side Neovim put
--- it and at its cursor; Neovim's window closes. Eclipse shows each window as an editor,
--- so this is the window the plug-in asked for. Decided here because every window a
--- plug-in opens passes this net. Example: Ctrl-V on b.txt in Telescope, with
--- 'splitright' off: a window left of the editor's, so an editor to its left.
local function take_file_window(win)
  local commands = package.loaded['neovim4eclipse.window_commands']
  if not commands or not vim.api.nvim_win_is_valid(win) or vim.fn.win_gettype(win) ~= '' then
    return false
  end
  local buf = vim.api.nvim_win_get_buf(win)
  local file = file_to_hand_over(buf)
  if not file then
    return false
  end
  local tab = vim.api.nvim_win_get_tabpage(win)
  -- Read from Neovim, not remembered: an editor's window that is current is where the
  -- action is (a plug-in's split opened without entering it, the next window of a
  -- pass). Else Neovim is in a plug-in's window: the editor that one came from.
  local current = vim.api.nvim_get_current_win()
  -- Opened behind a float that has the keyboard: left to the net's plain close. Handing
  -- it over would move Neovim into the editor, still in the float's mode. Example: a
  -- plug-in's background split while Telescope's prompt is in Insert mode; the next
  -- keys would be typed into the document.
  if win ~= current and M.focused_float(current) then
    return false
  end
  local before = own[current] and current or from_editor[win] or from_editor[current]
  if not (before and own[before] and vim.api.nvim_win_is_valid(before)) then
    before = nil -- none known: its tab's editor
    for _, w in ipairs(vim.api.nvim_tabpage_list_wins(tab)) do
      if own[w] then
        before = w
        break
      end
    end
  end
  if not before and win == current then
    -- A tab page opened with :noautocmd: no WinEnter said where from, and no editor is in
    -- it. Neovim keeps the tab page it came from as the previous one.
    local previous = vim.fn.tabpagenr('#')
    local w = previous > 0 and vim.fn.win_getid(vim.fn.tabpagewinnr(previous), previous) or 0
    before = own[w] and w or nil
  end
  if not before then
    return false
  end
  local at = vim.api.nvim_win_get_cursor(win)
  pcall(vim.api.nvim_set_current_win, before)
  -- An insert begun in a float must not go on in the editor: a plug-in that opens the
  -- split without :stopinsert would leave the next keys typing into the document.
  if S.insert_float ~= nil and S.in_insert() then
    S.stop_insert_in_place()
  end
  local where = 'tab'
  if vim.api.nvim_win_get_tabpage(before) == tab then
    local new, old = vim.fn.win_screenpos(win), vim.fn.win_screenpos(before)
    if new[1] == old[1] then
      where = new[2] < old[2] and 'left' or 'right'
    else
      where = new[1] < old[1] and 'above' or 'below'
    end
  end
  -- The buffer goes on, hidden, whatever its 'bufhidden' says: the new editor's attach takes
  -- it over by its name (LuaScripts.CREATE_DOCUMENT_BUFFER reuses a buffer of that name), so
  -- the window closes but the buffer does not end. A plug-in may wait for that buffer's end.
  -- Example: vim-fugitive's :Git commit splits on .git/COMMIT_EDITMSG with 'bufhidden' wipe
  -- and commits when the buffer is deleted; wiped here, the commit ended before the message
  -- was typed. Kept, it ends when the editor closes: CLOSE_DOCUMENT_BUFFER unloads it, and
  -- taking it off the buffer list then fires BufDelete, after :wq saved the message. Should
  -- Eclipse not open the file after all, the buffer stays hidden in Neovim (:ls lists it). A
  -- document's buffer is its editor's already. Should the close fail, nothing is handed over,
  -- and the plug-in's own 'bufhidden' is put back.
  local bufhidden = vim.bo[buf].bufhidden
  if not M.is_document(buf) then
    vim.bo[buf].bufhidden = 'hide'
  end
  if not pcall(vim.api.nvim_win_close, win, true) then
    if vim.api.nvim_buf_is_valid(buf) then
      vim.bo[buf].bufhidden = bufhidden
    end
    return false
  end
  commands.open_beside(where, file, at[1], at[2] + 1)
  return true
end
-- The window last left: when a command opens a window and enters it (:checkhealth's
-- `tab sbuffer`, a split), WinLeave fires in the old window, then WinNew and WinEnter in
-- the new. A window opened without entering it (nvim_open_win(buf, false, ...)) fires
-- WinNew alone, with that window current for the moment, so only WinEnter says that
-- left_from is where a window came from: at WinNew it may be an editor left long ago.
local left_from = S.home
vim.api.nvim_create_autocmd('WinLeave', {
  group = group,
  callback = function()
    left_from = vim.api.nvim_get_current_win()
  end,
})
vim.api.nvim_create_autocmd('WinEnter', {
  group = group,
  callback = function()
    local win = vim.api.nvim_get_current_win()
    if win ~= left_from and not own[win] and not opened_from[win] then
      opened_from[win] = left_from
      from_editor[win] = own[left_from] and left_from or from_editor[left_from]
    end
  end,
})
--- The net itself: looks at each window it has not seen yet. Scheduled, so that the command
--- that opened the window is over.
local function check_new_windows()
  local why, list
  local quickfix = package.loaded['neovim4eclipse.quickfix']
  for _, win in ipairs(vim.api.nvim_list_wins()) do
    -- ui2's windows (UI2) showing ui2's own buffers are Neovim's command line and
    -- messages: never closed. One showing another buffer is a float like any other.
    local new = not seen[win] and not S.shows_ui2_buffer(win)
    seen[win] = true
    local wintype = vim.fn.win_gettype(win)
    -- A report taken leaves its window closed: the checks below then pass it by.
    local taken = new and not own[win] and (take_health_report(win) or take_help(win)
      or take_file_window(win))
    local reason = not taken and not own[win] and why_closed[wintype]
    if reason and vim.api.nvim_win_is_valid(win) then
      keep_changes(win)
    end
    if reason and vim.api.nvim_win_is_valid(win) and pcall(vim.api.nvim_win_close, win, true) then
      if quickfix and (wintype == 'quickfix' or wintype == 'loclist') then
        list = list or wintype
      else
        why = why or reason
      end
    end
  end
  if why then
    vim.api.nvim_echo({ { 'Window closed: ' .. why } }, true, { err = true })
  end
  if list then
    quickfix.window(list)
  end
end
vim.api.nvim_create_autocmd('WinNew', {
  group = group,
  callback = function()
    vim.schedule(check_new_windows)
  end,
})
-- :noautocmd split fires no WinNew, and Neovim would go on in a window Eclipse does not
-- draw, with the keys editing its buffer unseen. SafeState comes as Neovim is about to
-- wait for a key, whatever opened the window, so the net still runs.
vim.api.nvim_create_autocmd('SafeState', {
  group = group,
  callback = function()
    for _, win in ipairs(vim.api.nvim_list_wins()) do
      if not seen[win] then
        vim.schedule(check_new_windows)
        return
      end
    end
  end,
})
-- The module, registered now that every file has loaded (init.lua, "How they share"): the
-- other scripts reach it through package.loaded.
package.loaded['neovim4eclipse.windows'] = M
-- The answer of the whole script (LuaScripts.WINDOWS, whose last file this is): the
-- home window.
return S.home
