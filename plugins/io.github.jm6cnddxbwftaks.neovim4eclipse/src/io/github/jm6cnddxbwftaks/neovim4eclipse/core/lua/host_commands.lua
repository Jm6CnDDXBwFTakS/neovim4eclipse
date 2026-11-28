-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

-- Hands the ex commands Eclipse carries out (`:w`, `:q`, `:e file`, `:tabn` ...; see
-- `util/HostCommand`) from Neovim's command line to Eclipse. The argument names those commands by
-- Neovim's full name, with what each may have after it (`HostCommand.rulesForNeovim()`), as words:
-- `"edit=any quit=none tabnext=count ..."`.
--
-- How
-- 1. `:Eclipse[!] {command} {args}` is a user command that sends `eclipse_command` to Eclipse
--    (`:Eclipse tabnext 3`) with the name, the `!`, the arguments and the window it ran in (the
--    command's origin, `HostCommand.origin`: `:q` typed in a page's window is the page's). Anyone
--    can use it, in a mapping too.
-- 2. Just before a `:` line runs, `CmdlineLeavePre` parses it with `nvim_parse_cmd()`. If it is one
--    of the commands, `setcmdline()` replaces it with the `:Eclipse` form, which Neovim then runs
--    instead: `:tabn 3` becomes `:Eclipse tabnext 3`, `:q!` `:Eclipse! quit`. Each command after a
--    `|` gets the same treatment (`:Eclipse` is defined with `bar`): `:w | q` becomes `:w | Eclipse
--    quit`, so Neovim writes through the write hook (LuaScripts.DOCUMENT_BUFFER_MODULE) and Eclipse
--    then closes the editor, asking first if the save failed. Left to Neovim, that `q` would end
--    the embedded Neovim on its last window. A command that reads `|` as part of its argument
--    (`:normal`, `:g`, `:let`; `nvim_parse_cmd()` gives it no next command) keeps the rest of its
--    line.
-- 3. `:Eclipse` puts the line as typed back into the history, in place of its own, so Up in the `:`
--    box recalls `tabn 3`.
-- 4. Refused commands. Other scripts can make a built-in command an error where it must not run,
--    through `package.loaded['neovim4eclipse.host_commands'].refuse(name, why)`: `why(p)`
--    returns the reason, or `nil` to let the command run; `p` is the command as
--    `nvim_parse_cmd()` gives it, or `nil` for an `:Eclipse name` typed or mapped as such. Such a
--    line becomes `:Eclipse name`, which shows `name: reason` as an error and tells Eclipse
--    nothing. A built-in command cannot be mapped, so this is the only way to stop one. Example:
--    LuaScripts.FOLDS refuses `:fold` in buffers whose folds are Eclipse's, so `:3,5fold` shows
--    `fold: Eclipse defines the folds in this editor`; LuaScripts.WINDOW_COMMANDS refuses
--    `:tabonly`.
-- 5. Translated commands. `translate(name, fn)`: a built-in command that Eclipse carries out in a
--    form of its own. `fn(p)` returns the words of the `:Eclipse` line to run instead, or `nil` and
--    why the command may not run, or nothing for the usual handling (the table's command of that
--    name, else Neovim). Example: LuaScripts.WINDOW_COMMANDS turns `:vsplit` into `:Eclipse window
--    split left same`, having read `'splitright'`. The words are written so that `:Eclipse`'s
--    arguments give them back (a space in a file name is escaped), and they are not expanded again.
--    `file_name(args)` reads a file argument as `:e` reads its name, for such a script.
-- 6. Commands left to Neovim. `leave_to_neovim(fn)`: when `fn(name)` returns true, the command is
--    not replaced and runs as Neovim's, and the `QuitPre` safety net below says nothing for a
--    `:quit`. Example: LuaScripts.WINDOW_COMMANDS leaves `:q` and `:close` typed in a floating
--    window to Neovim, which closes that window, not the editor.
--
-- Why replace the line rather than cancel it (`v:event.abort` in `CmdlineLeave`): a cancelled line
-- is not "the last command line", so `@:` would fail with E30, while the replaced one is, and `@:`
-- repeats it. Only `:reg :` shows the replacement. (Checked on Neovim 0.12.6.)
--
-- A file name arrives as a full path (`:e name`, `:wq name`, `:x name`, `:file name`). The rewrite expands it
-- once, from the parsed line, as Neovim's own command would (`expandcmd()`: `%:h/x`, `#`, `~`,
-- escapes taken out) and makes it absolute against the window's folder (`fnamemodify(':p')`, then
-- `vim.fs.normalize`), so Eclipse uses the file Neovim would, and `:e x`, `:w x` and `:wq x` mean
-- the same file. `./x` is that folder too, as in Neovim; the file's own folder is `%:h/x`. One
-- exception: `:file x`, a name with no folder in it, is put beside the file (a rename in place),
-- its folder joined as a path, not expanded.
-- `:Eclipse` does not expand the name a second time (that ran a shell for a completed name with a
-- backslash and a backtick), and a name with a line break is refused. `:e +cmd name`: Eclipse opens
-- the file, and Neovim runs the `+cmd` once it shows it (LuaScripts.GO_TO_LAST_POSITION).
--
-- Not replaced, so left to Neovim: a range the command does not take (`:5,10w`), arguments it does
-- not take (`:q foo`: Neovim reports E488), a line Neovim cannot parse. A range of buffers
-- (`:3,5bd`, `:%bd`) becomes the listed buffers it covers, and a count in front of a tab command
-- (`:3tabn`) its argument.
--
-- `CmdlineLeavePre` fires on Esc too; `v:char` tells which key ended the line, and only Enter runs
-- it. It fires for `:` typed by a mapping, a macro, `:normal` and `feedkeys()` as well, each line
-- on its own, but not for `<Cmd>`, for the commands `:bufdo` runs, or after a `|` that
-- `nvim_parse_cmd()` does not split (`:let x = 1 | q`): those reach Neovim. For the ones that close
-- something there are safety nets, which tell Eclipse but cannot stop Neovim: `QuitPre` here (a
-- `:quit` Neovim runs; on its last window Neovim ends), and `BufDelete` / `BufWipeout` on each
-- document buffer (LuaScripts.DOCUMENT_BUFFER_MODULE). Both send `eclipse_buffer_closed`, and
-- Eclipse closes the editor showing that buffer.
--
-- Sent as LuaScripts.HOST_COMMANDS (core/LuaModules runs this file as a chunk of its own).

