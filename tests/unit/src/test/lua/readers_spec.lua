-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- LuaScripts.NO_TERMINAL_URLS: a buffer named term://... starts no program. And the groups of the
-- bundled readers that NvimExecutable.READERS_OFF keeps from loading.

local t = ...

t.test('NO_TERMINAL_URLS: a buffer named term://... starts no program, :terminal still works', function()
  t.eq(1, vim.fn.exists('#nvim.terminal#BufReadCmd'), 'this Neovim reads term:// names by running them')
  t.run('NO_TERMINAL_URLS')
  t.eq(0, vim.fn.exists('#nvim.terminal#BufReadCmd'))
  -- gf on such a name in a file's text: the buffer it opens is no terminal. The program named is
  -- this Neovim, which exists on every system.
  vim.cmd('enew!')
  vim.api.nvim_buf_set_lines(0, 0, -1, false, { 'term://' .. vim.v.progpath:gsub('\\', '/') })
  vim.bo.modified = false
  pcall(vim.cmd, 'normal! gf')
  t.ok(vim.bo.buftype ~= 'terminal', 'no terminal was started')
  vim.cmd('enew!')
  vim.fn.jobstop(vim.fn.jobstart({ vim.v.progpath, '--version' }, { term = true }))
  t.eq('terminal', vim.bo.buftype, 'a terminal the user starts is still one')
end)

-- What NvimExecutable.READERS_OFF keeps from loading: each reader's autocommand group. Here Neovim
-- runs without that start command, so the groups exist; NeovimSessionIntegrationTest checks that
-- a session has none of them. A Neovim that renames a group fails here, not silently there.
t.test('the bundled readers of URLs and archives have the groups the session test looks for', function()
  for _, group in ipairs({ 'Network', 'nvim.net.remotefile', 'zip', 'tar' }) do
    t.eq(1, vim.fn.exists('#' .. group), group)
  end
end)
