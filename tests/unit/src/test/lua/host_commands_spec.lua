-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- LuaScripts.HOST_COMMANDS: which : lines Eclipse carries out. Neovim parses the line
-- (nvim_parse_cmd), CmdlineLeavePre swaps it for ":Eclipse <name> <args>", and that command sends
-- eclipse_command (name, bang, args) to Eclipse. What Eclipse then does with it is HostCommandTest
-- and HostCommandRunnerTest (tests/unit, no Neovim). See harness.lua for how these tests run.

local t = ...

--- A full path as :Eclipse sends it: vim.fs.normalize'd, so with / only. On Windows getcwd(),
--- tempname() and expand('~') give backslashes (C:\Users\me), and an expected path built from them
--- with .. '/name' would mix the two; Java turns the / form into the system's (Path.of).
--- Without expanding $NAME: a file named x$HOME is sent as that (no second expansion).
local function sent_path(path)
  return vim.fs.normalize(path, { expand_env = false })
end

local function start()
  t.setup()
  t.run('HOST_COMMANDS', t.host_rules)
end

t.test('a typed line, a mapping, a macro and @: all reach Eclipse, parsed by Neovim', function()
  start()
  vim.cmd('nnoremap <Space>q :q<CR>')
  vim.fn.setreg('a', ':tabn 3\r')

  t.type(':wa<CR>') -- an abbreviation: Neovim expands it to wall
  t.eq({ { 'wall', false, {} } }, t.sent('eclipse_command'))
  t.eq('wa', vim.fn.histget(':', -1), 'the history keeps the line as typed, not the :Eclipse one')

  t.type('<Space>q')
  t.type('@a')
  t.type('@:') -- repeats the last typed line, :wa
  t.type(':q!<CR>')
  t.eq({
    { 'wall', false, {} },
    { 'quit', false, {} },
    { 'tabnext', false, { '3' } },
    { 'wall', false, {} },
    { 'quit', true, {} },
  }, t.sent('eclipse_command'))
end)

t.test('arguments are passed as typed, a count becomes the argument, and | goes on', function()
  start()
  t.type(':e my\\ file.txt<CR>')
  t.type(':2tabn<CR>')
  t.type(':Eclipse! quit | let g:after = 1<CR>')
  t.eq({
    -- The backslash keeps it one argument; :Eclipse's fargs drop it. A file name is made a full
    -- path (the next tests).
    { 'edit', false, { sent_path(vim.fn.getcwd() .. '/my file.txt') } },
    { 'tabnext', false, { '2' } },
    { 'quit', true, {} },
  }, t.sent('eclipse_command'))
  t.eq(1, vim.g.after, 'the command after | ran')
end)

