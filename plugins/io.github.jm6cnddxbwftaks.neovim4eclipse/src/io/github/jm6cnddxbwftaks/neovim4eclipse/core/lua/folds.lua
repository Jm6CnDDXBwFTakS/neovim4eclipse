-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- Installs the fold module, `package.loaded['neovim4eclipse.folds']`, unless the user's config
-- turned folding off with `vim.g.neovim4eclipse_folds = 0` (or `false`), and returns whether
-- folding is on. The session runs it once at start, right after LuaScripts.SETUP (so after
-- `init.lua`), and keeps the answer (`NeovimSession.foldsEnabled()`): when it is off, no other fold
-- script is ever sent, so the opt-out costs nothing (docs/design/FOLDING.md, 3.6). Changing the
-- variable later needs a Neovim restart.
--
-- What the module does. Eclipse's fold regions become Neovim manual folds (`'foldmethod'=manual`,
-- `:{first},{last}fold`) in the window that shows the buffer, and each fold gets an extmark in the
-- namespace `neovim4eclipse_folds` whose id is the fold's index in Eclipse's list plus one (extmark
-- ids start at 1). The marks move with the text as the folds do, so a fold can be named to Eclipse
-- after edits. Functions:
-- - `set(buf, folds)`, used by LuaScripts.SET_FOLDS: replaces the current window's folds. `folds`
--   is a list of `{first, last, collapsed}` (1-based lines).
-- - `closed(win)`: the indexes (0-based) of the window's outermost closed folds. A fold inside a
--   closed fold is never listed, whatever its state: Neovim cannot see it (`foldclosed()` gives the
--   outer fold's first line for every line inside), so Eclipse keeps its own state for it
--   (`util/FoldRegions.stateChange`).
-- - `watch(fn)` and `poll(win)`: the one place that decides when fold state is read. Neovim has no
--   event for "a fold opened or closed" (no autocommand, and no UI event without `ext_multigrid`),
--   so for now LuaScripts.STATE_SNAPSHOT calls `poll` after every key; `poll` returns `closed(win)`
--   and calls every `watch` function when the list differs from the last one for that window and
--   buffer. Should Neovim add a fold event, only this trigger changes. The first watcher fires
--   `User Neovim4EclipseFolds` (with `data = { buf = 3, closed = { 0, 2 } }`), so a user's config
--   can react to fold changes, for example: `vim.api.nvim_create_autocmd('User', { pattern =
--   'Neovim4EclipseFolds', callback = ... })`.
--
-- How `set` closes exactly the collapsed folds. Each fold is created open, outer first (`:fold`
-- makes a closed fold, and a range over a closed fold would grow to cover all of it: experiment
-- X3). Then the collapsed ones are closed, inner first, each with `:{l}foldclose` on one handle
-- line `l`: the first line of the fold that no open fold inside it covers. `:foldclose` closes the
-- innermost open fold on each line of its range (X11), and on a line covered only by closed inner
-- folds it closes the fold around them (X13). Example: a collapsed class on lines 1-20 whose lines
-- 1-8 are an open method is closed on line 9. When open inner folds cover every line, the first
-- line is used and the innermost of them closes instead; the next snapshot tells Eclipse.
--
-- After `zn`. `'nofoldenable'` hides every fold's state from `foldclosed()`, and Eclipse, which
-- shows what Neovim asked, has all its regions expanded. A resend (after a reconcile, say) with
-- Eclipse's states would mark them all open, and `zi` would then bring back nothing. So while the
-- window has `'nofoldenable'`, `set` first reads the states Neovim keeps: it turns folding on for
-- the length of the call (nothing is redrawn inside one call), opens the outermost closed folds
-- level by level, noting each range, and then uses those states for the folds whose range is
-- unchanged; new folds take Eclipse's. `'nofoldenable'` is set again at the end, and `zi` restores
-- the user's folds.
--
-- Folds by hand are refused in a mirrored buffer: Eclipse decides where folds are, and its next
-- reconcile would replace them. `zf`, `zF`, `zd`, `zD` and `zE`, in normal and visual mode, show
-- `zf: Eclipse defines the folds in this editor`, in two places. Typed together, a buffer-local
-- mapping of the two keys refuses them. Typed apart, the mapping has timed out (`'timeoutlen'`) and
-- `z` is Neovim's own pending command, so a key watcher (`vim.on_key`) drops the second key when it
-- is typed while Neovim's pending command, as `'showcmd'` shows it, is `z`, and ends that `z`.
-- Example: `z`, two seconds, `E`: the `E` never reaches Neovim, and every fold stays. `z` alone is
-- not mapped, so the user's own mappings that start with `z` work, and a plug-in that lists what
-- can follow `z` (which-key) shows its menu. `'showcmd'` is kept on: the watcher sees nothing
-- without it. `:fold`, which cannot be mapped, is refused through LuaScripts.HOST_COMMANDS' hook,
-- so this script must run after it. Elsewhere (an editor that cannot fold) they are Neovim's. Only
-- typed command lines are caught, as for the other host commands: `vim.cmd.fold()` from Lua runs.
-- (Neovim has no `:folddelete`: deleting is `zd` only, and `:foldd` is `:folddoopen`, which opens
-- folds and stays Neovim's.)
--
-- The old folds are deleted with `:noautocmd normal! zE`: Eclipse can send folds while Neovim is in
-- insert mode, and a plain `:normal` passes through normal mode, firing `ModeChanged`, which made
-- the plug-in take insert mode as ended.
--
-- An empty list means the editor cannot fold: the window gets `'nofoldenable'` (window-local), so a
-- user's `'foldmethod'` cannot hide lines that Eclipse shows, and the marks and
-- `b:neovim4eclipse_folds` (the "mirrored" flag) are removed. All options are set window-local
-- (`:setlocal`), so other windows and the user's global values are untouched.
--
-- Sent as LuaScripts.FOLDS (core/LuaModules runs this file as a chunk of its own).

local enabled = vim.g.neovim4eclipse_folds
if enabled == 0 or enabled == false then
  return false
end
local M = {}
local ns = vim.api.nvim_create_namespace('neovim4eclipse_folds')
M.ns = ns
local watchers, last = {}, {}

local function setlocal(name, value)
  vim.api.nvim_set_option_value(name, value, { scope = 'local', win = 0 })
end

-- Making or deleting folds by hand: Eclipse decides where folds are, and its next
-- reconcile would replace them. The commands get a refusal in HOST_COMMANDS (built-in
-- commands cannot be mapped). The keys zf zF zd zD zE, in normal and visual mode, are
-- refused in two places, since they can reach Neovim in two ways:
--  1. Together (typed quickly, fed by a mapping or by a plug-in that lists what can
--     follow z): a buffer-local mapping of the two keys.
--  2. Apart: Neovim waits only 'timeoutlen' (1000 ms by default) for a mapping's second
--     key. After that z is Neovim's own pending command, and a slow zE would delete
--     every fold. So vim.on_key watches the typed keys: one of those keys typed while
--     Neovim's pending command is z is dropped, and the z ended.
-- z alone is not mapped (it was, through 0.2.22, reading the next key itself): so the
-- user's mappings that start with z work, whatever their length, and a plug-in that
-- shows what can follow z (which-key) gets its turn.
local REFUSED = 'Eclipse defines the folds in this editor'
local REFUSED_AFTER_Z = { n = { f = true, F = true, d = true, D = true, E = true },
                          x = { f = true, F = true, d = true, D = true, E = true } }
local ESC = string.char(27)

-- What each key would do, for its mapping's description: a plug-in that lists what can
-- follow z (which-key) shows it, so it names the command and says that it is off here,
-- e.g. for E: "Delete all folds: not here, Eclipse defines the folds".
local WOULD = { f = 'Create fold', F = 'Create fold for lines', d = 'Delete fold',
                D = 'Delete folds, nested too', E = 'Delete all folds' }

local function say_refused(key)
  vim.api.nvim_echo({ { 'z' .. key .. ': ' .. REFUSED } }, true, { err = true })
end

-- 1. The two keys together.
local function refuse_keys(buf, on)
  for mode, keys in pairs(REFUSED_AFTER_Z) do
    for key in pairs(keys) do
      if on then
        vim.keymap.set(mode, 'z' .. key, function()
          say_refused(key)
          if mode == 'x' then
            vim.api.nvim_feedkeys(ESC, 'n', false) -- leave visual mode, as zf does
          end
        end, { buffer = buf, desc = WOULD[key] .. ': not here, Eclipse defines the folds' })
      else
        pcall(vim.keymap.del, mode, 'z' .. key, { buffer = buf })
      end
    end
  end
end

-- 2. The keys apart. Whether Neovim's own pending command is z: what 'showcmd' would
-- show, without the count and the register that may come before it. Examples: "z",
-- "3z" and '"a2z' are a pending z; '"z' is a register waiting for its command ("zdd
-- deletes a line into register z), and "d" an operator.
local function z_pending()
  local shown = vim.api.nvim_eval_statusline('%S', {}).str
  shown = shown:gsub('^%d+', '')
  if shown:sub(1, 1) == '"' then
    shown = shown:sub(3):gsub('^%d+', '')
  end
  return shown == 'z'
end

-- 'n' in normal mode, 'x' in a visual mode, nil elsewhere (insert mode types its z and E,
-- and after an operator, mode "no", z is no fold command). Normal mode is also "niI"
-- and "niR": one command from insert or replace mode (Ctrl-O), where zE works as ever.
local function z_mode()
  local mode = vim.api.nvim_get_mode().mode
  if mode:sub(1, 1) == 'n' and mode:sub(2, 2) ~= 'o' then
    return 'n'
  end
  local first = mode:sub(1, 1)
  if first == 'v' or first == 'V' or first == string.char(22) then
    return 'x'
  end
  return nil
end

-- Returning '' drops the key (:help vim.on_key()); there is no way to put another in
-- its place. So the pending z is ended with an Esc fed to the head of the typeahead
-- ('i'), where it is the very next key z reads, before anything typed after the
-- dropped key (z, a pause, then "Eihello": the i must not become zi); not remapped
-- ('n'), so a user's mapping of Esc does not run. In visual mode a second Esc leaves
-- it, as a refused zf does. Only typed keys: Neovim's own zE from this script
-- (delete_all_folds) and a :normal are not typed.
-- In pcall: Neovim removes an on_key function that fails, and the folds would be
-- unguarded from then on. A failure lets the key through: typing is never blocked.
-- At a typed z, 'showcmd' is turned on if it is off: z_pending() reads through it, and
-- the OptionSet below does not hear every way of turning it off (:noautocmd, or
-- 'eventignore'); set here, before Neovim takes the z, it shows that z.
vim.on_key(function(_, typed)
  local ok, drop = pcall(function()
    if not typed or typed == '' or not vim.b.neovim4eclipse_folds then
      return false
    end
    if typed == 'z' and not vim.o.showcmd then
      vim.o.showcmd = true
      return false
    end
    local mode = z_mode()
    if not (mode and REFUSED_AFTER_Z[mode][typed] and z_pending()) then
      return false
    end
    vim.api.nvim_feedkeys(mode == 'x' and ESC .. ESC or ESC, 'ni', false)
    vim.schedule(function()
      say_refused(typed)
    end)
    return true
  end)
  if ok and drop then
    return ''
  end
end, vim.api.nvim_create_namespace('neovim4eclipse_fold_keys'))

-- The guard reads Neovim's pending command through 'showcmd' (SETUP turns it on; the
-- status bar needs it too). Off, it would see nothing, and a slow zE would delete the
-- folds: so it is turned on again, with a word why, once.
local showcmd_said = false
vim.api.nvim_create_autocmd('OptionSet', {
  group = vim.api.nvim_create_augroup('neovim4eclipse_fold_keys', { clear = true }),
  pattern = 'showcmd',
  callback = function()
    if vim.o.showcmd then
      return
    end
    vim.schedule(function()
      vim.o.showcmd = true
      if not showcmd_said then
        showcmd_said = true
        vim.api.nvim_echo({ { "'showcmd' stays on in Eclipse: the plug-in reads Neovim's pending"
          .. ' command through it (the status bar, and the fold keys after z)' } }, true, {})
      end
    end)
  end,
})

local host = package.loaded['neovim4eclipse.host_commands']
if host then
  host.refuse('fold', function()
    return vim.b.neovim4eclipse_folds and REFUSED or nil
  end)
end

-- Deletes every manual fold of the current window (zE), without ModeChanged.
-- Why: Eclipse may send folds while Neovim is in insert mode, and :normal passes through
-- normal mode on the way, which fires ModeChanged; the plug-in heard "normal" and took
-- insert mode as ended (a method typed in Eclipse then went missing). noautocmd keeps
-- that event (and the user's own autocommands) out; Neovim stays in insert mode. (Not
-- foldmethod=expr with foldexpr=0 and back: in insert mode Neovim does not recompute
-- expression folds, so the old folds stayed.)
local function delete_all_folds()
  vim.cmd('silent! noautocmd normal! zE')
end

-- The marks of buf as { first, last, index } (1-based lines, 0-based index), in index order.
local function marks(buf)
  local found = {}
  for _, m in ipairs(vim.api.nvim_buf_get_extmarks(buf, ns, 0, -1, { details = true })) do
    found[#found + 1] = { m[2] + 1, m[4].end_row + 1, m[1] - 1 }
  end
  table.sort(found, function(a, b) return a[3] < b[3] end)
  return found
end

-- Whether the fold over first..last is closed and not inside another closed fold.
local function outermost_closed(first, last)
  return vim.fn.foldclosed(first) == first and vim.fn.foldclosedend(first) == last
end

function M.closed(win)
  local buf = vim.api.nvim_win_get_buf(win)
  if not vim.b[buf].neovim4eclipse_folds then
    return {}
  end
  return vim.api.nvim_win_call(win, function()
    local ids = {}
    if not vim.wo.foldenable then
      return ids -- zn: Eclipse shows every fold open, as Neovim does
    end
    for _, m in ipairs(marks(buf)) do
      if outermost_closed(m[1], m[2]) then
        ids[#ids + 1] = m[3]
      end
    end
    return ids
  end)
end

function M.watch(fn)
  watchers[#watchers + 1] = fn
end

function M.poll(win)
  local ids = M.closed(win)
  local buf = vim.api.nvim_win_get_buf(win)
  local key = win .. ':' .. buf
  local joined = table.concat(ids, ',')
  if (last[key] or '') ~= joined then
    for _, fn in ipairs(watchers) do
      pcall(fn, ids, buf)
    end
  end
  last[key] = joined
  return ids
end

M.watch(function(ids, buf)
  vim.api.nvim_exec_autocmds('User', { pattern = 'Neovim4EclipseFolds', modeline = false,
    data = { buf = buf, closed = ids } })
end)

-- zn in effect: the closed state of every existing fold, by 'first:last' (true closed).
local function states_kept(buf)
  local states = {}
  setlocal('foldenable', true)
  local opened = true
  while opened do
    opened = false
    for _, m in ipairs(marks(buf)) do
      local key = m[1] .. ':' .. m[2]
      if states[key] == nil and outermost_closed(m[1], m[2]) then
        states[key] = true
        vim.cmd(m[1] .. 'foldopen') -- one level: this fold; the ones inside stay as they are
        opened = true
      end
    end
  end
  for _, m in ipairs(marks(buf)) do
    local key = m[1] .. ':' .. m[2]
    if states[key] == nil then
      states[key] = false
    end
  end
  return states
end

-- The first line of folds[i] that no open fold inside it covers (see the comment above).
local function handle_line(folds, i, closed)
  local f = folds[i]
  local line = f[1]
  local moved = true
  while moved and line <= f[2] do
    moved = false
    for j = i + 1, #folds do
      local g = folds[j]
      if g[1] > f[2] then
        break -- sorted by first line: no later fold is inside f
      end
      if not closed[j] and g[2] <= f[2] and g[1] <= line and line <= g[2] then
        line, moved = g[2] + 1, true
      end
    end
  end
  return line <= f[2] and line or f[1]
end

function M.set(buf, folds)
  local view = vim.fn.winsaveview()
  local kept = nil
  if #folds > 0 and vim.b[buf].neovim4eclipse_folds and not vim.wo.foldenable then
    kept = states_kept(buf)
  end
  vim.api.nvim_buf_clear_namespace(buf, ns, 0, -1)
  if #folds == 0 then
    setlocal('foldenable', false)
    if vim.b[buf].neovim4eclipse_folds then
      refuse_keys(buf, false)
    end
    vim.b[buf].neovim4eclipse_folds = nil
    return
  end
  setlocal('foldmethod', 'manual')
  setlocal('foldenable', true)
  setlocal('foldminlines', 0)
  delete_all_folds()
  local count = vim.api.nvim_buf_line_count(buf)
  local closed = {}
  for i, f in ipairs(folds) do
    local first, last = f[1], math.min(f[2], count)
    closed[i] = f[3]
    if kept and kept[f[1] .. ':' .. f[2]] ~= nil then
      closed[i] = kept[f[1] .. ':' .. f[2]]
    end
    if first < last then
      vim.cmd(first .. ',' .. last .. 'fold')
      vim.cmd(first .. 'foldopen')
      vim.api.nvim_buf_set_extmark(buf, ns, first - 1, 0, { id = i, end_row = last - 1, end_col = 0 })
    else
      closed[i] = false -- past the end of the buffer: no fold, so nothing to close
    end
  end
  for i = #folds, 1, -1 do
    if closed[i] then
      vim.cmd(handle_line(folds, i, closed) .. 'foldclose')
    end
  end
  if not vim.b[buf].neovim4eclipse_folds then
    refuse_keys(buf, true)
  end
  vim.b[buf].neovim4eclipse_folds = true
  if kept then
    setlocal('foldenable', false)
  end
  vim.fn.winrestview(view)
end

package.loaded['neovim4eclipse.folds'] = M
return true
