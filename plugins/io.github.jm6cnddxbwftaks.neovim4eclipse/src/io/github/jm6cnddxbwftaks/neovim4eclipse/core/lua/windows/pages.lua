-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- The windows module's page windows (part of `neovim4eclipse.windows`; init.lua says how its
-- files fit together): a page editor shown from this Neovim, not a file's (the messages page, a
-- help page, a `:checkhealth` report), in a window of its own, alone in a tabpage as an editor's
-- window is (docs/ARCHITECTURE.md, decision 10). Opened by LuaScripts.OPEN_PAGE_WINDOW, filled
-- by LuaScripts.SHOW_PAGE_BUFFER; the safety net (net.lua) hands a help or report window here.
--
-- One check decides what may be a page (page_ok): never an open document's buffer, so that a
-- page's `q`, keys and write hooks never reach a code editor. Example: `:help options` with the
-- help editor open: its window changes to options.txt, on the topic's line.

local S = package.loaded['neovim4eclipse.windows.shared']
local M = S.module
local own = S.own
local page_buf = S.page_buf
local page_kind = S.page_kind
local seen = S.seen

-- A page window's options, set when it opens: a new window copies the current window's
-- (an editor's number and sign columns, its 'list', its 'nowrap'), and a buffer shown
-- again takes those of the window it was last shown in; a page of text that no file has
-- wants neither. Set quietly, on the page's window only.
local PAGE_OPTIONS = {
  number = false, relativenumber = false, signcolumn = 'no', foldcolumn = '0',
  statuscolumn = '', list = false, wrap = true, spell = false, colorcolumn = '',
  foldenable = false,
}
-- The IDE keys a page gives back to Neovim (IDE_KEYS keeps what they were): gd, K, the
-- gr* keys and the like are about code, which a page is not. gt and gT stay Eclipse's:
-- they move among the editor tabs, the page's too.
local KEEP_IN_PAGES = { next_tab = true, previous_tab = true }

--- Gives the IDE keys of IDE_KEYS back to Neovim in buf, buffer-locally: the mapping each
--- had before the plug-in's (Neovim's own default for gr*), or the key itself without
--- mappings when it had none (gd, K). Example: gd in the messages page is Neovim's gd.
local function give_ide_keys_back(buf)
  local ide = package.loaded['neovim4eclipse.ide_keys']
  if not ide then
    return -- the IDE keys are off (vim.g.neovim4eclipse_ide_keys = 0), or not mapped yet
  end
  for _, key in ipairs(ide.keys) do
    if not KEEP_IN_PAGES[key.action] then
      for _, mode in ipairs(key.modes) do
        local before = ide.before[mode .. ' ' .. key.lhs]
        vim.api.nvim_buf_call(buf, function()
          if before then
            local local_map = vim.deepcopy(before)
            local_map.buffer = 1
            vim.fn.mapset(local_map)
          else
            vim.keymap.set(mode, key.lhs, key.lhs, { buffer = buf, remap = false, desc = 'Neovim (a page)' })
          end
        end)
      end
    end
  end
end

--- Makes buf fit to be a page, once per buffer: every write that runs autocommands is
--- refused with Vim's E382 (as PAGE_TEXT's own hooks do for the messages page, which
--- keeps those), and q closes the page editor (a q the user mapped for the buffer is
--- kept). For Neovim's own buffers a page shows: a help file (a real file, 'readonly'
--- and 'nomodifiable' already, but :w! or :w x would still write it) and a
--- :checkhealth report (a scratch buffer, written only under a name). Example: :w x in
--- the help editor gives E382, and nothing is written.
local function page_buffer(buf)
  if vim.b[buf].neovim4eclipse_page or vim.b[buf].neovim4eclipse_page_ready then
    return
  end
  vim.b[buf].neovim4eclipse_page_ready = true
  for _, event in ipairs({ 'BufWriteCmd', 'FileWriteCmd', 'FileAppendCmd' }) do
    vim.api.nvim_create_autocmd(event, {
      buffer = buf,
      callback = function()
        vim.api.nvim_echo({ { "E382: Cannot write, 'buftype' option is set" } }, true, { err = true })
      end,
    })
  end
  local own_q = vim.api.nvim_buf_call(buf, function()
    return vim.fn.maparg('q', 'n', false, true)
  end)
  if own_q.buffer ~= 1 then
    vim.keymap.set('n', 'q', '<Cmd>Eclipse quit<CR>', { buffer = buf, nowait = true, desc = 'Close the page editor' })
  end
end

--- Whether buf is an open document's buffer now: DOCUMENT_BUFFER marked it
--- (b:neovim4eclipse_file) and its hooks are on it (DOCUMENT_BUFFER_MODULE's is_document, the
--- one record of them). CLOSE_DOCUMENT_BUFFER removes the hooks and keeps the mark, so a
--- buffer whose editor closed is no document any more: its help may be a page again.
function M.is_document(buf)
  if vim.b[buf].neovim4eclipse_file == nil then
    return false
  end
  local documents = package.loaded['neovim4eclipse.document_buffer']
  return documents ~= nil and documents.is_document(buf)
