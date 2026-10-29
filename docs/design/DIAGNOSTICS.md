# Eclipse problems as Vim diagnostics and a quickfix list

Status: **phase 1 built** (steps 1 to 3 of section 7) on `feature/diagnostics`, cut from `release/0.1.8` at `4708e98` (2026-10-27), moved onto `release/0.1.9`; see *As built* at the end. **Phase 2 (quickfix) built 2026-10-29** (its own *As built* at the end), detailed in section 5, on `feature/quickfix` from `release/0.1.10` at `2168255`; then step 4 (the `gr*` keys) on the same branch *(decided 2026-10-29: quickfix first)*. First planned against `release/0.1.3` at `64abd26`; **revised 2026-10-25** against `release/0.1.7` at `0e3c153` for the new minimum Neovim, **0.12.6** (`NeovimVersion.MINIMUM`), and for the window-per-editor plan (*One Neovim window per Eclipse editor*, `feature/neovim-windows`). The experiments in section 2 ran on Neovim 0.12.4 and were **repeated on 0.12.6** (the version CI tests) on 2026-10-25, on Linux. Decisions made with the maintainer are marked *(decided)*; the questions were settled on 2026-10-22 and revised on 2026-10-25.

## Summary

Eclipse knows where the errors and warnings are: the Java compiler, Checkstyle, a language server (LSP4E) and other builders report them, and the editor underlines them. Today none of that reaches Neovim, so the Vim way of moving between problems does nothing: `]d` finds no diagnostics, and the quickfix commands (`:cnext`) have no list. A user has to leave Vim's keys for Eclipse's *Next Annotation* (Ctrl+.) or the Problems view.

**Plan:** mirror the problems of every open document into Neovim with `vim.diagnostic.set()`, in one namespace of our own. Neovim's own `]d` / `[d` then work, with counts, in macros, and in the jumplist (Ctrl-O comes back); the plug-in adds no key of its own. Later, a quickfix list of the whole workspace's problems (and, on request, tasks), walked with Neovim's own `]q` / `[q` or `:cnext` / `:cprev` / `:cc N`; Neovim runs them itself, and the plug-in opens the file's editor in Eclipse when it is not open yet.

Example: a Java file with an unused import on line 3 and a type error on line 40. With the cursor on line 1, `]d` goes to line 3, `2]d` to line 40, `[d` back, Ctrl-O to where `]d` started. `:Eproblems` then `]q` in a workspace with errors in three files opens the next file's editor at its first error.

Why this one next (chosen 2026-10-21 over window splits and write-through-Neovim; confirmed 2026-10-25): jumping between compile errors is constant in Java work, the keys are standard Vim/Neovim, the data already exists in Eclipse, and the decisions fit in small pure classes, as the project prefers. It also fits the project's goal that Neovim does the work: with Neovim 0.12.6 as the minimum, Neovim's defaults (`]d`, `]q`, `on_jump`) do all the moving, and the plug-in only supplies the lists. Phase 1 is per buffer and does not touch windows, so it goes **before** the windows plan's phase 1; phase 2 (quickfix) comes **after** the windows plan, because it builds on its `followNeovimInto` *(decided 2026-10-25)*.

## 1. What a user gets

Phase 1, diagnostics in open documents:

- **`]d` / `[d`**: next / previous problem in the current file, with a count (`3]d`). These are Neovim's own default mappings (`vim.diagnostic.jump({ count = v:count1 })`); the plug-in maps nothing, so a user's own mapping in `init.lua` wins.
- **`]D` / `[D`**: last / first problem (Neovim defaults).
- **Tasks** (a `TODO`-style comment, for the task tags the user chose, Q2) are not diagnostics, so `]d` never stops on one. They are walked through the quickfix list instead: `:Etasks`, then Neovim's `]q` / `[q` (phase 2). No task keys of our own: Neovim 0.12 already maps `]t` / `[t` to `:tnext` / `:tprevious` (tags) *(revised 2026-10-25)*.
- **The message** of the problem the cursor lands on is shown in the message line, as Eclipse's status bar does after Ctrl+. (Q3). Example: `]d` to line 40 shows `Type mismatch: cannot convert from int to String`. This is Neovim's `on_jump` hook, set by the plug-in only when the user's `init.lua` set none (section 4).
- **`K` right after `]d`** opens Eclipse's hover for the problem, with its quick fixes in Java. This already works today (`K` is *Show Tooltip Description*); `]d` does not open it by itself (Q3).
- **`gra`** *(**decided 2026-10-25**, Q3)*: Eclipse's *Quick Fix* (Ctrl+1) at the cursor. The key is Neovim's default for "code action" with a language server, the nearest Vim-world meaning; it joins `gd` and `K` as an IDE action (`IdeAction`), with `vim.g.neovim4eclipse_ide_keys = 0` turning it off like the others. The other `gr*` keys come with it (section 7, step 4).
- Nothing new is drawn: Eclipse already underlines problems and shows them in the ruler, so Neovim's own display (signs, virtual text, underline) is turned off for our namespace. Neovim's windows are never shown anyway: one hidden window today, one hidden tabpage per editor under the windows plan.
- Works in any text editor whose document has problem annotations: Java, generic editor with LSP4E, XML, plain text with markers.

Phase 2, the quickfix list:

- **`]q` / `[q`, `]Q` / `[Q`, `]<C-Q>` / `[<C-Q>`** (Neovim's default mappings) and **`:cn[ext]`, `:cp[revious]`, `:cN[ext]`, `:cc N`, `:cfir[st]`, `:cla[st]`, `:cnf[ile]`**: walk Neovim's current quickfix list, whatever filled it: Eclipse's problems or tasks, `:vimgrep`, or the user's own `setqflist()`. Neovim runs every one of them itself, typed, mapped or in a macro; an entry in a file that has no editor yet opens its editor, the way `:e file` does (section 5, way B; built with docs/design/BUFFER_NUMBERS.md, 2.3).
- **`:cl[ist]`**: the list, in the existing output popup (it is ordinary command output).
- **`:copen` / `:cw[indow]`**: open Eclipse's Problems view, since Neovim's quickfix window cannot be shown (Q5): it is a split, and the windows plan refuses splits in an editor's tabpage. The Tasks view for `Eclipse tasks`, the output popup for other lists.
- **`:Eproblems` / `:Etasks`** fill the quickfix list with the workspace's problems or tasks, and keep it current while it is the current list (Q4).

Not planned: Neovim's diagnostic float (`<C-w>d`, a Neovim default, and `vim.diagnostic.open_float()`): a float opens in a Neovim window nobody sees, and under the windows plan it lands in the home window (that plan's experiment E1). `K` already shows Eclipse's hover, which includes the problem text, and the message line shows it after each jump.

