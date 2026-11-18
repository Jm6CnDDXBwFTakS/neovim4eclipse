-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- Jump labels with the real plug-ins (docs/design/SEARCH_HIGHLIGHTING.md, "Jump labels"): flash,
-- leap, hop and mini.jump2d, each started as its user starts it, and LuaScripts.HIGHLIGHTS asked
-- what it would send while the plug-in waits for the label's key. What these tests are for: a
-- plug-in that renames its highlight groups, or draws its labels another way, fails here.
--
-- The plug-ins are not in the repository. `java releng/tools/plugins/TestPlugins.java fetch`
-- unpacks the commits pinned in releng/test-plugins.properties into .tool-cache/test-plugins/; a
-- plug-in that is not there, or of another commit, skips its test with the reason.
--
-- How a test looks at the labels: each plug-in reads its keys with vim.fn.getcharstr(). The test
-- puts its own function there, which hands out the keys one by one and, when asked for the key
-- after them (the label's), first notes what HIGHLIGHTS sends and then answers Esc. So nothing
-- waits and nothing depends on time.

local t = ...

local plugin = t.pinned_plugin

--- Starts HIGHLIGHTS as the session does, loads the plug-in from dir, and fills a buffer.
local function start(dir)
  t.setup()
  t.eq(true, t.run('HIGHLIGHTS', t.jump_label_prefixes))
  vim.o.termguicolors = true
  vim.opt.runtimepath:prepend(dir)
  vim.cmd('enew!')
  vim.api.nvim_buf_set_lines(0, 0, -1, false, { 'alpha beta gamma alto', 'delta alarm lambda', 'omega pal' })
  vim.api.nvim_win_set_cursor(0, { 1, 0 })
  t.forget()
  return package.loaded['neovim4eclipse.highlights']
end

--- Runs jump() with keys answered to the plug-in's getcharstr() one by one; at the read after
--- the last one, what HIGHLIGHTS sends is noted and Esc is answered. Returns ranges, labels.
local function while_waiting(m, keys, jump)
  local real = vim.fn.getcharstr
  local ranges, labels
  local asked = 0
  vim.fn.getcharstr = function()
    asked = asked + 1
    if keys[asked] then
      return keys[asked]
    end
    if not ranges then
      ranges, labels = m.update()
    end
    return '\27'
  end
  local ok, err = pcall(jump)
  vim.fn.getcharstr = real
  t.ok(ok, 'the plug-in ran: ' .. tostring(err))
  t.ok(ranges ~= nil, 'the plug-in waited for a key after ' .. #keys .. ' key(s)')
  return ranges, labels
end

--- What every plug-in must show: labels over the text, each one character on a line of the
--- buffer, with a color; and none left once the plug-in is done.
local function check(m, ranges, labels, want)
  t.ok(#labels >= want.labels, 'at least ' .. want.labels .. ' labels, got ' .. #labels)
  for _, l in ipairs(labels) do
    t.ok(l[1] >= 1 and l[1] <= 3 and l[2] >= 0 and #l[4] >= 1, 'a label on a line of the buffer: ' .. vim.inspect(l))
    t.ok(l[5] >= 0 or l[6] >= 0, 'a label has a color: ' .. vim.inspect(l))
  end
  if want.backdrop then
    local dimmed = false
    for i = 1, #ranges, 6 do
      dimmed = dimmed or (ranges[i + 4] == -1 and ranges[i + 5] >= 0)
    end
    t.ok(dimmed, 'the backdrop is sent: a range with a text color and no background')
  end
  local after_ranges, after_labels = m.update()
  t.eq({ {}, {} }, { after_ranges, after_labels }, 'nothing is left when the plug-in is done')
end

t.test('flash: labels over the matches of the typed character, the matches, the backdrop', function()
  local m = start(plugin('flash'))
  require('flash').setup({})
  local ranges, labels = while_waiting(m, { 'a' }, function()
    require('flash').jump()
  end)
  check(m, ranges, labels, { labels = 5, backdrop = true })
  local match = false
  for i = 1, #ranges, 6 do
    match = match or ranges[i + 4] >= 0
  end
  t.ok(match, 'the matches have a background')
end)

t.test('leap: labels after two characters', function()
  local m = start(plugin('leap'))
  local ranges, labels = while_waiting(m, { 'a', 'l' }, function()
    require('leap').leap({})
  end)
  check(m, ranges, labels, { labels = 2 })
end)

t.test('hop: a label at each word, the rest dimmed', function()
  local m = start(plugin('hop'))
  require('hop').setup({})
  local ranges, labels = while_waiting(m, {}, function()
    require('hop').hint_words()
  end)
  check(m, ranges, labels, { labels = 8, backdrop = true })
end)

t.test('mini.jump2d: a label at each spot', function()
  local m = start(plugin('mini.jump2d'))
  require('mini.jump2d').setup({})
  local ranges, labels = while_waiting(m, {}, function()
    require('mini.jump2d').start()
  end)
  check(m, ranges, labels, { labels = 8 })
end)