end

--- Gives a document's buffer back what :help took from it: :help on a help file that is
--- open in an editor reuses the document's buffer and makes it a help buffer, read-only,
--- unlisted, unchangeable, with 'tabstop' 8 and help's 'iskeyword' (its text, undo
--- history and marks stay: :help reads nothing from disk for a loaded buffer); the
--- editor's typing and Eclipse's own edits would then fail
--- in Neovim, and the two texts drift apart. Quietly (no OptionSet), and with a message
--- saying why no help page opened. Example: doc/myplug.txt open in an editor, then
--- :help myplug-tag: the editor keeps working, and the message names the file.
local function restore_document(buf)
  if not vim.api.nvim_buf_is_valid(buf) or not M.is_document(buf) then
    return
  end
  S.quietly(function()
    local bo = vim.bo[buf]
    bo.buftype, bo.readonly, bo.modifiable, bo.buflisted = 'acwrite', false, true, true
    -- :help also set 'tabstop' 8 and help's 'iskeyword': the document's own come back
    -- (Eclipse's tab width, SET_INDENTATION; the filetype's word characters,
    -- DOCUMENT_BUFFER), so block selections and w, * and ciw work as before.
    local ts, isk = vim.b[buf].neovim4eclipse_tabstop, vim.b[buf].neovim4eclipse_iskeyword
    if ts then
      bo.tabstop = ts
    end
    if isk then
      bo.iskeyword = isk
    end
  end)
end
local function give_document_back(buf)
  restore_document(buf)
  -- Again once the command is over: inside a help window (the Ctrl-] route, from
  -- BufEnter) Neovim sets help's 'iskeyword' after BufEnter.
  vim.schedule(function()
    restore_document(buf)
  end)
  -- After the command, not inside it: an error message from a BufEnter autocommand (the
  -- Ctrl-] route, follow_page) aborts the :help that fired it, with Neovim's "Error in
  -- BufEnter Autocommands" around it.
  local message = 'The help of ' .. vim.fn.fnamemodify(vim.api.nvim_buf_get_name(buf), ':t')
    .. ' is open in its editor: edit it there; the help editor shows it once that editor is closed'
  vim.schedule(function()
    vim.api.nvim_echo({ { message } }, true, { err = true })
  end)
end
S.give_document_back = give_document_back

