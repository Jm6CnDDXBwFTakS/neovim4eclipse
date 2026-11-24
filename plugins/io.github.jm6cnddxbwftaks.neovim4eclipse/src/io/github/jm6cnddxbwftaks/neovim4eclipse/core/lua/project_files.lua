-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- Module `neovim4eclipse.project_files`: Neovim never reads a file on disk by itself, a project's
-- or not, since Eclipse opens an editor for either (docs/design/BUFFER_NUMBERS.md, 2.3). Help pages
-- and a plug-in's buffers with a scheme (`fugitive://`) are Neovim's.
--
-- Why. When Neovim reads a file (`gf`, `'A`, `:b` into a closed file, a quickfix entry), it runs
-- filetype detection, the filetype plug-ins (`indentexpr`, `commentstring`) and the user's
-- `FileType` autocommands, which may start a language server inside Eclipse's Neovim (Eclipse is
-- that server already: jdtls is Eclipse's JDT). The buffers the plug-in fills from Eclipse's text
-- never do. So such a file is read by a `BufReadCmd` of the plug-in's instead, which reads only the
-- text; Eclipse then opens the file's editor (`EditorAttachManager`) and the attach takes the
-- buffer over, as for a document's kept buffer.
--
-- Which buffers. Each new buffer (`BufNew`, also sent after a rename) named after a file
-- (`eclipse_reads`: an absolute path, no scheme, no help file) gets a buffer-local `BufReadCmd`,
-- which stays with the buffer while it is unloaded. A help file outside the projects is told by its
-- place (`is_help`: a `doc` folder with a tags file), since `:help` sets `'buftype'` only after
-- `BufNew`; inside a project the same file is the project's (a Vim plug-in's repository), by the
-- folders Eclipse sends (LuaScripts.SET_PROJECT_FOLDERS; `inside(path)`). A buffer added to the
-- list is checked too (`BufAdd`), unless `BufNew` checked it under the same name: `:vimgrep` makes
-- the buffers of the files it matched while it searches, without `BufNew`, and its jump into one
-- fires only `BufAdd` before the read (found 2026-10-30, on macOS: `:vimgrep` into closed files
-- opened no editor; any system, for files without a buffer yet). Its first match is loaded by the
-- search itself, so the hook cannot read it.
--
-- `:vimgrep`'s search, without swap files. To search a file, `:vimgrep` loads it into a buffer of
-- its own, made with the global `'swapfile'` and given a swap file before any autocommand runs.
-- Where the swap file cannot be made (E303: on Windows, the UI tests' long paths), Neovim stops at
-- a prompt for every file it searches (mode `r?`) and the search goes on only after a key: inside
-- Eclipse it looked stuck, the list empty (found 2026-10-30, Windows). So `QuickFixCmdPre` turns
-- the global `'swapfile'` off for `:vimgrep`, `:lvimgrep` and their `add` forms, and
-- `QuickFixCmdPost` turns it back (a scheduled callback too, in case the command ends early). The
-- searched buffers are thrown away, except the first match, which then has no swap file either:
-- right for a project file (as the hook's read does it), and for a file outside the projects the
-- same as `:noswapfile`. The paths are compared by LuaScripts.PATHS's rule (resolved: symbolic
-- links, Windows junctions and 8.3 short names, macOS's NFD names; `/` separators; lower case where
-- `'fileignorecase'` is on, Windows and macOS). Autocommand patterns were tried first and cannot
-- match a junction, an 8.3 name or a folder named `$HOME` on Windows (BUFFER_NUMBERS.md, X18d).
-- Example: `gf` on `src/B.java` in `/ws/app/A.java`, and on `/etc/hosts`, is read by the hook;
-- `:help` reads `$VIMRUNTIME/doc/help.txt` as Neovim does.
--
-- The read. `'swapfile'` off first: with a swap file left by an earlier Neovim, the read would stop
-- at the E325 question, which nothing can answer under `noautocmd` (X18c). Nothing for a file that
-- does not exist. Then `:read ++edit` (Neovim's own decoding of encodings, line endings and a BOM)
-- without autocommands, the empty first line removed, under `lockmarks` (the marks stay on their
-- lines) and with `'undolevels'` -1 (no undo step). A closed document's buffer then has its cursor
-- put on `'"`, where Vim's `:b` puts it; a mark jump or a quickfix entry moves it on afterwards. A
-- buffer that another `BufReadCmd` filled first (Neovim's `zip.vim` lists an archive's files,
-- read-only) is left to it: reading the raw bytes on top of it failed with E21.
--
-- A document's buffer (`'buftype'` `acwrite`, an editor shows it) is left alone:
-- LuaScripts.DOCUMENT_BUFFER_MODULE gives every document's buffer, inside a project or not, a
-- `BufReadCmd` of its own, which asks Eclipse for the text instead.
--
-- Functions: `set_folders(list)`; `file_of(buf)`, the buffer's file name when the hook is on it and
-- the buffer is a plain one (`'buftype'` empty), else `""` (sent with `eclipse_buf_enter`,
-- LuaScripts.SETUP); `eclipse_reads(name)`; `is_help(name)`; `inside(path)`.
--
-- Sent as LuaScripts.PROJECT_FILES (core/LuaModules runs this file as a chunk of its own).

local M = {}
local group = vim.api.nvim_create_augroup('neovim4eclipse_projects', { clear = true })
local paths = package.loaded['neovim4eclipse.paths'] -- the one rule for comparing paths (PATHS)
local roots = {} -- the folders, as paths.canonical() gives them
local hooked = {} -- buffer number -> true, for the buffers that have the read hook
local checked = {} -- buffer number -> the name check() last looked at

function M.inside(path)
  if path == '' or #roots == 0 then
    return false
  end
  local c = paths.canonical(path)
  for _, root in ipairs(roots) do
    if paths.within(c, root) then
      return true
    end
  end
  return false
end

local function read(ev)
  local buf = ev.buf
  vim.bo[buf].swapfile = false -- first: a stale swap file would stop the read at E325
  -- A document's buffer: its own hook asks Eclipse for the text (DOCUMENT_BUFFER_MODULE). Known
  -- by its hooks (the module's is_document) as well as by 'acwrite': during :edit! the buffer is
  -- read with 'buftype' '' (its BufUnload set it, to keep the marks), and this hook runs first.
  local documents = package.loaded['neovim4eclipse.document_buffer']
  if vim.bo[buf].buftype == 'acwrite' or (documents ~= nil and documents.is_document(buf)) then
    return
  end
  local name = ev.match
  if vim.fn.filereadable(name) == 0 then
    return
  end
  -- Another reader came first (Neovim's zip.vim or tar.vim browse an archive with a
  -- BufReadCmd of their own, defined before this one): the buffer is theirs.
  local first = vim.api.nvim_buf_get_lines(buf, 0, 1, false)[1]
  if not vim.bo[buf].modifiable or vim.api.nvim_buf_line_count(buf) > 1 or (first and first ~= '') then
    return
  end
  -- A line break in the name would split the :read below into two commands: fnameescape()
  -- puts a backslash before it but leaves it in, and vim.cmd() runs each line on its own,
  -- so a cloned repository's file named "x<newline>lua=os.execute(...)" ran that Lua when
  -- Neovim loaded it. Such a file is not read.
  if name:find('[\r\n]') then
    vim.api.nvim_echo({ { 'Neovim for Eclipse does not read a file whose name has a line break: '
      .. vim.inspect(name), 'ErrorMsg' } }, true, {})
    return
  end
  -- No modeline of the file applies (NO_MODELINES says why). Said for this buffer
  -- too, should the user have turned 'modeline' on again: this read is the plug-in's.
  vim.bo[buf].modeline = false
  local saved = vim.bo[buf].undolevels
  vim.bo[buf].undolevels = -1
  local ok, err = pcall(vim.cmd, 'lockmarks keepalt noautocmd silent read ++edit ' .. vim.fn.fnameescape(name))
  if ok then
    vim.cmd('lockmarks keepjumps noautocmd silent 1delete _')
  end
  vim.bo[buf].undolevels = saved
  vim.bo[buf].modified = false
  if not ok then
    vim.api.nvim_echo({ { tostring(err), 'ErrorMsg' } }, true, {})
    return
  end
  if vim.b[buf].neovim4eclipse_file and vim.api.nvim_get_current_buf() == buf then
    local mark = vim.api.nvim_buf_get_mark(buf, '"')
    if mark[1] >= 1 and mark[1] <= vim.api.nvim_buf_line_count(buf) then
      pcall(vim.api.nvim_win_set_cursor, 0, mark)
    end
  end
end

local function hook(buf)
  if not hooked[buf] then
    hooked[buf] = true
    vim.api.nvim_create_autocmd('BufReadCmd', { group = group, buffer = buf, callback = read })
  end
end

local function unhook(buf)
  if hooked[buf] then
    hooked[buf] = nil
    pcall(vim.api.nvim_clear_autocmds, { group = group, buffer = buf, event = 'BufReadCmd' })
  end
end

-- Whether Eclipse reads name's file for Neovim: any file on disk, a project's or not
-- (/ws/app/B.java, /etc/hosts, ~/.config/nvim/init.lua), since Eclipse opens an editor
-- for either. A buffer's name for a file is its absolute path (/x, C:\x, C:/x,
-- \\server\share). Not a name with a scheme (zipfile://, fugitive://, term://, oil://):
-- those are a plug-in's buffers, which it fills itself.
function M.eclipse_reads(name)
  if name == '' or name:find('^%a[%w+.-]*://') then
    return false
  end
  -- A project's file is Eclipse's even where it looks like help: a Vim plug-in's own
  -- repository has doc/*.txt beside doc/tags, and its author edits them.
  if M.inside(name) then
    return true
  end
  if M.is_help(name) then
    return false
  end
  return name:find('^/') ~= nil or name:find('^%a:[/\\]') ~= nil
    or name:find('^[/\\][/\\]') ~= nil
end

-- Whether name is a help file, as Neovim finds them: a .txt in a doc folder that has a
-- tags file (doc/help.txt beside doc/tags), or a translated one (doc/usr_01.jax beside
-- doc/tags-ja). Neovim reads those itself: :help readies the buffer for them (concealed
-- tags, 'filetype' help), and 'buftype' help is set only after BufNew, too late for a
-- check by kind.
function M.is_help(name)
  local dir = vim.fn.fnamemodify(name, ':h')
  if vim.fn.fnamemodify(dir, ':t') ~= 'doc' then
    return false
  end
  local ext = vim.fn.fnamemodify(name, ':e')
  if ext == 'txt' then
    return vim.fn.filereadable(dir .. '/tags') == 1
  end
  return ext:find('^%a%ax$') ~= nil and vim.fn.filereadable(dir .. '/tags-' .. ext:sub(1, 2)) == 1
end

local function check(buf)
  local name = vim.api.nvim_buf_get_name(buf)
  checked[buf] = name
  if M.eclipse_reads(name) then
    hook(buf)
  else
    unhook(buf)
  end
end

function M.set_folders(folders)
  roots = {}
  for _, folder in ipairs(folders) do
    roots[#roots + 1] = paths.canonical(folder)
  end
  for _, buf in ipairs(vim.api.nvim_list_bufs()) do
    check(buf)
  end
end

function M.file_of(buf)
  if not hooked[buf] or vim.bo[buf].buftype ~= '' then
    return ''
  end
  return vim.api.nvim_buf_get_name(buf)
end

vim.api.nvim_create_autocmd('BufNew', { group = group, callback = function(ev)
  check(ev.buf)
end })
-- BufAdd too: :vimgrep makes the buffers of the files it matched while it searches, with
-- no BufNew (Neovim 0.12.6), and the jump to one fires only BufAdd before reading it.
-- Most buffers had BufNew just before: those are not resolved again (fs_realpath).
vim.api.nvim_create_autocmd('BufAdd', { group = group, callback = function(ev)
  local buf = ev.buf
  if checked[buf] ~= vim.api.nvim_buf_get_name(buf) then
    check(buf)
  end
end })

-- :vimgrep's search without swap files (see the Java comment): its buffers take the
-- global 'swapfile' and get the file before any autocommand runs, and where it cannot
-- be made (E303) Neovim stops at a prompt for each file searched.
local vimgreps = { 'vimgrep', 'vimgrepadd', 'lvimgrep', 'lvimgrepadd' }
local swapfile_before = nil -- the global 'swapfile' while a :vimgrep runs, else nil
local aside = nil -- a scratch buffer to set the global value from
-- Setting the global value acts on the current buffer too: turned on, it makes that
-- buffer's swap file at once (an empty unnamed buffer got one named after the folder).
-- Set from a scratch buffer, which never has one, nothing else changes.
local function set_global_swapfile(value)
  if not (aside and vim.api.nvim_buf_is_valid(aside)) then
    aside = vim.api.nvim_create_buf(false, true)
  end
  vim.api.nvim_buf_call(aside, function()
    vim.go.swapfile = value
  end)
end
local function restore_swapfile()
  if swapfile_before ~= nil then
    set_global_swapfile(swapfile_before)
    swapfile_before = nil
  end
end
vim.api.nvim_create_autocmd('QuickFixCmdPre', { group = group, pattern = vimgreps, callback = function()
  if swapfile_before == nil then
    swapfile_before = vim.go.swapfile
  end
  set_global_swapfile(false)
  vim.schedule(restore_swapfile) -- also when the command ends without QuickFixCmdPost
end })
vim.api.nvim_create_autocmd('QuickFixCmdPost', { group = group, pattern = vimgreps, callback = restore_swapfile })
vim.api.nvim_create_autocmd('BufWipeout', { group = group, callback = function(ev)
  hooked[ev.buf] = nil
  checked[ev.buf] = nil
end })
package.loaded['neovim4eclipse.project_files'] = M