local rules = {} -- { quit = 'none', edit = 'any', ... }
for name, rule in select(1, ...):gmatch('(%a+)=(%a+)') do
  rules[name] = rule
end
local typed -- the line as typed while its :Eclipse replacement runs (for the history)
local refusals = {} -- built-in command name -> function(p) returning why it may not run, or nil
local leaves = {} -- functions(name) returning true to leave that command to Neovim
-- built-in command name -> function(p) returning the :Eclipse words to run instead
-- ({ 'window', 'split', 'below', 'same' }), or nil and why it may not run, or nothing
-- at all to leave the command to Neovim
local translations = {}
-- The reasons eclipse_form found in the line about to run, by name: its :Eclipse form
-- carries only the name, and the reason may depend on the rest (:2tabclose).
local refusing = {}
-- The +cmd of :e +cmd name (:e +10 a.txt, :e + a.txt), waiting for Eclipse to show the
-- file: { [its full path (PATHS canonical)] = '10' }. By file, since one picker action
-- can open several (each with its own line). The attach push of that file's editor runs
-- its entry (GO_TO_LAST_POSITION, take_open_command below), as Vim runs it once the file
-- is read. The next : line drops those left over (Eclipse could not open them).
local open_commands = {}
local BS = string.char(92) -- a backslash (written as one char: in Java source it needs two)
local LINE_BREAK = '[' .. string.char(13, 10) .. ']'
-- The first word of :Eclipse's arguments when eclipse_form wrote the line: the words after
-- it are final (++opt words, a +cmd, the full path) and are not expanded again.
local FINAL = '++expanded'
package.loaded['neovim4eclipse.host_commands'] = {
  refuse = function(name, why)
    refusals[name] = why
  end,
  leave_to_neovim = function(fn)
    leaves[#leaves + 1] = fn
  end,
  translate = function(name, fn)
    translations[name] = fn
  end,
}
local function refused(name, p)
  return refusals[name] and refusals[name](p) or nil
end
local function left_to_neovim(name)
  -- :file renames the attached editor's file (editor/FileRenames), so it is Eclipse's only in an
  -- editor's own document buffer, decided from Neovim's state now: elsewhere (a picker's preview, a
  -- scratch buffer, a page) :file renames that buffer, Neovim's own way, and no file on disk.
  -- LuaScripts.WINDOW_COMMANDS also leaves it to Neovim in any float or side window, whatever
  -- buffer it shows.
  if name == 'file' and vim.b.neovim4eclipse_file == nil then
    return true
  end
  for _, fn in ipairs(leaves) do
    if fn(name) then
      return true
    end
  end
  return false
end
local file_commands = { edit = true, wq = true, xit = true, exit = true, file = true } -- take a file name
-- Of those, the ones that take a +cmd before it: :e[dit] [++opt] [+cmd] {file}, while
-- :wq, :x and :exit take [++opt] [file] only (:help :wq, :help :x). So :x +foo writes
-- the file "+foo" and runs no :foo, as in Vim.
local cmd_commands = { edit = true }
local canonical = package.loaded['neovim4eclipse.paths'].canonical -- PATHS: case, links, separators
-- Runs the +cmd of :e +cmd name as Vim does: + alone goes to the last line, +10 to line
-- 10, +/pat to the first match, any other an Ex command. It is what you typed (a
-- completed file name has its + escaped, so a name never becomes one).
local function run_open_command(command, later)
  local ok, err = pcall(vim.cmd, command == '' and '$' or command)
  if not ok then
    local message = tostring(err):gsub('^Vim:', '')
    local function say()
      vim.api.nvim_echo({ { message } }, true, { err = true })
    end
    -- At once, so that a failed +cmd stops a macro, as any failed command does. Later only
    -- inside nvim_win_call (later: GO_TO_LAST_POSITION, with Neovim in a float), where an
    -- error message would become an error of that call, and show nothing.
    if later then
      vim.schedule(say)
    else
      say()
    end
  end
end
package.loaded['neovim4eclipse.host_commands'].take_open_command = function(file, later)
  -- GO_TO_LAST_POSITION, after the attach push of file's editor: runs a waiting +cmd for
  -- that file, and says whether there was one (then the caret follows Neovim's cursor).
  local key = canonical(file)
  local command = open_commands[key]
  if not command then
    return false
  end
  open_commands[key] = nil
  run_open_command(command, later)
  return true
end
-- A plug-in's split of a file, handed to Eclipse (WINDOW_COMMANDS' open_beside): the new
-- editor starts where the plug-in put the cursor, by the same route as :e +10 name.
package.loaded['neovim4eclipse.host_commands'].open_at = function(file, line, col)
  open_commands[canonical(file)] = ('call cursor(%d, %d)'):format(line, col)
end
-- ui2 leaves a line that ran on the command row, as Neovim does (:set nu stays shown).
-- For a line :Eclipse replaced that would be the replacement (:Eclipse window focus l 0
-- for :wincmd l), so the line as typed is put back. Scheduled: ui2 draws the replacement
-- after the command runs. Only while the row still holds the replacement: a message
-- the command gave since (an error, Eclipse's answer) stays. Plain text: ui2's colors
-- for a : line are its own drawing's.
local function show_typed(line)
  vim.schedule(function()
    local ui2 = package.loaded['vim._core.ui2']
    local buf = ui2 and ui2.bufs and ui2.bufs.cmd
    if not buf or not vim.api.nvim_buf_is_valid(buf) or vim.api.nvim_buf_line_count(buf) ~= 1 then
      return
    end
    local shown = vim.api.nvim_buf_get_lines(buf, 0, 1, false)[1] or ''
    if shown:match('^:Eclipse!? ') then -- :e! runs as :Eclipse! edit
      vim.api.nvim_buf_set_lines(buf, 0, -1, false, { ':' .. vim.fn.strtrans(line) })
    end
  end)
end

-- Defined below; :Eclipse edit, wq, xit, exit and file typed by hand read their name with it,
-- as a : line's is read.
local file_words

vim.api.nvim_create_user_command('Eclipse', function(o)
  if typed then
    vim.fn.histdel(':', -1) -- the replacement
    vim.fn.histadd(':', typed)
    show_typed(typed)
    typed = nil
  end
  local name = o.fargs[1]
  local why = refusing[name] or refused(name)
  refusing[name] = nil
  if why then
    vim.api.nvim_echo({ { name .. ': ' .. why } }, true, { err = true })
    return
  end
  if not rules[name] then
    vim.api.nvim_echo({ { 'E492: Not an Eclipse command: ' .. name } }, true, { err = true })
    return
  end
  local args = vim.list_slice(o.fargs, 2)
  local command -- the +cmd of :e +cmd name, which Neovim runs itself (open_commands)
  if file_commands[name] then
    if args[1] == FINAL then
      -- The usual way: eclipse_form wrote this line from the : line as typed, and
      -- already made the file name the full path (file_words). Expanding the words
      -- again would be wrong, and dangerous: splitting the arguments
      -- (fargs) turns a completed name's doubled backslash into one, so a backtick after
      -- it was no longer escaped, and expandcmd() ran what followed in a shell (a cloned
      -- repository's file named with a backslash and a backtick, completed into :x).
      table.remove(args, 1)
    else
      -- :Eclipse edit, wq, xit, exit or file typed by hand: the rest of the line as typed
      -- (o.args keeps the escapes, as nvim_parse_cmd's args do; fargs would split the name
      -- at spaces and take a backslash away), read as a : line's name is (file_words), so
      -- that :Eclipse edit a  b opens the file "a  b", as :e a  b does. The hand-typed form
      -- has no completion, so only what you typed is expanded, once.
      local words, why = file_words((o.args:gsub('^%S+%s*', '')), name)
      if not words then
        vim.api.nvim_echo({ { name .. ': ' .. why } }, true, { err = true })
        return
      end
      args = words
    end
    for i = #args, 1, -1 do
      if cmd_commands[name] and (args[i]:match('^%+[^+]') or args[i] == '+') then
        command = args[i]:sub(2)
        table.remove(args, i)
      end
    end
  end
  vim.rpcnotify(vim.g.neovim4eclipse_channel, 'eclipse_command', name, o.bang, args, vim.api.nvim_get_current_win())
  if command then
    local file = args[#args]
    if file and file:sub(1, 2) ~= '++' and canonical(file) ~= canonical(vim.api.nvim_buf_get_name(0)) then
      open_commands[canonical(file)] = command
    else
      run_open_command(command) -- :e +10, or :e +10 of this file: here, now
    end
  end
end, { nargs = '+', bang = true, bar = true, desc = 'Eclipse carries out an ex command (:Eclipse quit)' })

-- The buffer of the document another editor has open when :e name is for its file, else
-- nil: then Neovim runs :buffer of it (not the :edit as typed; see below). It enters that
-- document's buffer, and Eclipse follows into the file's editor
-- with the window, as for :b and Ctrl-^ (docs/design/WINDOWS.md, E2): the window's jump
-- history and alternate file go along, so Ctrl-O goes back and :e # toggles between
-- the two editors, as in one Vim window. Example: Nested.txt and root.txt open, :e
-- root.txt, :e #, :e #: root.txt, Nested.txt, root.txt. Eclipse still opens a file
-- with no editor yet (only Eclipse can make an editor), and runs :e! name, :e +cmd name
-- and :e ++opt name, and :e of the current file (Neovim's would re-read the buffer).
-- The name is read as :Eclipse reads it (expandcmd, fnamemodify ':p'); a # with no
-- alternate file stays '#' and matches nothing, so Eclipse reports it.
-- :buffer, not :edit: the one rule (PATHS) found the buffer, so Neovim must not match the
-- name again its own way. It would not always agree: on Windows a junction to a file not
-- on disk yet gives a new buffer, since Neovim matches such a name as written (found
-- 2026-10-29). :buffer is a jump and sets the alternate file as :edit does.
local function open_document_edited(p)
  if p.cmd ~= 'edit' or p.bang or #p.args ~= 1 or p.args[1]:sub(1, 1) == '+' then
    return nil
  end
  local ok, expanded = pcall(vim.fn.expandcmd, p.args[1])
  if not ok or expanded == '' then
    return nil
  end
  local target = canonical(vim.fn.fnamemodify(expanded, ':p'))
  local current = vim.api.nvim_get_current_buf()
  for _, b in ipairs(vim.api.nvim_list_bufs()) do
    local file = vim.b[b].neovim4eclipse_file -- a document's buffer (DOCUMENT_BUFFER)
    -- Loaded only: entering a buffer Neovim has not read would run its read instead.
    if b ~= current and file and vim.api.nvim_buf_is_loaded(b) and canonical(file) == target then
      return b
    end
  end
  return nil
end

-- Where the word of s that starts at i ends: the index after it. A backslash keeps the
-- next character in the word (an escaped space, as completion writes my file.txt, is in it).
local function word_end(s, i)
  while i <= #s do
    local c = s:sub(i, i)
    if c == BS then
      i = i + 2
    elseif c:match('%s') then
      return i
    else
      i = i + 1
    end
  end
  return #s + 1
end

-- The words :Eclipse sends for the argument of command `cmd` (:e, :wq, :x, :file), as
-- nvim_parse_cmd gives it (one string, escapes as typed): its ++opt words, its +cmd when the
-- command takes one (:e only; backslashes taken out, as Vim does), and the file as the full path
-- Neovim's own :edit or :write would use (the rest is one name, spaces and all, as for :edit:
-- :e a b is the file "a b"). Returns nil and why when it is refused.
--
-- :file takes no ++opt and no +cmd, in Vim as here: `:file ++p x` names the file "++p x"
-- (Neovim's own :file names the buffer so), and a name without a folder goes beside the file
-- (below).
--
-- The name is expanded here, once, from the parsed form, where a completed name's
-- escapes are as Neovim wrote them: expandcmd() expands what :edit expands (%, #, ~,
-- $HOME; E194 when there is no alternate file for #) and takes the backslashes out
-- (an escaped space a space, an escaped backtick a plain one); fnamemodify(':p') makes it
-- absolute against the window's folder (getcwd(0): the project's root, or a folder
-- you went to with :cd or :lcd), as Neovim's :w other.txt does; vim.fs.normalize
-- resolves ../, without expanding $NAME a second time (a file named x$HOME stays so).
-- Example, editing src/Main.java in /ws/app: `:e notes.txt` and `:e ./notes.txt` give
-- /ws/app/notes.txt, `:wq %:h/Copy.java` /ws/app/src/Copy.java, `:x ++p out/a.txt`
-- { '++p', '/ws/app/out/a.txt' }, `:e +10 a.txt` { '+10', '/ws/app/a.txt' }, and
-- `:x +10 a.txt` { '/ws/app/+10 a.txt' } (no +cmd for :x: the name is all of it).
--
-- A name with a line break is refused: Eclipse cannot show it, and the :Eclipse line
-- would end at it, running the rest of the name as a command (a cloned repository's file
-- named notes, a line break, then lua=os.execute(...)).
function file_words(raw, cmd) -- the local declared above the :Eclipse command
  local with_cmd = cmd_commands[cmd]
  -- :file name without a folder renames the file where it is (beside), as a rename in a file
  -- manager or Eclipse's own does: `:file todo.txt` in src/notes.txt is src/todo.txt.
  -- The folder of the editor's file (its document buffer's name), and none in another buffer: a
  -- picker's preview of lib/x.txt does not put the name in lib/.
  local document = vim.b.neovim4eclipse_file
  local beside = cmd == 'file' and document and vim.fs.dirname(document) or nil
  local words, rest = {}, raw
  while cmd ~= 'file' and rest:match('^%+%+') do -- ++opt words
    local e = word_end(rest, 1)
    words[#words + 1] = rest:sub(1, e - 1)
    rest = (rest:sub(e):gsub('^%s+', ''))
  end
  if with_cmd and rest:sub(1, 1) == '+' then -- +cmd; a backslash before a space keeps it in
    local e = word_end(rest, 1)
    words[#words + 1] = (rest:sub(1, e - 1):gsub(BS .. '(.)', '%1'))
    rest = (rest:sub(e):gsub('^%s+', ''))
  end
  if rest == '' then
    return words
  end
  if rest:find(LINE_BREAK) then
    return nil, 'a file name with a line break is not supported'
  end
  local ok, expanded = pcall(vim.fn.expandcmd, rest, { errmsg = true })
  if not ok then
    return nil, (tostring(expanded):gsub('^Vim:', ''))
  end
  if expanded:find(LINE_BREAK) then
    return nil, 'a file name with a line break is not supported'
  end
  if beside and not rest:match('^[~%%#$<`]') and vim.fn.fnamemodify(expanded, ':t') == expanded then
    -- A name with no folder in it, put beside the file: the folder is joined as a path, never
    -- expanded as text. Not `%:h/` in front of the typed name: expandcmd() would then read the
    -- file's folder as typed text, and a folder named with a backtick (a cloned repository's) ran
    -- its command in a shell when the name had a wildcard character (`:file don't.txt`).
    words[#words + 1] = vim.fs.normalize(vim.fs.joinpath(beside, expanded), { expand_env = false })
    return words
  end
  words[#words + 1] = vim.fs.normalize(vim.fn.fnamemodify(expanded, ':p'), { expand_env = false })
  return words
end

-- The file a split command names ({ '++opt', 'name' } as nvim_parse_cmd gives its args),
-- as :e's name is read (file_words): the full path, or nil when it names none, or nil
-- and why. A +cmd is refused: Eclipse would have to run it in the new editor only.
package.loaded['neovim4eclipse.host_commands'].file_name = function(args)
  local words, why = file_words(table.concat(args, ' '), 'edit') -- [++opt] [+cmd] {file}, as :e
  if not words then
    return nil, why
  end
  local last = words[#words]
  for _, word in ipairs(words) do
    if word:sub(1, 1) == '+' and word:sub(1, 2) ~= '++' then
      return nil, 'a +cmd is not supported here; run it in the new window'
    end
  end
  if not last or last:sub(1, 2) == '++' then
    return nil
  end
  return last
end

-- One word as :Eclipse's line must spell it so that its fargs give the word back: a
-- backslash before each space, backslash, | and " (bar = true takes the one before | and
-- " away, splitting into fargs the one before a space or a backslash). Example:
-- /ws/my file|x gets a backslash before the space and one before the |.
local function fargs_word(word)
  return (word:gsub('[%s|"' .. BS .. ']', function(c)
    return BS .. c
  end))
end

-- The :Eclipse form of one parsed command (p, from nvim_parse_cmd; what follows a | is
-- not part of it), or nil to leave the command to Neovim.
local function eclipse_form(p)
  local why = refused(p.cmd, p)
  if why then
    refusing[p.cmd] = why
    return 'Eclipse ' .. p.cmd -- with any range and arguments: none of it runs
  end
  if left_to_neovim(p.cmd) then
    return nil -- :q in a floating window: Neovim closes that window
  end
  if translations[p.cmd] then
    local words, why = translations[p.cmd](p)
    for _, word in ipairs(words or {}) do
      -- A line break would end the :Eclipse line there and run the rest as a command
      -- (a buffer named after a cloned repository's file with one in its name), as for :e.
      if word:find(LINE_BREAK) then
        words, why = nil, 'a name with a line break is not supported'
        break
      end
    end
    if words then
      return 'Eclipse ' .. table.concat(vim.tbl_map(fargs_word, words), ' ')
    elseif why then
      refusing[p.cmd] = why
      return 'Eclipse ' .. p.cmd -- with any range and arguments: none of it runs
    end
    -- Nothing: the usual handling below (:tabclose alone is the table's tabclose).
  end
  local open = open_document_edited(p)
  if open then
    return 'buffer ' .. open -- :e of a file another editor has open: Neovim enters it (see above)
  end
  local rule = rules[p.cmd]
  if not rule then
    return nil
  end
  local args = vim.deepcopy(p.args)
  local range = p.range or {}
  if #range > 0 then
    if rule == 'buffers' and #args == 0 then
      -- :3,5bd, :%bd, :bd 3 (a number after :bd is a range too): the listed buffers it covers
      local last = range[2] or range[1]
      for _, b in ipairs(vim.fn.getbufinfo({ buflisted = 1 })) do
        if b.bufnr >= range[1] and b.bufnr <= last then
          args[#args + 1] = tostring(b.bufnr)
        end
      end
      if #args == 0 then
        return nil -- no such buffer: Neovim says so
      end
    elseif rule == 'count' and #args == 0 and #range == 1 then
      args = { tostring(range[1]) } -- :3tabn is :tabn 3
    else
      return nil
    end
  end
  if rule == 'none' and #args > 0 then
    return nil
  end
  if rule == 'name' and #args == 0 then
    return nil -- :file alone shows the file's info, Neovim's
  end
  if rule == 'count' and (#args > 1 or (#args == 1 and not args[1]:match('^%d+$'))) then
    return nil
  end
  local cmd = 'Eclipse' .. (p.bang and '!' or '') .. ' ' .. p.cmd
  if file_commands[p.cmd] and #args > 0 then
    local words, why = file_words(table.concat(args, ' '), p.cmd)
    if not words then
      refusing[p.cmd] = why
      return 'Eclipse ' .. p.cmd -- without the name: none of it runs
    end
    return cmd .. ' ' .. FINAL .. ' ' .. table.concat(vim.tbl_map(fargs_word, words), ' ')
  end
  if #args > 0 then
    -- As typed: a name keeps its backslashes (one before a space or a %), but
    -- nvim_parse_cmd took away the one before |, " and a line break, so it goes back.
    -- :Eclipse takes a | after it (bar = true): without the backslash, a cloned
    -- repository's file named x|lua(os.execute)(...) ran that Lua when its name was
    -- completed into :e and Enter pressed, and a " began a
    -- comment. :Eclipse gets the backslashed | and " back as | and ", as any command
    -- marked -bar does. A line break would end the line there: refused, as for files.
    local typed_args = {}
    for i, a in ipairs(args) do
      if a:find(LINE_BREAK) then
        refusing[p.cmd] = 'a name with a line break is not supported'
        return 'Eclipse ' .. p.cmd
      end
      typed_args[i] = (a:gsub('[|"]', BS .. '%0'))
    end
    cmd = cmd .. ' ' .. table.concat(typed_args, ' ')
  end
  return cmd
end

-- The line to run instead of `line`, or nil to leave it alone. Each command of a | chain
-- is looked at, not just the first: in `:w | q` Neovim writes (through Eclipse, the write
-- hook), then Eclipse closes the editor, asking first if the save failed. Without this
-- the q would be Neovim's, and on its last window Neovim would end.
-- Example: `w | q` becomes `w | Eclipse quit`, `q | echo 1` becomes `Eclipse quit | echo 1`.
-- nvim_parse_cmd gives what follows the | as nextcmd, the end of the line as typed
-- (`w  |  q` has nextcmd `q`); a command that takes | as an argument (:normal, :g,
-- :let) has none, so the rest of its line stays as typed.
local function replacement(line)
  local ok, p = pcall(vim.api.nvim_parse_cmd, line, {})
  if not ok then
    return nil -- Neovim reports it when it runs the line
  end
  local own = eclipse_form(p)
  local rest -- the rest of the chain rewritten, or nil if none of it changed
  local after = p.nextcmd
  if after ~= '' and line:sub(-#after) == after then
    rest = replacement(after)
  end
  if own then
    return after == '' and own or (own .. ' | ' .. (rest or after))
  end
  if rest then
    return line:sub(1, #line - #after) .. rest -- this command as typed, its | included
  end
  return nil
end

-- Safety net: a :quit that Neovim runs itself (<Cmd>q, vim.cmd.quit(), :let x = 1 | q) closes the
-- editor too. It cannot stop the quit (on the last window Neovim ends anyway). Not for the
-- command-line window, whose :quit closes only that window, nor
-- while Eclipse ends Neovim (:qa!), which the session ignores.
vim.api.nvim_create_autocmd('QuitPre', {
  desc = 'Eclipse closes the editor',
  callback = function()
    -- Nor for a quit left to Neovim (leave_to_neovim): it closes a floating window, whose
    -- buffer may be a document's (a plug-in's preview of another file).
    if vim.fn.getcmdwintype() == '' and not left_to_neovim('quit') then
      vim.rpcnotify(vim.g.neovim4eclipse_channel, 'eclipse_buffer_closed', vim.api.nvim_get_current_buf())
    end
  end,
})

vim.api.nvim_create_autocmd('CmdlineLeavePre', {
  desc = 'Eclipse carries out :w, :q, :e, :tabn ...',
  callback = function()
    -- Only a : line, and only when Enter runs it (v:char is the key that ends the line:
    -- CR or NL, spelled as string.char because a backslash escape is Java's too).
    if vim.fn.getcmdtype() ~= ':' or (vim.v.char ~= string.char(13) and vim.v.char ~= string.char(10)) then
      return
    end
    local line = vim.fn.getcmdline()
    open_commands = {} -- +cmds whose files Eclipse never showed
    refusing = {} -- a line that never got to its :Eclipse (an error before it) left some
    local cmd = replacement(line)
    if cmd then
      typed = line
      vim.fn.setcmdline(cmd)
    end
  end,
})
