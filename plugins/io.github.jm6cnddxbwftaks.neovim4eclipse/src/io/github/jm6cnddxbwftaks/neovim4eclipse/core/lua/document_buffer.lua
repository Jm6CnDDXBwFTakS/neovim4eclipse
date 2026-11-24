-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- Makes a new buffer (argument: its number) a document buffer, which only Eclipse writes. <h2>The
-- concept: `buftype=acwrite`</h2> Eclipse owns the files: a `:w` by Neovim itself would write its
-- copy behind Eclipse's back (no save actions, no refresh, a "file changed on disk" dialog later).
-- A buffer with `buftype=acwrite` is never written by Neovim; every write runs the buffer's
-- `BufWriteCmd` autocommands instead (`:help acwrite`, `:help BufWriteCmd`). The one here asks
-- Eclipse to save the document (the `eclipse_write` request, answered by DocumentWrite) and, if
-- that worked, clears `'modified'`, as a real write does, and shows the message Eclipse answers,
-- Vim's `"a.txt" 12L, 140B written`, which Neovim does not give for such a buffer. So `:w`,
-- `:update`, `:bufdo %s/a/b/g | update`, `:w other.txt` and `vim.cmd.write()` all write through
-- Eclipse. `v:cmdarg` passes `++p` along. `'modified'` itself follows Eclipse's dirty flag
-- (LuaScripts.SET_MODIFIED), so `:update` asks for exactly the files Eclipse would save. Neovim
-- pipes `:w !cmd` without these hooks (checked on Neovim 0.12.6). <h2>Which file: the editor's, or
-- a copy</h2> `BufWriteCmd` gets the name being written (`ev.match`), and the script compares it
-- with the file of the editor, kept in `b:neovim4eclipse_file` when the buffer is made a document
-- buffer. Not with the buffer's name: `:saveas` and `:file` change that.
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
-- ENTER" prompt. The buffer stays modified, so `:update` tries again next time. <h2>Deleted by
-- Neovim: a safety net</h2> A `:bd` on Neovim's command line reaches Eclipse as `:Eclipse bdelete`
-- (LuaScripts.HOST_COMMANDS), which closes the editor. One Neovim runs itself (`<Cmd>bd<CR>`,
-- `vim.cmd.bdelete()`, `:bufdo bd`) deletes the buffer first; its `BufDelete` or `BufWipeout` then
-- sends `eclipse_buffer_closed`, and Eclipse closes the editor showing it, asking about changes as
-- usual, so a buffer and its editor never outlive each other. When Eclipse wipes a buffer itself
-- (its last editor closed), no editor shows it any more and the notice is ignored. <h2>Reloaded by
-- Neovim</h2> A typed `:e!` goes to Eclipse, which reverts the editor (LuaScripts.HOST_COMMANDS). A
-- script's or a plug-in's `:edit!` (one that reloads files after a `git checkout`, say) runs inside
-- Neovim: it unloads the buffer and reads the file from disk, over Eclipse's text and its unsaved
-- changes, and the unload ends the buffer's line events (`nvim_buf_attach`), so later changes in
-- Neovim would never reach the document. Every document's buffer, whether its file is inside a
-- project or not (a file opened with File, Open File...), has two buffer-local autocommands
-- instead:
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
-- Sent as LuaScripts.DOCUMENT_BUFFER (core/LuaModules runs this file as a chunk of its own).

local buf = tonumber(select(1, ...))
vim.bo[buf].buftype = 'acwrite'
-- The plug-in's autocommands for this buffer, in one group: CLOSE_DOCUMENT_BUFFER removes
-- them when the last editor closes, and a buffer reused for a new editor gets them once.
local group = vim.api.nvim_create_augroup('neovim4eclipse_document', { clear = false })
vim.api.nvim_clear_autocmds({ group = group, buf = buf })
-- The editor's file, as Neovim names it (links resolved). Writes are compared with it, not
-- with the buffer's name, which :saveas and :file change.
vim.b[buf].neovim4eclipse_file = vim.api.nvim_buf_get_name(buf)
-- The document's own 'iskeyword', kept for WINDOWS' give_document_back: :help on the
-- document's file (a help file open in an editor) makes it help's, which counts
-- foo.bar(x) as one word for w, * and ciw. Kept now, after each FileType (the
-- filetype's ftplugin sets it) and after each :setlocal iskeyword, never while the
-- buffer is help.
local function keep_iskeyword()
  if vim.bo[buf].buftype ~= 'help' then
    vim.b[buf].neovim4eclipse_iskeyword = vim.bo[buf].iskeyword
  end
end
keep_iskeyword()
vim.api.nvim_create_autocmd('FileType', { group = group, buffer = buf, callback = keep_iskeyword })
vim.api.nvim_create_autocmd('OptionSet', {
  group = group,
  buffer = buf,
  callback = function(ev)
    if ev.match == 'iskeyword' then
      keep_iskeyword()
    end
  end,
})

-- An error message, as Neovim shows its own: err = true sets v:errmsg and the ErrorMsg
-- highlight, and marks the message as an error (msg_show kind "echoerr").
local function fail(msg)
  vim.api.nvim_echo({ { msg } }, true, { err = true })
end
-- PATHS's rule: the buffer name has its links resolved, ev.match not (/private/var/x and
-- /var/x on macOS); on Windows ev.match has / where the buffer name has \ (C:/x/a.txt,
-- C:\x\a.txt); and on Windows and macOS a name in other case is the same file.
local canonical = require('neovim4eclipse.paths').canonical
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

