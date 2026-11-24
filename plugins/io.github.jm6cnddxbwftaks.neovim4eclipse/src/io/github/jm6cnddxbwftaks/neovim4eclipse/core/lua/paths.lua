-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- Module `neovim4eclipse.paths`: the one rule for comparing file paths in Neovim, used by every
-- script that compares them (LuaScripts.PROJECT_FILES, LuaScripts.HOST_COMMANDS,
-- LuaScripts.DOCUMENT_BUFFER, LuaScripts.LEAVE_FOLDER) and by the Lua tests (`t.same_file`). Java's
-- `util/FilePaths` follows the same rule; both are checked against one table of cases
-- (`tests/unit/src/test/resources/path-cases.tsv`). Run first, before any other script.
--
-- Why one rule. The same file reaches Neovim in many spellings: Windows writes `\` or `/` and any
-- case (`C:\WS\a.txt`, `c:/ws/A.TXT`); macOS ignores case and links `/var` to `/private/var`; a
-- Windows junction or 8.3 short name (`C:\PROGRA~1`) points elsewhere; `..` and doubled slashes.
-- Neovim stores a buffer's name with links resolved, but a written name (`:w`) as typed. Each
-- script once had its own comparison, and each platform's fix landed in one of them only.
--
-- The rule (`canonical(path)`): absolute (`fnamemodify(':p')` for a relative name), then resolved
-- (`fs_realpath`: symbolic links, junctions, 8.3 names, macOS's NFD names; for a file that does not
-- exist yet, its nearest existing folder resolved plus the rest), with `/` separators and `.` and
-- `..` resolved (`vim.fs.normalize`, environment variables not expanded: a folder may be named
-- `$HOME`), and lower-cased where `'fileignorecase'` is on (Windows, macOS). Two paths name one
-- file when their canonical forms are equal (`same(a, b)`); `inside(path, folder)` when the path's
-- is the folder's or starts with it and a `/`. Example on Windows: `C:\link\App\a.txt` and
-- `c:/ws/app/A.TXT`, with `C:\link` a junction to `C:\ws`, are both `c:/ws/app/a.txt`.
--
-- For tests, any platform anywhere. `rules(platform)` gives the same three functions for a platform
-- given as `{ win32 = bool, ignorecase = bool, realpath = fn }`, so the Windows and macOS rules are
-- tested on Linux with a fake `realpath`; the module's own functions use this machine's
-- (`current()`).
--
-- Sent as LuaScripts.PATHS (core/LuaModules runs this file as a chunk of its own).

local M = {}

--- Whether `path` is absolute under the platform's rule (C:\ or \\server on Windows).
local function absolute(path, win32)
  if win32 then
    return path:match('^%a:[/\\]') ~= nil or path:match('^[/\\][/\\]') ~= nil
  end
  return path:sub(1, 1) == '/'
end

function M.current()
  return { win32 = vim.fn.has('win32') == 1, ignorecase = vim.o.fileignorecase, realpath = vim.uv.fs_realpath }
end

function M.rules(platform)
  local R = {}
  local opts = { win = platform.win32, expand_env = false }
  function R.canonical(path)
    if path == nil or path == '' then
      return ''
    end
    if not absolute(path, platform.win32) then
      path = vim.fn.fnamemodify(path, ':p')
    end
    local full = vim.fs.normalize(path, opts)
    -- Not there (yet): its nearest existing folder resolved, plus the rest. The nearest,
    -- not just the parent: on macOS /home is a link, and with only the parent tried
    -- /home/user (parent /home exists) and /home/user/notes.txt (parent /home/user does
    -- not) would resolve differently. Java's FilePaths.linksResolved walks the same way.
    local folder, rest = full, nil
    local real = platform.realpath(folder)
    while not real do
      local up = vim.fs.dirname(folder)
      if up == folder or up == '.' then
        break -- not even the root resolves
      end
      local name = vim.fs.basename(folder)
      rest = rest and (name .. '/' .. rest) or name
      folder = up
      real = platform.realpath(folder)
    end
    real = real and (rest and (real .. '/' .. rest) or real) or full
    real = vim.fs.normalize(real, opts)
    return platform.ignorecase and real:lower() or real
  end
  function R.same(a, b)
    return R.canonical(a) == R.canonical(b)
  end
  --- Whether canonical form `c` is the canonical folder `f` or inside it.
  function R.within(c, f)
    local prefix = f:sub(-1) == '/' and f or f .. '/' -- the root: / or C:/
    return c == f or c:sub(1, #prefix) == prefix
  end
  function R.inside(path, folder)
    return R.within(R.canonical(path), R.canonical(folder))
  end
  return R
end

-- This machine's rule, read as each call is made ('fileignorecase' may change).
for _, name in ipairs({ 'canonical', 'same', 'inside', 'within' }) do
  M[name] = function(...)
    local rule = M.rules(M.current())
    return rule[name](...)
  end
end
package.loaded['neovim4eclipse.paths'] = M
