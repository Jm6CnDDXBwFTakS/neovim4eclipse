-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- Module `neovim4eclipse.quickfix`: the quickfix lists of the workspace's problems and tasks
-- (docs/design/DIAGNOSTICS.md, section 5). Run once at start, after LuaScripts.WINDOWS, whose
-- safety net calls `window()` below.
--
-- The commands. `:Eproblems [error|warning|info]` and `:Etasks`, each with `!`, only ask Eclipse
-- (`eclipse_quickfix` with the kind, the argument and the bang): the markers live there. `:Etasks`
-- sends the tags of `vim.g.neovim4eclipse_task_tags` (a string `'JM6,FIXME'`, or a list), read as
-- it runs, so `:let` changes them at once; without tags it says how to set them and asks nothing. A
-- severity other than the three is refused with Vim's `E475`. Eclipse answers with
-- LuaScripts.SET_QUICKFIX.
--
-- Which list is ours. The list's `context` (`:help quickfix-context`) holds `{ neovim4eclipse =
-- 'problems', arg = 'warning' }`: the kind, and what to read again for an update. The title
-- (`Eclipse problems`) is only shown, never matched, so a list of the user's with the same title is
-- never touched.
--
-- `set(kind, arg, mode, jump, entries...)`, seven strings per entry
-- (`util/QuickfixEntries.toLuaArgs`). An entry names its document's buffer when Eclipse sends a
-- buffer number that exists, else the file, for which Neovim makes an unlisted buffer
-- (`PROJECT_FILES` gives it the read hook). Mode `command`: the newest list of this kind in the
-- stack is updated and made current (`:chistory`), or a new one is made, as
-- `vim.diagnostic.setqflist()` does (DIAGNOSTICS.md, 5.2); then `:cfirst` unless `jump` is `0`
-- (`:Eproblems!`, which shows the count instead). Mode `update`: only when the current list is this
-- kind's with the same argument; any other current list (`:vimgrep`'s) is left alone. Both update
-- with action `'u'`, which keeps the entry the user is on (Neovim matches it by its content).
--
-- The column, once known. A closed file's entries have the line only (column 0: Vim puts the cursor
-- on the first non-blank). When its editor opens, Eclipse sends the list again with columns from
-- the editor (DIAGNOSTICS.md, 5.3). If the current entry had no column before and has one now, is
-- in the current buffer, and the cursor is still where the line-only jump put it (that line, its
-- first non-blank), the cursor moves on to the column: the jump that opened the file ends where the
-- problem is. A cursor the user moved meanwhile is left alone, and so is one in insert or visual
-- mode (only normal mode moves).
--
-- An entry with neither a buffer nor a file (a marker on a folder or project) is text only:
-- `:clist` shows it, `:cnext` steps over it. With only such entries a command does not jump (Vim's
-- `:cfirst` would say `E42: No Errors`) and shows the count instead.
--
-- `window(type)`, from the `WinNew` safety net of LuaScripts.WINDOWS after it closed a `quickfix`
-- or `loclist` window (`:copen`, `:cwindow`, `:lopen`, also from a mapping or a plug-in): with one
-- of our lists current, `eclipse_show_view` with its kind, and Eclipse shows the Problems or Tasks
-- view; else the list's `:clist` (or `:llist`) as output, which Neovim shows (ui2).
--
-- `current()`: the current list's kind and argument when it is ours, else nothing
-- (LuaScripts.QUICKFIX_CURRENT). Neovim has no event for a change of the current list (`:colder`,
-- `:chistory`), so Eclipse asks before an update.
--
-- Sent as LuaScripts.QUICKFIX (core/LuaModules runs this file as a chunk of its own).

local M = {}
local titles = { problems = 'Eclipse problems', tasks = 'Eclipse tasks' }
local severities = { 'error', 'warning', 'info' }
local function channel()
  return vim.g.neovim4eclipse_channel or 0
end
local function echo(text, highlight)
  vim.api.nvim_echo({ { text, highlight } }, true, {})
end

--- The kind of a list's context when the plug-in made the list, else nil.
local function kind_of(context)
  return type(context) == 'table' and titles[context.neovim4eclipse] and context.neovim4eclipse or nil
end

function M.current()
  local list = vim.fn.getqflist({ nr = 0, context = 0 })
  local kind = kind_of(list.context)
  if kind then
    return { kind, list.context.arg or '' }
  end
end

--- The id and number of the newest list of `kind` in the stack, or nil.
local function find(kind)
  for nr = vim.fn.getqflist({ nr = '$' }).nr, 1, -1 do
    local list = vim.fn.getqflist({ nr = nr, id = 0, context = 0 })
    if kind_of(list.context) == kind then
      return list.id, nr
    end
  end
end

--- The current entry of list `id`, or nil.
local function entry(id)
  local idx = vim.fn.getqflist({ id = id, idx = 0 }).idx
  return idx > 0 and vim.fn.getqflist({ id = id, idx = idx, items = 0 }).items[1] or nil
end

--- The column fix-up of the class comment: `before` is the current entry before the update.
local function move_to_column(id, before)
  if not before or before.col ~= 0 or before.lnum == 0 then
    return
  end
  -- Normal mode only: in insert or visual mode the user is already doing something there
  -- (moving the cursor would put typed text, or the selection's end, elsewhere).
  if vim.api.nvim_get_mode().mode ~= 'n' then
    return
  end
  local after = entry(id)
  local buf = vim.api.nvim_get_current_buf()
  if not after or after.col == 0 or after.bufnr ~= buf or before.bufnr ~= buf then
    return
  end
  local cursor = vim.api.nvim_win_get_cursor(0)
  local line = vim.api.nvim_buf_get_lines(buf, cursor[1] - 1, cursor[1], false)[1] or ''
  local first = (line:find('%S') or 1) - 1 -- where the line-only jump put the cursor
  if cursor[1] == before.lnum and cursor[2] == first then
    pcall(vim.api.nvim_win_set_cursor, 0, { after.lnum, after.col - 1 })
  end
end

--- 'Eclipse problems: 12 (3 errors, 9 warnings)'.
local function summary(title, items)
  local counts, names = {}, { E = 'error', W = 'warning', I = 'info' }
  for _, item in ipairs(items) do
    counts[item.type] = (counts[item.type] or 0) + 1
  end
  local parts = {}
  for _, letter in ipairs({ 'E', 'W', 'I' }) do
    local n = counts[letter]
    if n then
      parts[#parts + 1] = n .. ' ' .. names[letter] .. (n == 1 and '' or 's')
    end
  end
  if #items == 0 then
    return title .. ': none'
  end
  return title .. ': ' .. #items .. (#parts > 0 and ' (' .. table.concat(parts, ', ') .. ')' or '')
end

function M.set(kind, arg, mode, jump, flat)
  local items = {}
  for i = 1, #flat - 6, 7 do
    local bufnr = tonumber(flat[i]) or 0
    local item = {
      module = flat[i + 2] ~= '' and flat[i + 2] or nil,
      lnum = tonumber(flat[i + 3]),
      col = tonumber(flat[i + 4]),
      type = flat[i + 5],
      text = flat[i + 6],
    }
    if bufnr > 0 and vim.api.nvim_buf_is_valid(bufnr) then
      item.bufnr = bufnr
    elseif flat[i + 1] ~= '' then
      item.filename = flat[i + 1]
    end -- neither: a marker on a folder or project, text only (:cnext steps over it)
    items[#items + 1] = item
  end
  local title = titles[kind]
  if arg ~= '' then
    title = title .. ' (' .. arg .. ')'
  end
  local context = { neovim4eclipse = kind, arg = arg }
  if mode == 'update' then
    local current = vim.fn.getqflist({ nr = 0, id = 0, context = 0 })
    if kind_of(current.context) ~= kind or (current.context.arg or '') ~= arg then
      return -- not ours, or ours with another severity: left as it is
    end
    local before = entry(current.id)
    vim.fn.setqflist({}, 'u', { id = current.id, title = title, context = context, items = items })
    move_to_column(current.id, before)
    return
  end
  local id, nr = find(kind)
  if id then
    vim.fn.setqflist({}, 'u', { id = id, title = title, context = context, items = items })
    if nr ~= vim.fn.getqflist({ nr = 0 }).nr then
      vim.cmd('silent ' .. nr .. 'chistory')
    end
  else
    vim.fn.setqflist({}, ' ', { title = title, context = context, items = items })
  end
  -- Jump only when there is somewhere to go: entries that are all text only (markers on a
  -- folder or project) would make :cfirst say "E42: No Errors" about a list that has some.
  local valid = false
  for _, item in ipairs(items) do
    if item.bufnr or item.filename then
      valid = true
      break
    end
  end
  if jump == '1' and valid then
    local ok, err = pcall(vim.cmd, 'cfirst') -- shows "(1 of 12): ..." as :make does
    if not ok then
      echo((tostring(err):gsub('^.-Vim%(%a+%):', '')), 'ErrorMsg')
    end
  else
    echo(summary(title, items))
  end
end

function M.window(wintype)
  local kind = wintype == 'quickfix' and M.current() or nil
  if kind then
    vim.rpcnotify(channel(), 'eclipse_show_view', kind[1])
    return
  end
  local command = wintype == 'loclist' and 'llist' or 'clist'
  local ok, output = pcall(vim.fn.execute, command)
  output = ok and output or tostring(output)
  output = output:gsub('^%s*\n', '')
  echo(output, ok and nil or 'ErrorMsg')
end

--- vim.g.neovim4eclipse_task_tags as one comma-separated string: 'JM6,FIXME', or '*'.
local function task_tags()
  local tags = vim.g.neovim4eclipse_task_tags
  if type(tags) == 'table' then
    tags = table.concat(vim.tbl_map(tostring, tags), ',')
  end
  return vim.trim(type(tags) == 'string' and tags or '')
end

vim.api.nvim_create_user_command('Eproblems', function(o)
  local arg = vim.trim(o.args)
  if arg ~= '' and not vim.tbl_contains(severities, arg) then
    echo('E475: Invalid argument: ' .. arg .. ' (error, warning or info)', 'ErrorMsg')
    return
  end
  vim.rpcnotify(channel(), 'eclipse_quickfix', 'problems', arg, o.bang)
end, {
  nargs = '?',
  bang = true,
  complete = function(lead)
    return vim.tbl_filter(function(s) return s:sub(1, #lead) == lead end, severities)
  end,
  desc = "Eclipse's problems in the quickfix list (at least the given severity)",
})
vim.api.nvim_create_user_command('Etasks', function(o)
  local tags = task_tags()
  if tags == '' or tags:gsub('[%s,]', '') == '' then
    echo([[Etasks: no task tags; set them in init.lua: vim.g.neovim4eclipse_task_tags = 'TODO,FIXME' (or '*' for all)]], 'WarningMsg')
    return
  end
  vim.rpcnotify(channel(), 'eclipse_quickfix', 'tasks', tags, o.bang)
end, { nargs = 0, bang = true, desc = "Eclipse's tasks with your tags (vim.g.neovim4eclipse_task_tags) in the quickfix list" })
package.loaded['neovim4eclipse.quickfix'] = M
