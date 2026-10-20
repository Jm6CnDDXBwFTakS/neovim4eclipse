# Design note: Tab completion in the `:` command line

Status: **planned, not started.** Written 2026-10-20 against `develop` at `b56571c`, with Neovim 0.12.4
on Windows 11 for the experiments. Decisions made with the maintainer are marked *(decided)*.

## Summary

The `:` box (`editor/CommandLineBox`) is a plain SWT text field. It has never completed anything:
no commit ever handled Tab or asked Neovim for completions, and since the box opens for every `:`,
Neovim's own command line, which does complete, is never reached. In an SWT single-line field, Tab
moves the keyboard focus to the next control, and the box closes when it loses focus, so Tab most
likely just cancels the box today (not tried by hand).

**Plan:** on Tab, let Neovim's own command-line completion do the work. One Lua call types `:` and
the box's text plus the Tab key(s) into Neovim, reads back the completed line, cancels, and cleans
up. The box shows the result and a one-line list of the candidates. Completion is then exactly
Neovim's (commands, options and their values, buffers, help tags, variables, functions, file names),
including the user's `'wildmode'`, `'wildignore'` and `'wildignorecase'`. The one exception is
`:e`, whose file names must be completed the way the plug-in's `:e` resolves them (section 3).

Size: about 350 lines of code and 400 of tests, in two commits (section 7).

## 1. Behavior

- **Tab** completes the text in the `:` box; **Shift+Tab** goes back. *(decided)* Cycling follows
  the user's `'wildmode'`, with its `list` parts removed while completing (section 4). With
  Neovim's default, `full`, each Tab gives the next match, and after the last one the box is back
  at what was typed.
- **A one-line list of the candidates** appears above the box, in the place the command output
  already uses, with the current candidate highlighted. *(decided)* When it does not fit, it shows
  a window around the current candidate, with `…` at the cut ends.
- **Typing, deleting or moving the caret** ends the cycle; the next Tab completes the new text.
  Enter runs the line and Esc cancels it, as now; both also close the list.
- **`:e` / `:edit` file names** complete against the edited file's folder, then its project root:
  the same order `:e` uses to find the file (section 3). Absolute and `~` paths are left as typed.
- **`/` and `?` boxes:** Tab does nothing and keeps the box open (Vim does not complete searches).
- **Neovim busy or at a prompt:** Tab does nothing. The UI thread never waits for Neovim.

## 2. How Neovim does the completing

Neovim offers `getcompletion(text, 'cmdline')`, which returns the candidates for a command line.
It is not enough on its own: it does not say which part of the line a candidate replaces, and that
varies (results from the experiments):

| Text | `getcompletion` gives | What Tab makes of the line |
|---|---|---|
| `tabn` | `tabnew`, `tabnext` | `tabnew`, then `tabnext`, then `tabn` |
| `set ts=` | `8` | `set ts=8` (appended) |
| `echo &tab` | `tabclose`, `tabline`, ... | `echo &tabclose` (replaces `tab`) |
| `let g:ecl` | `g:eclipse_channel` | `let g:eclipse_channel` (replaces `g:ecl`) |
| `h bufd` | `:bufdo`, `BufDelete` | `h :bufdo` (replaces `bufd`) |
| `e with` | `with space.txt` | `e with\ space.txt` (space escaped) |
| `e sub/` (Windows) | `sub\x.java` | `e sub\x.java` |

So the line itself comes from Neovim's real command line: the Lua script types it and the Tabs,
then reads `getcmdline()`. `getcompletion` only supplies the list shown above the box.

**The prototype that worked** (Neovim 0.12.4, run through `nvim_exec_lua` over RPC from a client
attached as a UI, as the plug-in is). Arguments: the line, and how many Tabs to type:

