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
  t.run('LOAD_WITHOUT_UNDO', { 'one', 'two' })
  t.eq({ 'one', 'two' }, lines())
  t.eq(false, vim.bo.modified)
  t.eq(50, vim.bo.undolevels, 'the buffer\'s own value is back')
  t.type('u')
  t.eq({ 'one', 'two' }, lines(), 'u undoes nothing')
end)

t.test('a load also clears the history before it, but a later change is undone as usual', function()
  vim.api.nvim_buf_set_lines(0, 0, -1, false, { 'old' })
  t.run('LOAD_WITHOUT_UNDO', { 'loaded' })
  t.type('dd')
  t.type('u')
  t.eq({ 'loaded' }, lines())
  t.type('u')
  t.eq({ 'loaded' }, lines(), 'the text from before the load does not come back')
end)

t.test('the global undolevels stays global after a load', function()
  t.eq(-123456, vim.bo.undolevels, 'Neovim\'s "use the global value"')
  t.run('LOAD_WITHOUT_UNDO', { 'x' })
  t.eq(-123456, vim.bo.undolevels)
end)

t.test('a load keeps the marks on their lines (a reused buffer, BUFFER_NUMBERS.md X10)', function()
  vim.api.nvim_buf_set_lines(0, 0, -1, false, { '' }) -- reloaded: one empty line, marks from before
  vim.api.nvim_buf_set_lines(0, 0, -1, false, { '1', '2', '3' })
  vim.api.nvim_buf_set_mark(0, 'a', 2, 0, {})
  vim.cmd('lockmarks call nvim_buf_set_lines(0, 0, -1, v:false, [""])')
  t.run('LOAD_WITHOUT_UNDO', { 'one', 'two', 'three', 'four' })
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
  local buf, named, reused = unpack(t.run('CREATE_DOCUMENT_BUFFER', name))
  t.eq(true, named)
  t.eq(false, reused, 'a new buffer')
  t.same_file(name, vim.api.nvim_buf_get_name(buf))
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
  local buf, named, reused = unpack(t.run('CREATE_DOCUMENT_BUFFER', name))
  t.eq({ kept, true, true }, { buf, named, reused }, 'the same number, reused')
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

-- GO_TO_LAST_POSITION: the first push into a reused buffer puts the cursor where its window left it.
t.test('the cursor goes to the buffer\'s last position, if it has one inside the text', function()
  vim.api.nvim_buf_set_lines(0, 0, -1, false, { '1', '2', '3' })
  vim.api.nvim_buf_set_mark(0, '"', 3, 0, {})
  t.eq(false, t.run('GO_TO_LAST_POSITION', 'false'), 'a buffer not reused: the caret\'s place')
  t.eq(true, t.run('GO_TO_LAST_POSITION', 'true'))
  t.eq({ 3, 0 }, vim.api.nvim_win_get_cursor(0))
  -- The file got shorter while it was closed: the mark stays on line 3 of a one-line text.
  vim.cmd('lockmarks call nvim_buf_set_lines(0, 0, -1, v:false, ["only"])')
  t.eq(false, t.run('GO_TO_LAST_POSITION', 'true'), 'past the end: the caret\'s place instead')
end)

-- SET_DOCUMENT_LINES: a document's buffer made equal to the document, only the lines that differ
-- replaced, with the echo marked by two notifications (see its Java comment).

t.test('a document write replaces only the lines that differ, between the two echo notifications', function()
  local buf = vim.api.nvim_get_current_buf()
  vim.api.nvim_buf_set_lines(buf, 0, -1, false, { 'one', 'two', 'three' })
  vim.api.nvim_buf_set_mark(buf, 'a', 3, 0, {})
  t.eq('changed', t.run('SET_DOCUMENT_LINES', tostring(buf), { 'one', 'TWO', 'three' }))
  t.eq({ 'one', 'TWO', 'three' }, lines())
  t.eq({ 3, 0 }, vim.api.nvim_buf_get_mark(buf, 'a'), 'an unchanged line keeps its mark')
  t.eq({ { buf, true }, { buf, false } }, t.sent('eclipse_echo'))
end)

t.test('a document write of equal lines sets nothing and marks no echo', function()
  local buf = vim.api.nvim_get_current_buf()
  vim.api.nvim_buf_set_lines(buf, 0, -1, false, { 'same' })
  local tick = vim.api.nvim_buf_get_changedtick(buf)
  t.eq('equal', t.run('SET_DOCUMENT_LINES', tostring(buf), { 'same' }))
  t.eq(tick, vim.api.nvim_buf_get_changedtick(buf))
  t.eq({}, t.sent('eclipse_echo'))
end)

t.test('a document write adds and removes lines at the end, and an empty document is one empty line', function()
  local buf = vim.api.nvim_get_current_buf()
  vim.api.nvim_buf_set_lines(buf, 0, -1, false, { 'a' })
  t.run('SET_DOCUMENT_LINES', tostring(buf), { 'a', 'b', 'c' })
  t.eq({ 'a', 'b', 'c' }, lines())
  t.run('SET_DOCUMENT_LINES', tostring(buf), { 'a' })
  t.eq({ 'a' }, lines())
  t.run('SET_DOCUMENT_LINES', tostring(buf), {})
  t.eq({ '' }, lines())
end)

t.test('a document write to a buffer that is gone does nothing', function()
  local gone = vim.api.nvim_create_buf(false, true)
  vim.api.nvim_buf_delete(gone, { force = true })
  t.eq('gone', t.run('SET_DOCUMENT_LINES', tostring(gone), { 'x' }))
  t.eq({}, t.sent('eclipse_echo'))
end)

t.test('a document write marks its echo on every channel when the channel is not known', function()
  local buf = vim.api.nvim_get_current_buf()
  vim.g.neovim4eclipse_channel = nil
  t.eq('changed', t.run('SET_DOCUMENT_LINES', tostring(buf), { 'x' }))
  t.eq({ 'x' }, lines())
  t.eq({ { buf, true }, { buf, false } }, t.sent('eclipse_echo'))
end)

-- SET_LINES_MARKED: a push into the attached buffer, read, compared and set in one script, with its
-- line events marked as its echo.
t.test('a push into the attached buffer sets only the lines that differ and marks its echo', function()
  local buf = vim.api.nvim_get_current_buf()
  vim.api.nvim_buf_set_lines(buf, 0, -1, false, { 'alpha', 'beta', 'gamma' })
  vim.api.nvim_buf_set_mark(buf, 'a', 3, 0, {})
  t.eq({ 'changed', { 'alpha', 'beta', 'gamma' } }, t.run('SET_LINES_MARKED', buf, 0, 0, { 'beta', 'gamma' }, true))
  t.eq({ 'beta', 'gamma' }, lines())
  t.eq({ 2, 0 }, vim.api.nvim_buf_get_mark(buf, 'a'), 'an unchanged line keeps its mark')
  t.eq({ { buf, true }, { buf, false } }, t.sent('eclipse_echo'))
end)

t.test('a push of equal lines sets nothing and marks no echo', function()
  local buf = vim.api.nvim_get_current_buf()
  vim.api.nvim_buf_set_lines(buf, 0, -1, false, { 'same' })
  local tick = vim.api.nvim_buf_get_changedtick(buf)
  t.eq({ 'equal' }, t.run('SET_LINES_MARKED', buf, 0, 0, { 'same' }, false))
  t.eq(tick, vim.api.nvim_buf_get_changedtick(buf))
  t.eq({}, t.sent('eclipse_echo'))
end)

t.test('a push of a part changes only that part, and one outside the buffer nothing', function()
  local buf = vim.api.nvim_get_current_buf()
  vim.api.nvim_buf_set_lines(buf, 0, -1, false, { 'a', 'b', 'c' })
  t.eq({ 'changed' }, t.run('SET_LINES_MARKED', buf, 1, 1, { 'B', 'b2' }, false))
  t.eq({ 'a', 'B', 'b2', 'c' }, lines())
  t.eq({ 'outside' }, t.run('SET_LINES_MARKED', buf, 3, 2, { 'x' }, false))
  t.eq({ 'a', 'B', 'b2', 'c' }, lines())
end)

t.test('a whole push of equal lines answers the old lines, for the repair check', function()
  local buf = vim.api.nvim_get_current_buf()
  vim.api.nvim_buf_set_lines(buf, 0, -1, false, { 'a', 'b' })
  t.eq({ 'equal', { 'a', 'b' } }, t.run('SET_LINES_MARKED', buf, 0, 0, { 'a', 'b' }, true))
end)

t.test('a push of a part deletes its lines, and one of an empty part inserts', function()
  local buf = vim.api.nvim_get_current_buf()
  vim.api.nvim_buf_set_lines(buf, 0, -1, false, { 'a', 'b', 'c', 'd' })
  t.eq({ 'changed' }, t.run('SET_LINES_MARKED', buf, 1, 1, {}, false)) -- b and c go
  t.eq({ 'a', 'd' }, lines())
  t.eq({ 'changed' }, t.run('SET_LINES_MARKED', buf, 1, 1, { 'x' }, false)) -- first + keep == count
  t.eq({ 'a', 'x', 'd' }, lines())
end)

t.test('a failed push into the attached buffer still ends its echo, then fails', function()
  local buf = vim.api.nvim_get_current_buf()
  vim.api.nvim_buf_set_lines(buf, 0, -1, false, { 'one' })
  local ok = pcall(t.run, 'SET_LINES_MARKED', buf, 0, 0, { 'two\nlines' }, false) -- a line break in a line
  t.eq(false, ok)
  t.eq({ 'one' }, lines())
  t.eq({ { buf, true }, { buf, false } }, t.sent('eclipse_echo'))
end)

-- SPLIT_LINE_BREAKS: a line holding carriage returns becomes the lines Eclipse's document shows it as
-- (the parts come from the document), and the cursor goes onto the part it was in (sync/SplitLines).
t.test('a carriage return inside a line splits it, and the cursor follows its part', function()
  local buf, win = vim.api.nvim_get_current_buf(), vim.api.nvim_get_current_win()
  vim.api.nvim_buf_set_lines(buf, 0, -1, true, { 'alpha', 'beta1\r2x', 'x\ry\rz', 'gamma' })
  vim.api.nvim_win_set_cursor(win, { 2, 7 }) -- on the x after the carriage return
  local cursor = t.run('SPLIT_LINE_BREAKS', tostring(buf), tostring(win),
    { { 2, { 'beta1', '2x' } }, { 3, { 'x', 'y', 'z' } } })
  t.eq({ 3, 1 }, cursor)
  t.eq({ 'alpha', 'beta1', '2x', 'x', 'y', 'z', 'gamma' }, lines())
  t.eq({ 3, 1 }, vim.api.nvim_win_get_cursor(win))
end)

t.test('the lines below a split carry the cursor down', function()
  local buf, win = vim.api.nvim_get_current_buf(), vim.api.nvim_get_current_win()
  vim.api.nvim_buf_set_lines(buf, 0, -1, true, { '\ra', 'b' })
  vim.api.nvim_win_set_cursor(win, { 2, 0 })
  t.eq({ 3, 0 }, t.run('SPLIT_LINE_BREAKS', tostring(buf), tostring(win), { { 1, { '', 'a' } } }))
  t.eq({ '', 'a', 'b' }, lines())
end)

t.test('a carriage return Eclipse keeps in a CRLF stays at the end of the last part', function()
  local buf, win = vim.api.nvim_get_current_buf(), vim.api.nvim_get_current_win()
  vim.api.nvim_buf_set_lines(buf, 0, -1, true, { 'x\ra\r' })
  t.run('SPLIT_LINE_BREAKS', tostring(buf), tostring(win), { { 1, { 'x', 'a' } } })
  t.eq({ 'x', 'a\r' }, lines())
end)

t.test('a line a key changed since the parts were read is left alone', function()
  local buf, win = vim.api.nvim_get_current_buf(), vim.api.nvim_get_current_win()
  vim.api.nvim_buf_set_lines(buf, 0, -1, true, { 'a\rbc' })
  t.run('SPLIT_LINE_BREAKS', tostring(buf), tostring(win), { { 1, { 'a', 'b' } }, { 5, { 'p', 'q' } } })
  t.eq({ 'a\rbc' }, lines())
end)

-- The split must not be an undo step of its own: one u after the insert puts back the line from
-- before it, not the "x1\r2" that Eclipse would split again (a u that never gets past the split).
-- Example: A, Ctrl-R Ctrl-R a (the register holds "1\r2"), Esc, u. During the insert Neovim keeps
-- every change in the insert's undo step anyway; the split usually arrives after the Esc, though
-- (it is queued behind the keys), when the insert's step is closed.
t.test('one u undoes an insert together with the split made during it', function()
  local buf, win = vim.api.nvim_get_current_buf(), vim.api.nvim_get_current_win()
  vim.bo.undolevels = 50
  vim.api.nvim_buf_set_lines(buf, 0, -1, true, { 'x' }) -- an undo step before the insert
  vim.fn.setreg('a', '1\r2')
  t.during('A<C-R><C-R>a', function()
    t.run('SPLIT_LINE_BREAKS', tostring(buf), tostring(win), { { 1, { 'x1', '2' } } })
  end)
  t.eq({ 'x1', '2' }, lines())
  t.type('u')
  t.eq({ 'x' }, lines())
end)

t.test('one u undoes an insert together with the split that arrived after its Esc', function()
  local buf, win = vim.api.nvim_get_current_buf(), vim.api.nvim_get_current_win()
  vim.bo.undolevels = 50
  vim.api.nvim_buf_set_lines(buf, 0, -1, true, { 'x' })
  vim.fn.setreg('a', '1\r2')
  t.type('A<C-R><C-R>a<Esc>')
  t.run('SPLIT_LINE_BREAKS', tostring(buf), tostring(win), { { 1, { 'x1', '2' } } })
  t.eq({ 'x1', '2' }, lines())
  t.type('u')
  t.eq({ 'x' }, lines())
end)

-- Another buffer than the window's (the editor that asked is no longer the attached one): the split
-- and its undo join happen in that buffer, and the window's cursor is not touched.
t.test('a buffer not shown in the window is split and joined to its own last change', function()
  local win, other = vim.api.nvim_get_current_win(), vim.api.nvim_get_current_buf()
  vim.bo[other].undolevels = 50
  vim.api.nvim_buf_set_lines(other, 0, -1, true, { 'x' })
  vim.fn.setreg('a', '1\r2')
  t.type('A<C-R><C-R>a<Esc>') -- typed in one editor, which the user then leaves for another
  local shown = vim.api.nvim_create_buf(true, false)
  vim.api.nvim_win_set_buf(win, shown)
  vim.api.nvim_buf_set_lines(shown, 0, -1, true, { 'shown' })
  vim.api.nvim_win_set_cursor(win, { 1, 3 })
  t.eq(nil, t.run('SPLIT_LINE_BREAKS', tostring(other), tostring(win), { { 1, { 'x1', '2' } } }))
  t.eq({ 'x1', '2' }, vim.api.nvim_buf_get_lines(other, 0, -1, true))
  t.eq({ 1, 3 }, vim.api.nvim_win_get_cursor(win))
  t.eq(shown, vim.api.nvim_win_get_buf(win))
  vim.api.nvim_buf_call(other, function() vim.cmd('silent undo') end)
  t.eq({ 'x' }, vim.api.nvim_buf_get_lines(other, 0, -1, true), 'the split went with the change')
end)

-- Outside an insert, after an undo, there is no change to join (Vim's E790): the split is then a
-- step of its own, and the script does not fail.
t.test('a split right after an undo still splits', function()
  local buf, win = vim.api.nvim_get_current_buf(), vim.api.nvim_get_current_win()
  vim.bo.undolevels = 50
  vim.api.nvim_buf_set_lines(buf, 0, -1, true, { 'a' })
  t.type('ox<Esc>u')
  vim.api.nvim_buf_set_lines(buf, 0, -1, true, { 'p\rq' })
  t.type('ox<Esc>u') -- the last command is an undo: :undojoin would say E790
  t.run('SPLIT_LINE_BREAKS', tostring(buf), tostring(win), { { 1, { 'p', 'q' } } })
  t.eq({ 'p', 'q' }, lines())
end)