--- Eclipse's side of the write hook (DOCUMENT_BUFFER's eclipse_write request): saves, or fails
--- with `err`. Returns the list of buffers asked for.
local function eclipse_saves(err)
  local asked = {}
  t.answer('eclipse_write', function(buf)
    asked[#asked + 1] = buf
    return err and { err, '' } or { '', 'written' }
  end)
  return asked
end

t.test('every command of a | chain is looked at: :w | q writes in Neovim, then Eclipse closes', function()
  start()
  vim.cmd('vsplit') -- if q were Neovim's, it would close a window (on the last one, end Neovim)
  local asked = eclipse_saves()
  local buf = t.document('A.txt', { 'a' })
  t.type(':w | q<CR>')
  t.eq({ buf }, asked, 'Neovim wrote, through Eclipse')
  t.eq({ { 'quit', false, {} } }, t.sent('eclipse_command'))
  t.eq({}, t.sent('eclipse_buffer_closed'), 'no :quit reached Neovim')
  t.eq(2, #vim.api.nvim_list_wins(), 'nothing was closed')
  t.eq('w | q', vim.fn.histget(':', -1), 'the history keeps the line as typed')
end)

t.test('after a failed save, :w | q still hands the quit to Eclipse, which asks about the changes', function()
  start()
  vim.cmd('vsplit')
  local asked = eclipse_saves('Eclipse could not save A.txt: read-only')
  local buf = t.document('A.txt', { 'a' })
  t.type(':w | q<CR>')
  t.eq({ buf }, asked)
  t.eq(true, vim.bo[buf].modified, 'not saved')
  -- Eclipse closes a dirty editor only after asking, as for a plain :q (HostCommandRunner).
  t.eq({ { 'quit', false, {} } }, t.sent('eclipse_command'))
  t.eq({}, t.sent('eclipse_buffer_closed'), 'the safety net did not close the editor behind the prompt')
  t.eq(2, #vim.api.nvim_list_wins())
end)

t.test('a host command anywhere in a chain goes to Eclipse; the others and refused ones stay Neovim\'s', function()
  start()
  t.type(':let g:a = 1 | echo 2<CR>') -- no host command: the line runs as typed
  t.type(':echo 1 | bd | let g:after = 1<CR>')
  t.type(':echo 1 |  tabn 3 | wa<CR>') -- spaces after | are Neovim's to skip
  t.eq({
    { 'bdelete', false, {} },
    { 'tabnext', false, { '3' } },
    { 'wall', false, {} },
  }, t.sent('eclipse_command'))
  t.eq(1, vim.g.a)
  t.eq(1, vim.g.after, 'the command after the host command ran')
  vim.cmd('vsplit')
  t.forget()
  t.type(':echo 1 | q foo<CR>') -- :q takes no argument: E488, from Neovim
  t.ok(vim.v.errmsg:find('E488', 1, true), vim.v.errmsg)
  t.eq({}, t.sent('eclipse_command'))
  t.eq(2, #vim.api.nvim_list_wins())
end)

t.test('a line Eclipse would refuse stays Neovim\'s', function()
  start()
  vim.cmd('vsplit') -- if :q foo were run, it would close a window, not end the test's Neovim
  t.type(':q foo<CR>') -- :q takes no argument: E488
  t.ok(vim.v.errmsg:find('E488', 1, true), 'Neovim reported it: ' .. vim.v.errmsg)
  t.type(':1,2tabn<CR>') -- a range where only a count fits
  t.type(':Eclipse nope<CR>')
  t.ok(vim.v.errmsg:find('E492: Not an Eclipse command: nope', 1, true), vim.v.errmsg)
  t.eq({}, t.sent('eclipse_command'))
  t.eq(2, #vim.api.nvim_list_wins(), 'nothing was closed')
end)

t.test(':bd with a range or numbers names the listed buffers it covers', function()
  start()
  local one = vim.api.nvim_get_current_buf()
  vim.cmd('badd two | badd three')
  local three = vim.fn.bufnr('three')
  t.type(':%bd<CR>')
  t.type((':%dbd<CR>'):format(three))
  t.type(':bd two<CR>')
  t.eq({
    { 'bdelete', false, { tostring(one), tostring(vim.fn.bufnr('two')), tostring(three) } },
    { 'bdelete', false, { tostring(three) } },
    { 'bdelete', false, { 'two' } },
  }, t.sent('eclipse_command'))
  t.eq(3, #vim.fn.getbufinfo({ buflisted = 1 }), 'Neovim deleted none of them: Eclipse closes the editors')
end)

t.test(':bd of a buffer number nobody has stays Neovim\'s, which says so', function()
  start()
  t.type(':99bd<CR>') -- a number after :bd is a range: no buffer in it
  t.eq({}, t.sent('eclipse_command'))
  t.ok(vim.v.errmsg:find('E16', 1, true), vim.v.errmsg) -- Invalid range
end)

t.test('a :quit Neovim runs itself (<Cmd>q) tells Eclipse through the QuitPre safety net', function()
  start()
  vim.cmd('vsplit') -- two windows, so the quit closes one instead of ending the test's Neovim
  local buf = vim.api.nvim_get_current_buf()
  vim.cmd('nnoremap <Space>q <Cmd>q<CR>')
  t.type('<Space>q')
  t.eq({}, t.sent('eclipse_command'), '<Cmd> lines never reach CmdlineLeavePre')
  t.eq({ { buf } }, t.sent('eclipse_buffer_closed'))
end)

-- Not here: the command-line window (q:) closed by SETUP's CmdwinEnter. t.type ends a pending mode
-- by itself when the keys run out (see harness.lua), so the window would close either way;
-- NeovimSessionIntegrationTest.theCommandLineWindowClosesWithAMessage checks it with keys sent one
-- at a time.

-- ---- file names: the full path Neovim's own :edit or :write would use ----

--- A window working in /tmp/.../app (as the plug-in's :lcd to the project root), editing
--- app/src/Main.java. Returns the project folder.
local function editing_main_in_a_project()
  start()
  local app = vim.fn.tempname() .. '/app'
  vim.fn.mkdir(app .. '/src', 'p')
  -- The folder's one true spelling, as Eclipse gives a project's: links resolved (macOS: /var is
  -- /private/var) and, on Windows, short 8.3 names expanded. GitHub's Windows runner has its temp
  -- folder under C:/Users/RUNNER~1 (the user is runneradmin); with that spelling in :lcd, Neovim
  -- sent `:e ./notes.txt` as .../runneradmin/... but `:e notes.txt` as .../RUNNER~1/..., the same
  -- file spelled two ways. vim.fn.resolve keeps 8.3 names; the system's realpath does not.
  app = vim.fs.normalize(assert(vim.uv.fs_realpath(app)))
  vim.cmd.lcd(vim.fn.fnameescape(app))
  vim.cmd.file(vim.fn.fnameescape(app .. '/src/Main.java'))
  return app
end

t.test(':e name, ./name and ../name are relative to the window\'s folder, as in Neovim', function()
  local app = editing_main_in_a_project()
  t.type(':e notes.txt<CR>')
  t.type(':e ./notes.txt<CR>')
  t.type(':e ../other/x.txt<CR>')
  t.eq({
    { 'edit', false, { sent_path(app .. '/notes.txt') } },
    { 'edit', false, { sent_path(app .. '/notes.txt') } }, -- ./ is the same folder, not the file's
    { 'edit', false, { sent_path(vim.fn.fnamemodify(app, ':h') .. '/other/x.txt') } },
  }, t.sent('eclipse_command'))
end)

-- Completing a cloned repository's file named x|lua=... into :e escapes the | (x\|lua=...).
-- nvim_parse_cmd took that backslash away, so the :Eclipse line ran the rest as a command of its
-- own (0.2.1 security review). Now the name reaches Eclipse whole, and nothing runs.
t.test('a | or " in a name reaches Eclipse as part of the name, and nothing after it runs', function()
  local app = editing_main_in_a_project()
  vim.fn.setreg('a', 'clean')
  t.type([[:e x\|lua=vim.fn.setreg(string.char(97),string.char(80))<CR>]])
  t.type([[:e say\"hi<CR>]])
  t.eq({
    { 'edit', false, { sent_path(app .. '/x|lua=vim.fn.setreg(string.char(97),string.char(80))') } },
    { 'edit', false, { sent_path(app .. '/say"hi') } },
  }, t.sent('eclipse_command'))
  t.eq('clean', vim.fn.getreg('a'), 'the Lua after the | did not run')
end)

--- Makes a file named `name` in the window's folder and types `:<cmd> <prefix><Tab><CR>`, so that
--- Neovim's completion writes the name with its own escapes, as when you complete a name from a
--- cloned repository. Returns the folder.
local function complete_into(cmd, name, prefix)
  local app = editing_main_in_a_project()
  local f = assert(io.open(app .. '/' .. name, 'w'))
  f:write('hi\n')
  f:close()
  vim.o.wildmenu = false
  t.type(':' .. cmd .. ' ' .. prefix .. '<Tab><CR>')
  return app
end

-- 0.2.1 security review, file by file: nvim_parse_cmd also takes away the backslash before a line
-- break, so a completed name with one ended the :Eclipse line there and ran the rest. Such a name
-- is refused now, for :e, :wq and :x alike.
for _, cmd in ipairs({ 'e', 'wq', 'x' }) do
  t.test(':' .. cmd .. ' of a completed name with a line break is refused, and nothing in it runs', function()
    complete_into(cmd, 'notes\nlua=vim.g.ran=1', 'no')
    t.eq(nil, vim.g.ran, 'the Lua after the line break did not run')
    t.eq({}, t.sent('eclipse_command'), 'nothing sent to Eclipse')
    t.ok(vim.fn.execute('messages'):find('line break is not supported', 1, true), 'the refusal is shown')
  end)
end

-- The same review: <f-args> made a completed name's doubled backslash a single one, which then
-- unescaped the backtick after it, and :Eclipse's second expandcmd() ran the rest in a shell, and
-- even put its output into the path Eclipse wrote. The name is expanded once now, from the parsed
-- line. (Backslashes in names are a Linux and macOS thing: on Windows the name is not legal.)
t.test('a completed name with a backslash and backticks reaches Eclipse literally; no shell runs', function()
  if vim.fn.has('win32') == 1 then
    return
  end
  local name = 'notes\\`touch MARK\\`.txt'
  local app = complete_into('x', name, 'no')
  t.eq({ { 'xit', false, { sent_path(app .. '/' .. name) } } }, t.sent('eclipse_command'))
  t.eq(0, vim.fn.filereadable(app .. '/MARK'), 'the shell command did not run')
end)

for _, name in ipairs({ 'x$HOME', 'a%b', 'my file.txt' }) do
  t.test('a completed name ' .. name .. ' keeps it: nothing is expanded a second time', function()
    local app = complete_into('e', name, name:sub(1, 1))
    t.eq({ { 'edit', false, { sent_path(app .. '/' .. name) } } }, t.sent('eclipse_command'))
  end)
end

t.test(':e a b is the one file "a b", as Neovim\'s :edit reads it', function()
  local app = editing_main_in_a_project()
  t.type(':e a b<CR>')
  t.eq({ { 'edit', false, { sent_path(app .. '/a b') } } }, t.sent('eclipse_command'))
end)

t.test(':e +cmd name sends the file alone, and the +cmd runs once Eclipse shows it', function()
  start()
  local _, a_name = t.document('a.txt', { 'one', 'two', 'three', 'four' })
  t.document('b.txt', { 'x' })
  t.forget()
  t.type(':e +3 ' .. vim.fn.fnameescape(a_name) .. '<CR>')
  t.eq({ { 'edit', false, { sent_path(a_name) } } }, t.sent('eclipse_command'), 'no +3 for Eclipse')
  -- Eclipse opens a.txt: its attach push enters the buffer, then places the cursor.
  vim.cmd.buffer(vim.fn.bufnr(a_name))
  t.eq(true, t.run('GO_TO_LAST_POSITION', 'false'), 'the +cmd ran')
  t.eq(3, vim.fn.line('.'))
  t.eq(false, t.run('GO_TO_LAST_POSITION', 'false'), 'only once')

  t.forget()
  t.type(':e ++ff=unix + ' .. vim.fn.fnameescape(a_name) .. '<CR>') -- + alone: the last line
  t.eq({ 'edit' }, vim.tbl_map(function(c) return c[1] end, t.sent('eclipse_command')))
  t.eq(4, vim.fn.line('.'), 'the current file: run at once')
end)

t.test('%, ~ and $HOME are expanded as Neovim\'s :edit expands them; # without one is E194', function()
  local app = editing_main_in_a_project()
  t.type(':e %:h/Util.java<CR>')
  t.type(':e ~/a.txt<CR>')
  t.type(':e #<CR>')
  t.eq({
    { 'edit', false, { sent_path(app .. '/src/Util.java') } }, -- beside Main.java
    { 'edit', false, { sent_path(vim.fn.expand('~') .. '/a.txt') } },
  }, t.sent('eclipse_command'), 'nothing is sent for #')
  t.ok(vim.fn.execute('messages'):find('E194', 1, true), 'the error is shown')
end)

t.test(':wq name and :x name get the same full path as :w name; ++p stays', function()
  local app = editing_main_in_a_project()
  t.type(':wq copy.txt<CR>')
  t.type(':x ++p out/copy.txt<CR>')
  t.eq({
    { 'wq', false, { sent_path(app .. '/copy.txt') } },
    { 'xit', false, { '++p', sent_path(app .. '/out/copy.txt') } },
  }, t.sent('eclipse_command'))
end)

--- The names of the commands sent to Eclipse, with a ! for a bang: { 'edit', 'edit!' }.
local function sent_names()
  return vim.tbl_map(function(c)
    return c[1] .. (c[2] and '!' or '')
  end, t.sent('eclipse_command'))
end

t.test(':e of a file another editor has open is Neovim\'s :edit, so :e # toggles as in one window', function()
  start()
  local a, a_name = t.document('a.txt')
  local b = t.document('b.txt')
  t.forget()

  t.type(':e ' .. vim.fn.fnameescape(a_name) .. '<CR>')
  t.eq({}, t.sent('eclipse_command'), 'not sent to Eclipse')
  t.eq(a, vim.api.nvim_get_current_buf(), 'Neovim entered a.txt (Eclipse follows into its editor)')
  t.type(':e #<CR>')
  t.eq(b, vim.api.nvim_get_current_buf())
  t.type(':e #<CR>')
  t.eq(a, vim.api.nvim_get_current_buf(), ':e # toggles')
  t.type('<C-o>')
  t.eq(b, vim.api.nvim_get_current_buf(), 'each :e is a jump, as in Vim')
  t.eq({}, t.sent('eclipse_command'))
end)

t.test(':e of an open file in other case (where names ignore case) or through a link is that file', function()
  start()
  vim.o.fileignorecase = true -- as on Windows and macOS (PATHS reads it)
  local a, a_name = t.document('a.txt')
  t.document('b.txt')
  t.forget()
  t.type(':e ' .. vim.fn.fnameescape(vim.fs.dirname(a_name) .. '/A.TXT') .. '<CR>')
  t.eq({}, t.sent('eclipse_command'), 'other case: not sent to Eclipse')
  t.eq(a, vim.api.nvim_get_current_buf())
  vim.o.fileignorecase = false
  local link = vim.fn.tempname()
  if vim.uv.fs_symlink(vim.fs.dirname(a_name), link, { dir = true, junction = true }) then
    t.type(':e #<CR>')
    t.forget()
    t.type(':e ' .. vim.fn.fnameescape(link .. '/a.txt') .. '<CR>')
    t.eq({}, t.sent('eclipse_command'), 'through a link (a junction on Windows): not sent to Eclipse')
    t.eq(a, vim.api.nvim_get_current_buf())
  end
end)

t.test('Eclipse still runs :e of a file without an editor, :e!, :e +cmd, :e ++opt and :e %', function()
  start()
  local _, a_name = t.document('a.txt')
  t.document('b.txt')
  local name = vim.fn.fnameescape(a_name)
  t.forget()

  t.type(':e ' .. vim.fn.fnameescape(vim.fn.tempname() .. '-new.txt') .. '<CR>') -- no editor: only Eclipse makes one
  t.type(':e! ' .. name .. '<CR>')
  t.type(':e +2 ' .. name .. '<CR>')
  t.type(':e ++ff=unix ' .. name .. '<CR>')
  t.type(':e %<CR>') -- the current file: Neovim's :edit would read it again
  t.eq({ 'edit', 'edit!', 'edit', 'edit', 'edit' }, sent_names())
end)

t.test(':e of a document whose buffer Neovim has not loaded goes to Eclipse', function()
  start()
  local a, a_name = t.document('a.txt')
  t.document('b.txt')
  vim.api.nvim_buf_call(a, function()
    vim.cmd('silent! bunload!')
  end)
  t.ok(not vim.api.nvim_buf_is_loaded(a), 'a.txt unloaded')
  t.forget()
  t.type(':e ' .. vim.fn.fnameescape(a_name) .. '<CR>')
  t.eq({ 'edit' }, sent_names())
end)

t.test(':e alone and :e! are left as they are', function()
  editing_main_in_a_project()
  t.type(':e<CR>')
  t.type(':e!<CR>')
  t.eq({ { 'edit', false, {} }, { 'edit', true, {} } }, t.sent('eclipse_command'))
end)
