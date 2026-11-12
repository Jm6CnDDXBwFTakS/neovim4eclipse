# Design note: Neovim's window follows the editor's view

Neovim's window is the editor's view: its size is the attached editor's text area, its `'wrap'` is
the editor's word wrap, and its top line is the editor's, in both directions. So Neovim does the
screen arithmetic itself, and every command that works on "the screen" works on what the editor
shows. The plug-in implements none of these commands; it copies sizes and top lines.

Without this, Neovim's screen would be the fixed 120 × 40 cells the plug-in attaches with
(`NeovimConnection.UI_WIDTH`, `UI_HEIGHT`), a screen nobody sees:

| Command | Vim's meaning | With a fixed 120 × 40 screen |
|---|---|---|
| `H` `M` `L` | top, middle, bottom line on screen | lines of Neovim's 39-line window, wherever it is scrolled |
| `Ctrl-E` `Ctrl-Y` | scroll one line, the cursor stays unless it would leave the screen | Neovim's window scrolls; Eclipse's view does not, only the caret moves when dragged |
| `Ctrl-D` `Ctrl-U` `Ctrl-F` `Ctrl-B` | half or whole screen | by 19 or 37 lines, whatever the editor's height |
| `zz` `zt` `zb` | put the cursor line in the middle, top, bottom | nothing visible |
| `'scrolloff'` | keep N lines around the cursor | kept in Neovim's window, not the editor's |
| `gj` `gk` | screen lines | Neovim wraps at 120 columns (`'wrap'` is on), Eclipse usually does not wrap at all |

That is still how it is with the opt-out (D5), and there `LuaScripts.MOUSE` scrolls Neovim's
window to a clicked line that is not on Neovim's screen (docs/ARCHITECTURE.md).

Each editor has its own Neovim window ([WINDOWS.md](WINDOWS.md)), and "Neovim's window" below is
the attached editor's. Each window keeps its view between editor switches, and the editor ignores
Neovim's top lines while an attach places the cursor (WINDOWS.md, section 4).

The experiments (section 2) used Neovim 0.12.6, the plug-in's minimum, on Linux (arm64), through a
small msgpack client attached with the plug-in's own UI options (`NeovimConnection.TERMINAL_UI`).

## 1. Behavior

- `H`, `M`, `L`, `Ctrl-E` and friends, `zz` `zt` `zb`, and `'scrolloff'` act on what the editor
  shows. Example: in an editor showing lines 120 to 160, `L` goes to line 160 (minus `'scrolloff'`),
  and `zt` makes the cursor line the editor's first.
- Resizing the editor, the window, or the font changes `&lines` and `&columns`; `Ctrl-D` then
  scrolls half of the new height. Lines made taller by code minings (Java editors' reference counts)
  are not counted, so there `L` lands a line or two low (the user guide says so).
- The mouse wheel and the touchpad scroll Neovim's window (D1, D9) in normal and visual mode: by
  the lines the operating system reports (its speed, acceleration and momentum, as Eclipse scrolls;
  `'mousescroll'` is not used), and the cursor is pulled along when it would leave the screen, as
  with `Ctrl-E`. With a modifier held the wheel stays Eclipse's: Shift+wheel may scroll sideways,
  and Ctrl+wheel and Cmd+wheel scroll in Eclipse's text editor (checked on Linux, macOS and
  Windows), so leaving them to Eclipse changes nothing visible there.
- Dragging the scroll bar, or Eclipse scrolling on its own (revealing a search hit, Open
  Declaration), drags Neovim's cursor into the new view as `Ctrl-E` does, so the next key does not
  scroll back.
- In insert mode the wheel is Eclipse's (D6): the view scrolls and the caret stays where you type,
  even off screen; the next key brings the view back to the caret, as Eclipse does. At Esc Neovim
  takes the editor's top line, and a cursor outside that view is dragged into it (one inside stays),
  as Neovim's own wheel would have done.
- The end of the document: Neovim can scroll until the last line is at the top (`Ctrl-E`), Eclipse
  cannot; the editor stops at its end, and that position becomes the agreed one (not sent back).
  The user guide says so.
- A user's `'laststatus'`, `'number'`, `'signcolumn'` stay theirs. The plug-in measures what they
  take (section 3.1) instead of turning them off.
