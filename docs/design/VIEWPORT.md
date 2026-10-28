# Design note: Neovim's window follows the editor's view

Status: **planned**, not implemented; for 0.1.10, on `feature/viewport` cut from `release/0.1.10`.
Planned on `feature/diagnostics` at `589485a`. The experiments used Neovim 0.12.6 (the plug-in's
minimum) on Linux (arm64), through a small msgpack client attached with the plug-in's own UI options
(`NeovimConnection.TERMINAL_UI`). Decisions D1 to D4 (section 4) were made with the maintainer on
2026-10-27, D5 to D8 on 2026-10-28; D9 (the touchpad) waits on a measurement. [FOLDING.md](FOLDING.md)
was built first (D3) and released in 0.1.9. After the feature is built, add a section *As built*
after the summary, as [TAB_COMPLETION.md](TAB_COMPLETION.md) does.

## Summary

The plug-in attaches to Neovim as a UI of a fixed 120 × 40 cells (`NeovimConnection.UI_WIDTH`,
`UI_HEIGHT`), and nothing keeps Neovim's window in step with the editor's view: no resize, no scroll
sync. Every command that works on "the screen" therefore works on a screen nobody sees:

| Command | Vim's meaning | What happens today |
|---|---|---|
| `H` `M` `L` | top, middle, bottom line on screen | lines of Neovim's 39-line window, wherever it is scrolled |
| `Ctrl-E` `Ctrl-Y` | scroll one line, the cursor stays unless it would leave the screen | Neovim's window scrolls; Eclipse's view does not, only the caret moves when dragged |
| `Ctrl-D` `Ctrl-U` `Ctrl-F` `Ctrl-B` | half or whole screen | by 19 or 37 lines, whatever the editor's height |
| `zz` `zt` `zb` | put the cursor line in the middle, top, bottom | nothing visible |
| `'scrolloff'` | keep N lines around the cursor | kept in Neovim's window, not the editor's |
| `gj` `gk` | screen lines | Neovim wraps at 120 columns (`'wrap'` is on), Eclipse usually does not wrap at all |

The left mouse button already has to work around this: `LuaScripts.MOUSE` scrolls Neovim's window to
a clicked line that is not on Neovim's screen (docs/ARCHITECTURE.md, "Neovim's screen is not the
editor's").

**Plan:** make Neovim's window the editor's view, so that Neovim does the screen arithmetic itself.

1. **Size:** the UI's size is the attached editor's text area, in lines and columns
   (`nvim_ui_try_resize`), and `'wrap'` follows Eclipse's word wrap.
2. **Neovim to Eclipse:** Neovim reports every change of its window's view (`win_viewport`, an
   event it already sends); the editor scrolls to Neovim's top line.
3. **Eclipse to Neovim:** the mouse wheel goes to Neovim's own mouse (`nvim_input_mouse`), like the
   left button already does (D1); other scrolling in Eclipse (scroll bar, Eclipse's own
   reveal) sets Neovim's top line.

The plug-in implements none of the commands above; it copies sizes and top lines.

Size: about 400 lines of code and 500 of tests, in four commits (section 6). Uncertain: the
SWT measuring with variable line heights (section 3.1) may grow it.

## 1. Behavior

- `H`, `M`, `L`, `Ctrl-E` and friends, `zz` `zt` `zb`, and `'scrolloff'` act on what the editor
  shows. Example: in an editor showing lines 120 to 160, `L` goes to line 160 (minus `'scrolloff'`),
  and `zt` makes the cursor line the editor's first.
- Resizing the editor, the window, or the font changes `&lines` and `&columns`; `Ctrl-D` then
  scrolls half of the new height.
- The mouse wheel scrolls as in Neovim *(D1)*: by `'mousescroll'` lines (default `ver:3`), and the
  cursor is pulled along when it would leave the screen. That is a change from today, where the
  wheel scrolls Eclipse alone and the caret may stay off screen.
- In insert mode the wheel is Eclipse's, as today *(D6)*: the view scrolls and the caret stays where
  you type, even off screen; the next key brings the view back to the caret, as Eclipse does. At Esc
  Neovim takes the editor's top line. Why not Neovim's wheel: while Eclipse types, Neovim does not
  have the typed lines until Esc (docs/ARCHITECTURE.md, "Folding: the workarounds"), so it cannot
  scroll or drag a cursor through them.
- A user's `'laststatus'`, `'number'`, `'signcolumn'` stay theirs. The plug-in measures what they
  take (section 3.1) instead of turning them off.
