# Design note: folding with `zo`, `zc` and the other `z` commands

Status: **planned**, not implemented. First planned against `develop` at `3e0c21f` with Neovim
0.12.4 on Windows 11; **re-planned 2026-10-27** against `feature/diagnostics` at `589485a` (release
0.1.9 in progress) with Neovim 0.12.6, the minimum version, on Linux (arm64). The Eclipse
experiments of section 2 are from the first plan (`org.eclipse.jface.text` 3.31.100,
`org.eclipse.jdt.ui` 3.39.0) and are to be checked against the current target platform (section 8).
Decisions made with the maintainer are marked *(decided)*, open ones *(to decide)*. Every decision
was confirmed with the maintainer on 2026-10-27, so none is open; section 8 lists only facts to
check while building. Once the feature
is built, add a section *As built* after the summary saying where the code differs from this plan
and why, as [TAB_COMPLETION.md](TAB_COMPLETION.md) does.

This feature comes before [VIEWPORT.md](VIEWPORT.md) *(decided)*: the viewport counts screen rows,
and only with Eclipse's folds in Neovim is a closed fold one row on both sides.

## As built (in progress)

All three commits of section 7 are built. Where they differ from the plan below, and why:

- **The handle lines are worked out in Lua**, in the fold module (`LuaScripts.FOLDS`), not in
  `util/FoldRegions`. During `zn` a resend keeps Neovim's own states (3.2), which only Neovim knows,
  and a handle line depends on which inner folds are open. One place then serves both cases.
  `FoldRegions` keeps the rest: filtering, merging, sorting and the state diff.
- **Two more experiments**, run on 0.12.6 while building:
  - **X13:** `:{l}foldclose` on a line inside a *closed* inner fold closes the fold around it. So a
    handle line only has to avoid *open* inner folds.
  - **X14:** two folds that overlap without nesting (2-6, then 4-9) do not stay so: Neovim widens
    the second to 2-9. `FoldRegions` drops a region that crosses the border of one before it.
- **The module and the opt-out are one script.** `LuaScripts.FOLDS` returns whether folding is on
  and, when it is, installs `require('neovim4eclipse.folds')` (`set`, `closed`, `watch`, `poll`).
  `SET_FOLDS` and the snapshot call it. Fold ids are extmark ids, which start at 1: index + 1.
- **The `zn` resend** reads Neovim's states by turning folding on for the length of the call and
  opening the outermost closed folds level by level (nothing is redrawn inside one call). The folds
  whose range is unchanged keep those states; new ones take Eclipse's.
- **Names:** the event is `core/FoldState` (buffer and closed indexes); the session's side is the
  port `core/FoldsPort` (`foldsEnabled`, `setFoldsAsync`, `add/removeFoldStateListener`), as
  `DiagnosticsPort` is for problems. Its tests are `NeovimFoldsIntegrationTest` and
  `folds_spec.lua`.
- **X12 (the redraw trigger)** is not run yet; `poll` after every key is the trigger.
- **Folds before the cursor.** The session reports the closed folds *before* the cursor
  (`StateSync.publishState`), and both listeners hop to the UI thread through the same queue, so
  the editor applies the folds first, as 3.2 asks. Reported after it, a caret placed while a fold
  that `zo` had just opened was still collapsed in Eclipse would go to its caption line and stay.
- **No change to `DocumentPush`.** `NeovimEditorController.sendFolds` runs right after
  `DocumentPush.start`, on the UI thread; `setFoldsAsync` is queued on the same input thread, so the
  folds still arrive after the push. That covers attach, switching Neovim mode back on, and Neovim
  entering another editor's buffer (each attach makes a new controller).
- **`FoldMirror` has no session types**: it gets a `Consumer` that sends the folds, and the
  controller filters `FoldState` by buffer. So `tests/swt` compiles it with only `core/UiExecutor`.
- **Selections** use the caption rule too: an end of Neovim's visual selection inside a closed fold
  is put at the caption line's start. Checking `VisualSelectionView` and `BlockOverlay` over closed
  folds (3.3) is still to do.
- **There is no `:folddelete`.** Neovim (and Vim) delete folds with `zd`, `zD` and `zE` only;
  `:foldd` is `:folddoopen`, which opens folds and stays Neovim's. Only `:fold` is refused.
- **The refusal is a hook, not a rule in the Java table.** `HostCommand`'s table lists what Eclipse
  carries out, and `:fold` is not that; it is refused only in mirrored buffers, which only Lua
  knows. So `HOST_COMMANDS` offers `require('neovim4eclipse.host_commands').refuse(name, why)`, and
  the fold module registers `fold` there (it runs after `HOST_COMMANDS`). A refused line becomes
  `:Eclipse fold`, which shows the error. Being an error, it ends the rest of a `|` chain, as any
  error does in Vim (`:10,12fold | let x = 1` does not set `x`).
- **An Eclipse caret glitch, left alone** (found while trying it, 2026-10-27): now and then, right
  after a fold opens or closes, the caret is not drawn until it moves (normal mode, Linux, XFCE on
  X11). It happens the same with `Ctrl+Numpad` in Eclipse mode and in an Eclipse without the
  plug-in, so it is SWT's or the platform's (likely the caret's blink state across the viewer's
  redraw). A workaround (hide and show the caret after each fold change) was decided against: the
  plug-in would only cover Neovim mode, and the README notes it instead.
