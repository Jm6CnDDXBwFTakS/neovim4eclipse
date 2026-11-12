# Design note: Vim's Ctrl keys in insert mode

In insert mode Eclipse types, not Neovim, so that content assist, auto-indent, templates, linked
rename and IME keep working (docs/ARCHITECTURE.md). Neovim learns the typed text only at `Esc`,
when the plug-in replays it (`util/InsertReplay`) and pushes the document. Without more, every
`Ctrl` key would stay Eclipse's in insert mode: `Ctrl+W` would close the editor, and in Java
`Ctrl+O` would open Quick Outline, instead of Vim's *delete word* and *one normal command*.

So each letter has a separate choice for insert mode (section 1). A letter claimed there becomes a
*sync point*: the plug-in hands Eclipse's typed text to Neovim's still-open insert, then sends the
key, and Neovim does the work (section 2). The re-typing does not trigger the user's insert
mappings (section 3), and only the changed lines are pushed (section 4). Keys after which Neovim
waits for another key make Neovim drive the insert until it has that key (section 5); a few keys
map to Eclipse commands instead (section 6).

## 1. A Normal and an Insert choice per letter

*Window → Preferences → Neovim → Ctrl Keys* has one row per letter with two boxes, each with Vim's
meaning beside it, and *Select All* / *Deselect All* per column:

```
Ctrl+W  [ ] window commands: splits  [x] delete word before the cursor
```

The 26 rows are one column: two halves side by side measured 950 px, wider than the Preferences
dialog's page area.

- **Normal** is the preference `ctrlKeyNormal.<letter>`, the one that existed before the Insert
  column, so an earlier choice keeps its meaning.
- **Insert** is `ctrlKeyInsert.<letter>`. Defaults on Windows and Linux: **W U R O T D**
  (delete word, delete to the insert's start, insert a register, one normal command, indent,
  unindent). A, C, V, X, Z, S, F stay Eclipse's (select all, copy, paste, cut, undo, save, find).
- **macOS**: every letter with a Vim insert meaning, since Eclipse's own shortcuts use Cmd there.
  That includes `Ctrl+A`, `Ctrl+E` and `Ctrl+K`, although macOS text fields use them for line
  start, line end and delete to line end: in the editor they are Vim's.

### Which letters are active

The Vim key contexts are on in every mode while the editor has the focus; only the set of letters
changes. In insert and replace mode the Insert letters are active, whether Eclipse types or Neovim
drives the insert; in any other mode the Normal letters (`util/KeyContextRule.column`). A mode
switch toggles only the letters whose two choices differ, in one `deferUpdates` batch
(`editor/VimKeyContexts.apply`): 0.3 ms, against 5 to 9 ms for 23 contexts toggled one by one.

An insert *Neovim drives* (after a block-visual `I`, `A` or `c`, or while recording a macro, Neovim
reads every key) uses the Insert letters too. Neovim is in insert mode there, so a key means its
insert meaning; with the Normal letters, `Ctrl+W` ticked for insert only would close the editor in a
block-visual `I`. The editor re-checks the contexts after each key, since a key can make the insert
Neovim-driven without a mode change.

### The contexts lag the mode

The contexts follow the mode a moment after the keys. A letter ticked for Normal only, typed right
after `i`, can still come through the normal-mode binding; as a sync point it would do Vim's delete
word (`i` then `Ctrl+W` fast), though `Ctrl+W` is Eclipse's in insert mode. `KeyRouter.routeBound`
hands such a key to Eclipse: the editor switches the contexts at once and runs the command Eclipse
binds to the key (`IBindingService.getPerfectMatch`, `IHandlerService.executeCommand`). Tests:
`KeyRouterTest.aBoundKeyEclipseOwnsInInsertMode…`, UI
`aNormalModeBindingStillOnInInsertModeRunsEclipsesCommand`.

## 2. The sync point

A claimed `Ctrl` key in an insert Eclipse types:

1. **Re-type** the text Eclipse typed since the insert began (or since the last sync point) into
   Neovim's still-open insert, as `Esc` does, but without the `Esc` (section 3: no user mapping or
   abbreviation fires on text that was already typed).
2. **Push the changed lines** to Neovim (section 4).
3. **Put Neovim's cursor** at Eclipse's caret.
4. **Send the key.** Neovim does the work: `Ctrl-W` deletes the word exactly as Vim does, `.` later
   repeats the whole insert, `Ctrl-U` still knows where the insert began, one `u` undoes it.
5. **Resume.** Neovim's change comes back as a buffer event and is applied to the document;
   `InsertTracker` starts over from there (new base, new "before" text), and Eclipse types again once
   it has the text, through the same handshake as entering insert mode
   (`StateSync.isEclipseTypingReady`). For a long document the handshake is lighter (section 4).
   A key typed in that gap (about 1 ms) goes to Neovim, and Eclipse types the next one (section 5).

Steps 1 to 4 take about 1 ms.