- `'wrap'` and Eclipse's word wrap (Alt+Shift+Y) are one setting (D2): toggling either changes
  the other, for that editor. On attach Eclipse's state wins, so the default is Eclipse's preference
  "Enable word wrap when opening an editor", not a `set wrap` in `init.lua`. With word wrap on,
  `'linebreak'` is set too (Eclipse wraps at word boundaries), and a `:set nolinebreak` then gets a
  message saying why it is needed *(D7)*. The wrap points can still differ by a
  word now and then (Eclipse measures pixels, Neovim cells).
- Horizontal scrolling stays Eclipse's (D4): `zl` `zh` `zs` `ze` change nothing visible.
- With no editor attached (after *Detach*, or with every editor closed) Neovim keeps the last
  editor's size *(D8)*; nobody sees its screen then.
- `vim.g.neovim4eclipse_viewport = 0` in `init.lua` turns the feature off *(D5)*: the fixed
  120 × 40 grid, no scroll sync, the wheel Eclipse's, `'wrap'` left alone. Read once at start, like
  `vim.g.neovim4eclipse_problems` and `_folds`.

## 2. Background, with the experiments

The script is in the session's scratchpad (`vp.py`), not in the repository. Results, Neovim 0.12.6:

- **X1, size.** With the plug-in's UI options `'cmdheight'` is already 0 (`ext_messages`), and the
  default `'laststatus'` 2 takes one row: at 40 rows `winheight(0)` is 39. After
  `nvim_ui_try_resize(80, 25)`: `&lines` 25, `&columns` 80, `winheight` 24, `winwidth` 80. With
  `laststatus=0`, `winheight` is 25. So the grid needs *editor rows + (lines − winheight)*.
- **X2, `win_viewport` without `ext_multigrid`.** Neovim 0.12 sends
  `["win_viewport", grid, win, topline, botline, curline, curcol, line_count, scroll_delta]`
  (0-based lines, `botline` exclusive) in every redraw whose view changed, even though the plug-in
  does not ask for multigrid. Examples: `Ctrl-E` twice gave two events, topline 1 then 2;
  `200G` gave topline 187 with `scroll_delta` 185; `zz` (already centered) sent one with delta 0.
  `WinScrolled` fires too; either would do (section 3.2 picks the event).
- **X2, `H`.** After the resize, `H` went to `line('w0')` (188), as expected.
- **X4, `winrestview({topline = N})`.** It keeps the cursor and so clamps the top line: with the
  cursor on 188 and 25 rows, `topline = 300` became 176. To scroll the way Vim does (cursor dragged
  along), the script must move the cursor first, into the new view (minus `'scrolloff'`) (section 3.3).
- **X5, wheel.** `nvim_input_mouse('wheel', 'down', '', 0, row, col)` with `mouse=a` scrolled by 3
  (`'mousescroll'` `ver:3`) and sent `WinScrolled`.
- **X7, gutter.** `set number` gives `getwininfo()[1].textoff` 4: the window's text is 4 columns
  narrower than `&columns`.

Not tried yet: variable line heights in the Java editor (code minings), and a proportional editor
font. Both are measured on the Eclipse side, so they change only the numbers sent (section 3.1).

## 3. Design

### 3.1 Size (Eclipse to Neovim)

- A pure class `util/ViewportSize` turns measurements into a grid size: `rows = editorRows +
  (lines − winheight)`, `columns = editorColumns + textoff`. `editorRows` is the count of *fully*
  visible lines (a partly visible last line does not count, as in Vim); `editorColumns` is the text
  area's width over the font's average character width. Unit-tested in `tests/unit`.
- `editor/ViewportMeasure` (SWT, tested in `tests/swt`) measures a `StyledText`: rows from
  `JFaceTextUtil.getPartialBottomIndex`/`getBottomIndex` against `getTopIndex`, so that line
  spacing from code minings counts; columns from `GC.getFontMetrics().getAverageCharacterWidth()`.
- The editor sends the size on attach and when the widget is resized or its font changes
  (`ControlListener`, a font property listener), at most one request in flight
  (`util/LatestValue`, as for mouse drags). The overhead (`lines − winheight`, `textoff`) comes back
  from a small Lua script (`LuaScripts.VIEWPORT_SIZE`) after each resize, and a size is sent again
  when it changed (a user's `set number` changes `textoff`).
- One UI, one size: that of the attached editor. Two editors side by side: only the one being typed
  in counts, as for everything else today. With one Neovim window per editor (a separate plan,
  not yet in the repository), each window could get its own size through
  `ext_multigrid` and `nvim_ui_try_resize_grid`; out of scope here.

### 3.2 Scrolling, Neovim to Eclipse

- `win_viewport` is parsed with the other redraw events (a `util/ViewportEvent`, next to
  `MessageEvent` and `CmdlineEvent`), and the session passes `topline` on, after the `flush` of the
  redraw, to the attached editor.
- The editor sets its top line to Neovim's (`ITextViewer.setTopIndex`, with model lines converted to
  widget lines where code is folded, `ITextViewerExtension5.modelLine2WidgetLine`), *before* the caret
  is placed, inside `CaretSync.runAsFromNeovim` so that the caret's own reveal does not scroll again.
