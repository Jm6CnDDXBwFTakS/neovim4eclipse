-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- Eclipse's folds mirrored into Neovim (docs/design/FOLDING.md): LuaScripts.FOLDS (the module and
-- the opt-out), SET_FOLDS (Eclipse's regions become manual folds) and the snapshot's fold element
-- (which folds are closed). Which regions Eclipse sends and how Neovim's answer becomes "collapse
-- these, expand those" is FoldRegionsTest; keys through the session, NeovimSessionIntegrationTest.

local t = ...

-- The snapshot's fold element, by position (see the STATE_SNAPSHOT comment).
local FOLDS = 13

local function lines(n)
  local list = {}
  for i = 1, n do
    list[i] = 'line ' .. i
  end
  vim.api.nvim_buf_set_lines(0, 0, -1, false, list)
  return vim.api.nvim_get_current_buf()
end

--- Runs SET_FOLDS for the current buffer with { first, last, collapsed } triples.
local function set_folds(folds)
  local args = { tostring(vim.api.nvim_get_current_buf()) }
  for _, f in ipairs(folds) do
    vim.list_extend(args, { tostring(f[1]), tostring(f[2]), f[3] and '1' or '0' })
  end
  t.run('SET_FOLDS', unpack(args))
end

--- The closed folds Neovim shows, outermost only, as 'first-last' strings.
local function shown_closed()
  local found, line = {}, 1
  while line <= vim.fn.line('$') do
    if vim.fn.foldclosed(line) == line then
      found[#found + 1] = line .. '-' .. vim.fn.foldclosedend(line)
      line = vim.fn.foldclosedend(line) + 1
    else
      line = line + 1
    end
  end
  return found
end

local function snapshot_folds()
  return t.run('STATE_SNAPSHOT', '0', '1')[FOLDS]
end

local function enabled()
  return t.run('FOLDS')
end

-- ---- the opt-out ----

t.test('folding is on unless the config says 0 or false', function()
  t.eq(true, enabled(), 'not set')
  vim.g.neovim4eclipse_folds = 0
  t.eq(false, enabled(), '0')
  vim.g.neovim4eclipse_folds = false
  t.eq(false, enabled(), 'false')
  vim.cmd('let g:neovim4eclipse_folds = v:false')
  t.eq(false, enabled(), 'v:false')
  vim.g.neovim4eclipse_folds = 1
  t.eq(true, enabled(), '1')
end)

t.test('with folding off there is no module, and the snapshot reports no folds', function()
  vim.g.neovim4eclipse_folds = 0
  enabled()
  t.eq(nil, package.loaded['neovim4eclipse.folds'])
  lines(10)
  set_folds({ { 2, 5, true } }) -- not sent by the session then; harmless if it were
  t.eq({}, shown_closed())
  t.eq({}, snapshot_folds())
end)

-- ---- SET_FOLDS ----

t.test('nested folds and folds sharing a first line get the right levels', function()
  enabled()
  lines(20)
  -- A class 1-20, a method 3-8 with its Javadoc 3-5, a method 10-15 (FoldRegions' order).
  set_folds({ { 1, 20, false }, { 3, 8, false }, { 3, 5, false }, { 10, 15, false } })
  local levels = {}
  for line = 1, 16 do
    levels[#levels + 1] = vim.fn.foldlevel(line)
  end
  t.eq({ 1, 1, 3, 3, 3, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1 }, levels)
  t.eq({}, shown_closed(), 'all open')
  t.eq('manual', vim.wo.foldmethod)
  t.eq(0, vim.wo.foldminlines)
end)

t.test('exactly the collapsed folds are closed, inner and outer', function()
  enabled()
  lines(20)
  set_folds({ { 1, 20, false }, { 3, 8, true }, { 10, 15, true } })
  t.eq({ '3-8', '10-15' }, shown_closed())
  set_folds({ { 1, 20, true }, { 3, 8, true }, { 10, 15, false } })
  t.eq({ '1-20' }, shown_closed())
  vim.cmd('1foldopen')
  t.eq({ '3-8' }, shown_closed(), 'the inner fold was closed too, the open one stays open')
end)

t.test('a collapsed fold whose first lines are an open fold is closed on its handle line', function()
  enabled()
  lines(20)
  set_folds({ { 1, 20, true }, { 1, 8, false } })
  t.eq({ '1-20' }, shown_closed())
  vim.cmd('1foldopen')
  t.eq({}, shown_closed(), 'the inner fold is open')
end)

t.test('a collapsed fold inside a collapsed fold sharing its first line', function()
  enabled()
  lines(12)
  set_folds({ { 2, 10, true }, { 2, 4, true } })
  t.eq({ '2-10' }, shown_closed())
  vim.cmd('normal! 2Gzo')
  t.eq({ '2-4' }, shown_closed())
end)

t.test('the cursor and the view are kept', function()
  enabled()
  lines(100)
  vim.fn.winrestview({ topline = 40, lnum = 50, col = 2 })
  set_folds({ { 60, 70, true } })
  local view = vim.fn.winsaveview()
  t.eq({ 40, 50, 2 }, { view.topline, view.lnum, view.col })
end)

t.test('folds past the end of the buffer are cut or left out', function()
  enabled()
  lines(10)
  set_folds({ { 2, 30, true }, { 12, 14, true } })
  t.eq({ '2-10' }, shown_closed())
  t.eq({ 0 }, snapshot_folds())
end)

t.test('a resend replaces the folds', function()
  enabled()
  lines(20)
  set_folds({ { 2, 5, true } })
  set_folds({ { 8, 12, true } })
  t.eq({ '8-12' }, shown_closed())
  t.eq(0, vim.fn.foldlevel(3), 'the old fold is gone')
end)

t.test('an empty list turns folding off in the window and clears the marks and the flag', function()
  enabled()
  local buf = lines(20)
  set_folds({ { 2, 5, true } })
  t.eq(true, vim.b.neovim4eclipse_folds)
  set_folds({})
  t.eq(false, vim.wo.foldenable)
  t.eq(nil, vim.b.neovim4eclipse_folds)
  t.eq({}, vim.api.nvim_buf_get_extmarks(buf, vim.api.nvim_create_namespace('neovim4eclipse_folds'), 0, -1, {}))
  t.eq({}, snapshot_folds())
end)

t.test('a global foldmethod=indent is replaced in the window only, and an empty list hides it', function()
  enabled()
  vim.o.shiftwidth = 4
  vim.o.foldmethod = 'indent'
  vim.api.nvim_buf_set_lines(0, 0, -1, false, { 'class A {', '    int x;', '    int y;', '}' })
  set_folds({})
  t.eq({}, shown_closed(), 'nofoldenable: the indent fold hides nothing')
  set_folds({ { 1, 4, false } })
  t.eq('manual', vim.wo.foldmethod)
  t.eq('indent', vim.go.foldmethod, 'the global value is the user\'s')
end)

t.test('a buffer that is not current is left alone', function()
  enabled()
  local other = vim.api.nvim_create_buf(true, false)
  vim.api.nvim_buf_set_lines(other, 0, -1, false, { 'a', 'b', 'c' })
  lines(10)
  t.run('SET_FOLDS', tostring(other), '1', '3', '1')
  t.eq(nil, vim.b[other].neovim4eclipse_folds)
end)

-- ---- the snapshot's fold element ----

t.test('the snapshot lists the outermost closed folds by their index', function()
  enabled()
  lines(20)
  set_folds({ { 1, 20, false }, { 3, 8, true }, { 10, 15, false } })
  t.eq({ 1 }, snapshot_folds())
  t.type('10Gzc')
  t.eq({ 1, 2 }, snapshot_folds())
  t.type('zM')
  t.eq({ 0 }, snapshot_folds(), 'inside the closed class nothing is listed')
  t.type('zR')
  t.eq({}, snapshot_folds())
end)

t.test('two folds starting on one line are told apart', function()
  enabled()
  lines(12)
  set_folds({ { 2, 10, false }, { 2, 4, false } })
  t.type('2Gzc')
  t.eq({ 1 }, snapshot_folds(), 'zc closes the inner one first')
  t.type('zc')
  t.eq({ 0 }, snapshot_folds())
end)

t.test('folds move with edits above them', function()
  enabled()
  lines(20)
  set_folds({ { 5, 8, true } })
  vim.api.nvim_buf_set_lines(0, 0, 0, false, { 'new 1', 'new 2' })
  t.eq({ '7-10' }, shown_closed())
  t.eq({ 0 }, snapshot_folds())
end)

t.test('zn reports nothing closed and zi brings the same folds back', function()
  enabled()
  lines(20)
  set_folds({ { 1, 20, false }, { 3, 8, true }, { 10, 15, true } })
  t.type('zn')
  t.eq({}, snapshot_folds())
  t.type('zi')
  t.eq({ 1, 2 }, snapshot_folds())
end)

t.test('a resend during zn keeps Neovim\'s states for zi', function()
  enabled()
  lines(30)
  set_folds({ { 1, 20, false }, { 3, 8, true }, { 10, 15, false } })
  t.type('zM') -- class closed (and both methods inside it)
  t.type('zn')
  -- Eclipse shows everything expanded during zn, so a reconcile resends all open, plus a new fold.
  set_folds({ { 1, 20, false }, { 3, 8, false }, { 10, 15, false }, { 22, 25, true } })
  t.eq(false, vim.wo.foldenable, 'still zn')
  t.type('zi')
  t.eq({ '1-20', '22-25' }, shown_closed(), 'the class as zM left it; the new fold as Eclipse sent it')
  t.type('1Gzo')
  t.eq({ '3-8', '10-15', '22-25' }, shown_closed(), 'zM had closed both methods')
end)

t.test('the User autocommand fires when the closed folds change', function()
  enabled()
  local buf = lines(20)
  local seen = {}
  vim.api.nvim_create_autocmd('User', {
    pattern = 'Neovim4EclipseFolds',
    callback = function(ev)
      seen[#seen + 1] = { ev.data.buf, ev.data.closed }
    end,
  })
  set_folds({ { 3, 8, false }, { 10, 15, false } })
  snapshot_folds()
  t.eq({}, seen, 'nothing closed yet: no change')
  t.type('3Gzc')
  snapshot_folds()
  snapshot_folds()
  t.eq({ { buf, { 0 } } }, seen, 'once per change, not once per key')
  t.type('zo')
  snapshot_folds()
  t.eq({ buf, {} }, seen[2])
end)

-- ---- folds by hand are refused in mirrored buffers ----

--- HOST_COMMANDS first, as the session runs it before FOLDS (the fold module registers its refusals there).
local function enabled_with_host_commands()
  t.run('HOST_COMMANDS', t.host_rules)
  return enabled()
end

local REFUSED = 'Eclipse defines the folds in this editor'

t.test('zf, zF, zd, zD and zE show an error and change no fold in a mirrored buffer', function()
  enabled_with_host_commands()
  lines(20)
  set_folds({ { 3, 8, true } })
  for _, keys in ipairs({ '1Gzfj', '1GzF', '3Gzd', '3GzD', 'zE' }) do
    vim.v.errmsg = ''
    t.type(keys)
    local key = keys:match('z%a$') or keys:match('z%a')
    t.eq(key .. ': ' .. REFUSED, vim.v.errmsg, keys)
    t.eq({ '3-8' }, shown_closed(), keys .. ' left the folds alone')
  end
end)

t.test('zf in visual mode shows the error and leaves visual mode', function()
  enabled_with_host_commands()
  lines(20)
  set_folds({ { 3, 8, false } })
  vim.v.errmsg = ''
  t.type('10GVjzf')
  t.eq('zf: ' .. REFUSED, vim.v.errmsg)
  t.eq('n', vim.api.nvim_get_mode().mode)
  t.eq(0, vim.fn.foldlevel(10), 'no fold made')
end)

t.test(':fold is refused there, with a range and abbreviated', function()
  enabled_with_host_commands()
  lines(20)
  set_folds({ { 3, 8, true } })
  for _, line in ipairs({ ':10,12fold<CR>', ':10,12fo<CR>', ':fold<CR>' }) do
    vim.v.errmsg = ''
    t.type(line)
    t.eq('fold: ' .. REFUSED, vim.v.errmsg, line)
    t.eq({ '3-8' }, shown_closed(), line)
    t.eq(0, vim.fn.foldlevel(11), line .. ' made no fold')
  end
  t.eq({}, t.sent('eclipse_command'), 'nothing reaches Eclipse')
end)

t.test(':fold after a | is refused too, and an error stops the rest of the line, as in Vim', function()
  enabled_with_host_commands()
  lines(20)
  set_folds({ { 3, 8, false } })
  t.type(':nohlsearch | 10,12fold<CR>')
  t.eq(0, vim.fn.foldlevel(11), 'refused after the |')
  vim.g.after = nil
  t.type(':10,12fold | let g:after = 2<CR>')
  t.eq(0, vim.fn.foldlevel(11), 'refused before it')
  t.eq(nil, vim.g.after, 'the error ended the line, as any error in a | chain does in Vim')
  -- Not caught, as for every host command: a | that nvim_parse_cmd does not split (:let takes the
  -- rest of the line as its argument), so the :fold after it reaches Neovim.
end)

t.test('the folding commands that open and close folds stay Neovim\'s', function()
  enabled_with_host_commands()
  lines(20)
  set_folds({ { 3, 8, false } })
  t.type(':3foldclose<CR>')
  t.eq({ '3-8' }, shown_closed())
  t.type(':3foldopen<CR>')
  t.eq({}, shown_closed())
  t.type(':3foldclose<CR>:%foldd normal! x<CR>') -- :foldd is :folddoopen (Neovim has no :folddelete)
  t.eq('ine 1', vim.fn.getline(1), 'ran on the open lines')
end)

t.test('in a buffer whose editor cannot fold, zf and :fold are Neovim\'s', function()
  enabled_with_host_commands()
  lines(20)
  set_folds({})
  vim.wo.foldenable = true -- the user turns folding on there
  t.type('1Gzfj')
  t.eq(1, vim.fn.foldlevel(1), 'zf made a fold')
  t.type(':5,7fold<CR>')
  t.eq(1, vim.fn.foldlevel(6), ':fold made one')
end)

t.test('the mappings go when the editor stops folding', function()
  enabled_with_host_commands()
  local buf = lines(20)
  set_folds({ { 3, 8, false } })
  t.ok(vim.fn.maparg('zf', 'n', false, true).buffer == 1, 'mapped while mirrored')
  set_folds({})
  t.eq({}, vim.fn.maparg('zf', 'n', false, true), 'unmapped in buffer ' .. buf)
end)

-- ---- folds sent while Neovim is in insert mode ----

t.test('SET_FOLDS in insert mode fires no ModeChanged and replaces the folds', function()
  enabled()
  lines(20)
  set_folds({ { 2, 5, false } })
  local modes = {}
  vim.api.nvim_create_autocmd('ModeChanged', {
    callback = function()
      modes[#modes + 1] = vim.v.event.old_mode .. ':' .. vim.v.event.new_mode
    end,
  })
  local during = t.during('3Go', function()
    modes = {}
    set_folds({ { 8, 12, true } })
    return { mode = vim.api.nvim_get_mode().mode, changes = vim.deepcopy(modes), closed = shown_closed(), level = vim.fn.foldlevel(3) }
  end)
  t.eq('i', during.mode, 'still in insert mode')
  t.eq({}, during.changes, 'no ModeChanged: the plug-in would take insert mode as ended')
  t.eq({ '8-12' }, during.closed)
  t.eq(0, during.level, 'the old fold is gone')
end)
