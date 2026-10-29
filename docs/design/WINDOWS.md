# Design note: one Neovim window per Eclipse editor

Status: **built** for 0.1.10, on `feature/neovim-windows` re-cut from `release/0.1.10` at `c033057`:
phase 1, the core (`fa3ac6e`); phase 2, the Eclipse glue (`67479dc`, `2dd7b1c`, `a11d4a6`); phase 3,
this note and the docs; see *As built*. First written on 2026-10-25 on Neovim 0.12.4, revised on
2026-10-28 for 0.1.10 (Neovim 0.12.6, after the viewport, folding, diagnostics and Restart
Neovim). The decisions were confirmed with the maintainer on 2026-10-28. Until phase 3 the note was
kept outside the repository, as an artifact.

## Summary

Each Eclipse editor gets its own Neovim window, held alone in its own hidden tabpage. Editors then
keep their own cursor, column memory, alternate file, scroll position and window-local options
between switches, and folding and the location list get a per-editor home. `Ctrl-O` keeps working
across files as with one window.

**Before (0.1.9 and the viewport of 0.1.10):** one Neovim window for every editor. Each document
had its own buffer; switching editors called `DocumentBuffers.show` (the buffer marked attached
first, so the `BufEnter` of the switch is recognized as Eclipse's own) and pushed Eclipse's cursor.
Events were filtered by buffer, not window, so two editors on one file could not be told apart and
fought over one cursor. The one window was resized to the attached editor, took its word wrap and
top line on attach, and kept its own scroll position, wrap and folds only until the next editor
attached. Its folder (`:lcd`) was set to the editor's project root on every attach, so a `:lcd` the
user typed lasted only until the next switch.

## As built (differences from the plan)

Phase 1, the core (`fa3ac6e`):

- **A Lua module instead of new RPC methods.** The plan named five API calls for `NeovimApi`
  (`nvim_open_tabpage`, `nvim_set_current_win`, `nvim_win_set_buf`, `nvim_win_close`,
  `nvim_win_call`). They are called from `LuaScripts.WINDOWS` (the module
  `neovim4eclipse.windows`) instead, one script per operation (`OPEN_WINDOW`, `SHOW_WINDOW`,
  `TRAVEL_WINDOW`, `CLOSE_WINDOW`, `RELEASE_BUFFER`): each operation is one round trip, and the
  "quietly" rule (below) lives in one place. `RPC_CLIENT.md`'s table is unchanged.
- **The token and the bookkeeping.** `core/EditorWindow` is the editor's handle, as
  `DocumentBuffer` is the document's: the session fills in the window id at first show, and a
  restarted Neovim does not own it, so the window is opened again at the next attach (the lazy
  rebuild, with no code of its own). `core/EditorWindows` keeps which token holds which window id,
  and the travel swap; unit-tested without Neovim (`EditorWindowsTest`).
- **Quietly.** What the module does to a window that is not the current one runs with
  `'eventignore'` set to `all`, and with `F` added to `'shortmess'`: putting a buffer in a window
  gives Neovim's file info as `:b` does, and together with the message of the command that caused
  the travel it made two, which opens Eclipse's output popup (found by `FileInfoEndToEndTest`).
- **The safety nets.** `WinClosed` sends `eclipse_window_closed`, so a window closed in Neovim by
  itself is forgotten and its editor gets a new one at its next show (the plan named
  `TabClosedPre`; `WinClosed` covers it and any other close). A `WinNew` net (phase 2) closes any
  window the plug-in did not open that is neither floating nor special, and also the quickfix,
  location list and preview windows (Eclipse cannot show them, and in the editor's tab they took
  its rows, so `H` and `L` no longer matched the editor; found in review). The module's set of its
  own windows forgets a window on the plug-in's quiet closes too, which fire no `WinClosed`.
- **Window ids in the events.** `eclipse_cursor`, `eclipse_buf_enter`, `eclipse_topline`,
  `eclipse_wrap` and the state snapshot (field 14) carry the window id; the session takes cursor
  reports, top lines, wrap changes and the snapshot only from the attached window
  (`DocumentBuffers.isAttached(winid, bufnr)`). Buffer line events stay per buffer.
- **`'showtabline'` 0** is set once, after the user's config. Honoring a value the user set
  (`nvim_get_option_info2().was_set` tells) was discussed and left for later.
- **The home window** stays: it is the window Neovim starts in, shows the home buffer, and shows
  a document shown without a token (`NeovimSession.showBuffer`, which only tests use since phase 2).
  It is never closed by the plug-in, and opened again if the user closed it.

Phase 2, the Eclipse glue:

- **`67479dc`.** `EditorAttachManager` keeps an `EditorWindow` per editor part (the text editor or
  its multi-page host); the attach push shows it (`showWindow`). Closing an editor closes its window
  before its buffer may be wiped. `followNeovimInto` queues the travel (`travelWindowAsync`) before
  the attach; an editor jumped into that has no window yet simply takes the one that jumped
  (`EditorWindows.transfer`), and the editor it left gets a new one at its next show. The folder is
  set once when the window opens; an untitled document's window keeps the folder Neovim copies from
  the current window (the editor attached before), or gets the home folder when the current window
  is the home window. A folder that does not exist yet (a new file in a missing folder, written
  with `:w ++p`) is skipped: `:lcd` to it failed the open, and with it the attach
  (`NewFileEndToEndTest`).
- **`2dd7b1c`.** `:close` and `:tabclose` are host commands (`QUIT`). `LuaScripts.WINDOW_COMMANDS`
  refuses `:split`, `:vsplit`, `:new`, `:vnew`, `:sview`, `:sfind`, `:sbuffer`, `:tabnew`,
  `:tabedit`, `:tabfind` and `:tabonly`, and maps `Ctrl-W s`, `S`, `Ctrl-S`, `v`, `Ctrl-V`, `n`,
  `Ctrl-N`, `T` (refused) and `Ctrl-W q`, `Ctrl-Q`, `c` (`:Eclipse quit`), unless the user's config
  maps them. The `WinNew` net could not use the current window (in `WinNew` it is still the old
  one, so a first version closed the editor's window); the module keeps the set of windows it
  opened, and the net closes the others. `:help` is closed by it too, with the same message.
  After review: `:tabclose` with a number or count is refused (Neovim would close another
  editor's window); in a *side window* (floating, such as hover documentation the user entered;
  `is_side_window`), `:q`, `:close` and the `Ctrl-W q` / `c` keys are Neovim's again, through the
  host commands' new `leave_to_neovim` hook, and close that window, not the editor. The travel
  also moves the attached window to the attached editor's new one, so a failed attach of the
  editor jumped into returns to the right window.
- **`a11d4a6`.** The viewport item: Neovim's top lines are dropped while an attach places Neovim's
  cursor at the caret (`CaretSync.isPlacingNeovimCursor`), the same span in which its cursor
  reports are dropped. Whether the single window of 0.1.10 flickered the same way on a switch was
  not measured; a switch back now finds the window where it was, so the attach's top line usually
  changes nothing (`ViewportEndToEndTest.anEditorSwitchKeepsEachEditorsView`).
- **After the hand test plan, two follow-ups.** The attach skips the document push when neither
  side changed since the editor was detached: the detach push records the document's
  modification stamp in its `DocumentBuffer` (`markInSyncAt`), any line event Neovim sends for
  that buffer clears it, and `DocumentPush` compares it with the document's stamp at attach. An
  editor switch then reads neither the whole document (on the UI thread) nor the whole buffer
  (`nvim_buf_get_lines`). And `SET_WINDOW_FOLDER` and `setWindowFolderAsync`, the folder on every
  attach, were removed; `OPEN_WINDOW`'s `:lcd` is silent the same way.

## 1. Decisions

| Question | Decided | Why |
|---|---|---|
| Scope | A window per editor | Independent views, and a home per editor for folds, scroll position and window options. Mapping Vim splits onto Eclipse editors is a later note. |
| Layout | A tabpage per editor, `'showtabline'` 0 | One full window per tab, so the viewport's size and scroll sync work unchanged and `H`/`M`/`L` and `Ctrl-D` match the editor; window commands stay inside the editor's tab (E1). |
| `Ctrl-O` across files | The window travels | History stays continuous, as with one window. Per-editor lists bounce between editors and cannot be repaired (E2). |
| `:close`, `<C-w>q`, `:tabclose` | Close the editor, like `:q`: Eclipse asks about unsaved changes, `:close!` discards them | In Vim, closing the window closes the view; here the editor is the view. Closing only the window would leave the editor without one, and the safety net would reopen it at once. Vim's `:close` keeps a modified buffer hidden; Eclipse cannot keep an unsaved document without an editor, so asking is the safe equivalent, as for `:bd`. |
| `:split`, `:vsplit`, `:new`, `:tabnew`, `:tabonly` | Refused with a message naming Eclipse's split editor and *Window → Editor → Clone* | A second Neovim window would be invisible and take the keys; `:tabonly` would delete every other editor's window (E4). Mapping them onto Eclipse (Toggle Split Editor, Clone, an untitled editor) is a later note. |
| Two editors on one file | A window each, one shared buffer | Neovim's model, and Eclipse's agrees on every point: shared text, undo and marks; cursor, column memory, scroll position, folds, window options and jump history per editor. Rejected: one shared window (the editors fight over one cursor) and a buffer per editor (separate undo and marks). |
| What travels on `Ctrl-O` | The whole window | Location list and jump history included; the two editors swap windows (E2). |
| Untitled documents' folder | The folder of the editor attached before, else the home folder | See section 3. |

Confirmed besides the rows above: windows created without entering them and their jump history
cleared (E7); the travel swap with autocommands off; Neovim's top lines ignored while an attach
places the cursor; windows rebuilt lazily after Restart Neovim; the safety nets; per-editor grid
sizes out of scope.

## 2. The experiments

### E1 Layout

Three ways to hold several windows, each with two buffers of 200 lines, UI 120 × 40 (Neovim
0.12.4; the layout facts held on 0.12.6, E7).

| Layout | Height | `L` / `gg Ctrl-D` | `:split` then `:close` | |
|---|---|---|---|---|
| Tabpage per editor | 38 | 38 / 20 | back in the editor's window | chosen |
| Hidden floats | 38 | 38 / 20 | lands in the home window | no |
| Splits in one tab | 12 | 12 / 7 | back in the editor's window | no |

With the viewport, "38" is now the editor's own height: the tab's one window fills Neovim's screen,
which is resized to the attached editor. Splits would still halve it. `nvim_set_current_win` into
another tab fires `BufLeave WinLeave TabLeave WinEnter TabEnter BufEnter`, then `CursorMoved` and
`WinScrolled` (E7). Floats and splits skip the two tab events.

### E2 `Ctrl-O` across files

Editor A on a.txt, editor B on b.txt, each in its own tab. In A: `10G`, `20G`, then `:b` into b.txt.

| Candidate | What the plug-in does | Result |
|---|---|---|
| Per-editor lists | Put a.txt back in A's window, activate B's window, copy the cursor. | A's history now ends in b.txt: `Ctrl-O` in A jumps to B again. B's history holds only what tab creation copied. Neovim cannot delete single entries. |
| Window travels (chosen) | A's window, now on b.txt, becomes B's. B's old window gets a.txt and A's cursor and becomes A's. | `Ctrl-O` in B returns to a.txt line 20, which travels back to A. The same chain as with one window. |

What travels with a window: its window-local options, its folds, its scroll position, its jump
history, its location list, and the viewport's saved `'linebreak'` values
(`w:neovim4eclipse_saved`). The editor that receives the window is attached as usual, which sends its
word wrap, size, top line and folds again (VIEWPORT.md, FOLDING.md), so nothing visible moves.

Checked on 0.12.6, each window with its own location list: after `10G`, `20G` and `:b b.txt` in A,
and the swap, `Ctrl-O` in B goes to a.txt line 20 with the jump history whole (a.txt:1, 10, 20,
b.txt:1), and the list B's editor shows is the one made in A. That is Neovim's own rule: the list
belongs to the window the jump happened in. So editor A receives B's old window with B's old list
and jump history; Neovim has no counterpart for that half (there, B's window would go on showing
b.txt), it comes from Eclipse having one editor per file. Rejected: emptying the displaced window's
list (a special case for little gain). For the diagnostics plans: a location list filled in one
editor can end up in the other after a cross-file `Ctrl-O`.

The swap sets a buffer in a window of a tab that is not current, which fires `BufLeave BufEnter
BufWinEnter` (E7), so it runs with autocommands off, and Eclipse does not take it for a jump.

**Switching editors in Eclipse (found in the manual test).** A tab click, `Ctrl+F6` or `F3` is no
Neovim jump: B's own window is attached, and its jump history knows nothing of a.txt, so `Ctrl-O` in
B after `G` in A and a tab click did nothing. Neovim has no function to add an entry to a jump
history (`getjumplist()` only reads), so the plug-in cannot write the switch in. Weighed:

| Candidate | Result |
|---|---|
| Every Eclipse switch travels, as `:b` does | `Ctrl-O` as with one window, but editors swap window-local options, folds and location lists on every tab click: per-editor settings would not hold. |
| Fall back to Eclipse's Back and Forward (chosen) | Editors keep their windows. `Ctrl-O` / `Ctrl-I` are Neovim's while the window's history goes on, Eclipse's `Backward History` / `Forward History` at its end (`LuaScripts.SETUP`, `editor/IdeActions`). Eclipse's history also holds its own entries (Open Declaration), so the two histories can disagree. |
| Leave it, as per-window behavior | Matches Vim's windows, but most users expect `Ctrl-O` to go back to the last editor. |

**No stray jumps (2026-10-29).** `nvim_win_set_buf` and `nvim_set_current_buf` add a jump to the
window they change. The plug-in puts buffers in windows behind the user's back (the window left
behind by a travel, a show), so each window gathered jumps the user never made: `Ctrl-O` held down
between two editors found one more for ever, and at the end of a window's history Eclipse's Back
found the other editor again. The module now uses `:keepjumps buffer!` (`put_buf`).

**Back and Forward that retrace (2026-10-29).** After `:e other.txt`, `:e #`, `:e #`, `Ctrl-O`
went back about nine times and `Ctrl-I` forward about three (measured: 8 and 2). Two causes, both
from the editor switches the plug-in makes when a Neovim jump goes into another editor's file:

- Eclipse records each such switch in its history, as it records a tab click. Neovim's history has
  the jump too, but keeps fewer: it drops repeats of a place, so the three `:e` made two jumps and
  five Eclipse entries. `Ctrl-O` went through the jumps, then through the same switches again as
  Back.
- A new entry deletes the entries ahead of it, as a new page does in a browser. `Ctrl-I`'s first
  jump into the other file made one, and Eclipse's Forward had nothing left.

Eclipse's API has no way to leave an entry out or to go to an entry directly
(`INavigationHistory` gives the entries and the current one; Back and Forward are commands). So:
the plug-in notes the entries its own switches add (`editor/NavigationHistorySteps.activateForNeovim`),
Back steps over those whose move is in Neovim's jump history (`BufEnter` says so: the window's jumps
have an entry in the buffer left; not for `:keepjumps`), and a Back that lands where the editor
already is (same editor, same caret) goes on to the next entry. Each Back is remembered with the editor and caret it left; `Ctrl-I` at
the end of a window's jumps undoes the last one, through Eclipse's Forward while the place is still
in its history, else by showing that editor again. A switch the user makes (a tab click, `F3`)
forgets the remembered Backs, as it deletes Eclipse's way forward; Neovim jumps do not, as Vim
never drops a jump. The decisions are in `util/HistorySteps`, tested without Eclipse.

