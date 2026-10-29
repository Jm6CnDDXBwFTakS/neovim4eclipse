-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
-- SPDX-License-Identifier: EPL-2.0

--[[
Runs one test of a *_spec.lua file inside a headless Neovim, against the plug-in's Lua scripts
(core/LuaScripts), without Eclipse or the Java session.

Why this tier exists
--------------------
Since Neovim 0.12.6 much of the plug-in's logic is Lua that runs inside Neovim: which ex commands
Eclipse carries out (HOST_COMMANDS), the write hook (DOCUMENT_BUFFER), the yank hook and the
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

How it is run
-------------
core/LuaScriptsInNeovimTest (JUnit, tagged "integration") writes the scripts' current text to a
Lua module and starts, per test:

  nvim --clean --headless --cmd "let g:neovim4eclipse=1" -l harness.lua <module> <spec> --number <n>

which runs the spec's n-th test (1 is the first declared). With "--list" instead it prints the
spec's test names, one per line after "TEST\t", in that order. By hand, a test name works in place
of "--number <n>". JUnit passes the number, not the name, because a name may hold a double quote
('"+p asks Eclipse ...'), and Windows splits a command-line argument at such a quote: Java does not
escape it when it builds the command line (Linux passes arguments one by one and has no such issue).
A test passes when the process ends with status 0; a failed check prints "FAIL: ..." and a
traceback, and ends with status 1. By hand, from tests/unit, after one JUnit run has written the
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
    t.eq({ { 'wall', false, {} } }, t.sent('eclipse_command'))
  end)
]]

local module_path, spec_path, wanted = arg[1], arg[2], arg[3]
local generated = dofile(module_path) -- { scripts = { SETUP = '...', ... }, host_rules = '...', ide_keys = { ... } }

local t = {
  --- HostCommand.rulesForNeovim(): HOST_COMMANDS's argument, as the session passes it.
  host_rules = generated.host_rules,
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
--- then IDE_KEYS with core/IdeKeys's table (gd, K, gt), as the session does next.
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

--- Runs Neovim's event loop until condition() holds (work deferred with vim.schedule, a timer), and
--- fails after 5 s.
function t.wait_for(what, condition)
  if not vim.wait(5000, condition, 10) then
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

--- Fails unless `expected` and `actual` are equal (deeply, for tables).
function t.eq(expected, actual, what)
  if not vim.deep_equal(expected, actual) then
    error(('%s\n  expected: %s\n  actual:   %s'):format(what or 'not equal', vim.inspect(expected),
      vim.inspect(actual)), 2)
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
io.stdout:write('FAIL: ', test[1], ': ', tostring(err), '\n')
os.exit(1)