- **Tests:** a test editor that folds, `TestFoldingEditor` (`*.fold`, brace blocks are its folds),
  for `FoldingEndToEndTest` without Java tooling. Not done: a UI test of the opt-out (the UI tests
  share one Neovim, started without it; `NeovimFoldsIntegrationTest` covers it), and "a method added
  in insert mode becomes a fold after the reconcile".


## Summary

Eclipse folds code in editors built on a `ProjectionViewer`. The Java editor folds imports,
comments, methods and inner types; the Generic Editor folds what its language server reports.
Neovim knows nothing about these folds today.

- **The `z` commands do nothing to them.** `zc` reaches Neovim, which has no folds, and answers
  "E490: No fold found".
- **Moving into folded code unfolds it.** `editor/WidgetOffsets.toWidgetRevealing` expands the
  region (it is called by `StyledTextCaret` for every caret and selection move), because Neovim's
  cursor has no other way to stand there. So a method collapsed with the mouse opens again as soon
  as `j` reaches it.
- **A user's `'foldmethod'` already applies, unseen.** An `init.lua` with `set foldmethod=indent`
  makes Neovim close folds in the plug-in's buffers that Eclipse does not show. `j` then jumps over
  lines that are on screen.

**Plan:** mirror Eclipse's folds into Neovim *(decided)*.

- **Eclipse decides where the folds are:** its regions become manual folds in Neovim's window.
- **Open and closed state syncs both ways.** A fold that Neovim opens or closes is expanded or
  collapsed in Eclipse. A fold that Eclipse opens or closes (the ruler, `Ctrl`+`Numpad` keys, Open
  Declaration) is opened or closed in Neovim.
- **Neovim then does the Vim part itself:** `zo` `zO` `zc` `zC` `za` `zA` `zv` `zx` `zX` `zm` `zM`
  `zr` `zR` `zn` `zN` `zi` `zj` `zk` `[z` `]z`, with counts. `j` and `k` step over a closed fold,
  and the user's `'foldopen'` decides which commands open one.

The plug-in reimplements none of these commands. It only copies regions one way and the
open/closed state both ways.

Size: about 450 lines of code and 550 of tests (Java and Lua), in three commits (section 7), on
`feature/folding` cut from `feature/diagnostics` *(decided)*: it builds on that branch's snapshot
and `HOST_COMMANDS` changes, and merges into `release/0.1.9` after it.

## What changed since the first plan

The 0.1.7 cleanup and later work changed several things this feature builds on:

| First plan | Now |
|---|---|
| Snapshot of 15 elements, two flags | `STATE_SNAPSHOT` has 12 elements and one flag (the statusline); messages and `showcmd` come as `ext_messages` events. The fold element is index 12, the fold flag argument 2. |
| `zf` errors set `v:errmsg`, which the status bar showed through the snapshot | Errors are messages: `nvim_echo(..., { err = true })` reaches the message line through `MessageRoute`, as `NORMAL_WITHOUT_MAPPINGS` does. |
| `:fold`/`:folddelete` added to the Java `HostExCommand` parser | That parser is gone. Neovim parses every command line (`CmdlineLeavePre` with `nvim_parse_cmd`, `LuaScripts.HOST_COMMANDS`); the refusal is decided there, in Lua, without a round trip to Eclipse (3.5). |
| Lua tested through `NeovimSession` integration tests | Lua in `core/LuaScripts` is tested in Lua (`tests/unit/src/test/lua/*_spec.lua`, a headless Neovim). `SET_FOLDS` and the snapshot's fold element get a `folds_spec.lua`. |
| Nested folds: "check `foldclosed(l) == l` when two folds start on one line" | Checked (X3): creating them needs a specific order, and Neovim cannot report an inner fold's state while an outer one is closed. Both are now part of the design (3.1, 3.2). |
| Neovim 0.12.4 | 0.12.6 is the minimum. Every Neovim result below was rerun with it; none differs from 0.12.4's. |

Planned work that touches this one: one Neovim window per editor (a separate plan, not in the
repository). Manual folds are kept per buffer in a window (X10), so one window per editor needs
nothing new from this feature.

## 1. Behavior

- **Editors that can fold** (a `ProjectionViewer` with projection on: the Java editor, the Generic
  Editor) have their fold regions mirrored. Each `z` command acts on Eclipse's folds as Vim's acts
  on its own, and the two always show the same folds open and closed.
