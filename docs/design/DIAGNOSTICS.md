# Design note: Eclipse problems as Vim diagnostics and a quickfix list

Eclipse knows where the errors and warnings are: the Java compiler, Checkstyle, a language server
(LSP4E) and other builders report them, and the editor underlines them. The plug-in hands them to
Neovim, so that the Vim way of moving between problems works:

- **Diagnostics.** The problems of every open document are mirrored into Neovim with
  `vim.diagnostic.set()`, in one namespace of the plug-in's own. Neovim's own `]d` / `[d` then
  work, with counts, in macros, and in the jumplist (`Ctrl-O` comes back); the plug-in adds no key
  of its own (sections 3 and 4).
- **A quickfix list** of the whole workspace's problems, or of chosen tasks: `:Eproblems`,
  `:Etasks`, walked with Neovim's own `]q` / `[q` or `:cnext` / `:cprev` / `:cc N`. Neovim runs
  them itself; the plug-in opens the file's editor in Eclipse when it is not open yet (section 5).
- **Neovim's `gr*` keys** (meant for a language server inside Neovim) run their Eclipse
  counterparts: `gra` Quick Fix, `grn` Rename, `grr` References, and so on (section 6).

Example: a Java file with an unused import on line 3 and a type error on line 40. With the cursor
on line 1, `]d` goes to line 3, `2]d` to line 40, `[d` back, `Ctrl-O` to where `]d` started.
`:Eproblems` then `]q` in a workspace with errors in three files opens the next file's editor at
its first error.

**Why:** jumping between compile errors is constant in Java work, the keys are standard
Vim/Neovim, the data already exists in Eclipse, and the decisions fit in small pure classes. It
also fits the project's goal that Neovim does the work: with Neovim 0.12.6 as the minimum
(`NeovimVersion.MINIMUM`), Neovim's defaults (`]d`, `]q`, `on_jump`) do all the moving, and the
plug-in only supplies the lists.

## 1. What a user gets

Diagnostics in open documents:

- **`]d` / `[d`**: next / previous problem in the current file, with a count (`3]d`). These are
  Neovim's own default mappings (`vim.diagnostic.jump({ count = v:count1 })`); the plug-in maps
  nothing, so a user's own mapping in `init.lua` wins.
- **`]D` / `[D`**: last / first problem (Neovim defaults).
- **Tasks** (a `TODO`-style comment) are not diagnostics, so `]d` never stops on one. They are
  walked through the quickfix list instead: `:Etasks`, then Neovim's `]q` / `[q`. There are no task
  keys of the plug-in's own: Neovim 0.12 already maps `]t` / `[t` to `:tnext` / `:tprevious` (tags).
- **The message** of the problem the cursor lands on is shown in the message line, as Eclipse's
  status bar does after Ctrl+. (*Next Annotation*). Example: `]d` to line 40 shows
  `Type mismatch: cannot convert from int to String`. This is Neovim's `on_jump` hook, set by the
  plug-in only when the user's `init.lua` set none (section 4).
- **`K` right after `]d`** opens Eclipse's hover for the problem, with its quick fixes in Java (`K`
  is *Show Tooltip Description*); `]d` does not open it by itself (section 4 says why).
- **`gra`**: Eclipse's *Quick Fix* (Ctrl+1) at the cursor, with the other `gr*` keys (section 6).
- Nothing new is drawn: Eclipse already underlines problems and shows them in the ruler, so
  Neovim's own display (signs, virtual text, underline) is turned off for the plug-in's namespace.
  Neovim's windows are never shown anyway (one hidden tabpage per editor).
- Works in any text editor whose document has problem annotations: Java, generic editor with
  LSP4E, XML, plain text with markers.

The quickfix list:

- **`]q` / `[q`, `]Q` / `[Q`, `]<C-Q>` / `[<C-Q>`** (Neovim's default mappings) and
  **`:cn[ext]`, `:cp[revious]`, `:cN[ext]`, `:cc N`, `:cfir[st]`, `:cla[st]`, `:cnf[ile]`**: walk
  Neovim's current quickfix list, whatever filled it: Eclipse's problems or tasks, `:vimgrep`, or
  the user's own `setqflist()`. Neovim runs every one of them itself, typed, mapped or in a macro;
  an entry in a file that has no editor yet opens its editor, the way `:e file` does
  (docs/design/BUFFER_NUMBERS.md, 2.3).
- **`:cl[ist]`**: the list, in the output popup (it is ordinary command output).
- **`:copen` / `:cw[indow]`**: Eclipse's Problems view, or the Tasks view for `Eclipse tasks`, or
  the output popup for other lists (5.4).
- **`:Eproblems` / `:Etasks`** fill the quickfix list with the workspace's problems or tasks, and
  keep it current while it is the current list (5.2).

Not done: Neovim's diagnostic float (`<C-w>d`, a Neovim default, and
`vim.diagnostic.open_float()`): a float would open in a Neovim window nobody sees (the editors' windows sit in hidden tabpages,
docs/design/WINDOWS.md). `K` already shows Eclipse's hover, which includes the problem text,
and the message line shows it after each jump.

