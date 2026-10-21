# Design note: folding with `zo`, `zc` and the other `z` commands

Status: **planned**, not implemented. Planned against `develop` at `3e0c21f`. The experiments used
Neovim 0.12.4 on Windows 11, and the local Eclipse install (`org.eclipse.jface.text` 3.31.100,
`org.eclipse.jdt.ui` 3.39.0). Decisions made with the maintainer are marked *(decided)*. Once the
feature is built, add a section *As built* after the summary saying where the code differs from this
plan and why, as [TAB_COMPLETION.md](TAB_COMPLETION.md) does.

## Summary

Eclipse folds code in editors built on a `ProjectionViewer`. The Java editor folds imports,
comments, methods and inner types; the Generic Editor folds what its language server reports.
Neovim knows nothing about these folds today.

- **The `z` commands do nothing to them.** `zc` reaches Neovim, which has no folds, and answers
  "E490: No fold found".
- **Moving into folded code unfolds it.** `editor/WidgetOffsets.toWidgetRevealing` expands the
  region, because Neovim's cursor has no other way to stand there. So a method collapsed with the
  mouse opens again as soon as `j` reaches it.
- **A user's `'foldmethod'` already applies, unseen.** An `init.lua` with `set foldmethod=indent`
  makes Neovim close folds in the plug-in's buffers that Eclipse does not show. `j` then jumps over
  lines that are on screen (seen in the experiment for section 2).

**Plan:** mirror Eclipse's folds into Neovim *(decided)*.

- **Eclipse decides where the folds are:** its regions become manual folds in Neovim's window.
- **Open and closed state syncs both ways.** A fold that Neovim opens or closes is expanded or
  collapsed in Eclipse. A fold that Eclipse opens or closes (the ruler, `Ctrl`+`Numpad` keys, Open
  Declaration) is opened or closed in Neovim.
- **Neovim then does the Vim part itself:** `zo` `zO` `zc` `zC` `za` `zA` `zv` `zx` `zX` `zm` `zM`
  `zr` `zR` `zn` `zN` `zi` `zj` `zk` `[z` `]z`, with counts. `j` and `k` step over a closed fold,
  and the user's `'foldopen'` decides which commands open one (section 2).

The plug-in reimplements none of these commands. It only copies regions one way and the
open/closed state both ways.

Size: about 450 lines of code and 550 of tests, in three commits (section 7).

## 1. Behavior

- **Editors that can fold** (a `ProjectionViewer` with projection on: the Java editor, the Generic
  Editor) have their fold regions mirrored. Each `z` command acts on Eclipse's folds as Vim's acts
  on its own, and the two always show the same folds open and closed.
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
  `init.lua` has run, and when it is off none of the fold code runs: Eclipse sets up no mirror and
  listens to nothing, sends no fold list, and the per-key snapshot reads no fold state (section 3.6).
  The cost is that changing `g:neovim4eclipse_folds` in a running Neovim does nothing until Neovim is
  restarted (Window › Preferences › Neovim, or restarting Eclipse). README says so.

## 2. Background, with the experiments

**Eclipse.** A `ProjectionViewer` hides collapsed regions from its widget; `WidgetOffsets` already
converts between widget and document offsets. Each region is a `ProjectionAnnotation` in the
viewer's `ProjectionAnnotationModel`, at a `Position` that covers whole lines. Several things change
that model:

- the editor's folding structure provider, after each reconcile;
- the user, through the folding ruler and the Expand/Collapse commands
  (`org.eclipse.ui.edit.text.folding.*`);
- `exposeModelRange`, when Eclipse reveals a hidden offset.

`IAnnotationModelListener` (or `IAnnotationModelListenerExtension`, whose events list what was added,
removed and changed) hears all of them. Collapsing or expanding counts as a change.