```lua
local line, tabs = ...
local result = {}
_G.__eclipse_capture = function()
  result.line = vim.fn.getcmdline()
  result.list = vim.fn.getcompletion(line, 'cmdline')
  return ''   -- <C-\>e replaces the command line with this; <C-c> then cancels it
end
local hist = vim.fn.histnr(':')
local keys = ':' .. line:gsub('<', '<lt>') .. string.rep('<Tab>', tabs)
    .. [[<C-\>ev:lua.__eclipse_capture()<CR><C-c>]]
-- do_lt = true, so <lt> becomes '<'; 'x' runs the keys now, 'n' ignores the user's mappings
vim.api.nvim_feedkeys(vim.api.nvim_replace_termcodes(keys, true, true, true), 'xtn', false)
if vim.fn.histnr(':') > hist then vim.fn.histdel(':', -1) end   -- a cancelled line is kept in history
_G.__eclipse_capture = nil
return { result.line, result.list }
```

Confirmed with it: `e al` with 1, 2, 3 Tabs gave `e alpha.txt`, `e alpine.txt`, `e al`; escaping of
spaces and `<`; Neovim back in normal mode afterwards; the `:` history unchanged. Without the
`histdel`, every completion added an entry to the history (so `@:` would repeat it).

**Why one call, without state in between:** each Tab re-types the original text plus N Tabs. Neovim
is never left in command-line mode while the box is open, where keys from the editor or the
session's own requests could land in it. Neovim applies its `'wildmode'` rules to the N Tabs
itself, so the plug-in does not reimplement them. The cost is one RPC round trip per Tab.

**Not usable:** `cmdcomplete_info()` exists in 0.12 but returned no matches in this setup, and is
missing in older versions; `getcompletion` stays the source of the list. The list can then miss
what Tab completes in rare cases: after `echo "<x>" |` it returned nothing (Tab completed nothing
there either, as in Vim).

## 3. `:e` file names

Vim resolves a relative file name against its working directory, which for the embedded Neovim is
wherever Eclipse was started. The plug-in's `:e` instead looks in the edited file's folder, then the
project root (`util/EditPath`, `EclipseFileCommands.findFile`). Completion has to use the same two
folders, or Tab would offer names that `:e` then cannot find.

When the line is an `:e` (decided by the existing `util/HostExCommand` parser, kind `EDIT`), the
script runs `noautocmd lcd` to the first folder before typing, and tries the second if the first
completes nothing. Restoring the directory afterwards was confirmed: if the window had no local
directory before, `noautocmd cd <global directory>` clears the window-local one again
(`haslocaldir()` back to 0); if it had one, `lcd` back to it. `noautocmd` keeps `DirChanged` from
firing.

On Windows, file candidates come back with backslashes (`sub\x.java`). The plug-in's `:e` accepts
both kinds of slash there, so nothing needs converting.

## 4. `'wildmode'` *(decided: follow it)*

Neovim's default is `full`, so for most users the result is the same as fixed cycling. Users who
set, for example, `longest:full` get the common part first and cycling after, as in their own
Neovim.

The `list` parts are removed for the duration of the call: `list:longest` becomes `longest`,
`longest,list` becomes `longest`, `list:full` becomes `full`. They would make Neovim print the
matches into its message area, which nobody sees in the embedded Neovim and which could leave it
waiting at a "Press ENTER" prompt. The box's own list takes their place. With plain `longest`, Tab
never cycles; the list is then the only way to see the other matches.

Pros and cons as weighed with the maintainer:
- For: Tab behaves as in the user's Neovim; nothing to reimplement, Neovim applies the rules.
- Against: the `list` parts must be removed; more combinations to test and describe.

## 5. Implementation, by layer

1. **`core/LuaScripts.COMPLETE_CMDLINE`**: the prototype above, plus:
   - arguments: the line, the number of Tabs (negative: that many Shift+Tabs, `<S-Tab>`), and up to
     two folders for `:e` (empty strings when not an `:e`);
   - the key in `'wildchar'` instead of a literal `<Tab>` (the user may have changed it);
   - `'wildmode'` without its `list` parts, and `'eventignore'` extended with `CmdlineEnter`,
     `CmdlineChanged` and `CmdlineLeave` so that user plug-ins do not react; both restored after;
   - the `lcd` and the second-folder retry of section 3;
   - everything after the first change wrapped so that the options, the directory, the history and
     the global function are restored even if a step fails (`pcall`, then restore, then rethrow);
   - the list capped (about 200 entries) and returned with its full count.
