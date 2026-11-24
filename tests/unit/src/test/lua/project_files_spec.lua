-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- LuaScripts.PROJECT_FILES: Neovim never reads a file of an open Eclipse project by itself, so no
-- filetype plug-in, FileType autocommand or language server runs for it (docs/design/
-- BUFFER_NUMBERS.md, 2.3). Eclipse's side (opening the file's editor when Neovim enters such a
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
  t.run('SET_PROJECT_FOLDERS', { root })
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

-- A modeline may set global options, 'isfname' among them: with ` and the space in it, Ctrl-X
-- Ctrl-F in any buffer takes a line's `...` for a file name and runs it in the shell. So no
-- modeline of a file the hook reads applies, as none of a document Eclipse opens does.
t.test('a modeline in a file the hook reads does not apply', function()
  local root = project()
  vim.o.modeline = true
  local isfname = vim.o.isfname
  vim.fn.writefile({ '// vim: set isfname+=`,32 tabstop=3 :', 'class B {}' }, file(root, 'src', 'B.java'))
  vim.cmd.edit(vim.fn.fnameescape(file(root, 'src', 'A.java')))
  vim.api.nvim_win_set_cursor(0, { 1, 5 })
  t.type('gf')
  t.eq('// vim: set isfname+=`,32 tabstop=3 :', vim.api.nvim_buf_get_lines(0, 0, 1, false)[1], 'the file was read')
  t.eq(isfname, vim.o.isfname, "'isfname' is as it was")
  t.ok(vim.bo.tabstop ~= 3, "nor did 'tabstop' change")
  t.eq(false, vim.bo.modeline)
end)

-- PROJECT_FILES finds PATHS in package.loaded, as the session sent it first: without it, the install
-- fails at once, rather than leaving a read hook that fails on every file (and Neovim reading the
-- disk itself), or require searching the runtimepath for it.
t.test('the project files module is not installed without PATHS sent first', function()
  local paths, files = package.loaded['neovim4eclipse.paths'], package.loaded['neovim4eclipse.project_files']
  package.loaded['neovim4eclipse.paths'], package.loaded['neovim4eclipse.project_files'] = nil, nil
  local ok, err = pcall(t.run, 'PROJECT_FILES')
  local installed = package.loaded['neovim4eclipse.project_files']
  package.loaded['neovim4eclipse.paths'], package.loaded['neovim4eclipse.project_files'] = paths, files
  t.ok(not ok and tostring(err):find('LuaScripts.PATHS', 1, true) ~= nil, tostring(err))
  t.eq(nil, installed)
end)

-- A cloned repository may hold a file whose name has a line break (legal on Linux and macOS). Built
-- into the hook's :read, the rest of the name ran as a command of its own: the attack needed only
-- a second file named like the first line plus a backslash, so the :read before it succeeded
-- (0.2.1 security review). Now such a file is not read at all.
t.test('a file name with a line break is not read, and nothing in it runs', function()
  if vim.fn.has('win32') == 1 then
    t.skip('Windows names cannot hold a line break')
  end
  local root = project()
  vim.g.pwned = nil
  local decoy = file(root, 'src', 'x\\')
  local evil = file(root, 'src', 'x\nlua=vim.g.pwned=1')
  vim.fn.writefile({ 'decoy' }, decoy)
  vim.fn.writefile({ 'evil' }, evil)
  local b = vim.fn.bufadd(evil)
  vim.api.nvim_set_current_buf(b)
  vim.cmd('silent! edit') -- loads it: the hook's BufReadCmd runs
  t.eq(nil, vim.g.pwned, 'the Lua in the name did not run')
  t.eq({ '' }, vim.api.nvim_buf_get_lines(b, 0, -1, false), 'not read')
  t.ok(vim.fn.execute('messages'):find('line break', 1, true), 'it says why')
end)

