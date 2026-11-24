-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- Maps the keys only the IDE can carry out (`gd`, `K`, the `gr*` keys, `gt`; see IdeAction): each
-- sends `eclipse_action` with its action and Vim's count (`v:count`, the count typed before the
-- key). Arguments: four per key, lhs, modes, action, description, from the one table IdeKeys
-- (`IdeKeys.toLuaArgs()`), which Eclipse's side reads too. Example: `"gra", "nx", "quick_fix",
-- "Quick Fix"` maps `gra` in normal and visual mode, described `Eclipse: Quick Fix`.
--
-- Run right after LuaScripts.SETUP, so after the user's `init.lua`: it wins over their own mappings
-- of these keys. `vim.g.neovim4eclipse_ide_keys = 0` (or `false`, `v:false` in Vim script) in
-- `init.lua` keeps Neovim's meaning of all of them instead.
--
-- Sent as LuaScripts.IDE_KEYS (core/LuaModules runs this file as a chunk of its own).

local ide_keys = vim.g.neovim4eclipse_ide_keys
if ide_keys == 0 or ide_keys == false then
  return
end
local args = { ... }
-- The keys, and the global mapping each had before (Neovim's own default for gr*), by
-- "mode lhs": a page gives them back (WINDOWS.open_page).
local ide = { keys = {}, before = {} }
package.loaded['neovim4eclipse.ide_keys'] = ide
for i = 1, #args - 3, 4 do
  local lhs, modes, action, desc = args[i], args[i + 1], args[i + 2], args[i + 3]
  ide.keys[#ide.keys + 1] = { lhs = lhs, modes = vim.split(modes, ''), action = action }
  for _, mode in ipairs(vim.split(modes, '')) do
    local before = vim.fn.maparg(lhs, mode, false, true)
    if before.lhs and before.buffer == 0 then
      ide.before[mode .. ' ' .. lhs] = before
    end
  end
  vim.keymap.set(vim.split(modes, ''), lhs, function()
    vim.rpcnotify(vim.g.neovim4eclipse_channel or 0, 'eclipse_action', action, vim.v.count)
  end, { desc = 'Eclipse: ' .. desc })
end
