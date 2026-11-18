# User guide

How each part of Neovim for Eclipse behaves, section by section. The [README](../README.md) has the
overview: how to install, the first five minutes, the [Settings](../README.md#settings) and the
[Known limitations](../README.md#known-limitations).

## Starting Neovim

Neovim starts with the first text editor that opens, whether you open it or Eclipse reopens it at
launch, and again with the next one after *Neovim → Restart Neovim...* or after Neovim quit or
stopped. Eclipse waits for it, with the busy cursor: usually well under a second, plus your
`init.lua`. What you type meanwhile is kept, and reaches Neovim in order once it is ready, so `dd`
typed while it starts deletes a line. Keys typed before that, while Eclipse is still opening the
editor, are Eclipse's own, as in an editor without Neovim.

## Status bar

*Window → Preferences → Neovim → Status Bar* chooses which parts of Vim's status information appear in the
Eclipse status bar; changes apply immediately:

| Item | Shows | Default |
|------|-------|---------|
| `mode` | `INSERT`, `V-BLOCK`, `INSERT rec @q` while a macro records | on |
| `cmd` | the pending command as Vim's `showcmd` shows it: `2d`, `"a`, `2f` | on |
| `statusline` | Neovim's own `'statusline'` (your config's, or Vim's default: file, line,col, position) | off |

Each part has a label of its own, in that order. Hovering one shows its whole text. `ECLIPSE`
shows while Neovim mode is off, even with `mode` turned off. Only the enabled parts are requested from
Neovim.

The mode uses the names of lualine and most Neovim statuslines: `NORMAL`, `INSERT`, `VISUAL`,
`V-LINE`, `V-BLOCK`, `SELECT`, `S-LINE`, `S-BLOCK`, `O-PENDING` (after `d`), `REPLACE`, `V-REPLACE`
(`gR`), `COMMAND`, `EX`. Parentheses say where one command after `Ctrl-O` goes back to, as in Vim:
`(INSERT)` is Normal mode for one command, then Insert again; `(S) V-BLOCK` is Visual block for one
command, then Select block again. Hovering the mode shows Vim's own wording (`-- VISUAL BLOCK --`).
The full table is in [docs/design/STATUS_BAR.md](design/STATUS_BAR.md).

*Statusline width* (characters, default 60) is how wide the statusline is; it is drawn in the
editor's fixed-width font, as in Vim, at the status bar's size (it does not follow the editor's text size). Neovim fits your `'statusline'` into exactly that many columns: `%<` shortens the file name and `%=` pushes the
position to the right. *At most this share of the status bar* (default 40%) keeps room for Eclipse's
own messages: in a narrow window the statusline gets narrower first.

Messages and errors are not in the status bar: they appear in the editor (next section).

## Messages and errors

Neovim's latest message or error appears in a line along the bottom of the editor, where Vim shows
its messages and where the `:` box opens:

- **Errors** in the theme's error color: `E486: Pattern not found: x`, `E37: No write since last
  change`, `Can't find file "x"`.
- **Other messages** in the editor's text color: `3 lines yanked`, `3 substitutions on 3 lines`.

As in Vim, the message stays until the next key; a click also hides it. A message too long for the
line ends in `...`. Its tooltip shows all of it, and `:messages` lists the recent ones in a
[page editor](#the-page-editors-help-messages-checkhealth).

The line shows every message of one line that Neovim gives, whatever printed it: a key, a `:` command
(`:echo "hi"`, `:pwd`), a mapping, a timer or an autocommand; and the plug-in's own errors from the file
and tab commands. Messages of more than one line open the output box described under
[Command line](#command-line---), as Vim would show them above a "Press ENTER" prompt: two
errors at once, a failed save with Neovim's `Error in BufWriteCmd Autocommands` line above it, a Lua
error with its traceback.

*Window → Preferences → Neovim → Flash the editor on errors* (off by default) also tints the editor for
a moment on each error, like Vim's `'visualbell'`, for when your eyes are on the cursor.

## Problems (`]d`, `[d`)

The errors, warnings and infos Eclipse shows in an open file (the ones it underlines: the Java
compiler's, a builder's, a language server's) are also Neovim *diagnostics* of that file's buffer, so
Neovim's own keys move between them:

| Key | Goes to |
|-----|---------|
| `]d` / `[d` | the next / previous problem in the file; with a count, `3]d` skips two |
| `]D` / `[D` | the last / first problem |

They are Neovim's default mappings: the plug-in maps nothing, so your own mapping of these keys in
`init.lua` wins. Each jump is in the jumplist (`Ctrl-O` comes back) and works in a macro. The
message line then shows the problem's message (its first line), as Eclipse's status bar does after
*Next Annotation* (`Ctrl+.`); `K` right after opens Eclipse's hover for it, with its quick fixes in
a Java editor. Example: on line 1 of a file with an unused import on line 3, `]d` goes to line 3
and shows `The import java.util.List is never used`.

- **Nothing more is drawn**: Eclipse already underlines the problems and marks them in the rulers.
- **Tasks** (`TODO` comments) are not problems, so `]d` does not stop on them; nor on spelling
  mistakes, bookmarks or search results.
- **The list follows Eclipse**: a problem fixed (or found) shows in Neovim a moment after Eclipse
  shows it (about 300 ms after the editor's annotations change).
- **Your own diagnostics** (nvim-lint, a language server started in `init.lua`) stay as they are;
  `]d` stops on theirs and Eclipse's alike, as Neovim does with several sources. The message after
  a jump is shown for any of them. A `vim.diagnostic.config({ jump = { on_jump = ... } })` of your
  own replaces the plug-in's message.
- **To turn it off**: `vim.g.neovim4eclipse_problems = 0` in `init.lua`, then *Neovim → Restart
  Neovim...* (the variable is read once, when Neovim starts). Off, Neovim has
  none of Eclipse's problems; useful when your own diagnostics cover the same files. In a file
  with no diagnostics at all, `]d` then says
  `]d: Eclipse's problems are not sent to Neovim (vim.g.neovim4eclipse_problems = 0)` instead of
  Neovim's *No more valid diagnostics to move to*, so that you can tell the two cases apart.
  Only those words change: with diagnostics of your own, `]d` is Neovim's, and a `]d` you mapped
  yourself is left alone.

How it works: [docs/design/DIAGNOSTICS.md](design/DIAGNOSTICS.md).

## The quickfix list (`:Eproblems`, `]q`)

`]d` walks one file's problems; Neovim's *quickfix list* walks a list of places across files. Two
commands fill it with what Eclipse knows about the whole workspace:

| Command | Fills the quickfix list with |
|---------|------------------------------|
| `:Eproblems` | every error, warning and info in the workspace (the Problems view's), and goes to the first |
| `:Eproblems warning` | errors and warnings only; `:Eproblems error`, errors only |
| `:Etasks` | the tasks (`TODO`, `FIXME` comments a builder found) whose tag you chose, and goes to the first |
| `:Eproblems!`, `:Etasks!` | the same, without going to the first; the message line says how many |

Then Neovim's own quickfix commands walk it, typed, mapped or in a macro: `]q` / `[q` (next /
previous), `]Q` / `[Q` (last / first), `:cnext`, `:cprevious`, `:cc 3`, `:cnfile`, `:clist`,
`:colder`. An entry in a file without an editor opens its editor; an editor with several pages
(the plug-in manifest editor of `plugin.xml`) turns to the file's text page. Example: errors in `A.java`
(open) and `B.java` (closed): `:Eproblems` goes to `A.java`'s error and shows
`(1 of 2) error: Type mismatch: ...`; `]q` opens `B.java` on its error's line.

- **Columns**: a file with an open editor has each entry at the problem's column. A closed file's
  entries have the line only (the cursor goes to the line's first non-blank); once its editor is
  open, they get their columns, and the jump that opened it moves on to the column a moment later.
- **The list follows Eclipse** while it is the current list: a build that finds or fixes problems,
  a problem the Java editor finds while you type, a task you add. The entry you are on stays the
  current one. A list of your own (`:vimgrep`) is never touched; `:colder` back to Eclipse's list,
  and it follows Eclipse again from then on. Running `:Eproblems` again reuses its list (and makes
  it current) instead of adding another to Neovim's ten.
- **`:copen` and `:cwindow`** show Eclipse's Problems view for `:Eproblems`'s list, the Tasks view
  for `:Etasks`'s; for any other list, its `:clist` in the output popup. Neovim's own quickfix
  window cannot be shown (Eclipse shows one Neovim window per editor); `:lopen` shows the
  location list's `:llist`.
- **Task tags**: set them in `init.lua`, comma-separated, or `'*'` for every task:

  ```lua
  vim.g.neovim4eclipse_task_tags = 'JM6,FIXME' -- tasks whose comment starts with JM6 or FIXME
  ```

  The variable is read each time `:Etasks` runs, so `:let g:neovim4eclipse_task_tags = 'TODO'`
  works at once. Without tags `:Etasks` says how to set them. A tag counts as a word at the start of
  the task's text: `JM6` takes `// JM6 check this`, not `// JM67` or `// see JM6`. Choosing a tag of
  your own keeps other people's `TODO`s out of the list. Only builders make tasks (in Java, the
  compiler's *Task Tags*), so a `TODO` in a plain text file is not one.

How it works: [docs/design/DIAGNOSTICS.md](design/DIAGNOSTICS.md), section 5.

## Folding (`zo`, `zc`, `zM`)

In an editor that folds (the Java editor; the Generic Editor with a language server), Eclipse's
folds are also Neovim's, so Vim's fold commands work on them and the two always show the same folds
open and closed:

| Key | Does |
|-----|------|
| `zo` / `zc` / `za` | open / close / toggle the fold at the cursor; `zO`, `zC`, `zA` all the way down |
| `zM` / `zR` | close / open every fold; `zm`, `zr` one level |
| `zv`, `zx`, `zX` | show the cursor's line; reset to `'foldlevel'` |
| `zj` / `zk`, `[z` / `]z` | to the next / previous fold; to the current fold's start / end |
| `zn` / `zN` / `zi` | folding off / on / toggled, in Neovim and Eclipse alike |

Neovim runs them all itself; the plug-in only copies Eclipse's fold regions into Neovim and keeps
the open and closed state the same both ways: a fold collapsed with the ruler's `−` or
`Ctrl+Numpad -` is closed in Neovim too.

- **A closed fold is one line.** `j` and `k` step over it. The caret stands on the line Eclipse
  shows for it: for a Java method with Javadoc that is the signature, not the comment above it.
- **What opens a fold is your `'foldopen'`**, as in Vim. By default `j` or `G` into a closed fold leave
  it closed, and a search, `%`, a mark or `u` opens it. Before 0.1.9 any move into folded
  code opened it; `set foldopen=all` in `init.lua` gives that back.
- **Eclipse decides where the folds are.** `zf`, `zF`, `zd`, `zD`, `zE` and `:fold` show
  `zf: Eclipse defines the folds in this editor` in these editors, and change no fold: Eclipse's
  next reconcile would replace any fold made by hand. To add one where
  Eclipse has none, the Java editor folds between `// region` and `// endregion` comments when
  *Window → Preferences → Java → Editor → Folding* says so (custom folding regions).
- **`dd` on a closed fold, then `u`**, brings the lines back at once and the fold a moment later,
  open (Neovim does not keep manual folds through an undo; Eclipse makes the fold again).
- **Editors that cannot fold** (the plain Text Editor) turn Neovim's folding off in its window, so
  a `'foldmethod'` of yours cannot hide lines Eclipse shows.
- **To leave Neovim's folding alone**: `vim.g.neovim4eclipse_folds = 0` in `init.lua`. Eclipse then
  folds as it did before 0.1.9: the `z` commands do nothing to its folds, and moving into folded
  code opens it. The setting is read once when Neovim starts: changing it needs a restart of
  Neovim (*Neovim → Restart Neovim...*).
- **For your config**: the `User` autocommand `Neovim4EclipseFolds` fires when the closed folds
  change, with `data = { buf = 3, closed = { ... } }`.
- **The caret can vanish after a fold opens or closes** (seen on Linux, XFCE on X11): it stays in
  the right place but is not drawn until it next moves. This is Eclipse's own behavior: it happens
  with `Ctrl+Numpad -`/`+` in Eclipse mode, and in an Eclipse without this plug-in. Any motion
  brings the caret back.

## The editor's size, scrolling and word wrap (`H`, `L`, `Ctrl-D`, `zz`)

Neovim's window is the size of the editor's text area: as many lines and columns as the editor
shows, whatever you have in Neovim besides the text (status line, `set number`, signs). So the
commands that work on "the screen" work on what you see: `H`, `M` and `L` go to the editor's top,
middle and last full line, `Ctrl-D` scrolls half the editor's height, and a resized window, part
or font (`Ctrl+=`) changes `&lines` and `&columns` with it.

The editor scrolls where Neovim's window scrolls: `Ctrl-E`, `Ctrl-Y`, `Ctrl-D`, `zz`, `zt`, `zb`, a
jump such as `150G`, and your `'scrolloff'`, which keeps lines around the cursor in the editor too.
In normal and visual mode the mouse wheel and the touchpad scroll Neovim's window, by as many lines
as your system reports (its speed and momentum, so as far as Eclipse would scroll; Neovim's
`'mousescroll'` is not used), and the cursor is dragged into the view as `Ctrl-E` drags it,
`'scrolloff'` lines from the edge: the caret moves when you scroll it off the screen, as in Vim.
The wheel with Ctrl or Shift held, and the sideways wheel, stay Eclipse's. Scrolling the editor otherwise (the
scroll bar, Eclipse showing a search hit) scrolls Neovim's window to the same line and drags the
cursor the same way. In insert mode Eclipse scrolls alone and the caret stays where you type; at
`Esc` the view stays where you scrolled, and a caret you scrolled off the screen is dragged into it,
as Neovim's own wheel would have dragged it. When an editor attaches, Neovim's window is
scrolled to the editor's view too ([design](design/VIEWPORT.md)).

If the wheel or touchpad scrolls too far or too little, start Eclipse with
`-Dneovim4eclipse.logLevel=FINE` (a line after `-vmargs` in `eclipse.ini`), scroll a little, and
send the `wheel count=… detail=…` lines from the Error Log with your system and device in an issue.

- **Word wrap is one setting.** Eclipse's word wrap (`Alt+Shift+Y`; `Cmd+Option+Y` on macOS) and Neovim's `'wrap'` follow
  each other, for that editor. When an editor attaches, Eclipse's wins: set the default with
  *Window → Preferences → General → Editors → Text Editors → Enable word wrap when opening an
  editor*, not with `set wrap` in `init.lua`.
- **While word wrap is on, `'linebreak'` stays on** (with `'breakindent'` off and `'showbreak'`
  empty), because Eclipse wraps at word boundaries and draws no marker; otherwise a long line would
  take a different number of rows on each side, and `gj` or `L` would land on another row than the
  one you see. A change of one of them shows a message once per Neovim session and is set back;
  your value applies again when wrap is off. Eclipse measures pixels and Neovim cells, so a line can
  still wrap one word apart now and then.
- **To keep Neovim's own fixed screen** (120 × 40, as before 0.2.0): `vim.g.neovim4eclipse_viewport = 0`
  in `init.lua`. The setting is read once when Neovim starts (*Neovim → Restart Neovim...*).
- Lines made taller by code minings (the Java editor's reference counts) are not measured: there
  `L` can land a line or two below the editor's last line.
- With `vim.g.neovim4eclipse_folds = 0` Neovim does not know Eclipse's folds, so where code is
  folded `L`, `Ctrl-F` and `zb` fall short by the folded lines.
- At the end of a file Neovim can scroll further than Eclipse (`Ctrl-E` until the last line is at
  the top); the editor stops at its own end, and `H` then goes to a line above the editor's top.

## Splits (`:split`, `:vsplit`, `Ctrl-W`)

Each Vim window is an Eclipse editor here, so a Vim split is an editor beside this one: `:split`
and `:vsplit` put a second editor on the file into a new editor stack, as dragging its tab to the
edge of the editor area would, and the cursor goes there. Neovim drives each editor with a window
of its own, so the two have their own cursor and scroll position, and share the text and the undo
history, as two Vim windows on one buffer. Close a split with `:q` (or `Ctrl-W q`, or its tab's
close button); its stack goes with its last editor. A file opened from Eclipse (Package
Explorer, *Open Type*) goes into the stack you were last in, as in Eclipse.

| Command | Eclipse does |
|---------|--------------|
| `:sp[lit]`, `:sv[iew]`, `Ctrl-W s` / `S` / `Ctrl-S` | a second editor on this file (*Window › Editor › Clone*) in a new stack above, or below with `'splitbelow'` |
| `:vs[plit]`, `:vert[ical] split`, `Ctrl-W v` / `Ctrl-V` | the same to the left, or to the right with `'splitright'` |
| `:sp file`, `:vs file`, `:sf[ind] file`, `:sb[uffer] N` | `file` (named as `:e` names it; `:sfind` looks in `'path'`), or buffer `N`'s file, in a new editor beside this one, even if another editor has it open. A file that does not exist opens as a new one, as for `:e` |
| `:new`, `:vne[w]`, `Ctrl-W n` | a new untitled editor beside this one (with a name, `:new file` is `:split file`) |
| `Ctrl-W ^`, `Ctrl-W f` | a split of the alternate file (with a count, of buffer N), or of the file under the cursor, found in `'path'` |
| `Ctrl-W h` / `j` / `k` / `l` (and `Ctrl-H`, the arrows, `Backspace`) | the editor to the left, below, above or to the right, the one level with the cursor; with a count, that many editors on |
| `Ctrl-W w` / `W`, `t` / `b`, `p` | the next or previous editor, top-left to bottom-right (with a count, editor N), the first or last one, the one you were in before |
| `Ctrl-W o`, `:on[ly]` | this editor alone on screen: the other stacks' editors become tabs of this stack. Nothing is closed, since an editor may hold unsaved changes |
| `Ctrl-W +` / `-`, `Ctrl-W _` | N lines taller or shorter (1 without a count); N lines high, or as high as it can be. `:res[ize] +N`, `:res -N`, `:res N` too |
| `Ctrl-W >` / `<`, `Ctrl-W \|` | the same for the width, in characters; `:vert[ical] res[ize] N` too |
| `Ctrl-W =` | all editors as equal as they can be |
| `Ctrl-W x`, `Ctrl-W r` / `R` | swap this editor with the next one in its row or column (the cursor stays where it was on screen); move every editor of the row or column one place on |
| `:wincmd {key}` | the same as `Ctrl-W` and the key (`:3wincmd l`) |

`:aboveleft`, `:belowright`, `:topleft` and `:botright` in front of a split put it above or below
(left or right) this editor for that one command. A size given with the split (`:5split`) is not
used: the new editor takes half of this one.

**`Ctrl+W`** goes to Neovim by default, for the `Ctrl-W` keys above; Eclipse's *Close* is still
`Ctrl+F4` (`Cmd+W` on macOS), or `:q`. To have `Ctrl+W` close the editor again, clear its Normal box
under *Window → Preferences → Neovim → Ctrl Keys*; `:wincmd` and the `:` commands work either way.
Your own mappings of a `Ctrl-W` key win.

**Eclipse's *Toggle Split Editor*** (*Window › Editor*, `Ctrl+_` one above the other, `Ctrl+{` side
by side) makes the same Vim split in an editor Neovim drives, on the side `'splitbelow'` or
`'splitright'` says; pressed while more than one editor is on screen, it is `:only`. In an editor
in Eclipse mode, or one Neovim does not drive, it makes Eclipse's two halves of the editor, as
always. Such halves (from a workspace that had them) are windows too: `Ctrl-W h/j/k/l`, the sizes
and `:only` work on them; `Ctrl-W x` and `r` do not, and say so.

**Vim's tabs are Eclipse's editor tabs** here (each editor is a Neovim window in a hidden tab page
of its own), so the tab commands work on the editor tabs of this stack:

| Command | Eclipse does |
|---------|--------------|
| `:tabe[dit] file`, `:tabf[ind] file`, `:tab split`, `:tab sb N` | the file in a new editor tab of this stack, named as for `:split` (a new editor even if another has it open) |
| `:tabnew`, `:tabe[dit]` alone, `:tab new` | a new untitled editor tab |
| `Ctrl-W T` | this editor out of its split, as a tab of the first other stack on screen (top left) |
| `:tabc[lose] 2`, `:tabc $`, `:tabc +`, `:tabc -2` | close that editor tab of this stack (counted as `:tabn` counts); Eclipse asks about changes, `:tabclose!` discards them |
| `:tabo[nly]`, `:tabo 2` | close the other editor tabs of this stack (Eclipse's *Close Others*), keeping this one or tab 2. Eclipse asks once about the changes that would be lost; a clone of the tab that stays closes without asking, its changes stay there. Splits in other stacks stay |

Write the tab after `:tabclose` or `:tabonly`: a count in front (`:2tabclose`, `:$tabclose`) is
refused, since Neovim turns it into one of its hidden tab pages before the plug-in sees it.

Refused, with a message: `:tabonly!`, which would discard every other tab's changes at once (Vim's
keeps their buffers hidden; `:tabonly` asks about them), and the splits that jump first (`:stag`,
`Ctrl-W ]`, `Ctrl-W F`). A window a plug-in opens in Neovim beside an editor's
(its own `vim.cmd.split()`) is closed again, with a message: Eclipse would not show it.

After a restart, Eclipse brings the stacks back as it does any layout you dragged.

## Command line (`:`, `/`, `?`)

`:`, `/` and `?` open Neovim's own command line, shown in a single text box near the bottom edge of
the editor, as wide as the editor minus a small padding and in the editor's font. Every key you type
in it goes to Neovim, so it behaves exactly like Neovim's command line: Enter runs it, Esc or
Backspace on the empty line cancels, Up/Down browse Neovim's history (the same as in a terminal,
kept between sessions), `Ctrl-R` inserts a register, and your command-line mappings and
abbreviations apply. `:` from visual mode gets `:'<,'>` from Neovim. Clicking elsewhere in Eclipse
cancels the line; switching to another application or input method keeps it. In Ex mode (`gQ`),
where Vim cannot cancel a line, a click keeps it too, and a click in the editor leaves the keys in the
box; `:vi` leaves Ex mode. In the box the `Ctrl`
letters ticked in the preferences ([Preferences](#preferences), *Ctrl Keys*) go to Neovim's line (`Ctrl-R`, `Ctrl-U`, `Ctrl-N`),
except `Ctrl-V`, which pastes, and `Ctrl-C` with text selected, which copies (on macOS, Cmd-V and Cmd-C
do that, and `Ctrl-V` is Neovim's). Shift+Insert pastes too, and text from an input method (IME) goes
in the same way: the text you are composing shows in the box, and reaches Neovim once you keep it
(Enter while composing is the input method's, not the command's). On macOS, holding a letter
shows its accent menu: pick with a digit (with Shift where the digits need it, as on AZERTY), or the
arrows and Enter, or type the accent as two keys (Option+E, then E, makes `é`). Clicking an accent works only within a moment of letting go of the
key: later clicks are lost to a bug in SWT, Eclipse's widget toolkit (its macOS `Text` asks for the
event being handled, and after a pause there is none). With press-and-hold turned off, for every
application or for Eclipse alone (`defaults write <Eclipse's id> ApplePressAndHoldEnabled -bool false`,
the id being `CFBundleIdentifier` in `Eclipse.app/Contents/Info.plist`), a held letter repeats. A message that arrives while you type (a timer's `:echo`) shows above the box.

The box opens for every command line Neovim opens: a mapping in your `init.lua`
(`vim.keymap.set('n', ';', ':')`, `vim.keymap.set('n', '<Space>s', ':%s/')`; in Vimscript
`nnoremap ; :`), a count before `:` (`2:` gives `:.,.+1`), `!` with a motion (`!!` gives `:.!`), a
search inside an operator (`d/`), and prompts such as `input()` and `confirm()`.

Tab completes the line as Neovim does (commands, options and their values, buffers, help tags,
variables, functions, file names), following your `'wildmode'`; Shift+Tab goes back. A line above the
box lists the candidates, with the selected one highlighted. File names complete from the editor's
project folder (the editor's Neovim window works there, `:lcd`), and `:e` opens what Tab completes:
a relative name is relative to that folder, as in Neovim. The folder is set once, when the editor's window
opens, so a `:lcd` you type stays with that editor. An untitled document (*File → New → Untitled
Text File*) gets the root of the project selected in the Project Explorer or Package Explorer (the
one you right-clicked to make it), so `:w notes.txt` saves it there; with none selected, the folder
of the editor that was active before it, or your home folder if none was. One made with `:enew`
gets the folder of the editor you typed it in, a `:lcd` there included, as a Vim window keeps its folder. With `'autochdir'` set in your `init.lua`, Neovim moves each window to its file's folder
instead.

While you type a search, the editor follows Neovim's match (`'incsearch'`); Esc goes back to where
you were. The matches are highlighted as in Neovim: every match of what you type, then of the last
search (`'hlsearch'`) until `:noh`, behind the text in an amber (darker in a dark editor;
change it under *Window → Preferences → General → Appearance → Colors and Fonts → Neovim for
Eclipse → Search match*), a box around the match under the caret. `vim.g.neovim4eclipse_search_highlight = 0` in `init.lua` turns this off
([design](design/SEARCH_HIGHLIGHTING.md)).

The editor also shows two kinds of highlight you set up in your `init.lua`, in the colors of their
Neovim group (its background, and its text color when the group has one): the flash over the text
you yank, which `vim.hl.on_yank` makes, and your window matches (`:match`, `:2match`, `matchadd()`,
`matchaddpos()`). They lie over search matches, as in Neovim. Neovim's matching-bracket highlight
(matchparen) is left out: the code editors mark the matching bracket themselves.
`vim.g.neovim4eclipse_highlights = 0` turns these off, and the [jump labels](#jump-labels) with them. For example:

```lua
-- Flash the yanked text, as kickstart.nvim does
vim.api.nvim_create_autocmd('TextYankPost', {
  callback = function() vim.hl.on_yank() end,
})
-- Mark trailing white space in red, in every window (a :match belongs to one window)
vim.api.nvim_create_autocmd('BufWinEnter', {
  callback = function() vim.cmd([[match Error /\s\+$/]]) end,
})
```

A group with no background of its own (`ErrorMsg` sets only a text color) shows nothing.

A substitution (`:%s/old/new/g`) changes the text when you press Enter; Neovim's live
preview of it (`'inccommand'`) is not shown in the editor.

Neovim's command-line window (`q:` for commands, `q/` and `q?` for searches, or `Ctrl-F` while
typing on the `:` line) opens over the bottom of the editor, where the box opens: the history, one
line each, and an empty line at the end for a new one. It is Neovim's own window, so every key
goes to Neovim, in every mode: move with `j` and `k`, search with `/`, edit a line in insert mode,
and press Enter to run the line under the cursor; `:q` closes it without running anything. A click
anywhere else in Eclipse closes it too, without running anything; the click goes where it was
aimed. Its colors are the editor's, with Neovim's highlight groups on top; Neovim's `'background'`
follows Eclipse's theme (light or dark), so a colorscheme with both variants picks the one that
matches; when Eclipse changes it, Neovim reloads the colorscheme, so highlight changes your
config makes after its `:colorscheme` are lost then (make them in a `ColorScheme` autocommand to
keep them). The left button and the wheel are Neovim's inside it, in every mode: a click puts the
cursor on a history line, a double click selects a word, dragging selects, and the wheel scrolls
(your `'mouse'`, `'mousescroll'` and mouse mappings apply; with `'mouse'` empty, a click there
does nothing). The right and middle buttons do nothing there. An input method's composed text (Japanese, the macOS accent menu) does not reach it: the
`:` box has that.

File and tab commands are carried out by Eclipse, everything else by Neovim.

| Command | Eclipse does |
|---------|--------------|
| `:w`, `:up[date]` | save the editor, with Vim's message (`"A.java" 12L, 340B written`); these are Neovim's own writes, see below |
| `:wa[ll]` | *File > Save All* |
| `:q[uit]` | close the editor (Eclipse asks about changes; `:q!` discards them). When another editor shows the same file (a split, a clone), it closes without asking, as Vim closes one of two windows: the changes stay in the other |
| `:clo[se]`, `:tabc[lose]` | close the editor, as `:q`: the editor is its Neovim window and tab (`:close!` discards changes). `:tabclose 2` closes another editor tab (see [Splits](#splits-split-vsplit-ctrl-w)). In a floating window you entered (hover documentation), `:q`, `:close`, `Ctrl+W q` and `Ctrl+W c` close that window, as in Neovim |
| `:bd[elete]`, `:bw[ipeout]`, `:bun[load]` | close the editor, as `:q` (each buffer is an editor; `:bd!` discards changes). The buffer is kept, as after Vim's `:bd`: `:b 3` (its number) or Ctrl-^ opens the file again |
| `:bd N`, `:bd name` (also `:bw`, `:bun`) | close the editor of buffer `N` or of the buffer called `name` (a unique part is enough, as with `:b`); several may follow (`:bd 3 other.txt`). A range closes the editors of the buffers it covers (`:3,5bd`); `:%bd` closes all editors |
| `:qa[ll]`, `:quita[ll]` | close all editors (`:qa!` without asking) |
| `:wq`, `:x[it]`, `:exi[t]` | save, then close; a failed save keeps the editor open, with `!` too (`!` only lets a write through, as in Vim) |
| `:wqa[ll]`, `:xa[ll]` | save all, then close all; if one cannot be saved, all stay open (`:wqa!` too) |
| `:e[dit] file` | open `file`, as Neovim's `:e` names it: relative to the editor's project folder (the window's folder, `:lcd`), `./file` too; `%:h/file` is beside the current file, `#` the alternate file, and `~` and absolute paths work. A file that does not exist opens as a new, empty editor (`"file" [New]`); nothing is written until `:w` |
| `:w ++p` | as `:w`, and for a new file also create its missing folders (`:wq ++p`, `:x ++p` too) |
| `:e!` | revert the editor to the saved file; `:e` alone reloads it, refused while there are changes |
| `:ene[w]` | open a new untitled editor (*File → New → Untitled Text File*), working in this editor's folder (its `:lcd` too), so `:w notes.txt` saves it there. Unlike Vim's `:enew`, which empties the window, this editor stays open (so `:enew!` is the same). `Ctrl-^` goes back |
| `:te[rminal] [cmd]` | open Eclipse's *Terminal* view with a shell in the file's folder, instead of a Neovim terminal (which Eclipse cannot show). A command after it (`:terminal make`) is not run, and a message says so: type it in the shell. In an Eclipse without the Terminal nothing opens, and a message says how to get it. A terminal started another way (a `<Cmd>` mapping, a plug-in) still starts in Neovim, out of sight, with a message naming it |
| `:tabn[ext] [N]`, `:tabp[revious] [N]`, `:tabN[ext] [N]` | next / previous editor tab, or tab N / N tabs back |
| `:tabfir[st]`, `:tabr[ewind]`, `:tabl[ast]` | first / last editor tab |
| `:sp[lit]`, `:vs[plit]`, `:new`, `:vne[w]`, `:sv[iew]`, `:sf[ind]`, `:sb[uffer]`, `:on[ly]`, `:res[ize]`, `:winc[md]` | Vim's windows as editors: see [Splits](#splits-split-vsplit-ctrl-w) |
| `:tabnew`, `:tabe[dit]`, `:tabf[ind]` | a new editor tab: see [Splits](#splits-split-vsplit-ctrl-w) |
| `:tabo[nly]` | close the other editor tabs: see [Splits](#splits-split-vsplit-ctrl-w) |
| `:tabonly!`, `:stag` | refused, with a message. A window opened some other way (a plug-in's `vim.cmd.split()`) is closed again with a message; a help window's page opens in the [help editor](#the-help-editor-help) instead. So is the preview window (`:pedit`). The quickfix and location list windows are closed as well, and shown the Eclipse way instead: `:copen` opens the Problems or Tasks view for Eclipse's lists, else shows the list's `:clist` (see [The quickfix list](#the-quickfix-list-eproblems-q)) |

Neovim reads these commands, so they are written as in Neovim (`:wa`, `:wal`, `:wall`), and they
work wherever a `:` line runs: typed in the box, from a mapping in your `init.lua` (`<leader>q` →
`:q<CR>`), from a macro, with `:normal`, and repeated with `@:`. Neovim hands each one to Eclipse as
`:Eclipse quit`, `:Eclipse! edit file` ..., a command you can also use in your own mappings; the
history keeps what you typed. Each command of a `|` chain is handed over the same way: `:w | q`
writes, then closes the editor (asking first if the save failed), and `:w | echo "saved"` runs the
`echo` after the write. Not handed over, so Neovim's: a line run by `<Cmd>` or Lua
(`vim.cmd.quit()`), the commands `:bufdo` runs, the rest of a line whose command takes `|` as part of
its argument (`:normal`, `:g`, `:let x = 1 | q`), and a range a command does not take (`:5,10w`).

A buffer that Neovim deletes by itself (a Lua plug-in, `<Cmd>bd<CR>`, `:bufdo bd`) closes its
editor too, and so does a `:quit` Neovim runs itself; Eclipse asks about changes as for `:q`.

Writes are Neovim's own: `:w`, `:update`, `:bufdo update`, `vim.cmd.write()` in Lua. Neovim never writes
a file itself; it asks Eclipse to save it (so save actions run as usual) and shows Vim's message. Neovim's
"modified" flag follows Eclipse's unsaved-changes marker, so `:update` saves exactly what Eclipse would,
and `:ls` shows `+` for those files. `:w !cmd` pipes the text to a command, as in Neovim.

A write under another name, `:w other.txt`, writes a copy, as in Vim: the text goes to `other.txt` (in
the file's own encoding, `++p` creating missing folders), and the editor stays on its own file with its
unsaved changes, so a later `:w` still saves it. A relative name is relative to the folder
Neovim's window works in, the editor's project folder (`:lcd`, as Tab completion shows), so from
`src/Main.java`, `:w copy.txt` writes `copy.txt` at the project's root; `:w %:h/copy.txt` writes it
beside the file (`%:h` is the file's own folder, `:help filename-modifiers`). `:e`, `:wq` and `:x`
read a name the same way. Inside a project the
copy appears in the Project Explorer at once. As in Vim, an existing file needs `!` (`:w! other.txt`), and a file another Neovim
buffer has is refused (E139). A copy is the text as it is: Eclipse's save actions (format on save) are
for an editor's own save. A copy over a file that an Eclipse editor has open with unsaved changes is
refused, even with `!`; an open editor without changes shows the new text. Some lines go to another
file the same way (`:5,10w part.txt`), and `>>` adds them to the end of one (`:w >> log.txt`,
`:5,10w >> log.txt`; a missing file needs `!`, as in Vim). The editor's own file is saved only whole,
so `:5,10w!` and `:w >> %` are refused there. `:wq other.txt` writes the copy, then closes the editor,
as Vim's `:w other.txt | q`: Eclipse asks about the editor's own unsaved changes (where Vim says E37),
and `:wq!` discards them. `:x other.txt` writes the copy only if the editor has changes.

An untitled editor (*File → New → Untitled Text File*) has no file to keep, so there `:w a.txt`
saves it as `a.txt` and the editor shows `a.txt` from then on, as Vim names a buffer without a file
after the file it writes (`:help cpo-F`). `:wq a.txt` and `:x a.txt` do the same, then close the editor
without asking about changes. Until its next write, `:file` (`Ctrl-G`) says `[Not edited]`. An
untitled document open in two editors (*Window → Editor → Clone*) gets a copy instead, and both stay
untitled. A file's revision from Git's history or a jar's entry is no untitled editor: `:w name` writes
a copy there.

`:saveas new.txt` is Eclipse's *File → Save As* without the dialog: the document is saved as `new.txt`
and the editor moves there, keeping Neovim's undo history and marks; the old file stays as it was.
Eclipse's save actions do not run in that save (a `:w` afterwards runs them).
It is refused when another Eclipse editor has `new.txt` open (E139). `:file new.txt`, which renames a
buffer without a file, is undone at once (the editor's file stays the same); `:file` alone shows the
file info as usual, which says `[Not edited]` until the next write after such an attempt.

Every other command is typed into Neovim as you wrote it, so it is in the history, `@:` repeats it and a
macro being recorded contains it. Listings of more than one line (`:ls`, `:jumps`, `:marks`,
`:registers` ...), shell output (`:!ls`, `:!make`) and anything else of more than one
line show in a read-only box in the same place, with the command as its first line; output that keeps coming
(`:!make`) is added while the box is open. `j`/`k`, Space/`b`, `g`/`G` and the arrow and page keys
scroll it; any other key or a click closes it. Output that comes while you type elsewhere in Eclipse
(a timer's or an autocommand's) opens the box without taking the keyboard from there. Like a message,
it then closes with your next key or click in the editor, and the key works as usual; click into the
box to scroll it. `:messages` and `:checkhealth` open a
[page editor](#the-page-editors-help-messages-checkhealth) instead, which stays open beside the code. One-line output (`:echo "hi"`, `:set ts?`, a `:s` report) goes to
the message line instead, as on Neovim's own screen, and so do errors. Your own `:redir` collects as in Vim.

## Completion (`Ctrl-N`, `Ctrl-P`)

In insert mode `Ctrl-N` and `Ctrl-P` are Vim's own completion, done by Neovim: the word before the
cursor is completed from the words of the open files (`'complete'`), and Neovim's list of matches
is drawn under the word. Example: after `al`, `Ctrl-N` gives `alpha` and a list of `alpha`,
`alphabet`, `alpine`.

| Key | With the list open |
|-----|--------------------|
| `Ctrl-N`, `Ctrl-P` | the next, the previous match |
| `Ctrl-Y` | keep the selected match and close the list |
| `Ctrl-E` | take the completion back: the text as you typed it |
| `Esc` | leave insert mode, with the match that is in the text |
| `Backspace`, or a letter while no match is selected | narrow or widen the list |
| any other character | keeps the match, closes the list and is typed |

- **Where the keys go.** Eclipse types in insert mode. `Ctrl-N` first hands Neovim what you typed,
  and from there Neovim types until the completion has ended; then Eclipse types again. While
  Neovim completes, *every* `Ctrl` letter is Neovim's, whatever the *Ctrl Keys* preference page
  says: `Ctrl-Y` accepts also where you gave the key back to Eclipse's Redo.
- **The character that ends it is typed by Neovim**, so Eclipse's typing helpers skip that one
  character: a `(` right after a completed name gets no closing `)`, a `.` does not open content
  assist. The next character is Eclipse's again.
- **The `Ctrl-X` modes** work where `Ctrl+X` is ticked for insert mode (*Ctrl Keys*; the
  default): `Ctrl-X Ctrl-N` words of this file, `Ctrl-X Ctrl-L` whole lines, `Ctrl-X Ctrl-F` file
  names, and Vim's others. `Ctrl-X Ctrl-O` stays Eclipse's Content Assist, which knows the
  language; Neovim's omni completion does not. While Neovim's list is open it does the same:
  the completion ends with its match kept, and Content Assist opens.
- **One is refused:** `Ctrl-X Ctrl-V` (also typed `Ctrl-X Ctrl-Q`), which completes the line as a
  Vim command. That runs what the line holds in backticks in the shell, so a line of a file you
  only opened could run a program. Neovim's own omni completion is never reached either: in an
  insert Neovim types to its end (while recording a macro), `Ctrl-X Ctrl-O` is dropped with a
  message. In the command-line window of `:` commands (`q:`) the lines are your own commands,
  and `Tab` completes them as before.
- **How it looks:** Neovim's colors for the list (`Pmenu`, `PmenuSel` and the others, your
  colorscheme's), in the editor's font, inside a frame made from the editor's own colors, so
  that it stands out where the colorscheme's list background is close to the editor's. `'pumheight'` limits its rows; otherwise it takes the room
  there is, below the line or above it. `'completeopt'` works as in Vim (`noinsert`, `noselect`,
  `fuzzy`), with one exception: the extra window `popup` and `preview` open for a match's
  documentation is not shown.
- **One match, or none:** Neovim shows no list, as in Vim. It still completes until the next key:
  `Ctrl-N` again takes a single match back, any character goes on.
- **`.` and undo** take the insert as one: what you typed and what was completed.
- **A click into the text**, or leaving the editor, ends the completion and keeps the match.
- **Not completed as you type.** Neovim sees what Eclipse typed only at such a key or at `Esc`, so
  a setting or plug-in that opens the list by itself while you type (`'autocomplete'`, nvim-cmp)
  has nothing to work with. Eclipse's own content assist does that job.

`vim.g.neovim4eclipse_completion_menu = 0` in `init.lua` turns this off: the keys then run
Eclipse's *Word Completion* (`Alt+/`), which inserts the nearest matching word and shows no list,
as before 0.2.24.

## Jump labels

A jump plug-in takes the cursor anywhere on the screen in two or three keys: after its key it
draws a letter over each place you could go to, and the letter you type next takes you there. The
editor paints those letters over the text, in the plug-in's own colors, together with its match
highlights and the dimmed backdrop that makes the letters stand out. Your text does not change;
the letters are only drawn.

[flash](https://github.com/folke/flash.nvim), [leap](https://codeberg.org/andyg/leap.nvim),
[hop](https://github.com/smoka7/hop.nvim) and
[mini.jump2d](https://github.com/echasnovski/mini.jump2d) work without any setting. The editor
knows a jump plug-in's marks by the names of their highlight groups, which start with `Flash`,
`Leap`, `Hop` or `MiniJump2d`. For another plug-in, name the start of its groups in `init.lua`:

```lua
-- also draw the labels of a plug-in whose groups are SneakLabel, SneakScope, ...
vim.g.neovim4eclipse_jump_label_group_prefixes = { 'Sneak' }
```

Your list is added to the four; it cannot remove one. It is read once, when Neovim starts.
`vim.g.neovim4eclipse_highlights = 0` turns the labels off, together with the yank flash and
`:match` highlights.

What to know:

- The plug-in must be set up for Eclipse like any other: load it from your `init.lua`, and map
  its key there. `if vim.g.neovim4eclipse then ... end` keeps a mapping to Eclipse only.
- The keys of a jump (the search character, the label) go to Neovim, so they must not be among
  the `Ctrl` keys you gave to Eclipse; plain letters always are Neovim's in normal mode.
- A label is one character cell wide. In a proportional font it can be a little wider or narrower
  than the character under it.
- flash's small prompt at the bottom is one of Neovim's [floating windows](#floating-windows) and
  is drawn as those are.

## Floating windows

A Neovim plug-in can open a *floating window*: a small window over the text, such as the panel of
key hints which-key shows after `<Space>`. Eclipse draws the editor, so it has to draw these too.
It draws the ones that do **not take the keyboard**: the plug-in opened the window without moving
the cursor into it.

- **What you see:** the window's text in Neovim's colors for floating windows (`NormalFloat`,
  `FloatBorder`, your colorscheme's), over the editor's text, where Neovim put it. A window across
  the bottom of Neovim's screen lies on the editor's bottom edge. One larger than the editor is cut
  off at the editor's edges.
- **They only show.** Your keys go to the editor and on to Neovim, as if the window were not
  there: with which-key, the next key runs the mapping and the panel closes. A click or the wheel
  on a floating window does nothing.
- **Modes:** in Normal, Visual and operator-pending mode (`d` and then a panel of motions). In
  insert mode Eclipse types and Neovim gets the text at `Esc`, so a window that follows your
  typing (signature help, a completion plug-in's documentation) does not appear, or lags; Eclipse's
  own content assist and parameter hints do that job.
- **Not drawn:** a floating window that opens *with* the keyboard focus (a picker's prompt, `:Lazy`,
  a file tree). It is closed at once, with a message, since it would take the keys you type in the
  editor ([README](../README.md#known-limitations), "Neovim plug-ins that draw").
- **Limits:** at most 16 floating windows are drawn at once, and at most 250,000 cells in all of
  them together (a window covering an editor on a 4K screen is about 46,000). Past that, the windows on top
  in Neovim's order stay and the others are not drawn; the plug-in's log says so once
  ([The plug-in's log](#the-plug-ins-log)). The limits keep a plug-in gone wrong, with a window of
  5000 by 5000 cells or thousands of windows, from slowing Eclipse down.
- **A row off, sometimes.** A window anchored to a text position is placed by Neovim's rows. Where
  Eclipse's rows differ (a code mining above a line, a long line Eclipse wraps at another
  character), it can sit one row from where Neovim meant it.
- **Icons:** a plug-in that draws icons from a Nerd Font shows boxes unless Eclipse's text font
  (*Preferences › General › Appearance › Colors and Fonts › Text Font*) has them, or a font the
  system falls back to does (on Linux, an installed *Symbols Nerd Font*). An icon wider than a
  cell reaches into the cell after it, as in a terminal; a text font patched with the icons
  (a "Nerd Font Mono") keeps each in its cell. which-key's `icons` options turn its icons off or
  replace them with plain text (see its documentation).

`vim.g.neovim4eclipse_floating_windows = 0` in `init.lua` turns the drawing off; the windows then
exist unseen, as before 0.2.23. Nothing is drawn either with `vim.g.neovim4eclipse_viewport = 0`:
Neovim's screen is then not the editor's size, and a window "at the bottom" would land anywhere.

<a id="the-neovim-view-messages-checkhealth"></a>

## The page editors (`:help`, `:messages`, `:checkhealth`)

Three commands show more than the box over the editor is good for: `:help`, `:messages`
(Neovim's message history) and `:checkhealth` (its report on your setup). Each opens a **page
editor** instead, an editor of its own beside your code, titled with what it holds: *Neovim: help*,
*Neovim: messages*, *Neovim: checkhealth*. Vim opens these in a window of their own; here that
window is an Eclipse editor.

- **Where it opens:** the first one below the editor you typed in, as Vim's `:help` splits the
  window; the others as tabs beside it, so the code is split only once. Running a command again
  brings its editor to the front and shows the new content there, wherever you moved it. Drag it
  anywhere in the editor area, as any editor.
- **What you see:** for `:help` and `:checkhealth`, Neovim's own screen, from a second Neovim the
  plug-in starts for each page editor; its look is Neovim's default colorscheme on your editor's
  background, in Eclipse's text font, and your mappings are not there. The report is colored as
  Neovim colors `:checkhealth`, its OK, WARNING and ERROR lines included. `:messages` is a window
  of your own Neovim, the one behind your code (next section).
- **It takes the keyboard**, as Vim's own `:help`, `:messages` and `:checkhealth` do. Every key is
  Neovim's: `/` searches, `n` goes on, `y` yanks into your Neovim's register of the same name (so
  `p` in an editor pastes it; a plain `yy` fills the unnamed register, and the clipboard when your
  `'clipboard'` says so). The left button and the wheel work as in Neovim. Eclipse's own shortcuts
  stay Eclipse's, as in any editor that is not a text editor.
- **`Ctrl-W`** moves as in Vim, by where the windows are: inside the page editor first (a help
  page and its `:copen` list), then on to Eclipse's editors, as from your code. So `Ctrl-W k` from
  a page editor below the code goes to the code, `Ctrl-W j` from the code comes back, and
  `Ctrl-W p` goes to the editor you were in before. Ctrl+W there is Neovim's, not Eclipse's Close
  Editor (on macOS Close is Cmd+W, as everywhere).
- **Not a `Ctrl-O` stop:** `Ctrl-O` and `Ctrl-I` from your code pass over the page editors, as
  Vim's jumps never go into a help window. Inside a page editor they are its Neovim's own.
- **Closing it:** `q` or `:q`, or its tab's close button. The next command opens it again. Page
  editors are not brought back when Eclipse starts again: their Neovim ended with Eclipse.
- **`:checkhealth` gets text only:** its second Neovim gets the lines, never a
  file name, and it starts without your config or plug-ins (`--clean`), in a folder of the
  plug-in's own, with writing turned off. Nothing it shows and nothing it does by itself writes a
  file, so it cannot get in the way of your editors; a command you type there that writes on purpose
  (`:call writefile(...)`, `:mksession`) is yours, as in any Neovim. The readers that could run a
  program from a name in the text (netrw, zip, tar, http, `term://`) are off there too, as in the
  editing Neovim, so `gf` on a shown `term://…` runs nothing.
- **Long messages** in a page editor itself (a refusal after `:w`, say) do not stop at "Press
  ENTER": they are cut to the first lines with a `[+N]` marker, and `g<` shows them all (Neovim's
  `ui2`).

### The messages editor (`:messages`)

`:messages` opens a window of your own Neovim, the one behind your code, in an editor of its own:
the message history, in order, the newest last and the cursor on it, errors in your colorscheme's
error color and warnings in its warning color. Being the same Neovim as your code's:

- **Your colors and mappings are there**, and so are your registers and your search history: `y`
  in the messages and `p` in the code, `/` in the messages and `n` in the code.
- **The `:` box, messages and floating windows** (which-key's panel, say) show over the messages
  editor while it has the keyboard, as over your code. The status bar shows Neovim's mode and status
  line for it.
- **`:split`, `:vsplit`, `Ctrl-W s`, `Ctrl-W v`** in it give a second messages editor in a tab group
  of its own: two views of the one history, each with its own cursor and scroll position, as two
  Neovim windows on one buffer. `:messages` again shows the new history in both, and brings the one
  you used last to the front.
- **`:new`, `:vnew`, `Ctrl-W n`** open a new untitled text editor beside it; **`:e file`** opens the
  file in an editor, in the tab group of the code you came from: the messages editor keeps its text.
  `gf` on a path in the messages does the same.
- **It cannot be changed or written:** it is a scratch buffer of your Neovim
  (`neovim4eclipse://messages`) that no file has, read-only; `:w` there gets Neovim's own E382, and
  `i` changes nothing. `:wq` is refused the same way; `:x` closes it, as nothing changed.
- **`gd`, `K` and the `gr*` keys** are Neovim's own there, not Eclipse's, since the messages are no
  code; `gt` and `gT` still move among the editor tabs.
- **Closing it:** `q` or `:q` closes that messages editor; **Restart Neovim** closes every messages
  editor, as their windows belong to the Neovim that ended.
- **In the background**, behind another editor, it keeps what it showed: Neovim draws a window only
  while it is in front, and it is drawn anew when you come back to it.

<a id="help-in-the-view-help"></a>

### The help editor (`:help`)

`:help` opens the page in the help editor: Neovim's own help, with the pages of your plug-ins, as
in a terminal Neovim. So do `:helpgrep`, `:vert help`, `:tab help` and a plug-in's
`vim.cmd.help()`. The editor you typed in keeps no help window. `:help` typed in the messages or
checkhealth editor opens the help editor too, as if typed in your code.

- **It is Neovim's help:** `Ctrl-]` (or a double click) follows a link, `Ctrl-T` and `Ctrl-O` go
  back, `:help` again opens another page there, `K` looks up the word under the cursor, `/`
  searches and `y` yanks into your registers, as above.
- **With your plug-ins' help:** the help editor's Neovim has your Neovim's `'runtimepath'`, so it
  finds the help pages of the plug-ins you installed, and runs the help scripts they bring
  (`ftplugin/help.lua`, `after/ftplugin/help.lua`, a parser for a code block's language), as your
  editor's Neovim does on a help page. Your `init.lua` and the plug-ins' start-up scripts
  (`plugin/`) do not run there. Writing stays off: no help page can be changed or written. A
  plug-in your plug-in manager loads only when first used has its help there once it is loaded.
- **`:helpgrep`** goes on in the help editor: typed in your code, the help editor runs the same
  search, so `]q`, `[q`, `:cnext` and `:cprevious` there step through the matches, and `:copen`
  shows the list beside the page (Enter opens a match). Typed in the help editor, it stays there.
  From your code, `:cnext` still works too.
- **Help only:** a file opened from a page (`gf` on a path, `:edit`) is swapped back for the page,
  with the message "The help editor shows help only". Open files in an editor of their own.

**`:help neovim4eclipse`** opens the plug-in's own help page: a short one, pointing to the README
and this guide, where its documentation is.

**`:checkhealth neovim4eclipse`** is the plug-in's own check: which Neovim runs and whether it is new
enough, whether it is the dedicated Neovim (checked before it started) or a program of your own
(`-Dneovim4eclipse.nvim`), the plug-in's and Eclipse's versions, and whether what
[SECURITY.md](../SECURITY.md) turns off for the files you open is still off (modelines, the readers
of URLs and archives, the `term://` reader). A plain `:checkhealth` runs it among all the others.
Worth adding to a [problem report](../README.md#reporting-a-problem).

## Mouse

In normal and visual mode the left mouse button is Neovim's, as in Neovim with `'mouse'` on (its
default): a click moves the cursor, a double click selects a word (on a bracket, up to its match), a
triple click selects the line (`V`), a fourth click a block (`Ctrl-V`), dragging selects, and
Shift+click extends the selection. The selection is Neovim's visual selection, so `d`, `y`, `>`, `:`
act on what you see. Your `'mouse'`, `'mousetime'` and mappings such as `<2-LeftMouse>` apply; with
`'mouse'` empty a click only moves the cursor.

Eclipse keeps the mouse in insert mode and in Eclipse mode, and always keeps Ctrl+click (Cmd+click on
macOS: Open Declaration and other hyperlinks), the right button (context menu) and the middle button
(pasting the primary selection on Linux). The wheel scrolls Neovim's window in normal and visual mode
(see "The editor's size, scrolling and word wrap"). Dragging selected text to move it is not available in
normal mode; drag in insert mode or Eclipse mode for that.

## Indentation (`o`, `O`, `>>`)

Neovim indents the lines it creates. When a document is attached, the plug-in sets that buffer's
options to match the file:

- **Tabs or spaces, and the width of one level**, are read from the document's own indented lines.
  A file with none yet uses *Preferences › General › Editors › Text Editors › Insert spaces for tabs*.
  `'tabstop'` is always the editor's tab width.
- **Brace languages** (Java, C/C++, C#, JavaScript/TypeScript, Kotlin, Go, Rust, CSS, JSON, ...) get
  `'cindent'`: `o` on a line ending in `{` and `O` on a line starting with `}` start one level in.
  Other files keep `'autoindent'` (the new line copies the current line's indent).

No file's modeline (`// vim: set ts=4 :`) applies: the plug-in turns `'modeline'` off at start,
since a modeline of a file you only opened may set options that change what a later key does.

Neovim's bundled readers of things that are no files are not loaded: netrw (`scp://`, `ftp://`,
`file://` and other URLs, and its directory listings), the `http://` reader, and the zip and tar
browsers. Their buffers would have no Eclipse editor to show them, and with them `gf` on a URL
written in a file made Neovim fetch it. `gf` on a URL or an archive fetches and unpacks nothing.
To have one back, unset its variable in `init.lua`
(`vim.g.loaded_netrwPlugin = nil` and `vim.g.loaded_netrw = nil`, `vim.g.loaded_nvim_net_plugin`,
`vim.g.loaded_zipPlugin`, `vim.g.loaded_tarPlugin`).

A buffer named `term://...` starts no program either (`gf` on such a name in a file would
otherwise run it). `:terminal` opens Eclipse's *Terminal* view instead, and Neovim starts nothing
for it (see the `:` commands table). A terminal started where the `:` line is not read (a `<Cmd>`
mapping, `:execute`, Lua, a plug-in's `jobstart({term = true})` such as toggleterm) still starts
in Neovim. Eclipse has no view for it: the editor goes back to its file, and the program runs out
of sight until it exits or Neovim stops. A message names it
(`terminal: "lazygit" is running hidden: Eclipse cannot show a Neovim terminal. It stops when it
exits or Neovim does.`). It is not stopped, since by then it has started, and a stop could leave
its work half done.

`Ctrl-R` is Neovim's own, in insert mode and on the `:` line. As in Vim, some registers are
inserted as if typed (the file's name `%`, the last search `/`, the unnamed register `"`), so a
control character in the text acts as a key: an Esc ends the insert, and what follows runs as
commands. `Ctrl-R Ctrl-R` and the register inserts the same text as text
(`:help i_CTRL-R_CTRL-R`); use it for text you have not looked at, such as the name of a file from
a repository you only cloned.

These options replace any your `init.lua` sets for the buffer. Filetype detection is not used, so
your filetype plugins (and language servers they may start) do not run in the embedded Neovim. That
holds for any file Neovim reaches by itself too (`gf`, `'A`, a quickfix entry), in a project or
not: the plug-in reads it for Neovim, without filetype detection, and Eclipse opens its editor (a
file outside the open projects by its location, as *File › Open File...* does). Help pages are
Neovim's own ([docs/design/BUFFER_NUMBERS.md](design/BUFFER_NUMBERS.md)). One exception: to search files,
`:vimgrep` reads them itself, filetype detection included, so the files it matched get their
filetype (and a language server started from `FileType` may attach to its first match) before
Eclipse opens their editors.

## Macros (`q`)

`qa` starts recording into register `a` (the status line shows `recording @a`), `q` stops. Play back with
`@a`, `3@a`, `@@` (last macro) and `@:` (last `:` command). Recording and playback are done by Neovim itself;
the plugin makes sure everything you do is seen by Neovim as keys:

- **Insert mode while recording** is driven by Neovim (every key is forwarded, as for block `I`/`A`), so the
  macro contains exactly what you typed, including arrow keys and Backspace. Eclipse's content assist and
  IME composition are therefore unavailable *while recording*.
- **`:` commands and `/` searches** from the command line are typed into Neovim (`:<cmd><CR>`), so they are
  recorded and `@:` works, `:w`, `:q` and the other commands Eclipse carries out too. The editor gets the
  macro's changes only when it ends, so a `:w` inside a macro saves the text from before the macro.
- **Playback** is applied to the editor as one change when the macro finishes, which is much faster than
  replaying each step and avoids half-finished states. Very long macros are given up to 60 seconds.
- `f/`, `t:`, `r/`, `@:`, `qa` ...: the character after `f F t T r @ m ' \` " q` is an *argument* and always goes
  to Neovim (previously `f/` opened the search box).

Clicks in normal and visual mode are recorded, as Neovim records them (`<LeftMouse>`, without a
position: playback clicks where the mouse was last). Not recorded: actions that happen only in Eclipse. A macro's
text can be edited in the command-line window as in Vim (`q:`, then `let @q = '...'`). Macros live in Neovim's registers, which Neovim's ShaDa file keeps across restarts (*Neovim →
Restart Neovim...*, a new executable, or Eclipse), as in a terminal.

## Repeating inserted text with `.`

`A`, type some text, `Esc`, then `j` and `.` repeats the typed text on the next line (likewise `cw`, `o`,
`i`, ... followed by text, and `".p`). Eclipse does the typing; when you press Esc the plugin re-types that
text into Neovim's insert mode so Neovim records it for `.`, then makes Eclipse's text authoritative again.

This applies when the insert was a plain insertion at one spot. It is **not** recorded (the text stays
correct, but `.` repeats only the command without the text) when you delete text before the insertion
point, edit in two places in one insert, or paste more than 1000 characters. Counts (`3Afoo`) and Replace
mode (`R`) are also not repeated by Neovim in this mode. Your insert-mode mappings and abbreviations
are kept: the re-typing applies none of them (a `jk` mapped to `Esc` cannot end it half way), and they
work wherever Neovim reads the keys itself, as in a block-visual `I` or for the `Ctrl` keys ticked for
insert mode. While Eclipse types, they do not apply: Eclipse inserts what you type.

A completion plug-in (nvim-cmp, blink.cmp, ...) maps `Enter` and `Tab` in insert mode to accept its
popup. That popup is the plug-in's own, not Neovim's list, and is not drawn, so in an insert Neovim types (a block-visual `I`, or
while recording a macro) `Enter` could accept an entry you never saw. Leave completion plug-ins out
inside Eclipse, which has its own (`Ctrl+Space`), beside Neovim's built-in one on `Ctrl+N`
([Completion](#completion-ctrl-n-ctrl-p)): see
[Your `init.lua` in Eclipse](../README.md#your-initlua-in-eclipse).

## Clipboard

Eclipse is the embedded Neovim's *clipboard provider* (`:help clipboard`), so the registers that
stand for the system clipboard work as in Vim, with no `xclip`, `wl-clipboard` or `win32yank` needed:

- `"+p` pastes what was copied in any program, `"+yy` copies a line for any program to paste.
- `"*` is the primary selection on Linux (the text last selected, pasted with the middle mouse button);
  on Windows and macOS it is the same clipboard as `"+`.
- With `set clipboard=unnamedplus` in your `init.lua`, plain `y` and `p` use the clipboard, as in Vim.
- Without it, every yank (`yy`, `dw`, `"ay` ...) is still copied to the clipboard, so text yanked in
  the editor can be pasted elsewhere; plain `p` pastes Neovim's own register.

A whole-line copy ends with a line break, and pasting text that ends with one pastes whole lines. What
Neovim copied itself pastes back as it was yanked: lines as lines, a `Ctrl-V` block as a block. If your
`init.lua` sets `g:clipboard`, your provider is kept. If the clipboard cannot be read within 2 seconds,
the paste inserts nothing.

## Blockwise visual (`Ctrl-V`)

Works like Vim: `Ctrl-V` then motions, `$` to extend to line ends, `o`/`O` to switch corners, and `d`, `y`,
`c`, `r`, `I`, `A`, `p` ... applied by Neovim. Neovim also measures the rectangle, in screen columns (tabs
and wide characters count correctly), so the overlay shows exactly what its commands act on. On attach,
Neovim's `tabstop` is set to the editor's tab width so both agree.

**Key bindings.** Eclipse handles shortcuts before the editor sees keys (`Ctrl-V` is *Paste* on
Windows/Linux), so `plugin.xml` binds every `Ctrl+letter` to a command that sends the key to Neovim.
Each letter's binding lives in its own key-binding context, and the editor activates only the contexts
of the letters you send to Neovim in the current mode (*Window → Preferences → Neovim → Ctrl Keys*;
in normal mode by default every letter except `Ctrl+S` and `Ctrl+Z`, in insert mode every letter
Vim uses there except `Ctrl+A`, `Ctrl+C` and `Ctrl+V`; see [docs/KEYBOARD.md](KEYBOARD.md), which
also says how to reach the Eclipse commands those keys had, such as `Shift+Delete` for Cut). The bindings work
only while Neovim owns the editor's keys and the editor has focus; for the other letters, and in
Eclipse mode (toggle with the toolbar button: color logo in Neovim mode, grey in Eclipse mode),
Eclipse's own shortcuts apply. On **macOS use the physical Control key**, as in MacVim or IdeaVim; Command-V remains
the system Paste.

**If `Ctrl-V` does nothing**, the plug-in's log says why. It goes to the console of the Eclipse
process: the terminal you started Eclipse from, or the Console view of the Eclipse that launched a
runtime Eclipse. WARNING lines also appear in the Error Log (*Window › Show View › Error Log*).
The first three lines are written when Neovim mode turns on in an editor, not when you press `Ctrl-V`.

| Log line | Level | Meaning |
|----------|-------|---------|
| `Neovim key context activated` | FINE | the context is on; if absent, the editor did not have focus or Neovim mode is off/insert |
| `Ctrl-V (M1+V) resolves to the Neovim Ctrl-key command` | FINE | binding works; the key goes to Neovim |
| `Ctrl-V (...) resolves to '<other command>' (or no command), as chosen` | FINE | Ctrl-V goes to Eclipse in this mode by your Ctrl Keys choices (on Linux and Windows, insert mode by default) |
| `Ctrl-V (...) resolves to '<other command>' (or no command), not to a Neovim Ctrl-key command` | WARNING | Ctrl-V should go to Neovim, but another binding outranks ours, or the context is not in effect |
| `Ctrl chord reached the editor key listener` | INFO | the key got past the workbench (so a binding is not consuming it) |
| `Ctrl-key command ran but there is nothing to send to` | WARNING | the binding fired but no editor is attached (the line names the command and key) |

FINE lines appear only with `-Dneovim4eclipse.logLevel=FINE`: see [The plug-in's log](#the-plug-ins-log).

## Preferences

*Window → Preferences → Neovim* (also *Neovim → Preferences...*, which opens it with its sub-pages
expanded): which Neovim runs, with *Get Neovim* (see [Finding Neovim](#finding-neovim); a Neovim of
your own is a startup option, not a preference), whether to show the toolbar toggle, and
whether to show the *Neovim* menu in the menu bar (`Alt+V` opens it on Linux and Windows). With the menu hidden, its entries stay reachable:
the toggle through `Ctrl+Shift+M`, the toolbar button or Quick Access (`Ctrl+3`), the preferences
through *Window → Preferences*.

The *Neovim* menu, top to bottom: *Neovim Mode* (the toggle, `Ctrl+Shift+M`); *Attach Neovim*,
for an editor that did not attach by itself, which also starts a new Neovim if
the old one stopped; *Restart Neovim...*, which asks, then stops Neovim and starts a new one, for
example after changing `init.lua` (lost: the undo history, and what you set by hand with `:set`,
`:map` or `:let`; kept: the editors' text, unsaved changes included, and the registers, marks and
history, which Neovim's ShaDa file brings back as after a restart in a terminal); *Get Neovim...*; *Welcome...*;
*Report a Problem...*, which shows what a bug report needs, to copy into an issue
([Reporting a problem](../README.md#reporting-a-problem)); *Preferences...*.
On Linux and Windows each entry has an underlined letter: `Alt+V` then `V`, `A`, `R`, `G`, `W`, `P` or `N`
(*Prefere<u>n</u>ces*, as in Eclipse's *Window* menu) runs it without the mouse.
*Neovim: Detach Neovim* is in Quick Access (`Ctrl+3`) only: *Neovim Mode* off already
gives the keys back to Eclipse. *Flash the editor on errors*
adds a brief flash to errors in the message line ([Messages and errors](#messages-and-errors)).
*Show a welcome after installing...* turns off the welcome and the update notes (step 5 of
[Install](../README.md#install)); an administrator can turn it off for everyone with the line
`io.github.jm6cnddxbwftaks.neovim4eclipse/showWelcome=false` in the product's
`plugin_customization.ini`.

*Window → Preferences → Neovim → Status Bar*: which parts of the Vim status appear in the status bar,
and how wide the statusline may be (see [Status bar](#status-bar)).

*Window → Preferences → Neovim → Ctrl Keys*: two check boxes per letter, each with Vim's meaning
beside it. Ticked `Ctrl+letter` keys go to Neovim, in normal and visual mode (**Normal**) or in
insert mode (**Insert**); cleared ones keep their Eclipse shortcut. In an insert Eclipse types, an
Insert key first hands Neovim the typed text, so `Ctrl+W` deletes the word just typed, and `.`
repeats the whole insert. *Select All* and *Deselect All* set a column; *Restore Defaults* ticks
every Normal box but `Ctrl+S` and `Ctrl+Z`, and the Insert box of every letter Vim uses in insert
mode but `Ctrl+A`, `Ctrl+C` and `Ctrl+V` (on macOS those three too). Changes apply immediately.
[docs/KEYBOARD.md](KEYBOARD.md) explains the defaults and which keys go where.

### Get Neovim

**Get Neovim 0.12.6…** installs the Neovim the plug-in needs into its own folder,
`~/.local/share/neovim4eclipse-plugin/neovim/<code>` (Windows:
`%LOCALAPPDATA%\neovim4eclipse-plugin\neovim\<code>`; see [Finding Neovim](#finding-neovim) for the
folder and the code). The dialogs and the preference page call it the **dedicated Neovim**: it is
for Eclipse alone, and no other Neovim on the machine is changed. While the dedicated Neovim is
missing or fails the start check (older, or damaged), and no
[Neovim of your own](../README.md#using-a-neovim-of-your-own) is named, the dialog opens by itself: once when
Eclipse starts, or when an editor first needs Neovim, with a *Why* line saying which, and a last
*Instead* line on how to use a Neovim of your own. Cancel it and nothing more appears; editors keep
their normal Eclipse keys. It is also in the *Neovim* group of *Window → Preferences → Neovim* and in
the *Neovim* menu while it is needed. When the dedicated Neovim checks out, the page's note has a
*Reinstall…* link under it instead. A dialog first says where it goes and what it downloads, and offers two ways:

- **Download**: from Neovim's GitHub release, which redirects to
  `release-assets.githubusercontent.com`; about 10 to 13 MB. The download goes through Eclipse's
  own proxy settings (*Window → Preferences → General → Network Connections*), as Eclipse's updates
  do.
- **Install from File…**: the same archive, already downloaded, for example in a browser where
  Eclipse cannot download (no internet, a blocked host, a proxy that wants a password). Only the
  archive for this platform is accepted, which the dialog names (`nvim-linux-arm64.tar.gz`); the
  file is only read, never moved or deleted.

Either way the plug-in checks the archive's SHA-256 against the one pinned in the plug-in (the
dialog shows it, with *Copy*, and links to the GitHub release page and the VirusTotal report for
that hash), unpacks it, checks that it runs and reports the right version, and only then puts it in
place. Nothing changes if any step fails. Without a running Neovim the active editor attaches at
once; otherwise the note offers *Restart Neovim*, and a Neovim running from the same folder is
replaced when it restarts. If Eclipse was started with `-Dneovim4eclipse.nvim`, the note says to
remove that option and restart Eclipse, since the dedicated Neovim is not used until then.

**A proxy that asks for a password**: Java does not send a proxy password through an HTTPS
connection unless Eclipse starts with this line in `eclipse.ini`, after `-vmargs` (Eclipse's own
updates need the same):

```text
-Djdk.http.auth.tunneling.disabledSchemes=
```

Then enter the user and password under *Network Connections*. Or use *Install from File…*.

**The dialog's links** (GitHub release page, VirusTotal, the archive's download) open in the browser
chosen in *Window → Preferences → General → Web Browser*. If Eclipse cannot start it, a *Link Not
Opened* dialog shows the link to copy and why. On macOS two Eclipse quirks get in the way:

- *Default system web browser* opens a link in the app macOS uses for **`.html` files**, not your
  default browser. If that is MacVim (or another editor), the link opens there.
- A browser added by its app folder (*Browse…* to `Safari.app`, which may show as
  `/System/Volumes/Preboot/Cryptexes/App/System/Applications/Safari.app`) does not start, since an
  `.app` is a folder, not a program.

What works: *New…* on that page, with any name, the location `/usr/bin/open` and the parameters
`-a Safari %URL%` (or `-a Firefox %URL%`, ...); then select it. `open` hands the link to that app.

**By hand**, without the plug-in's help: extract the archive and rename its folder to the code, so
that `neovim/<code>/bin/nvim` exists; or extract it anywhere and name its `bin/nvim` with
`-Dneovim4eclipse.nvim` ([Using a Neovim of your own](../README.md#using-a-neovim-of-your-own)). On macOS an archive downloaded with a browser is quarantined, and macOS then refuses to
run Neovim; remove the flag after extracting (files the plug-in unpacks have none):

```bash
xattr -dr com.apple.quarantine ~/.local/share/neovim4eclipse-plugin/neovim/ma
```

Uninstalling the plug-in leaves this folder (about 40 MB per platform); delete it by hand.

### Finding Neovim

The plug-in runs exactly one Neovim, and looks nowhere else for it: not on `PATH`, not in the usual
install folders. The note in the *Neovim* group of *Window → Preferences → Neovim* shows which, for
example
`Found: dedicated Neovim 0.12.6, checked, /home/you/.local/share/neovim4eclipse-plugin/neovim/lx/bin/nvim`.
When Neovim would not start with it, the note is in the theme's error color.

- **Normally** (the default): the **dedicated Neovim**, the one *Get Neovim* installs, at
  `<code>/bin/nvim` in the plug-in's Neovim folder, where `<code>` names the platform (below). The
  folder is the first of these whose variable is set (to an absolute path):
  - Linux and macOS: `$XDG_DATA_HOME/neovim4eclipse-plugin/neovim`, then
    `$HOME/.local/share/neovim4eclipse-plugin/neovim`.
  - Windows: `%XDG_DATA_HOME%\neovim4eclipse-plugin\neovim`, then
    `%LOCALAPPDATA%\neovim4eclipse-plugin\neovim` (usually
    `C:\Users\you\AppData\Local\neovim4eclipse-plugin\neovim`), then
    `%HOME%\AppData\Local\neovim4eclipse-plugin\neovim`, then
    `%USERPROFILE%\AppData\Local\neovim4eclipse-plugin\neovim`.

  These are the folders where Neovim itself keeps its data (`:echo stdpath('data')`), with the
  plug-in's folder in place of `nvim` (or `nvim-data` on Windows).

  The code is the system's letter (`l` Linux, `m` macOS, `w` Windows) and the processor's (`x`
  x86_64, `a` arm64): `lx`, `la`, `mx`, `ma`, `wx`, `wa`. The processor is the machine's real one,
  also when Eclipse itself runs emulated (an x86_64 Eclipse on Apple silicon uses `ma`).

  **Checked at every start.** The plug-in hashes all its files (about 2,000, in about a tenth of a
  second) and compares the result with the hash pinned for this release in the plug-in: when
  Eclipse starts, in the background, and again before every start of Neovim (the first, *Restart
  Neovim*, or after Neovim quit). A Neovim left from an older version of the plug-in,
  a damaged copy, or one where a file was changed or added fails the check: nothing of it runs,
  not even `nvim --version`, and the preference page and the "Neovim not started" notice say that
  *Get Neovim* replaces it. Anything else in the plug-in's Neovim folder, such as an archive
  extracted there by hand before 0.2.1, is not used; the page and the notice list each such
  entry's full path, and you can delete it.
- **A Neovim of your own**, named when Eclipse starts with `-Dneovim4eclipse.nvim`
  ([Using a Neovim of your own](../README.md#using-a-neovim-of-your-own)): that program, run as it is. It is **not checked and not
  supported**.

**Why so strict.** Until 0.2.1 a blank preference also searched `PATH` and the usual install folders, and
a folder was searched four levels deep, running `nvim --version` on every `bin/nvim` it found. That
ran programs nobody chose: picking `~/src` by mistake would run a `bin/nvim` planted in a cloned
repository below it. It also depended on how Eclipse was started, since Eclipse started from the
macOS Dock or a desktop menu does not see your shell's `PATH`. Now the plug-in runs a Neovim it
installed and checked, or the one program you named. Eclipse sees only the environment variables
(`XDG_DATA_HOME`, `HOME`) of whatever started it: set in a shell's startup file, they are missing
when Eclipse starts from the Dock, a desktop menu or the Start menu.
[docs/PLATFORM.md](PLATFORM.md#neovim-executable) has the details.

## Neovim settings the plug-in changes

Where the plug-in has to work around one of your settings for a technical reason, it does so as
narrowly as it can, and it is listed here.

- **`'foldmethod'`, `'foldenable'` and `'foldminlines'`, window-local, in editors' buffers.** In an
  editor that folds, `'foldmethod'` is `manual` and `'foldminlines'` 0, so Neovim's folds are
  exactly Eclipse's ([Folding](#folding-zo-zc-zm)); in one that cannot fold, `'nofoldenable'`.
  Your global values stay as they are. In those buffers `zf`, `zF`, `zd`, `zD` and `zE` also get
  buffer-local mappings that refuse them; every other `z` command is Neovim's (see
  [Known limitations](../README.md#known-limitations), item 6). `'showcmd'` is kept on, globally:
  the plug-in reads Neovim's pending command through it, for the status bar and to refuse those
  keys when they are typed slowly; turned off, it is turned on again, with a message the first time.
  (The plug-in turns `'showcmd'` on at start in any case, for the status bar.) With `vim.g.neovim4eclipse_folds = 0`, none of this is set.

- **`'linebreak'`, `'breakindent'` and `'showbreak'`, window-local, while word wrap is on.** On,
  off and `NONE`, so that lines wrap where Eclipse wraps them ([The editor's size, scrolling and
  word wrap](#the-editors-size-scrolling-and-word-wrap-h-l-ctrl-d-zz)); your values come back when wrap goes off, and
  `'wrap'` itself is the editor's word wrap. With `vim.g.neovim4eclipse_viewport = 0`, none of this
  is set.

- **`vim.diagnostic`'s `jump.on_jump`, only if your config sets none.** After `]d` it shows the
  first line of the problem's message in the message line ([Problems](#problems-d-d)). Set your own
  `on_jump` (or the older `jump.float`) and the plug-in leaves it alone. The namespace
  `neovim4eclipse`, which holds Eclipse's problems, is set to draw nothing; your other namespaces
  keep their display settings.

- **`'showtabline'` 0.** The plug-in keeps editors' Neovim windows each in a tabpage of its own,
  hidden; a tab line would list them and take a row from the editor (with Neovim's default, 1, as
  soon as there are two). It is set once at start-up, after your config; a `:set showtabline=2`
  later shows the tab line again, for as long as you want it.

- **`'shortmess'` `F`, while Eclipse switches editors.** When another editor becomes active, the
  plug-in makes Neovim enter that file's buffer with `F` added to `'shortmess'`, and puts your value
  back right after. Without `F` (common in configs carried over from Vim), Neovim would show its
  file info (`"a.txt" 3 lines --33%--`) on every switch, which Eclipse does not do. Two such
  messages in a row also opened the output popup, which took the keyboard focus from the editor,
  and Eclipse's Undo then left Neovim's cursor a line off. A `:b` you type is not affected.

- **`'runtimepath'`: one folder of the plug-in's appended.** `<plug-in folder>/runtime` (on Linux
  `~/.local/share/neovim4eclipse-plugin/runtime`) holds one file, `lua/neovim4eclipse/health.lua`,
  so that `:checkhealth` finds the plug-in's check ([The page editors](#the-page-editors-help-messages-checkhealth));
  `:checkhealth` looks for checks only among runtime files. The plug-in writes that file again at
  each start and puts nothing else there, so the folder adds no plug-in, filetype script or color
  scheme.

## When Neovim's copy and Eclipse's differ

Eclipse keeps the document you see; Neovim keeps a copy of it in a buffer, and every change goes
from one to the other. Should the two ever drift apart (a bug, a change that got lost), the
plug-in notices at its next comparison and makes them equal again:

- **Eclipse's lines go to Neovim** at `Esc`, after an edit Eclipse makes in normal mode (a format,
  a refactoring), after each save, and when you come back to an editor. At `Esc` the whole file is
  compared up to 20,000 lines; after a save and on coming back, up to 100,000.
- **Neovim's lines come to Eclipse** when an insert starts and after a `:` command, when the
  plug-in reads Neovim's whole buffer.

Whichever comes first wins. When Eclipse's lines win, the window's status bar says *"Neovim's and
Eclipse's copies of this file had drifted apart and were made equal again"* (once per Neovim
session), and the Error Log (*Window → Show View → Error Log*) gets a warning (once per file) that
says where and when, holding no text of the file and not its name. Check your last
change: what Neovim did while its copy was stale may have gone to the wrong line. Please report the
warning in an [issue](https://github.com/Jm6CnDDXBwFTakS/neovim4eclipse/issues): a drift is a bug.

## The plug-in's log

The plug-in writes what it does to a log: WARNING and SEVERE lines reach Eclipse's Error Log
(*Window › Show View › Error Log*), and every line goes to the console of the Eclipse process, the
terminal you started Eclipse from. FINE lines are hidden by default. To show them (and the plug-in's other details, such as each
attach and detach), start Eclipse with the JVM system property `-Dneovim4eclipse.logLevel=FINE`.
It takes the `java.util.logging` level names, in any case: `OFF`, `SEVERE`, `WARNING`, `INFO`,
`CONFIG`, `FINE`, `FINER`, `FINEST`, `ALL`. The plug-in's lines then go to the console, one per line:

```text
06:50:12.345 FINE KeyContextDiagnostics: Ctrl-V (M1+V) resolves to the Neovim Ctrl-key command
```

Pass it in one of these places:

- **Every start:** a line `-Dneovim4eclipse.logLevel=FINE` after `-vmargs` in `eclipse.ini`.
- **One start, from a terminal:**

  ```bash
  eclipsec.exe --launcher.appendVmargs -vmargs -Dneovim4eclipse.logLevel=FINE
  ```

  On Linux and macOS, run `eclipse` instead. Keep `--launcher.appendVmargs`: without it, `-vmargs` on
  the command line replaces all of `eclipse.ini`'s VM arguments, memory settings included. On
  **Windows**, run `eclipsec.exe`, not `eclipse.exe`: it is in the same folder, and `eclipse.exe` has
  no console, so its log lines are lost.
- **A runtime Eclipse launched from the IDE:** its launch configuration, *Arguments* tab, *VM
  arguments*. The lines appear in the Console view of the Eclipse that launched it.

To keep the log in a file, redirect the console: `eclipsec.exe … 2> neovim.log`, or
`eclipse … 2>&1 | tee neovim.log` on Linux and macOS to see it at the same time. The Error Log is
not affected: WARNING and SEVERE lines reach it at every level, even `OFF`. An unknown value is
ignored, with a warning that lists the valid names.