- `'wrap'` and Eclipse's word wrap (Alt+Shift+Y; Cmd+Option+Y on macOS) are one setting (D2):
  toggling either changes the other, for that editor. On attach Eclipse's state wins, so the default
  is Eclipse's preference "Enable word wrap when opening an editor", not a `set wrap` in `init.lua`.
  With word wrap on, `'linebreak'` is set too (Eclipse wraps at word boundaries), and a
  `:set nolinebreak` then gets a message saying why it is needed (D7). The wrap points can still
  differ by a word now and then (Eclipse measures pixels, Neovim cells).
- Horizontal scrolling stays Eclipse's (D4): `zl` `zh` `zs` `ze` change nothing visible.
- With no editor attached (after *Detach*, or with every editor closed) Neovim keeps the last
  editor's size (D8); nobody sees its screen then.
- `vim.g.neovim4eclipse_viewport = 0` in `init.lua` turns the feature off (D5): the fixed
  120 × 40 grid, no scroll sync, the wheel Eclipse's, `'wrap'` left alone. Read once at start, like
  `vim.g.neovim4eclipse_problems` and `_folds`; a change needs a Neovim restart.

## 2. Background, with the experiments

Neovim 0.12.6:

- **X1, size.** With the plug-in's UI options `'cmdheight'` is already 0 (`ext_messages`), and the
  default `'laststatus'` 2 takes one row: at 40 rows `winheight(0)` is 39. After
  `nvim_ui_try_resize(80, 25)`: `&lines` 25, `&columns` 80, `winheight` 24, `winwidth` 80. With
  `laststatus=0`, `winheight` is 25. So the grid needs *editor rows + (lines − winheight)*.
- **X2, `win_viewport` without `ext_multigrid`.** Neovim 0.12 sends
  `["win_viewport", grid, win, topline, botline, curline, curcol, line_count, scroll_delta]`
  (0-based lines, `botline` exclusive) in every redraw whose view changed, even though the plug-in
  does not ask for multigrid. Examples: `Ctrl-E` twice gave two events, topline 1 then 2;
  `200G` gave topline 187 with `scroll_delta` 185; `zz` (already centered) sent one with delta 0.
  `WinScrolled` fires too; section 3.2 uses autocmds instead of the event.
- **X2, `H`.** After the resize, `H` went to `line('w0')` (188), as expected.
- **X4, `winrestview({topline = N})`.** It keeps the cursor and so clamps the top line: with the
  cursor on 188 and 25 rows, `topline = 300` became 176. To scroll the way Vim does (cursor dragged
  along), the script must move the cursor first, into the new view (minus `'scrolloff'`) (section 3.3).
- **X5, wheel.** `nvim_input_mouse('wheel', 'down', '', 0, row, col)` with `mouse=a` scrolled by 3
  (`'mousescroll'` `ver:3`) and sent `WinScrolled`.
- **X7, gutter.** `set number` gives `getwininfo()[1].textoff` 4: the window's text is 4 columns
  narrower than `&columns`.
- **X12, autocmd order.** `CursorMoved` fires before `WinScrolled`, and `line('w0')` is already the
  new top line in it: `50G` gave `cur 50 w0=40`, then `scr w0=40`; `L` only `cur`; `Ctrl-E` only
  `scr`. Neither fires inside the Lua tests' `feedkeys(…, 'x')`.

- **X10, wheel and touchpad in SWT (D9), macOS** (SWT 4967 cocoa, Apple trackpad and a mouse;
  [probes/WheelProbe.java](probes/WheelProbe.java), a window with a `StyledText` of 13 px lines,
  which logs each event and how far the text moved). Both arrive as `SWT.MouseVerticalWheel` with
  `detail` 1 (`SWT.SCROLL_LINE`) and `stateMask` 0; no `SWT.Gesture` event at all. `count` is
  already whole lines:
  - Mouse wheel: one event per notch, `count` −1, the text moved 13 px (one line).
  - Trackpad, slow: one event about every 16 ms while the fingers move, each `count` ±1, 13 px.
  - Trackpad flick: the momentum after letting go comes as more events of the same kind, `count`
    rising to −13 and falling back to −1 over about 1 s (55 events, 3370 px, about 260 lines).
  - Sideways: `SWT.MouseHorizontalWheel`, `detail` 0; a few also come during vertical trackpad
    scrolling (6 in phases 2 and 3). `StyledText` did not scroll sideways in the probe.

  So nothing in the event tells a trackpad from a mouse wheel, finger from momentum, on macOS; only
  the timing differs. And SWT's own scrolling is not pixel-smooth there: the text moves `count`
  lines per event.

