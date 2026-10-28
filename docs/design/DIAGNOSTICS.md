# Eclipse problems as Vim diagnostics and a quickfix list

Status: **phase 1 built** (steps 1 to 3 of section 7) on `feature/diagnostics`, cut from `release/0.1.8` at `4708e98` (2026-10-27), moved onto `release/0.1.9`; see *As built* at the end. Steps 4 (the `gr*` keys) and 5 (quickfix, phase 2) are not started. First planned against `release/0.1.3` at `64abd26`; **revised 2026-10-25** against `release/0.1.7` at `0e3c153` for the new minimum Neovim, **0.12.6** (`NeovimVersion.MINIMUM`), and for the window-per-editor plan (*One Neovim window per Eclipse editor*, `feature/neovim-windows`). The experiments in section 2 ran on Neovim 0.12.4 and were **repeated on 0.12.6** (the version CI tests) on 2026-10-25, on Linux. Decisions made with the maintainer are marked *(decided)*; the questions were settled on 2026-10-22 and revised on 2026-10-25.

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

- **`]q` / `[q`, `]Q` / `[Q`, `]<C-Q>` / `[<C-Q>`** (Neovim's default mappings) and **`:cn[ext]`, `:cp[revious]`, `:cN[ext]`, `:cc N`, `:cfir[st]`, `:cla[st]`, `:cnf[ile]`**: walk Neovim's current quickfix list, whatever filled it: Eclipse's problems or tasks, `:vimgrep`, or the user's own `setqflist()`. Neovim runs every one of them itself, typed, mapped or in a macro; an entry in a file that has no editor yet opens its editor, the way `:e file` does (section 5, way B).
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

## 5. Phase 2: quickfix (outline; detailed after phase 1)

Phase 2 starts **after the windows plan is built** (Q7). With that plan, a jump into a file that already has an editor works: `followNeovimInto` moves Neovim's window to that editor ("window travels", its E2). What is left is a file with no editor: Neovim `:edit`s it from disk and the plug-in undoes the jump (README, *Known limitations* #1). Two ways to fix that for quickfix:

- **A. Host commands** *(re**jected 2026-10-25**)*: `:cn`, `:cp`, `:cc`, `:cfir`, `:cla` as host commands (`HostExCommand`) that read the list (`getqflist({ idx = 0, items = 1 })`), set the index and open the entry's file in Eclipse. Rejected because Neovim 0.12's `]q` / `[q` / `]Q` / `[Q` / `]<C-Q>` / `[<C-Q>` mappings run `:cnext` and friends through `nvim_cmd` from Lua, which host commands never see; covering them would mean overriding Neovim's mappings too, and none of it would be recorded in macros.
- **B. Follow Neovim's jump** *(decided 2026-10-25)*: Neovim runs `:cnext` itself, typed, mapped or in a macro. When `BufEnter` reaches a buffer for a workspace file that has no editor, `followNeovimInto` opens that file's editor (as `:e file` does) and gives it the window, instead of undoing the jump. Works for any list (`:vimgrep` too), and would also make `gf` and `:b name` work for closed files. The risk: Neovim loaded the file from disk, while the plug-in fills each document's buffer from Eclipse's text, so the two loads must not fight (cursor, undo history, `BufRead` autocommands, filetype). That is phase 2's first experiment.

Filling the list (Q4, decided: `:Eproblems` / `:Etasks`, kept current while current): from `IWorkspaceRoot.findMarkers(IMarker.PROBLEM, true, DEPTH_INFINITE)` (every problem, open or not; open documents take positions from their annotations instead, which are fresher), sorted like the Problems view: by file, then line. Never replace a list the user made: only a list titled `Eclipse problems` is updated when markers change.

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
6. Phase 2 gets its own detailed section here (with its experiments) before it starts.

Each step is one commit or more, each with tests and docs.

## Decisions

- **Q1.** A preference to turn it off? *(decided; revised 2026-10-27: an `init.lua` variable, `vim.g.neovim4eclipse_problems = 0`, see "As built")* Yes: *Send Eclipse problems to Neovim* on the *Neovim* preference page, on by default. It costs some work per annotation change, and a user with their own diagnostics in `init.lua` (nvim-lint) may not want `]d` to stop on both.
- **Q2.** Task markers (`TODO`, `FIXME`)? *(decided**; revised 2026-10-25**)* Yes, but apart from problems, and only for the tags in the preference *Task tags to include* (comma-separated, empty = none, `*` = all). Typed rather than chosen from JDT's task-tag list, so there is no dependency on Java tooling. The maintainer's use: a rare personal tag, so that other people's TODOs stay out of the list. **Revised:** no tasks namespace and no `]t` / `[t` keys, because Neovim 0.12 maps those to tags; tasks are walked through the quickfix list only (`:Etasks`, then Neovim's `]q` / `[q`), so they arrive with phase 2 and span the workspace rather than one file.
- **Q3.** Show the message after `]d`? *(decided**; revised 2026-10-25**)* Yes, in the message line, through Neovim's `on_jump` hook: `SETUP` sets it only when the user's `init.lua` set none, and it echoes for any namespace's diagnostic (section 4). Opening a popup on each jump was considered and rejected: Eclipse's hover and Quick Fix take the keyboard focus, so the next `]d` would go to the popup, and a count or a macro would open one per step. Instead `K` after `]d` shows the hover (works today), and `gra` opens Quick Fix. **The `gr*` keys** *(decided 2026-10-25)*: map Neovim's defaults to their Eclipse counterparts as IDE actions, like `gd` and `K`, implemented as needed; the details are settled while building step 4, asking the maintainer where the choice is unclear. Those defaults (meant for a language server; all present in 0.12.6) are `grn` rename, `gra` code action, `grr` references, `gri` implementation, `grt` type definition, `grx` run code lens, `gO` document symbols; candidate Eclipse counterparts: Rename, Quick Fix, References, Open Implementation, Open Declared Type, none yet for `grx`, Quick Outline.
- **Q4.** How the workspace's problems get into quickfix? *(decided)* Both: commands `:Eproblems` and `:Etasks` fill the quickfix list (titled `Eclipse problems` / `Eclipse tasks`) and make it current; while that list is the current one, the plug-in keeps it up to date as markers change. A list the user made (`:vimgrep`) is never touched. User commands must start with a capital; the `E` prefix groups ours. **If keeping the list current turns out slow** (a workspace-wide `findMarkers` per build), fall back to the commands alone, which refresh only when run.
- **Q5.** What `:copen` / `:cw[indow]` do? *(decided**; confirmed 2026-10-25**)* Open Eclipse's view: the Problems view, or the Tasks view when the current list is `Eclipse tasks`; for any other list (`:vimgrep`), show `:clist`'s output in the output popup. Neovim's own quickfix window cannot be shown: it is a split, and the windows plan refuses splits (one hidden tabpage per editor, `:split` refused). This is the Eclipse-native equivalent, not a stopgap; revisit only if Vim splits are ever mapped onto Eclipse (a later note of the windows plan).
- **Q6.** How quickfix jumps reach files without an editor? *(decided 2026-10-25)* Way B, follow Neovim's jump (section 5): Neovim runs `:cnext` and its default `]q` / `[q` mappings itself, and the windows plan's `followNeovimInto` opens the editor for a workspace file that has none. Way A (host commands) is rejected: mappings bypass host commands, and macros would not record them.
- **Q7.** Which first, diagnostics or the windows plan? *(decided 2026-10-25)* Diagnostics phase 1 (section 7, steps 1 to 3) first: it is per buffer and does not touch windows. The windows plan next. Quickfix (phase 2) after the windows plan, because way B builds on it.
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
