-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- Module `neovim4eclipse.document_buffer`: makes a buffer a document buffer, which only Eclipse
-- writes. Installed once per Neovim, right after PATHS (LuaScripts.DOCUMENT_BUFFER_MODULE);
-- `setup(buf)` then makes each new editor's buffer one (LuaScripts.DOCUMENT_BUFFER). A module, not
-- a script run per buffer, so that the hooks' code is sent and compiled once, not once per editor:
-- every document buffer's autocommands call the same functions, and what a hook keeps for a buffer
-- until a later event (a reload's text, a rename's old name) is in a table by buffer number.
--
-- The concept: `buftype=acwrite`
-- Eclipse owns the files: a `:w` by Neovim itself would write its copy behind Eclipse's back (no
-- save actions, no refresh, a "file changed on disk" dialog later). A buffer with `buftype=acwrite`
-- is never written by Neovim; every write runs the buffer's `BufWriteCmd` autocommands instead
-- (`:help acwrite`, `:help BufWriteCmd`). The one here asks Eclipse to save the document (the
-- `eclipse_write` request, answered by DocumentWrite) and, if that worked, clears `'modified'`, as
-- a real write does, and shows the message Eclipse answers, Vim's `"a.txt" 12L, 140B written`,
-- which Neovim does not give for such a buffer. So `:w`, `:update`, `:bufdo %s/a/b/g | update`, `:w
-- other.txt` and `vim.cmd.write()` all write through Eclipse. `v:cmdarg` passes `++p` along.
-- `'modified'` itself follows Eclipse's dirty flag (LuaScripts.SET_MODIFIED), so `:update` asks for
-- exactly the files Eclipse would save. Neovim pipes `:w !cmd` without these hooks (checked on
-- Neovim 0.12.6).
--
-- Which file: the editor's, or a copy
-- `BufWriteCmd` gets the name being written (`ev.match`), and the hook compares it with the file
-- of the editor, kept in `b:neovim4eclipse_file` when the buffer is made a document buffer. Not
-- with the buffer's name: `:saveas` and `:file` change that.
-- - The editor's file (`:w`, `:update`): Eclipse saves the editor (request kind `""`), and
--   `'modified'` is cleared.
-- - Another file (`:w other.txt`, `:w! other.txt`): a copy, as in Vim (kind `"copy"`, with the full
--   name). Eclipse writes the document's text there; the editor stays on its own file and keeps its
--   unsaved changes, so `'modified'` does not change. Neovim itself refuses an existing file
--   without `!` (E13), before the hook. An editor without a file of its own (an untitled one) has
--   nothing to keep: Eclipse saves it as `other.txt` instead and answers with that file, and the
--   buffer takes its name (`nvim_buf_set_name`), as Vim names a buffer without a file after the
--   file it writes (`:help cpo-F`, which Neovim does not apply to an `acwrite` buffer). Renamed so,
--   the buffer is `[Not edited]` until its next write, which works as usual. Eclipse alone decides
--   copy or save as (`editor/OtherFileWrites.copyOrSaveAs`); the hook only follows the answer.
--   Should the rename fail (a loaded buffer has the name), the buffer keeps its name and file, so a
--   later `:w` is the editor's own save.
-- - The buffer's new name after `:saveas new.txt`, which renames the buffer and then writes it:
--   Eclipse saves the document as that file and moves the editor there (kind `"saveas"`); then
--   `b:neovim4eclipse_file` is the new file. (Without it, Eclipse would save the editor's old file
--   while Neovim called the buffer by the new name.)
-- The buffer must keep its editor's name, so `BufFilePost` checks it once the command is over
-- (`vim.schedule`) and puts the old name back after `:file new.txt` (with a message saying why) or
-- a `:saveas` Eclipse refused (whose error is already shown). The rename back is `:keepalt
-- noautocmd file`, which leaves the buffer `[Not edited]` until its next write, as any `:file`
-- does; the plug-in's own buffers avoid that mark by being named with `bufadd()`
-- (LuaScripts.CREATE_DOCUMENT_BUFFER), which cannot rename.
--
-- A write or rename under another name leaves a buffer of that name behind, empty and not loaded:
-- Vim's alternate file (`:help cpo-A`, `cpo-f`). It is wiped once the command is over, since a
-- buffer of that name would make LuaScripts.CREATE_DOCUMENT_BUFFER leave the buffer of an editor
-- opened on that file later unnamed. So `:e #` has no file after these commands (the plug-in's `:e`
-- does not expand `#` anyway). Some lines (`:5,10w part.txt`) run `FileWriteCmd` instead, and an
-- append (`:w >> log.txt`, `:5,10w >> log.txt`) `FileAppendCmd`; Neovim marks the lines with `'[`
-- and `']`, which the hooks send (request kinds `"lines"` and `"append"`, with `v:cmdbang`: `:w! >>
-- new.txt` may create the file). Both are refused for the editor's own file, which Eclipse saves
-- only whole: `:5,10w!` would leave only those lines on disk under an editor that has them all.
-- Without such a hook Neovim refused them itself (E140, with `!` E676). The names are compared by
-- LuaScripts.PATHS's rule (which also ignores case on Windows and macOS, so `:w notes.txt` in
-- `Notes.txt` is a save there, not a copy): Neovim resolves symbolic links in a buffer's name but
-- not in the written name (on macOS `/var` is a link to `/private/var`, so the buffer is
-- `/private/var/.../A.txt` while `:update` writes `/var/.../A.txt`), and on Windows it reports the
-- written name with forward slashes while the buffer keeps backslashes.
--
-- A failed save fails the write, as a real failed write (`E212: Can't open file for writing`) does:
-- `nvim_echo`'s `err = true` (Neovim 0.11) works like `:echoerr`, so inside the autocommand it
-- aborts the `:write` that triggered it. The error reaches whoever ran the command: through the API
-- (`nvim_command`, `vim.cmd`) the command fails and `:bufdo update` stops at that buffer. A typed
-- line, though, goes on after `|`: in `:w | let g:x = 1` the `let` still runs (checked in
-- `tests/unit/src/test/lua/document_buffer_spec.lua`, and the same in Neovim's own TUI). `v:errmsg`
-- holds the text alone (`Eclipse could not save A.txt: read-only`); the message Neovim shows puts
-- its usual line in front, `Error in BufWriteCmd Autocommands for "<buffer=3>":`, as for any error
-- in an autocommand. Neovim shows the two lines (ui2), where Vim would show them above a "Press
-- ENTER" prompt. The buffer stays modified, so `:update` tries again next time.
--
-- Deleted by Neovim: a safety net
-- A `:bd` on Neovim's command line reaches Eclipse as `:Eclipse bdelete`
-- (LuaScripts.HOST_COMMANDS), which closes the editor. One Neovim runs itself (`<Cmd>bd<CR>`,
-- `vim.cmd.bdelete()`, `:bufdo bd`) deletes the buffer first; its `BufDelete` or `BufWipeout` then
-- sends `eclipse_buffer_closed`, and Eclipse closes the editor showing it, asking about changes as
-- usual, so a buffer and its editor never outlive each other. When Eclipse wipes a buffer itself
-- (its last editor closed), no editor shows it any more and the notice is ignored.
--
-- Reloaded by Neovim
-- A typed `:e!` goes to Eclipse, which reverts the editor (LuaScripts.HOST_COMMANDS). A script's or
-- a plug-in's `:edit!` (one that reloads files after a `git checkout`, say) runs inside Neovim: it
-- unloads the buffer and reads the file from disk, over Eclipse's text and its unsaved changes, and
-- the unload ends the buffer's line events (`nvim_buf_attach`), so later changes in Neovim would
-- never reach the document. Every document's buffer, whether its file is inside a project or not (a
-- file opened with File, Open File...), has two buffer-local autocommands instead:
-- 1. `BufUnload` keeps the buffer's lines, `'modified'` and its marks (the text is still there at
--    that moment; Neovim drops an `acwrite` buffer's marks on unload, where Vim's `:edit!` keeps
--    them), until Neovim's next loop.
-- 2. `BufReadCmd` replaces the read: it puts those lines back, under `:lockmarks` and without an
--    undo step, and sends `eclipse_reload(buf, true)`. The buffer is never empty, so the cursor
--    keeps its line (Neovim puts it back after the read, as for any `:edit!`) and a script reading
--    the buffer right after its `:edit!` sees the text. Eclipse attaches the buffer again, with the
--    whole buffer sent first, and applies any difference: keys Neovim handled between the unload
--    and the new attach reach the document (`NeovimSession`, "re-attach after a reload").
-- A read with nothing kept (the buffer unloaded on its own, by a plug-in's `:bunload`, and read
-- later) sends `eclipse_reload(buf, false)`, and Eclipse loads the document's text into it
-- (LuaScripts.RELOAD_DOCUMENT_BUFFER). Example: `a.txt` has an unsaved `x` and the cursor on line
-- 3; after `:lua vim.cmd('edit!')` the buffer still holds the `x`, the cursor is still on line 3,
-- and typing in Neovim still changes the document.
--
-- Sent as LuaScripts.DOCUMENT_BUFFER_MODULE (core/LuaModules runs this file as a chunk of its own).