- **X11, wheel and touchpad in SWT (D9), Linux in a VM** (SWT 4967 gtk, aarch64, Debian with XFCE
  on X11, in a VM on the Mac of X10, its trackpad and a mouse; the same probe). Every event is
  `SWT.MouseVerticalWheel`, `detail` 1, `count` ±3, wheel and trackpad alike, and Eclipse moved the
  text 78 or 79 px, three lines of 26 px. Slow trackpad: 14 events over 6 s. A flick: 364 events in
  about 1 s, 28696 px (about 1100 lines; many events moved 0 px and the next one the rest, as GTK
  coalesces redraws). The VM passes the trackpad on as wheel clicks, so a touchpad on Linux itself
  may differ; still the setup of anyone running Eclipse in a VM.

Not tried: a proportional editor font. It is measured on the Eclipse side, so it changes only the
numbers sent (section 3.1).

## 3. Design

### 3.1 Size (Eclipse to Neovim)

- `editor/ViewportFollower.cells` (SWT, tested in `tests/swt`, `ViewportFollowerTest`) reads the
  `StyledText`'s area, margins and line height, and `util/ViewportSize.editorCells` (tested in
  `tests/unit`) turns them into cells: the text area's width over the font's average character width
  (`GC.getFontMetrics().getAverageCharacterWidth()`), its height over the line height. Only whole
  characters and whole lines count (a partly visible last line does not, as in Vim). Example:
  720 × 520 pixels of a font 9 pixels wide and 13 high are 80 columns and 40 rows.
- Rows come from pixels, not `JFaceTextUtil.getBottomIndex`: that index stops at the document's
  end (a short file would get a short window) and moves with code minings while scrolling, which
  would resize Neovim on every scroll. That is why lines made taller by code minings are not
  counted (section 1).
- The size is measured at every `Resize` and `Paint` and sent when it changes; that catches a
  resized window or part, a new font (noticed because the font object differs; the character width
  is read once per font, no font-registry listener) and word wrap toggled (the horizontal scroll bar
  comes or goes).
- The session side is `core/ViewportPort` on `NeovimSession` (`setEditorSizeAsync`,
  `setWrapAsync`, `setTopLineAsync`, `scrollToAsync`, the wrap listener). Sizes are merged in a
  `util/LatestValue`, so at most one is in flight and only the newest counts.
- A pure class `util/ViewportSize` turns the editor's cells into a grid size: `rows = editorRows +
  (lines − winheight)`, `columns = editorColumns + textoff` (X1, X7), at least one column and two
  rows of text. The overhead (`{ &lines - winheight(0), textoff }`, for example `{ 1, 4 }` with the
  default status line and `set number`) comes from `LuaScripts.VIEWPORT_OVERHEAD`. The grid goes to
  Neovim with `nvim_ui_try_resize`, not twice the same.
- No resize is sent while Neovim waits for the rest of a command (`nvim_get_mode().blocking`, after
  `z`, `r` or `"`): Neovim would not answer the overhead question then. The skipped resize is sent
  after the input that ends the wait. Dropping it until the next resize is wrong: `L` then acts like
  Page Down, since Neovim's window keeps the previous editor's height.
- An option that changes the overhead (`:set number`, `laststatus`, ...) fires `OptionSet`, which
  sends `eclipse_viewport`, scheduled so that `:set nu rnu` resizes once; the session measures again
  and resizes.
- One UI, one size: that of the attached editor. Each editor's window could get its own size
  through `ext_multigrid` and `nvim_ui_try_resize_grid`; not done, since only the attached editor's
  window is seen and typed in.

**Word wrap** (D2, D7). `LuaScripts.SET_WRAP` sets the window's `'wrap'` from Eclipse's word wrap.
`ViewportFollower` sees Eclipse's word wrap on the widget (`StyledText.getWordWrap()` at the next
paint), not through Eclipse's command, because the widget is the one place every change shows: the
key, the menu, the toolbar button, `setWordWrap` from code. A `:set wrap` in Neovim comes back as
`eclipse_wrap(buf, on, win)`. Neither is echoed: the value last agreed on is kept, and only a
difference from it is sent; an option the plug-in set itself is not reported back (the `applying`
flag in the Lua module). An editor that cannot wrap (`AbstractTextEditor.isWordWrapSupported()`
false) keeps its state, and Neovim is told so. Block selection is no obstacle: Eclipse turns it off
when word wrap goes on. While wrap is on, `'showbreak'` is set to `NONE`, not `''`: it is
global-local, and an empty local value falls back to the global one.