- Why the event and not the per-key state snapshot: it also comes for scrolling that no key caused
  (a plug-in's timer, `:2000` from a mapping), and it costs no request.
- Echo: setting the top index fires Eclipse's viewport listener; changes made while applying
  Neovim's top line are ignored by the listener, as `CaretSync` ignores its own caret moves.

### 3.3 Scrolling, Eclipse to Neovim

- **Wheel** *(D1)*: in normal and visual mode the wheel event is consumed and sent to Neovim's mouse
  (`NeovimMouse`, extended with `wheel` up and down; sideways wheel stays Eclipse's, D4), and the editor scrolls only when
  `win_viewport` answers. One source of truth, so no race between the two. Without `'mouse'` the
  wheel stays Eclipse's, and the scroll bar path below applies.
- **Everything else** (dragging the scroll bar, Page Down on the scroll bar, Eclipse revealing a
  search hit, Open Declaration): the viewport listener sends the new top line, newest only
  (`LatestValue`), to a script `LuaScripts.SET_TOPLINE` that does what Vim does on `Ctrl-E`: it moves
  the cursor into the new view, `'scrolloff'` lines from its edge, then sets `topline`
  (`winrestview`). Neovim's `win_viewport` then comes back with the same top line, which changes
  nothing. A `win_viewport` older than a top line Eclipse sent is dropped: the event carries no
  sequence number, so the editor ignores events until one matches the top line it sent last (or a
  key is typed). Uncertain: whether Eclipse's own reveal can fight Neovim's `'scrolloff'`; check in
  the UI tests.
- **Insert mode:** Eclipse scrolls alone; at Esc the top line is sent with the document
  (`KeyInput.leaveInsertMode`).

### 3.4 Folding

[FOLDING.md](FOLDING.md) is built first (D3): Neovim then has Eclipse's folds, and a closed fold is
one screen row on both sides (its experiment X9), so the row counts agree. What remains here:

- Top lines are converted between model and widget lines (3.2).
- A closed Java method shows its signature, not the fold's first line (its Javadoc). When Neovim's
  top line is inside a closed fold, the editor's top line is that fold's caption line, found as
  FOLDING.md finds the caret's (`FoldMirror`'s caption lookup).
- With the opt-out (`vim.g.neovim4eclipse_folds = 0`) folds are not mirrored, and `L`, `Ctrl-F`
  and `zb` fall short by the folded lines; README says so. `LuaScripts.MOUSE` keeps its off-screen
  branch for this case.

### 3.5 Horizontal scrolling

With `'nowrap'`, Neovim's `leftcol` (`zl`, `zh`, `zs`, `ze`) and Eclipse's horizontal scroll could be
synced the same way (`WinScrolled` reports `leftcol`). Left out *(decided, D4)*: rarely used, and the
column conversion (bytes, cells, pixels) is its own work.

## 4. Decisions

- **D1, the wheel** *(decided 2026-10-27)*: Neovim's (Vim behavior, `'mousescroll'`, the cursor
  dragged along, no race). Rejected: Eclipse scrolls, then sends its top line as in 3.3 (the caret
  is still dragged, by the script, since Neovim's cursor cannot be off screen, and Neovim's late
  answers need the stale-event rule). To check while building: how SWT reports touchpad scrolling
  (many small wheel events, or few large ones); whole-line steps replace Eclipse's pixel scrolling.
- **D2, `'wrap'`** *(decided 2026-10-27)*: synced both ways. On attach and on Alt+Shift+Y the
  plug-in sets the window's `'wrap'` from the editor's word wrap; an `OptionSet` autocommand turns a
  `:set wrap` / `:set nowrap` in Neovim into Eclipse's word wrap for that editor. Eclipse's state
  wins on attach, so a `set wrap` in `init.lua` does not fight Eclipse's preference ("Enable word
  wrap when opening an editor", which README names as the way to set a default). While wrap is on,
  the window also gets `'linebreak'` (Eclipse breaks at words), no `'showbreak'` and no
  `'breakindent'` (Eclipse draws neither). Echo guard as for the caret: an option set from Eclipse
  does not come back. Rejected: one way only (a `:set wrap` would silently leave the two out of
  step), and leaving `'wrap'` to the user (wrong counts on long lines).
