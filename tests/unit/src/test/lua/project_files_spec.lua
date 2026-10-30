-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- LuaScripts.PROJECT_FILES: Neovim never reads a file of an open Eclipse project by itself, so no
-- filetype plug-in, FileType autocommand or language server runs for it (docs/design/
-- BUFFER_NUMBERS.md, 2.3 and Q3). Eclipse's side (opening the file's editor when Neovim enters such a
-- buffer) is in the UI tests (TwoEditorsEndToEndTest); the folders Eclipse sends are ProjectFoldersTest.

local t = ...

--- A project folder with files, the module installed and told about it. Returns the folder.
local function project()
  vim.cmd('filetype plugin indent on') -- as in a normal Neovim: what the hook must keep away
  t.setup()
  t.run('PROJECT_FILES')
  local dir = vim.fs.normalize(vim.uv.fs_realpath(vim.fn.tempname()) or vim.fn.tempname())
  local root = vim.fs.joinpath(vim.fn.fnamemodify(dir, ':h'), vim.fn.fnamemodify(dir, ':t') .. '-app')
  vim.fn.mkdir(vim.fs.joinpath(root, 'src'), 'p')
  vim.fn.writefile({ 'see B.java' }, vim.fs.joinpath(root, 'src', 'A.java'))
  vim.fn.writefile({ 'class B {', '\tint x;', '}' }, vim.fs.joinpath(root, 'src', 'B.java'))
  t.run('SET_PROJECT_FOLDERS', root)
  return root
end