## 2. Experiments (diagnostics)

Run with `nvim --headless --clean` on Neovim 0.12.6 (first on 0.12.4, with the same results), on
Linux: a 50-line buffer with three diagnostics set by `vim.diagnostic.set`, keys sent with
`nvim_feedkeys`. The checks became `diagnostics_spec.lua`.

- `]d` is mapped by default (to a Lua function), moves to the next diagnostic, and accepts a count
  (`2]d` skipped the first). `]d`, `2]d`, `[d`, `[D`, `]D` land on the expected lines.
- `]d` adds to the jumplist: `Ctrl-O` went back to the starting position.
- `vim.diagnostic.setqflist({ open = false })` fills the quickfix list with title `"Diagnostics"`.
- `vim.diagnostic.jump` merges in the **global** `vim.diagnostic.config().jump` table, then calls
  `on_jump(diagnostic, bufnr)` with `vim.schedule`, after the cursor has moved: a synchronous check
  right after the jump still sees `nil`. There is no per-namespace jump option:
  `vim.diagnostic.config({ jump = … }, ns)` is ignored by `]d`. `jump({ namespace = ns })` limits a
  jump to one namespace.
- With nothing left to jump to (`wrap = false` past the last problem) `on_jump` is **not** called,
  and Neovim itself prints `No more valid diagnostics to move to`, which reaches the message line
  like any message.
- Neovim 0.12's defaults that matter here: `]d` `[d` `]D` `[D` `<C-W>d` (diagnostics); `]q` `[q`
  `]Q` `[Q` `]<C-Q>` `[<C-Q>` (quickfix: `:cnext` and friends, run with `nvim_cmd` from a Lua
  mapping, so the plug-in's host commands never see them); `]t` `[t` `]T` `[T` (tags: `:tnext` and
  friends); `gra` `gri` `grn` `grr` `grt` `grx` `gO` (language server).
- The `on_jump` snippet (section 4) sets `on_jump` when none is set, leaves a user's own alone, and
  the echo shows only the first line of a two-line message.
- **Macros** (`qqOnew<Esc>]dq`): a line inserted during the macro moves the diagnostics with the
  text, and `]d` lands on the shifted line (4, not 3). Neovim tracks diagnostic positions with
  extmarks, so a macro needs no update from the plug-in. `vim.diagnostic.get()` still reports the
  position as set (`lnum` unchanged) until the next `vim.diagnostic.set`; the plug-in never reads
  positions back, so this does not matter.

Not checked: how often JDT's annotation model changes while typing (its reconcile runs after about
500 ms); macro playback inside Eclipse, which applies the macro's edit to Eclipse once at the end
(docs/USER_GUIDE.md, *Macros*), so the plug-in's next `vim.diagnostic.set` after Eclipse reconciles must agree
with what Neovim already shifted; JDT's temporary problems (found while typing, before a save) in a
Java editor, which would be a `-PuiHeavyJava` test.

## 3. Design: diagnostics

```
Eclipse                                             Neovim
annotation model ──(listener, debounced)──►  DiagnosticsSync (sync/)
  of each open document                        │  reads problem annotations on the UI thread
                                               ▼
                                     DiagnosticPositions (util/, pure)
                                       offset → line + UTF-8 byte column
                                               │
                                               ▼  session.setDiagnosticsAsync(bufnr, list)
                                     LuaScripts.SET_DIAGNOSTICS: vim.diagnostic.set(ns, bufnr, items)
```

- **Source**: the annotation model of each open document
  (`IDocumentProvider.getAnnotationModel(input)`). It holds both kinds of problems: markers saved
  by a build (`MarkerAnnotation`) and JDT's *temporary* problems found while typing, before any
  save (`ProblemAnnotation`, not markers at all), so it has the problems the user sees. Markers of
  closed files are the quickfix list's business (section 5).
- **Which annotations are problems** is a function `DiagnosticsSync` is given:
  `editor/ProblemAnnotations` answers with `DefaultMarkerAnnotationAccess.isSubtype` against the
  platform's three types (`org.eclipse.ui.workbench.texteditor.error`, `warning`, `info`), which
  needs the workbench. Spelling, tasks, bookmarks, search results and occurrences are no subtype of
  them.
- **Every open document, not only the attached one.** Each open document has its own Neovim
  buffer, so `]d` works right after switching editors. Diagnostics belong to the buffer, not the
  window: two editors on one file share one buffer (docs/design/WINDOWS.md, E3) and so share its
  diagnostics, and a travelling window (its E2) finds them on whichever buffer it shows.
- **`sync/DiagnosticsSync`** needs only the text model (`IAnnotationModel` and `IDocument` are in
  the `org.eclipse.text` jar), so it is tested in `tests/text` (`DiagnosticsSyncTest`, with a real
  `AnnotationModel`).
