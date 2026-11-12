# Design note: folding with `zo`, `zc` and the other `z` commands

Eclipse's folds are mirrored into Neovim as manual folds, so Vim's `z` commands work on them and the
two sides always show the same folds open and closed. The Neovim results below were checked with
Neovim 0.12.6, the minimum version, on Linux (arm64), except where the X5 table marks a cell 0.12.4.
The experiments first ran on Neovim 0.12.4 on Windows 11; none of the results repeated on 0.12.6
differs.
The Eclipse results of section 2 were checked with `org.eclipse.jface.text` 3.31.100 and
`org.eclipse.jdt.ui` 3.39.0.

[VIEWPORT.md](VIEWPORT.md) builds on this: the viewport counts screen rows, and only with Eclipse's
folds in Neovim is a closed fold one row on both sides.

## Summary

Eclipse folds code in editors built on a `ProjectionViewer`. The Java editor folds imports,
comments, methods and inner types; the Generic Editor folds what its language server reports.
Without the mirror Neovim knows nothing about these folds:

- **The `z` commands do nothing to them.** `zc` reaches Neovim, which has no folds, and answers
  "E490: No fold found".
- **Moving into folded code unfolds it.** `editor/WidgetOffsets.toWidgetRevealing` expands the
  region (it is called by `StyledTextCaret` for every caret and selection move), because Neovim's
  cursor has no other way to stand there. So a method collapsed with the mouse opens again as soon
  as `j` reaches it.
- **A user's `'foldmethod'` applies, unseen.** An `init.lua` with `set foldmethod=indent` makes
  Neovim close folds in the plug-in's buffers that Eclipse does not show. `j` then jumps over lines
  that are on screen.

**Design:** mirror Eclipse's folds into Neovim.

- **Eclipse decides where the folds are:** its regions become manual folds in Neovim's window.
- **Open and closed state syncs both ways.** A fold that Neovim opens or closes is expanded or
  collapsed in Eclipse. A fold that Eclipse opens or closes (the ruler, `Ctrl`+`Numpad` keys, Open
  Declaration) is opened or closed in Neovim.
- **Neovim then does the Vim part itself:** `zo` `zO` `zc` `zC` `za` `zA` `zv` `zx` `zX` `zm` `zM`
  `zr` `zR` `zn` `zN` `zi` `zj` `zk` `[z` `]z`, with counts. `j` and `k` step over a closed fold,
  and the user's `'foldopen'` decides which commands open one.

The plug-in reimplements none of these commands. It only copies regions one way and the
open/closed state both ways.

With one Neovim window per editor ([WINDOWS.md](WINDOWS.md)), manual folds are kept per buffer in a
window (X10), so this needs nothing extra: an editor's closed folds stay in its own window between
switches, two editors on one file have their own, and the send on attach finds them already there.

## 1. Behavior

- **Editors that can fold** (a `ProjectionViewer` with projection on: the Java editor, the Generic
  Editor) have their fold regions mirrored. Each `z` command acts on Eclipse's folds as Vim's acts
  on its own, and the two always show the same folds open and closed.
- **Region comments come for free.** Like C#'s `#region`, the Java editor can fold between two
  comments, `// region` and `// endregion` by default (*Java › Editor › Folding*, custom folding
  regions, Eclipse 4.35 or later, not checked: section 7). A language server can report such regions too
  (TypeScript's `//#region`). Eclipse turns them into ordinary projection annotations, so the mirror
  copies them like any other fold. This is also the user's way to add a fold, instead of `zf` (next
  points).
- **A closed fold shows its caption line in Eclipse,** which is not always the first line: Java
  shows a method's signature, not the Javadoc above it (section 3.3). Neovim's cursor anywhere in a
  closed fold puts Eclipse's caret on that caption line.
- **`'foldopen'` is the user's,** and the plug-in does not set it. Out of the box this is Vim's
  behavior: `j` steps over a closed fold, and a search into one opens it. `set foldopen=all` in
  `init.lua` gives Eclipse's behavior, where any move into folded code opens it. docs/USER_GUIDE.md and
  `docs/KEYBOARD.md` say so.
- **`zf`, `zF`, `zd`, `zD`, `zE` and `:fold` are not supported** in a mirrored buffer. They show
  `zf: Eclipse defines the folds in this editor` and change no fold: Eclipse's next reconcile would
  replace any fold made by hand. Neovim (and Vim) have no `:folddelete`: folds are deleted with
  `zd`, `zD` and `zE` only, and `:foldd` is `:folddoopen`, which opens folds and stays Neovim's. In
  editors that cannot fold these commands keep Neovim's meaning, but folding is off there (next
  point).