The order matters too: a Back can come to an editor whose window still has a newer jump, left from
an earlier `Ctrl-O` of the same run, and `Ctrl-I` there took that jump first. So `LuaScripts.SETUP`
keeps a trail of what each `Ctrl-O` step did (a jump in a window, or a Back step), and `Ctrl-I`
undoes the newest step. Each Back step has an id, chosen by Neovim and sent with `history_back`;
`Ctrl-I` sends back the id of the oldest step to undo. The two sides never match steps by counting:
Eclipse reports the ids of Back steps that went nowhere (the start of its history), which leave the
trail, and an id Eclipse no longer has moves nothing. A count goes as far as the newest run of one
kind (`3<C-i>` after two Back steps does nothing, as Vim's does past its history). The trail is
forgotten when a key is typed in between, when the user switches editors in Eclipse (checked after
each activation: an entry that is neither the plug-in's nor seen at the last Back), and when a jump
step's window is no longer current or has no newer jump: the keys then work as before.

### E3 Two editors on one file

- Cursors and `curswant` are independent: `$j` in one window left the other at its column.
- Undo is shared: `dd` in one window, `u` in the other brings the line back.
- A line restored above a window's cursor moves that cursor down (10 → 11), as an Eclipse caret
  moves with its text.
- `nvim_buf_attach` line events are unchanged. `CursorMoved` fires per window with the right window id.
- With the viewport, each editor's window keeps its own top line, so two editors on one file scroll
  independently, as two Vim windows do.

### E4 Window commands the user types

| Command | Result with a tab per editor | Plan |
|---|---|---|
| `:close`, `<C-w>q`, `:tabclose` | The editor's tab closes; Neovim enters another editor's window; the buffer stays listed (E7). | Host command: close the editor |
| `:split`, `:vsplit`, `<C-w>s` | A hidden second window in the tab (`WinNew`, E7). | Refuse |
| `:new`, `:tabnew` | A new empty buffer and window (and tab). | Refuse |
| `:tabonly` | Every other tab closed, home window included. | Refuse |
| `:only`, `<C-w>o`, `<C-w>w`, `:windo` | No effect (one window per tab). | Leave alone |
| `:bufdo`, `:argdo` | The editor's window ends on the last buffer. | Follow, as before |
| `:e name` of a file that has an editor | Neovim's `:edit` would enter its buffer in this window. | Leave it to Neovim (`LuaScripts.HOST_COMMANDS`, `edits_an_open_document`): the window travels as for `:b` (E2), so `:e #` toggles and `Ctrl-O` goes back, as in one Vim window. Added 2026-10-29; before, Eclipse switched editors and each kept its own `#`, so a second `:e #` found none. A file with no editor, `:e!`, `:e +cmd`, `:e ++opt` and `:e %` stay Eclipse's. |

`:q` never reaches Neovim; the plug-in already handles it. `Ctrl+W` keys reach Neovim only when the
user gives `Ctrl+W` to Neovim in the preferences (KEYBOARD.md).

### E5 Folds

- Manual folds belong to the window: a second window on the same buffer had none.
- A window kept its closed fold after switching to another buffer and back.
- Since 0.1.9 the plug-in mirrors Eclipse's folds into Neovim (FOLDING.md) and sends them on every
  attach. With a window per editor, an editor's closed folds stay in its window between switches,
  and the send on attach becomes a check rather than a rebuild. Two editors on one file get their
  own folds, as in Eclipse.

### E6 Scale

- 50 tabpages on 50 buffers of 2000 lines: +64 kB of memory, about 1 kB each.
- `nvim_set_current_win` across tabs: 0.13 ms per call, RPC included (0.30 ms from a Python client
  on 0.12.6). `nvim_set_current_buf`: 0.10 ms.

### E7 Neovim 0.12.6, 2026-10-28

Two buffers of 200 lines, a UI attached as the plug-in attaches (120 × 40, `ext_messages`,
`ext_cmdline`), every autocommand of interest logged.

| Step | Events | Finding |
|---|---|---|
| `nvim_open_tabpage(b, false, {})` | `WinNew TabNew BufWinEnter WinScrolled` | No `BufEnter`, `TabEnter` or `CursorMoved`; the current window and buffer stay. The viewport's `WinScrolled` handler ignores it (not the current window). |
| Jump history of the new window | | Copied: 3 entries in the old window, 4 in the new one; `:clearjumps` in it (`nvim_win_call`) empties it. |
| `nvim_set_current_win` into the other tab | `BufLeave WinLeave TabLeave WinEnter TabEnter BufEnter CursorMoved WinScrolled` | The `CursorMoved` report carries that window's own remembered top line (section 4). |
| `nvim_ui_try_resize(100, 25)` | `WinScrolled` | The current tab's window: 23 rows at once. The other tab's: still 38 until entered, then 23. |
| `nvim_win_set_buf` on a window of another tab | `BufLeave BufEnter BufWinEnter` | Works, but enters the window for a moment: the travel swap runs with autocommands off. |
| `:tabclose` typed | `TabClosedPre BufLeave WinLeave TabLeave WinClosed TabClosed WinEnter TabEnter BufEnter CursorMoved WinScrolled` | The buffer stays listed. |
| `:split` typed | `WinLeave WinNew WinEnter CursorMoved WinScrolled` | A second window in the tab. |

Found while building (2026-10-28, 0.12.6): a window opened in a new tab copies the current
window's `:lcd`, as `:split` does; `getcwd()` inside `nvim_win_call` reads the old window's folder,
though a `:lcd` there applies to the target. `'autochdir'` works in such a window and wins over its
`:lcd` when the window is entered (only once Neovim has finished starting, so not in a `-c` script).

## 3. Working folder per window

- **`:lcd` once per window.** The project root is set when the editor's window is created, not on
  every attach, so a `:lcd` the user types stays with that editor.
- **`'autochdir'` honored.** When the user's `init.lua` sets it, the plug-in skips its own `:lcd`,
  and Neovim moves each window to its file's folder. The default stays the project root, which suits
  `:!mvn test`, `:make` and `:e pom.xml`; the file's folder suits `:e Util.java` for a sibling.
- **Untitled documents** (*File → New → Untitled Text File*) belong to no project. Their window
  keeps the folder of the editor attached just before (Neovim copies it, as a new Vim window
  inherits it); with no editor before it, the user's home folder. Example: working in
  `app/src/Main.java`, open an untitled document: `:pwd` there shows `app`, and `:w notes.txt`
  writes `app/notes.txt`. Rejected: always the home folder (never near the code), the workspace
  folder (rarely meant), refusing relative names (an error for `:w notes.txt`).

This unblocks the parked branch `feature/neovim-file-names` (`589dd98`, `368dbd6`), which makes
`:e name`, `:wq name` and `:x name` absolute against `getcwd(0)` as Neovim's own `:w name` is; it
needs a rebase onto `release/0.1.10`.

## 4. The viewport

VIEWPORT.md was built for one window; with a window per editor:

- **Size:** one UI, one grid, the attached editor's size, as before. Only the current tab's window
  is resized at once (E7), and an editor's window becomes current at its attach, which sends the
  size, so nothing changed. Per-window grids (`ext_multigrid`, `nvim_ui_try_resize_grid`) stay out
  of scope.
- **Top line on attach:** entering an editor's window reports that window's remembered top line
  (its `CursorMoved`) before the attach sends Eclipse's. The editor ignores Neovim's top lines while
  the attach places Neovim's cursor, as it ignores the cursor reports then.
- **Remembered views are a gain:** switching back to an editor finds Neovim's window where it was.
- **Word wrap:** `'wrap'`, `'linebreak'` and `w:neovim4eclipse_saved` are per window already; a
  travelling window takes them along, and the attach sets the receiving editor's.
- **Events by window:** `eclipse_cursor`, `eclipse_topline` and `eclipse_wrap` send the window id,
  and the session filters by the attached window, so two editors on one file are told apart.

## 5. Tests

- `tests/unit`: `EditorWindowsTest` (the bookkeeping, the swap, the transfer, a restarted session);
  `windows_spec.lua` (the module: open without entering, jump history cleared, the folder rules,
  `'autochdir'`, show, the travel without autocommands or file info, close, release before a wipe,
  the safety nets, the refusals and the `Ctrl-W` keys); `state_snapshot_spec.lua` (the window id);
  `NotificationBridgeTest` (window ids in the events); `HostCommandTest` (`:close`, `:tabclose`);
  `NeovimWindowsIntegrationTest` (through the session: independent cursors and shared undo on one
  file, cursor reports from another editor's window dropped, a typed `:lcd` surviving a switch, the
  travel and its jump history, an editor without a window taking the travelling one, the untitled
  folder rule, the safety net, a wipe closing the windows, a restart).
- UI tests: `TwoEditorsEndToEndTest.eachEditorOnOneFileHasItsOwnNeovimWindow`,
  `EditorEndToEndTest.ctrlOAcrossFilesGoesBackThroughTheWholeJumpHistory`,
  `ViewportEndToEndTest.anEditorSwitchKeepsEachEditorsView`,
  `RestartNeovimEndToEndTest.afterARestartEachEditorGetsANewWindow`.