**In the code.** `KeyRouter` (the key binding's `routeBound`, or the editor's key listener with the
Insert filter) asks `InsertTracker.consumeSyncPoint` for a `util/InsertReplay.SyncPoint` and pauses
Eclipse's typing on the UI thread (`StateSync.pauseEclipseTyping`); `KeyInput.syncInsertThenInput`
does steps 1 to 5 on the input thread. The cursor is published after the key's buffer events and
before the handshake's sync, so the caret lands on Neovim's cursor before Eclipse types again
(cursor reports are dropped while Eclipse types).

Tests: `KeyRouterTest`, `KeyInputTest`, `InsertReplayTest` (unit), and the UI tests
`EditorEndToEndTest.ctrlWWhileEclipseTypes…` (`.` repeats the whole insert), `ctrlUWhileEclipseTypes…`
(only this insert's text) and `ctrlRWhileEclipseTypes…` (Neovim drives until it has the register).

## 3. Re-typing without the user's insert mappings

Re-typed text would trigger the user's insert mappings and abbreviations if it were typed as keys
(`nvim_input`): with `inoremap jk <Esc>`, re-typed `jk` would end the insert half way. So the
re-typing, at a sync point and at `Esc`, is `nvim_feedkeys(text, "n", true)`, which does not remap.
It goes through `LuaScripts.TYPE_TEXT`, which types only while Neovim is in Insert or Replace mode,
so the text can never run as Normal-mode commands.

This is why the start-up script does not run `imapclear` and `iabclear`: the user's insert mappings
and abbreviations stay, and so do Neovim's own defaults (`Ctrl-W` and `Ctrl-U` start a new undo
step).

`InsertReplay` chunks raw text, not key notation: feedkeys does not read `<lt>`; a line break is
`\r`, a tab stays a tab.

Checked in `NeovimSessionIntegrationTest`: with `inoremap jk <Esc>` and `iabbrev teh the`, the
re-typed ` teh jk` stays as typed, `.` repeats it so, and `teh ` typed in Neovim itself still
expands; a user's `inoremap <C-u> <C-u>U` applies to a sync point's key.

## 4. Pushing only the changed lines

At `Esc` and at a sync point the document goes to Neovim. Reading Neovim's whole buffer to diff it
costs 0.3 ms at 1k lines, 3 ms at 10k and 19 to 22 ms at 100k, so long documents push only the
lines that changed.