- **Editors that cannot fold** (the plain Text Editor; any editor with folding turned off) get
  `'nofoldenable'` in Neovim's window while they are shown. Neovim then never hides lines that
  Eclipse shows, whatever the user's `'foldmethod'`. `z` commands give Neovim's usual errors.
- **On by default, with an opt-out in `init.lua`:** `vim.g.neovim4eclipse_folds = 0` (or `false`).
  As with `vim.g.neovim4eclipse_ide_keys`, there is no Eclipse preference. With the opt-out, the
  plug-in leaves Neovim's folding alone, and moving into folded code unfolds it in Eclipse.
- **The opt-out costs nothing.** The setting is read once per Neovim session, after `init.lua` has
  run, and when it is off none of the fold code runs (section 3.6). The cost is that changing
  `g:neovim4eclipse_folds` in a running Neovim does nothing until Neovim is restarted (Window ›
  Preferences › Neovim, or restarting Eclipse). The README's Settings say so.
- **Flat variable names:** `neovim4eclipse_folds` beside `neovim4eclipse_ide_keys`, not one table
  `vim.g.neovim4eclipse = { folds = … }`. Why:
  - `vim.g.neovim4eclipse.folds = 0` silently does nothing: reading `vim.g.x` returns a *copy* of
    the table, so the assignment changes the copy. Users would have to assign the whole table, and
    two config files doing so would overwrite each other.
  - Flat names are Vim's convention and work from Vimscript too (`let g:neovim4eclipse_folds = 0`);
    the prefix is the namespace.
  - The usual Lua answer, `require('neovim4eclipse').setup({ folds = false })`, needs a Lua module
    on Neovim's runtimepath *when `init.lua` runs*. The plug-in's Lua only arrives over RPC after
    Neovim has started, so the module would have to be installed and added with `--cmd "set
    rtp+=…"` at launch. A `setup()` that only writes these variables could be added later without
    breaking anyone.

## 2. Background, with the experiments

### Eclipse

A `ProjectionViewer` hides collapsed regions from its widget; `WidgetOffsets` converts between
widget and document offsets. Each region is a `ProjectionAnnotation` in the viewer's
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
(with a line range), and read with `foldclosed()`/`foldclosedend()`. The experiment scripts (not in
the repository) attach with the plug-in's UI options and send keys with `nvim_input`, as the
plug-in does. Lines are 1-based.

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
  (`block,hor,mark,percent,quickfix,search,tag,undo`). Cells marked 0.12.4 were checked only there:

  | Keys | Default | `foldopen=all` |
  |---|---|---|
  | `j` (onto the fold) | the cursor stands on the fold (line 3), which stays closed | opens |
  | `jj` (one batch) | line 6, closed | line 6, closed (0.12.4): only where the batch ends counts |
  | `k` from line 6 | line 3, closed | (not tried) |
  | `3G` | stays closed (`jump` is not in the default) | opens (0.12.4) |
  | `/d` (match inside) | opens, `search` (0.12.4) | opens |

  The `all` check runs in Neovim's main loop when no keys are pending, so `feedkeys(…, 'x')`, which
  skips the loop, never shows it. Tests must send keys the way the plug-in does. A key in a mapping
  does not use `'foldopen'` (`:help 'foldopen'`). For the plug-in's own mappings that does not
  matter: `gd` ends in Eclipse, which reveals the target, and that reaches Neovim as a state change
  (section 3.2).
- **X6, two-line folds.** With `'foldminlines'` 0, a fold of 3–5 (and of two lines) is closed and
  hidden. Neovim's default 1 would show a two-line fold open, and Eclipse folds two-line regions.
- **X7, `dd` and `u`.** `dd` on a closed fold deleted its three lines and the fold; `u` brought the
  lines back but not the fold. In Eclipse the annotation goes with the text, and the reconcile after
  the undo makes it again (expanded, as JDT does for new regions; section 7). So after `u` the fold
  is back in both, open, one reconcile later.
- **X8, speed.** Creating 200 folds in a 5000-line buffer took 0.4 ms; reading the closed ones 0.7 ms
  (both including the round trip).
- **X9, rows.** With 25 rows and a closed fold over lines 3–40, `line('w$')` was 62 and `L` went to
  62: a closed fold is one screen row. This is what [VIEWPORT.md](VIEWPORT.md) relies on.
- **X10, buffer switch.** Switching the window to another buffer (`nvim_win_set_buf`) and back kept
  the fold, its state and `'foldmethod'=manual` of the first buffer.
