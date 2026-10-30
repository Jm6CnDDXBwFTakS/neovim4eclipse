# The status bar: mode styles, widths and the cap

Status: built on `feature/status-bar` (from `release/0.1.10` at 03bafa5), 2026-10-29. A status row
at the bottom of each editor, like Vim's, was probed and is left for later (section 5).

The Eclipse status bar showed Vim's mode, pending command and statusline joined in one bold, centered
label 420 pixels wide. Longer text was cut with "...", and Neovim evaluated the statusline 300
columns wide, so its `%=` padding pushed the cursor position far past the label. The maintainer asked
for more room for the text, measured sensibly, and a choice of how the mode reads.

## 1. Decisions (with the maintainer, 2026-10-29)

- **Three labels**, one per part (mode, cmd, statusline), each after a separator, left-aligned and bold.
  A part turned off has no label and takes no space. *(decided)*
- **Widths in characters** of the bar's font (its average character width), as Eclipse's own status
  items measure theirs; the statusline's width is a preference (default 60), the others follow from
  their content. *(decided)*
- **A cap**: the labels together take at most a share of the bar (preference, default 40%). *(decided)*
- **A tooltip** on every label with its whole text, and for the short mode styles what they mean
  (`-VR- = VREPLACE`). *(decided)*
- **A mode style**: Full (`-- INSERT --`), Short (`INSERT`) or Letters (`-I-`), the letters picked one
  by one (section 2). *(decided)*
- **Neovim fits the statusline**: it is evaluated at the label's width in columns (`maxwidth`), so `%<`
  shortens the file name and `%=` pads to the label, instead of Eclipse cutting the end. *(decided)*

## 2. The mode table

Every state the mode label names, checked against Neovim 0.12.6 (`nvim_get_mode().mode`, which the
plug-in reads, so sub-modes are available). "Vim shows" is from Neovim's help except where marked.
The code is `util/ModeLabels`, the test `ModeLabelsTest`.

