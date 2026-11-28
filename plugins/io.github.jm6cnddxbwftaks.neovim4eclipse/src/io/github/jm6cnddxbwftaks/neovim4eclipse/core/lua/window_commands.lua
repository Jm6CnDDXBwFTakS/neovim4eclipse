-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- Vim's window commands, carried out on Eclipse's editors: each Vim window is an editor, and a
-- split is a new editor stack beside the current one (docs/design/SPLITS.md). Run after
-- LuaScripts.HOST_COMMANDS (its `translate` and `refuse` hooks) and LuaScripts.WINDOWS.
--
-- Neovim works out what each command means, as Vim would, and hands Eclipse the result as `:Eclipse
-- window` words (`util/WindowCommand` reads them): where a split goes, which file it shows, the
-- count. A typed line goes through the `translate` hook, so `@:` repeats it; a `Ctrl-W` key sends
-- the same words itself.
--
-- What each command becomes:
-- - `:split`, `:sview`, `:sbuffer` of this buffer, `Ctrl-W s S ^S` -> `window split above same`: a
--   clone of this editor
-- - `:vsplit`, `:vertical split`, `Ctrl-W v ^V` -> `window split left same`
-- - `:split b.txt`, `:sfind b.txt`, `:sbuffer 3`, `Ctrl-W ^`, `Ctrl-W f` -> `window split above
--   file /ws/app/b.txt`
-- - `:new`, `:vnew`, `Ctrl-W n` -> `window split above new`
-- - `Ctrl-W h j k l w W t b p` (and the Ctrl and arrow forms), `:wincmd l` -> `window focus l 0`
-- - `Ctrl-W o`, `:only` -> `window only`
-- - `Ctrl-W = + - < > _ |`, `:resize +5`, `:vertical resize 80` -> `window size + 5`
-- - `Ctrl-W x`, `Ctrl-W r`, `Ctrl-W R` -> `window exchange 0`, `window rotate down 0`
-- - `Ctrl-W q ^Q c`, `:wincmd q` -> `quit`: the editor closes, as for `:q`
-- - `:tabedit b.txt`, `:tabfind b.txt`, `:tabnew`, `:tab split` -> `window tab file /ws/app/b.txt`,
--   `window tab new`, `window tab same`: a new editor tab in this stack
-- - `Ctrl-W T` -> `window totab`
-- - `:tabclose 2`, `:tabclose! $`, `:tabclose +` -> `window tabclose 2 0`, `window tabclose $ 1`:
--   that editor tab of this stack
-- - `:tabonly`, `:tabonly 2` -> `window tabonly .`, `window tabonly 2`: the other editor tabs of
--   this stack close
--
-- Vim's tab pages are Eclipse's editor tabs here, since each editor is a Neovim window alone in a
-- hidden tab page of its own: the tab commands work on the editor tabs of this stack, counted as
-- `:tabnext` counts them. A count in front of `:tabclose` or `:tabonly` (`:2tabclose`,
-- `:$tabclose`) is refused: Neovim has turned it into one of its hidden tab pages already, which
-- says nothing about the editor tabs.
--
-- Where a split goes is Vim's rule: above, or to the left for `:vsplit`, unless `'splitbelow'` or
-- `'splitright'` is set; `:aboveleft` and `:belowright` (and `:topleft`, `:botright`, taken as
-- those two) say it for one command. A height or width given with the split (`:5split`) is not
-- used: the new editor takes half.
--
-- Refused, with a message: `:tabonly!`, which would discard every other editor tab's changes at
-- once (Vim's keeps their buffers hidden); and the splits that jump somewhere first, which need
-- more than a split (`:stag`, `Ctrl-W ]`, `Ctrl-W F`). `:close` and `:tabclose` close the editor:
-- `util/HostCommand`.
--
-- The keys are mapped only where the user's config maps none of them itself, and reach Neovim only
-- when Ctrl+W does (the default; its Normal box under Ctrl Keys: docs/KEYBOARD.md). In a side
-- window (`is_side_window` of LuaScripts.WINDOWS: a floating window the user entered), `:q`,
-- `:close`, `Ctrl-W q` and `Ctrl-W c` are Neovim's again: they close that window, as in Neovim, not
-- the editor.
--
-- A window opened some other way (a plug-in's `vim.cmd.split()`) reaches the `WinNew` safety net of
-- LuaScripts.WINDOWS: on a file it becomes an editor through `open_beside`, on anything else it is
-- closed. The module is `package.loaded['neovim4eclipse.window_commands']`;
-- LuaScripts.SPLIT_WINDOW calls it.
--
-- Sent as LuaScripts.WINDOW_COMMANDS (core/LuaModules runs this file as a chunk of its own).

-- The modules this one calls, each sent before it (NeovimSession.initialize: PATHS first, then
-- WINDOWS, then HOST_COMMANDS). Read from package.loaded, never with require, which would search
-- the runtimepath (and ./?.lua) for a module the session had not sent. Checked first, so that a
-- failed install changes nothing.
local windows = package.loaded['neovim4eclipse.windows']
local host = package.loaded['neovim4eclipse.host_commands']
local paths = package.loaded['neovim4eclipse.paths']
if not (windows and host and paths) then
  error('neovim4eclipse.window_commands needs neovim4eclipse.paths, neovim4eclipse.windows and '
    .. 'neovim4eclipse.host_commands (LuaScripts.PATHS, WINDOWS, HOST_COMMANDS) first', 0)
end
local canonical = paths.canonical -- PATHS: case, links, separators
local M = {}

local JUMP_FIRST = 'a split that jumps to a tag is not supported; Ctrl-] goes to the declaration in this editor'
for _, name in ipairs({ 'stag', 'stjump', 'stselect' }) do
  host.refuse(name, function() return JUMP_FIRST end)
end
host.leave_to_neovim(function(name)
  -- :file too: in a float or side window it would rename the attached editor's file, not the
  -- buffer shown there (a plug-in's float may show another editor's document buffer).
  return (name == 'quit' or name == 'close' or name == 'file') and windows.is_side_window(0)
end)

--- The side a split goes to: Vim's 'splitbelow' and 'splitright', unless a modifier of
--- the command says otherwise (mods as nvim_parse_cmd gives them; nil for a key).
local function side(vertical, mods)
  local split = mods and mods.split or ''
  local after
  if split == 'aboveleft' or split == 'topleft' then
    after = false
  elseif split == 'belowright' or split == 'botright' then
    after = true
  elseif vertical then -- not `vertical and splitright or splitbelow`: false would fall through
    after = vim.o.splitright
  else
    after = vim.o.splitbelow
  end
  if vertical then
    return after and 'right' or 'left'
  end
  return after and 'below' or 'above'
end

--- The words of a split showing `file` (a full path): this buffer when it is this
--- buffer's file or nil, a new untitled editor for 'new'. With :tab in front (mods.tab),
--- or for :tabedit, a new editor tab in this stack instead: Eclipse's editor tabs are
--- Vim's tab pages here.
local function split(vertical, mods, file)
  local words = (mods and mods.tab and mods.tab >= 0) and { 'window', 'tab' }
      or { 'window', 'split', side(vertical, mods) }
  if file == 'new' then
    words[#words + 1] = 'new'
  elseif not file or canonical(file) == canonical(vim.api.nvim_buf_get_name(0)) then
    words[#words + 1] = 'same'
  else
    words[#words + 1] = 'file'
    words[#words + 1] = file
  end
  return words
end

--- A full path as :e's names are sent: with / only, $NAME not expanded again.
local function full(name)
  return vim.fs.normalize(vim.fn.fnamemodify(name, ':p'), { expand_env = false })
end

--- The file of buffer `buf` for a split (:sbuffer 3, Ctrl-W ^), or nil and why (`what`
--- when there is no such buffer). Only a buffer with a file: an untitled editor's buffer
--- has a name and no file.
local function buffer_file(buf, what)
  if not buf or buf <= 0 or not vim.api.nvim_buf_is_valid(buf) then
    return nil, what
  end
  if buf == vim.api.nvim_get_current_buf() then
    return vim.api.nvim_buf_get_name(0)
  end
  local name = vim.b[buf].neovim4eclipse_file or vim.api.nvim_buf_get_name(buf)
  if name == '' or vim.fn.isabsolutepath(name) == 0 or name:match('^%a[%w+.-]*://') then
    return nil, 'buffer ' .. buf .. ' has no file to open beside this one'
  end
  return full(name)
end

--- The words for :sbuffer: a buffer number, a name (as :buffer matches it), or none.
local function sbuffer(p)
  local arg = p.args[1] or (p.range and p.range[1] and tostring(p.range[1]))
  if not arg then
    return split(p.mods.vertical, p.mods, nil)
  end
  local buf = tonumber(arg) or vim.fn.bufnr(arg)
  local file, why = buffer_file(buf, tonumber(arg) and ('E86: Buffer ' .. arg .. ' does not exist')
      or ('E94: No matching buffer for ' .. arg))
  if not file then
    return nil, why
  end
  return split(p.mods.vertical, p.mods, file)
end

--- The words for a split of the file p names: found in 'path' when `find` (:sfind; a
--- name that is not there is Vim's E345), else read as :e reads its name. `empty` is
--- what a split without a name shows (nil: this buffer; 'new' for :new).
local function named(p, vertical, find, empty, mods)
  mods = mods or p.mods
  if #p.args == 0 then
    return split(vertical, mods, empty)
  end
  if find then
    -- Expanded as Vim's :sfind does (%:h/x, ~, #), once, from the line as typed.
    local ok, name = pcall(vim.fn.expandcmd, table.concat(p.args, ' '), { errmsg = true })
    if not ok then
      return nil, (tostring(name):gsub('^Vim:', ''))
    end
    local found = vim.fn.findfile(name)
    if found == '' then
      return nil, 'E345: Can\'t find file "' .. name .. '" in path'
    end
    return split(vertical, mods, full(found))
  end
  local file, why = host.file_name(p.args)
  if why then
    return nil, why
  end
  return split(vertical, mods, file or empty) -- only ++opt words: as without a name
end

for name, how in pairs({
  split = function(p) return named(p, p.mods.vertical, false, nil) end,
  vsplit = function(p) return named(p, true, false, nil) end,
  sview = function(p) return named(p, p.mods.vertical, false, nil) end,
  sfind = function(p) return named(p, p.mods.vertical, true, nil) end,
  new = function(p) return named(p, p.mods.vertical, false, 'new') end,
  vnew = function(p) return named(p, true, false, 'new') end,
  sbuffer = sbuffer,
}) do
  host.translate(name, how) -- :tab split too: mods.tab makes it a tab (split above)
end

-- Vim's tab pages are Eclipse's editor tabs here: :tabedit file opens the file in a new
-- editor tab of this stack (a new editor even when one has it open, as a tab page of
-- its own would show it), :tabnew and :tabedit alone a new untitled one.
local TAB = { tab = 1 }
host.translate('tabedit', function(p) return named(p, false, false, 'new', TAB) end)
host.translate('tabnew', function(p) return named(p, false, false, 'new', TAB) end)
host.translate('tabfind', function(p)
  if #p.args == 0 then
    return nil, 'E471: Argument required'
  end
  return named(p, false, true, nil, TAB)
end)
--- The words for a tab named after :tabclose or :tabonly (2, $, +, -2), or nil and why. A
--- count in front (:2tabclose, :$tabclose) is refused: Neovim has already turned it into
--- one of its own tab pages, the editors' hidden ones, which say nothing about the editor
--- tabs.
local function tab_named(p)
  if p.range and #p.range > 0 then
    return nil, 'write the tab after the command: :' .. p.cmd .. ' 2, :' .. p.cmd .. ' $, :' .. p.cmd .. ' +'
  end
  local arg = p.args[1]
  if arg and not (arg == '$' or arg:match('^[+-]?%d+$') or arg == '+' or arg == '-') then
    return nil, 'E475: Invalid argument: ' .. arg
  end
  return arg
end

-- :tabonly, :tabonly 2: the other editor tabs of this stack close, as Eclipse's Close
-- Others closes them (the one named stays and comes to the front); Eclipse asks once
-- about the changes of those it would lose. :tabonly! is refused (windows.TABS).
host.translate('tabonly', function(p)
  if p.bang then
    return nil, windows.TABS
  end
  local arg, why = tab_named(p)
  if why then
    return nil, why
  end
  return { 'window', 'tabonly', arg or '.' }
end)

-- :tabclose 2, :tabclose $, :tabclose +, :tabclose -2: the editor tab of this stack that
-- many tabs on, or the Nth, as :tabnext counts them. :tabclose alone is the table's:
-- this editor closes.
host.translate('tabclose', function(p)
  local arg, why = tab_named(p)
  if why then
    return nil, why
  end
  if not arg then
    return nil -- the usual handling: close this editor
  end
  return { 'window', 'tabclose', arg, p.bang and '1' or '0' }
end)

-- Ctrl-W's control forms as the letters they stand for: Ctrl-W Ctrl-S is Ctrl-W s.
local CONTROL = { ['<C-^>'] = '^', ['<C-_>'] = '_', ['<C-]>'] = ']' }
for c in ('abcdefghijklmnopqrstuvwxyz'):gmatch('.') do
  CONTROL['<C-' .. c .. '>'] = c
end
local ARROWS = { ['<Left>'] = 'h', ['<Down>'] = 'j', ['<Up>'] = 'k', ['<Right>'] = 'l', ['<BS>'] = 'h' }

--- The words for Ctrl-W followed by `key` with `count` (0: none), or nil and why, or
--- nothing for a key left to Neovim. Example: ('l', 2) gives { 'window', 'focus', 'l', '2' }.
function M.wincmd(key, count)
  key = CONTROL[key] or ARROWS[key] or key
  if key == 's' or key == 'S' then
    return split(false, nil, nil)
  elseif key == 'v' then
    return split(true, nil, nil)
  elseif key == 'n' then
    return split(false, nil, 'new')
  elseif key == '^' then
    local buf = count > 0 and count or vim.fn.bufnr('#')
    local file, why = buffer_file(buf, count > 0 and ('E86: Buffer ' .. count .. ' does not exist')
        or 'E23: No alternate file')
    if not file then
      return nil, why
    end
    return split(false, nil, file)
  elseif key == 'f' then
    local name = vim.fn.expand('<cfile>')
    if name == '' then
      return nil, 'E446: No file name under cursor'
    end
    local found = vim.fn.findfile(name)
    if found == '' then
      return nil, 'E447: Can\'t find file "' .. name .. '" in path'
    end
    return split(false, nil, full(found))
  elseif #key == 1 and ('hjklwWtbp'):find(key, 1, true) then
    return { 'window', 'focus', key, tostring(count) }
  elseif key == 'o' then
    return { 'window', 'only' }
  elseif #key == 1 and ('=+-<>_|'):find(key, 1, true) then
    return { 'window', 'size', key, tostring(count) }
  elseif key == 'x' then
    return { 'window', 'exchange', tostring(count) }
  elseif key == 'r' or key == 'R' then
    return { 'window', 'rotate', key == 'r' and 'down' or 'up', tostring(count) }
  elseif key == 'q' or key == 'c' then
    return { 'quit' }
  elseif key == 'T' then
    return { 'window', 'totab' }
  elseif key == 'F' or key == ']' or key == 'g]' or key == '}' then
    return nil, JUMP_FIRST
  end
end

local function send(words)
  vim.rpcnotify(vim.g.neovim4eclipse_channel or 0, 'eclipse_command', words[1], false, vim.list_slice(words, 2),
    vim.api.nvim_get_current_win())
end

--- How each place of a plug-in's window reads as :split's arguments (WINDOWS' net).
local BESIDE = {
  above = { false, { split = 'aboveleft' } },
  below = { false, { split = 'belowright' } },
  left = { true, { split = 'aboveleft' } },
  right = { true, { split = 'belowright' } },
  tab = { false, TAB },
}
--- Opens `file` in an Eclipse editor on that side, or as a tab ('tab'), with its cursor
--- at (line, col), both 1-based: what a plug-in's own split of a file becomes, by the
--- route :vsplit file takes, so the same checks apply. Example: Telescope's Ctrl-V on
--- b.txt, line 40: ('left', '/ws/b.txt', 40, 1) sends window split left file /ws/b.txt.
function M.open_beside(where, file, line, col)
  local place = BESIDE[where]
  local path = full(file)
  host.open_at(path, line, col)
  send(split(place[1], place[2], path))
end

--- Toggle Split Editor in an editor Neovim drives (SPLIT_WINDOW): :split, or :vsplit
--- when `vertical`.
function M.split(vertical)
  send(split(vertical, nil, nil))
end

-- :wincmd {key}, :3wincmd l: the same as the key.
host.translate('wincmd', function(p)
  local key = p.args[1] or ''
  local byte = key:byte()
  if #key == 1 and byte and byte < 32 then -- :exe "wincmd \<C-W>": a control character
    key = byte == 30 and '^' or byte == 31 and '_' or byte == 29 and ']' or string.char(byte + 96)
  end
  local count = p.count and p.count > 0 and p.count or (p.range and p.range[1]) or 0
  local words, why = M.wincmd(key, count)
  if not words and not why then
    return nil, 'E474: Invalid argument' -- a key Eclipse has nothing for (:wincmd z)
  end
  return words, why
end)
-- :only (! changes nothing: no editor is closed).
host.translate('only', function()
  return { 'window', 'only' }
end)
-- :resize [+-]N, :vertical resize N; :resize alone makes the window as high as it can be.
host.translate('resize', function(p)
  local arg = p.args[1] or (p.range and p.range[1] and tostring(p.range[1])) or ''
  local sign, number = arg:match('^([+-]?)(%d*)$')
  if not sign then
    return nil, 'E475: Invalid argument: ' .. arg
  end
  local key = p.mods.vertical and ({ ['+'] = '>', ['-'] = '<', [''] = '|' })[sign]
      or ({ ['+'] = '+', ['-'] = '-', [''] = '_' })[sign]
  local count = number == '' and (sign == '' and 0 or 1) or tonumber(number)
  return { 'window', 'size', key, tostring(count) }
end)

--- Closes the editor, or in a side window, the window: the key as Neovim has it (the
--- mapping is not recursive, so the key is Neovim's own there).
local function quit(key)
  return function()
    return windows.is_side_window(0) and key or '<Cmd>Eclipse quit<CR>'
  end
end
local function key_command(key)
  return function()
    local words, why = M.wincmd(key, vim.v.count)
    if words then
      send(words)
    elseif why then
      vim.api.nvim_echo({ { why } }, true, { err = true })
    end
  end
end
local keys = { 's', 'S', '<C-s>', 'v', '<C-v>', 'n', '<C-n>', '^', '<C-^>', 'f', '<C-f>',
               'h', 'j', 'k', 'l', '<C-h>', '<C-j>', '<C-k>', '<C-l>',
               '<Left>', '<Down>', '<Up>', '<Right>', '<BS>',
               'w', '<C-w>', 'W', 'p', '<C-p>', 't', '<C-t>', 'b', '<C-b>',
               'o', '<C-o>', '=', '+', '-', '<', '>', '_', '<C-_>', '|',
               'x', '<C-x>', 'r', '<C-r>', 'R', 'T', 'F', ']', '<C-]>', 'g]', '}' }
for _, key in ipairs(keys) do
  local lhs = '<C-w>' .. key
  if vim.fn.maparg(lhs, 'n') == '' then
    vim.keymap.set('n', lhs, key_command(key), { desc = 'Eclipse: window command' })
  end
end
for _, lhs in ipairs({ '<C-w>q', '<C-w><C-q>', '<C-w>c' }) do
  if vim.fn.maparg(lhs, 'n') == '' then
    vim.keymap.set('n', lhs, quit(lhs), { expr = true, desc = 'Eclipse: close the editor' })
  end
end
package.loaded['neovim4eclipse.window_commands'] = M
