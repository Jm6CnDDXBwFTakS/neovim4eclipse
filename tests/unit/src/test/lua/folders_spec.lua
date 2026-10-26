-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- LuaScripts.SET_WINDOW_FOLDER (:lcd to the attached editor's project) and LEAVE_FOLDER (Neovim
-- leaves a project's folder before Eclipse deletes or closes it; Windows refuses to delete a folder
-- a process works in). Paths are real paths: macOS's /var is a link to /private/var, and getcwd()
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

t.test('the window\'s folder is set with :lcd, silently', function()
  local dir = folder('project')
  t.run('SET_WINDOW_FOLDER', dir)
  t.eq(dir, cwd(0))
  t.eq(1, vim.fn.haslocaldir(0), 'window-local')
  t.ok(not vim.fn.execute('messages'):find(dir, 1, true), 'no message replaces the one the user reads')
end)

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
  t.eq(root, cwd(-1, -1), 'the global folder')
  t.eq(root, cwd(-1, 2), 'tab page 2\'s folder')
  t.eq(root, cwd(), 'the current window\'s folder')
  t.eq(other, cwd(1, 1), 'a window elsewhere keeps its folder')
  t.eq(1, vim.fn.haslocaldir(1, 1), 'still its own (:lcd)')
  t.eq(root, vim.fs.normalize(vim.uv.cwd()), 'the process\'s folder, the one Windows checks')
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
  t.eq(root, vim.fs.normalize(vim.uv.cwd()))
end)

t.test('on Windows the folder is matched ignoring case and slashes', function()
  if vim.fn.has('win32') == 0 then
    return
  end
  local project, root = folder('App')
  vim.cmd.cd(project)
  t.eq(1, t.run('LEAVE_FOLDER', (root .. '/app'):upper():gsub('/', '\\')))
end)