| State | Neovim reports | Vim shows | Full | Short | Letters |
|---|---|---|---|---|---|
| Normal | `n` (`nt`, `ntT`) | nothing | `-- NORMAL --` | `NORMAL` | `-N-` |
| Waiting for a motion (after `d`) | `no`, `nov`, `noV`, `no^V` | nothing | `-- OPERATOR --` | `OPERATOR` | `-O-` |
| Ctrl-O from Insert | `niI` | `-- (insert) --` | `-- (insert) --` | `(INSERT)` | `-(i)-` |
| Ctrl-O from Replace / Virtual Replace | `niR` / `niV` | `-- (replace) --` / `-- (vreplace) --` (from memory of Vim's source; not in the help) | same | `(REPLACE)` / `(VREPLACE)` | `-(r)-` / `-(vr)-` |
| Insert, also completion | `i`, `ic`, `ix` | `-- INSERT --` (then the completion message) | `-- INSERT --` | `INSERT` | `-I-` |
| Replace (also while `r` waits) | `R`, `Rc`, `Rx` | `-- REPLACE --` | `-- REPLACE --` | `REPLACE` | `-R-` |
| Virtual Replace (`gR`) | `Rv`, `Rvc`, `Rvx` | `-- VREPLACE --` | `-- VREPLACE --` | `VREPLACE` | `-VR-` |
| Visual by character / line / block | `v`, `V`, `^V` | `-- VISUAL --` etc. | same | `VISUAL` etc. | `-V-`, `-VL-`, `-VB-` |
| Visual via Ctrl-O from Insert | `v` (as plain) | `-- (insert) VISUAL --` | as plain Visual *(decided: not tracked)* | | |
| Visual via Ctrl-O from Select | `vs`, `Vs`, `^Vs` | not checked | `-- (select) VISUAL --` etc. | `(SELECT) VISUAL` etc. | `-(s)V-`, `-(s)VL-`, `-(s)VB-` |
| Select by character / line / block | `s`, `S`, `^S` | `-- SELECT --` etc. | same | `SELECT` etc. | `-S-`, `-SL-`, `-SB-` |
| Select via Ctrl-O from Insert | `s` (as plain) | `-- (insert) SELECT --` | as plain Select *(decided: not tracked)* | | |
| Command line | `c`, `cr` | nothing | `-- COMMAND --` | `COMMAND` | `-C-` |
| Ex mode (`gQ`) | `cv`, `cvr` | nothing | `-- EX --` | `EX` | `-EX-` |
| `-- more --` prompt | `rm` | the prompt | `-- MORE --` | `MORE` | `-RM-` |
| Hit-enter / `:confirm` | `r` / `r?` | the prompt | `-- HIT-ENTER --` / `-- CONFIRM --` | `HIT-ENTER` / `CONFIRM` | `-R-` (as Replace, by choice) / `-R?-` |
| Shell command running | `!` | nothing | `-- SHELL --` | `SHELL` | `-!sh-` |
| Terminal | `t` | `-- TERMINAL --` | `-- TERMINAL --` | `TERMINAL` | `-T-` |
| Keys go to Eclipse | (the plug-in's) | | `-- ECLIPSE --` | `ECLIPSE` | `-E-` *(decided)* |
| Recording a macro | the mode + register | `recording @a` | `-- NORMAL --  recording @a` | `NORMAL  recording @a` | `-N- rec @a` |

Before this change Virtual Replace showed `-- REPLACE --`, the Ctrl-O states `-- NORMAL --`, and the
prompts and the shell the raw strings (`-- RM --`, `-- ! --`). Hit-enter and `:confirm` were not
reached in a headless probe; with the plug-in's `ext_messages` Neovim may never show them. The
`-T-` letters were not discussed (a terminal never shows in an Eclipse editor); `-E-` was confirmed
by the maintainer after the build.

## 3. Widths and the cap

### How Eclipse's status bar lays out (read from JFace's bytecode, 2026-10-29)

`StatusLine.StatusLineLayout` gives every item the width it asks for (`StatusLineLayoutData.widthHint`)
plus a 3 pixel gap, and the message area on the left ("Building workspace", the selection count) what
is left. When the items ask for more than there is, the message area gives up width down to a third
of the bar; after that the **rightmost** items, which are the plug-in's, are cut, down to nothing.
Seen in a 738 pixel window: Eclipse's Writable, Insert and cursor position took 380 pixels, the message
area its 246, and the plug-in's labels were left 112.

### The rules (`util/StatusBarWidths`, `editor/StatusBarLabels`)

- Mode: as wide as the widest text of the chosen style as the font draws it (`GC.textExtent` over
  `ModeLabels.texts`), so the labels after it keep their place as the mode changes. Counting
  characters of the average width was not enough: mode texts are nearly all capitals, wider than the
  average, and the widest were cut (review, 2026-10-29). While a macro is recorded the texts carry
  `  recording @a` (Letters ` rec @a`), so the label widens only then.
- Cmd: 10 characters of the bar's bold font, the size of Vim's `'showcmd'` area.
- Statusline: the preference (default 60) in characters of the editor's **fixed-width** font, down to
  10 under the cap. Neovim fits the `'statusline'` to columns; in a fixed-width font columns are
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
- While keys go to Eclipse only the mode label shows (`-- ECLIPSE --`); the command and statusline
  mean nothing then.

The statusline keeps Neovim's spacing (`StatusLineFormat.oneLine`), so `%=` puts the position at the
right as in Vim. The status bar preferences are read once and again when one changes, not per key.

## 4. As built

- `util/ModeLabels` (the table, tooltips, widths per style), `util/StatusBarWidths` (cap, free space,
  fitting, characters ↔ pixels), `editor/StatusBarLabels` (the widgets; SWT and JFace only, tested in
  `tests/swt`), `editor/ModeStatusContribution` (what the labels say; rebuilds the bar when the set
  of labels changes, including `-- ECLIPSE --`, which shows even with the mode part off).
- Preferences `statusModeStyle`, `statusStatuslineWidth`, `statusMaxShare` on the Status Bar page.
- `VimModes.statusText` and `StatusLineFormat.compose` are gone (one table, no joined text).
- Tests: `ModeLabelsTest`, `StatusBarWidthsTest`, `StatusLineFormatTest`, `StateSyncTest`
  (the width reaches the snapshot), `state_snapshot_spec.lua` (Neovim fills and cuts to the width),
  `tests/swt` `StatusBarLabelsTest` (a real JFace bar: widths, cap, columns, tooltips), UI
  `StatusBarEndToEndTest` (styles, the fitted statusline, `-- ECLIPSE --` with the mode off).

## 5. Later: a status row in each editor

Vim shows each window's statusline under it. Probes on 2026-10-29 (throwaway UI tests) found it
feasible: a `StyledText` bottom margin reserves the row, Neovim's window and Eclipse's scrolling and
reveals all keep the text above it with no further code, and nothing in Eclipse 2024-12 or the
installed 2026-09 sets that margin. The line-number ruler ignores the margin, so the row must sit on
the editor's outer canvas, across the ruler and the text, re-placed on resize. The full data is in
the maintainer's doc "Status Row Probe Findings".
