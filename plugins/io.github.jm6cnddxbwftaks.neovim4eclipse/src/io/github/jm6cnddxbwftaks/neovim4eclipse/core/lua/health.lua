-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- Registers `:checkhealth neovim4eclipse` in the editing Neovim (HealthCheck): run once at start of
-- each Neovim, after the user's config, so the folder is appended once. Arguments: the folder
-- holding the stub `lua/neovim4eclipse/health.lua`, appended to `'runtimepath'` so that
-- `:checkhealth` finds the check (a folder whose name holds a comma never gets here:
-- `HealthCheck.fitsRuntimepath`), and what only Eclipse knows (`HealthCheck.Info.toLua`: `minimum`,
-- `program`, `plugin`, `eclipse`, `readers`).
--
-- The report, in three sections: Neovim, its version against the minimum (an error below it) and
-- whether it is the dedicated Neovim (a warning for a program of the user's: unchecked and
-- unsupported); Eclipse, the plug-in's and Eclipse's versions; Off for the files you open, read
-- live, since `init.lua` or a later `:set` may have turned one back on (a warning then): modelines,
-- each reader of `NvimExecutable.READERS_OFF` (its "already loaded" variable still set), and the
-- `term://` reader (no `BufReadCmd` left in the group `nvim.terminal`). Example line: `- OK Neovim
-- 0.12.6 (Neovim for Eclipse needs 0.12.6 or newer)`.
--
-- Sent as LuaScripts.HEALTH_CHECK (core/LuaModules runs this file as a chunk of its own).

local folder, info = ...
vim.opt.runtimepath:append(folder)
local M = {}
function M.check()
  local h = vim.health
  h.start('Neovim')
  local v = vim.version()
  local version = string.format('%d.%d.%d', v.major, v.minor, v.patch)
  if vim.version.lt(v, info.minimum) then
    h.error('Neovim ' .. version .. ' is older than ' .. info.minimum .. ', which Neovim for Eclipse needs')
  else
    h.ok('Neovim ' .. version .. ' (Neovim for Eclipse needs ' .. info.minimum .. ' or newer)')
  end
  if info.program == 'dedicated' then
    h.ok('The dedicated Neovim: its files were checked against the release before it started')
  else
    h.warn('A Neovim program of your own (-Dneovim4eclipse.nvim): run unchecked, and not supported',
      { 'Leave out -Dneovim4eclipse.nvim to use the dedicated Neovim; Get Neovim installs it' })
  end
  h.start('Eclipse')
  h.info('Neovim for Eclipse ' .. info.plugin)
  h.info('Eclipse ' .. (info.eclipse ~= '' and info.eclipse or 'version unknown'))
  h.start('Off for the files you open (SECURITY.md)')
  if vim.go.modeline then
    h.warn("Modelines are on again: a file's text can set options", { 'Your init.lua or a :set modeline turned them on' })
  else
    h.ok('Modelines are off')
  end
  for _, name in ipairs(info.readers) do
    if vim.g[name] ~= nil then
      h.ok('g:' .. name .. ' is set: that reader stays off')
    else
      h.warn('g:' .. name .. ' is unset: that reader is on again', { 'Your init.lua unset it' })
    end
  end
  local ok, reads = pcall(vim.api.nvim_get_autocmds, { group = 'nvim.terminal', event = 'BufReadCmd' })
  if ok and #reads > 0 then
    h.warn('The term:// reader is on again: a buffer named term://... runs its command')
  else
    h.ok('The term:// reader is off')
  end
end
package.loaded['neovim4eclipse.health'] = M
return true