## 2. Experiments

Done (`nvim --headless --clean`, a 50-line buffer with three diagnostics set by `vim.diagnostic.set`, keys sent with `nvim_feedkeys`): first on Neovim 0.12.4, then all of it **again on 0.12.6** on 2026-10-25, every check passing; the 0.12.6 run is the one the dated items describe. The script becomes the integration test of section 6.

- `]d` is mapped by default (to a Lua function), moves to the next diagnostic, and accepts a count (`2]d` skipped the first).
- `]d` adds to the jumplist: Ctrl-O went back to the starting position.
- `vim.diagnostic.setqflist({ open = false })` fills the quickfix list with title `"Diagnostics"`.
- *(2026-10-25)* `vim.diagnostic.jump` merges in the **global** `vim.diagnostic.config().jump` table (there is no per-namespace jump option), then calls `on_jump(diagnostic, bufnr)` with `vim.schedule`, after the cursor has moved: a synchronous check right after the jump still sees `nil`. `jump({ namespace = ns })` limits a jump to one namespace.
- *(2026-10-25)* Neovim 0.12's defaults that matter here: `]d` `[d` `]D` `[D` `<C-W>d` (diagnostics); `]q` `[q` `]Q` `[Q` `]<C-Q>` `[<C-Q>` (quickfix: `:cnext` and friends, run with `nvim_cmd` from a Lua mapping, so the plug-in's host commands never see them); `]t` `[t` `]T` `[T` (tags: `:tnext` and friends); `gra` `gri` `grn` `grr` `grt` `grx` (language server).
- *(0.12.6)* `]d`, `2]d`, `[d`, `[D`, `]D` land on the expected lines; Ctrl-O after `]d` returns to the start. `vim.diagnostic.config({ jump = … }, ns)` (per namespace) is ignored by `]d`; only the global one counts. With nothing left to jump to (`wrap = false` past the last problem) `on_jump` is **not** called, and Neovim itself prints `No more valid diagnostics to move to`, which reaches the message line like any message.
- *(0.12.6)* The note's `SETUP` snippet (section 4): it sets `on_jump` when none is set, leaves a user's own alone, and the echo shows only the first line of a two-line message.
- *(0.12.6)* **Macros** (`qqOnew<Esc>]dq`): a line inserted during the macro moves the diagnostics with the text, and `]d` lands on the shifted line (4, not 3). Neovim tracks diagnostic positions with extmarks, so a macro needs no update from the plug-in. Note: `vim.diagnostic.get()` still reports the position as set (`lnum` unchanged) until the next `vim.diagnostic.set`; the plug-in never reads positions back, so this does not matter here.

Still to do, before phase 1's first commit:

1. ~~**Neovim 0.12.6**: repeat the checks above on it.~~ **Done 2026-10-25**, all pass (items marked 0.12.6 above). Nothing older is supported any more, so the 0.9 questions of the first draft (a `goto_next` count loop, `m'` by hand) are gone.
2. **Which Eclipse model to read.** The editor's annotation model (`IDocumentProvider.getAnnotationModel(input)`) holds both kinds of problems: markers saved by a build (`MarkerAnnotation`) and JDT's *temporary* problems found while typing, before any save (`ProblemAnnotation`, not markers at all). Check that both appear, and how to tell problems from other annotations (search results, occurrences, bookmarks, spelling): probably `IAnnotationAccessExtension.isSubtype(type, "org.eclipse.ui.workbench.texteditor.error")`, also `warning` and `info`.
3. **How often the model changes** while typing in a Java editor (reconcile after ~500 ms), to choose the debounce (section 3).
4. **Quick Fix command.** As `gd` runs whatever command F3 is bound to in the editor's context (`IdeActions`), `gra` should run whatever Ctrl+1 is bound to: in the Java editor JDT's *Quick Fix*, in a plain text editor probably the platform's quick-assist command, if any. Check both, and what happens where nothing is bound.
5. ~~**Macro playback**~~ **Done 2026-10-25** in Neovim alone: diagnostics move with edits made during a macro (extmarks), and `]d` inside the macro finds the shifted line (items above). Still to check in Eclipse: macro playback applies the edit to Eclipse once at the end (README, *Macros*); the plug-in's next `vim.diagnostic.set` after Eclipse reconciles must then agree with what Neovim already shifted.

## 3. Design, phase 1

```
Eclipse                                             Neovim
annotation model ──(listener, debounced)──►  DiagnosticsBridge (editor/)
  of each open document                        │  reads problem annotations on the UI thread
                                               ▼
                                     Diagnostics (util/, pure)
                                       offset → line + UTF-8 byte column, severity, sort, dedupe
                                               │
                                               ▼  session.setDiagnosticsAsync(bufnr, list)
                                     LuaScripts.SET_DIAGNOSTICS: vim.diagnostic.set(ns, bufnr, items)
```

