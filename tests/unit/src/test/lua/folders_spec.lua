-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- LuaScripts.LEAVE_FOLDER (Neovim leaves a project's folder before Eclipse deletes or closes it;
-- Windows refuses to delete a folder a process works in). Setting the folder is OPEN_WINDOW's, in
-- windows_spec.lua. Paths are real paths: macOS's /var is a link to /private/var, and getcwd()
-- resolves it.

local t = ...

local function folder(...)
  local dir = vim.fn.resolve(vim.fn.tempname())
  local path = vim.fs.joinpath(dir, ...)
  vim.fn.mkdir(path, 'p')
  return vim.fs.normalize(vim.uv.fs_realpath(path)), vim.fs.normalize(vim.uv.fs_realpath(dir))
end

local function cwd(...)
  return vim.fs.normalize(vim.fn.getcwd(...))
end

t.test('leaving a folder moves every working folder inside it to its parent, and only those', function()
  local src, root = folder('app', 'src')
  local other = folder('other')
  vim.cmd.cd(src)
  vim.cmd.vsplit()
  vim.cmd.lcd(other)
  vim.cmd.wincmd('w')
  vim.cmd.lcd(src)
  vim.cmd.tabnew()
  vim.cmd.tcd(src)
  vim.cmd.tabprevious()

  t.eq(3, t.run('LEAVE_FOLDER', root .. '/app'), 'global, tab page 2, window 2')
  t.same_file(root, cwd(-1, -1), 'the global folder')
  t.same_file(root, cwd(-1, 2), 'tab page 2\'s folder')
  t.same_file(root, cwd(), 'the current window\'s folder')
  t.same_file(other, cwd(1, 1), 'a window elsewhere keeps its folder')
  t.eq(1, vim.fn.haslocaldir(1, 1), 'still its own (:lcd)')
  t.same_file(root, vim.uv.cwd(), 'the process\'s folder, the one Windows checks')
  t.eq(0, t.run('LEAVE_FOLDER', root .. '/app'), 'nothing left inside')
end)

t.test('a window in a folder that is gone does not stop the leaving', function()
  local project, root = folder('app')
  local gone = vim.fs.joinpath(root, 'gone')
  vim.fn.mkdir(gone, 'p')
  vim.cmd.vsplit()
  vim.cmd.lcd(gone)
  vim.cmd.wincmd('w')
  vim.cmd.cd(project)
  vim.fn.delete(gone, 'd')

  t.eq(1, t.run('LEAVE_FOLDER', project), 'the global folder')
  t.same_file(root, vim.uv.cwd())
end)

-- The parent a window moves to is set with chdir(): :lcd would expand `...` in its name as a shell
-- command (0.2.1 security review).
t.test('a parent folder named with backticks is moved to as it is, nothing runs', function()
  if vim.fn.has('win32') == 1 then
    t.skip('the name holds the marker\'s full path, and a Windows one (C:\\...) cannot be part of a name')
  end
  local _, root = folder('x')
  local marker = vim.fs.joinpath(root, 'MARKER')
  local parent = vim.fs.joinpath(root, 'evil`touch ' .. marker .. '`')
  local project = vim.fs.joinpath(parent, 'app')
  vim.fn.mkdir(project, 'p')
  vim.cmd.vsplit()
  vim.fn.chdir(project, 'window')
  t.eq(1, t.run('LEAVE_FOLDER', project), 'the window inside')
  t.same_file(parent, cwd(), 'it works in the parent now')
  t.eq(0, vim.fn.filereadable(marker), 'the backtick command did not run')
end)

t.test('on Windows the folder is matched ignoring case and slashes', function()
  if vim.fn.has('win32') == 0 then
    t.skip('only Windows ignores case and slashes in paths')
  end
  local project, root = folder('App')
  vim.cmd.cd(project)
  t.eq(1, t.run('LEAVE_FOLDER', (root .. '/app'):upper():gsub('/', '\\')))
end)

-- On Linux and macOS the system reports the working folder with links resolved (getcwd), so this
-- passes without the plug-in resolving anything; on Windows a junction stays in the folder's name,
-- and PATHS's rule resolves it. A guard here, the real case on Windows.
t.test('a working folder reached through a link (a junction on Windows) is inside the folder left', function()
  local _, root = folder('app', 'src')
  local link = root .. '/link'
  if not vim.uv.fs_symlink(root .. '/app', link, { dir = true, junction = true }) then
    return -- no right to make one here (a Windows runner)
  end
  vim.cmd.cd(vim.fn.fnameescape(link .. '/src'))
  t.eq(1, t.run('LEAVE_FOLDER', root .. '/app'), 'moved out, though its name does not start with the folder')
end)
