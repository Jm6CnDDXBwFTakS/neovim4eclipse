-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- LuaScripts.DOCUMENT_BUFFER: a document buffer is written only through Eclipse. 'buftype' acwrite
-- runs its BufWriteCmd, which asks Eclipse (the eclipse_write request) and acts on the answer
-- { error, message }; and Neovim deleting the buffer by itself tells Eclipse (eclipse_buffer_closed).
-- Eclipse's side of the request is DocumentWriteTest; one round trip through the real session stays
-- in NeovimBufferIntegrationTest (aTypedWriteAsksEclipseAndShowsTheWrittenMessage).

local t = ...

--- Eclipse's answers, by buffer number: '' saves, any other text is the error. Records each request:
--- { buf, cmdarg } for a save of the editor's file, { buf, cmdarg, kind, file } for a copy or a
--- :saveas, and
--- { buf, cmdarg, kind, file, first, last, bang } for lines.
local function eclipse(failures)
  local asked = {}
  t.answer('eclipse_write', function(buf, cmdarg, kind, file, first, last, bang)
    if kind == '' then
      asked[#asked + 1] = { buf, cmdarg }
    elseif kind == 'copy' or kind == 'saveas' then
      asked[#asked + 1] = { buf, cmdarg, kind, file }
    else
      asked[#asked + 1] = { buf, cmdarg, kind, file, first, last, bang }
    end
    local err = (failures or {})[buf]
    if err then
      return { err, '' }
    end
    return { '', '"' .. vim.fn.fnamemodify(vim.api.nvim_buf_get_name(buf), ':t') .. '" written' }
  end)
  return asked
end

t.test('a write asks Eclipse, passes ++p along, clears modified and shows the answer', function()
  t.setup()
  local asked = eclipse()
  local buf, name = t.document('A.txt', { 'a' })
  t.eq('acwrite', vim.bo[buf].buftype)
  t.type(':w ++p<CR>')
  t.eq({ { buf, ' ++p' } }, asked)
  t.eq(false, vim.bo[buf].modified, 'saved')
  t.ok(vim.fn.execute('messages'):find('"A.txt" written', 1, true), 'Vim\'s written message is shown')
  t.eq(0, vim.fn.filereadable(name), 'Neovim never writes the file itself')
end)

t.test(':bufdo update asks only for the modified buffers', function()
  t.setup()
  local asked = eclipse()
  local a = t.document('A.txt', { 'a' })
  local b = t.document('B.txt', { 'b' })
  vim.bo[a].modified = true
  vim.bo[b].modified = false -- as SET_MODIFIED leaves a document without unsaved changes
  vim.cmd('bufdo update')
  t.eq({ { a, '' } }, asked)
end)

t.test('a failed save fails the write: modified stays, the error is Neovim\'s, :bufdo stops', function()
  t.setup()
  local a = t.document('A.txt', { 'a' })
  local b = t.document('B.txt', { 'b' })
  local asked = eclipse({ [a] = 'Eclipse could not save A.txt: read-only' })
  vim.bo[a].modified, vim.bo[b].modified = true, true

  local ok, err = pcall(vim.cmd, 'bufdo update')
  t.eq(false, ok, 'the command failed, as a real failed write does')
  t.ok(tostring(err):find('Eclipse could not save A.txt: read-only', 1, true), tostring(err))
  t.eq({ { a, '' } }, asked, 'B is not tried after A failed')
  t.eq(true, vim.bo[a].modified, ':update tries again next time')
end)

-- Recorded as Neovim behaves (checked against the TUI too): the error aborts a command run through
-- the API (nvim_command, vim.cmd), but a typed line goes on after |.
t.test('a typed :w | cmd still runs cmd after a failed save', function()
  t.setup()
  local a = t.document('A.txt', { 'a' })
  eclipse({ [a] = 'Eclipse could not save A.txt: read-only' })
  t.type(':w | let g:after = 1<CR>')
  t.eq(1, vim.g.after)
  t.eq(true, vim.bo[a].modified)
  t.eq('Eclipse could not save A.txt: read-only', vim.v.errmsg, 'v:errmsg has the text alone')
end)

t.test('a write under another name asks Eclipse for a copy and leaves the buffer as it was', function()
  t.setup()
  local asked = eclipse()
  local buf, name = t.document('A.txt', { 'a' })
  vim.bo[buf].modified = true
  local other = vim.fs.dirname(name) .. '/other.txt'
  vim.cmd.lcd(vim.fn.fnameescape(vim.fs.dirname(name))) -- as the plug-in sets the window's folder
  t.type(':w ++p other.txt<CR>')
  t.eq({ { buf, ' ++p', 'copy', other } }, asked, 'the full name, resolved in the file\'s folder')
  t.eq(true, vim.bo[buf].modified, 'the editor\'s own changes are still unsaved, as in Vim')
  t.eq(name, vim.api.nvim_buf_get_name(buf))
  t.eq(0, vim.fn.filereadable(other), 'Neovim never writes the file itself')
  -- Neovim keeps an empty buffer of the other name (Vim's alternate file); it would keep an editor
  -- opened on other.txt later from naming its buffer, so it goes once the command is over.
  t.wait_for('no buffer left of other.txt', function() return vim.fn.bufexists(other) == 0 end)
end)

t.test('a copy over an existing file needs !, as in Vim (E13, before Eclipse is asked)', function()
  t.setup()
  local asked = eclipse()
  local buf, name = t.document('A.txt', { 'a' })
  local other = vim.fs.dirname(name) .. '/other.txt'
  vim.fn.writefile({ 'old' }, other)
  local ok, err = pcall(vim.cmd.write, other)
  t.eq(false, ok)
  t.ok(tostring(err):find('E13', 1, true), tostring(err))
  t.eq({}, asked)
  vim.cmd.write({ other, bang = true })
  t.eq({ { buf, '', 'copy', other } }, asked)
end)

t.test(':saveas asks Eclipse to save as the new file, which becomes the buffer\'s', function()
  t.setup()
  local asked = eclipse()
  local buf, name = t.document('A.txt', { 'a' })
  vim.bo[buf].modified = true
  local new = vim.fs.dirname(name) .. '/B.txt'
  vim.cmd.saveas(vim.fn.fnameescape(new))
  t.eq({ { buf, '', 'saveas', new } }, asked)
  -- Compared in one form: on Windows the buffer's name can come back with backslashes
  -- (C:\Users\RUNNER~1\...\B.txt on GitHub's runner, whose user folder has an 8.3 short name)
  -- while new has the forward slashes of vim.fs.dirname. The plug-in compares names the same way
  -- (canonical() in LuaScripts.DOCUMENT_BUFFER).
  local same = vim.fs.normalize
  t.eq(same(new), same(vim.api.nvim_buf_get_name(buf)))
  t.eq(same(new), same(vim.b[buf].neovim4eclipse_file), 'later writes compare with the new file')
  t.eq(false, vim.bo[buf].modified)
  t.ok(vim.fn.bufexists(name) == 1, 'Neovim keeps a buffer of the old name, as Vim does for :e #')
  t.wait_for('that buffer wiped', function() return vim.fn.bufexists(name) == 0 end)
  t.eq(same(new), same(vim.api.nvim_buf_get_name(buf)), 'the scheduled check leaves the new name alone')
  vim.cmd.write()
  t.eq({ buf, '' }, asked[2], 'a :w then saves the new file')
end)

t.test('a :saveas Eclipse refused puts the old name back, with Eclipse\'s error only', function()
  t.setup()
  local buf, name = t.document('A.txt', { 'a' })
  eclipse({ [buf] = 'E139: File is loaded in another buffer' })
  local ok, err = pcall(vim.cmd.saveas, vim.fn.fnameescape(vim.fs.dirname(name) .. '/B.txt'))
  t.eq(false, ok)
  t.ok(tostring(err):find('E139', 1, true), tostring(err))
  t.wait_for('the old name', function() return vim.api.nvim_buf_get_name(buf) == name end)
  t.ok(not vim.fn.execute('messages'):find('file: Eclipse', 1, true), 'no :file message on top')
end)

t.test(':file new.txt is undone: the buffer keeps the editor\'s name', function()
  t.setup()
  local asked = eclipse()
  local buf, name = t.document('A.txt', { 'a' })
  vim.cmd.file(vim.fn.fnameescape(vim.fs.dirname(name) .. '/renamed.txt'))
  t.wait_for('the old name', function() return vim.api.nvim_buf_get_name(buf) == name end)
  t.eq('file: Eclipse names this buffer after its file; use :saveas or File > Save As', vim.v.errmsg)
  t.eq(0, vim.fn.bufexists(vim.fs.dirname(name) .. '/renamed.txt'), 'no buffer left under the other name')
  vim.cmd.write()
  t.eq({ { buf, '' } }, asked, 'X1: a :w saves the editor\'s file, under its own name')
  t.ok(vim.fn.execute('file'):find('A.txt', 1, true), ':file alone still shows the file info')
end)

t.test('the same file named through a link or with other slashes is its own name', function()
  if vim.fn.has('win32') == 1 then
    return -- making a link needs rights a Windows runner may not have; the slashes are checked below
  end
  t.setup()
  local asked = eclipse()
  local buf, name = t.document('A.txt', { 'a' })
  local link = vim.fn.tempname()
  assert(vim.uv.fs_symlink(vim.fs.dirname(name), link))
  vim.cmd.write(link .. '/A.txt') -- macOS: /var/... is /private/var/...
  t.eq({ { buf, '' } }, asked)
end)

t.test('some lines to another file ask Eclipse for those lines', function()
  t.setup()
  local asked = eclipse()
  local buf, name = t.document('A.txt', { 'a', 'b', 'c', 'd' })
  local part = vim.fs.dirname(name) .. '/part.txt'
  vim.cmd('2,3write ' .. vim.fn.fnameescape(part))
  t.eq({ { buf, '', 'lines', part, 2, 3, false } }, asked)
end)

t.test('>> asks Eclipse to append, with the ! that lets it create the file', function()
  t.setup()
  local asked = eclipse()
  local buf, name = t.document('A.txt', { 'a', 'b', 'c' })
  local log = vim.fs.dirname(name) .. '/log.txt'
  vim.cmd('write >> ' .. vim.fn.fnameescape(log))
  vim.cmd('2write! >> ' .. vim.fn.fnameescape(log))
  t.eq({ { buf, '', 'append', log, 1, 3, false }, { buf, '', 'append', log, 2, 2, true } }, asked)
end)

t.test('some lines or >> to the editor\'s own file are refused: Eclipse saves it whole', function()
  t.setup()
  local asked = eclipse()
  local _, name = t.document('A.txt', { 'a', 'b', 'c' })
  for _, cmd in ipairs({ '2,3write!', 'write >> ' .. vim.fn.fnameescape(name) }) do
    local ok, err = pcall(vim.cmd, cmd)
    t.eq(false, ok, cmd)
    t.ok(tostring(err):find('Eclipse saves this file only whole; use :w', 1, true), tostring(err))
  end
  t.eq({}, asked)
end)

t.test(':w !cmd pipes the text and asks Eclipse nothing', function()
  if vim.fn.has('win32') == 1 then
    return -- no cat
  end
  t.setup()
  local asked = eclipse()
  t.document('A.txt', { 'a' })
  vim.cmd('silent w !cat >/dev/null')
  t.eq({}, asked)
end)

t.test('Neovim deleting or wiping a document buffer by itself tells Eclipse', function()
  t.setup()
  local a = t.document('A.txt')
  local b = t.document('B.txt')
  vim.cmd.bdelete(a)
  vim.cmd.bwipeout(b) -- BufDelete, then BufWipeout: told twice, and Eclipse closes the editor once
  t.eq({ { a }, { b }, { b } }, t.sent('eclipse_buffer_closed'))
end)

-- CLOSE_DOCUMENT_BUFFER: the last editor closed, the buffer is kept as Vim's :bd keeps it
-- (docs/design/BUFFER_NUMBERS.md): unloaded, unlisted, with its number and marks, for the file's next
-- editor (CREATE_DOCUMENT_BUFFER reuses it).

local function plugins_autocmds(buf)
  return #vim.api.nvim_get_autocmds({ group = 'neovim4eclipse_document', buf = buf })
end

t.test('closing a document keeps its buffer as :bd does: number, marks and last position', function()
  t.setup()
  local buf, name = t.document('Kept.txt', { '1', '2', '3' })
  vim.api.nvim_win_set_cursor(0, { 3, 0 })
  vim.api.nvim_buf_set_mark(buf, 'a', 2, 0, {})
  vim.cmd('enew') -- the plug-in leaves the buffer first (RELEASE_BUFFER)
  t.forget()
  t.run('CLOSE_DOCUMENT_BUFFER', tostring(buf))
  t.eq(true, vim.api.nvim_buf_is_valid(buf), 'kept')
  t.eq(false, vim.api.nvim_buf_is_loaded(buf), 'unloaded')
  t.eq(false, vim.bo[buf].buflisted, 'only :ls! shows it')
  t.eq({ 2, 0 }, vim.api.nvim_buf_get_mark(buf, 'a'))
  t.eq(3, vim.api.nvim_buf_get_mark(buf, '"')[1])
  t.eq(0, plugins_autocmds(buf), 'no write hook, no safety net')
  t.eq({}, t.sent('eclipse_buffer_closed'), 'Eclipse closed it: nothing to tell')
  local again = unpack(t.run('CREATE_DOCUMENT_BUFFER', name))
  t.eq(buf, again, 'the next editor gets the same buffer')
end)

t.test('a reused buffer gets the plug-in\'s autocommands once', function()
  t.setup()
  local buf = t.document('Twice.txt', { 'x' })
  local each = plugins_autocmds(buf)
  t.ok(each > 0, 'the write hook and the safety net')
  t.run('DOCUMENT_BUFFER', tostring(buf))
  t.eq(each, plugins_autocmds(buf))
end)

t.test('closing a buffer that is gone does nothing', function()
  t.run('CLOSE_DOCUMENT_BUFFER', '999')
end)
