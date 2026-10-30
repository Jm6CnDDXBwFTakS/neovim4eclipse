-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- LuaScripts.PATHS (module neovim4eclipse.paths): the one rule for comparing file paths. Every case
-- of the shared table tests/unit/src/test/resources/path-cases.tsv, which Java's util/FilePaths
-- passes too (FilePathsTest), on the Linux, macOS and Windows rules, with that table's fake links;
-- so this runs the Windows and macOS rules on any machine. Then this machine's own rule on real files.

local t = ...

local LINKS = {
  linux = { ['/lnk'] = '/ws' },
  macos = { ['/var'] = '/private/var' },
  windows = { ['C:/link'] = 'C:/ws', ['C:/PROGRA~1'] = 'C:/Program Files' },
}

--- The table's fake realpath for `platform`: the longest matching link replaced; nothing for a
--- path whose last name starts with "new" (it does not exist).
local function fake_realpath(platform)
  return function(path)
    if vim.fs.basename(path):sub(1, 3) == 'new' then
      return nil
    end
    local best
    for from, to in pairs(LINKS[platform]) do
      if (path == from or path:sub(1, #from + 1) == from .. '/') and (not best or #from > #best[1]) then
        best = { from, to }
      end
    end
    return best and (best[2] .. path:sub(#best[1] + 1)) or path
  end
end

local function rules(platform)
  return require('neovim4eclipse.paths').rules({
    win32 = platform == 'windows',
    ignorecase = platform ~= 'linux',
    realpath = fake_realpath(platform),
  })
end

local function cases()
  local list = {}
  for line in io.lines(vim.fs.joinpath(t.spec_dir, '..', 'resources', 'path-cases.tsv')) do
    if line ~= '' and line:sub(1, 1) ~= '#' then
      local op, platform, a, b, expected, why = unpack(vim.split(line, '\t', { plain = true }))
      list[#list + 1] = { op = op, platform = platform, a = a, b = b, yes = expected == 'yes', why = why }
    end
  end
  return list
end

t.test('every case of the shared table, on its platform\'s rule', function()
  local list = cases()
  t.ok(#list >= 20, 'the table was read: ' .. #list .. ' cases')
  for _, c in ipairs(list) do
    local rule = rules(c.platform)
    local got = c.op == 'same' and rule.same(c.a, c.b) or rule.inside(c.a, c.b)
    t.eq(c.yes, got, ('%s %s: %s / %s (%s); canonical %s and %s'):format(c.op, c.platform, c.a, c.b, c.why,
      rule.canonical(c.a), rule.canonical(c.b)))
  end
end)

t.test('this machine\'s rule on real files: a link, a relative name, and a file not there yet', function()
  local paths = require('neovim4eclipse.paths')
  local dir = vim.fn.tempname()
  vim.fn.mkdir(dir .. '/real', 'p')
  vim.fn.writefile({ 'x' }, dir .. '/real/a.txt')
  local linked = vim.uv.fs_symlink(dir .. '/real', dir .. '/link', { dir = true, junction = true })
  if linked then
    t.ok(paths.same(dir .. '/link/a.txt', dir .. '/real/a.txt'), 'through a link (a junction on Windows)')
    t.ok(paths.inside(dir .. '/link/a.txt', dir .. '/real'))
  end
  t.ok(paths.same(dir .. '/real/new.txt', dir .. '/real/../real/new.txt'), 'not there yet: its folder resolved')
  local cwd = vim.fn.getcwd()
  vim.cmd.cd(vim.fn.fnameescape(dir .. '/real'))
  t.ok(paths.same('a.txt', dir .. '/real/a.txt'), 'a relative name, against the current folder')
  vim.cmd.cd(vim.fn.fnameescape(cwd))
  t.eq('', paths.canonical(''))
  t.same_file(dir .. '/real/a.txt', dir .. '/real/./a.txt')
end)