### 3.2 Scrolling, Neovim to Eclipse

- Neovim reports its top line through two autocmds, not by parsing `win_viewport` (X2). The
  `CursorMoved` report (`eclipse_cursor`) carries `line('w0')`, and a `WinScrolled` autocmd sends
  `eclipse_topline(buf, line, win)` for scrolls that leave the cursor (`Ctrl-E`, `zt`, a resize).
  Both name the buffer, as the other reports do, and `WinScrolled`'s `v:event` tells the current
  window from a split or float (it fires for every window that scrolled; only the current one is
  the editor's). A scroll that moves the cursor (`50G`, `j` at the bottom) is reported by both
  (X12); the second changes nothing. Either way it also covers scrolling no key caused (a plug-in's
  timer, `:2000` from a mapping), and it costs no request.
- **The top line before the caret.** `NotificationBridge` passes a cursor report's top line on first
  (`Sink.onTopLine`), so the controller's UI queue scrolls the editor, then places the caret, which
  is then on screen, and Eclipse's own reveal does not scroll. Example: the editor and Neovim show
  lines 1 to 20, and `50G` scrolls Neovim's window to line 40; the editor shows 40 to 59, then the
  caret goes to line 50. Without this, Eclipse would show 31 to 50 (its reveal scrolls as little as
  it can), and `H` would go to line 40, off the screen.
- The editor applies it with `ITextViewer.setTopIndex`, which converts document lines to widget
  lines where code is folded. A top line hidden in a closed fold becomes the fold's caption line
  (3.4).
- Echo: a top line from Neovim becomes the agreed one once applied (3.3), so the paint it causes
  sends nothing back.

### 3.3 Scrolling, Eclipse to Neovim

- **Wheel** (D1, D9): `editor/NeovimWheel`, a display filter as `NeovimMouse` is, swallows the
  vertical wheel in normal and visual mode unless a modifier is held (section 1), and sends its
  lines with `scrollLinesAsync`, added up while Neovim is busy. `util/WheelScroll`, a static pure
  function, turns SWT's `count` and `detail` into lines; a page (`SWT.SCROLL_PAGE`, Windows' "one
  screen at a time") is the editor's rows less two, as `Ctrl-F`. `LuaScripts.SCROLL_LINES` steps the
  top line as `Ctrl-E` and `Ctrl-Y` do, a closed fold counting as one line, and then uses
  `set_topline(…, 'drag')`; no `:normal`, which would end visual mode or fire `ModeChanged`. The
  editor scrolls only when Neovim's top line comes back (3.2): one source of truth, so no race. The
  sideways wheel stays Eclipse's (D4). Each event logs one FINE line
  (`wheel count=-3 detail=1: 3 lines`), so a user with a device that scrolls wrongly can send it.
- `'mouse'` is not consulted: the editor's own scroll (below) drags Neovim's cursor the same way,
  so the result would not differ.
- **Everything else** (dragging the scroll bar, Page Down on the scroll bar, Eclipse revealing a
  search hit, Open Declaration): `ViewportFollower` keeps the top line last agreed with Neovim and,
  at a paint, sends any other one it sees (`scrollToAsync`, newest only). Without this, a scroll-bar
  drag would be undone by the next key, or by a click (`LuaScripts.MOUSE` scrolls Neovim's window to
  a clicked line it does not show, and the editor would follow). A late report from an earlier key
  can still scroll the editor back for a moment before Neovim's answer to the scroll arrives.
- **`LuaScripts.SET_TOPLINE`** (the buffer, the 1-based line, a mode; nothing when that buffer is
  no longer the current one) has three modes:
  - `keep` (attach): the cursor stays, and Neovim's `winrestview` clamps the top line to keep it
    visible (X4); that answer comes back as a top line.
  - `drag` (a scroll in the editor, the wheel): the cursor is dragged into the new view as `Ctrl-E`
    and `Ctrl-Y` drag it, `'scrolloff'` lines from its edges. The drag counts lines, not screen
    rows; with closed folds or wrapped lines Neovim may then move the top line a little, and the
    editor follows.
  - `show` (Esc): a cursor outside the view is dragged in, one inside it stays (not moved for
    `'scrolloff'`).
- **Insert mode** (D6): Eclipse scrolls alone, and its scrolls are not sent while it types
  (Neovim's lines are behind). At Esc its top line goes to Neovim after the push and before `<Esc>`
  (`session.setTopLine`, on the input thread), and `SET_TOPLINE`'s `show` runs as the last step,
  once the cursor is final (after the replay's `setCursor` or the `<Esc>`; earlier, that cursor
  would be put back off screen). Neovim's top lines and cursor reports are ignored while Esc is
  handled (`KeyInput.isLeavingInsertMode`): the replay of the typed text runs in Neovim's old view,
  and its reports would scroll the editor back, or make Eclipse reveal the old caret, first. That
  span starts when Esc is routed on the UI thread, where Eclipse stops typing, not when the input
  thread starts the leave (`KeyInput.handOver`): on a loaded machine, Neovim's answer to
  Eclipse's last caret move came in between and scrolled the editor back to Neovim's old view,
  which the leave then read as the editor's.
  Without `show`, the view went back to the caret at Esc, since Neovim keeps its cursor on screen,
  while Neovim's own wheel would have dragged the cursor along.

### 3.4 Folding

[FOLDING.md](FOLDING.md) comes first (D3): Neovim has Eclipse's folds, and a closed fold is one
screen row on both sides (its experiment X9), so the row counts agree. What remains here:

- Top lines are converted between model and widget lines (3.2).
- A closed Java method shows its signature, not the fold's first line (its Javadoc). When Neovim's
  top line is inside a closed fold, the editor's top line is that fold's caption line
  (`FoldMirror.captionOffset`), as FOLDING.md finds the caret's.
- With the folds opt-out (`vim.g.neovim4eclipse_folds = 0`) folds are not mirrored, and `L`,
  `Ctrl-F` and `zb` fall short by the folded lines; the user guide says so. `LuaScripts.MOUSE` keeps its
  off-screen branch for this case.

### 3.5 Horizontal scrolling

With `'nowrap'`, Neovim's `leftcol` (`zl`, `zh`, `zs`, `ze`) and Eclipse's horizontal scroll could be
synced the same way (`WinScrolled` reports `leftcol`). Not done (D4): rarely used, and the column
conversion (bytes, cells, pixels) is its own work.

## 4. Decisions

- **D1, the wheel** (how far it scrolls: D9): Neovim scrolls (Vim behavior, the cursor dragged
  along, no race). Rejected: Eclipse scrolls, then sends its top line as in 3.3 (the caret is still
  dragged, by the script, since Neovim's cursor cannot be off screen, and Neovim's late answers need
  a stale-event rule).
- **D2, `'wrap'`**: synced both ways. On attach and on Alt+Shift+Y (Cmd+Option+Y on macOS) the
  plug-in sets the window's `'wrap'` from the editor's word wrap; an `OptionSet` autocommand turns a
  `:set wrap` / `:set nowrap` in Neovim into Eclipse's word wrap for that editor. Eclipse's state
  wins on attach, so a `set wrap` in `init.lua` does not fight Eclipse's preference ("Enable word
  wrap when opening an editor", which the user guide names as the way to set a default). While wrap is on,
  the window also gets `'linebreak'` (D7). Echo guard as for the caret: an option set from Eclipse
  does not come back. Rejected: one way only (a `:set wrap` would silently leave the two out of
  step), and leaving `'wrap'` to the user (wrong counts on long lines).
- **D3, folded editors**: built on FOLDING.md, so that the row counts agree; what remains is the
  caption-line rule (3.4).
- **D4, horizontal scrolling**: out of scope. Eclipse keeps its own horizontal scroll (it reveals
  the caret); `zl` `zh` `zs` `ze` change nothing visible. `leftcol` could be synced through
  `WinScrolled`, which reports it (`win_viewport` does not).
- **D5, an opt-out**: `vim.g.neovim4eclipse_viewport = 0`, read at start like the other
  `vim.g.neovim4eclipse_*` switches. The feature changes the wheel for everyone, so there is a way
  back. When off, no other viewport script is sent and no `ViewportFollower` is created.
- **D6, the wheel in insert mode**: Eclipse's, the caret stays (section 1). Rejected: dragging the
  caret along as Vim does (it closes an open content-assist popup and moves the typing to another
  line), and ending insert mode on the first wheel step (a scroll would quietly leave insert mode,
  which Vim never does).
- **D7, `'linebreak'`**: set in the window while word wrap is on, with `'breakindent'` off and
  `'showbreak'` `NONE` (Eclipse draws neither an indent nor a marker for a wrapped part); the user's
  values are kept in `w:neovim4eclipse_saved` and come back when wrap goes off. An `OptionSet` on
  any of the three while wrap is on sets it back and shows a message (for example "neovim4eclipse:
  'linebreak' stays on while word wrap is on, so that lines wrap where Eclipse wraps them"), once
  per Neovim session; later changes are set back silently, and the value asked for applies once
  wrap is off. Once, because `OptionSet` also fires for a filetype plug-in, another plug-in or a
  modeline (`vim: set nolinebreak:`, checked in `viewport_spec.lua`), so a Markdown plug-in that
  sets `'nolinebreak'` would otherwise show it for every file. Why: with `'nolinebreak'` Neovim
  breaks mid-word and Eclipse at the word, so a long line has a different number of screen rows on
  each side, and `gj`, `H`, `Ctrl-D` and the top lines drift. Rejected: following the user's value
  (screen commands wrong on long lines).
- **D8, the size with no editor attached**: the last editor's.
- **D9, the touchpad**: Neovim scrolls by the lines SWT reports (`count`), for a mouse wheel and a
  touchpad alike, dragging the cursor when it would leave the screen as `Ctrl-E` does
  (`'scrolloff'` kept). The speed, acceleration and momentum stay the operating system's, as
  Eclipse scrolls; `'mousescroll'` is not used (the user guide says so). Why: on macOS nothing in the event
  tells a trackpad from a wheel (X10), so one rule must serve both. Events of 0 lines, possible from
  smooth touchpads, did not come on macOS (X10) or Linux (X11), since SWT reports whole lines, so
  nothing is added up; real Linux and Windows touchpads are not measured (the test machines share
  one Mac's trackpad, two of them as VMs). Rejected: one Neovim wheel step (`'mousescroll'`, 3
  lines) per event (a trackpad scrolls three times as fast as in Eclipse, a flick about 500 lines),
  and one step per 3 lines reported (3-line jumps on a trackpad). Built so it can change: the
  conversion is one pure function, `util/WheelScroll` (SWT's `count` and `detail` in, lines out),
  unit-tested; the editor only passes the event in, and the Lua side scrolls by a number of lines
  without knowing where it came from. A measurement from another machine becomes a
  `WheelScrollTest` case.

## 5. Tests

- `tests/unit`: `ViewportSizeTest` (grid from cells and overhead, the minimum, impossible sizes
  refused, an editor's cells: whole characters and lines, at least 1 × 1); `WheelScrollTest` (the X10 and X11 events as cases, a page as `Ctrl-F`);
  `LatestValueTest`.
- Lua (`tests/unit/src/test/lua/viewport_spec.lua`): the opt-out; `VIEWPORT_OVERHEAD` with the
  user's status line and number column; `SET_WRAP` with `'linebreak'`, `'breakindent'` and
  `'showbreak'`, the user's values back, no echo, `'nolinebreak'` set back with one message, a
  modeline too, `eclipse_viewport` once per batch; `SET_TOPLINE` keeps the cursor visible, drags it
  as `Ctrl-E` does with `'scrolloff'`, and `show` at Esc; `SCROLL_LINES` scrolls and drags as
  `Ctrl-E` and `Ctrl-Y`, a closed fold as one line, stops with the last line at the top.
- Integration (`NeovimViewportIntegrationTest`, real Neovim through the session, since
  `nvim_ui_try_resize` needs an attached UI and the autocmds of X12 need Neovim's main loop): the
  window gets the editor's size and `L` its last line; `set number` widens the screen; a jump
  reports the top line before the cursor; `Ctrl-E` reports the top line without a cursor move; the
  editor's top line comes back; a size arriving mid-command is sent when the command ends; the
  opt-out keeps the fixed screen.
- `tests/swt`: `ViewportFollowerTest` (cells, sizes sent once, wrap both ways without echo, top
  lines both ways without echo); `NeovimWheelTest` (the wheel goes to Neovim, a page, insert mode
  and modifiers stay Eclipse's).
- UI tests: `ViewportEndToEndTest` (size and resize, `L`, a jump, `Ctrl-E`, an editor switch keeps
  each view, the wheel, a scroll kept by the next key, Esc after scrolling in insert mode, word wrap
  both ways); `FoldingEndToEndTest` for top lines with collapsed folds. All waits with `TestWait`.