**The caption line.** A plain `Position` shows its first line when collapsed. A position that
implements `IProjectionPosition` decides itself which line stays visible:
`computeCaptionOffset(document)` gives it. JDT's fold positions do this
(`DefaultJavaFoldingStructureProvider$JavaElementPosition` and `$CommentPosition`, checked with
`javap`). A method's region starts at its Javadoc, but the collapsed method shows the signature
line, hiding lines both above and below it.

**Neovim.** Manual folds are made with `:{first},{last}fold`, opened and closed with
`:foldopen`/`:foldclose`, and read with `foldclosed()`/`foldclosedend()`. Checked with Neovim 0.12.4:

- **Edits through `nvim_buf_set_lines`** (the plug-in's way) move the folds. Two lines inserted above
  a fold at 3–5 moved it to 5–7. One line inside it replaced by two made it end at 8.
- **Switching buffers** in the window (`nvim_win_set_buf`, `:b`) and back kept the fold and its
  closed state.
- **A user's global `foldmethod=indent`** applies to a buffer the plug-in creates. With a 4-space
  `'shiftwidth'`, which `util/Indentation` sets, the body of `class A {…}` was a closed fold at
  level 1.
- **Reading the state is cheap.** With 200 folds in a 5000-line buffer, finding the closed ones
  (extmark positions plus `foldclosed`) took 0.04 ms.

**`'foldopen'`.** This option lists the kinds of commands that open a closed fold when they move
the cursor into it. Its default is `block,hor,mark,percent,quickfix,search,tag,undo`. Results with
lines 3–5 closed, the cursor on line 2, and keys sent with `nvim_input`, as the plug-in sends them:

| Keys | Default `'foldopen'` | `foldopen=all` |
|---|---|---|
| `j` (onto the fold) | the cursor stands on the fold (line 3), which stays closed | opens |
| `jj` (one batch) | line 6, closed | line 6, closed: only where the batch ends counts |
| `k` from line 6 | line 3, closed | (not tried) |
| `3G`, `G` | stays closed (`jump` is not in the default) | `3G` opens |
| `/d` (match inside) | opens (`search`) | opens |
| `dd` on line 2 | stays closed | opens (the cursor lands on it) |

The `all` check runs in Neovim's main loop when no keys are pending, so `feedkeys(…, 'x')`, which
skips the loop, never shows it. Tests must send keys the way the plug-in does. A key in a mapping
does not use `'foldopen'` (`:help 'foldopen'`). For the plug-in's own mappings that does not
matter: `gd` ends in Eclipse, which reveals the target, and that reaches Neovim as a state change
(section 3.2).

## 3. Design

### 3.1 Eclipse to Neovim: regions and state

- **What is sent:** every annotation's line range `[first, last]`, 1-based, plus its collapsed
  flag. Ranges of fewer than two lines and duplicate ranges are dropped. The list is sorted by
  first line, then by length with the longest first, so that outer folds come before the folds
  inside them.
- **When:**
  - at attach;
  - when the editor becomes the shown buffer again;
  - after any change to the annotation model that did not come from Neovim (next section).

  Changes are coalesced: one send per UI-thread turn (`asyncExec`), since a reconcile changes
  many annotations at once.
- **How:** a new `LuaScripts.SET_FOLDS`, queued through a session `*Async` method. It must not
  block the UI thread, and it must keep its order among the keys already queued. For the current
  buffer only (argument: the buffer number; skipped if another buffer is current, which then gets
  its folds when shown). It is only ever sent when folding is on (section 3.6), so it does not
  check the opt-out itself:
  1. Window-local `foldmethod=manual`, `foldenable`, `foldminlines=0` (Eclipse folds two-line
     regions, which Neovim's default `1` would show open), then `zE`.
  2. Create each fold with `:{first},{last}fold`, outer first; then set each one's state.
  3. Put one extmark per fold, in the namespace `neovim4eclipse_folds`, at the fold's first line. Its id
     is the fold's index in the list Eclipse sent, so Neovim can name a fold to Eclipse and both
     stay valid while edits move lines.
  4. Keep the cursor and view (`winsaveview`/`winrestview`).
  5. Set the buffer-local `zf`/`zd`/`zE` mappings described in section 3.5.

  An empty list means the editor cannot fold. The script then sets window-local `nofoldenable`
  and clears the marks.

### 3.2 Neovim to Eclipse: state

- **Read after every key:** `STATE_SNAPSHOT` gets a 16th element (added at the end, as
  `StateSnapshot` requires). It holds the ids of the closed folds: the marks whose line
  `foldclosed()` reports as the start of a closed fold. It is empty when there are no marks or
  `'foldenable'` is off, so `zn` and `zi` open everything in Eclipse too. The script computes it
  only when a third argument is `"1"`, like its two existing flags (the statusline and `showcmd`;
  messages are always read); with folding off it is `"0"`, and the element is an empty list that nothing reads.
- **Applied on the UI thread:** compare with the last list; collapse or expand only the
  annotations whose state differs. This must happen *before* the cursor is applied, so that the
  caret lands on visible text.
- **No echo:** these changes are made with a flag set, like `CaretSync.runAsFromNeovim`, so that
  the annotation listener does not send them back. A change Eclipse makes for another reason, such
  as revealing Open Declaration's target, still goes to Neovim.

### 3.3 The cursor in a closed fold

- **Neovim to Eclipse:** if the cursor's line is in a closed fold that Eclipse also shows closed,
  the caret goes to the start of that fold's caption line, without revealing. The caption line
  comes from `IProjectionPosition.computeCaptionOffset`, or is the first line for a plain
  `Position`. `CaretSync`/`EditorCaret` get that choice before the `moveCaret` that reveals. Today
  every hidden offset is revealed; afterwards, only offsets hidden by a fold Neovim has open.
  Neovim opens a fold before its cursor stands inside it in an open state, so that case means the
  two disagree for a moment. Reveal, as today.
- **Eclipse to Neovim:** a click on a closed method's signature line puts Neovim's cursor on that
  line, inside the closed fold. Neovim treats that as standing on the fold, so `j` goes past it and
  `zo` opens it with the cursor on the signature.
- **Visual mode** over a closed fold: Neovim's selection includes the whole fold, and so does
  Eclipse's once its ends are converted. Selecting into a fold must not reveal it; check this with
  `VisualSelectionView`.

### 3.4 Edits and insert mode

- **Edits from Neovim** (`dd` on a closed fold deletes all its lines) reach the document as one
  edit. Eclipse drops or shrinks the annotations, and the next reconcile resends the regions.
- **Typing in insert mode** happens in Eclipse, where annotation positions follow the text. On
  `Esc` the text reaches Neovim through `nvim_buf_set_lines`, which moves Neovim's folds the same
  way (section 2). The reconcile after typing resends the regions anyway.
- **The extmark ids** become stale only when the list is resent, and resending replaces them all.

### 3.5 The user's own folding

- **The user's `'foldmethod'` is replaced** by `manual`, window-local, only while a mirrored buffer
  is shown *(decided)*. An editor that cannot fold gets `nofoldenable`. With the opt-out, nothing is
  touched.
- **`zf`, `zF`, `zd`, `zD`, `zE`** (normal mode, plus `zf`, `zd` and `zD` in visual mode) get
  buffer-local mappings in mirrored buffers that set `v:errmsg`, which the status bar already shows
  through the snapshot. `:fold` and `:folddelete` (with their abbreviations and ranges) are added to
  `util/HostExCommand` as host commands that show the same error, since a built-in command cannot be
  mapped. `:foldopen`, `:foldclose`, `:folddoopen` and `:folddoclosed` stay Neovim's: they open,
  close or use folds and need nothing special. With the opt-out, `:fold` and `:folddelete` are
  passed to Neovim as before.

### 3.6 The opt-out, read once

- **When:** the session reads `vim.g.neovim4eclipse_folds` once, while it starts, right after
  `LuaScripts.SETUP` (so after `init.lua`), in the same request or the one after it. Session start
  already runs off the UI thread and already waits for Neovim, so this adds no wait anywhere else.
  The value is kept in a final field, `NeovimSession.foldsEnabled()`. `0` and `false` (`v:false`)
  mean off, as for `neovim4eclipse_ide_keys`; anything else, including not set, means on.
- **What off skips, all of it:**
  - the controller creates no `FoldMirror`, so no annotation-model or projection listener is added
    and nothing is sent at attach, on a reconcile or on a buffer switch;
  - `SET_FOLDS` is never sent, so no window option, fold, extmark or mapping is set, and editors
    that cannot fold do not get `nofoldenable` either;
  - the snapshot's fold flag is `"0"`, so Neovim reads no fold state after a key;
  - `HostExCommand` treats `:fold` and `:folddelete` as Neovim commands;
  - `CaretSync` keeps today's path (reveal every hidden offset), since there is no caption lookup.

  What remains is one flag test per key on each side, which is the same work the two existing
  snapshot flags do.
- **Why not check the variable in Lua each time:** that kept a Neovim call per fold change and an
  Eclipse listener per editor even when the user had said no. Reading it once is cheaper and also
  makes the behavior stable for the life of the session. The cost (a change needs a Neovim restart)
  is the same as for `neovim4eclipse_ide_keys`, whose mappings are also made once at start.

## 4. Implementation, by layer

1. **`util/FoldRegions`** (pure, `tests/unit`):
   - from `(firstLine, lastLine, collapsed)` triples to the sorted, filtered list of section 3.1;
   - from the snapshot's closed ids and the previous ones to "collapse these, expand those".
2. **`core`**:
   - `LuaScripts.SET_FOLDS`, plus the snapshot's third flag, new element and `StateSnapshot` index;
   - `NeovimSession.foldsEnabled()`, read once at start (section 3.6), and the snapshot flag set
     from it;
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
4. **`NeovimEditorController`**: when `session.foldsEnabled()`, creates the mirror at attach and
   disposes it at detach. It sends the regions at attach and when the buffer is shown again (the
   `eclipse_buf_enter` path). It routes the state listener to the mirror, then the caret. It hands
   the caption lookup to `CaretSync`. When folding is off, none of this is created and `CaretSync`
   has no caption lookup.
5. **`util/HostExCommand`** and `HostCommandRunner`: `:fold`/`:folddelete` give the error message,
   only when folding is on (a flag passed in, so the class stays pure).

## 5. Tests

- **`tests/unit`**:
  - `FoldRegionsTest`: sorting, nesting, ranges under two lines, duplicates, and the state diff;
  - `StateSnapshotTest`: the new element, and a short list giving no closed folds;
  - `HostExCommandTest`: `:fold`, `:3,5fo`, `:foldd`; `:foldopen` is not a host command; with
    folding off, `:fold` is not one either.
- **`tests/unit -Pintegration`**, real Neovim (`NeovimSessionIntegrationTest` or a new
  `FoldingIntegrationTest`), keys sent with `nvim_input`:
  - `zc`, `zo`, `za`, `zM`, `zR`, `zn` and `zi` change the snapshot's closed list;
  - `j` steps over a closed fold; `/` into one opens it; `foldopen=all` makes `j` open it;
  - nested folds and `zO`/`zC`;
  - edits above a fold move it;
  - the folds survive a buffer switch;
  - a global `foldmethod=indent` is replaced; an empty list sets `nofoldenable`;
  - `zf` sets the error;
  - the opt-out: `foldsEnabled()` is false for `0`, `false` and `v:false` and true when unset;
    with it off, a session started on a buffer with a global `foldmethod=indent` keeps the
    user's folds (nothing was replaced), and the snapshot's fold element is empty.
- **`tests/swt`**, a `ProjectionViewer` as in `WidgetOffsetsTest`:
  - `FoldMirror` reports regions, including a custom `IProjectionPosition`'s caption;
  - a collapse from the ruler side calls the sender, and applying Neovim's state does not;
  - the caption lookup.
- **UI test, `-Pui`:** an editor with folding is needed. Either a small test editor registered
  for a test extension, like `TestMultiPageEditor`, with `ProjectionSupport` and a fixed folding
  structure, or the Generic Editor if it folds a plain file in the test runtime (section 8).
  - `zc`, then `j` steps over the fold and the caret is on the line after it;
  - `zo` shows the lines again;
  - a collapse through `org.eclipse.ui.edit.text.folding.collapse` makes `j` step over the fold;
  - `zf` shows the error in the status line;
  - with `vim.g.neovim4eclipse_folds = 0` (a test `init.lua`, or `--cmd`), the controller has no
    mirror, and moving into collapsed code unfolds it as today.
- **`-PuiHeavyJava`, `JavaEditorEndToEndTest`:**
  - `zM` collapses the methods;
  - on a method with Javadoc, the caret sits on the signature line;
  - `zo` opens it;
  - a method added in insert mode becomes a fold after the reconcile.

  The existing `movingIntoFoldedImportsUnfoldsThem` changes: `4G` into closed imports leaves them
  closed by default (`jump` is not in `'foldopen'`) and opens them with `foldopen=all`.

## 6. Docs

- **README:** a row in the feature table, and a short folding section with `'foldopen'` and the
  opt-out.
- **`docs/KEYS.md`:** the `z` keys go to Neovim. List what is not supported, and say where
  folding is off.
- **`docs/ARCHITECTURE.md`:** replace the *Folding* bullet (folds are mirrored, the caption
  line), and add rows to the "Where logic lives" table.
- **`CHANGELOG.md`, *Unreleased*:** *Added*: folding with the `z` commands. *Changed*: moving into
  folded code no longer opens it unless `'foldopen'` says so, and a user's `'foldmethod'` no longer
  hides lines in Eclipse's editors.
- **This note:** status set to implemented, and an *As built* section added.

## 7. Commits

Three commits, in UTC, as AGENTS.md asks:

1. **The Neovim side:** `FoldRegions`, `SET_FOLDS`, the snapshot element, and the session method
   and event. Unit and integration tests. No change users can see yet.
2. **The Eclipse side:** `FoldMirror`, the controller wiring, the caret on the caption line, and
   `nofoldenable` for editors that cannot fold. SWT, UI and heavy Java tests, and the docs and
   CHANGELOG of section 6.
3. **`zf`/`zd`/`zE` and `:fold`/`:folddelete` errors,** with their tests and a line in KEYS.md.

## 8. To check while implementing

- **Which editors fold** in the test runtime and in a normal install: whether the plain Text Editor
  has projection in this Eclipse version, and whether the Generic Editor folds a `.txt` file
  without a language server. This decides the `-Pui` test editor.
- **Setting states on nested folds:** `:fold` creates a closed fold, and the order matters when an
  inner fold must stay closed inside an open outer one. Also check `foldclosed(l) == l` when an
  outer and an inner fold start on the same line: then only one can be reported as closed. Try it
  with JDT's own structure: a type and its first member, a Javadoc and its method.
- **`zn`/`zi` and a resend:** while `'nofoldenable'` is set, a resend from Eclipse (everything
  expanded) must not erase the closed states Neovim keeps for `zi`.
- **`v:errmsg` from a mapping** reaches the status line the way other errors do (the snapshot
  clears it). Also check on Neovim 0.9, the oldest supported version.
- **Undo** (`u` is in the default `'foldopen'`): a fold deleted with `dd` and restored with `u`
  comes back in Eclipse after the reconcile, not at once.
- **Speed:** a resend recreates every fold. Measure it for a large Java file (hundreds of
  members) with the session's command timeout in mind.
- **The Generic Editor's language-server folds** may arrive late or change often. Check that the
  coalescing is enough.

**Later, not in this plan:** folds the user creates (`zf` making a `ProjectionAnnotation`; the
reconciler would have to keep them), and `'foldclose'`, which should work already but will not be
tested.