local M = {}

-- The plug-in's autocommands for document buffers, in one group (`:autocmd
-- neovim4eclipse_document` lists them): setup gives each buffer its own (buffer-local) ones.
-- Made here and again by each setup ({ clear = false } keeps the other buffers'
-- autocommands), so that a group deleted meanwhile (`:augroup! neovim4eclipse_document`) is
-- made anew, as a script run per buffer did.
local GROUP = 'neovim4eclipse_document'
local group = vim.api.nvim_create_augroup(GROUP, { clear = false })

-- Each document buffer's hooks, by buffer number: the ids of the autocommands setup made for
-- it. The one record of which buffers are documents now (M.is_document): setup sets it,
-- M.close (CLOSE_DOCUMENT_BUFFER, its last editor closed) and a wipe remove it. Kept by id,
-- not looked up by group, so that it holds whatever happened to the group: after a config's
-- `:augroup! neovim4eclipse_document`, Vim's rule keeps the autocommands of the deleted group,
-- and a lookup by name would find the new group, without them.
local hooks = {}

-- What a buffer's hooks keep until a later event, one table per buffer, by its number (Neovim
-- never gives a number twice): `kept`, the text a reload's unload took (BufUnload to
-- BufReadCmd); `saveas_tried`, that a :saveas asked Eclipse (BufWriteCmd to BufFilePost);
-- `name_before`, the name before a rename (BufFilePre to BufFilePost). setup gives the buffer a
-- new table, as a buffer made anew starts with nothing kept; a hook scheduled for the next loop
-- holds on to the table it began with, so it finishes with what its own event kept, never with
-- a later setup's. M.close and a wipe (BufWipeout) drop the buffer's table.
local state = {}
local function state_of(b)
  local st = state[b]
  if not st then
    st = {}
    state[b] = st
  end
  return st
end

-- The document's own 'iskeyword', kept for WINDOWS' give_document_back: :help on the
-- document's file (a help file open in an editor) makes it help's, which counts
-- foo.bar(x) as one word for w, * and ciw. Kept at setup, after each FileType (the
-- filetype's ftplugin sets it) and after each :setlocal iskeyword, never while the
-- buffer is help.
local function keep_iskeyword(buf)
  if vim.bo[buf].buftype ~= 'help' then
    vim.b[buf].neovim4eclipse_iskeyword = vim.bo[buf].iskeyword
  end
end

-- An error message, as Neovim shows its own: err = true sets v:errmsg and the ErrorMsg
-- highlight, and marks the message as an error (msg_show kind "echoerr").
local function fail(msg)
  vim.api.nvim_echo({ { msg } }, true, { err = true })
end
-- PATHS's rule: the buffer name has its links resolved, ev.match not (/private/var/x and
-- /var/x on macOS); on Windows ev.match has / where the buffer name has \ (C:/x/a.txt,
-- C:\x\a.txt); and on Windows and macOS a name in other case is the same file. PATHS is
-- sent before this module (NeovimSession.initialize); read from package.loaded, never with
-- require, which would search the runtimepath (and ./?.lua) for a module the session had not
-- sent. Without it, this module is not installed.
local paths = package.loaded['neovim4eclipse.paths']
if not paths then
  error('neovim4eclipse.document_buffer needs neovim4eclipse.paths (LuaScripts.PATHS) first', 0)
end
local canonical = paths.canonical
-- Sets the buffer's 'buftype' without firing OptionSet. The hooks below switch it to ''
-- for an unload (the marks, X5) and back to 'acwrite' right after: a step of their own,
-- not a change another plug-in should react to (a status line that hides itself for
-- special buffers would flicker, one that tracks file buffers would take it for a file).
-- Only the switch back on Neovim's next loop needs this: inside an autocommand's
-- callback (BufUnload, BufReadCmd) no other autocommand fires, OptionSet included, since
-- autocommands do not nest (:help autocmd-nested). Example: :bunload 3 sets '' in
-- BufUnload and, on the next loop, 'acwrite' here, and an OptionSet autocommand for
-- buftype runs neither time.
local function set_buftype(b, value)
  local saved = vim.o.eventignore
  vim.o.eventignore = saved == '' and 'OptionSet' or saved .. ',OptionSet'
  local ok, err = pcall(function() vim.bo[b].buftype = value end)
  vim.o.eventignore = saved
  if not ok then
    error(err, 0)
  end
end
-- Whether `name` is the editor's file, or the buffer's name (renamed by :saveas or :file).
local function own(b, name)
  local c = canonical(name)
  return c == canonical(vim.b[b].neovim4eclipse_file) or c == canonical(vim.api.nvim_buf_get_name(b))
end
-- Writing or renaming under another name leaves a buffer of that name behind, empty and not
-- loaded: Vim's alternate file (:help cpo-A, cpo-f). Such a buffer would keep the plug-in from
-- naming the buffer of an editor opened on that file later (CREATE_DOCUMENT_BUFFER), so it is
-- wiped once the command is over. A document buffer, or any loaded one, is never touched.
local function wipe_left_over(keep, names)
  vim.schedule(function()
    -- Compared canonically: on Windows Neovim reports a written name with forward slashes
    -- (C:/tmp/other.txt) while the buffer left behind keeps backslashes.
    local wanted = vim.tbl_map(canonical, names)
    for _, other in ipairs(vim.api.nvim_list_bufs()) do
      local name = vim.api.nvim_buf_get_name(other)
      if other ~= keep and not vim.api.nvim_buf_is_loaded(other) and not vim.b[other].neovim4eclipse_file
          and name ~= '' and vim.tbl_contains(wanted, canonical(name)) then
        pcall(vim.api.nvim_buf_delete, other, { force = true })
      end
    end
  end)
end
-- Asks Eclipse to write, and shows its answer. kind '' saves the editor; 'copy', 'lines' and
-- 'append' write to the file `name` (lines first..last). Neovim waits for the answer:
-- Eclipse writes on its UI thread (see EditorAttachManager). v:cmdarg has the write's
-- ++opt arguments (" ++p" for :w ++p); v:cmdbang the !, which lets >> create a file.
-- Returns whether it was written, and the file the editor now shows when Eclipse saved
-- the document as another file (:saveas, or a 'copy' of an editor without a file), else ''.
local function ask(b, kind, name, first, last)
  local answer = vim.rpcrequest(vim.g.neovim4eclipse_channel, 'eclipse_write', b, vim.v.cmdarg,
    kind, name, first, last, vim.v.cmdbang == 1)
  -- { error, message, saved_as }, each '' when none
  local err, written, saved_as = answer[1], answer[2], answer[3] or ''
  if err ~= '' then
    fail(err)
    return false, ''
  end
  if written ~= '' then
    -- Vim's own message after a write, which Neovim does not give for an acwrite buffer.
    vim.api.nvim_echo({ { written } }, true, {})
  end
  return true, saved_as
end

-- The hooks, one function each for every document buffer; ev.buf is the buffer the event is
-- for (each autocommand is local to one buffer).

local function on_write(ev)
  local file = vim.b[ev.buf].neovim4eclipse_file
  local target = vim.fn.fnamemodify(ev.match, ':p')
  if canonical(target) == canonical(file) then
    if ask(ev.buf, '', '', 0, 0) then
      vim.bo[ev.buf].modified = false
    end
  elseif own(ev.buf, target) then
    -- :saveas new.txt: Neovim renamed the buffer, then writes it under the new name.
    -- Eclipse saves the document as that file and moves the editor there.
    state_of(ev.buf).saveas_tried = true -- the BufFilePost check below then stays quiet
    if ask(ev.buf, 'saveas', target, 0, 0) then
      vim.b[ev.buf].neovim4eclipse_file = vim.api.nvim_buf_get_name(ev.buf)
      vim.bo[ev.buf].modified = false
    end
  else
    -- :w other.txt: the text to another file, as in Vim. The editor's changes stay unsaved.
    local old = vim.api.nvim_buf_get_name(ev.buf)
    local ok, saved_as = ask(ev.buf, 'copy', target, 0, 0)
    if ok and saved_as ~= '' then
      -- An editor without a file of its own (an untitled one): Eclipse saved it as the
      -- file instead (OtherFileWrites.copyOrSaveAs), and the editor shows that file now.
      -- Vim too names a buffer without a file after the file it writes (:help cpo-F),
      -- which Neovim does not do for an acwrite buffer. Renamed here, the buffer is
      -- "[Not edited]" until its next write. As in Vim, an unloaded buffer of that name
      -- (Neovim's alternate file, or the buffer kept from a closed editor of the file)
      -- is wiped by the rename; a loaded one makes it fail (E95).
      state_of(ev.buf).saveas_tried = true -- the BufFilePost check below then stays quiet
      local renamed, rename_err = pcall(vim.api.nvim_buf_set_name, ev.buf, saved_as)
      if renamed then
        vim.b[ev.buf].neovim4eclipse_file = saved_as
        vim.bo[ev.buf].modified = false
      else
        -- The buffer keeps its name and its file, so a later :w is still the editor's
        -- own save (Eclipse saves the file the editor shows), never a save as under the
        -- old name.
        state_of(ev.buf).saveas_tried = nil
        fail('Eclipse saved ' .. vim.fn.fnamemodify(saved_as, ':t')
          .. ', but Neovim could not name this buffer after it: ' .. tostring(rename_err))
      end
    end
    wipe_left_over(ev.buf, { ev.match, target, old })
  end
end

-- :5,10w part.txt and :w >> log.txt: some or all lines to another file, between the marks
-- '[ and '] that Neovim sets for the write. Not to the editor's own file, which Eclipse
-- saves whole: :5,10w! would leave only those lines on disk, and >> would double the file.
local LINE_WRITES = { FileWriteCmd = 'lines', FileAppendCmd = 'append' }
local function on_line_write(ev)
  local target = vim.fn.fnamemodify(ev.match, ':p')
  if own(ev.buf, target) then
    return fail('Eclipse saves this file only whole; use :w')
  end
  ask(ev.buf, LINE_WRITES[ev.event], target, vim.fn.line("'["), vim.fn.line("']"))
  wipe_left_over(ev.buf, { ev.match, target })
end

-- The buffer keeps the editor's name. :file new.txt renames it without a file (the editor
-- would still show the old one), and so does a :saveas that Eclipse could not carry out:
-- once the command is over (scheduled), the old name is put back; only :file says why.
-- A rename also leaves a buffer behind under the other name (wipe_left_over).
local function before_rename(ev)
  state_of(ev.buf).name_before = vim.api.nvim_buf_get_name(ev.buf)
end
local function after_rename(ev)
  local b = ev.buf
  local st = state_of(b)
  local names = { st.name_before, vim.api.nvim_buf_get_name(b) }
  st.name_before = nil
  vim.schedule(function()
    if not vim.api.nvim_buf_is_valid(b) then
      return
    end
    local quiet = st.saveas_tried
    st.saveas_tried = nil
    local file = vim.b[b].neovim4eclipse_file
    if canonical(vim.api.nvim_buf_get_name(b)) ~= canonical(file) then
      vim.api.nvim_buf_call(b, function()
        if file:find('[\r\n]') then
          -- Not through :file: a line break would split the command in two (see the
          -- read hook in PROJECT_FILES); the API takes the name as it is.
          pcall(vim.api.nvim_buf_set_name, b, file)
        else
          vim.cmd('keepalt noautocmd file ' .. vim.fn.fnameescape(file))
        end
      end)
      if not quiet then
        fail('file: Eclipse names this buffer after its file; use :saveas or File > Save As')
      end
    end
    wipe_left_over(b, names)
  end)
end

-- Safety net: Neovim deleting this buffer by itself (<Cmd>bd, Lua, :bufdo bd) closes its
-- editor too. Eclipse ignores it for a buffer no editor shows. (When Eclipse closes the
-- last editor, CLOSE_DOCUMENT_BUFFER removes this autocommand first.) A wiped buffer is gone
-- for good, its number never given again, and Neovim removes its autocommands: its records
-- go too (BufWipeout comes last, after BufUnload and BufDelete).
local function on_delete(ev)
  if ev.event == 'BufWipeout' then
    state[ev.buf], hooks[ev.buf] = nil, nil
  end
  vim.rpcnotify(vim.g.neovim4eclipse_channel, 'eclipse_buffer_closed', ev.buf)
end

-- Reloaded by Neovim (a script's or a plug-in's :edit!): nothing is read from disk, which
-- would replace Eclipse's text and its unsaved changes. The text the unload took is put
-- back at once, so the buffer is never empty, and Eclipse attaches again
-- (eclipse_reload, true). Kept only until Neovim's next loop: a buffer unloaded on its own
-- (:bunload) and read much later gets Eclipse's text instead (eclipse_reload, false).
-- The marks too: Neovim drops an acwrite buffer's a-z marks when it unloads it, and puts
-- its A-Z marks on line 1 (BUFFER_NUMBERS.md, X4), where Vim's :edit! keeps them; the
-- unload runs with 'buftype' '' (X5) and the read puts them back besides.
local function on_unload(ev)
  local b = ev.buf
  local marks = {}
  for c in ('abcdefghijklmnopqrstuvwxyz'):gmatch('.') do
    local m = vim.api.nvim_buf_get_mark(b, c)
    if m[1] > 0 then
      marks[c] = m
    end
  end
  for c in ('ABCDEFGHIJKLMNOPQRSTUVWXYZ'):gmatch('.') do
    local m = vim.api.nvim_get_mark(c, {})
    if m[3] == b and m[1] > 0 then
      marks[c] = { m[1], m[2] }
    end
  end
  local st = state_of(b)
  -- The clean-up first, then what it cleans: should a later step fail, the text is still
  -- dropped on the next loop, never handed to a read much later.
  vim.schedule(function()
    st.kept = nil
    if vim.api.nvim_buf_is_valid(b) and M.is_document(b) then
      set_buftype(b, 'acwrite') -- still a document's buffer (not closed meanwhile)
    end
  end)
  st.kept = { lines = vim.api.nvim_buf_get_lines(b, 0, -1, false), modified = vim.bo[b].modified,
              marks = marks }
  -- Unloaded as a normal file's buffer, the buffer keeps its marks and last position
  -- (BUFFER_NUMBERS.md, X5): for Neovim's own :bdelete (a Lua plug-in, <Cmd>bd!), whose
  -- buffer Eclipse then closes (the safety net), as for CLOSE_DOCUMENT_BUFFER's unload.
  -- 'acwrite' comes back with the read (BufReadCmd below) or on the next loop.
  vim.bo[b].buftype = '' -- no OptionSet: inside an autocommand (see set_buftype)
end
local function on_read(ev)
  local b = ev.buf
  vim.bo[b].buftype = 'acwrite' -- BufUnload made it '' for the unload; no OptionSet here
  local st = state_of(b)
  local k = st.kept
  st.kept = nil
  if k then
    local saved = vim.bo[b].undolevels
    vim.bo[b].undolevels = -1 -- no undo step, as for a file just read
    package.loaded['neovim4eclipse.load'] = { buf = b, lines = k.lines }
    pcall(vim.cmd, "lockmarks lua local l = package.loaded['neovim4eclipse.load'] "
      .. "vim.api.nvim_buf_set_lines(l.buf, 0, -1, false, l.lines)")
    package.loaded['neovim4eclipse.load'] = nil
    vim.bo[b].undolevels = saved
    vim.bo[b].modified = k.modified
    for c, m in pairs(k.marks) do
      pcall(vim.api.nvim_buf_set_mark, b, c, m[1], m[2], {})
    end
  end
  vim.rpcnotify(vim.g.neovim4eclipse_channel, 'eclipse_reload', b, k ~= nil)
end

-- Removes buffer b's hooks and what they kept (nothing for a buffer that has none).
local function remove_hooks(b)
  for _, id in ipairs(hooks[b] or {}) do
    pcall(vim.api.nvim_del_autocmd, id) -- one a config deleted already is gone
  end
  hooks[b], state[b] = nil, nil
end

--- Whether buf is an open document's buffer now: setup gave it its hooks, and neither
--- M.close nor a wipe took them since. Example: false for a buffer whose editor closed
--- (CLOSE_DOCUMENT_BUFFER), which keeps b:neovim4eclipse_file.
---
--- The record, not a look at the autocommands: whether the buffer is a document is
--- Eclipse's to say (an editor shows it), and a config that cleared the hooks
--- (`:autocmd! * <buffer>`) does not make it a plain file's. Each caller's answer for such a
--- buffer is the safe one: 'acwrite' stays (or, with the unload hook still there, the unload
--- puts it back), so Neovim refuses a :w (E676) instead of writing the disk behind Eclipse;
--- PROJECT_FILES' read hook and the help pages leave the buffer alone, as Eclipse's.
function M.is_document(buf)
  return hooks[buf] ~= nil
end

--- Takes the hooks off buffer `buf`, its last editor closed (CLOSE_DOCUMENT_BUFFER, which
--- then unloads it): Neovim reads the file itself again if the buffer is shown later.
function M.close(buf)
  remove_hooks(buf)
end

--- Makes buffer `buf` a document buffer: 'acwrite', b:neovim4eclipse_file, and the hooks
--- above as autocommands local to it. Again for the same buffer (one reused for a new
--- editor), its hooks are made anew, once. Example: setup(3) after CREATE_DOCUMENT_BUFFER
--- made buffer 3 for /ws/app/A.java.
function M.setup(buf)
  vim.bo[buf].buftype = 'acwrite'
  group = vim.api.nvim_create_augroup(GROUP, { clear = false })
  remove_hooks(buf)
  state[buf] = {}
  local ids = {}
  hooks[buf] = ids
  -- Every hook has a desc that starts with "Eclipse " (`:autocmd neovim4eclipse_document`
  -- shows it), by which CLOSE_DOCUMENT_BUFFER finds them without this module in a group a
  -- config deleted.
  local function hook(event, opts)
    ids[#ids + 1] = vim.api.nvim_create_autocmd(event, opts)
  end
  -- The editor's file, as Neovim names it (links resolved). Writes are compared with it, not
  -- with the buffer's name, which :saveas and :file change.
  vim.b[buf].neovim4eclipse_file = vim.api.nvim_buf_get_name(buf)
  keep_iskeyword(buf)
  hook('FileType', {
    group = group,
    buffer = buf,
    desc = "Eclipse keeps the document's 'iskeyword'",
    callback = function()
      keep_iskeyword(buf)
    end,
  })
  hook('OptionSet', {
    group = group,
    buffer = buf,
    desc = "Eclipse keeps the document's 'iskeyword'",
    callback = function(ev)
      if ev.match == 'iskeyword' then
        keep_iskeyword(buf)
      end
    end,
  })
  hook('BufWriteCmd', {
    buf = buf, -- named "buffer" before Neovim 0.12.2
    group = group,
    desc = 'Eclipse saves the document',
    callback = on_write,
  })
  for event in pairs(LINE_WRITES) do
    hook(event, {
      buf = buf,
      group = group,
      desc = 'Eclipse writes the lines to another file',
      callback = on_line_write,
    })
  end
  hook('BufFilePre', {
    buf = buf,
    group = group,
    desc = 'Eclipse notes the name before a rename',
    callback = before_rename,
  })
  hook('BufFilePost', {
    buf = buf,
    group = group,
    desc = 'Eclipse keeps the buffer named after its file',
    callback = after_rename,
  })
  hook({ 'BufDelete', 'BufWipeout' }, {
    buf = buf,
    group = group,
    desc = 'Eclipse closes the editor',
    callback = on_delete,
  })
  hook('BufUnload', {
    buf = buf,
    group = group,
    desc = 'Eclipse keeps the text for a reload',
    callback = on_unload,
  })
  hook('BufReadCmd', {
    buf = buf,
    group = group,
    desc = 'Eclipse keeps the document',
    callback = on_read,
  })
end

package.loaded['neovim4eclipse.document_buffer'] = M