`util/ChangedLines` keeps two numbers: the lines at the start and at the end of the document that
no change has touched since Neovim's buffer last equaled it. Counted from their own end, they stay
true whatever later edits do elsewhere, so each change only lowers them (`BufferSynchronizer`
records every change, Neovim's applied ones too: that only widens the range).
`BufferSynchronizer.pushChangedLinesToNeovim` reads that range and clears it in one step on the UI
thread, then `NeovimSession.setBufferRegion` has one script (`LuaScripts.SET_LINES_MARKED`) read the
same range of Neovim's buffer, counted from its end, and replace the lines that differ, nothing when
they are equal; a range that is not in the buffer is not written, and the whole document is pushed.

The whole document is pushed instead when the last line may have changed (its final line break may
have come or gone), before the first push, after a failed push, and when a Neovim change did not
reach the document (not listening, an error, a skipped full sync): then the lines could differ
anywhere.

**Documents up to `ChangedLines.COMPARE_WHOLE_UP_TO_LINES` (10,000) lines are compared whole**,
at about 3 ms: the whole comparison is also a safety net, since whatever differed, anywhere, is
repaired at the next `Esc`. A repair is no longer silent: when the comparison finds a line no
recorded change explains (`ChangedLines.Known.firstUnexplained`: one outside the two unchanged
counts), `sync/TextRepairs` logs it at `FINE`, which users do not see and the UI tests fail on.
Only longer documents push the changed lines. The same limit chooses the
handshake after a sync point's key (section 2, step 5): a full sync for a short document; for a long
one only a marker change (`BufferChange.marker`) that lets Eclipse type once the key's own line
events are applied, without reading Neovim's whole buffer per `Ctrl` key.

**The risk is a silent divergence**, and the fall-backs above are for it. One remains, in long
documents: a change Neovim makes by itself outside the typed lines while the typed text is re-typed
(an autocommand of the user's on `InsertCharPre`, say) is not seen by the push, and stays until a
whole-document push. Re-typing happens at the typed place, so such an autocommand would have to
edit far from the cursor.

## 5. Keys Neovim waits after, and the hand-back to Eclipse

After `Ctrl-R`, `Ctrl-K`, `Ctrl-V`, `Ctrl-Q`, `Ctrl-G` and `Ctrl-\` Neovim is blocked waiting for
the next key and answers only `nvim_get_mode` and `nvim_input` (an `exec_lua` barrier hangs). So
the insert is Neovim-driven from there, as after a block-visual `I` or while recording: every
following key goes to Neovim.

Once Neovim has the key it waited for and is in plain insert mode again (`Ctrl-R a` inserted
register `a`; `Ctrl-K e:` made `ë`), typing goes back to Eclipse with the insert-start handshake
(section 2, step 5): a full sync, and Eclipse types once the editor applied it, so content assist
works again. `.` still repeats the whole insert: Neovim's insert never ended. Only such an insert is
handed back; a block-visual `I` or a recording stays Neovim's until `Esc`.

**Keys in flight.** Keys typed meanwhile go to Neovim in order, and one queued after the sync was
read would land among Eclipse's typing. So `KeyInput` counts queued keys against taken ones: the
hand-back starts only when none waits, and the editor calls it off on applying the sync if one was
queued since (the next key retries). Tests: `KeyInputTest` (`onceNeovimHasTheKeyItWaitedFor…`, the
queue cases), UI `ctrlRWhileEclipseTypesInsertsARegisterThenEclipseTypesAgain`.

The insert-start handshake makes the same check. Keys can wait behind the key that enters insert
mode: `ihello<Esc>ibye` typed fast sends the second `i` and `bye` to Neovim, queued behind the
leave, and the mode change ends the Neovim-driven insert they were sent in (`ibye` typed fast in
Normal mode is the same: the keys are routed before Neovim reports the insert). If any key waits
when the editor applies the insert's sync, Eclipse does not type yet: the insert becomes
Neovim-driven, and the last waiting key hands it back (`KeyInput.noKeyWaitsAfter`;
`KeyInputTest.anInsertEnteredWhileKeysWait…`, `NeovimSessionIntegrationTest.anInsertEnteredWhileKeysWait…`).

The same hand-back covers a key typed in the millisecond before Eclipse has Neovim's text, at an
insert's start or after a sync point (`KeyRouter` → `driveInsertFromNeovim`): it goes to Neovim, and
Eclipse types the next one, rather than Neovim typing the rest of the insert
(`KeyInputTest.aKeyTypedBeforeEclipseMayType…`; block-visual and recording inserts are not handed
back, `anInsertFromBlockwiseVisual…`, `anInsertWhileRecording…`).

## 6. Ctrl-O, Ctrl-C, and the completion keys

- **`Ctrl-O`**: Neovim goes to `niI`, runs one normal command, and comes back to insert; the
  insert-start sync hands typing back to Eclipse.
- **`Ctrl-C`**: offered, off by default (copy). When ticked, `KeyRouter` takes it for `Esc`: the
  typed text is replayed, so `.` repeats it.
- **`Ctrl-N` / `Ctrl-P`**: Eclipse's *Word Completion* (Alt+/), and `Ctrl-X Ctrl-O` Eclipse's
  content assist, rather than Neovim's popup, which the plug-in does not draw. Off by default on
  Windows and Linux.
  - Word Completion cannot go backward (`HippieCompleteAction` keeps only a forward iterator), so
    `Ctrl-P` does what `Ctrl-N` does; its first offer is the nearest word before the caret, as
    Vim's `Ctrl-P`.
  - It offers the next word when pressed again only while no other command runs in between (its
    `CompoundEditExitStrategy` ends on any other command), and the plug-in's own `...commands.ctrl.n`
    would be one. So in an insert Eclipse types, `Ctrl+N` and `Ctrl+P` switch on contexts of their
    own (`...context.complete.n`, `.p`) whose bindings run Eclipse's
    `org.eclipse.ui.edit.text.hippieCompletion` directly (`KeyContextRule.contextId`). In an insert
    Neovim drives they stay Neovim's letter contexts: Eclipse does not type there.
  - `Ctrl-X` is a one-key prefix in `KeyRouter`: `Ctrl-O` then runs Content Assist, `Ctrl-N` /
    `Ctrl-P` Word Completion, any other key is handled as usual. A pending `Ctrl-X` ends at any
    command other than the plug-in's own key commands (`editor/OtherCommandWatch`): a key Eclipse's
    binding took never reaches the router, and `Ctrl-X Ctrl-N` goes straight to Word Completion.
  - Tests: `KeyRouterTest`, `KeyContextRuleTest`, `PluginXmlConsistencyTest`, UI
    `ctrlNWhileEclipseTypesRunsWordCompletion` (the order and the cycling),
    `ctrlCEndsAnInsertAsEscDoes`, and `ctrlXCtrlOWhileEclipseTypesRunsContentAssist` (the text
    editor has no proposals, so it checks that the command runs).

## 7. Measurements

| What | Cost |
|---|---|
| Sync point (re-type, changed line, cursor, key) | about 1 ms |
| Pushing the whole document instead | 3 ms at 10k lines, 22 ms at 100k |
| Switching 23 letter contexts | 5 to 9 ms one by one; 0.3 ms batched |

Modes after each key: `Ctrl-O` gives `niI`; `Ctrl-N` and `Ctrl-P` give `ic` (Neovim's popup, not
drawn); `Ctrl-X` gives `ix`; `Ctrl-C` and `Ctrl-@` leave insert. `Ctrl-R`, `K`, `V`, `Q`, `G`, `\`
leave Neovim blocking.