--- Whether buf may be shown as a page of kind: the one check every way into a page window
--- passes (open_page, show_page, follow_page, the net's hand-over). Never an open
--- document's buffer (M.is_document): :help on a help file
--- that is open in an editor reuses the document's buffer and gives it 'buftype' help,
--- and as a page it would take the page's q, keys and write hooks into the code's
--- editor. A help page is a 'buftype' help buffer, a report a checkhealth scratch
--- buffer; messages are PAGE_TEXT's own buffer. Example: doc/myplug.txt open in an
--- editor, :help myplug-tag: refused as a page.
local function page_ok(buf, kind)
  if not vim.api.nvim_buf_is_valid(buf) or M.is_document(buf) then
    return false
  end
  if kind == 'help' then
    return vim.bo[buf].buftype == 'help'
  elseif kind == 'checkhealth' then
    return vim.bo[buf].filetype == 'checkhealth' and vim.bo[buf].buftype == 'nofile'
  end
  return true
end
S.page_ok = page_ok

--- A help page's window options, as Neovim's help ftplugin sets them in the window :help
--- opened (which the net closed): the stars of *tags* and the bars of |links| hidden.
local function help_options(win, kind)
  if kind == 'help' then
    vim.wo[win].conceallevel, vim.wo[win].concealcursor = 2, 'nc'
  end
end

--- Opens a window for a page (a page editor shown from this Neovim, not a file's) on buf,
--- alone in a tabpage of its own as an editor's is, and returns its id; -2 for a buffer
--- no longer there, or one that may not be a page of kind (page_ok). kind is
--- 'messages' (the default), 'help' or 'checkhealth'.
--- Quietly: its options are a page's, set here, not the look a user's
--- BufWinEnter gives a code editor's window ('colorcolumn', 'relativenumber'); the
--- user's WinEnter and BufEnter still run when the page takes the focus, as for any
--- window. Marked as the plug-in's, so the WinNew net leaves it, and :q in it is a host
--- command.
function M.open_page(buf, kind)
  if not page_ok(buf, kind or 'messages') then
    return -2
  end
  page_buffer(buf)
  local win = S.quietly(function()
    local w = vim.api.nvim_tabpage_get_win(vim.api.nvim_open_tabpage(buf, false, {}))
    vim.api.nvim_win_call(w, function()
      vim.cmd('clearjumps') -- the current window's jumps are another editor's
    end)
    -- Where PAGE_TEXT puts the cursor of the page's windows ("end": on the newest
    -- message): a window opened after the text was filled gets it too.
    if vim.b[buf].neovim4eclipse_page_cursor == 'end' then
      pcall(vim.api.nvim_win_set_cursor, w, { vim.api.nvim_buf_line_count(buf), 0 })
    end
    for name, value in pairs(PAGE_OPTIONS) do
      vim.wo[w][name] = value
    end
    help_options(w, kind)
    give_ide_keys_back(buf)
    return w
  end)
  own[win] = 'page'
  seen[win] = true
  page_buf[win] = buf
  page_kind[win] = kind or 'messages'
  return win
end

--- Whether win is a page's window (M.open_page).
function M.is_page(win)
  return own[win] == 'page'
end

--- Opens another page window like win: on the buffer it shows, of its kind (a :split of
--- the page: a second view, each with its own cursor). Returns { window, buffer }, or
--- { -1, -1 } when win is no page window.
function M.open_page_like(win)
  local buf = page_buf[win]
  if own[win] ~= 'page' or not buf or not vim.api.nvim_buf_is_valid(buf) then
    return { -1, -1 }
  end
  local second = M.open_page(buf, page_kind[win])
  pcall(vim.api.nvim_win_set_cursor, second, vim.api.nvim_win_get_cursor(win))
  return { second, buf }
end

--- Shows buf, a buffer of Neovim's own for page kind ('help': the page :help opened;
--- 'checkhealth': a report), in page window win with the cursor on line, the line at
--- the top as :help puts it; when win is no open page window, in a new one
--- (M.open_page). Returns the window, or -2 when buf is gone. A report the window showed
--- before is wiped when no window shows it any more: :checkhealth makes a new buffer
--- each time, and an old one would stay in :ls! for nothing. Old help buffers stay, as in
--- Vim (Ctrl-T and Ctrl-O go back to them). Example: :help options with the help editor
--- open: its window changes to options.txt, quietly, on the topic's line.
function M.show_page(win, buf, line, kind)
  if not page_ok(buf, kind) then
    return -2
  end
  if own[win] ~= 'page' or not vim.api.nvim_win_is_valid(win) then
    win = M.open_page(buf, kind)
  else
    local old = page_buf[win]
    page_buffer(buf)
    S.quietly(function()
      S.put_buf(win, buf)
      for name, value in pairs(PAGE_OPTIONS) do
        vim.wo[win][name] = value
      end
      help_options(win, kind)
    end)
    give_ide_keys_back(buf)
    page_buf[win] = buf
    page_kind[win] = kind
    if old and old ~= buf and vim.api.nvim_buf_is_valid(old) and vim.bo[old].filetype == 'checkhealth'
        and #vim.fn.win_findbuf(old) == 0 then
      pcall(vim.api.nvim_buf_delete, old, { force = true })
    end
  end
  if win > 0 then
    local last = vim.api.nvim_buf_line_count(buf)
    line = math.max(1, math.min(line, last))
    vim.api.nvim_win_call(win, function()
      vim.fn.winrestview({ lnum = line, col = 0, topline = line })
    end)
  end
  return win
end

--- Called on each BufEnter (SETUP): when a help page's window now shows another help
--- buffer, put there by Neovim's help itself (Ctrl-], :help typed in it, Ctrl-T,
--- :cnext through a :helpgrep), that buffer becomes the page's, as Vim's help window
--- goes from page to page. Returns whether it did; Eclipse then counts it as the
--- page's too (DocumentBuffers.onEntered). Any other buffer in a page window is put
--- back (M.restore_page). Example: Ctrl-] on |options| in the help editor:
--- options.txt is the page now.
function M.follow_page(win, buf)
  if own[win] ~= 'page' or page_kind[win] ~= 'help' or page_buf[win] == buf then
    return false
  end
  if vim.api.nvim_buf_is_valid(buf) and M.is_document(buf) and vim.bo[buf].buftype == 'help' then
    give_document_back(buf) -- Ctrl-] to it: the page gets its own buffer back (restore_page)
    return false
  end
  if not page_ok(buf, 'help') then
    return false
  end
  page_buf[win] = buf
  page_buffer(buf)
  give_ide_keys_back(buf)
  return true
end

--- Puts a page window's own buffer back into it, quietly and without a jump, after Neovim
--- put another one there (gf on a path in the page, :b 3): a page shows only its text,
--- and Eclipse opens the other buffer's file in an editor of its own. Returns the page's
--- buffer, or -1 when win is no open page window (or its buffer is gone).
function M.restore_page(win)
  local buf = page_buf[win]
  if not buf or not vim.api.nvim_win_is_valid(win) or not vim.api.nvim_buf_is_valid(buf) then
    return -1
  end
  if vim.api.nvim_win_get_buf(win) ~= buf then
    S.quietly(S.put_buf, win, buf)
  end
  return buf
end