- **D3, folded editors** *(decided 2026-10-27)*: FOLDING.md is built first, on its own branch, and
  merged before this feature starts. Section 3.4 then shrinks to the caption-line rule: Eclipse
  shows a closed Java method on its signature, not on the fold's first line (its Javadoc), so the
  top line is placed by Eclipse's displayed line, as FOLDING.md places the caret.
- **D4, horizontal scrolling** *(decided 2026-10-27)*: out of scope. Eclipse keeps its own horizontal
  scroll (it reveals the caret as today); `zl` `zh` `zs` `ze` change nothing visible. A follow-up can
  sync `leftcol` through `WinScrolled`, which reports it (`win_viewport` does not).
- **D5, an opt-out** *(decided 2026-10-28)*: `vim.g.neovim4eclipse_viewport = 0`, read at start
  like the other `vim.g.neovim4eclipse_*` switches. The feature changes the wheel for everyone, so
  there is a way back.
- **D6, the wheel in insert mode** *(decided 2026-10-28)*: Eclipse's, the caret stays (section 1).
  Rejected: dragging the caret along as Vim does (it closes an open content-assist popup and moves
  the typing to another line), and ending insert mode on the first wheel step (a scroll would
  quietly leave insert mode, which Vim never does).
- **D7, `'linebreak'`** *(decided 2026-10-28)*: set in the window while word wrap is on, with
  `'breakindent'` off and `'showbreak'` empty; the user's values come back when wrap goes off.
  An `OptionSet` on any of the three while wrap is on sets it back and shows a message (for
  example "neovim4eclipse: 'linebreak' stays on while word wrap is on, so that lines wrap where
  Eclipse wraps them"), once per Neovim session; later changes are set back silently. Once, because
  `OptionSet` also fires for a filetype plug-in, another plug-in or (to check in the Lua test) a
  modeline, so a Markdown plug-in that sets `'nolinebreak'` would otherwise show it for every file. Why: with `'nolinebreak'` Neovim breaks mid-word and Eclipse at the word, so
  a long line has a different number of screen rows on each side, and `gj`, `H`, `Ctrl-D` and the
  top lines drift. Rejected: following the user's value (screen commands wrong on long lines).
- **D8, the size with no editor attached** *(decided 2026-10-28)*: the last editor's.
- **D9, the touchpad** *(open)*: measure what SWT reports for touchpad scrolling on Linux, macOS
  and Windows (many small wheel events, few large ones, or a marker that tells them from a mouse
  wheel), then decide between whole-line steps for every wheel (D1 as is) and keeping Eclipse's
  pixel scrolling for the touchpad, followed by the top line as in 3.3. Asked again after the
  measurement.

## 5. Tests

- `tests/unit`: `ViewportSizeTest` (rows and columns from measurements, overhead, minimum 1×1);
  `ViewportEventTest` (parsing, 0-based lines, events of other windows ignored); the echo rule of
  3.3 as a small pure class (`util/ScrollFollow`) with its test.
- Lua (`tests/unit/src/test/lua/viewport_spec.lua`): `SET_TOPLINE` drags the cursor as `Ctrl-E`
  does, keeps `'scrolloff'`, clamps at the end of the buffer; `VIEWPORT_SIZE` reports the overhead
  with `laststatus` 0 and 2 and with `number`.
- Integration (real Neovim): after `nvim_ui_try_resize`, `H`/`L` land on `w0`/`w$`; a `win_viewport`
  arrives for `Ctrl-E`.
- `tests/swt`: `ViewportMeasureTest` with a fixed font and a known widget size.
- UI tests: `Ctrl-E` scrolls the editor by one line; `L` goes to the last visible line; `zt`; the
  wheel; resizing the editor changes `&lines`; a scroll-bar drag far away moves the caret into view.
  All waits with `TestWait`; nothing here should need `timeoutReliant`.

## 6. Commits

0. The touchpad measurement (D9), before commit 3; its result is added to section 2.
1. Size: `ViewportSize`, `ViewportMeasure`, `VIEWPORT_SIZE`, resize on attach and resize, the last
   size kept (D8); `'wrap'` and `'linebreak'` (D2, D7); the opt-out (D5).
2. Neovim to Eclipse: `ViewportEvent`, top line applied before the caret.
3. Eclipse to Neovim: the wheel through `NeovimMouse` (D1), `SET_TOPLINE` for the rest, insert mode
   at Esc.
4. Docs: README (screen commands now match the editor; D3 shortfall), docs/KEYS.md,
   docs/ARCHITECTURE.md (drop "Neovim's screen is not the editor's", describe the viewport),
   docs/LEARNING.md (a walkthrough of `Ctrl-E`), CHANGELOG under *Unreleased*.
