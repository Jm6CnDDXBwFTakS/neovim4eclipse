-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

--[[
Runs one test of a *_spec.lua file inside a headless Neovim, against the plug-in's Lua scripts
(core/LuaScripts, and the files in core/lua/ that it reads), without Eclipse or the Java session.

Why this tier exists
--------------------
Since Neovim 0.12.6 much of the plug-in's logic is Lua that runs inside Neovim: which ex commands
Eclipse carries out (HOST_COMMANDS), the write hook (DOCUMENT_BUFFER_MODULE), the yank hook and the
clipboard provider (SETUP), leaving a folder (LEAVE_FOLDER). Through the Java session such a test
needs a Neovim process, the message channel, two threads and waits. Here the scripts run in the
Neovim that runs the test, and what they send to Eclipse is recorded instead of sent:

  * vim.rpcnotify is replaced by a recorder (t.sent), vim.rpcrequest by answers the test gives
    (t.answer). The scripts look these functions up when they call them, so the stubs apply.
  * Keys are typed with nvim_feedkeys(keys, 'xt'): 'x' runs them to the end before it returns,
    't' makes them count as typed, so mappings, the command line and its autocmds (CmdlineLeavePre)
    behave as when a person types. No waiting, no race: when t.type returns, everything is done.

One limit: when the keys run out, 'x' ends a pending mode as Esc would (an operator, the command
line, the command-line window). So a test cannot show that something stays open, or that a script
closed it; those checks stay in the integration tests, whose keys arrive one at a time. To look
while the mode is still on, use t.during(keys, check), which runs the check from a mapping. Work a
script deferred (vim.schedule) runs only once the event loop does: t.wait_for(condition).

The Java side of each case (what the plug-in does with a notification) is tested in tests/unit
without Neovim (NotificationBridgeTest, HostCommandTest, DocumentWriteTest...). What only the
real session can show stays in the integration tests.

File names in checks
--------------------
Compare file names with t.same_file(expected, actual), never with t.eq: the same file is spelled
differently on macOS (/var is a link to /private/var, and Neovim stores buffer names resolved) and
on Windows (\ or /, any case). t.same_file uses the plug-in's own rule (LuaScripts.PATHS), so a
spec that passes on Linux passes on the other two as well.

How it is run
-------------
core/LuaScriptsInNeovimTest (JUnit, tagged "integration") writes the scripts' current text to a
Lua module and starts, per test (a script made of a file in core/lua/ is the text the session
sends, which compiles the file as a chunk named after it: an error reads
neovim4eclipse/core/lua/folds.lua:120, the file's line):

  nvim --clean --headless --cmd "let g:neovim4eclipse=1" -l harness.lua <module> <spec> --number <n>

which runs the spec's n-th test (1 is the first declared). With "--list" instead it prints the
spec's test names, one per line after "TEST\t", in that order. By hand, a test name works in place
of "--number <n>". JUnit passes the number, not the name, because a name may hold a double quote
('"+p asks Eclipse ...'), and Windows splits a command-line argument at such a quote: Java does not
escape it when it builds the command line (Linux passes arguments one by one and has no such issue).
A test passes when the process ends with status 0; a failed check prints "FAIL: ..." and a
traceback, and ends with status 1. A test that cannot run on this system calls t.skip(why), which
prints "SKIP: <why>" and ends with status 0; JUnit reports it as skipped, with that reason. Such a
test is declared on every system, so each one lists the same tests. By hand, from tests/unit, after one JUnit run has written the
module to target/lua-tests/scripts.lua:

  nvim --clean --headless --cmd "let g:neovim4eclipse=1" -l src/test/lua/harness.lua \
    target/lua-tests/scripts.lua src/test/lua/host_commands_spec.lua --list

and then, with one of the names printed (or "--number 2" for the second test):

  nvim --clean --headless --cmd "let g:neovim4eclipse=1" -l src/test/lua/harness.lua \
    target/lua-tests/scripts.lua src/test/lua/host_commands_spec.lua \
    "arguments are passed as typed, a count becomes the argument, and | goes on"

A spec file gets the harness as its argument:

  local t = ...
  t.test(':wa goes to Eclipse', function()
    t.run('HOST_COMMANDS', t.host_rules)
    t.type(':wa<CR>')
    t.eq({ { 'wall', false, {} } }, t.commands())
  end)
]]

local module_path, spec_path, wanted = arg[1], arg[2], arg[3]
local generated = dofile(module_path) -- { scripts = { SETUP = '...', ... }, host_rules = '...', ide_keys = { ... } }

-- The one rule for comparing file paths, run first as the session runs it (LuaScripts.PATHS): the
-- scripts and t.same_file below use it.
assert(load(generated.scripts.PATHS, '=LuaScripts.PATHS'))()
-- Then the document buffers' module, as the session sends it next
-- (LuaScripts.DOCUMENT_BUFFER_MODULE): DOCUMENT_BUFFER, t.document below, calls it for each buffer.
assert(load(generated.scripts.DOCUMENT_BUFFER_MODULE, '=LuaScripts.DOCUMENT_BUFFER_MODULE'))()

local t = {
  --- HostCommand.rulesForNeovim(): HOST_COMMANDS's argument, as the session passes it.
  host_rules = generated.host_rules,
  --- JumpLabels.BUILT_IN_PREFIXES: HIGHLIGHTS's argument, as the session passes it.
  jump_label_prefixes = generated.jump_label_prefixes,
  --- The folder of the spec being run, for files beside it (../resources/path-cases.tsv).
  spec_dir = vim.fs.dirname(vim.fs.normalize(vim.fn.fnamemodify(spec_path, ':p'))),
  --- The channel number the scripts send to (any number: nothing is sent).
  channel = 7,
}

local tests = {} -- { { name, fn }, ... } in the spec's order
local notifications = {} -- { { name, arg1, ... }, ... } as the scripts sent them
local answers = {} -- request name -> function(...) returning the answer

vim.rpcnotify = function(_, name, ...)
  notifications[#notifications + 1] = { name, ... }
  return true
end

vim.rpcrequest = function(_, name, ...)
  local answer = answers[name]
  if not answer then
    error('the test gives no answer to the request ' .. name, 2)
  end
  return answer(...)
end

--- Declares a test (called by the spec file).
function t.test(name, fn)
  tests[#tests + 1] = { name, fn }
end

--- Marker of a skip: the error value t.skip raises, told apart from a failure after the run.
local SKIP = {}

--- Ends the running test as skipped, with the reason JUnit shows. For a test that cannot run on
--- this system: declare it on every system and skip it here, so each one runs the same list of
--- tests (the same count in every report) and the report says why one did not run, rather than
--- leaving it out or letting it pass with nothing checked. Example:
---   if vim.fn.has('win32') == 1 then t.skip('Windows names cannot hold a line break') end
function t.skip(why)
  error({ [SKIP] = why }, 0) -- a table, which debug.traceback passes on unchanged
end

--- Runs one of LuaScripts' scripts with its arguments, as nvim_exec_lua does, and returns its result.
--- Example: t.run('DOCUMENT_BUFFER', tostring(buf)).
function t.run(name, ...)
  local code = generated.scripts[name]
  if not code then
    error('LuaScripts has no script ' .. name, 2)
  end
  return assert(load(code, '=LuaScripts.' .. name))(...)
end

--- Runs SETUP with the test channel, as the session does first (g:neovim4eclipse_channel included),
--- then IDE_KEYS with core/IdeKeys's table (gd, K, the gr* keys), as the session does next.
function t.setup()
  local result = t.run('SETUP', tostring(t.channel))
  t.run('IDE_KEYS', unpack(generated.ide_keys))
  return result
end

--- Types keys as a person would, in Neovim's key notation ('<CR>', '<Esc>', '<Space>'), and returns
--- once Neovim has handled every one of them.
function t.type(keys)
  vim.api.nvim_feedkeys(vim.api.nvim_replace_termcodes(keys, true, false, true), 'xt', false)
end

--- Types `keys`, then runs check() while their mode is still on (visual mode, an operator, the
--- command line), and returns its result. t.type alone ends such a mode when the keys run out (see
--- the top of this file); here check() runs from a mapping on the key after them, as a script
--- Eclipse sends then would. Example: t.during('gg<C-v>jl', function() return t.run('STATE_SNAPSHOT', '0') end).
local CHECK_KEY = '<F35>' -- a key no keyboard sends
function t.during(keys, check)
  local modes = { 'n', 'x', 's', 'o', 'i', 'c' }
  local done, result = false, nil
  vim.keymap.set(modes, CHECK_KEY, function()
    result, done = check(), true
  end)
  t.type(keys .. CHECK_KEY)
  vim.keymap.del(modes, CHECK_KEY)
  if not done then
    error('the check never ran: the keys left no mode for its key', 2)
  end
  return result
end

--- How long t.wait_for waits at most: the limit every test waits with (TestWait in Java,
--- -Dtest.wait.seconds, 15 s by default), which LuaScriptsInNeovimTest passes in $TEST_WAIT_MS. A
--- limit of the harness's own would fail a spec on a slow machine with nothing wrong, as 5 s once
--- could. Run by hand, without the variable, it is TestWait's default.
local WAIT_MS = tonumber(vim.env.TEST_WAIT_MS) or 15000

--- Runs Neovim's event loop until condition() holds (work deferred with vim.schedule, a timer), and
--- fails after WAIT_MS. It returns as soon as condition() holds, so the limit costs a passing test
--- nothing.
function t.wait_for(what, condition)
  if not vim.wait(WAIT_MS, condition, 10) then
    error('timed out waiting for ' .. what, 2)
  end
end

--- The arguments of every notification named `name` sent so far, in order, each as a list.
--- Example: after :wa, t.sent('eclipse_command') is { { 'wall', false, {} } }.
function t.sent(name)
  local found = {}
  for _, n in ipairs(notifications) do
    if n[1] == name then
      found[#found + 1] = vim.list_slice(n, 2)
    end
  end
  return found
end

--- The commands sent to Eclipse (eclipse_command) so far, each as { name, bang, args }: what
--- t.sent('eclipse_command') gives without the fourth value, the window the command ran in
--- (HostCommand.origin), which most tests do not look at. Example: after :wa,
--- t.commands() is { { 'wall', false, {} } }.
function t.commands()
  local found = {}
  for _, c in ipairs(t.sent('eclipse_command')) do
    found[#found + 1] = { c[1], c[2], c[3] }
  end
  return found
end

--- Forgets the notifications sent so far.
function t.forget()
  notifications = {}
end

--- Answers the request `name` with fn(...)'s result from now on (Eclipse's side of a vim.rpcrequest).
function t.answer(name, fn)
  answers[name] = fn
end

--- A new buffer for a file `name` in a fresh temporary folder, made a document buffer
--- (DOCUMENT_BUFFER), and shown. Returns its number and its full name.
function t.document(name, lines)
  local dir = vim.fn.tempname()
  vim.fn.mkdir(dir, 'p')
  local path = dir .. '/' .. name
  vim.cmd.edit(vim.fn.fnameescape(path))
  local buf = vim.api.nvim_get_current_buf()
  t.run('DOCUMENT_BUFFER', tostring(buf))
  if lines then
    vim.api.nvim_buf_set_lines(buf, 0, -1, false, lines)
  end
  return buf, vim.api.nvim_buf_get_name(buf)
end

--- The folder of the Neovim plug-in pinned as `name` (releng/test-plugins.properties), or a skip:
--- not fetched (no network when the tool ran), or another commit or archive than the pin. The
--- plug-ins are not in the repository: `java releng/tools/plugins/TestPlugins.java fetch` unpacks
--- them into .tool-cache/test-plugins/ (a copy of common.ToolCache.TEST_PLUGINS, which
--- ToolCacheTest checks; not under target/, so ./mvnw clean leaves them in place). The marker it
--- writes beside each, "<commit> <sha256>", must be the pin's. Example: t.pinned_plugin('flash').
function t.pinned_plugin(name)
  local root = vim.fs.normalize(t.spec_dir .. '/../../../../..')
  local folder = '.tool-cache/test-plugins'
  local commit, sha
  for line in io.lines(root .. '/releng/test-plugins.properties') do
    commit = commit or line:match('^' .. vim.pesc(name) .. '%.commit=(%x+)%s*$')
    sha = sha or line:match('^' .. vim.pesc(name) .. '%.sha256=(%x+)%s*$')
  end
  if not commit or not sha then
    error('no pin named ' .. name)
  end
  local dir = root .. '/' .. folder .. '/' .. name
  local marker = io.open(dir .. '.pin')
  if not marker or vim.fn.isdirectory(dir) == 0 then
    if marker then
      marker:close()
    end
    t.skip(name .. ' is not fetched: java releng/tools/plugins/TestPlugins.java fetch')
  end
  local there = marker:read('*l') or ''
  marker:close()
  if vim.trim(there) ~= commit .. ' ' .. sha then
    t.skip(name .. ' is fetched at another commit or archive than the pin: java releng/tools/plugins/TestPlugins.java fetch')
  end
  return dir
end

--- Fails unless `expected` and `actual` are equal (deeply, for tables).
function t.eq(expected, actual, what)
  if not vim.deep_equal(expected, actual) then
    error(('%s\n  expected: %s\n  actual:   %s'):format(what or 'not equal', vim.inspect(expected),
      vim.inspect(actual)), 2)
  end
end

--- Fails unless `expected` and `actual` name the same file, by the plug-in's one rule for paths
--- (neovim4eclipse.paths: separators, case where the system ignores it, links, .. resolved). Use it
--- for every comparison of file names: a raw t.eq of two spellings of one file passes on Linux and
--- fails on macOS (/var is /private/var) or Windows (\ or /), as quickfix_spec once did twice.
function t.same_file(expected, actual, what)
  local paths = package.loaded['neovim4eclipse.paths']
  if not paths.same(expected, actual) then
    error(('%s\n  expected: %s\n  actual:   %s\n  (canonical: %s and %s)'):format(what or 'not the same file',
      vim.inspect(expected), vim.inspect(actual), paths.canonical(expected), paths.canonical(actual)), 2)
  end
end

--- Fails unless `condition` holds.
function t.ok(condition, what)
  if not condition then
    error(what or 'expected true', 2)
  end
end

assert(loadfile(spec_path))(t)

if wanted == '--list' then
  for _, test in ipairs(tests) do
    io.stdout:write('TEST\t', test[1], '\n')
  end
  os.exit(0)
end

--- The test to run: by number ("--number 3", as JUnit asks) or by name (by hand).
local function find_test()
  if wanted == '--number' then
    local n = tonumber(arg[4])
    return n and tests[n], ('number ' .. tostring(arg[4]))
  end
  for _, test in ipairs(tests) do
    if test[1] == wanted then
      return test
    end
  end
  return nil, ('named ' .. tostring(wanted))
end

local test, which = find_test()
if not test then
  io.stdout:write('FAIL: no test ', which, ' in ', spec_path, ' (it has ', #tests, ')\n')
  os.exit(1)
end
local ok, err = xpcall(test[2], debug.traceback)
if ok then
  io.stdout:write('PASS\n')
  os.exit(0)
end
if type(err) == 'table' and err[SKIP] then
  io.stdout:write('SKIP: ', tostring(err[SKIP]), '\n')
  os.exit(0)
end
io.stdout:write('FAIL: ', test[1], ': ', tostring(err), '\n')
os.exit(1)
