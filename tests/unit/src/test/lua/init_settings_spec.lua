-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- The problem report's question to Neovim (LuaScripts.INIT_SETTINGS, util/ProblemReport): which of
-- the plug-in's init.lua settings are set, each with its value as vim.inspect prints it.

local t = ...

t.test('only the set ones come back, as vim.g names with their values', function()
  vim.g.neovim4eclipse_folds = 0
  vim.g.neovim4eclipse_task_tags = 'TODO,JM6'
  local set = t.run('INIT_SETTINGS', { 'neovim4eclipse_folds', 'neovim4eclipse_viewport', 'neovim4eclipse_task_tags' })
  vim.g.neovim4eclipse_folds = nil
  vim.g.neovim4eclipse_task_tags = nil
  t.eq({ { 'vim.g.neovim4eclipse_folds', '0' }, { 'vim.g.neovim4eclipse_task_tags', '"TODO,JM6"' } }, set)
end)

t.test('none set is an empty list', function()
  t.eq({}, t.run('INIT_SETTINGS', { 'neovim4eclipse_folds' }))
end)
