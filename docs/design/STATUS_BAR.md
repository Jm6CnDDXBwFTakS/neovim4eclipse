# Design note: the status bar: the mode's names, widths and the cap

The Eclipse status bar shows Vim's mode, the pending command and Neovim's statusline in three
labels. A single bold, centered label 420 pixels wide, holding all three joined, did not work:
longer text was cut with "...", and Neovim evaluated the statusline 300 columns wide, so its `%=`
padding pushed the cursor position far past the label. The design below gives the text more room,
measures it as Eclipse measures its own status items, and lets Neovim fit the statusline.

## 1. Design

- **Three labels**, one per part (mode, cmd, statusline), each after a separator, left-aligned and
  bold. A part turned off has no label and takes no space.
- **Widths in characters** of the bar's font (its average character width), as Eclipse's own status
  items measure theirs; the statusline's width is a preference (default 60), the others follow from
  their content (section 3).
- **A cap**: the labels together take at most a share of the bar (preference, default 40%).
- **A tooltip** on every label with its whole text; for the mode, Vim's own wording
  (`-- INSERT --`).
- **One set of mode names, no style choice.** The names are lualine's (`V-BLOCK`, `S-LINE`,
  `O-PENDING`, `V-REPLACE`), the ones most Neovim statuslines use; Vim's parentheses stay
  (`(INSERT)`, and `(S) V-BLOCK` where lualine shows plain `V-BLOCK`), and a recording is a
  lower-case `rec @a`, which stands apart from the name. The widest are 11 characters, 18 while
  recording. The mode label is as wide as its widest text (section 3), so the rarest state sets the
  width for all of them: with Vim's long names, `(SELECT) VISUAL BLOCK` took 21 characters, 35 while
  recording. `BLOCK` and `REPLACE` are not cut further (`BLK`, `REPL`): no common statusline does,
  and it would save 3 characters of a rare label.

  The 0.2.0 snapshots offered three styles instead, through a *Mode style* preference
  (`statusModeStyle`): Full (`-- INSERT --`), Short (`INSERT`, the default) and Letters (`-I-`).
  Short with shorter long names made the other two unnecessary. A value stored by such a snapshot is
  ignored.
- **Neovim fits the statusline**: it is evaluated at the label's width in columns (`maxwidth`), so
  `%<` shortens the file name and `%=` pads to the label, instead of Eclipse cutting the end.

## 2. The mode table

Every state the mode label names, checked against Neovim 0.12.6 (`nvim_get_mode().mode`, which the
plug-in reads, so sub-modes are available). "Vim shows" is from Neovim's help except where marked.
The code is `util/ModeLabels`, the test `ModeLabelsTest`.