2. **Session**: `NeovimSession.completeAsync(line, tabs, folders, onResult)`, queued on the session
   thread like `captureOutputAsync`; skipped when Neovim is blocked at a prompt. A small record in
   `core` holds the answer (line, candidates, total), with a parser unit-tested like
   `StateSnapshot`. Remember the `static final String` pitfall in AGENTS.md: after changing the
   script, delete `plugins/.../target/classes`.
3. **Pure logic, `util/CommandCompletion`**: the cycle state (the text when Tab was first pressed,
   the Tab count, a generation number so that an answer arriving after more typing is dropped),
   which candidate to highlight, and fitting the list into the box's width (the window with `…`).
   Whether a partial line is an `:e` comes from `HostExCommand`.
4. **The box, `editor/CommandLineBox`**:
   - a traverse listener stops SWT's Tab and Shift+Tab traversal in every box (`doit = false`);
   - in the `:` box, Tab and Shift+Tab call a new `Commands.complete(text, tabs, onResult)` and
     apply the answer on the UI thread only if the box is still open and the generation matches
     (text replaced through the `programmatic` flag, caret at the end);
   - the list is a small `StyledText` line above the box with the current candidate highlighted,
     hidden on any edit, Enter or Esc.
5. **`NeovimEditorController.SessionCommands`** implements `complete`, passing the two `:e`
   folders. The folder logic moves out of `EclipseFileCommands.findFile` into a helper both use.

## 6. Tests

- **`tests/unit`**: `CommandCompletion` (cycling, Shift+Tab, a late answer after typing, list
  fitting with `…`); the result parser; `HostExCommand` on partial lines (`e al`, `edit! x`).
- **`tests/unit -Pintegration`**, real Neovim:
  - `tabn` cycles and comes back to `tabn`; Shift+Tab; `set ts=` gives `set ts=8`;
  - `:e` in a temporary folder, the fallback to the second folder, a name with a space, a line with `<`;
  - afterwards: the `:` history, the working directory (and `haslocaldir()`), `'wildmode'` and
    `'eventignore'` unchanged, Neovim in normal mode;
  - `wildmode=longest:full` is followed; `wildmode=list:longest` does not leave Neovim at a prompt.
- **`tests/swt`**: the box with a fake `Commands`: Tab sends the text and the count; the answer
  replaces the text; a stale answer is dropped; the list shows, highlights and hides; Tab in a `/`
  box keeps the box open.
- **UI test**, one end to end: `:`, `e te`, Tab gives `e test.txt`, Enter opens it.

## 7. Commits, docs

Two commits, in UTC, as AGENTS.md asks:
1. The Neovim side: the script, the session method, the result record and parser, unit and
   integration tests.
2. The box: Tab handling, the cycle, the list, the shared `:e` folders, SWT and UI tests; the
   README (command-line section), `docs/ARCHITECTURE.md` (feature table) and an *Added* entry in
   `CHANGELOG.md`. Set this note's status to done.

## 8. To check while implementing

- **Neovim 0.9**, the oldest supported version: `<C-\>e` with `v:lua` and `nvim_feedkeys` in
  command-line mode should work there; only 0.12 was tried.
- **The grid:** the plug-in attaches as a UI with every `ext_*` option off
  (`NeovimConnection.TERMINAL_UI`), so the typed command line is drawn into Neovim's grid rather
  than sent as events. The plug-in does not show the grid, so nothing should be visible; confirm
  that nothing flickers and no status-bar text changes.
- **Speed:** `:h ` or `:e ` in a large folder gives thousands of candidates; check that the call
  stays well under the session's command timeout, and that capping the list keeps the reply small.
- **What Tab does in the box today** (expected: closes it), to describe the change correctly in
  `CHANGELOG.md`.