- **Source** *(recommended)*: the annotation model of each open document, for the reason in experiment 2: it has the problems the user sees, including unsaved ones. Markers of closed files are phase 2's business.
- **Every open document, not only the attached one.** Each open document already has its own Neovim buffer, so `]d` works right after switching editors, and a later quickfix list can use the buffers. Diagnostics belong to the buffer, not the window, so the windows plan changes nothing here: two editors on one file share one buffer (that plan's E3) and so share its diagnostics, and a travelling window (its E2) finds them on whichever buffer it shows.
- **Conversion** in a pure `util/` class (unit-testable, no `org.eclipse.*`): from document offset and length, plus the line's text, to Neovim's `lnum` / `col` / `end_lnum` / `end_col` (0-based lines, **UTF-8 byte** columns: `Utf8Columns`). Annotation positions are document offsets, so folding does not matter here (`WidgetOffsets` is only for `StyledText`).
- **Severity**: Eclipse error / warning / info → `vim.diagnostic.severity.ERROR` / `WARN` / `INFO`. Task markers are not problems (Eclipse lists them in the Tasks view), so they are never sent as diagnostics; `:Etasks` puts them in the quickfix list (phase 2, Q2).
- **Which tasks** (phase 2, for `:Etasks`): the preference *Task tags to include*, a comma-separated list, empty by default (`:Etasks` then fills an empty list). `JM6, FIXME` takes tasks whose message starts with one of those tags (Eclipse starts a task marker's message with its tag: `JM6 check the offsets`); `*` takes every task. The tags are typed by the user rather than picked from *Java › Compiler › Task Tags*, so that the plug-in does not depend on Java tooling and works for any builder that makes task markers (CDT too). Only builders make task markers: a `JM6` comment in a plain `.txt` file creates none (to check for the generic editor).
- **Threads**: annotations are read on the UI thread (cheap: one pass over the model), the list is sent with an `*Async` call, never blocking RPC on the UI thread (AGENTS.md). A change is debounced per document (~300 ms, experiment 3) so typing does not send a list per key.
- **One namespace**, `neovim4eclipse`, with `vim.diagnostic.config({ virtual_text = false, signs = false, underline = false, update_in_insert = false }, ns)`. Each update replaces the buffer's whole list (`vim.diagnostic.set`), so nothing has to be diffed.
- **Lifecycle**: start listening when a document's buffer is created, stop when it is discarded (the same place `EditorAttachManager` creates and deletes buffers; under the windows plan buffers are still per document, so this stays). A user who does not want it: a preference *Send Eclipse problems to Neovim* (on by default; Q1, decided; built as `vim.g.neovim4eclipse_problems = 0` instead, see "As built").

## 4. Keys

The plug-in maps no key for diagnostics. Neovim's default `]d` / `[d` / `]D` / `[D` mappings are used as they are, so a user's own mapping in `init.lua` wins, as with every other key, and `]d` stops on every namespace's diagnostics (ours, nvim-lint's), as in plain Neovim.

No task keys either: Neovim 0.12 maps `]t` / `[t` to `:tnext` / `:tprevious` (tags), and taking them over would be a workaround of the kind the project avoids. Tasks are walked with `:Etasks` and Neovim's `]q` / `[q` (phase 2, Q2).

The message after a jump (Q3, decided 2026-10-25): Neovim's `on_jump` hook, with no wrapper around `]d` and no `CursorMoved` check. `LuaScripts.SETUP` runs after the user's `init.lua`; if no `on_jump` is set by then, it sets one that echoes the first line of the message. A user's own `on_jump` wins (for example `vim.diagnostic.open_float`, which shows nothing here, see section 1). The hook is global (experiment, section 2), so it echoes the message of any namespace's diagnostic, ours or nvim-lint's; staying silent for the others would look like a bug.

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

## 5. Phase 2: quickfix (detailed 2026-10-29)

Planned on `feature/quickfix`, cut from `release/0.1.10` at `2168255`. Way B (follow Neovim's jump into a file with no editor) is built already, as step 3 of docs/design/BUFFER_NUMBERS.md: a quickfix entry in a closed project file is read by `PROJECT_FILES`, and Eclipse opens its editor with the caret where Neovim's cursor went. What phase 2 adds is the lists themselves and the window commands.

### 5.0 Before phase 2 (the outline, 2026-10-25)

Phase 2 starts **after the windows plan is built** (Q7). With that plan, a jump into a file that already has an editor works: `followNeovimInto` moves Neovim's window to that editor ("window travels", its E2). What is left is a file with no editor: Neovim `:edit`s it from disk and the plug-in undoes the jump (README, *Known limitations* #1). Two ways to fix that for quickfix:

- **A. Host commands** *(re**jected 2026-10-25**)*: `:cn`, `:cp`, `:cc`, `:cfir`, `:cla` as host commands (`HostExCommand`) that read the list (`getqflist({ idx = 0, items = 1 })`), set the index and open the entry's file in Eclipse. Rejected because Neovim 0.12's `]q` / `[q` / `]Q` / `[Q` / `]<C-Q>` / `[<C-Q>` mappings run `:cnext` and friends through `nvim_cmd` from Lua, which host commands never see; covering them would mean overriding Neovim's mappings too, and none of it would be recorded in macros.
- **B. Follow Neovim's jump** *(decided 2026-10-25; to be built ahead of phase 2, as step 3 of
  docs/design/BUFFER_NUMBERS.md, decided 2026-10-29)*: Neovim runs `:cnext` itself, typed, mapped or in a macro. When `BufEnter` reaches a buffer for a workspace file that has no editor, `followNeovimInto` opens that file's editor (as `:e file` does) and gives it the window, instead of undoing the jump. Works for any list (`:vimgrep` too), and would also make `gf` and `:b name` work for closed files. The risk: Neovim loaded the file from disk, while the plug-in fills each document's buffer from Eclipse's text, so the two loads must not fight (cursor, undo history, `BufRead` autocommands, filetype). That is phase 2's first experiment.

Filling the list (Q4, decided: `:Eproblems` / `:Etasks`, kept current while current): from `IWorkspaceRoot.findMarkers(IMarker.PROBLEM, true, DEPTH_INFINITE)` (every problem, open or not; open documents take positions from their annotations instead, which are fresher), sorted like the Problems view: by file, then line. Never replace a list the user made: only a list titled `Eclipse problems` is updated when markers change.

### 5.1 Experiments (2026-10-29, Neovim 0.12.6, Linux)

Run headless, with the plug-in's `PROJECT_FILES` and `SET_PROJECT_FOLDERS` loaded through the Lua harness (`tests/unit/src/test/lua/harness.lua`), on a project folder of generated files. The checks that matter become `quickfix_spec.lua`.

- **Q-E1, cost of a big list.** `setqflist()` with entries given by `filename` makes one buffer per new file (unlisted, not loaded), and fires `BufNew` once per file, nothing else (no `BufAdd`, no read, no `FileType`). Times: 300 entries in 100 files 3.5 ms; 3,000 in 1,000 files 31 ms; 9,000 in 3,000 files 319 ms, of which about 170 ms is the `PROJECT_FILES` hook (`fs_realpath` per new buffer; 149 ms without it). Updating the same list again (action `'u'`, the buffers exist) 1.3 / 13 / 147 ms. So a workspace with thousands of problems costs a fraction of a second per update, inside Neovim (not on Eclipse's UI thread); the usual few hundred cost milliseconds. Every new buffer gets the read hook (`file_of` gives its file), so a jump to it is read by the plug-in, not Neovim.
- **Q-E2, keeping the current entry.** Action `'u'` (Neovim only; *tries to preserve the current selection*) keeps the entry the user is on by its content: with `a b c` and `b` current, the same list keeps 2; `x a b c` gives 3 (still `b`); `b` moved to another line or column, or its text changed, keeps it; `b` removed falls back to an entry near it. Action `'r'` resets to entry 1. The window's cursor and buffer do not move. Neovim's own `vim.diagnostic.setqflist()` updates its list the same way (`'u'` by `id`, found by title anywhere in the stack, then `:chistory` to make it current), which is the precedent followed here (Q10).
- **Q-E3, round trips.** `context` (any Lua value, here `{ neovim4eclipse = 'problems', severity = 'warning' }`) and each entry's `user_data` come back from `getqflist()` unchanged. `module` replaces the file name in `:clist` (`1 app/src/F1.java:2 error: Type mismatch`), so the list can show workspace paths while jumps use the full file name.
- **Q-E4, `:copen` and `:cwindow`.** Both fire `WinNew`, `WinEnter`, `FileType qf` and `BufWinEnter` in the new window, whose `win_gettype()` is `quickfix` (`loclist` for `:lopen`); `:cwindow` on a list without valid entries opens nothing and fires nothing. The `WinNew` safety net of `LuaScripts.WINDOWS` already sees and closes these windows, so it is the one place to route them to Eclipse (5.4), for `:copen` typed, mapped, from `<Cmd>`, a plug-in or `vim.diagnostic.setqflist({ open = true })` alike.
- **Q-E5, a jump into a closed file.** `:cc 1` to a project file without a buffer: the plug-in's hook reads it (no `filetype`), `eclipse_buf_enter` carries its file, Ctrl-O comes back. An entry with `col = 0` puts the cursor on the line's first non-blank (`{4, 4}` for a line indented by four), as Vim does; `col = 9` on byte 9. `lnum = 0` leaves the cursor where it was in that file. Neovim's `]q` is `:cnext`.
- **Q-E6, buffers that go away.** Unloading an entry's buffer keeps the entry; wiping it (`:bwipeout`, which here closes the editor first) leaves `bufnr = 0`, and `:cc` to it fails with `E92`. The next update of the list (5.3) gives the entry a buffer again, since entries are made by file name.
- **Q-E7, edits.** In a loaded buffer an entry's line moves with the text (two lines inserted above: line 5 becomes 7), like the diagnostics of phase 1; Neovim keeps marks for them.
- **Q-E8, no event for the stack.** `:colder`, `:cnewer`, `:chistory` and `setqflist()` fire no `QuickFixCmdPre` / `QuickFixCmdPost`. So Neovim cannot tell Eclipse which list is current; Eclipse asks (`getqflist({ context = 1 })`) before it updates one (5.3).

To measure in the UI test of step 5.6, not headless: the cost of `IWorkspaceRoot.findMarkers(PROBLEM, true, DEPTH_INFINITE)` and of reading each marker's attributes in a workspace with thousands of markers. Eclipse keeps markers in memory (in the resource tree), so it should be milliseconds; if not, the reading moves off the UI thread, where it already is (5.3).

### 5.2 What the user gets

| Command | Does |
|---|---|
| `:Eproblems` | Fills the quickfix list titled `Eclipse problems` with every error, warning and info in the workspace, then jumps to the first, as `:make` does. |
| `:Eproblems warning`, `:Eproblems error` | Only problems at least that severe: `warning` gives errors and warnings, `error` errors (Q9). `info` is the same as none. |
| `:Eproblems!` | Fills the list without jumping (Vim's `:make!`); the message line says how many: `Eclipse problems: 12 (3 errors, 9 warnings)`. |
| `:Etasks`, `:Etasks!` | The same for task markers (`TODO`, `FIXME`) whose tag is in `vim.g.neovim4eclipse_task_tags` (Q2): a comma-separated string in `init.lua`, `'JM6,FIXME'`, or `'*'` for every task. Read each time `:Etasks` runs, so it can be changed with `:let` without a restart. Not set: the message line says how to set it, and no list is made. |
| `]q` `[q` `:cnext` `:cc 3` ... | Neovim's own, unchanged (section 1). |
| `:copen`, `:cwindow` | The Problems view for `Eclipse problems`, the Tasks view for `Eclipse tasks`; for any other list (`:vimgrep`, `vim.diagnostic.setqflist()`), its `:clist` in the output popup. `:lopen`, `:lwindow`: the location list's `:llist`. |

While an `Eclipse problems` or `Eclipse tasks` list is the **current** list, it is kept up to date (Q4): a build adds or removes problems, an editor opens or closes, JDT finds a problem while typing; the entry the user is on stays the current one (Q-E2). Running `:Eproblems` again uses the list already in the stack, if any, and makes it current (Q10), so the stack (ten lists) does not fill with copies; `:colder` back to it later gets it updated again from then on.

Example: errors in `A.java` (line 40, open) and `B.java` (line 7, closed). `:Eproblems` goes to `A.java` line 40 at the error's column, and shows `(1 of 2): Type mismatch: ...`. `]q` opens `B.java`'s editor on line 7, at its first non-blank; a moment later the caret moves to the problem's column (5.3, *Columns*). Fixing the error in `A.java` and saving removes it from the list; the user stays on `B.java`'s entry, now `(1 of 1)`.

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

- *A file with an open editor* (attached or not, a buffer or not): the problems of its annotation model, read as `DiagnosticsSync` reads them (one shared reader), so the list holds what the editor underlines, JDT's unsaved problems included, with exact line and column. Tasks: the task markers' positions in that model (`AbstractMarkerAnnotationModel.getMarkerPosition`), which follow unsaved edits. The entry names the document's buffer by number when it has one (no second buffer for another spelling of the path: a Windows junction, an 8.3 name).
- *A closed file*: its markers (`IWorkspaceRoot.findMarkers`, subtypes included), line only (`LINE_NUMBER`, column 0: Neovim puts the cursor on the first non-blank, Q-E5). A column would need the file's text, read for every closed file with a problem (decided 2026-10-29, Q11, option c). A marker with no line (some builders set only `CHAR_START`) gets line 0: the jump opens the file.
- *Columns once the editor opens* (Q11): opening the file's editor counts as a change (below), and the update then gives that file's entries their columns. `QUICKFIX.set()` moves the cursor to the current entry's new column when, and only when, that entry had no column before, is in the current buffer, and the cursor is still where the line-only jump put it (that line's first non-blank): a user who moved meanwhile is left alone.
- *Severity* from the marker's `SEVERITY` or the annotation's type (phase 1's `ProblemAnnotations`), as the entry's `type` (`E`, `W`, `I`); `:clist` shows `error`, `warning`, `info`. *Text* the message's first line. *module*: the workspace path (`app/src/A.java`), for `:clist`. *Sort*: by workspace path, then line, then column, as the Problems view sorted by resource. *Dedupe*: a file seen through two projects (a nested project) appears once, by its location, line and message.

**Keeping the list current.** A change schedules the job (debounced 500 ms, a burst gives one run): a `POST_CHANGE` with problem or task marker deltas (a build), a document of a workspace file attached for the first time or closed, an annotation change in an open document (JDT's reconcile; through `DiagnosticsSync`'s listener when phase 1 is on, else a listener of the same kind). The job first asks Neovim for the current list's `context` (one call, Q-E8); only when it is ours does it read the markers and send the list again, with the same severity or tags, by the list's `id` with action `'u'`. With another list current, a change costs that one call.

**Threads.** Markers are read in the job (marker reads take no workspace lock and are safe on any thread); the open editors' models and documents on the UI thread (`asyncExec`, short: one pass per open editor), as phase 1 reads them; the list goes to Neovim with an `*Async` call. Nothing blocks the UI thread on Neovim (AGENTS.md).

**The Lua side** (`LuaScripts.QUICKFIX`, module `neovim4eclipse.quickfix`): the two user commands (with `-bang`, `-nargs=?` and completion of the severities), `set(...)` (flat string arguments, as `SET_DIAGNOSTICS`: eight per entry), `current()` (the current list's kind and argument, or nothing), and `window(type)` for the safety net. Kinds are kept in `context.neovim4eclipse`, not matched by title, so a title of the user's never counts as ours.

### 5.4 `:copen`, `:cwindow`, `:lopen`

The `WinNew` safety net of `LuaScripts.WINDOWS` closes a `quickfix` or `loclist` window as before, and instead of the message it now calls the quickfix module: for our current list, `eclipse_show_view` with `problems` or `tasks`, and Eclipse shows that view (`IPageLayout.ID_PROBLEM_VIEW`, `ID_TASK_LIST`); for any other list it echoes `:clist` (or `:llist`), which arrives as multi-line output and opens the output popup. Host commands (`:copen` in `HostCommand.TABLE`) were considered and not taken: a mapping's `<Cmd>copen<CR>`, a plug-in's `vim.cmd.copen()` and `vim.diagnostic.setqflist({ open = true })` never pass the command line, while every one of them opens the window (Q-E4).

### 5.5 Not planned

- Location lists of Eclipse's problems (`:Elproblems`): phase 1's diagnostics already give one file's problems to `]d`.
- Showing the list in Neovim's own quickfix window (Q5).
- Severity names other than Eclipse's three (`hint`).

### 5.6 Tests

- `tests/unit`: `QuickfixEntries` (severity filter, task tag match: `JM6 check` matches `JM6`, `JM6x` and `xJM6` do not, `*`; the sort; the dedupe; the flat arguments, with a message holding quotes and a line break).
- `quickfix_spec.lua` (headless): the commands send what Eclipse needs (kind, severity, bang, tags; the hint without tags; a bad severity is refused with Vim's `E475`); `set` makes a new list, reuses ours from the stack and makes it current, keeps the current entry (`'u'`), jumps to the first unless `!`, echoes the count; an update leaves a list of the user's alone; the column fix-up and when it does not happen; `:copen` on ours sends `eclipse_show_view`, on another list echoes `:clist`; `:lopen` echoes `:llist`.
- `tests/unit` (`NotificationBridgeTest`): the two notifications.
- UI test (plain text files, markers made by the test, no Java tooling): `:Eproblems` jumps to the first problem's column in the open file; `]q` opens the closed file's editor, on the line, then at the column; a marker added while the list is current appears in it, and with `:vimgrep`'s list current it does not; `:copen` shows the Problems view; `:Etasks` with `vim.g.neovim4eclipse_task_tags` set lists the tagged task only. The `findMarkers` cost (5.1) is measured there once, in a temporary test.

## 6. Tests

- `tests/unit`: the conversion (multi-byte text before the problem; a problem at end of line; a range over two lines; a zero-length range), the severity map, the sort; for phase 2 the quickfix entry choice (`:cc 3` past the end, `:cn` at the last entry: Vim's E553).
- `tests/unit -Pintegration` (real Neovim): `SET_DIAGNOSTICS` then `]d` moves where expected, with a count, and Ctrl-O comes back; `SETUP` sets `on_jump` when none is set and leaves a user's own alone; the echo shows the first line of a two-line message.
- UI test (plain text editor, no Java tooling): create a problem marker on the test file (`IResource.createMarker(IMarker.PROBLEM)` with `CHAR_START` / `CHAR_END`), wait for it in Neovim, press `]d`, check the caret; delete the marker, check the list is empty. A Java test of temporary problems belongs in `-PuiHeavyJava`.

## 7. Order of work

Problems first, then Neovim's `gr*` keys, then quickfix with tasks *(decided; revised 2026-10-25, tasks joined quickfix)*: the `gr*` keys (rename, code action, references, implementation, type definition) are worth more than walking tasks, and `gra` belongs with them. Across plans *(decided 2026-10-25)*: steps 1 to 3 before the windows plan's phase 1; step 5 after the windows plan is built.

1. The pure conversion and severity map (`util/`), with unit tests.
2. The bridge for problems: the annotation listener, debounce, `SET_DIAGNOSTICS`, the namespace config, the preference; integration and UI tests. README: a *Diagnostics* section and a row in the feature table; CHANGELOG.
3. The message after a jump: `on_jump` set in `SETUP` when the user has none (section 4).
4. **Neovim's `gr*` keys mapped to Eclipse** (`gra` Quick Fix among them) *(decided 2026-10-25, Q3)*: each as an IDE action, run through whatever Eclipse command the editor's context binds (as `gd` runs F3's), so a plain text editor without the command leaves Neovim's key alone. Questions that come up while building go to the maintainer.
5. Phase 2, quickfix, after the windows plan is built: way B first, with its experiment (section 5), then `:Eproblems`, then `:Etasks` with the *Task tags to include* preference, then `:copen` / `:cwindow`.
6. ~~Phase 2 gets its own detailed section here (with its experiments) before it starts.~~ Done 2026-10-29: section 5. Order now *(decided 2026-10-29)*: quickfix (step 5), then the `gr*` keys (step 4), both on `feature/quickfix`.

Each step is one commit or more, each with tests and docs.

## Decisions

- **Q1.** A preference to turn it off? *(decided; revised 2026-10-27: an `init.lua` variable, `vim.g.neovim4eclipse_problems = 0`, see "As built")* Yes: *Send Eclipse problems to Neovim* on the *Neovim* preference page, on by default. It costs some work per annotation change, and a user with their own diagnostics in `init.lua` (nvim-lint) may not want `]d` to stop on both.
- **Q2.** Task markers (`TODO`, `FIXME`)? *(decided**; revised 2026-10-25**)* Yes, but apart from problems, and only for the tags in the preference *Task tags to include* (comma-separated, empty = none, `*` = all). Typed rather than chosen from JDT's task-tag list, so there is no dependency on Java tooling. The maintainer's use: a rare personal tag, so that other people's TODOs stay out of the list. **Revised:** no tasks namespace and no `]t` / `[t` keys, because Neovim 0.12 maps those to tags; tasks are walked through the quickfix list only (`:Etasks`, then Neovim's `]q` / `[q`), so they arrive with phase 2 and span the workspace rather than one file.
- **Q3.** Show the message after `]d`? *(decided**; revised 2026-10-25**)* Yes, in the message line, through Neovim's `on_jump` hook: `SETUP` sets it only when the user's `init.lua` set none, and it echoes for any namespace's diagnostic (section 4). Opening a popup on each jump was considered and rejected: Eclipse's hover and Quick Fix take the keyboard focus, so the next `]d` would go to the popup, and a count or a macro would open one per step. Instead `K` after `]d` shows the hover (works today), and `gra` opens Quick Fix. **The `gr*` keys** *(decided 2026-10-25)*: map Neovim's defaults to their Eclipse counterparts as IDE actions, like `gd` and `K`, implemented as needed; the details are settled while building step 4, asking the maintainer where the choice is unclear. Those defaults (meant for a language server; all present in 0.12.6) are `grn` rename, `gra` code action, `grr` references, `gri` implementation, `grt` type definition, `grx` run code lens, `gO` document symbols; candidate Eclipse counterparts: Rename, Quick Fix, References, Open Implementation, Open Declared Type, none yet for `grx`, Quick Outline.
- **Q4.** How the workspace's problems get into quickfix? *(decided)* Both: commands `:Eproblems` and `:Etasks` fill the quickfix list (titled `Eclipse problems` / `Eclipse tasks`) and make it current; while that list is the current one, the plug-in keeps it up to date as markers change. A list the user made (`:vimgrep`) is never touched. User commands must start with a capital; the `E` prefix groups ours. **If keeping the list current turns out slow** (a workspace-wide `findMarkers` per build), fall back to the commands alone, which refresh only when run.
- **Q5.** What `:copen` / `:cw[indow]` do? *(decided**; confirmed 2026-10-25**)* Open Eclipse's view: the Problems view, or the Tasks view when the current list is `Eclipse tasks`; for any other list (`:vimgrep`), show `:clist`'s output in the output popup. Neovim's own quickfix window cannot be shown: it is a split, and the windows plan refuses splits (one hidden tabpage per editor, `:split` refused). This is the Eclipse-native equivalent, not a stopgap; revisit only if Vim splits are ever mapped onto Eclipse (a later note of the windows plan).
- **Q6.** How quickfix jumps reach files without an editor? *(decided 2026-10-25)* Way B, follow Neovim's jump (section 5): Neovim runs `:cnext` and its default `]q` / `[q` mappings itself, and the windows plan's `followNeovimInto` opens the editor for a workspace file that has none. Way A (host commands) is rejected: mappings bypass host commands, and macros would not record them.
- **Q7.** Which first, diagnostics or the windows plan? *(decided 2026-10-25)* Diagnostics phase 1 (section 7, steps 1 to 3) first: it is per buffer and does not touch windows. The windows plan next. Quickfix (phase 2) after the windows plan, because way B builds on it.
- **Q9.** *(decided 2026-10-29)* `:Eproblems` lists every severity; an argument filters: `warning` means at least a warning (errors and warnings), `error` errors only, as `vim.diagnostic`'s `severity = { min = ... }`.
- **Q10.** *(decided 2026-10-29)* `:Eproblems` and `:Etasks` jump to the first entry, `!` does not (Vim's `:make` and `:make!`). Run again, they update the list of their kind already in the stack and make it current, as `vim.diagnostic.setqflist()` does, rather than push a copy.
- **Q11.** *(decided 2026-10-29, option c of three)* Columns: a closed file's entries have the line only (a column needs the file's text); once its editor opens, its entries get exact columns from the editor's annotations, and a jump that just opened it moves on to the column. Open documents always have exact columns. Rejected: (a) line only everywhere; (b) reading every closed file's text for its columns.
- **Q2b.** *(decided 2026-10-29)* The task tags are an `init.lua` variable, `vim.g.neovim4eclipse_task_tags`, read each time `:Etasks` runs (not a preference page field), like the plug-in's other Neovim settings.
- **Q8.** Does anything come before phase 1? *(decided 2026-10-25)* Yes: the whole *Neovim 0.12 cleanup plan* (`NEOVIM_NATIVE.md` draft). Its step A1 (messages as `ext_messages` events) is what lets the `on_jump` echo reach the message line at all: `nvim_echo` does not set `v:statusmsg`, which is all Eclipse reads today, and `on_jump` runs scheduled, after that read.

## As built (phase 1, 2026-10-27)

Where the code is, and where it differs from the plan above:

- **`util/Diagnostic`** (the record, the severity numbers, the flat argument list) and **`util/DiagnosticPositions`** (offset to line and byte column): pure, tested in `tests/unit` (`DiagnosticTest`, `DiagnosticPositionsTest`). A position past the end of the text is clamped to the end of Neovim's last line rather than dropped: Neovim's line count has no line after a final line break, and Eclipse may not have moved a problem yet after a deletion.
- **`sync/DiagnosticsSync`**, not `editor/DiagnosticsBridge`: it needs only the text model (`IAnnotationModel` and `IDocument` are in the `org.eclipse.text` jar), so it is tested in `tests/text` (`DiagnosticsSyncTest`, with a real `AnnotationModel`). Which annotations are problems is a function it is given: **`editor/ProblemAnnotations`** answers with `DefaultMarkerAnnotationAccess.isSubtype` against the platform's three types, which needs the workbench. Spelling, tasks, bookmarks and occurrences are no subtype of them.
- **No dedupe in `util/`**: the sync sends nothing when the new list equals the one sent last. Typing moves Eclipse's positions, but Neovim's diagnostics move with the text on their own (extmarks, section 2), so only a change of the problems needs a list.
- **Debounce**: 300 ms after the first change (`DiagnosticsSync.DEBOUNCE_MS`), through a new `UiExecutor.timerExec` (SWT's `Display.timerExec`). Experiment 3 (how often JDT's model changes while typing) was not measured; the dedupe above makes the choice matter little.
- **The arguments of `SET_DIAGNOSTICS`** are flat strings (the buffer, then six per problem), since the session calls `nvim_exec_lua` with strings; no JSON, so a message with quotes or line breaks needs no escaping. `source = 'Eclipse'` is set on each diagnostic.
- **The namespace config** also turns off `virtual_lines` (Neovim 0.11+). The `on_jump` hook is set only when `init.lua` set neither `on_jump` nor the older `jump.float`.
- **Lifecycle**: one sync per document with a buffer, kept next to its `UnattachedDocumentSync` in `EditorAttachManager`, started on the document's first attach (after the controller's attach, so the first list is queued behind the buffer's first load) and closed with it. A document of an editor without an annotation model gets none.
- **Turning it off** (revised 2026-10-27; Q1 first decided a preference): `vim.g.neovim4eclipse_problems = 0` (or `false`) in `init.lua`, not a check box. It is set once and forgotten, belongs with the user's other Neovim settings (and their dotfiles), and matches `vim.g.neovim4eclipse_folds`. `LuaScripts.PROBLEMS` reads it once at start, after `init.lua`; the session keeps the answer (`DiagnosticsPort.problemsEnabled()`), and when off `EditorAttachManager` starts no `DiagnosticsSync`, so nothing is read or sent. A change needs a Neovim restart (the price of reading it once; the preference changed at once). Off, `]d` in a buffer without diagnostics would print Neovim's "No more valid diagnostics to move to", the same as a file without problems; so `PROBLEMS` wraps Neovim's *default* `]d [d ]D [D` (recognized by Neovim's description of each, and a function from Neovim's runtime: source `vim/_core/defaults`, or `?` on Windows, whose Neovim build has no debug information for its runtime) to say `]d: Eclipse's problems are not sent to Neovim (vim.g.neovim4eclipse_problems = 0)` in that case, and to call Neovim's own mapping in every other case. The wrapper adds words only: the jump, count, jump list, `on_jump` and the user's own diagnostics stay Neovim's (rather than turning the keys off, which would break diagnostics of the user's own). A key mapped or unmapped in `init.lua` is not wrapped.
- **Tests**: `diagnostics_spec.lua` (Neovim's side: the list, `]d` with a count, Ctrl-O, the echo, a user's own `on_jump`, nothing drawn), `DiagnosticsSyncTest` (when lists are sent), and the UI test `DiagnosticsEndToEndTest` (a problem marker on a plain text file, `]d` to it with its message in the message line, the marker deleted); the opt-out in `diagnostics_spec.lua` (the variable, the message, a count and a user's diagnostics through the wrapper, a user's own `]d`) and `NeovimProblemsIntegrationTest` (read through the session at start).
- **Not checked yet**: JDT's temporary problems (found while typing, before a save) in a Java editor, a `-PuiHeavyJava` test; experiment 5's macro playback in Eclipse.

## As built (phase 2, quickfix, 2026-10-29)

Where the code is, and where it differs from section 5:

- **`util/QuickfixEntries`** (pure, `QuickfixEntriesTest`): the kinds, the severity words (`leastSeverity`, `atLeast`), the task tags (`TaskTags`: comma-separated, `*`, a whole word at the start of the text, case kept), the order and the dedupe (`sorted`), the current list as Neovim answers it (`Current.fromLua`), and the flat arguments (`toLuaArgs`, seven per entry).
- **`LuaScripts.QUICKFIX`** (module `neovim4eclipse.quickfix`, `quickfix_spec.lua`), run at start after `WINDOW_COMMANDS`: the two commands (`E475` for a severity Eclipse has not, completion of the three), `set` (reuse by `context`, `'u'`, `:chistory`, `:cfirst` or the count, the column fix-up), `current`, `window`. `SET_QUICKFIX` and `QUICKFIX_CURRENT` call it. The list's title carries the argument: `Eclipse problems (warning)`, `Eclipse tasks (JM6)`.
- **The `WinNew` safety net** (`LuaScripts.WINDOWS`) calls `window()` for a closed quickfix or location list window when the module is loaded, instead of its message; without the module (a spec of the windows alone) the message stays.
- **`core/QuickfixPort`** (`NeovimSession`): `setQuickfixHandler`, `setQuickfixAsync`, `currentQuickfixAsync` (a future answered on the input thread). Notifications `eclipse_quickfix` and `eclipse_show_view` (`NotificationBridgeTest`).
- **`editor/QuickfixLists`**: the job (family `QuickfixLists.class`, so a test can wait for it), the `POST_CHANGE` listener installed by the `Activator`, `changed()` (called by `EditorAttachManager` when a document gets its buffer or loses it), `watching()` (the `DiagnosticsPort` given to each `DiagnosticsSync`, so a new list of an open document's problems schedules an update too). Before the first command of a Neovim, a change costs nothing.
- **`DiagnosticsSync.read` / `lines`** are static now: the quickfix list reads an open editor's problems the same way, whether phase 1 is on or not.
- **A marker on a folder or project** gets an entry without a file: its text in `:clist`, stepped over by `:cnext`.
- **Measured** (section 5.1's open item, a temporary UI test on Linux): `findMarkers` for 9,000 problems in 3,000 files 0.8 ms, the whole read of the list 18 ms (10 ms the second time). The job is kept anyway: nothing is gained by doing it on the UI thread.
- **Tests**: `QuickfixEndToEndTest` (UI): `:Eproblems` lands on the column of the open file's error; `]q` opens a closed file's editor, which then moves to the column; a marker added to a closed file and to the open one joins the current list, a marker added while `:vimgrep`'s list is current does not, and after `:colder` the list follows again; `:Eproblems! warning` leaves out infos; `:copen` shows the Problems view; `:Etasks` with `vim.g.neovim4eclipse_task_tags = 'JM6'` lists only that task and goes to it. Checked to fail with the column fix-up taken out, and with the marker listener taken out.
- **Not checked yet**: JDT's problems found while typing reaching the list (a `-PuiHeavyJava` test, as for phase 1); macOS and Windows.
- **Review fixes** (2026-10-29, `/code-review high`, seven findings): markers on folders or projects with the same text are kept apart (their workspace path is their place); an open editor whose model holds no markers (not an `AbstractMarkerAnnotationModel`) no longer hides its file's markers, which are listed by line instead; an open file of a nested project is listed once (open editors are matched by their place on disk, not their IFile, and a line-only entry gives way to the same problem at a column); the column fix-up moves the cursor in normal mode only; an update equal to the list sent last is not sent (about 150 ms on Neovim's input thread for 9,000 entries); a command waiting for its run is not delayed by an update's 500 ms; a list of text-only entries does not jump (no `E42`) and shows its count. Tests: `QuickfixEntriesTest`, `quickfix_spec.lua`, and the UI tests `anUnchangedListIsNotSentAgain` and `aNestedProjectsOpenFileIsListedOnce`; each checked to fail with its fix undone. Not tested: the non-marker model (no such editor in the platform to open) and the command delay (a timing).

## As built (step 4, the `gr*` keys, 2026-10-29)

On `feature/quickfix`, after phase 2. Decided with the maintainer: `grt` opens JDT's "Open Declared Type" hyperlink (there is no command); a key without an Eclipse command shows a message rather than running Neovim's own (which needs a language server inside Neovim); `gra` works in visual mode too, on the selection; and the code for `gd`, `K` and the new keys follows one table (the maintainer's request: don't repeat yourself).

- **Experiment** (a temporary UI test with JDT, 2026-10-29): in the Java editor JDT handles Rename (`org.eclipse.jdt.ui.edit.text.java.rename.element`, Alt+Shift+R), Quick Fix (`...correction.assist.proposals`, Ctrl+1, the platform's `QUICK_ASSIST` id, which the Text and Generic Editors handle too), References in Workspace (Ctrl+Shift+G; the Generic Editor binds it to `org.eclipse.ui.genericeditor.findReferences`), Open Implementation (no key), Quick Outline (Ctrl+O, the plug-in's own). "Open Declared Type" is only a hyperlink (`JavaElementHyperlinkDeclaredTypeDetector`). The platform's `org.eclipse.ui.edit.rename` (F2) is handled nowhere in text editors.
- **`core/IdeKeys`** (pure, `IdeKeysTest`): `KEYS` (key, modes, action) and `COMMANDS` (label, the ways to find the command, Neovim's fallback keys). `LuaScripts.IDE_KEYS` maps the keys from it (replacing the list in `SETUP`), and `editor/IdeActions` interprets it: `choose` (the first way that finds a command with an enabled handler, or a link; else `gd` for Open Declaration, else the message) and run. `HyperlinkProbe` opens one detector's link.
- **Fixed on the way**: a command id unknown to the running Eclipse (a JDT command without Java tooling) counted as run, since only "not handled" and "not enabled" were caught; `choose` checks `isDefined()` first.
- **Tests**: `setup_spec.lua` (each key sends its action, `gra` in visual mode keeps the selection, `grx` stays Neovim's, the opt-out), `IdeKeysTest`, `IdeKeysEndToEndTest` (the plain Text Editor's choices, the message), `JavaEditorEndToEndTest` (heavyJava: Java's command for each key, `gri` to the implementation, `grt` to `String`). Checked to fail with the key-binding and hyperlink ways taken out.
- **Review fixes** (2026-10-29, `/code-review high`, seven findings): one loop both chooses and runs (`IdeActions.pick`), so a command that turns out not to run when executed passes to the next way and `gd` still ends in Neovim's `gd`, and `grt`'s link is detected once (`HyperlinkProbe.open` detects and opens); whether a command can run is asked of the editor's own Eclipse 4 context (`EHandlerService.canExecute`, bundles `org.eclipse.e4.core.commands` and `org.eclipse.e4.core.contexts`), the lookup the run itself uses; `choose` refuses an action that is no command (`next_tab`); `IdeKeysTest` reads the action names from `IdeAction` instead of a list of its own; the UI test `visualGraRunsQuickFixOnTheSelection` checks that Quick Fix starts with Neovim's visual selection as the editor's; and the move of `gd`, `K` and `gt` onto the table is its own commit, before the `gr*` keys.
- **Not checked**: a Language Server editor (LSP4E is not in the test platform), CDT; macOS and Windows; the fallback when a command stops running between the check and the run (a timing).