- **Conversion** in pure `util/` classes (unit-tested, no `org.eclipse.*`): `util/Diagnostic` (the
  record, the severity numbers, the flat argument list) and `util/DiagnosticPositions` (from
  document offset and length, plus the line's text, to Neovim's `lnum` / `col` / `end_lnum` /
  `end_col`: 0-based lines, **UTF-8 byte** columns, `Utf8Columns`). Annotation positions are
  document offsets, so folding does not matter here (`WidgetOffsets` is only for `StyledText`). A
  position past the end of the text is clamped to the end of Neovim's last line rather than
  dropped: Neovim's line count has no line after a final line break, and Eclipse may not have moved
  a problem yet after a deletion.
- **Severity**: Eclipse error / warning / info → `vim.diagnostic.severity.ERROR` / `WARN` / `INFO`.
  Task markers are not problems (Eclipse lists them in the Tasks view), so they are never sent as
  diagnostics.
- **No dedupe of positions**: the sync sends nothing when the new list equals the one sent last.
  Typing moves Eclipse's positions, but Neovim's diagnostics move with the text on their own
  (extmarks, section 2), so only a change of the problems needs a list.
- **Threads**: annotations are read on the UI thread (one pass over the model), the list is sent
  with an `*Async` call, never blocking RPC on the UI thread (AGENTS.md). A change is debounced
  per document: 300 ms after the first change (`DiagnosticsSync.DEBOUNCE_MS`), through
  `UiExecutor.timerExec` (SWT's `Display.timerExec`). The model's change rate while typing was not
  measured (section 2); the equal-list check above makes the choice matter little.
- **The arguments of `SET_DIAGNOSTICS`** are flat strings (the buffer, then six per problem), since
  the session calls `nvim_exec_lua` with strings; no JSON, so a message with quotes or line breaks
  needs no escaping. `source = 'Eclipse'` is set on each diagnostic.
- **One namespace**, `neovim4eclipse`, with `vim.diagnostic.config({ virtual_text = false,
  virtual_lines = false, signs = false, underline = false, update_in_insert = false }, ns)`. Each
  update replaces the buffer's whole list (`vim.diagnostic.set`), so nothing has to be diffed.
- **Lifecycle**: one sync per document with a buffer, kept next to its `UnattachedDocumentSync` in
  `EditorAttachManager`, started on the document's first attach (after the controller's attach, so
  the first list is queued behind the buffer's first load) and closed with it. A document of an
  editor without an annotation model gets none.

### Turning it off

`vim.g.neovim4eclipse_problems = 0` (or `false`) in `init.lua`. It costs some work per annotation
change, and a user with their own diagnostics in `init.lua` (nvim-lint) may not want `]d` to stop
on both. It is an `init.lua` variable rather than a check box on the preference page: it is set
once and forgotten, belongs with the user's other Neovim settings (and their dotfiles), and matches
`vim.g.neovim4eclipse_folds`.

`LuaScripts.PROBLEMS` reads it once at start, after `init.lua`; the session keeps the answer
(`DiagnosticsPort.problemsEnabled()`), and when off `EditorAttachManager` starts no
`DiagnosticsSync`, so nothing is read or sent. A change needs a Neovim restart (the price of
reading it once).

Off, `]d` in a buffer without diagnostics would print Neovim's "No more valid diagnostics to move
to", the same as a file without problems. So `PROBLEMS` wraps Neovim's *default* `]d [d ]D [D`
(recognized by Neovim's description of each, and a function from Neovim's runtime: source
`vim/_core/defaults`, or `?` on Windows, whose Neovim build has no debug information for its
runtime) to say `]d: Eclipse's problems are not sent to Neovim (vim.g.neovim4eclipse_problems = 0)`
in that case, and to call Neovim's own mapping in every other case. The wrapper adds words only:
the jump, count, jump list, `on_jump` and the user's own diagnostics stay Neovim's (turning the keys
off instead would break diagnostics of the user's own). A key mapped or unmapped in `init.lua` is
not wrapped.

### Tests

`diagnostics_spec.lua` (Neovim's side: the list, `]d` with a count, `Ctrl-O`, the echo, a user's
own `on_jump`, nothing drawn; the opt-out: the variable, the message, a count and a user's
diagnostics through the wrapper, a user's own `]d`), `DiagnosticTest`, `DiagnosticPositionsTest`
(multi-byte text before the problem, a problem at end of line, a range over two lines, a
zero-length range), `DiagnosticsSyncTest` (when lists are sent), `NeovimProblemsIntegrationTest`
(the variable read through the session at start), and the UI test `DiagnosticsEndToEndTest` (a
problem marker made with `IResource.createMarker(IMarker.PROBLEM)` on a plain text file, `]d` to it
with its message in the message line, the marker deleted).

## 4. Keys, and the message after a jump

The plug-in maps no key for diagnostics. Neovim's default `]d` / `[d` / `]D` / `[D` mappings are
used as they are, so a user's own mapping in `init.lua` wins, as with every other key, and `]d`
stops on every namespace's diagnostics (the plug-in's, nvim-lint's), as in plain Neovim.

No task keys either: Neovim 0.12 maps `]t` / `[t` to `:tnext` / `:tprevious` (tags), and taking
them over would be a workaround of the kind the project avoids. Tasks are walked with `:Etasks` and
Neovim's `]q` / `[q`.

The message after a jump is Neovim's `on_jump` hook, with no wrapper around `]d` and no
`CursorMoved` check. `LuaScripts.SETUP` runs after the user's `init.lua`; if neither `on_jump` nor
the older `jump.float` is set by then, it sets an `on_jump` that echoes the first line of the
message. A user's own `on_jump` wins (for example `vim.diagnostic.open_float`, which shows nothing
here, see section 1). The hook is global (section 2), so it echoes the message of any namespace's
diagnostic, the plug-in's or nvim-lint's; staying silent for the others would look like a bug.

```lua
local jump = vim.diagnostic.config().jump or {}
if jump.on_jump == nil then
  vim.diagnostic.config({ jump = { on_jump = function(diagnostic)
    -- Called with vim.schedule after the cursor moved; nil when there was nothing to jump to.
    if diagnostic then
      vim.api.nvim_echo({ { (diagnostic.message:gsub('\n.*', '')) } }, false, {})
    end
  end } })
end
```

The echo reaches the message line only because Eclipse receives messages as `ext_messages` events:
`nvim_echo` does not set `v:statusmsg`, and `on_jump` runs scheduled, after any read of it.

Rejected: opening Eclipse's hover or Quick Fix popup on each jump. Those popups take the keyboard
focus, so the next `]d` would go to the popup, and a count or a macro would open one per step.
Instead `K` after `]d` shows the hover, and `gra` opens Quick Fix.

## 5. Quickfix

A quickfix entry in a file without an editor is reached by following Neovim's jump: Neovim runs
`:cnext` itself (typed, mapped or in a macro), reads the file through the plug-in's project-file
hook (`PROJECT_FILES`), and Eclipse opens its editor with the caret where Neovim's cursor went
(docs/design/BUFFER_NUMBERS.md, 2.3). This works for any list (`:vimgrep` too), and also makes `gf`
and `:b name` work for closed files. A jump into a file that already has an editor moves Neovim's
window to that editor ("window travels", docs/design/WINDOWS.md, E2). What this part adds is the
lists themselves and the window commands.

Rejected: `:cn`, `:cp`, `:cc`, `:cfir`, `:cla` as host commands (`HostExCommand`) that read the
list (`getqflist({ idx = 0, items = 1 })`), set the index and open the entry's file in Eclipse.
Neovim 0.12's `]q` / `[q` / `]Q` / `[Q` / `]<C-Q>` / `[<C-Q>` mappings run `:cnext` and friends
through `nvim_cmd` from Lua, which host commands never see; covering them would mean overriding
Neovim's mappings too, and none of it would be recorded in macros.

### 5.1 Experiments (Neovim 0.12.6, Linux)

Run headless, with the plug-in's `PROJECT_FILES` and `SET_PROJECT_FOLDERS` loaded through the Lua
harness (`tests/unit/src/test/lua/harness.lua`), on a project folder of generated files. The checks
that matter are in `quickfix_spec.lua`.

- **Q-E1, cost of a big list.** `setqflist()` with entries given by `filename` makes one buffer per
  new file (unlisted, not loaded), and fires `BufNew` once per file, nothing else (no `BufAdd`, no
  read, no `FileType`). Times: 300 entries in 100 files 3.5 ms; 3,000 in 1,000 files 31 ms; 9,000
  in 3,000 files 319 ms, of which about 170 ms is the `PROJECT_FILES` hook (`fs_realpath` per new
  buffer; 149 ms without it). Updating the same list again (action `'u'`, the buffers exist) 1.3 /
  13 / 147 ms. So a workspace with thousands of problems costs a fraction of a second per update,
  inside Neovim (not on Eclipse's UI thread); the usual few hundred cost milliseconds. Every new
  buffer gets the read hook (`file_of` gives its file), so a jump to it is read by the plug-in, not
  Neovim.
- **Q-E2, keeping the current entry.** Action `'u'` (Neovim only; *tries to preserve the current
  selection*) keeps the entry the user is on by its content: with `a b c` and `b` current, the same
  list keeps 2; `x a b c` gives 3 (still `b`); `b` moved to another line or column, or its text
  changed, keeps it; `b` removed falls back to an entry near it. Action `'r'` resets to entry 1.
  The window's cursor and buffer do not move. Neovim's own `vim.diagnostic.setqflist()` updates its
  list the same way (`'u'` by `id`, found by title anywhere in the stack, then `:chistory` to make
  it current), which is the precedent the plug-in follows (5.2).
- **Q-E3, round trips.** `context` (any Lua value, here `{ neovim4eclipse = 'problems', severity =
  'warning' }`) and each entry's `user_data` come back from `getqflist()` unchanged. `module`
  replaces the file name in `:clist` (`1 app/src/F1.java:2 error: Type mismatch`), so the list can
  show workspace paths while jumps use the full file name.
- **Q-E4, `:copen` and `:cwindow`.** Both fire `WinNew`, `WinEnter`, `FileType qf` and
  `BufWinEnter` in the new window, whose `win_gettype()` is `quickfix` (`loclist` for `:lopen`);
  `:cwindow` on a list without valid entries opens nothing and fires nothing. The `WinNew` safety
  net of `LuaScripts.WINDOWS` already sees and closes these windows, so it is the one place to
  route them to Eclipse (5.4), for `:copen` typed, mapped, from `<Cmd>`, a plug-in or
  `vim.diagnostic.setqflist({ open = true })` alike.
- **Q-E5, a jump into a closed file.** `:cc 1` to a project file without a buffer: the plug-in's
  hook reads it (no `filetype`), `eclipse_buf_enter` carries its file, `Ctrl-O` comes back. An entry
  with `col = 0` puts the cursor on the line's first non-blank (`{4, 4}` for a line indented by
  four), as Vim does; `col = 9` on byte 9. `lnum = 0` leaves the cursor where it was in that file.
  Neovim's `]q` is `:cnext`.
- **Q-E6, buffers that go away.** Unloading an entry's buffer keeps the entry; wiping it
  (`:bwipeout`, which here closes the editor first) leaves `bufnr = 0`, and `:cc` to it fails with
  `E92`. The next update of the list (5.3) gives the entry a buffer again, since entries are made
  by file name.
- **Q-E7, edits.** In a loaded buffer an entry's line moves with the text (two lines inserted
  above: line 5 becomes 7), like the diagnostics; Neovim keeps marks for them.
- **Q-E8, no event for the stack.** `:colder`, `:cnewer`, `:chistory` and `setqflist()` fire no
  `QuickFixCmdPre` / `QuickFixCmdPost`. So Neovim cannot tell Eclipse which list is current;
  Eclipse asks (`getqflist({ context = 1 })`) before it updates one (5.3).
- **Eclipse's side** (a temporary UI test, Linux): `IWorkspaceRoot.findMarkers` for 9,000 problems
  in 3,000 files takes 0.8 ms (Eclipse keeps markers in memory, in the resource tree), the whole
  read of the list 18 ms (10 ms the second time).

### 5.2 What the user gets

| Command | Does |
|---|---|
| `:Eproblems` | Fills the quickfix list titled `Eclipse problems` with every error, warning and info in the workspace, then jumps to the first, as `:make` does. |
| `:Eproblems warning`, `:Eproblems error` | Only problems at least that severe, as `vim.diagnostic`'s `severity = { min = ... }`: `warning` gives errors and warnings, `error` errors. `info` is the same as none. Any other word is refused with Vim's `E475`; the three are completed. The title carries the argument: `Eclipse problems (warning)`. |
| `:Eproblems!` | Fills the list without jumping (Vim's `:make!`); the message line says how many: `Eclipse problems: 12 (3 errors, 9 warnings)`. |
| `:Etasks`, `:Etasks!` | The same for task markers (`TODO`, `FIXME`) whose tag is in `vim.g.neovim4eclipse_task_tags`: a comma-separated string in `init.lua`, `'JM6,FIXME'`, or `'*'` for every task. Read each time `:Etasks` runs, so it can be changed with `:let` without a restart. Not set: the message line says how to set it, and no list is made. Title: `Eclipse tasks (JM6)`. |
| `]q` `[q` `:cnext` `:cc 3` ... | Neovim's own, unchanged (section 1). |
| `:copen`, `:cwindow` | The Problems view for `Eclipse problems`, the Tasks view for `Eclipse tasks`; for any other list (`:vimgrep`, `vim.diagnostic.setqflist()`), its `:clist` in the output popup. `:lopen`, `:lwindow`: the location list's `:llist`. |

User commands must start with a capital; the `E` prefix groups the plug-in's.

**Task tags** are typed by the user rather than taken from *Java › Compiler › Task Tags*, so that
the plug-in does not depend on Java tooling and works for any builder that makes task markers (CDT
too). A tag matches as a whole word at the start of the marker's text, case kept (Eclipse starts a
task marker's message with its tag: `JM6 check the offsets`): `JM6 check` matches `JM6`, `JM6x` and
`xJM6` do not. Choosing tags lets a user list a rare tag of their own and keep other people's
TODOs out of the list. The tags are an `init.lua` variable, not a preference page field, like the
plug-in's other Neovim settings. Only builders make task markers: a `JM6` comment in a plain `.txt`
file creates none.

**Kept current.** While an `Eclipse problems` or `Eclipse tasks` list is the **current** list, it
is kept up to date: a build adds or removes problems, an editor opens or closes, JDT finds a
problem while typing; the entry the user is on stays the current one (Q-E2). A list the user made
(`:vimgrep`) is never touched. Running `:Eproblems` or `:Etasks` again updates the list of its kind
already in the stack, if any, and makes it current, as `vim.diagnostic.setqflist()` does (Q-E2),
rather than push a copy, so the stack (ten lists) does not fill with copies; `:colder` back to it
later gets it updated again from then on. Both jump to the first entry; `!` does not (Vim's `:make`
and `:make!`).

Example: errors in `A.java` (line 40, open) and `B.java` (line 7, closed). `:Eproblems` goes to
`A.java` line 40 at the error's column, and shows `(1 of 2): Type mismatch: ...`. `]q` opens
`B.java`'s editor on line 7, at its first non-blank; a moment later the caret moves to the
problem's column (5.3, *Columns*). Fixing the error in `A.java` and saving removes it from the
list; the user stays on `B.java`'s entry, now `(1 of 1)`.

### 5.3 Design

```
Neovim                                        Eclipse
:Eproblems [sev] / :Etasks ──eclipse_quickfix──►  QuickfixLists (editor/): a Job, one run at a time
  (QUICKFIX: reads task tags)                      │ markers: findMarkers(PROBLEM | TASK), off the UI thread
                                                   │ open documents: annotations, on the UI thread
                                                   ▼
                                        QuickfixEntries (util/, pure): filter, tags, sort, dedupe, flat args
                                                   │ session.setQuickfixAsync(...)
                                                   ▼
QUICKFIX.set(): find our list (context), 'u' or new, :chistory, :cfirst, the column fix-up
```

**Where the entries come from.**

- *A file with an open editor* (attached or not, a buffer or not): the problems of its annotation
  model, read as `DiagnosticsSync` reads them (`DiagnosticsSync.read` / `lines` are static, one
  shared reader, used whether problems are sent as diagnostics or not), so the list holds what the
  editor underlines, JDT's unsaved problems included, with exact line and column. Tasks: the task
  markers' positions in that model (`AbstractMarkerAnnotationModel.getMarkerPosition`), which
  follow unsaved edits. An editor whose model holds no markers (not an
  `AbstractMarkerAnnotationModel`) does not hide its file's markers: they are listed by line, as
  for a closed file. The entry names the document's buffer by number when it has one (no second
  buffer for another spelling of the path: a Windows junction, an 8.3 name).
- *A closed file*: its markers (`IWorkspaceRoot.findMarkers(IMarker.PROBLEM, true,
  DEPTH_INFINITE)`, subtypes included), line only (`LINE_NUMBER`, column 0: Neovim puts the cursor
  on the first non-blank, Q-E5). A marker with no line (some builders set only `CHAR_START`) gets
  line 0: the jump opens the file.
- *A marker on a folder or project* gets an entry without a file: its text in `:clist`, stepped
  over by `:cnext`. Two with the same text are kept apart (their workspace path is their place).
- *Columns.* A closed file's entries have the line only, since a column needs the file's text.
  Opening the file's editor counts as a change (below), and the update then gives that file's
  entries their exact columns from the editor's annotations. `QUICKFIX.set()` moves the cursor to
  the current entry's new column when, and only when, that entry had no column before, is in the
  current buffer, the cursor is still where the line-only jump put it (that line's first non-blank)
  and Neovim is in normal mode: a user who moved meanwhile is left alone. So the jump that opened
  the file ends on the column. Open documents always have exact columns. Rejected: line only
  everywhere (loses what Eclipse knows for open files); reading every closed file's text for its
  columns (costly in a workspace with many problems).
- *Severity* from the marker's `SEVERITY` or the annotation's type (`ProblemAnnotations`), as the
  entry's `type` (`E`, `W`, `I`); `:clist` shows `error`, `warning`, `info`. *Text*: the message's
  first line. *module*: the workspace path (`app/src/A.java`), for `:clist` (Q-E3).
- *Sort*: by workspace path, then line, then column, as the Problems view sorted by resource.
  *Dedupe*: a file seen through two projects (a nested project) appears once: open editors are
  matched by their place on disk, not their `IFile`, and a line-only entry gives way to the same
  problem at a column.

**Keeping the list current.** A change schedules the job (debounced 500 ms, a burst gives one run):
a `POST_CHANGE` with problem or task marker deltas (a build), a document of a workspace file
getting its buffer or losing it (`QuickfixLists.changed()`, called by `EditorAttachManager`), an
annotation change in an open document (JDT's reconcile: `QuickfixLists.watching()` is the
`DiagnosticsPort` given to each `DiagnosticsSync`, so a new list of an open document's problems
schedules an update too). The job first asks Neovim for the current list's `context` (one call,
Q-E8); only when it is the plug-in's does it read the markers and send the list again, with the
same severity or tags, by the list's `id` with action `'u'`. With another list current, a change
costs that one call; before the first command of a Neovim, it costs nothing. An update equal to the
list sent last is not sent (it would take about 150 ms on Neovim's input thread for 9,000 entries).
A command waiting for its run is not delayed by an update's 500 ms.

**Threads.** Markers are read in the job (marker reads take no workspace lock and are safe on any
thread); the open editors' models and documents on the UI thread (`asyncExec`, short: one pass per
open editor); the list goes to Neovim with an `*Async` call. Nothing blocks the UI thread on Neovim
(AGENTS.md). The job stays although `findMarkers` is fast (5.1): nothing is gained by doing it on
the UI thread.

**The pure part**, `util/QuickfixEntries`: the kinds, the severity words (`leastSeverity`,
`atLeast`), the task tags (`TaskTags`), the order and the dedupe (`sorted`), the current list as
Neovim answers it (`Current.fromLua`), the flat arguments (`toLuaArgs`, seven per entry), and the
retry rule (`onFailedUpdate`).

**The Lua side** (`LuaScripts.QUICKFIX`, module `neovim4eclipse.quickfix`, run at start after
`WINDOW_COMMANDS`): the two user commands (with `-bang`, `-nargs=?` and completion of the
severities), `set(kind, arg, mode, jump, entries...)` (reuse by `context`, `'u'`, `:chistory`,
`:cfirst` or the count, the column fix-up), `current()` (the current list's kind and argument, or
nothing), and `window(type)` for the safety net. `SET_QUICKFIX` and `QUICKFIX_CURRENT` call it.
Kinds are kept in `context.neovim4eclipse`, not matched by title, so a title of the user's never
counts as the plug-in's. A list of text-only entries does not jump (no `E42`) and shows its count.

**The session side**, `core/QuickfixPort` (`NeovimSession`): `setQuickfixHandler`,
`setQuickfixAsync`, `currentQuickfixAsync` (a future answered on the input thread), and the
notifications `eclipse_quickfix` and `eclipse_show_view`. Both calls are held while Neovim waits
for the rest of a command (`]` of `]d`, `r`, `q`), which answers no API call then, and sent after
the key that ends it (`NeovimSession.whenAnswering`); otherwise the question would hold the input
thread, and that key, for `Rpc.TIMEOUT_SEC` (seen on macOS). An update whose question is still held
when its wait runs out is tried again, not reported; one that Neovim got but did not answer within
`Rpc.TIMEOUT_SEC` is tried again up to three times in a row (`QuickfixEntries.onFailedUpdate`), then
reported; one asked while Neovim closes fails at once.

**The Eclipse side**, `editor/QuickfixLists`: the job (family `QuickfixLists.class`, so a test can
wait for it), the `POST_CHANGE` listener installed by the `Activator`, `changed()` and `watching()`.

### 5.4 `:copen`, `:cwindow`, `:lopen`

Neovim's own quickfix window cannot be shown: it is a split, and the plug-in refuses splits in an
editor's tabpage. Eclipse's views are the Eclipse-native equivalent. The `WinNew` safety net of
`LuaScripts.WINDOWS` closes a `quickfix` or `loclist` window and calls the quickfix module's
`window()`: for the plug-in's current list, `eclipse_show_view` with `problems` or `tasks`, and
Eclipse shows that view (`IPageLayout.ID_PROBLEM_VIEW`, `ID_TASK_LIST`); for any other list it
echoes `:clist` (or `:llist`), which arrives as multi-line output and opens the output popup.
Without the module loaded (a spec of the windows alone) the safety net shows its own message.

Rejected: host commands (`:copen` in `HostCommand.TABLE`). A mapping's `<Cmd>copen<CR>`, a
plug-in's `vim.cmd.copen()` and `vim.diagnostic.setqflist({ open = true })` never pass the command
line, while every one of them opens the window (Q-E4).

### 5.5 Not done

- Location lists of Eclipse's problems (`:Elproblems`): the diagnostics already give one file's
  problems to `]d`.
- Showing the list in Neovim's own quickfix window (5.4).
- Severity names other than Eclipse's three (`hint`).

### 5.6 Tests

- `QuickfixEntriesTest` (`tests/unit`): severity filter, task tag match, the sort, the dedupe, the
  flat arguments (a message holding quotes and a line break), the retry rule.
- `quickfix_spec.lua` (headless): the commands send what Eclipse needs (kind, severity, bang, tags;
  the hint without tags; a bad severity refused with `E475`); `set` makes a new list, reuses the
  plug-in's from the stack and makes it current, keeps the current entry (`'u'`), jumps to the
  first unless `!`, echoes the count; an update leaves a list of the user's alone; the column
  fix-up and when it does not happen; `:copen` on the plug-in's list sends `eclipse_show_view`, on
  another list echoes `:clist`; `:lopen` echoes `:llist`.
- `NotificationBridgeTest`: the two notifications. `NeovimQuickfixIntegrationTest`: the calls held
  while Neovim waits for the rest of a command.
- `QuickfixEndToEndTest` (UI, plain text files, markers made by the test, no Java tooling):
  `:Eproblems` lands on the column of the open file's error; `]q` opens a closed file's editor,
  which then moves to the column; a marker added to a closed file and to the open one joins the
  current list, a marker added while `:vimgrep`'s list is current does not, and after `:colder` the
  list follows again; `:Eproblems! warning` leaves out infos; `:copen` shows the Problems view;
  `:Etasks` with `vim.g.neovim4eclipse_task_tags = 'JM6'` lists only that task and goes to it;
  `anUnchangedListIsNotSentAgain`; `aNestedProjectsOpenFileIsListedOnce`. Checked to fail with the
  column fix-up taken out, and with the marker listener taken out.
- Not tested: an editor whose model holds no markers (no such editor in the platform to open), the
  command not waiting for an update's delay (a timing), and JDT's problems found while typing
  reaching the list (a `-PuiHeavyJava` test).

## 6. The `gr*` keys

Neovim's defaults for a language server (all present in 0.12.6) run their Eclipse counterparts as
IDE actions, like `gd` and `K`; `vim.g.neovim4eclipse_ide_keys = 0` turns them off with the others.

| Key | Neovim's meaning | Eclipse |
|---|---|---|
| `grn` | rename | Rename (JDT: `org.eclipse.jdt.ui.edit.text.java.rename.element`, Alt+Shift+R) |
| `gra` | code action | Quick Fix (Ctrl+1: the platform's `QUICK_ASSIST` id, which the Text and Generic Editors handle too; JDT's `...correction.assist.proposals`); in visual mode too, on the selection |
| `grr` | references | References in Workspace (Ctrl+Shift+G; the Generic Editor binds it to `org.eclipse.ui.genericeditor.findReferences`) |
| `gri` | implementation | Open Implementation (no key) |
| `grt` | type definition | JDT's "Open Declared Type", which is only a hyperlink (`JavaElementHyperlinkDeclaredTypeDetector`), not a command; `HyperlinkProbe.open` detects and opens it |
| `gO` | document symbols | Quick Outline (Ctrl+O, the plug-in's own) |
| `grx` | run code lens | none: stays Neovim's |

A key without an Eclipse command in the editor shows a message rather than running Neovim's own,
which needs a language server inside Neovim. `gd` instead falls back to Neovim's `gd`. Each key runs
whatever Eclipse command the editor's context binds (as `gd` runs F3's), so a plain text editor
without the command leaves it to that fallback. The platform's `org.eclipse.ui.edit.rename` (F2)
is handled nowhere in text editors, so it is not used.

**One table** drives `gd`, `K`, `gt` and the `gr*` keys, so the code does not repeat itself:
`core/IdeKeys` (pure, `IdeKeysTest`) holds `KEYS` (key, modes, action) and `COMMANDS` (label, the
ways to find the command, Neovim's fallback keys). `LuaScripts.IDE_KEYS` maps the keys from it.
`IdeKeys.pick` both chooses and runs, over a small port, `IdeKeys.Editor` (which command a key
runs, whether a command can run, run it, links), with `editor/IdeActions.EclipseEditor` as its thin
Eclipse side; so a command that turns out not to run when executed passes to the next way, and `gd`
still ends in Neovim's `gd`. `IdeKeys.dryRun` answers the tests' "what would it do". Whether a
command can run is asked of the editor's own Eclipse 4 context (`EHandlerService.canExecute`,
bundles `org.eclipse.e4.core.commands` and `org.eclipse.e4.core.contexts`), the lookup the run
itself uses; for a multi-page editor's text page the outer editor's context gives the same answer, so its test
guards the result, not the context.
A command id unknown to the running Eclipse (a JDT command without Java tooling) does not count as
run: `choose` checks `isDefined()` first, and refuses an action that is no command (`next_tab`). A
command that fails while running, and a link that fails to open, say so in the message line
(`grr: References failed: ...`).

**Tests**: `setup_spec.lua` (each key sends its action, `gra` in visual mode keeps the selection,
`grx` stays Neovim's, the opt-out); `IdeKeysTest` (with a fake editor: a command that passes the
check and then fails to run goes on to the next way, and `gd` to Neovim's `gd`, with no waiting;
action names read from `IdeAction`); `IdeKeysEndToEndTest` (the plain Text Editor's choices, the
message; a failing test command and a failing test link from `fragment.xml`; a multi-page editor's
text page finds its commands; `visualGraRunsQuickFixOnTheSelection`: Quick Fix starts with Neovim's
visual selection as the editor's, and the test closes only the shells it opened); `JavaEditorEndToEndTest` (heavyJava: Java's command for each
key, `gri` to the implementation, `grt` to `String`). Checked to fail with the key-binding and
hyperlink ways taken out.

Not checked: a Language Server editor (LSP4E is not in the test platform), CDT.
