-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- LuaScripts.DOCUMENT_BUFFER: a document buffer is written only through Eclipse. 'buftype' acwrite
-- runs its BufWriteCmd, which asks Eclipse (the eclipse_write request) and acts on the answer
-- { error, message }; and Neovim deleting the buffer by itself tells Eclipse (eclipse_buffer_closed).
-- Eclipse's side of the request is DocumentWriteTest; one round trip through the real session stays
-- in NeovimBufferIntegrationTest (aTypedWriteAsksEclipseAndShowsTheWrittenMessage).

local t = ...

--- Eclipse's answers, by buffer number: '' saves, any other text is the error. Records each request.
local function eclipse(failures)
  local asked = {}
  t.answer('eclipse_write', function(buf, cmdarg)
    asked[#asked + 1] = { buf, cmdarg }
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

t.test('a write under another name is refused and nothing is asked or written', function()
  t.setup()
  local asked = eclipse()
  local _, name = t.document('A.txt', { 'a' })
  local other = vim.fs.dirname(name) .. '/other.txt'
  local ok, err = pcall(vim.cmd.write, other)
  t.eq(false, ok)
  t.ok(tostring(err):find('only under its own name', 1, true), tostring(err))
  t.eq({}, asked)
  t.eq(0, vim.fn.filereadable(other))
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