-- Buffers whose :saveas asked Eclipse, so their rename is no :file (see BufFilePost below).
local saveas_tried = {}

vim.api.nvim_create_autocmd('BufWriteCmd', {
  buf = buf, -- named "buffer" before Neovim 0.12.2
  group = group,
  desc = 'Eclipse saves the document',
  callback = function(ev)
    local file = vim.b[ev.buf].neovim4eclipse_file
    local target = vim.fn.fnamemodify(ev.match, ':p')
    if canonical(target) == canonical(file) then
      if ask(ev.buf, '', '', 0, 0) then
        vim.bo[ev.buf].modified = false
      end
    elseif own(ev.buf, target) then
      -- :saveas new.txt: Neovim renamed the buffer, then writes it under the new name.
      -- Eclipse saves the document as that file and moves the editor there.
      saveas_tried[ev.buf] = true -- the BufFilePost check below then stays quiet
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
        saveas_tried[ev.buf] = true -- the BufFilePost check below then stays quiet
        local renamed, rename_err = pcall(vim.api.nvim_buf_set_name, ev.buf, saved_as)
        if renamed then
          vim.b[ev.buf].neovim4eclipse_file = saved_as
          vim.bo[ev.buf].modified = false
        else
          -- The buffer keeps its name and its file, so a later :w is still the editor's
          -- own save (Eclipse saves the file the editor shows), never a save as under the
          -- old name.
          saveas_tried[ev.buf] = nil
          fail('Eclipse saved ' .. vim.fn.fnamemodify(saved_as, ':t')
            .. ', but Neovim could not name this buffer after it: ' .. tostring(rename_err))
        end
      end
      wipe_left_over(ev.buf, { ev.match, target, old })
    end
  end,
})
-- :5,10w part.txt and :w >> log.txt: some or all lines to another file, between the marks
-- '[ and '] that Neovim sets for the write. Not to the editor's own file, which Eclipse
-- saves whole: :5,10w! would leave only those lines on disk, and >> would double the file.
for event, kind in pairs({ FileWriteCmd = 'lines', FileAppendCmd = 'append' }) do
  vim.api.nvim_create_autocmd(event, {
    buf = buf,
    group = group,
    desc = 'Eclipse writes the lines to another file',
    callback = function(ev)
      local target = vim.fn.fnamemodify(ev.match, ':p')
      if own(ev.buf, target) then
        return fail('Eclipse saves this file only whole; use :w')
      end
      ask(ev.buf, kind, target, vim.fn.line("'["), vim.fn.line("']"))
      wipe_left_over(ev.buf, { ev.match, target })
    end,
  })