- **Region comments come for free.** Like C#'s `#region`, the Java editor can fold between two
  comments, `// region` and `// endregion` by default (*Java › Editor › Folding*, custom folding
  regions, Eclipse 4.35 or later; to check, section 8). A language server can report such regions
  too (TypeScript's `//#region`). Eclipse turns them into ordinary projection annotations, so the
  mirror copies them like any other fold. This is also the user's way to add a fold, instead of
  `zf` (next points).
- **A closed fold shows its caption line in Eclipse,** which is not always the first line: Java
  shows a method's signature, not the Javadoc above it (section 3.3). Neovim's cursor anywhere in a
  closed fold puts Eclipse's caret on that caption line.
- **`'foldopen'` is the user's,** and the plug-in does not set it *(decided)*. Out of the box this
  is Vim's behavior: `j` steps over a closed fold, and a search into one opens it. `set
  foldopen=all` in `init.lua` gives Eclipse's behavior, where any move into folded code opens it.
  README and `docs/KEYS.md` say so.
- **`zf`, `zF`, `zd`, `zD`, `zE`, `:fold` and `:folddelete` are not supported** in a mirrored
  buffer *(decided)*. They show an error such as `zf: Eclipse defines the folds in this editor`. Folds
  the user creates are out of scope (section 8). In editors that cannot fold, these commands keep
  Neovim's meaning, but folding is off there (next point).
- **Editors that cannot fold** (the plain Text Editor; any editor with folding turned off) get
  `'nofoldenable'` in Neovim's window while they are shown *(decided)*. Neovim then never hides
  lines that Eclipse shows, whatever the user's `'foldmethod'`. `z` commands give Neovim's usual
  errors.
- **On by default, with an opt-out in `init.lua`** *(decided)*: `vim.g.neovim4eclipse_folds = 0` (or
  `false`). As with `vim.g.neovim4eclipse_ide_keys`, there is no Eclipse preference. With the opt-out,
  the plug-in leaves Neovim's folding alone, as before this feature. Moving into folded code then
  unfolds it in Eclipse, as today.
- **The opt-out costs nothing** *(decided)*. The setting is read once per Neovim session, after
  `init.lua` has run, and when it is off none of the fold code runs (section 3.6). The cost is that
  changing `g:neovim4eclipse_folds` in a running Neovim does nothing until Neovim is restarted
  (Window › Preferences › Neovim, or restarting Eclipse). README says so.
- **Flat variable names stay** *(decided)*: `neovim4eclipse_folds` beside `neovim4eclipse_ide_keys`,
  not one table `vim.g.neovim4eclipse = { folds = … }`. Why:
  - `vim.g.neovim4eclipse.folds = 0` silently does nothing: reading `vim.g.x` returns a *copy* of
    the table, so the assignment changes the copy. Users would have to assign the whole table, and
    two config files doing so would overwrite each other.
  - Flat names are Vim's convention and work from Vimscript too (`let g:neovim4eclipse_folds = 0`);
    the prefix is the namespace.
  - The usual Lua answer, `require('neovim4eclipse').setup({ folds = false })`, needs a Lua module
    on Neovim's runtimepath *when `init.lua` runs*. The plug-in's Lua only arrives over RPC after
    Neovim has started, so the module would have to be installed and added with `--cmd "set
    rtp+=…"` at launch. A `setup()` that only writes these variables can be added later without
    breaking anyone; not in this plan.

## 2. Background, with the experiments

### Eclipse (first plan; recheck, section 8)

A `ProjectionViewer` hides collapsed regions from its widget; `WidgetOffsets` already converts
between widget and document offsets. Each region is a `ProjectionAnnotation` in the viewer's
`ProjectionAnnotationModel`, at a `Position` that covers whole lines. Several things change that
model:

- the editor's folding structure provider, after each reconcile;
- the user, through the folding ruler and the Expand/Collapse commands
  (`org.eclipse.ui.edit.text.folding.*`);
- `exposeModelRange`, when Eclipse reveals a hidden offset.

`IAnnotationModelListener` (or `IAnnotationModelListenerExtension`, whose events list what was added,
removed and changed) hears all of them. Collapsing or expanding counts as a change. Each annotation
keeps its own collapsed flag, also when an outer one is collapsed around it.

**The caption line.** A plain `Position` shows its first line when collapsed. A position that
implements `IProjectionPosition` decides itself which line stays visible:
`computeCaptionOffset(document)` gives it. JDT's fold positions do this
(`DefaultJavaFoldingStructureProvider$JavaElementPosition` and `$CommentPosition`, checked with
`javap`). A method's region starts at its Javadoc, but the collapsed method shows the signature
line, hiding lines both above and below it.

### Neovim 0.12.6

Manual folds are made with `:{first},{last}fold`, opened and closed with `:foldopen`/`:foldclose`
(with a line range), and read with `foldclosed()`/`foldclosedend()`. The script is in the session's
scratchpad (`fold.py`, `fold2.py`), not in the repository; it attaches with the plug-in's UI options
and sends keys with `nvim_input`, as the plug-in does. Lines are 1-based.

- **X1, edits through `nvim_buf_set_lines`** (the plug-in's way) move the folds. Two lines inserted
  above a fold at 3–5 moved it to 5–7. One line inside it replaced by two made it 5–8. Replacing the
  whole fold and the line after it by one line left a one-line fold (5–5): the fold is gone in
  Eclipse then, and the next resend (after the reconcile) replaces it.
- **X2, a full reload** as `LOAD_WITHOUT_UNDO` does it (`'undolevels'` -1, all lines replaced, same
  count) kept the fold. A reload is followed by a resend anyway (3.1).
- **X3, nested folds.** A `:fold` makes a *closed* fold, and a range on a closed fold is extended to
  the whole fold (`:help fold-behavior`). So `:2,10fold` then `:2,4fold` made the *same* fold twice
  (levels 2 on lines 2–10), not an inner one. Creating each fold open (`:2,10fold`, `:2foldopen`,
  then `:2,4fold`, `:2foldopen`) gave the right levels (`0222122211`). Then, with folds 2–10, 2–4
  and 6–8 open:
  - `zc` on line 2 closed the innermost fold there (2–4); a second `zc` closed 2–10;
  - with 2–4 and 2–10 both closed, `foldclosed()` reports only 2–10; `zo` then opens 2–10 and
    2–4 shows closed again: Neovim remembers an inner fold's state, but does not tell it while an
    outer fold is closed around it.
- **X4, `zn` and `zi`.** `zn` turns `'foldenable'` off (no closed folds reported); `zi` brings the
  same closed folds back.
- **X5, `'foldopen'`.** Lines 3–5 closed, the cursor on line 2, default `'foldopen'`
  (`block,hor,mark,percent,quickfix,search,tag,undo`):

  | Keys | Default | `foldopen=all` |
  |---|---|---|
  | `j` (onto the fold) | the cursor stands on the fold (line 3), which stays closed | opens |
  | `jj` (one batch) | line 6, closed | (first plan: line 6, closed; only where the batch ends counts) |
  | `k` from line 6 | line 3, closed | (not tried) |
  | `3G` | stays closed (`jump` is not in the default) | (first plan: opens) |
  | `/d` (match inside) | (first plan: opens, `search`) | opens |

  The `all` check runs in Neovim's main loop when no keys are pending, so `feedkeys(…, 'x')`, which
  skips the loop, never shows it. Tests must send keys the way the plug-in does. A key in a mapping
  does not use `'foldopen'` (`:help 'foldopen'`). For the plug-in's own mappings that does not
  matter: `gd` ends in Eclipse, which reveals the target, and that reaches Neovim as a state change
  (section 3.2).
- **X6, two-line folds.** With `'foldminlines'` 0, a fold of 3–5 (and of two lines) is closed and
  hidden. Neovim's default 1 would show a two-line fold open, and Eclipse folds two-line regions.
- **X7, `dd` and `u`.** `dd` on a closed fold deleted its three lines and the fold; `u` brought the
  lines back but not the fold. In Eclipse the annotation goes with the text, and the reconcile after
  the undo makes it again (expanded, as JDT does for new regions; section 8). So after `u` the fold
  is back in both, open, one reconcile later.
- **X11, closing by range.** Folds 2–10 and 2–4, both open. `:2,4foldclose` closed only 2–4.
  `:2,10foldclose` closed both (`zo` on line 6 then opened 2–10 and left 2–4 closed). `:5,10foldclose`
  (lines of the outer fold only) closed only 2–10. So a range closes the innermost open fold on each
  of its lines, and one line outside every open inner fold closes exactly the fold wanted.
- **X8, speed.** Creating 200 folds in a 5000-line buffer took 0.4 ms; reading the closed ones 0.7 ms
  (both including the round trip).
- **X9, rows.** With 25 rows and a closed fold over lines 3–40, `line('w$')` was 62 and `L` went to
  62: a closed fold is one screen row. This is what [VIEWPORT.md](VIEWPORT.md) relies on.
- **X10, buffer switch.** Switching the window to another buffer (`nvim_win_set_buf`) and back kept
  the fold, its state and `'foldmethod'=manual` of the first buffer.
- **A user's global `foldmethod=indent`** (first plan) applies to a buffer the plug-in creates. With
  a 4-space `'shiftwidth'`, which `util/Indentation` sets, the body of `class A {…}` was a closed
  fold at level 1.

## 3. Design

### 3.1 Eclipse to Neovim: regions and state

- **What is sent:** every annotation's line range `[first, last]`, 1-based, plus its collapsed
  flag. Ranges of fewer than two lines and duplicate ranges are dropped. The list is sorted by
  first line, then by length with the longest first, so that outer folds come before the folds
  inside them (also when they start on the same line).
- **When:**
  - at attach (in `DocumentPush`, after the push and the indent options, so that the lines exist);
  - when the editor becomes the shown buffer again (the `eclipse_buf_enter` path);
  - after any change to the annotation model that did not come from Neovim (next section).

  Changes are coalesced: one send per UI-thread turn (`asyncExec`), since a reconcile changes
  many annotations at once.
- **How:** a new `LuaScripts.SET_FOLDS`, queued through a session `*Async` method. It must not
  block the UI thread, and it must keep its order among the keys already queued. For the current
  buffer only (argument: the buffer number; skipped if another buffer is current, which then gets
  its folds when shown). It is only ever sent when folding is on (section 3.6), so it does not
  check the opt-out itself:
  1. Window-local `foldmethod=manual`, `foldenable`, `foldminlines=0` (X6), then `zE`.
  2. Create each fold **open**, outer first: `:{first},{last}fold` then `:{first}foldopen` (X3: a
     fold is created closed, and a range over a closed fold would grow to the whole fold).
  3. Close the collapsed ones, **inner first** (from the end of the list), so that an outer fold
     closed last hides inner ones already closed, as Eclipse shows them. Each with
     `:{l}foldclose` on one *handle line* `l`: a line of the fold that no open fold inside it
     covers, because `:foldclose` closes one level, the innermost open fold, on each line of its
     range (X11). The fold module computes the handle lines (*As built*). When open inner folds cover every
     line of a collapsed one (rare: an outer fold made only of open inner ones), those inner folds
     are closed with it, and Neovim then shows them closed when the outer one opens; the next
     snapshot makes Eclipse agree. *(decided)* The alternative, moving the cursor to each fold and
     running `zc` innermost first, needs a command per fold plus saving the cursor, for the same
     result.
  4. Put one extmark per fold, in the namespace `neovim4eclipse_folds`, from the fold's first line to
     its last (`end_row`). Its id is the fold's index in the list Eclipse sent, so Neovim can name a
     fold to Eclipse, and both stay valid while edits move lines (X1). The end is needed because two
     folds can start on one line (X3).
  5. Keep the cursor and view (`winsaveview`/`winrestview`).
  6. Set `b:neovim4eclipse_folds` (the buffer is mirrored) and the buffer-local `zf`/`zd`/`zE`
     mappings of section 3.5.

  An empty list means the editor cannot fold. The script then sets window-local `nofoldenable`,
  clears the marks and `b:neovim4eclipse_folds`, and removes the mappings.

### 3.2 Neovim to Eclipse: state

- **One trigger, kept swappable** *(decided)*. Neovim has no event for "a fold opened or closed":
  no autocommand, and no UI event without `ext_multigrid`. So the plug-in must look for itself, and
  *when* it looks is hidden behind one Lua function, `folds.watch(on_change)` in `SET_FOLDS`' setup
  code. `on_change` gets the list of outermost closed ids (below) and only runs when the list
  differs from the last one. Everything after it (the Java listener, the diff, the mirror) does not
  know what the trigger was. Should Neovim add an event (a `FoldChanged` autocommand, say), only
  `folds.watch` changes. Example of the contract:

  ```lua
  folds.watch(function(closed_ids)            -- e.g. { 0, 3 }: folds 0 and 3 are closed
    vim.api.nvim_exec_autocmds('User', { pattern = 'Neovim4EclipseFolds' })
    -- ...and the ids go to Eclipse (the snapshot element below)
  end)
  ```

  The `User Neovim4EclipseFolds` autocommand lets a user's config react to fold changes too.
  No `vim.g` setting picks the trigger *(decided)*: a cheaper trigger would only miss changes, and
  each one is a code path to test.
- **The first trigger: after every key.** `STATE_SNAPSHOT` gets a 13th element (index 12, added at the end, as
  `StateSnapshot` requires) and a second argument, `"1"` or `"0"`, like the statusline flag. With
  `"1"` the element holds the ids of the **outermost** closed folds: for each line `l` where
  `foldclosed(l) == l`, the mark that starts on `l` and ends on `foldclosedend(l)`. It is empty when
  there are no marks or `'foldenable'` is off, so `zn` opens everything in Eclipse too. With folding
  off the argument is `"0"`, and the element is an empty list that nothing reads.
- **Inner folds are "unknown", not "open"** *(decided)* (X3). The technical reason: Neovim can
  only report what is on screen. Inside a closed fold, `foldclosed(l)` returns the *outer* fold's
  first line for every line `l`, whatever the inner folds' state; `foldlevel(l)` gives depth, not
  state; and no function returns the open/closed state of a fold that is hidden. The only way to
  find out would be to open the outer fold (`zo`, then `zc` again), which changes the view, moves
  the cursor and is seen by the user's autocommands. Treating them as "open" instead would be
  wrong in a common case: `zM` closes a class and its methods, Eclipse would then expand every
  method, and after `zo` on the class all methods would show open, though both sides had them
  closed. So an id inside a closed fold is not in the list
  whatever its state, so the diff leaves annotations inside a reported closed fold as they are, and
  Eclipse keeps their state. When the outer fold opens, the next snapshot reports the inner ones as
  Neovim has them. Example: methods 2–4 and 6–8 inside a class 1–10; `zM` closes all three; the
  snapshot lists only the class; Eclipse collapses the class and leaves the methods' flags alone; `zo`
  on the class lists the two methods, which Eclipse shows collapsed.
- **Applied on the UI thread:** compare with the last list; collapse or expand only the
  annotations whose state differs. This must happen *before* the cursor is applied, so that the
  caret lands on visible text.
- **No echo:** these changes are made with a flag set, like `CaretSync.runAsFromNeovim`, so that
  the annotation listener does not send them back. A change Eclipse makes for another reason, such
  as revealing Open Declaration's target, still goes to Neovim.
- **Not seen with this trigger:** a fold opened or closed between keys (a plug-in's timer) shows at
  the next key. Experiment X12 (section 8) tries a second trigger that would see it.
- **A candidate trigger, redraw** (X12, to measure). A fold change always redraws the window, and a
  decoration provider (`nvim_set_decoration_provider`) has an `on_win` callback that runs for each
  window Neovim redraws. Reading the closed folds there (or setting a "dirty" flag there and reading
  in `on_end`) would also catch timer changes. Redraws can be more frequent than keys, so it is used
  only if X12 shows its cost per redraw is about the snapshot's (0.7 ms for 200 folds, X8) or less.
  Either way it goes behind `folds.watch`, and the snapshot element stays as the per-key path.
- **`zn`, then a resend from Eclipse** *(decided)*: while `'foldenable'` is off, a resend (after a
  reconcile, say) sends only the regions, not their open/closed state. Why: Eclipse shows every fold
  expanded while `zn` is in effect (it showed what Neovim asked), so a full resend would mark them
  all open, and `zi` would then bring back nothing. Without the state, `SET_FOLDS` keeps the states
  Neovim already has for folds whose range is unchanged, and `zi` restores the user's closed folds.
  To verify while building (section 8): that `zE` plus recreate under `nofoldenable` can keep those
  states, or else that the script skips `zE` and changes only the ranges that differ.

### 3.3 The cursor in a closed fold

- **Neovim to Eclipse** *(decided)*: if the cursor's line is in a closed fold that Eclipse also shows closed,
  the caret goes to the start of that fold's caption line, without revealing. The caption line
  comes from `IProjectionPosition.computeCaptionOffset`, or is the first line for a plain
  `Position`. `StyledTextCaret` gets that choice before `toWidgetRevealing`: today every hidden
  offset is revealed; afterwards, only offsets hidden by a fold Neovim has open. Neovim opens a fold
  before its cursor stands inside it in an open state, so that case means the two disagree for a
  moment. Reveal, as today. Letting Eclipse reveal every time (today's path) is what leaves Neovim's
  fold closed while Eclipse shows it open.
- **Eclipse to Neovim:** a click on a closed method's signature line puts Neovim's cursor on that
  line, inside the closed fold. Neovim treats that as standing on the fold, so `j` goes past it and
  `zo` opens it with the cursor on the signature.
- **Visual mode** over a closed fold: Neovim's selection includes the whole fold, and so does
  Eclipse's once its ends are converted. Selecting into a fold must not reveal it; check this with
  `VisualSelectionView` and `BlockOverlay`.

### 3.4 Edits and insert mode

- **Edits from Neovim** (`dd` on a closed fold deletes all its lines) reach the document as one
  edit. Eclipse drops or shrinks the annotations, and the next reconcile resends the regions.
- **Typing in insert mode** happens in Eclipse, where annotation positions follow the text. On
  `Esc` the text reaches Neovim through `nvim_buf_set_lines` (only the lines that differ,
  `util/LineDiff`), which moves Neovim's folds the same way (X1). The reconcile after typing resends
  the regions anyway.
- **Undo** (X7) *(decided)*: `u` restores the lines, not the fold; the reconcile brings it back in
  both, open. This is Neovim's own behavior for manual folds, and users will notice it, so README
  mentions it. Closing it again after the undo would need the plug-in to remember deleted closed
  folds and match them to restored lines; not worth the state.
- **The extmark ids** become stale only when the list is resent, and resending replaces them all.

### 3.5 The user's own folding

- **The user's `'foldmethod'` is replaced** by `manual`, window-local, only while a mirrored buffer
  is shown *(decided)*. An editor that cannot fold gets `nofoldenable`. With the opt-out, nothing is
  touched.
- **`zf`, `zF`, `zd`, `zD`, `zE`** (normal mode, plus `zf`, `zd` and `zD` in visual mode) get
  buffer-local mappings in mirrored buffers that show the error with `nvim_echo(..., { err = true })`.
- **`:fold` and `:folddelete`** (with their abbreviations and ranges): a built-in command cannot be
  mapped, but every `:` line already passes through `HOST_COMMANDS`' `CmdlineLeavePre`, which parses
  it with `nvim_parse_cmd`. A new rule, `refused`, for `fold` and `folddelete`: in a buffer with
  `b:neovim4eclipse_folds`, the line becomes `:Eclipse fold`, and the `Eclipse` user command shows
  the error itself instead of notifying Eclipse. Elsewhere (an editor that cannot fold, or the
  opt-out) the rule does not apply and Neovim runs the command. Like the other host commands this
  works for typed lines, mappings' `:fold<CR>`, macros and `:normal`, not for `vim.cmd.fold()`
  *(decided)*. Catching that too (the snapshot spotting folds that are not Eclipse's and resending
  Eclipse's set) would let the stray fold flash first and cost code for a rare case.
- `:foldopen`, `:foldclose`, `:folddoopen` and `:folddoclosed` stay Neovim's: they open, close or
  use folds and need nothing special.

### 3.6 The opt-out, read once

- **When:** the session reads `vim.g.neovim4eclipse_folds` once, while it starts, right after
  `LuaScripts.SETUP` (so after `init.lua`), in the same request or the one after it. Session start
  already runs off the UI thread and already waits for Neovim, so this adds no wait anywhere else.
  The value is kept in a final field, `NeovimSession.foldsEnabled()`. `0` and `false` (`v:false`)
  mean off, as for `neovim4eclipse_ide_keys`; anything else, including not set, means on.
- **What off skips, all of it:**
  - the controller creates no `FoldMirror`, so no annotation-model or projection listener is added
    and nothing is sent at attach, on a reconcile or on a buffer switch;
  - `SET_FOLDS` is never sent, so no window option, fold, extmark, buffer variable or mapping is
    set, and editors that cannot fold do not get `nofoldenable` either;
  - the snapshot's fold flag is `"0"`, so Neovim reads no fold state after a key;
  - with no `b:neovim4eclipse_folds`, the `refused` rule never applies: `:fold` is Neovim's;
  - `StyledTextCaret` keeps today's path (reveal every hidden offset), since there is no caption
    lookup.

  What remains is one flag test per key on each side.
- **Why not check the variable in Lua each time:** that kept a Neovim call per fold change and an
  Eclipse listener per editor even when the user had said no. Reading it once is cheaper and also
  makes the behavior stable for the life of the session. The cost (a change needs a Neovim restart)
  is the same as for `neovim4eclipse_ide_keys`, whose mappings are also made once at start.

## 4. Implementation, by layer

1. **`util/FoldRegions`** (pure, `tests/unit`):
   - from `(firstLine, lastLine, collapsed)` triples to the sorted, filtered list of section 3.1,
     with each collapsed fold's handle line (3.1 step 3);
   - from the snapshot's closed ids, the previous ones and the nesting to "collapse these, expand
     those", leaving folds inside a closed one alone (3.2).
2. **`core`**:
   - `LuaScripts.SET_FOLDS`, with `folds.watch` (3.2) and the `User Neovim4EclipseFolds`
     autocommand; the snapshot's second argument, element 12 and `StateSnapshot.FOLDS`;
   - the `refused` rule in `HOST_COMMANDS` and the `fold`/`folddelete` entries in the rules string
     (`util/HostCommand`'s table, which the session turns into that string);
   - `NeovimSession.foldsEnabled()`, read once at start (3.6), and the snapshot flag set from it;
   - `NeovimSession.setFoldsAsync(buffer, regions)`;
   - a `SessionEvents` listener for fold-state changes, like the cursor's.

   Delete `plugins/.../target/classes` after changing a script (AGENTS.md, Pitfalls).
3. **`editor/FoldMirror`** (SWT and JFace only, `tests/swt`):
   - holds the viewer's `ProjectionAnnotationModel`, the list last sent, and the id-to-annotation
     map;
   - listens to the annotation model and to `IProjectionListener` (folding turned on or off);
   - applies Neovim's state with the no-echo flag;
   - answers "caption offset of the closed fold at this document offset, or -1" for the caret code.

   The `ProjectionViewer` comes from `viewerOf(editor)`, as `WidgetOffsets` gets its viewer.
4. **`NeovimEditorController`** and `sync/DocumentPush`: when `session.foldsEnabled()`, the
   controller creates the mirror at attach and disposes it at detach; `DocumentPush` sends the
   regions after the push; the buffer-enter path sends them again. The controller routes the state
   listener to the mirror, then the caret, and hands the caption lookup to `StyledTextCaret`. When
   folding is off, none of this is created.

## 5. Tests

- **`tests/unit`**:
  - `FoldRegionsTest`: sorting (same start line: longer first), nesting, ranges under two lines,
    duplicates, handle lines (an open inner fold at the start, at the end, covering everything),
    the state diff, and "unknown" inner folds under a closed one;
  - `StateSnapshotTest`: the new element, and a short list giving no closed folds;
  - `HostCommandTest`: `fold` and `folddelete` are in the table with the `refused` rule.
- **Lua, `tests/unit/src/test/lua/folds_spec.lua`** (headless Neovim, no Java session):
  - `SET_FOLDS` makes the right levels for nested folds and folds sharing a first line (X3), closes
    exactly the collapsed ones, inner and outer, and keeps the cursor and view;
  - an empty list sets `nofoldenable` and clears marks, variable and mappings;
  - a global `foldmethod=indent` is replaced, window-local;
  - `folds.watch` calls back, and fires `User Neovim4EclipseFolds`, only when the closed list
    changes (two keys that change nothing: no call);
  - `zn`, a regions-only resend, then `zi`: the same folds closed as before (3.2);
  - the snapshot's element: outermost closed ids only; empty with `zn`; the same ids back with `zi`;
    the right id when two folds start on one line;
  - `zf` and `zE` in a mirrored buffer show the error; `:fold` and `:3,5fo` are refused there and
    run elsewhere (in `host_commands_spec.lua`).
- **`tests/unit -Pintegration`**, real Neovim through the session, keys sent with `nvim_input`
  (only what needs the session or `nvim_input`'s main-loop behavior):
  - `zc`, `zo`, `za`, `zM`, `zR` change the snapshot's closed list;
  - `j` steps over a closed fold; `/` into one opens it; `foldopen=all` makes `j` open it (X5);
  - edits above a fold move it; the folds survive a buffer switch;
  - the opt-out: `foldsEnabled()` is false for `0`, `false` and `v:false` and true when unset; with
    it off, a buffer with a global `foldmethod=indent` keeps the user's folds, and the snapshot's
    fold element is empty.
- **`tests/swt`**, a `ProjectionViewer` as in `WidgetOffsetsTest`:
  - `FoldMirror` reports regions, including a custom `IProjectionPosition`'s caption;
  - a collapse from the ruler side calls the sender, and applying Neovim's state does not;
  - the caption lookup.
- **UI test, `-Pui`:** an editor with folding is needed: a small test editor registered for a test
  extension in the fragment (like `TestMultiPageEditor`), with `ProjectionSupport` and a fixed
  folding structure, unless the Generic Editor folds a plain file in the test runtime (section 8).
  - `zc`, then `j` steps over the fold and the caret is on the line after it;
  - `zo` shows the lines again;
  - a collapse through `org.eclipse.ui.edit.text.folding.collapse` makes `j` step over the fold;
  - `zf` shows the error in the message line;
  - with `vim.g.neovim4eclipse_folds = 0` (the UI tests' own `init.lua` folder, see their `pom.xml`),
    the controller has no mirror, and moving into collapsed code unfolds it as today.
- **`-PuiHeavyJava`, `JavaEditorEndToEndTest`:**
  - `zM` collapses the methods;
  - on a method with Javadoc, the caret sits on the signature line;
  - `zo` opens it;
  - a method added in insert mode becomes a fold after the reconcile.

  The existing `movingIntoFoldedImportsUnfoldsThem` changes: `4G` into closed imports leaves them
  closed by default (`jump` is not in `'foldopen'`) and opens them with `foldopen=all`.

All waits use `TestWait`; "nothing more arrived" uses `NeovimBarrier.pass`. Nothing here should need
`timeoutReliant`.

## 6. Docs

- **README:** a row in the feature table, and a short folding section with `'foldopen'` and the
  opt-out.
- **`docs/KEYS.md`:** the `z` keys go to Neovim. List what is not supported, and say where
  folding is off.
- **`docs/ARCHITECTURE.md`:** replace the *Folding* bullet (folds are mirrored, the caption
  line), and add rows to the "Where logic lives" table.
- **`docs/LEARNING.md`:** a walkthrough of `zc` (key, Neovim closes the fold, the snapshot's ids,
  `FoldMirror` collapses the annotation, the caret on the caption line), and the projection concepts
  (`ProjectionViewer`, `ProjectionAnnotation`, `IProjectionPosition`).
- **`CHANGELOG.md`, *Unreleased*:** *Added*: folding with the `z` commands. *Changed*: moving into
  folded code no longer opens it unless `'foldopen'` says so, and a user's `'foldmethod'` no longer
  hides lines in Eclipse's editors.
- **This note:** status set to implemented, and an *As built* section added.

## 7. Commits

Three commits on `feature/folding` (cut from `feature/diagnostics`, section *Summary*), in UTC, as
AGENTS.md asks:

1. **The Neovim side:** `FoldRegions`, `SET_FOLDS`, the snapshot element, and the session method
   and event. Unit, Lua and integration tests. No change users can see yet.
2. **The Eclipse side:** `FoldMirror`, the controller and `DocumentPush` wiring, the caret on the
   caption line, and `nofoldenable` for editors that cannot fold. SWT, UI and heavy Java tests, and
   the docs and CHANGELOG of section 6.
3. **`zf`/`zd`/`zE` and `:fold`/`:folddelete` errors,** with their tests and a line in KEYS.md.

Because the branch is stacked on `feature/diagnostics`, it merges into `release/0.1.9` only after
that branch has; if diagnostics changes first, `feature/folding` is rebased onto it.

## 8. To check while implementing

- **The Eclipse side against the current target platform:** the projection API and JDT's caption
  positions (section 2 is from the first plan); and whether JDT makes a region recreated after an
  undo expanded (X7).
- **Which editors fold** in the test runtime and in a normal install: whether the plain Text Editor
  has projection in this Eclipse version, and whether the Generic Editor folds a `.txt` file
  without a language server. This decides the `-Pui` test editor.
- **`zn`/`zi` and a resend:** the decision is in 3.2 (regions only); check how `SET_FOLDS` keeps
  the existing states (Lua spec: `zn`, resend, `zi` gives the same closed folds).
- **X12, the redraw trigger** (3.2): cost per redraw of reading closed folds in a decoration
  provider's `on_win`, against the per-key snapshot; and whether it sees a fold closed by a
  `vim.defer_fn` timer. Switch `folds.watch` to it only if it is about as cheap.
- **Custom folding regions** (section 1): which Eclipse version added them to the Java editor, and
  that they arrive as ordinary `ProjectionAnnotation`s (expected; a `-PuiHeavyJava` check).
- **Speed:** a resend recreates every fold (X8: 0.4 ms for 200). Measure it for a large Java file
  (hundreds of members) with the session's command timeout in mind.
- **The Generic Editor's language-server folds** may arrive late or change often. Check that the
  coalescing is enough.

**Later, not in this plan:** folds the user creates (`zf` making a `ProjectionAnnotation`; the
reconciler would have to keep them), and `'foldclose'`, which should work already but will not be
tested.