- **X11, closing by range.** Folds 2–10 and 2–4, both open. `:2,4foldclose` closed only 2–4.
  `:2,10foldclose` closed both (`zo` on line 6 then opened 2–10 and left 2–4 closed). `:5,10foldclose`
  (lines of the outer fold only) closed only 2–10. So a range closes the innermost open fold on each
  of its lines, and one line outside every open inner fold closes exactly the fold wanted.
- **X12, a redraw trigger:** not run (section 3.2).
- **X13, closing over a closed inner fold.** `:{l}foldclose` on a line inside a *closed* inner fold
  closes the fold around it. So a handle line (3.1) only has to avoid *open* inner folds.
- **X14, crossing folds.** Two folds that overlap without nesting (2–6, then 4–9) do not stay so:
  Neovim widens the second to 2–9. `util/FoldRegions` drops a region that crosses the border of one
  before it.
- **A user's global `foldmethod=indent`** applies to a buffer the plug-in creates. With a 4-space
  `'shiftwidth'`, which `util/Indentation` sets, the body of `class A {…}` was a closed fold at
  level 1.

## 3. Design

### 3.1 Eclipse to Neovim: regions and state

- **What is sent:** every annotation's line range `[first, last]`, 1-based, plus its collapsed
  flag. Ranges of fewer than two lines, duplicate ranges and ranges that cross the border of an
  earlier one (X14) are dropped. The list is sorted by first line, then by length with the longest
  first, so that outer folds come before the folds inside them (also when they start on the same
  line). `util/FoldRegions` decides all of this.
- **When:**
  - at attach: `NeovimEditorController.sendFolds` runs right after `DocumentPush.start`, on the UI
    thread. `setFoldsAsync` is queued on the same input thread as the push, so the folds arrive
    after the lines and the indent options. `DocumentPush` itself has no fold code. This covers
    attach, switching Neovim mode back on, and Neovim entering another editor's buffer (each attach
    makes a new controller);
  - after any change to the annotation model that did not come from Neovim (section 3.2), or to
    projection (`IProjectionListener`: folding turned on or off).

  Changes are coalesced: one send per UI-thread turn (`asyncExec`), since a reconcile changes
  many annotations at once. Sends are held while Eclipse types in insert mode (section 3.4).
- **How:** `LuaScripts.SET_FOLDS`, queued through the session's `setFoldsAsync`. It must not block
  the UI thread, and it must keep its order among the keys already queued. It acts on the current
  buffer only (argument: the buffer number; skipped if another buffer is current, which then gets
  its folds when shown) and calls the fold module's `set` (section 4). It is only ever sent when
  folding is on (section 3.6), so it does not check the opt-out itself. `set`:
  1. Sets window-local `foldmethod=manual`, `foldenable`, `foldminlines=0` (X6), then deletes the
     old folds with `:noautocmd normal! zE` (section 6: a plain `:normal` breaks insert mode).
  2. Creates each fold **open**, outer first: `:{first},{last}fold` then `:{first}foldopen` (X3: a
     fold is created closed, and a range over a closed fold would grow to the whole fold).
  3. Closes the collapsed ones, **inner first** (from the end of the list), so that an outer fold
     closed last hides inner ones already closed, as Eclipse shows them. Each with
     `:{l}foldclose` on one *handle line* `l`: the first line of the fold that no open fold inside
     it covers, because `:foldclose` closes one level, the innermost open fold, on each line of its
     range (X11); closed inner folds do not matter (X13). Example: a collapsed class on lines 1–20
     whose lines 1–8 are an open method is closed on line 9. The handle lines are worked out in Lua,
     not in `FoldRegions`: during `zn` a resend keeps Neovim's own states (3.2), which only Neovim
     knows, and a handle line depends on which inner folds are open, so one place serves both
     cases. When open inner folds cover every line of a collapsed one (rare: an outer fold made only
     of open inner ones), the first line is used, the innermost of them closes instead, and the next
     snapshot makes Eclipse agree. The alternative, moving the cursor to each fold and running `zc`
     innermost first, needs a command per fold plus saving the cursor, for the same result.
  4. Puts one extmark per fold, in the namespace `neovim4eclipse_folds`, from the fold's first line
     to its last (`end_row`). Its id is the fold's index in the list Eclipse sent plus one (extmark
     ids start at 1), so Neovim can name a fold to Eclipse, and both stay valid while edits move
     lines (X1). The end is needed because two folds can start on one line (X3).
  5. Keeps the cursor and view (`winsaveview`/`winrestview`).
  6. Sets `b:neovim4eclipse_folds` (the buffer is mirrored) and the buffer-local `z` mapping of
     section 3.5.

  An empty list means the editor cannot fold. `set` then sets window-local `nofoldenable`, clears
  the marks and `b:neovim4eclipse_folds`, and removes the mapping. All options are set window-local
  (`:setlocal`), so other windows and the user's global values are untouched.