| State | Neovim reports | Vim shows | Label | Tooltip |
|---|---|---|---|---|
| Normal | `n` (`nt`, `ntT`) | nothing | `NORMAL` | `-- NORMAL --` |
| Waiting for a motion (after `d`) | `no`, `nov`, `noV`, `no^V` | nothing | `O-PENDING` | `-- OPERATOR PENDING --` |
| Ctrl-O from Insert | `niI` | `-- (insert) --` | `(INSERT)` | `-- (insert) --` |
| Ctrl-O from Replace / Virtual Replace | `niR` / `niV` | `-- (replace) --` / `-- (vreplace) --` (from memory of Vim's source; not in the help) | `(REPLACE)` / `(V-REPLACE)` | as Vim |
| Insert, also completion | `i`, `ic`, `ix` | `-- INSERT --` (then the completion message) | `INSERT` | `-- INSERT --` |
| Replace (also while `r` waits) | `R`, `Rc`, `Rx` | `-- REPLACE --` | `REPLACE` | `-- REPLACE --` |
| Virtual Replace (`gR`) | `Rv`, `Rvc`, `Rvx` | `-- VREPLACE --` | `V-REPLACE` | `-- VREPLACE --` |
| Visual by character / line / block | `v`, `V`, `^V` | `-- VISUAL --` etc. | `VISUAL`, `V-LINE`, `V-BLOCK` | as Vim |
| Visual via Ctrl-O from Insert | `v` (as plain) | `-- (insert) VISUAL --` | as plain Visual (not tracked) | |
| Visual via Ctrl-O from Select | `vs`, `Vs`, `^Vs` | not checked | `(S) VISUAL`, `(S) V-LINE`, `(S) V-BLOCK` | `-- (select) VISUAL --` etc. |
| Select by character / line / block | `s`, `S`, `^S` | `-- SELECT --` etc. | `SELECT`, `S-LINE`, `S-BLOCK` | as Vim |
| Select via Ctrl-O from Insert | `s` (as plain) | `-- (insert) SELECT --` | as plain Select (not tracked) | |
| Command line | `c`, `cr` | nothing | `COMMAND` | `-- COMMAND --` |
| Ex mode (`gQ`) | `cv`, `cvr` | nothing | `EX` | `-- EX --` |
| `-- More --` prompt | `rm` | the prompt | `MORE` | `-- More --` |
| Hit-enter / `:confirm` | `r` / `r?` | the prompt | `HIT-ENTER` / `CONFIRM` | `Press ENTER` / `-- CONFIRM --` |
| Shell command running | `!` | nothing | `SHELL` (practically never shown, below) | `-- SHELL --` |
| Terminal | `t` | `-- TERMINAL --` | `TERMINAL` | `-- TERMINAL --` |
| Keys go to Eclipse | (the plug-in's) | | `ECLIPSE` | `Keys go to Eclipse, not Neovim` |
| Recording a macro | the mode + register | `recording @a` | `NORMAL rec @a` | `-- NORMAL --  recording @a` |

Parentheses follow Vim's rule: they name the mode that comes back after one command. `(INSERT)` is
Normal for one command, then Insert; `(S) V-BLOCK` is Visual block for one command, then Select block.
So `(V-BLOCK)` would have said the wrong thing (Normal, then back to Visual block).

A running shell command (`:!sleep 3`) shows `NORMAL`, not `SHELL` (Neovim 0.12.6, with a UI
attached): Neovim sends `c`, then `n` and the command's echo, before it starts the
command, and answers nothing, not even `nvim_get_mode`, until it ends. The label stays for a mode a
script may still report.

Hit-enter and `:confirm` were not reached in a headless probe; with the plug-in's `ext_messages`
Neovim may never show them.

## 3. Widths and the cap

### How Eclipse's status bar lays out (read from JFace's bytecode)

`StatusLine.StatusLineLayout` gives every item the width it asks for (`StatusLineLayoutData.widthHint`)
plus a 3 pixel gap, and the message area on the left ("Building workspace", the selection count) what
is left. When the items ask for more than there is, the message area gives up width down to a third
of the bar; after that the **rightmost** items, which are the plug-in's, are cut, down to nothing.
Seen in a 738 pixel window: Eclipse's Writable, Insert and cursor position took 380 pixels, the message
area its 246, and the plug-in's labels were left 112.

### The rules (`util/StatusBarWidths`, `editor/StatusBarLabels`)

- Mode: as wide as its widest text as the font draws it (`GC.textExtent` over
  `ModeLabels.texts`), so the labels after it keep their place as the mode changes. Counting
  characters of the average width is not enough: mode texts are nearly all capitals, wider than the
  average, and the widest would be cut. While a macro is recorded the texts carry
  ` rec @a`, so the label widens only then.
- Cmd: 10 characters of the bar's bold font, the size of Vim's `'showcmd'` area.
- Statusline: the preference (default 60) in characters of the editor's **fixed-width** font family at
  the bar's size (`StatusBarLabels.statuslineFontData`; not at the editor's size, with which a change of the
  editor's font showed only once the bar was rebuilt), down to 10 under the cap. Neovim fits the `'statusline'` to columns; in a fixed-width font columns are
  exact, so `%=` puts the position at the label's right edge, as in Vim, and nothing is cut. (In the
  bar's proportional font wide letters pushed the end out of the label.)
- The cap: the smaller of the preference's share of the bar and what is free after the message area's
  third and every other item. Other items are every child after JFace's own first three (message
  area, cancel button, progress bar), whatever their widget type: Eclipse's heap status, for one, is
  a `Composite`. The statusline gives up width first; the mode and cmd keep theirs (when even those do
  not fit, JFace cuts from the right, as above).
- Everything is fitted again when the bar is resized; the statusline's width in characters goes to
  Neovim (`NeovimSession.setStatuslineColumns`, the first argument of `LuaScripts.STATE_SNAPSHOT`;
  0, nothing evaluated, until a label has been laid out). The status is read again once the width has
  stayed the same for 200 ms, so dragging a window's edge asks Neovim once. Each window has its own
  bar: the editor that becomes active brings its window's width along (`ModeStatusContribution.addTo`).
- While keys go to Eclipse only the mode label shows (`ECLIPSE`); the command and statusline
  mean nothing then.

The statusline keeps Neovim's spacing (`StatusLineFormat.oneLine`), so `%=` puts the position at the
right as in Vim. The status bar preferences are read once and again when one changes, not per key.

## 4. Where the code is

- `util/ModeLabels` (the table, tooltips, the texts for the width, the groups for the colors), `util/StatusBarWidths` (cap, free space,
  fitting, characters ↔ pixels), `editor/StatusBarLabels` (the widgets; SWT and JFace only, tested in
  `tests/swt`), `editor/ModeStatusContribution` (what the labels say; rebuilds the bar when the set
  of labels changes, including `ECLIPSE`, which shows even with the mode part off).
- Preferences `statusStatuslineWidth`, `statusMaxShare` on the Status Bar page.
- Tests: `ModeLabelsTest`, `StatusBarWidthsTest`, `StatusLineFormatTest`, `StateSyncTest`
  (the width reaches the snapshot), `state_snapshot_spec.lua` (Neovim fills and cuts to the width),
  `tests/swt` `StatusBarLabelsTest` (a real JFace bar: widths, cap, columns, tooltips), UI
  `StatusBarEndToEndTest` (the names and a recording, the fitted statusline, `ECLIPSE` with the mode off).

## 5. Not done: a status row in each editor

Vim shows each window's statusline under it; the plug-in has only the one status bar. Probes found
such a row feasible: a `StyledText` bottom margin reserves the row, Neovim's window and Eclipse's
scrolling and reveals all keep the text above it with no further code, and nothing in Eclipse
2024-12 or 2026-09 sets that margin. The line-number ruler ignores the margin, so the row would have
to sit on the editor's outer canvas, across the ruler and the text, re-placed on resize.
