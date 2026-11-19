-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- HEALTH_CHECK: the plug-in's own :checkhealth neovim4eclipse, in the editing Neovim.

local t = ...

--- HEALTH_CHECK with the stub written where :checkhealth looks for it, then the report's lines.
local function report(info)
  local folder = vim.fn.tempname()
  vim.fn.mkdir(folder .. '/lua/neovim4eclipse', 'p')
  vim.fn.writefile({ "return { check = function() vim.health.error('stub') end }" },
    folder .. '/lua/neovim4eclipse/health.lua')
  t.eq(true, t.run('HEALTH_CHECK', folder, info))
  local rtp = vim.opt.runtimepath:get()
  t.same_file(folder, rtp[#rtp], 'appended, last')
  vim.cmd('checkhealth neovim4eclipse')
  return table.concat(vim.api.nvim_buf_get_lines(vim.fn.bufnr('health://'), 0, -1, false), '\n')
end

local function info(program)
  return { minimum = '0.12.6', program = program, plugin = '0.2.27', eclipse = '4.38.0',
    readers = { 'loaded_netrw', 'loaded_zipPlugin' } }
end

t.test(':checkhealth neovim4eclipse: the dedicated Neovim, and what is off for the files you open', function()
  vim.g.loaded_netrw = 1
  vim.g.loaded_zipPlugin = 1
  vim.go.modeline = false
  local text = report(info('dedicated'))
  t.ok(not text:find('stub', 1, true), 'the registered check ran, not the file: ' .. text)
  t.ok(text:find('OK Neovim ' .. tostring(vim.version()):match('^%d+%.%d+%.%d+'), 1, true), text)
  t.ok(text:find('OK The dedicated Neovim', 1, true), text)
  t.ok(text:find('Neovim for Eclipse 0.2.27', 1, true), text)
  t.ok(text:find('Eclipse 4.38.0', 1, true), text)
  t.ok(text:find('OK Modelines are off', 1, true), text)
  t.ok(text:find('OK g:loaded_netrw is set', 1, true), text)
end)

t.test(':checkhealth neovim4eclipse warns of a program of your own and of what is on again', function()
  vim.g.loaded_netrw = nil
  vim.g.loaded_zipPlugin = 1
  vim.go.modeline = true
  local text = report(info('own'))
  t.ok(text:find('WARNING A Neovim program of your own', 1, true), text)
  t.ok(text:find('WARNING Modelines are on again', 1, true), text)
  t.ok(text:find('WARNING g:loaded_netrw is unset', 1, true), text)
  vim.go.modeline = false
end)

t.test(':checkhealth neovim4eclipse: a Neovim older than the minimum is an error', function()
  local text = report({ minimum = '99.0.0', program = 'dedicated', plugin = '0.2.27', eclipse = '',
    readers = {} })
  t.ok(text:find('ERROR Neovim ', 1, true) and text:find('older than 99.0.0', 1, true), text)
  t.ok(text:find('Eclipse version unknown', 1, true), text)
end)