end
-- The buffer keeps the editor's name. :file new.txt renames it without a file (the editor
-- would still show the old one), and so does a :saveas that Eclipse could not carry out:
-- once the command is over (scheduled), the old name is put back; only :file says why.
-- A rename also leaves a buffer behind under the other name (wipe_left_over).
local names_before = {}
vim.api.nvim_create_autocmd('BufFilePre', {
  buf = buf,
  group = group,
  callback = function(ev)
    names_before[ev.buf] = vim.api.nvim_buf_get_name(ev.buf)
  end,
})
vim.api.nvim_create_autocmd('BufFilePost', {
  buf = buf,
  group = group,
  desc = 'Eclipse keeps the buffer named after its file',
  callback = function(ev)
    local b = ev.buf
    local names = { names_before[b], vim.api.nvim_buf_get_name(b) }
    names_before[b] = nil
    vim.schedule(function()
      if not vim.api.nvim_buf_is_valid(b) then
        return
      end
      local quiet = saveas_tried[b]
      saveas_tried[b] = nil
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
  end,
})
-- Safety net: Neovim deleting this buffer by itself (<Cmd>bd, Lua, :bufdo bd) closes its
-- editor too. Eclipse ignores it for a buffer no editor shows. (When Eclipse closes the
-- last editor, CLOSE_DOCUMENT_BUFFER removes this autocommand first.)
vim.api.nvim_create_autocmd({ 'BufDelete', 'BufWipeout' }, {
  buf = buf,
  group = group,
  desc = 'Eclipse closes the editor',
  callback = function(ev)
    vim.rpcnotify(vim.g.neovim4eclipse_channel, 'eclipse_buffer_closed', ev.buf)
  end,
})
-- Reloaded by Neovim (a script's or a plug-in's :edit!): nothing is read from disk, which
-- would replace Eclipse's text and its unsaved changes. The text the unload took is put
-- back at once, so the buffer is never empty, and Eclipse attaches again
-- (eclipse_reload, true). Kept only until Neovim's next loop: a buffer unloaded on its own
-- (:bunload) and read much later gets Eclipse's text instead (eclipse_reload, false).
-- The marks too: Neovim drops an acwrite buffer's a-z marks when it unloads it, and puts
-- its A-Z marks on line 1 (BUFFER_NUMBERS.md, X4), where Vim's :edit! keeps them; the
-- unload runs with 'buftype' '' (X5) and the read puts them back besides.
local kept
vim.api.nvim_create_autocmd('BufUnload', {
  buf = buf,
  group = group,
  desc = 'Eclipse keeps the text for a reload',
  callback = function(ev)
    local marks = {}
    for c in ('abcdefghijklmnopqrstuvwxyz'):gmatch('.') do
      local m = vim.api.nvim_buf_get_mark(ev.buf, c)
      if m[1] > 0 then
        marks[c] = m
      end
    end
    for c in ('ABCDEFGHIJKLMNOPQRSTUVWXYZ'):gmatch('.') do
      local m = vim.api.nvim_get_mark(c, {})
      if m[3] == ev.buf and m[1] > 0 then
        marks[c] = { m[1], m[2] }
      end
    end
    kept = { lines = vim.api.nvim_buf_get_lines(ev.buf, 0, -1, false), modified = vim.bo[ev.buf].modified,
             marks = marks }
    -- Unloaded as a normal file's buffer, the buffer keeps its marks and last position
    -- (BUFFER_NUMBERS.md, X5): for Neovim's own :bdelete (a Lua plug-in, <Cmd>bd!), whose
    -- buffer Eclipse then closes (the safety net), as for CLOSE_DOCUMENT_BUFFER's unload.
    -- 'acwrite' comes back with the read (BufReadCmd below) or on the next loop.
    vim.bo[ev.buf].buftype = '' -- no OptionSet: inside an autocommand (see set_buftype)
    vim.schedule(function()
      kept = nil
      if vim.api.nvim_buf_is_valid(ev.buf)
          and #vim.api.nvim_get_autocmds({ group = group, buf = ev.buf }) > 0 then
        set_buftype(ev.buf, 'acwrite') -- still a document's buffer (not closed meanwhile)
      end
    end)
  end,
})
vim.api.nvim_create_autocmd('BufReadCmd', {
  buf = buf,
  group = group,
  desc = 'Eclipse keeps the document',
  callback = function(ev)
    vim.bo[ev.buf].buftype = 'acwrite' -- BufUnload made it '' for the unload; no OptionSet here
    local k = kept
    kept = nil
    if k then
      local saved = vim.bo[ev.buf].undolevels
      vim.bo[ev.buf].undolevels = -1 -- no undo step, as for a file just read
      package.loaded['neovim4eclipse.load'] = { buf = ev.buf, lines = k.lines }
      pcall(vim.cmd, "lockmarks lua local l = package.loaded['neovim4eclipse.load'] "
        .. "vim.api.nvim_buf_set_lines(l.buf, 0, -1, false, l.lines)")
      package.loaded['neovim4eclipse.load'] = nil
      vim.bo[ev.buf].undolevels = saved
      vim.bo[ev.buf].modified = k.modified
      for c, m in pairs(k.marks) do
        pcall(vim.api.nvim_buf_set_mark, ev.buf, c, m[1], m[2], {})
      end
    end
    vim.rpcnotify(vim.g.neovim4eclipse_channel, 'eclipse_reload', ev.buf, k ~= nil)
  end,
})
