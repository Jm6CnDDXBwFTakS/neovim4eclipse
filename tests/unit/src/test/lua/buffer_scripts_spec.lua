-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- The small scripts the session runs on a document's buffer: LOAD_WITHOUT_UNDO (the first load is no
-- undo step, and moves no mark) and SET_MODIFIED ('modified' follows Eclipse's dirty flag). When the session runs them is
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

t.test('a load keeps the marks on their lines (a reused buffer, BUFFER_NUMBERS.md X10)', function()
  vim.api.nvim_buf_set_lines(0, 0, -1, false, { '' }) -- reloaded: one empty line, marks from before
  vim.api.nvim_buf_set_lines(0, 0, -1, false, { '1', '2', '3' })
  vim.api.nvim_buf_set_mark(0, 'a', 2, 0, {})
  vim.cmd('lockmarks call nvim_buf_set_lines(0, 0, -1, v:false, [""])')
  t.run('LOAD_WITHOUT_UNDO', 'one', 'two', 'three', 'four')
  t.eq({ 2, 0 }, vim.api.nvim_buf_get_mark(0, 'a'), 'not moved down by the lines replacing the empty one')
  t.eq(nil, vim.g.neovim4eclipse_load, 'nothing left in g:')
end)

t.test('modified is set and cleared on request, for a buffer that exists', function()
  local buf = vim.api.nvim_get_current_buf()
  t.run('SET_MODIFIED', tostring(buf), '1')
  t.eq(true, vim.bo[buf].modified)
  t.run('SET_MODIFIED', tostring(buf), '0')
  t.eq(false, vim.bo[buf].modified)
  t.run('SET_MODIFIED', '999', '1') -- gone already (Eclipse closed it): nothing to do, no error
end)

-- SHOW_BUFFER: the plug-in's own switch to a buffer shows no file info message, also for a user whose
-- 'shortmess' lacks F (common in configs from Vim). Neovim decides about the message while entering the
-- buffer, so F must be in 'shortmess' then; the user's value is back afterwards.
t.test('showing a buffer enters it with F in shortmess and puts the user\'s value back', function()
  vim.o.shortmess = 'filnxtToOI'
  local other = vim.api.nvim_create_buf(true, true)
  local during
  local id = vim.api.nvim_create_autocmd('BufEnter', { callback = function() during = vim.o.shortmess end })
  t.run('SHOW_BUFFER', tostring(other))
  vim.api.nvim_del_autocmd(id)
  t.eq(other, vim.api.nvim_get_current_buf())
  t.eq('filnxtToOIF', during, 'F added while entering')
  t.eq('filnxtToOI', vim.o.shortmess, 'the user\'s value afterwards')
end)

t.test('showing a buffer that is gone fails, and the user\'s shortmess is still put back', function()
  vim.o.shortmess = 'ltToOC'
  local ok = pcall(t.run, 'SHOW_BUFFER', '999')
  t.eq(false, ok)
  t.eq('ltToOC', vim.o.shortmess)
end)

-- CREATE_DOCUMENT_BUFFER: a document's buffer named as :e names one, not "not edited" as
-- nvim_buf_set_name leaves it; loaded and empty without reading the file, and without the events of
-- entering it.
local function file_info(buf)
  return vim.api.nvim_buf_call(buf, function() return vim.fn.execute('file') end)
end

t.test('a document buffer is named, loaded, empty and not marked "not edited"', function()
  local name = vim.fn.tempname() .. '-a.txt'
  vim.fn.writefile({ 'on disk' }, name) -- not read: Eclipse pushes its own text
  local buf, named = unpack(t.run('CREATE_DOCUMENT_BUFFER', name))
  t.eq(true, named)
  t.eq(vim.fn.resolve(vim.fn.fnamemodify(name, ':p')), vim.fn.resolve(vim.api.nvim_buf_get_name(buf)))
  t.eq(true, vim.api.nvim_buf_is_loaded(buf))
  t.eq({ '' }, vim.api.nvim_buf_get_lines(buf, 0, -1, false), 'the file was not read')
  t.eq(true, vim.bo[buf].buflisted)
  t.eq(false, vim.bo[buf].swapfile)
  t.eq('hide', vim.bo[buf].bufhidden)
  vim.bo[buf].buftype = 'acwrite' -- as DOCUMENT_BUFFER sets it
  t.eq(nil, file_info(buf):find('[Not edited]', 1, true), file_info(buf))
end)

t.test('creating a document buffer neither reads the file nor enters the buffer', function()
  local fired = {}
  vim.api.nvim_create_autocmd({ 'BufReadCmd', 'BufReadPre', 'BufReadPost', 'BufEnter', 'BufWinEnter', 'FileType' }, {
    callback = function(ev) table.insert(fired, ev.event) end,
  })
  t.run('CREATE_DOCUMENT_BUFFER', vim.fn.tempname() .. '-b.java')
  t.eq({}, fired)
end)

t.test('a name another document\'s buffer has already gives a new, unnamed buffer', function()
  local name = vim.fn.tempname() .. '-c.txt'
  local existing = unpack(t.run('CREATE_DOCUMENT_BUFFER', name))
  vim.bo[existing].buftype = 'acwrite' -- a document now (DOCUMENT_BUFFER)
  local buf, named = unpack(t.run('CREATE_DOCUMENT_BUFFER', name))
  t.eq(false, named)
  t.ok(buf ~= existing, 'a buffer of its own')
  t.eq('', vim.api.nvim_buf_get_name(buf))
end)

t.test('the file\'s buffer kept from before is reused, as :e reuses the one :bd kept', function()
  local name = vim.fn.tempname() .. '-d.txt'
  vim.fn.writefile({ 'on disk' }, name)
  vim.cmd('edit ' .. vim.fn.fnameescape(name))
  local kept = vim.api.nvim_get_current_buf()
  vim.api.nvim_buf_set_mark(kept, 'a', 1, 0, {})
  vim.cmd('enew | bdelete ' .. kept) -- unloaded and unlisted, number and marks kept
  local buf, named = unpack(t.run('CREATE_DOCUMENT_BUFFER', name))
  t.eq({ kept, true }, { buf, named }, 'the same number')
  t.eq(true, vim.api.nvim_buf_is_loaded(buf))
  t.eq(true, vim.bo[buf].buflisted)
  t.eq('nofile', vim.bo[buf].buftype)
  t.eq({ 1, 0 }, vim.api.nvim_buf_get_mark(buf, 'a'))
end)

t.test('a buffer Neovim loaded from disk itself (:b, Ctrl-^) is reused as it is', function()
  local name = vim.fn.tempname() .. '-e.txt'
  vim.fn.writefile({ 'on disk' }, name)
  vim.cmd('edit ' .. vim.fn.fnameescape(name))
  local loaded = vim.api.nvim_get_current_buf()
  local buf = unpack(t.run('CREATE_DOCUMENT_BUFFER', name))
  t.eq(loaded, buf)
  t.eq(false, vim.bo[buf].swapfile)
  t.eq({ 'on disk' }, vim.api.nvim_buf_get_lines(buf, 0, -1, false), 'Eclipse\'s text comes next')
end)
