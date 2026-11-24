-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- Neovim's own highlights that the editor shows besides search matches
-- (docs/design/SEARCH_HIGHLIGHTING.md, "The yank flash and window matches"): the flash after a
-- yank, and the window's matches. Run once at start, after the user's config; returns `false` (and
-- installs nothing) when the config sets `vim.g.neovim4eclipse_highlights = 0` (or `false`).
--
-- What.
-- - The yank flash: `vim.hl.on_yank`, which a user's `TextYankPost` autocommand calls
--   (kickstart.nvim has one), puts an extmark in the namespace `nvim.hlyank` over the yanked text,
--   group `IncSearch` unless the user names another, priority 200, and removes it after its timeout
--   (150 ms by default). Neovim never flashes on its own.
-- - The window's matches (`getmatches()`): `:match`, `:2match`, `matchadd()` with a pattern,
--   `matchaddpos()` with positions. Example: `:match Error /\s\+$/` marks trailing white space. A
--   pattern is found with `matchbufline()` after `\C` and `\m`, since a match ignores
--   `'ignorecase'` and `'magic'`, which `matchbufline()` follows.
-- - Not the matches of matchparen (group `MatchParen`), on by default: Eclipse's code editors mark
--   the matching bracket themselves, and two marks would clash.
-- - The marks of a jump plug-in (flash, leap, hop, mini.jump2d): see "Jump labels" below.
--
-- Jump labels. A jump plug-in shows where a key would take the cursor with extmarks: a highlight
-- over each match, a text color over everything else (the backdrop), and at each target a mark with
-- overlay virtual text, a letter drawn over the character there (`:help nvim_buf_set_extmark()`,
-- `virt_text_pos`). They are told from other plug-ins' marks by the name of their highlight group:
-- it starts with one of the prefixes this script is given as its argument
-- (`util/JumpLabels.BUILT_IN_PREFIXES`, the one place that names them) or with one the user lists
-- in `vim.g.neovim4eclipse_jump_label_group_prefixes`. Example: flash's label is `{ 'a',
-- 'FlashLabel' }`, which starts with `Flash`. By group and not by namespace, because a namespace
-- need not have a name (leap's has none). The user's list is read once, here; it adds to the
-- built-in prefixes and cannot take one away. An entry that is not a non-empty string is left out,
-- with a warning.
--
-- For these groups a text color alone is enough (the backdrop sets no background), and the range is
-- then sent with background -1: the editor's text takes the color while the marks are there. The
-- yank flash and window matches keep needing a background.
--
-- Colors. The group's own, its links followed (`nvim_get_hl` with `link = false`): the background,
-- and the text color when the group sets one, since Neovim pairs them (the default `IncSearch` is a
-- light yellow with dark text, which a dark Eclipse theme's light text could not be read on). A
-- group without a 24-bit background (a colorscheme for terminals only) is not shown. The colors are
-- read at each update, not kept: `:highlight Mine guibg=#112233` after the `:match` changes no
-- colorscheme, but redraws, and the new color is sent as a change.
--
-- Which lines, and when. As LuaScripts.SEARCH: the window's visible lines and the editor's
-- (`M.set_view`, sent with LuaScripts.SEARCH_VIEW), worked out in work that a decoration provider's
-- `on_win` schedules at each redraw (a flash's extmark comes and goes, `:match` redraws), and sent
-- only when they changed; with highlights, the buffer's change count counts as part of them, so
-- they are sent again after an edit, which may have made the editor drop one it touched.
-- `M.resend()` (LuaScripts.SEARCH_RESEND) sends them in any case.
--
-- Sent: `eclipse_highlights(buf, ranges, win, labels)`, where `ranges` is a flat list of six
-- numbers per highlight, lowest priority first: start line (1-based), start byte column (0-based),
-- end line, end byte column (0-based, exclusive), background and text color (`0xRRGGBB`; -1 for one
-- the group does not set, and never both). Example: the flash of `yy` on line 2, `"abc"`, with the
-- default `IncSearch`: `{2, 0, 2, 3, 0xFCE094, 0x07080D}`. A flash over whole lines ends at the end
-- of its last line, not at the start of the next one: a line-wise yank of the last line would name
-- a line the document does not have.
--
-- `labels` is a list with one entry per piece of overlay text: `{ line, byte column, cells, text,
-- background, text color, bold }`. `cells` is how many screen cells after the mark's column the
-- piece starts (0 for the first; a label of two pieces in two groups has its second at 1). Example:
-- flash's `a` over the `l` of `alpha` on line 1: `{ 1, 1, 0, 'a', 0x6B5300, 0xEEF1F8, false }`.
--
-- Sent as LuaScripts.HIGHLIGHTS (core/LuaModules runs this file as a chunk of its own).

local builtin_prefixes = ...
local enabled = vim.g.neovim4eclipse_highlights
if enabled == 0 or enabled == false then
  return false
end
local M = {}
-- What each window was last sent, as text, so that only a change is sent.
local sent = {}
-- The lines each window's editor shows, { top, bottom }, as Eclipse last reported them.
local views = {}

local function notify(...)
  local chan = vim.g.neovim4eclipse_channel
  if chan then
    vim.rpcnotify(chan, ...)
  end
end

local ns = vim.api.nvim_create_namespace('neovim4eclipse_highlights')
-- Neovim's own namespace for the flash: creating it again gives the same id.
local yank_ns = vim.api.nvim_create_namespace('nvim.hlyank')

local group = vim.api.nvim_create_augroup('neovim4eclipse_highlights', { clear = true })
vim.api.nvim_create_autocmd({ 'WinEnter', 'BufWinEnter' }, {
  group = group,
  callback = function()
    sent[vim.api.nvim_get_current_win()] = nil
  end,
})
vim.api.nvim_create_autocmd('WinClosed', {
  group = group,
  callback = function(ev)
    local win = tonumber(ev.match)
    sent[win] = nil
    views[win] = nil
  end,
})
-- A group's colors, { bg, fg }, or false for a group without a 24-bit background; looked up
-- once per update in colors, a table of that update's own.
local function color(colors, name)
  if colors[name] == nil then
    local ok, hl = pcall(vim.api.nvim_get_hl, 0, { name = name, link = false })
    if ok and hl.bg then
      colors[name] = { hl.bg, hl.fg or -1 }
    else
      colors[name] = false
    end
  end
  return colors[name]
end

-- The prefixes a jump plug-in's highlight groups start with: the built-in ones first,
-- then the user's own. Made once; neither list is changed.
local prefixes = {}
do
  local known = {}
  local function take(list, whose)
    for _, p in ipairs(type(list) == 'table' and list or {}) do
      if type(p) == 'string' and p ~= '' then
        if not known[p] then
          known[p] = true
          prefixes[#prefixes + 1] = p
        end
      elseif whose then
        vim.schedule(function()
          vim.notify('neovim4eclipse: ' .. whose .. ': left out ' .. vim.inspect(p)
            .. ' (an entry is a non-empty string, such as "Sneak")', vim.log.levels.WARN)
        end)
      end
    end
  end
  take(builtin_prefixes)
  local own = vim.g.neovim4eclipse_jump_label_group_prefixes
  if own ~= nil and type(own) ~= 'table' then
    vim.schedule(function()
      vim.notify('neovim4eclipse: vim.g.neovim4eclipse_jump_label_group_prefixes is not a list: '
        .. vim.inspect(own), vim.log.levels.WARN)
    end)
  end
  take(own, 'vim.g.neovim4eclipse_jump_label_group_prefixes')
end

-- Whether a highlight group is a jump plug-in's: its name starts with one of the prefixes.
local function is_jump_group(name)
  if type(name) ~= 'string' then
    return false
  end
  for _, p in ipairs(prefixes) do
    if name:sub(1, #p) == p then
      return true
    end
  end
  return false
end

-- A jump group's colors, { bg, fg, bold }, each -1 when the group does not set it, or
-- false when it sets neither color. Kept in colors under a key of its own, since the
-- same group asked through color() must have a background.
local function jump_color(colors, name)
  local key = '\0' .. name
  if colors[key] == nil then
    local ok, hl = pcall(vim.api.nvim_get_hl, 0, { name = name, link = false })
    if ok and (hl.bg or hl.fg) then
      colors[key] = { hl.bg or -1, hl.fg or -1, hl.bold == true }
    else
      colors[key] = false
    end
  end
  return colors[key]
end

-- The highlights of win between lines top and bottom (1-based), added to out as
-- { priority, line, col, endline, endcol, bg, fg }, seen keeping each one once.
local function collect(win, buf, top, bottom, out, seen, colors, labels)
  local function add(priority, l1, c1, l2, c2, name, jump)
    if l2 < l1 or (l2 == l1 and c2 <= c1) then
      return -- no width: nothing to paint
    end
    local rgb = jump and jump_color(colors, name) or (not jump and color(colors, name))
    if not rgb then
      return
    end
    local key = table.concat({ l1, c1, l2, c2, name }, ':')
    if not seen[key] then
      seen[key] = true
      out[#out + 1] = { priority, l1, c1, l2, c2, rgb[1], rgb[2] }
    end
  end
  local marks = vim.api.nvim_buf_get_extmarks(buf, yank_ns, { top - 1, 0 }, { bottom - 1, -1 },
    { details = true, overlap = true })
  for _, m in ipairs(marks) do
    local d = m[4]
    if d.hl_group and d.end_row then
      local l2, c2 = d.end_row + 1, d.end_col
      if c2 == 0 and d.end_row > m[2] then
        -- Up to the start of the next line: the end of the line before it instead.
        l2 = d.end_row
        c2 = #(vim.api.nvim_buf_get_lines(buf, l2 - 1, l2, false)[1] or '')
      end
      add(d.priority or 4096, m[2] + 1, m[3], l2, c2, d.hl_group)
    end
  end
  if #prefixes > 0 then
    -- Every namespace's marks on these lines; a jump plug-in's are known by their group.
    local all = vim.api.nvim_buf_get_extmarks(buf, -1, { top - 1, 0 }, { bottom - 1, -1 },
      { details = true, overlap = true })
    for _, m in ipairs(all) do
      local d = m[4]
      if d.end_row and is_jump_group(d.hl_group) then
        local l2, c2 = d.end_row + 1, d.end_col
        if c2 == 0 and d.end_row > m[2] then
          l2 = d.end_row -- as for the flash: the end of the line before
          c2 = #(vim.api.nvim_buf_get_lines(buf, l2 - 1, l2, false)[1] or '')
        end
        add(d.priority or 4096, m[2] + 1, m[3], l2, c2, d.hl_group, true)
      end
      -- Not text put at a window column (virt_text_win_col): that is over no character.
      if d.virt_text and d.virt_text_pos == 'overlay' and not d.virt_text_win_col
          and m[2] + 1 >= top and m[2] + 1 <= bottom then
        local cells = 0
        for _, chunk in ipairs(d.virt_text) do
          local text, name = chunk[1], chunk[2]
          if type(name) == 'table' then
            name = name[#name] -- several groups combined: the last one wins
          end
          if type(text) == 'string' and text ~= '' then
            local rgb = is_jump_group(name) and jump_color(colors, name)
            local key = table.concat({ 'label', m[2], m[3], cells, text, tostring(name) }, ':')
            if rgb and not seen[key] then
              seen[key] = true
              labels[#labels + 1] = { m[2] + 1, m[3], cells, text, rgb[1], rgb[2], rgb[3] }
            end
            cells = cells + vim.fn.strdisplaywidth(text)
          end
        end
      end
    end
  end
  local ok, list = pcall(vim.fn.getmatches, win)
  for _, m in ipairs(ok and list or {}) do
    if m.group ~= 'MatchParen' then
      local priority = m.priority or 10
      if m.pattern then
        local pok, found = pcall(vim.fn.matchbufline, buf, '\\C\\m' .. m.pattern, top, bottom)
        for _, f in ipairs(pok and found or {}) do
          add(priority, f.lnum, f.byteidx, f.lnum, f.byteidx + #f.text, m.group)
        end
      else
        local i = 1
        while m['pos' .. i] do
          local p = m['pos' .. i]
          local lnum = p[1]
          if lnum >= top and lnum <= bottom then
            local text = vim.api.nvim_buf_get_lines(buf, lnum - 1, lnum, false)[1] or ''
            if p[2] then
              add(priority, lnum, p[2] - 1, lnum, math.min(#text, p[2] - 1 + (p[3] or 1)), m.group)
            else
              add(priority, lnum, 0, lnum, #text, m.group) -- {lnum} alone: the whole line
            end
          end
          i = i + 1
        end
      end
    end
  end
end

--- Sends win's highlights even if they did not change: the editor that shows them was just
--- attached, or Neovim mode is back on, and has none painted. Scheduled, as on_win's work is.
function M.resend(win)
  win = win or vim.api.nvim_get_current_win()
  sent[win] = nil
  vim.schedule(function()
    pcall(M.update, win)
  end)
end

--- The lines the editor of win shows (1-based): looked at along with Neovim's own visible lines.
function M.set_view(win, top, bottom)
  win = win or vim.api.nvim_get_current_win()
  views[win] = { top, bottom }
  vim.schedule(function()
    pcall(M.update, win)
  end)
end

--- Works out win's highlights and sends them if they changed. Returns the flat list sent.
function M.update(win)
  win = win or vim.api.nvim_get_current_win()
  if not vim.api.nvim_win_is_valid(win) then
    return {}
  end
  local buf = vim.api.nvim_win_get_buf(win)
  local last = vim.api.nvim_buf_line_count(buf)
  local found, seen, colors, labels = {}, {}, {}, {}
  collect(win, buf, vim.fn.line('w0', win), vim.fn.line('w$', win), found, seen, colors, labels)
  local view = views[win]
  if view then
    local top, bottom = math.max(1, view[1]), math.min(last, view[2])
    if top <= bottom then
      collect(win, buf, top, bottom, found, seen, colors, labels)
    end
  end
  -- Lowest priority first: Eclipse paints them in order, so the highest lies on top.
  table.sort(found, function(a, b)
    if a[1] ~= b[1] then
      return a[1] < b[1]
    end
    return a[2] < b[2] or (a[2] == b[2] and a[3] < b[3])
  end)
  local ranges = {}
  for _, h in ipairs(found) do
    vim.list_extend(ranges, { h[2], h[3], h[4], h[5], h[6], h[7] })
  end
  table.sort(labels, function(a, b)
    if a[1] ~= b[1] then
      return a[1] < b[1]
    end
    return a[2] < b[2] or (a[2] == b[2] and a[3] < b[3])
  end)
  local key = table.concat(ranges, ',')
  for _, l in ipairs(labels) do
    key = key .. '|' .. table.concat({ l[1], l[2], l[3], l[4], l[5], l[6], tostring(l[7]) }, ',')
  end
  if #ranges > 0 or #labels > 0 then
    key = key .. '@' .. vim.api.nvim_buf_get_changedtick(buf)
  end
  if sent[win] ~= key then
    sent[win] = key
    notify('eclipse_highlights', buf, ranges, win, labels)
  end
  return ranges, labels
end

local scheduled = false
vim.api.nvim_set_decoration_provider(ns, {
  on_win = function()
    if not scheduled then
      scheduled = true
      vim.schedule(function()
        scheduled = false
        pcall(M.update)
      end)
    end
    return false -- no on_line calls: nothing is drawn here
  end,
})
package.loaded['neovim4eclipse.highlights'] = M
return true