### 3.2 Neovim to Eclipse: state

- **One trigger, kept swappable.** Neovim has no event for "a fold opened or closed": no
  autocommand, and no UI event without `ext_multigrid`. So the plug-in must look for itself, and
  *when* it looks is hidden behind the fold module's `watch(fn)` and `poll(win)`. `poll` returns
  the list of outermost closed ids (below) and calls every `watch` function only when the list
  differs from the last one for that window and buffer. Everything after it (the Java listener,
  the diff, the mirror) does not know what the trigger was. Should Neovim add an event (a
  `FoldChanged` autocommand, say), only this trigger changes. The first watcher fires the
  autocommand `User Neovim4EclipseFolds` (with `data = { buf = 3, closed = { 0, 2 } }`), so a
  user's config can react to fold changes too:

  ```lua
  vim.api.nvim_create_autocmd('User', {
    pattern = 'Neovim4EclipseFolds',
    callback = function(ev) print(vim.inspect(ev.data.closed)) end,   -- e.g. { 0, 3 }
  })
  ```

  No `vim.g` setting picks the trigger: a cheaper trigger would only miss changes, and each one is
  a code path to test.
- **The trigger: after every key.** `STATE_SNAPSHOT` calls `poll`. Its 13th element (index 12,
  added at the end, as `StateSnapshot` requires; `StateSnapshot.FOLDS`) depends on its second
  argument, `"1"` or `"0"`, like the statusline flag. With `"1"` the element holds the indexes of
  the **outermost** closed folds: for each line `l` where `foldclosed(l) == l`, the mark that
  starts on `l` and ends on `foldclosedend(l)`. It is empty when there are no marks or
  `'foldenable'` is off, so `zn` opens everything in Eclipse too. With folding off the argument is
  `"0"`, and the element is an empty list that nothing reads. The session turns it into a
  `core/FoldState` event (buffer and closed indexes).
- **Inner folds are "unknown", not "open"** (X3). Neovim can only report what is on screen. Inside
  a closed fold, `foldclosed(l)` returns the *outer* fold's first line for every line `l`, whatever
  the inner folds' state; `foldlevel(l)` gives depth, not state; and no function returns the
  open/closed state of a fold that is hidden. The only way to find out would be to open the outer
  fold (`zo`, then `zc` again), which changes the view, moves the cursor and is seen by the user's
  autocommands. Treating them as "open" instead would be wrong in a common case: `zM` closes a
  class and its methods, Eclipse would then expand every method, and after `zo` on the class all
  methods would show open, though both sides had them closed. So an id inside a closed fold is not
  in the list whatever its state, the diff (`util/FoldRegions.stateChange`) leaves annotations
  inside a reported closed fold as they are, and Eclipse keeps their state. When the outer fold
  opens, the next snapshot reports the inner ones as Neovim has them. Example: methods 2–4 and 6–8
  inside a class 1–10; `zM` closes all three; the snapshot lists only the class; Eclipse collapses
  the class and leaves the methods' flags alone; `zo` on the class lists the two methods, which
  Eclipse shows collapsed.
- **Applied on the UI thread, before the cursor:** compare with the last list; collapse or expand
  only the annotations whose state differs. This must happen *before* the cursor is applied, so
  that the caret lands on visible text. The session reports the closed folds before the cursor
  (`StateSync.publishState`), and both listeners hop to the UI thread through the same queue, so
  the editor applies the folds first. Reported after it, a caret placed in a fold that `zo` had just
  opened, while it was still collapsed in Eclipse, would go to its caption line and stay.
- **No echo:** these changes are made with a flag set, so that the annotation listener does not
  send them back. They are also made inside `CaretSync.runAsFromNeovim` (section 6). A change
  Eclipse makes for another reason, such as revealing Open Declaration's target, still goes to
  Neovim.