--- Records Neovim's own read events from now on; returns the list.
local function reads()
  local seen = {}
  vim.api.nvim_create_autocmd({ 'BufReadPre', 'BufReadPost', 'FileType' }, { callback = function(ev)
    seen[#seen + 1] = ev.event .. ':' .. vim.fn.fnamemodify(ev.file, ':t')
  end })
  return seen
end

local function file(root, ...)
  return vim.fs.joinpath(root, ...)
end

t.test('gf into a project file: read without filetype, undo step or swap file; its name goes with BufEnter', function()
  local root = project()
  local seen = reads()
  vim.cmd.edit(vim.fn.fnameescape(file(root, 'src', 'A.java')))
  t.forget()
  vim.api.nvim_win_set_cursor(0, { 1, 5 })
  t.type('gf')
  local b = vim.api.nvim_get_current_buf()
  t.eq({ 'class B {', '\tint x;', '}' }, vim.api.nvim_buf_get_lines(b, 0, -1, false))
  t.eq({}, seen, 'no BufReadPre, BufReadPost or FileType')
  t.eq('', vim.bo[b].filetype)
  t.eq(false, vim.bo[b].swapfile)
  t.eq(false, vim.bo[b].modified)
  t.eq(0, vim.fn.undotree(b).seq_last, 'nothing to undo')
  local entered = t.sent('eclipse_buf_enter')
  t.eq(b, entered[#entered][1])
  t.eq(vim.api.nvim_buf_get_name(b), entered[#entered][4], 'the file, for Eclipse to open its editor')
end)

-- :vimgrep makes the buffers of the files it matched while it searches, without BufNew, and its
-- jump into one fires only BufAdd before the read (Neovim 0.12.6). Found on macOS 2026-10-30: the
-- buffers had no hook, and Eclipse opened no editor. What the hook cannot prevent: to search a file,
-- :vimgrep loads it itself, filetype detection included, so the matched buffers keep a filetype; and
-- the first match stays loaded from that search. A later jump (:cnext) is read by the hook. Both go
-- to Eclipse to open their editors.
t.test(':vimgrep and :cnext into project files: Eclipse gets each file; :cnext reads by the hook', function()
  local root = project()
  vim.cmd.edit(vim.fn.fnameescape(file(root, 'src', 'A.java')))
  vim.cmd.lcd(vim.fn.fnameescape(root))
  vim.cmd.bwipeout() -- start with no buffer of either file, as after a fresh start
  t.forget()
  vim.cmd('silent vimgrep /B/ src/*.java')
  local a = vim.api.nvim_get_current_buf()
  t.same_file(file(root, 'src', 'A.java'), vim.api.nvim_buf_get_name(a))
  local entered = t.sent('eclipse_buf_enter')
  t.eq(vim.api.nvim_buf_get_name(a), entered[#entered][4], 'the first match\'s file, for Eclipse to open its editor')

  local seen = reads()
  vim.cmd('silent cnext')
  local b = vim.api.nvim_get_current_buf()
  t.same_file(file(root, 'src', 'B.java'), vim.api.nvim_buf_get_name(b))
  t.eq({}, seen, ':cnext: read by the hook, no BufReadPre, BufReadPost or FileType')
  entered = t.sent('eclipse_buf_enter')
  t.eq(vim.api.nvim_buf_get_name(b), entered[#entered][4], 'its file, for Eclipse to open its editor')
end)

t.test('a file outside the projects, and one in a folder whose name starts the same, are Neovim\'s to read', function()
  local root = project()
  local sibling = root .. '2'
  vim.fn.mkdir(sibling, 'p')
  vim.fn.writefile({ 'class C {}' }, file(sibling, 'C.java'))
  vim.cmd.edit(vim.fn.fnameescape(file(sibling, 'C.java')))
  t.eq('java', vim.bo.filetype, 'Neovim read it as usual')
  t.eq('', require('neovim4eclipse.project_files').file_of(0 + vim.api.nvim_get_current_buf()))
  local entered = t.sent('eclipse_buf_enter')
  t.eq('', entered[#entered][4])
end)

t.test('a file reached through a link to the project is the project\'s', function()
  local root = project()
  local link = root .. '-link'
  -- On Windows a symbolic link needs developer mode or admin rights (EPERM without them); a
  -- junction, the folder link Windows users usually make (mklink /J), needs neither.
  assert(vim.uv.fs_symlink(root, link, vim.fn.has('win32') == 1 and { junction = true } or nil))
  vim.cmd.edit(vim.fn.fnameescape(file(link, 'src', 'B.java')))
  t.eq('', vim.bo.filetype)
  t.eq({ 'class B {', '\tint x;', '}' }, vim.api.nvim_buf_get_lines(0, 0, -1, false))
end)

t.test('buffers made before the folders came get the hook; a folder that goes takes it away', function()
  vim.cmd('filetype plugin indent on')
  t.setup()
  t.run('PROJECT_FILES')
  local root = vim.fn.tempname()
  vim.fn.mkdir(root, 'p')
  local f = file(root, 'B.java')
  vim.fn.writefile({ 'class B {}' }, f)
  local early = vim.fn.bufadd(f)
  local files = require('neovim4eclipse.project_files')
  t.eq('', files.file_of(early))
  t.run('SET_PROJECT_FOLDERS', root)
  t.eq(vim.api.nvim_buf_get_name(early), files.file_of(early))
  vim.fn.bufload(early)
  t.eq('', vim.bo[early].filetype, 'read by the hook')
  t.run('SET_PROJECT_FOLDERS') -- the project was closed
  t.eq('', files.file_of(early))
end)

t.test('a closed document\'s buffer is read by the hook: its number, marks and last place stay', function()
  local root = project()
  local made = t.run('CREATE_DOCUMENT_BUFFER', file(root, 'src', 'B.java'))
  local b = made[1]
  vim.api.nvim_win_set_buf(0, b)
  t.run('DOCUMENT_BUFFER', tostring(b))
  t.run('LOAD_WITHOUT_UNDO', 'class B {', '\tint x;', '}')
  vim.api.nvim_buf_set_mark(b, 'a', 2, 0, {})
  vim.api.nvim_win_set_cursor(0, { 3, 0 })
  local other = vim.api.nvim_create_buf(true, true)
  vim.api.nvim_win_set_buf(0, other)
  t.run('CLOSE_DOCUMENT_BUFFER', tostring(b))
  local seen = reads()
  vim.cmd('buffer ' .. b)
  t.eq(b, vim.api.nvim_get_current_buf())
  t.eq({}, seen)
  t.eq({ 'class B {', '\tint x;', '}' }, vim.api.nvim_buf_get_lines(b, 0, -1, false))
  t.eq({ 2, 0 }, vim.api.nvim_buf_get_mark(b, 'a'))
  t.eq(3, vim.api.nvim_win_get_cursor(0)[1], "the cursor on '\"")
end)

t.test('a document reloaded by a script\'s :edit! keeps its text; loaded empty, Eclipse\'s reload fills it', function()
  local root = project()
  local path = file(root, 'src', 'B.java')
  local made = t.run('CREATE_DOCUMENT_BUFFER', path)
  local b = made[1]
  vim.api.nvim_win_set_buf(0, b)
  t.run('DOCUMENT_BUFFER', tostring(b))
  t.run('LOAD_WITHOUT_UNDO', 'unsaved in Eclipse')
  vim.api.nvim_buf_set_mark(b, 'a', 1, 0, {})
  t.forget()
  vim.cmd('edit!')
  t.eq({ { b, true } }, t.sent('eclipse_reload'), 'once: the project hook leaves it to the document')
  t.eq({ 'unsaved in Eclipse' }, vim.api.nvim_buf_get_lines(b, 0, -1, false), 'the disk is not read')
  -- RELOAD_DOCUMENT_BUFFER, for a buffer Neovim loaded empty (see document_buffer_spec):
  vim.api.nvim_buf_set_lines(b, 0, -1, false, { '' })
  local other = vim.api.nvim_create_buf(true, true)
  vim.api.nvim_win_set_buf(0, other) -- the reload works on a buffer that is not the current one
  t.run('RELOAD_DOCUMENT_BUFFER', tostring(b), 'unsaved in Eclipse', 'two')
  t.eq({ 'unsaved in Eclipse', 'two' }, vim.api.nvim_buf_get_lines(b, 0, -1, false))
  t.eq(false, vim.bo[b].modified)
  t.eq(other, vim.api.nvim_get_current_buf())
  vim.api.nvim_win_set_buf(0, b)
  t.type('u')
  t.eq({ 'unsaved in Eclipse', 'two' }, vim.api.nvim_buf_get_lines(b, 0, -1, false), 'no undo step')
  t.run('RELOAD_DOCUMENT_BUFFER', '9999', 'x') -- a buffer that is gone: nothing
end)

t.test('a stale swap file does not stop the read at E325', function()
  local root = project()
  local path = file(root, 'src', 'B.java')
  -- A swap file as an earlier Neovim that crashed would leave it: made (by Neovim's own read, the
  -- project off for a moment), kept aside, put back.
  t.run('SET_PROJECT_FOLDERS')
  vim.o.updatecount = 200 -- nvim -l starts with swap files off (0), a normal Neovim with 200
  vim.o.directory = vim.fn.tempname() .. '//'
  vim.fn.mkdir(vim.o.directory, 'p')
  local probe = vim.fn.bufadd(path)
  vim.bo[probe].swapfile = true
  vim.fn.bufload(probe)
  vim.api.nvim_buf_call(probe, function() vim.cmd('silent! noautocmd normal! ix') vim.cmd('preserve') end)
  local swap = vim.fn.swapname(probe)
  t.ok(swap ~= '' and vim.fn.filereadable(swap) == 1, 'a swap file was made')
  assert(vim.uv.fs_copyfile(swap, swap .. '.keep'))
  vim.api.nvim_buf_delete(probe, { force = true })
  assert(vim.uv.fs_rename(swap .. '.keep', swap))
  t.run('SET_PROJECT_FOLDERS', root)
  vim.cmd.edit(vim.fn.fnameescape(path))
  t.eq({ 'class B {', '\tint x;', '}' }, vim.api.nvim_buf_get_lines(0, 0, -1, false))
  t.eq('n', vim.api.nvim_get_mode().mode, 'no question asked')
end)

t.test('a missing file stays empty, and encodings and line endings are decoded as Neovim does', function()
  local root = project()
  vim.cmd.edit(vim.fn.fnameescape(file(root, 'gone.txt')))
  t.eq({ '' }, vim.api.nvim_buf_get_lines(0, 0, -1, false))
  local function raw(name, bytes)
    local f = assert(io.open(file(root, name), 'wb'))
    f:write(bytes)
    f:close()
    return file(root, name)
  end
  vim.cmd.edit(vim.fn.fnameescape(raw('crlf.txt', 'one\r\ntwo\r\n')))
  t.eq({ 'one', 'two' }, vim.api.nvim_buf_get_lines(0, 0, -1, false))
  t.eq('dos', vim.bo.fileformat)
  vim.cmd.edit(vim.fn.fnameescape(raw('latin1.txt', 'caf\233\n')))
  t.eq({ 'café' }, vim.api.nvim_buf_get_lines(0, 0, -1, false))
end)

t.test('bufload() of a project file (a picker\'s preview) is read by the hook too', function()
  local root = project()
  local seen = reads()
  local p = vim.fn.bufadd(file(root, 'src', 'B.java'))
  vim.fn.bufload(p)
  t.eq({}, seen)
  t.eq({ 'class B {', '\tint x;', '}' }, vim.api.nvim_buf_get_lines(p, 0, -1, false))
end)

t.test('a file another BufReadCmd reads (zip.vim browsing an archive) is left to it', function()
  local root = project()
  -- As Neovim's zip.vim does for *.zip: its own reader, defined before the plug-in's, read-only.
  vim.api.nvim_create_autocmd('BufReadCmd', { pattern = '*.archive', callback = function(ev)
    vim.api.nvim_buf_set_lines(ev.buf, 0, -1, false, { '" Browsing', 'inner.txt' })
    vim.bo[ev.buf].modifiable = false
  end })
  vim.fn.writefile({ 'raw bytes' }, file(root, 'x.archive'))
  vim.cmd('messages clear')
  vim.cmd.edit(vim.fn.fnameescape(file(root, 'x.archive')))
  t.eq({ '" Browsing', 'inner.txt' }, vim.api.nvim_buf_get_lines(0, 0, -1, false))
  t.eq('', vim.fn.execute('messages'):gsub('^%s+', ''), 'no E21')
end)
