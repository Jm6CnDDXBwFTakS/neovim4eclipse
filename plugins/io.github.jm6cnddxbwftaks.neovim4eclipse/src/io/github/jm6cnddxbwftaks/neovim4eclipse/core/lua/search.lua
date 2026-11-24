-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- Search highlighting (docs/design/SEARCH_HIGHLIGHTING.md): the matches of Neovim's search that the
-- current window shows, sent to Eclipse, which paints them behind the editor's text. Run once at
-- start, after the user's config; returns `false` (and installs nothing) when the config sets
-- `vim.g.neovim4eclipse_search_highlight = 0` (or `false`).
--
-- Which pattern. What Neovim itself highlights:
-- - while a search is typed (`/` or `?`) and `'incsearch'` is on: the text typed so far, without a
--   search offset (`/foo/e` is `foo`; after `?`, and in `:s?a?b?`, an escaped `\?` is a plain `?`,
--   as Neovim reads it: `?what\?` finds `what?`; a delimiter in a `[]` collection does not end it:
--   `/[/]x` finds `/x`); before the first character, the last search, which Neovim still shows;
-- - while a `:s`, `:g`, `:v`, `:smagic` or `:snomagic` line is typed, with `'incsearch'`: its
--   pattern (`:%s/foo/bar` gives `foo`); a range with a pattern of its own (`:/x/s/y/`) is not
--   read, and the last search shows instead;
-- - else, while `v:hlsearch` is 1 (so `'hlsearch'` is on and no `:noh` since the last search): the
--   last search pattern, register `/`;
-- - else none: an empty list is sent once, and Eclipse clears the highlights.
--
-- Upper and lower case. `matchbufline()` follows `'ignorecase'` but not `'smartcase'`, so the
-- pattern gets an explicit `\c` or `\C` as Neovim's own search would: a `\c` or `\C` in it wins
-- (not a `c` after an escaped backslash: `C:\\Code` is a literal backslash and `Code`); then
-- `'noignorecase'` means exact case; then `'smartcase'` with an upper-case letter (not one after a
-- backslash, as in `\S`, nor after `\_` or `\%`, as in `\_S` or `\%V`; an accented capital such as
-- `É` counts) means exact case, as Vim's `pat_has_uppercase` decides. A search made with `*`, `#`,
-- `g*` or `g#` is never made smart, and stays so for `n` and after any other command, as Vim keeps
-- that with the pattern. A `*` key may also be the argument of another command (`"*p`, `f*`), so
-- `vim.on_key` only notes register `/` before it; if the register changed once the key ran (at the
-- next key, or in scheduled work when none follows: a macro's keys run before any scheduled work),
-- its new value is the star's pattern, and the highlighted pattern is a star's while it equals that
-- value. Example, with `ignorecase smartcase`: `/Foo` finds only `Foo`, `/foo` finds `Foo` too, and
-- `*` on `Foo` finds both, also after `:w`.
--
-- Which lines. Only the visible lines: 0.1 to 0.2 ms whatever the file's size, where the whole of a
-- 100,000-line file takes over 100 ms. Those are the window's (`w0` to `w$`) and the lines the
-- editor shows, which Eclipse reports when its view changes (`M.set_view`, LuaScripts.SEARCH_VIEW):
-- the two differ when Eclipse scrolled alone in insert mode, when Neovim does not know Eclipse's
-- folds, and by a partly shown last line. Two ranges apart are searched one by one, not with every
-- line between them. A pattern that can match across lines (`\n`, `\_x`) is found with a
-- `searchpos()` walk instead, as `matchbufline()` matches within one line; the walk starts a screen
-- higher, for a match that begins above the view, goes on after each match's end (a match that
-- begins inside another is not one, as in Neovim's hlsearch), and stops after `'redrawtime'`, as
-- Neovim's own highlighting does.
--
-- When. A decoration provider's `on_win` runs at every redraw of a window, which is exactly when
-- the highlights may have changed: a new pattern, `:noh`, a typed character, an edit, a scroll. It
-- only schedules the work (`vim.schedule`, once per batch: twenty `Ctrl-E` compute once), since
-- `on_win` must not change anything. A window's matches are sent only when they differ from what
-- was sent for it last, so `n`, which moves the cursor between the same matches, sends nothing;
-- with matches, the buffer's change count counts as part of them, so they are sent again after an
-- edit, which may have made the editor drop one it touched: Eclipse picks the match under the caret
-- itself (`CurSearch`). Entering a window or a buffer (an editor attached, or switched to) forgets
-- what was sent for it, as that editor has nothing painted; so does `M.resend()`, which the session
-- calls when an editor is attached, back in Neovim mode, or at the end of an insert in which
-- Eclipse typed and ignored the matches Neovim sent (they were of Neovim's older lines). Without a
-- UI attached (a headless test) no redraw happens; `M.update()` does the same work on demand.
--
-- Sent: `eclipse_search(buf, ranges, win)`, where `ranges` is a flat list of four numbers per
-- match: start line (1-based), start byte column (0-based), end line, end byte column (0-based,
-- exclusive). Example: `foo` at the start of line 3 is `{3, 0, 3, 3}`. A match of no width (as `^`)
-- is left out: there is nothing to paint.
--
-- Sent as LuaScripts.SEARCH (core/LuaModules runs this file as a chunk of its own).

local enabled = vim.g.neovim4eclipse_search_highlight
if enabled == 0 or enabled == false then
  return false
end
local M = {}
-- The value of register / that the last *, #, g* or g# put there (see above); nil before any.
local star_pattern = nil
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

local ns = vim.api.nvim_create_namespace('neovim4eclipse_search')
-- A * or # in normal mode may be a search, or the argument of another command ("*p, f*): it was a
-- search if register / changed once the key has run, and then the new value is the star's. "Once
-- the key has run" is the next key (keys that arrive together, as a macro's, run before any
-- scheduled work) or, when no key follows, the scheduled check, whichever comes first.
local star_before = nil -- register / before a * or # not checked yet

local function check_star()
  if star_before ~= nil then
    local after = vim.fn.getreg('/')
    if after ~= star_before then
      star_pattern = after
    end
    star_before = nil
  end
end

vim.on_key(function(key)
  check_star() -- the key before this one has run
  if (key == '*' or key == '#') and vim.api.nvim_get_mode().mode == 'n' then
    star_before = vim.fn.getreg('/')
    vim.schedule(check_star)
  end
end, ns)
local group = vim.api.nvim_create_augroup('neovim4eclipse_search', { clear = true })
-- A window or buffer entered: an editor was attached to it (or switched to), and has
-- nothing painted yet, so the next redraw sends its matches even if they did not change.
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

-- The characters of s, one UTF-8 sequence each.
local function chars(s)
  return s:gmatch('[%z\1-\127\194-\244][\128-\191]*')
end

-- Whether pat has an upper-case letter, as Vim's pat_has_uppercase decides for 'smartcase': a
-- character after a backslash does not count, nor the one after \_ or \% (\_S, \%V), and a
-- letter outside ASCII counts when it has a lower-case form (É).
local function has_upper(pat)
  local skip = 0
  local after_backslash = false
  for c in chars(pat) do
    if skip > 0 then
      skip = skip - 1
      if after_backslash and (c == '_' or c == '%') then
        skip = 1 -- \_X and \%X: the X too
      end
      after_backslash = false
    elseif c == '\\' then
      skip = 1
      after_backslash = true
    elseif vim.fn.tolower(c) ~= c then
      return true
    end
  end
  return false
end

-- The class names Neovim reads as [:name:] inside a [] collection (regexp.c, get_char_class).
local CHAR_CLASSES = {}
for name in ('alnum alpha blank cntrl digit graph lower print punct space upper xdigit'
    .. ' tab return backspace escape ident keyword fname'):gmatch('%a+') do
  CHAR_CLASSES[name] = true
end
local ONE_CHAR = '[%z\1-\127\194-\244][\128-\191]*' -- one UTF-8 sequence

-- Where the [] collection whose items start at s's index i (after the [ or [^) ends, as
-- Neovim's skip_anyof finds it: the index of its closing ], or #s + 1 when there is none
-- yet. Inside it a delimiter is an item, not the pattern's end: /[/]x looks for "/x".
-- A ] or - first is an item ([]a], [-a]); so is what follows a - unless it is a ]
-- ([a-/] is a to /, [a-] ends there), and what follows one of the backslashes a
-- collection knows ([\]], [\n], [\d]); [:alpha:], [=a=] and [.a.] are skipped
-- whole, so their ] does not end it ([:nope:] is no class: its ] does).
local function collection_end(s, i)
  if s:sub(i, i) == '^' then
    i = i + 1
  end
  if s:sub(i, i) == ']' or s:sub(i, i) == '-' then
    i = i + 1
  end
  -- After a backslash: REGEXP_INRANGE, and REGEXP_ABBR unless 'cpoptions' has l.
  local escapes = ']^-n\\' .. (vim.o.cpoptions:find('l', 1, true) and '' or 'nrtebdoxuU')
  while i <= #s and s:sub(i, i) ~= ']' do
    local c, nxt = s:sub(i, i), s:sub(i + 1, i + 1)
    if c == '-' then
      i = i + 1
      if i <= #s and s:sub(i, i) ~= ']' then
        i = i + #(s:match('^' .. ONE_CHAR, i) or c) -- c: a stray byte
      end
    elseif c == '\\' and nxt ~= '' and escapes:find(nxt, 1, true) then
      i = i + 2
    elseif c == '[' then
      local class = s:match('^%[:(%a+):%]', i)
      local whole = (class and CHAR_CLASSES[class] and '[:' .. class .. ':]')
        or s:match('^%[=' .. ONE_CHAR .. '=%]', i)
        or s:match('^%[%.' .. ONE_CHAR .. '%.%]', i)
      i = i + (whole and #whole or 1)
    else
      i = i + 1
    end
  end
  return i
end

-- The pattern typed so far, up to its delimiter, as Neovim's skip_regexp_ex reads it:
-- 'foo/e' typed after / is 'foo'. Not ended by an escaped delimiter (a\/b) nor by one in a
-- [] collection (/[/]x is '[/]x'); the collection's [ is \[ instead with 'nomagic' (magic
-- false) or after \V, and [ again after \v. With ? as the delimiter (a ? search,
-- :s?a?b?), an escaped ? is a plain ?, as skip_regexp_ex makes it: ?what\? looks for
-- "what?", where the pattern what\? would be "wha" and an optional t. An escaped
-- backslash stays one (?a\\? ends at the ?). No other delimiter is changed: \/ in a /
-- search is already a plain / to the regexp.
local function until_delimiter(line, delimiter, magic)
  local out = {}
  local i = 1
  while i <= #line do
    local c, nxt = line:sub(i, i), line:sub(i + 1, i + 1)
    if c == delimiter then
      break
    elseif (c == '[' and magic) or (c == '\\' and nxt == '[' and not magic) then
      local close = collection_end(line, i + (c == '[' and 1 or 2))
      out[#out + 1] = line:sub(i, close) -- with its ], when it has one
      i = close + 1
    elseif c == '\\' and nxt ~= '' then
      out[#out + 1] = (delimiter == '?' and nxt == '?') and '?' or c .. nxt
      if nxt == 'v' then
        magic = true
      elseif nxt == 'V' then
        magic = false
      end
      i = i + 2
    else
      out[#out + 1] = c
      i = i + 1
    end
  end
  return table.concat(out)
end

-- The commands whose pattern 'incsearch' shows while it is typed, by their shortest names.
local PATTERN_COMMANDS = {
  { 's', 'substitute' }, { 'g', 'global' }, { 'v', 'vglobal' }, { 'sm', 'smagic' }, { 'sno', 'snomagic' },
}

-- The pattern of a :s, :g or :v line being typed ('%s/foo/bar' gives 'foo'), or nil. A range
-- with a pattern of its own (:/x/s/y/) is not read: nil, and the last search shows instead.
local function ex_pattern(line)
  local rest = line:gsub('^[%s:]*', '')
  rest = rest:gsub("^[%d%s,;%.%$%%<>+%-]*", ''):gsub("^'.", ''):gsub("^[%d%s,;%.%$%%'<>+%-]*", '')
  local name, after = rest:match('^(%a+)!?(.*)$')
  if not name then
    return nil
  end
  local known
  for _, c in ipairs(PATTERN_COMMANDS) do
    if #name >= #c[1] and c[2]:sub(1, #name) == name then
      known = c[2]
    end
  end
  if not known then
    return nil
  end
  -- :smagic and :snomagic read the pattern with 'magic' on and off, whatever it is.
  local magic = (known == 'smagic') or (known ~= 'snomagic' and vim.o.magic)
  after = after:gsub('^%s*', '')
  local delimiter = after:sub(1, 1)
  if delimiter == '' or delimiter:match('[%w%s"|\\]') then
    return nil
  end
  local pat = until_delimiter(after:sub(2), delimiter, magic)
  return pat ~= '' and pat or nil
end

-- The pattern Neovim highlights now and whether * made it; nil when there is none.
local function current_pattern()
  local kind = vim.fn.getcmdtype()
  if vim.o.incsearch then
    if kind == '/' or kind == '?' then
      local typed = until_delimiter(vim.fn.getcmdline(), kind, vim.o.magic)
      if typed ~= '' then
        return typed, false
      end
      -- Nothing typed yet: Neovim still shows the last search's matches.
    elseif kind == ':' then
      local pat = ex_pattern(vim.fn.getcmdline())
      if pat then
        return pat, false
      end
    end
  end
  if vim.v.hlsearch == 1 and vim.o.hlsearch then
    local pat = vim.fn.getreg('/')
    return pat, pat == star_pattern
  end
  return nil
end

--- The pattern as Neovim's search would apply it, with \c or \C (see above).
-- Whether pat holds Vim's \c or \C flag; a backslash that is itself escaped (\\c, a literal
-- backslash and c) is not one.
local function has_case_flag(pat)
  local i = 1
  while i <= #pat do
    if pat:sub(i, i) == '\\' then
      local next = pat:sub(i + 1, i + 1)
      if next == 'c' or next == 'C' then
        return true
      end
      i = i + 2
    else
      i = i + 1
    end
  end
  return false
end

function M.effective(pat, star)
  if has_case_flag(pat) then
    return pat
  end
  if not vim.o.ignorecase then
    return '\\C' .. pat
  end
  if vim.o.smartcase and not star and has_upper(pat) then
    return '\\C' .. pat
  end
  return '\\c' .. pat
end

M.has_upper = has_upper
M.ex_pattern = ex_pattern

-- The matches of pat in win's lines top to bottom, as the flat list sent to Eclipse.
local function matches(win, pat, top, bottom)
  local ranges = {}
  local buf = vim.api.nvim_win_get_buf(win)
  if not pat:find('\\n') and not pat:find('\\_') then
    local ok, found = pcall(vim.fn.matchbufline, buf, pat, top, bottom)
    if ok then
      for _, m in ipairs(found) do
        if #m.text > 0 then
          vim.list_extend(ranges, { m.lnum, m.byteidx, m.lnum, m.byteidx + #m.text })
        end
      end
    end
    return ranges
  end
  -- Across lines: walk the matches with the cursor, then put the view back as it was. The walk
  -- starts a screen above the top, so that a match that begins there and ends in view is found,
  -- and it stops after 'redrawtime' milliseconds, as Neovim's own highlighting does: a pattern
  -- such as foo\_.*bar can make each search scan to the end of a long file.
  local height = bottom - top + 1
  local budget = vim.o.redrawtime
  local started = vim.uv.hrtime()
  local from_end = vim.o.cpoptions:find('c', 1, true) ~= nil -- see the end of the loop
  local function text(l)
    return vim.api.nvim_buf_get_lines(buf, l - 1, l, true)[1]
  end
  -- A match that begins at a line break (after the last character: \n.\|b on "ab", "cd"
  -- has b, then the break and c). Neovim's search puts such a match on the line's last
  -- character, and so skips it when the cursor is there, as it is after a match that ends
  -- on that character ('W', and 'c' too); nor does a search for its end start there.
  -- So pat is searched for only where it begins at that break: \%23l\%5c before it in a
  -- group, from the last character with 'c'. The \m before \) closes the group in any
  -- mode, after a \v or \V in pat. Example: pat \n. at line 1 of "ab" is
  -- \m\%1l\%3c\%(\n.\m\).
  local function at_break(l, len)
    return '\\m\\%' .. l .. 'l\\%' .. (len + 1) .. 'c\\%(' .. pat .. '\\m\\)'
  end
  vim.api.nvim_win_call(win, function()
    local view = vim.fn.winsaveview()
    pcall(function()
      -- Where the next match may begin: line l, byte column c (1-based; #line + 1 is
      -- the line break).
      local l, c = math.max(1, top - height), 1
      while l <= bottom do
        local left = math.floor(budget - (vim.uv.hrtime() - started) / 1e6) -- a Number: searchpos takes no Float
        if left <= 0 then
          break
        end
        local len = #text(l)
        local s, here
        if c > len and len > 0 then
          here = at_break(l, len)
          vim.fn.cursor(l, len)
          s = vim.fn.searchpos(here, 'cnW', l, left)
        else
          vim.fn.cursor(l, c)
          s = vim.fn.searchpos(pat, 'cW', bottom, left)
          if s[1] == 0 then
            break
          end
          local slen = #text(s[1])
          if s[2] > slen and slen > 0 then
            here = at_break(s[1], slen) -- found from further back, but the cursor is put back
            vim.fn.cursor(s[1], slen)
          end
        end
        if s[1] == 0 then
          l, c = l + 1, 1 -- no match begins at l's break: go on at the next line
        else
          local e = vim.fn.searchpos(here or pat, 'cenW', 0, left)
          if e[1] == 0 then
            break
          end
          -- e is the last character's first byte: the end is after that character.
          local line = text(e[1])
          local last = vim.fn.strcharpart(line:sub(e[2]), 0, 1)
          local stop = e[2] - 1 + math.max(#last, 1)
          local empty = e[1] < s[1] or (e[1] == s[1] and stop <= s[2] - 1)
          if e[1] >= top and not empty then
            vim.list_extend(ranges, { s[1], s[2] - 1, e[1], math.min(stop, #line) })
          end
          -- The next search starts after this match, not inside it, as Neovim's hlsearch
          -- goes on at the match's end (with 'cpoptions' c, the default): on lines a, b, c
          -- a\nb\|b\nc paints a-b only, and on "foo foo", "bar" foo\_.\{-}bar paints one
          -- match from the first foo, not a second from the other foo, each of which
          -- could scan far for its bar. A match of no width (or 'cpoptions' without c)
          -- goes on one character after its start.
          if from_end and not empty then
            if e[2] > #line then
              l, c = e[1] + 1, 1 -- the match took the line break
            else
              l, c = e[1], stop + 1
            end
          else
            local sline = text(s[1])
            if s[2] > #sline then
              l, c = s[1] + 1, 1
            else
              l, c = s[1], s[2] + #vim.fn.strcharpart(sline:sub(s[2]), 0, 1)
            end
          end
        end
      end
    end)
    vim.fn.winrestview(view)
  end)
  return ranges
end

--- Sends win's visible matches even if they did not change: the editor that shows them was just
--- attached, or Neovim mode is back on, and has none painted. Scheduled, as on_win's work is:
--- run inside the RPC call that asks for it, the search state is not the one the window shows.
function M.resend(win)
  win = win or vim.api.nvim_get_current_win()
  sent[win] = nil
  vim.schedule(function()
    pcall(M.update, win)
  end)
end

--- The lines the editor of win shows (1-based), as Eclipse reports them when its view changes:
--- they are searched along with Neovim's own visible lines (see above). Scheduled, as resend.
function M.set_view(win, top, bottom)
  win = win or vim.api.nvim_get_current_win()
  views[win] = { top, bottom }
  vim.schedule(function()
    pcall(M.update, win)
  end)
end

--- Works out win's visible matches and sends them if they changed. Returns the ranges.
function M.update(win)
  win = win or vim.api.nvim_get_current_win()
  if not vim.api.nvim_win_is_valid(win) then
    return {}
  end
  local pat, star = current_pattern()
  local ranges = {}
  local buf = vim.api.nvim_win_get_buf(win)
  if pat and pat ~= '' then
    local effective = M.effective(pat, star)
    local top, bottom = vim.fn.line('w0', win), vim.fn.line('w$', win)
    local view = views[win]
    if view then
      local last = vim.api.nvim_buf_line_count(buf)
      local vtop, vbottom = math.max(1, view[1]), math.min(last, view[2])
      if vtop > bottom + 1 or vbottom < top - 1 then
        -- Apart (Eclipse scrolled alone, far from Neovim's window): two searches, top first,
        -- not one over every line between them.
        local first, second = { top, bottom }, { vtop, vbottom }
        if vtop < top then
          first, second = second, first
        end
        ranges = matches(win, effective, first[1], first[2])
        -- A match across lines between the two can be found from both: kept once.
        local seen = {}
        for i = 1, #ranges, 4 do
          seen[ranges[i] .. ':' .. ranges[i + 1]] = true
        end
        local more = matches(win, effective, second[1], second[2])
        for i = 1, #more, 4 do
          if not seen[more[i] .. ':' .. more[i + 1]] then
            vim.list_extend(ranges, { more[i], more[i + 1], more[i + 2], more[i + 3] })
          end
        end
      else
        ranges = matches(win, effective, math.min(top, vtop), math.max(bottom, vbottom))
      end
    else
      ranges = matches(win, effective, top, bottom)
    end
  end
  -- With matches, the buffer's change count is part of what was sent: after an edit the editor may
  -- have dropped a match it touched (SearchHighlighter), and needs them again even if no position
  -- moved. Without matches it is not, so an edit sends nothing new.
  local key = table.concat(ranges, ',')
  if #ranges > 0 then
    key = key .. '@' .. vim.api.nvim_buf_get_changedtick(buf)
  end
  if sent[win] ~= key then
    sent[win] = key
    notify('eclipse_search', buf, ranges, win)
  end
  return ranges
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
package.loaded['neovim4eclipse.search'] = M
return true