- **Not seen with this trigger:** a fold opened or closed between keys (a plug-in's timer) shows at
  the next key.
- **A candidate trigger, redraw (X12, not run).** A fold change always redraws the window, and a
  decoration provider (`nvim_set_decoration_provider`) has an `on_win` callback that runs for each
  window Neovim redraws. Reading the closed folds there (or setting a "dirty" flag there and reading
  in `on_end`) would also catch timer changes. Redraws can be more frequent than keys, so it is worth
  using only if its cost per redraw is about the snapshot's (0.7 ms for 200 folds, X8) or less.
  Either way it goes behind `watch`/`poll`, and the snapshot element stays as the per-key path.
- **`zn`, then a resend from Eclipse:** while `'foldenable'` is off, a resend (after a reconcile,
  say) does not take Eclipse's open/closed states. Eclipse shows every fold expanded while `zn` is
  in effect (it shows what Neovim asked), so its states would mark them all open, and `zi` would
  then bring back nothing. So `set` first reads the states Neovim keeps: it turns folding on for the
  length of the call (nothing is redrawn inside one call), opens the outermost closed folds level by
  level, noting each range, and then uses those states for the folds whose range is unchanged; new
  folds take Eclipse's. `'nofoldenable'` is set again at the end, and `zi` restores the user's
  closed folds.

### 3.3 The cursor in a closed fold

- **Neovim to Eclipse:** if the cursor's line is in a closed fold that Eclipse also shows closed,
  the caret goes to the start of that fold's caption line, without revealing. The caption line
  comes from `IProjectionPosition.computeCaptionOffset`, or is the first line for a plain
  `Position`. `StyledTextCaret` asks `FoldMirror.captionOffset` before `toWidgetRevealing`; only
  offsets hidden by something else (a fold Neovim has open) are revealed. Neovim opens a fold
  before its cursor stands inside it in an open state, so that case means the two disagree for a
  moment: reveal. Letting Eclipse reveal every time would leave Neovim's fold closed while Eclipse
  shows it open.
- **Eclipse to Neovim:** a click on a closed method's signature line puts Neovim's cursor on that
  line, inside the closed fold. Neovim treats that as standing on the fold, so `j` goes past it and
  `zo` opens it with the cursor on the signature.
- **Visual mode** over a closed fold: Neovim's selection includes the whole fold, and so does
  Eclipse's once its ends are converted. An end of Neovim's visual selection inside a closed fold is
  put at the caption line's start, as the caret is, so selecting into a fold does not reveal it.
  (`VisualSelectionView` and `BlockOverlay` over closed folds: section 7.)

### 3.4 Edits and insert mode

- **Edits from Neovim** (`dd` on a closed fold deletes all its lines) reach the document as one
  edit. Eclipse drops or shrinks the annotations, and the next reconcile resends the regions.
- **Typing in insert mode** happens in Eclipse, where annotation positions follow the text. On
  `Esc` the text reaches Neovim through `nvim_buf_set_lines` (only the lines that differ,
  `LuaScripts.SET_LINES_MARKED`), which moves Neovim's folds the same way (X1). The reconcile after typing resends
  the regions anyway.
- **Fold sends wait while Eclipse types.** Typing a new method in insert mode (`{`, Enter) makes
  JDT's reconcile add a fold. Neovim does not have the typed lines until `Esc`, so a fold sent then
  would land on the wrong lines. `FoldMirror` holds sends while `NeovimSession.isTypingMode()`
  (`hold`) and sends at the end of insert mode, after the push (`resume`). Test:
  `JavaEditorEndToEndTest.aMethodTypedInInsertModeStaysAndBecomesAFold`.
- **Undo** (X7): `u` restores the lines, not the fold; the reconcile brings it back in both, open.
  This is Neovim's own behavior for manual folds, and users will notice it, so the user guide mentions it.
  Closing it again after the undo would need the plug-in to remember deleted closed folds and match
  them to restored lines; not worth the state.
- **The extmark ids** become stale only when the list is resent, and resending replaces them all.

### 3.5 The user's own folding

- **The user's `'foldmethod'` is replaced** by `manual`, window-local, only while a mirrored buffer
  is shown. An editor that cannot fold gets `nofoldenable`. With the opt-out, nothing is touched.
- **`z` alone is mapped**, buffer-local, in mirrored buffers, not `zf`, `zd`, `zE` (section 6 says
  why). The mapping reads the next key with `getcharstr()`, which has no time limit. `zf`, `zF`,
  `zd`, `zD` and `zE` (and `zf`, `zd`, `zD` in visual mode) show the error with
  `nvim_echo(..., { err = true })`, which reaches the message line through `MessageRoute`. Any other
  key goes on to the user's mapping of `z` and that key (`maparg`), if there is one, or else `z`
  and the key are fed to Neovim, with the count. A user mapping of `z` plus more than one key is
  lost (README, Known limitations). Neovim shows no pending `z` while a mapping reads a key (no
  `msg_showcmd`), so the mapping sends `eclipse_showcmd` itself (`NotificationBridge.onShowcmd`),
  and the status bar shows `z` (or `3z`) until the next key.
- **`:fold`** (with its abbreviations and ranges): a built-in command cannot be mapped, but every
  `:` line already passes through `HOST_COMMANDS`' `CmdlineLeavePre`, which parses it with
  `nvim_parse_cmd`. The refusal is a hook, not a rule in the Java table of host commands: that table
  lists what Eclipse carries out, and `:fold` is not that; it is refused only in mirrored buffers,
  which only Lua knows. So `HOST_COMMANDS` offers
  `require('neovim4eclipse.host_commands').refuse(name, why)`, and the fold module registers `fold`
  there (so `FOLDS` runs after `HOST_COMMANDS`). In a buffer with `b:neovim4eclipse_folds` a
  refused line becomes `:Eclipse fold`, and the `Eclipse` user command shows the error itself
  instead of notifying Eclipse. Being an error, it ends the rest of a `|` chain, as any error does in
  Vim (`:10,12fold | let x = 1` does not set `x`). Elsewhere (an editor that cannot fold, or the
  opt-out) the hook does not apply and Neovim runs the command. Like the other host commands this
  works for typed lines, mappings' `:fold<CR>`, macros and `:normal`, not for `vim.cmd.fold()` from
  Lua. Catching that too (the snapshot spotting folds that are not Eclipse's and resending
  Eclipse's set) would let the stray fold flash first and cost code for a rare case.
- `:foldopen`, `:foldclose`, `:folddoopen` and `:folddoclosed` stay Neovim's: they open, close or
  use folds and need nothing special. `'foldclose'` should work too, but is not tested.

### 3.6 The opt-out, read once

- **When:** `LuaScripts.FOLDS` is one script for both the module and the opt-out. The session runs
  it once while it starts, right after `LuaScripts.SETUP` (so after `init.lua`). It reads
  `vim.g.neovim4eclipse_folds` and, when folding is on, installs the fold module; it returns whether
  folding is on. Session start already runs off the UI thread and already waits for Neovim, so this
  adds no wait anywhere else. The answer is kept in a final field, `FoldsPort.foldsEnabled()`
  (implemented by `NeovimSession`). `0` and `false` (`v:false`) mean off, as for
  `neovim4eclipse_ide_keys`; anything else, including not set, means on.
- **What off skips, all of it:**
  - the controller creates no `FoldMirror`, so no annotation-model or projection listener is added
    and nothing is sent at attach, on a reconcile or on a buffer switch;
  - `SET_FOLDS` is never sent, so no window option, fold, extmark, buffer variable or mapping is
    set, and editors that cannot fold do not get `nofoldenable` either;
  - the snapshot's fold flag is `"0"`, so Neovim reads no fold state after a key;
  - with no fold module, the `fold` refusal is never registered: `:fold` is Neovim's;
  - `StyledTextCaret` reveals every hidden offset, since there is no caption lookup.

  What remains is one flag test per key on each side.
- **Why not check the variable in Lua each time:** that kept a Neovim call per fold change and an
  Eclipse listener per editor even when the user had said no. Reading it once is cheaper and also
  makes the behavior stable for the life of the session. The cost (a change needs a Neovim restart)
  is the same as for `neovim4eclipse_ide_keys`, whose mappings are also made once at start.

## 4. Implementation, by layer

1. **`util/FoldRegions`** (pure, `tests/unit`):
   - from `(firstLine, lastLine, collapsed)` triples to the sorted, filtered list of section 3.1
     (`forNeovim`: filtering, merging, sorting; crossing regions dropped, X14);
   - from the snapshot's closed ids, the previous ones and the nesting to "collapse these, expand
     those", leaving folds inside a closed one alone (`stateChange`, 3.2).
2. **`core`**:
   - `LuaScripts.FOLDS`: the opt-out and the module `require('neovim4eclipse.folds')`, with `set`
     (3.1, including the handle lines and the `zn` states), `closed`, `watch` and `poll` (3.2), the
     `z` mapping and the `fold` refusal (3.5);
   - `LuaScripts.SET_FOLDS`, which calls `set`; the snapshot's second argument, element 12 and
     `StateSnapshot.FOLDS`;
   - the `refuse` hook in `LuaScripts.HOST_COMMANDS`;
   - the port `core/FoldsPort` (`foldsEnabled`, `setFoldsAsync`, `add/removeFoldStateListener`),
     as `DiagnosticsPort` is for problems, implemented by `NeovimSession`; the event
     `core/FoldState`, published through `SessionEvents` by `StateSync`.

   Delete `plugins/.../target/classes` after changing a script (AGENTS.md, Pitfalls).
3. **`editor/FoldMirror`** (SWT and JFace only, `tests/swt`):
   - holds the viewer's `ProjectionAnnotationModel`, the list last sent, and the id-to-annotation
     map;
   - listens to the annotation model and to `IProjectionListener` (folding turned on or off);
   - applies Neovim's state with the no-echo flag;
   - holds sends while Eclipse types (`hold`, `resume`, 3.4);
   - answers "caption offset of the closed fold at this document offset, or -1"
     (`captionOffset`) for the caret code.

   It has no session types: it gets a `Consumer` that sends the folds, and the controller filters
   `FoldState` by buffer, so `tests/swt` compiles it with only `core/UiExecutor`. The
   `ProjectionViewer` comes from `viewerOf(editor)`, as `WidgetOffsets` gets its viewer.
4. **`NeovimEditorController`**: when `foldsEnabled()`, creates the mirror at attach, sends the
   folds after the push (`sendFolds`) and disposes the mirror at detach. It routes the fold-state
   listener to the mirror (`onFolds`, inside `CaretSync.runAsFromNeovim`), then the caret, and hands
   the caption lookup to `StyledTextCaret`. When folding is off, none of this is created.

## 5. Tests

- **`tests/unit`**:
  - `FoldRegionsTest`: sorting (same start line: longer first), nesting, ranges under two lines,
    duplicates, crossing regions (X14), the state diff, and "unknown" inner folds under a closed
    one;
  - `StateSnapshotTest`: the fold element, and a short list giving no closed folds.
- **Lua, `tests/unit/src/test/lua/folds_spec.lua`** (headless Neovim, no Java session):
  - `set` makes the right levels for nested folds and folds sharing a first line (X3), closes
    exactly the collapsed ones, inner and outer, on their handle lines (an open inner fold at the
    start, at the end, covering everything), and keeps the cursor and view;
  - an empty list sets `nofoldenable` and clears marks, variable and mapping;
  - a global `foldmethod=indent` is replaced, window-local;
  - the old folds are deleted without leaving insert mode (`:noautocmd normal! zE`);
  - `watch` calls back, and fires `User Neovim4EclipseFolds`, only when the closed list changes
    (two keys that change nothing: no call);
  - `zn`, a resend, then `zi`: the same folds closed as before (3.2);
  - the snapshot's element: outermost closed ids only; empty with `zn`; the same ids back with `zi`;
    the right id when two folds start on one line;
  - `zf` and `zE` in a mirrored buffer show the error; `:fold` and `:3,5fo` are refused there and
    run elsewhere, and a refusal ends a `|` chain.
- **`tests/unit -Pintegration`, `NeovimFoldsIntegrationTest`**: real Neovim through the session,
  keys sent with `nvim_input` (only what needs the session or `nvim_input`'s main-loop behavior):
  - `zc`, `zo`, `za`, `zM`, `zR` change the snapshot's closed list;
  - `j` steps over a closed fold; `/` into one opens it; `foldopen=all` makes `j` open it (X5);
  - edits above a fold move it; the folds survive a buffer switch;
  - a slowly typed `zE` is refused (`aSlowZEIsRefusedToo`), and a pending `z` shows in the status
    bar (`aPendingZIsShownInTheStatusBar`);
  - the opt-out: `foldsEnabled()` is false for `0`, `false` and `v:false` and true when unset; with
    it off, a buffer with a global `foldmethod=indent` keeps the user's folds, and the snapshot's
    fold element is empty.
- **`tests/swt`**: `FoldMirrorTest` on a `ProjectionViewer` as in `WidgetOffsetsTest`: the regions
  sent, a collapse from Eclipse's side sent again and Neovim's state not echoed, folds inside a
  closed one keeping Eclipse's state, the caption lookup (the outermost collapsed fold gives the
  caption), projection turned off and on, and the caret going to the caption without unfolding.
- **UI test, `-Pui`, `FoldingEndToEndTest`**, with a test editor that folds without Java tooling:
  `TestFoldingEditor` in the test fragment, for `*.fold` files, whose brace blocks are its folds.
  It covers `zc` (and `j` stepping over the fold), `zo`, a fold collapsed in Eclipse (through
  `org.eclipse.ui.edit.text.folding.collapse`) closing in Neovim, `zM` then `zo` showing the
  methods as they were, `Ctrl-E` stepping the top line over a closed fold, `zf` showing the error
  and making no fold, and an editor that cannot fold turning Neovim's folding off. There is no UI
  test of the opt-out: the UI tests share one Neovim, started without it;
  `NeovimFoldsIntegrationTest` covers it.
- **`-PuiHeavyJava`, `JavaEditorEndToEndTest`:** `zMAndZRFoldAndUnfoldEverything`,
  `zcOnAMethodWithJavadocPutsTheCaretOnItsSignature`,
  `zcInTheBodyOfAMethodWithJavadocAndZoReopensIt`, `aMethodTypedInInsertModeStaysAndBecomesAFold`,
  `oAboveAFoldedHeaderCommentTypesOnTheNewLine`; and for `'foldopen'`,
  `movingIntoFoldedImportsLeavesThemFoldedWithTheCaretOnTheirCaption` (`4G` into closed imports;
  `jump` is not in the default) and `withFoldopenAllMovingIntoFoldedImportsUnfoldsThem`.

All waits use `TestWait`; "nothing more arrived" uses `NeovimBarrier.pass`. Nothing here needs
`timeoutReliant`.

## 6. Bugs found by hand, and their fixes

Each of these was found by trying the feature; docs/ARCHITECTURE.md, "Folding: the workarounds,
and why", lists them all with the code and the test that holds each.

- **`z` alone is mapped, not `zf`, `zd`, `zE`.** Neovim waits only `'timeoutlen'` (1000 ms by
  default, or the user's) for a mapping's second key; typed more slowly, `zE` was Neovim's own and
  deleted every fold, after which `zc` said `E490: No fold found`. The mapping of `z` (3.5) has no
  time limit. Test: `NeovimFoldsIntegrationTest.aSlowZEIsRefusedToo`, which failed with the two-key
  mappings.
- **Old folds are deleted with `:noautocmd normal! zE`.** Folds can be sent while Neovim is in
  insert mode, and a plain `:normal! zE` passes through normal mode and fires `ModeChanged`: the
  plug-in took insert mode as ended and a typed method went missing. Switching to
  `foldmethod=expr` and back, to avoid `:normal`, does not work in insert mode: Neovim does not
  recompute expression folds there. This, with the held sends of 3.4, is tested by
  `JavaEditorEndToEndTest.aMethodTypedInInsertModeStaysAndBecomesAFold`, which failed before the
  fix.
- **Collapsing moves the caret.** `zc` in a method's body collapsed it in Eclipse, whose widget moved
  its caret below the fold, and that move reached Neovim as the user's. Neovim's fold changes are
  applied inside `CaretSync.runAsFromNeovim`
  (`JavaEditorEndToEndTest.zcInTheBodyOfAMethodWithJavadocAndZoReopensIt`).
- **Folds before the cursor** (3.2): the session reports folds before the cursor, so a caret is not
  placed in a fold Eclipse still shows collapsed.
- **An Eclipse caret glitch, left alone:** now and then, right after a fold opens or closes, the
  caret is not drawn until it moves (normal mode, Linux, XFCE on X11). It happens the same with
  `Ctrl+Numpad` in Eclipse mode and in an Eclipse without the plug-in, so it is SWT's or the
  platform's (likely the caret's blink state across the viewer's redraw). Hiding and showing the
  caret after each fold change was rejected: it would only cover Neovim mode. The user guide notes it ("Folding").

## 7. Not checked

- **The Eclipse side against the current target platform:** the projection API and JDT's caption
  positions (section 2 was checked on the versions at the top); and whether JDT makes a region
  recreated after an undo expanded (X7).
- **`VisualSelectionView` and `BlockOverlay`** over closed folds (3.3).
- **The redraw trigger, X12** (3.2): its cost per redraw, and whether it sees a fold closed by a
  `vim.defer_fn` timer.
- **Custom folding regions** (section 1): which Eclipse version added them to the Java editor, and
  that they arrive as ordinary `ProjectionAnnotation`s (expected; a `-PuiHeavyJava` check).
- **Speed** of a resend, which recreates every fold (X8: 0.4 ms for 200), for a large Java file
  (hundreds of members), against the session's command timeout.
- **The Generic Editor's language-server folds** may arrive late or change often; whether the
  coalescing is enough.

**Not done:** folds the user creates (`zf` making a `ProjectionAnnotation`): the reconciler would
have to keep them, so `zf` is refused instead (section 1).