-- :vimgrep makes the buffers of the files it matched while it searches, without BufNew, and its
-- jump into one fires only BufAdd before the read (Neovim 0.12.6). Found on macOS 2026-10-30: the
-- buffers had no hook, and Eclipse opened no editor. What the hook cannot prevent: to search a file,
-- :vimgrep loads it itself, filetype detection included, so the matched buffers keep a filetype; and
-- the first match stays loaded from that search (without a swap file: the search makes none). A
-- later jump (:cnext) is read by the hook. Both go to Eclipse to open their editors.
t.test(':vimgrep and :cnext into project files: Eclipse gets each file; :cnext reads by the hook', function()
  local root = project()
  vim.o.updatecount = 200 -- nvim -l starts with swap files off (0), a normal Neovim with 200
  vim.o.directory = vim.fn.tempname() .. '//'
  vim.fn.mkdir(vim.o.directory, 'p')
  vim.cmd.edit(vim.fn.fnameescape(file(root, 'src', 'A.java')))
  vim.cmd.lcd(vim.fn.fnameescape(root))
  vim.cmd.bwipeout() -- start with no buffer of either file, as after a fresh start
  t.forget()
  vim.cmd('silent vimgrep /B/ src/*.java')
  local a = vim.api.nvim_get_current_buf()
  t.same_file(file(root, 'src', 'A.java'), vim.api.nvim_buf_get_name(a))
  t.eq(false, vim.bo[a].swapfile, 'the first match, loaded by the search: no swap file')
  t.eq({}, vim.fn.glob(vim.o.directory .. '*', true, true), 'the search\'s swap file is gone')
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

-- Where no swap file can be made (E303; on Windows, the UI tests' long paths), :vimgrep stopped at a
-- prompt for each file it searched (found 2026-10-30). Its search now makes none, so it runs through;
-- here, from Lua, E303 would be an error. A regular file as 'directory' cannot hold swap files on
-- any system.
t.test(':vimgrep runs through where no swap file can be made, and \'swapfile\' comes back', function()
  local root = project()
  vim.o.updatecount = 200 -- nvim -l starts with swap files off (0), a normal Neovim with 200
  local not_a_folder = vim.fn.tempname()
  vim.fn.writefile({}, not_a_folder)
  vim.o.directory = not_a_folder .. '//'
  vim.o.swapfile = true
  vim.cmd.lcd(vim.fn.fnameescape(root))
  vim.cmd('silent vimgrep /B/ src/*.java')
  t.eq(2, #vim.fn.getqflist(), 'both matches')
  t.eq('n', vim.api.nvim_get_mode().mode, 'no prompt')
  t.same_file(file(root, 'src', 'A.java'), vim.api.nvim_buf_get_name(0))
  t.eq(false, vim.bo.swapfile, 'the first match has none')
  t.eq(true, vim.go.swapfile, 'the global value is back')
end)

-- BufAdd checks a buffer only when BufNew did not, under the same name: the check reads the disk
-- (is_help looks for a doc folder's tags file), and :vimgrep makes many buffers at once
-- (docs/design/DIAGNOSTICS.md, Q-E1).
t.test(':badd checks the new buffer once, not again on BufAdd', function()
  local root = project()
  local files = package.loaded['neovim4eclipse.project_files']
  local reads_, calls = files.eclipse_reads, 0
  files.eclipse_reads = function(...)
    calls = calls + 1
    return reads_(...)
  end
  local ok, err = pcall(vim.cmd.badd, vim.fn.fnameescape(file(root, 'src', 'B.java')))
  files.eclipse_reads = reads_
  assert(ok, err)
  t.eq(1, calls)
  t.ok(files.file_of(vim.fn.bufnr(file(root, 'src', 'B.java'))) ~= '', 'hooked')
end)

t.test('a file outside the projects is read by the hook too, and its name goes with BufEnter', function()
  local root = project()
  local sibling = root .. '2' -- a folder whose name starts as the project's
  vim.fn.mkdir(sibling, 'p')
  vim.fn.writefile({ 'class C {}' }, file(sibling, 'C.java'))
  local seen = reads()
  vim.cmd.edit(vim.fn.fnameescape(file(sibling, 'C.java')))
  t.eq({ 'class C {}' }, vim.api.nvim_buf_get_lines(0, 0, -1, false))
  t.eq({}, seen, 'no filetype plug-in or language server: Eclipse opens its editor')
  local b = vim.api.nvim_get_current_buf()
  t.eq(vim.api.nvim_buf_get_name(b), package.loaded['neovim4eclipse.project_files'].file_of(b))
  local entered = t.sent('eclipse_buf_enter')
  t.eq(vim.api.nvim_buf_get_name(b), entered[#entered][4])
end)

t.test('a help page and a plug-in\'s buffer with a scheme are Neovim\'s', function()
  project()
  local files = package.loaded['neovim4eclipse.project_files']
  vim.cmd('help help')
  t.eq('help', vim.bo.buftype)
  t.ok(vim.api.nvim_buf_line_count(0) > 10, 'Neovim read the help file itself')
  t.eq('', files.file_of(vim.api.nvim_get_current_buf()))
  t.eq(true, files.is_help(vim.api.nvim_buf_get_name(0)))
  local entered = t.sent('eclipse_buf_enter')
  t.eq('', entered[#entered][4])
  local scheme = vim.api.nvim_create_buf(true, false)
  vim.api.nvim_buf_set_name(scheme, 'fugitive:///repo/.git//0/a.txt')
  t.eq(false, files.eclipse_reads(vim.api.nvim_buf_get_name(scheme)))
  t.eq(true, files.eclipse_reads('/etc/hosts'))
  t.eq(true, files.eclipse_reads('C:\\Users\\you\\a.txt'))
  t.eq(true, files.eclipse_reads('C:/Users/you/a.txt'))
  t.eq(true, files.eclipse_reads('\\\\server\\share\\a.txt'))
  t.eq(false, files.eclipse_reads(''))
end)

t.test('a project\'s own doc/*.txt beside doc/tags (a Vim plug-in\'s repository) is the project\'s', function()
  local root = project()
  vim.fn.mkdir(file(root, 'doc'), 'p')
  vim.fn.writefile({ '*myplugin.txt*  For Vim', '' }, file(root, 'doc', 'myplugin.txt'))
  vim.fn.writefile({ 'myplugin.txt\tmyplugin.txt\t/*myplugin.txt*' }, file(root, 'doc', 'tags'))
  local files = package.loaded['neovim4eclipse.project_files']
  t.eq(true, files.is_help(file(root, 'doc', 'myplugin.txt')), 'it looks like help')
  vim.cmd.edit(vim.fn.fnameescape(file(root, 'doc', 'myplugin.txt')))
  local b = vim.api.nvim_get_current_buf()
  t.eq('', vim.bo[b].filetype, 'read by the hook, not as help')
  t.eq(vim.api.nvim_buf_get_name(b), files.file_of(b), 'Eclipse opens its editor')
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

t.test('a buffer made before the folders came has the hook, and keeps it when its project closes', function()
  vim.cmd('filetype plugin indent on')
  t.setup()
  t.run('PROJECT_FILES')
  local root = vim.fn.tempname()
  vim.fn.mkdir(root, 'p')
  local f = file(root, 'B.java')
  vim.fn.writefile({ 'class B {}' }, f)
  local early = vim.fn.bufadd(f)
  local files = package.loaded['neovim4eclipse.project_files']
  t.eq(vim.api.nvim_buf_get_name(early), files.file_of(early))
  t.run('SET_PROJECT_FOLDERS', { root })
  vim.fn.bufload(early)
  t.eq('', vim.bo[early].filetype, 'read by the hook')
  t.run('SET_PROJECT_FOLDERS', {}) -- the project was closed: a file outside the projects now
  t.eq(vim.api.nvim_buf_get_name(early), files.file_of(early))
end)

t.test('a closed document\'s buffer is read by the hook: its number, marks and last place stay', function()
  local root = project()
  local made = t.run('CREATE_DOCUMENT_BUFFER', file(root, 'src', 'B.java'))
  local b = made[1]
  vim.api.nvim_win_set_buf(0, b)
  t.run('DOCUMENT_BUFFER', tostring(b))
  t.run('LOAD_WITHOUT_UNDO', { 'class B {', '\tint x;', '}' })
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
  t.run('LOAD_WITHOUT_UNDO', { 'unsaved in Eclipse' })
  vim.api.nvim_buf_set_mark(b, 'a', 1, 0, {})
  t.forget()
  vim.cmd('edit!')
  t.eq({ { b, true } }, t.sent('eclipse_reload'), 'once: the project hook leaves it to the document')
  t.eq({ 'unsaved in Eclipse' }, vim.api.nvim_buf_get_lines(b, 0, -1, false), 'the disk is not read')
  -- RELOAD_DOCUMENT_BUFFER, for a buffer Neovim loaded empty (see document_buffer_spec):
  vim.api.nvim_buf_set_lines(b, 0, -1, false, { '' })
  local other = vim.api.nvim_create_buf(true, true)
  vim.api.nvim_win_set_buf(0, other) -- the reload works on a buffer that is not the current one
  t.run('RELOAD_DOCUMENT_BUFFER', tostring(b), { 'unsaved in Eclipse', 'two' })
  t.eq({ 'unsaved in Eclipse', 'two' }, vim.api.nvim_buf_get_lines(b, 0, -1, false))
  t.eq(false, vim.bo[b].modified)
  t.eq(other, vim.api.nvim_get_current_buf())
  vim.api.nvim_win_set_buf(0, b)
  t.type('u')
  t.eq({ 'unsaved in Eclipse', 'two' }, vim.api.nvim_buf_get_lines(b, 0, -1, false), 'no undo step')
  t.run('RELOAD_DOCUMENT_BUFFER', '9999', { 'x' }) -- a buffer that is gone: nothing
end)

t.test('a stale swap file does not stop the read at E325', function()
  local root = project()
  local path = file(root, 'src', 'B.java')
  -- A swap file as an earlier Neovim that crashed would leave it: made (by Neovim's own read, the
  -- project off for a moment), kept aside, put back.
  vim.o.updatecount = 200 -- nvim -l starts with swap files off (0), a normal Neovim with 200
  vim.o.directory = vim.fn.tempname() .. '//'
  vim.fn.mkdir(vim.o.directory, 'p')
  local probe = vim.fn.bufadd(path)
  vim.api.nvim_clear_autocmds({ group = 'neovim4eclipse_projects', buf = probe }) -- Neovim's own read
  vim.bo[probe].swapfile = true
  vim.fn.bufload(probe)
  vim.api.nvim_buf_call(probe, function() vim.cmd('silent! noautocmd normal! ix') vim.cmd('preserve') end)
  local swap = vim.fn.swapname(probe)
  t.ok(swap ~= '' and vim.fn.filereadable(swap) == 1, 'a swap file was made')
  assert(vim.uv.fs_copyfile(swap, swap .. '.keep'))
  vim.api.nvim_buf_delete(probe, { force = true })
  assert(vim.uv.fs_rename(swap .. '.keep', swap))
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
