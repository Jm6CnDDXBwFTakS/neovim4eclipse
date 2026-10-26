-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- The small scripts the session runs on a document's buffer: LOAD_WITHOUT_UNDO (the first load is no
-- undo step) and SET_MODIFIED ('modified' follows Eclipse's dirty flag). When the session runs them is
-- Java (NeovimBufferIntegrationTest keeps those round trips).

local t = ...

local function lines()
  return vim.api.nvim_buf_get_lines(0, 0, -1, false)
end

t.test('loading without undo leaves nothing to undo, and the buffer unmodified', function()
  vim.bo.undolevels = 50
  t.run('LOAD_WITHOUT_UNDO', 'one', 'two')
  t.eq({ 'one', 'two' }, lines())
  t.eq(false, vim.bo.modified)
  t.eq(50, vim.bo.undolevels, 'the buffer\'s own value is back')
  t.type('u')
  t.eq({ 'one', 'two' }, lines(), 'u undoes nothing')
end)

t.test('a load also clears the history before it, but a later change is undone as usual', function()
  vim.api.nvim_buf_set_lines(0, 0, -1, false, { 'old' })
  t.run('LOAD_WITHOUT_UNDO', 'loaded')
  t.type('dd')
  t.type('u')
  t.eq({ 'loaded' }, lines())
  t.type('u')
  t.eq({ 'loaded' }, lines(), 'the text from before the load does not come back')
end)

t.test('the global undolevels stays global after a load', function()
  t.eq(-123456, vim.bo.undolevels, 'Neovim\'s "use the global value"')
  t.run('LOAD_WITHOUT_UNDO', 'x')
  t.eq(-123456, vim.bo.undolevels)
end)

t.test('modified is set and cleared on request, for a buffer that exists', function()
  local buf = vim.api.nvim_get_current_buf()
  t.run('SET_MODIFIED', tostring(buf), '1')
  t.eq(true, vim.bo[buf].modified)
  t.run('SET_MODIFIED', tostring(buf), '0')
  t.eq(false, vim.bo[buf].modified)
  t.run('SET_MODIFIED', '999', '1') -- gone already (Eclipse closed it): nothing to do, no error
end)
