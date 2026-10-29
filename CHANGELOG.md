# Changelog

What changed in each release of Neovim for Eclipse, newest first, for people who install it. The
format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/): each release has a section,
and inside it the groups *Added*, *Changed*, *Deprecated*, *Removed*, *Fixed* and *Security*, as
needed. Version numbers follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html); before
1.0.0, a minor version (0.2.0) may change behavior.

New entries go under **Unreleased** with the change that makes them. A release renames that heading
to the version and date, and the release workflow publishes that section as the release notes: it
stops if the section is missing (see CONTRIBUTING.md, "Releasing").

## [Unreleased]

### Added

- Neovim's language-server keys run Eclipse's commands: `grn` Rename, `gra` Quick Fix (also on a
  visual selection), `grr` References, `gri` Open Implementation, `grt` Open Declared Type, `gO`
  Quick Outline. Where an editor has no such command, the message line says so.
  `vim.g.neovim4eclipse_ide_keys = 0` gives them back to Neovim, as it does `gd` and `K`.
- `:Eproblems` puts the workspace's problems in Neovim's quickfix list, and `:Etasks` the tasks with
  your tags (`vim.g.neovim4eclipse_task_tags = 'JM6,FIXME'` in `init.lua`); both go to the first
  entry (`!`: they do not). `:Eproblems warning` leaves out infos, `:Eproblems error` warnings too.
  Neovim's `]q`, `[q`, `:cnext` and `:cc` walk the list, opening a closed file's editor; the list
  follows builds and edits while it is the current one, and `:copen` shows the Problems or Tasks
  view. See the README, "The quickfix list".
- Each editor has a Neovim window of its own (in a hidden tabpage), which keeps its cursor, column,
  scroll position, folds, window-local options, jump history, location list and `:lcd` while you
  work in other editors. Two editors on one file (*Window → Editor → Clone*) now have a cursor each
  and share the text and undo history, as two Vim windows on one buffer. `Ctrl-O` into another
  file takes the window along, so the jump history stays one chain. See the README, "Known
  limitations".
- A jump into a project's file that has no editor open (`gf`, `'A`, `:b 3` of a closed file,
  `Ctrl-^`, a quickfix entry) opens the file's editor, with the caret where Neovim's cursor went, and
  `Ctrl-O` comes back. Neovim does not read such a file itself, so your filetype plug-ins and
  `FileType` autocommands (and a language server started from one) do not run for it, as for every
  file Eclipse opens. Jumps to other files are still undone.
- `Ctrl-^` (`Ctrl+Shift+6`, or `Ctrl+6`) edits the alternate file, toggling between two files'
  editors; it reached Neovim as `^` or not at all. `Ctrl-]` runs Open Declaration, as `gd` does.
  `Ctrl-\`, `Ctrl-_` and `Ctrl-@` reach Neovim too.

### Changed

- Closing a file's last editor keeps its Neovim buffer, as Vim's `:bd` does: the buffer number
  stays the same when the file is opened again (`:ls`, `:b 3`), and so do its marks; the caret goes
  where you left it. `:ls!` lists closed files with `u`. Before, the buffer was wiped and the file
  got a new number.
- `:close`, `:tabclose` and, when Ctrl+W goes to Neovim, `Ctrl+W q` and `Ctrl+W c` close the
  editor as `:q` does (Eclipse asks about changes; `:close!` discards them). `:split`, `:vsplit`,
  `:new`, `:tabnew`, `:tabonly` and `Ctrl+W s` / `v` are refused with a message naming Eclipse's
  split editor and *Clone*; a window a plug-in opens beside an editor's is closed again, and so are
  the preview window (`:pedit`). The quickfix and location list windows cannot be shown either:
  `:copen` and `:cwindow` show the Problems or Tasks view for Eclipse's lists (below), and any
  other list's `:clist` in the output popup. `:tabclose 2`
  is refused. In a floating window you entered, `:q`, `:close` and `Ctrl+W q` / `c` close that
  window, not the editor.
- `Ctrl-O` and `Ctrl-I` fall back to Eclipse's Back and Forward (`Alt+Left`, `Alt+Right`) when
  the editor's own jump history has nothing further, so `Ctrl-O` after switching editors with a tab
  click goes back to the editor before. Your own `init.lua` mapping of either key is kept.
- The folder Neovim works in (`:lcd`) is set once per editor, when its window opens, instead of on
  every editor switch, so a `:lcd` you type stays with that editor. An untitled document gets the
  folder of the editor active before it, or your home folder; `'autochdir'` is honored.

- Neovim's `'showtabline'` is 0, set after your config: each editor is getting a Neovim window
  of its own, in a hidden tabpage, and a tab line would take a row from the editor. See the README,
  "Neovim settings the plug-in changes".
- Neovim's window is now the size of the editor's text area, so `H`, `M`, `L` and `Ctrl-D` work on
  the lines the editor shows (before, on a fixed screen of 40 lines nobody saw). Resizing the
  window, the part or the font resizes it too. `vim.g.neovim4eclipse_viewport = 0` in `init.lua`
  keeps the old fixed screen. See the README, "The editor's size, scrolling and word wrap".
- Eclipse's word wrap (`Alt+Shift+Y`, on macOS `Cmd+Option+Y`) and Neovim's `'wrap'` are one setting: changing either changes
  the other, and Eclipse's preference decides when an editor opens. While wrap is on, `'linebreak'`
  stays on, so that lines wrap at the same words in both.
- The editor scrolls where Neovim's window scrolls (`Ctrl-E`, `Ctrl-D`, `zz`, `zt`, a jump,
  `'scrolloff'`), so `H` and `L` go to lines on the screen also after a jump or a resize (before,
  shrinking the window could make `L` land below the editor's last line).
- The mouse wheel and the touchpad scroll Neovim's window in normal and visual mode, by the lines
  your system reports, and drag the caret into the view as `Ctrl-E` does in Vim (before, the editor
  scrolled alone and the caret could stay off the screen). The scroll bar does the same. In insert
  mode Eclipse scrolls alone and the caret stays; at `Esc` the view is kept, and a caret scrolled
  off the screen is dragged into it.
- The FINE log line "Mode → …" is written when the mode changes, not again for every key typed on
  the command line.
- `:e name` opens the file Neovim's `:e` would: relative to the editor's project folder (its
  window's `:lcd`), like `:w name`, `./name` included. Before, it looked beside the current file
  first, and `./name` meant that folder. Neovim now expands the name, so `:e %:h/name` (beside the
  current file) and `:e #` work.
- `:e name` of a file that already has an editor is now Neovim's own `:edit`: the window moves to
  that editor, as with `:b` and `Ctrl-^`, so `:e #` toggles between two editors and `Ctrl-O` goes
  back, as in one Vim window. Before, a second `:e #` found no alternate file (E499). A file without
  an editor still opens through Eclipse.

### Fixed

- A plug-in's or script's `:edit!` in Neovim (one that reloads files after a `git checkout`, say)
  no longer replaces an open file's text, unsaved changes included, with the file on disk, and no
  longer stops Neovim's changes from reaching the editor. The text, marks and cursor line stay as
  they were, and keys typed at that moment are kept. (A typed `:e!` already reverted the editor in
  Eclipse.)
- `Ctrl-I` now comes back through the same places `Ctrl-O` went. After a few `:e #` between two
  files, `Ctrl-O` went back about nine times and `Ctrl-I` forward about three: Eclipse's Back
  repeated every editor switch Neovim's jumps had made, and the first jump forward into the other
  file deleted Eclipse's Forward. Back now steps over those switches.
- `Ctrl-O` or `Ctrl-I` held down between two editors no longer goes back and forth for ever. Each
  move of a Neovim window to another editor left a stray jump in the other editor's window, so
  there was always one more jump, and Eclipse's Back found the other editor again.
- Enter that accepts a rename (`Alt+Shift+R`) begun in normal mode now ends insert mode too, as
  Esc already did, with the cursor on the name's first letter. Neovim stayed in insert mode, and so
  did the next editor you switched to.
- In a Java file whose header comment is folded with its `/*` line hidden, `O` on line 1 left the
  new line hidden, so the caret went to the comment's first line of text. The new line is now
  shown and the comment stays folded.

## [0.1.9] - 2026-10-28

### Added

- *Neovim → Restart Neovim...* stops Neovim and starts a new one, after asking: for a change to
  `init.lua` without restarting Eclipse, or a Neovim that is stuck. The editors' text is kept,
  and registers, marks and history come back from Neovim's ShaDa file; the undo history and what
  you set by hand (`:set`, `:map`) are lost.
- Neovim's `]d` and `[d` (and `]D`, `[D`) move between the errors, warnings and infos Eclipse shows
  in the file, with counts, in macros and in the jumplist; the message line then shows the
  problem's message. Eclipse's problems are Neovim diagnostics of the file's buffer, so your own
  diagnostics (nvim-lint) work alongside them. `vim.g.neovim4eclipse_problems = 0` in `init.lua`
  turns it off; `]d` then says so rather than "No more valid diagnostics". See the README, "Problems".
- Folding with Vim's `z` commands: in the Java editor (and other editors that fold), `zo`, `zc`,
  `za`, `zM`, `zR`, `zj`, `zk` and the rest open and close Eclipse's folds, `j` steps over a closed
  fold, and a fold collapsed in Eclipse is closed in Neovim too. `vim.g.neovim4eclipse_folds = 0` in
  `init.lua` turns it off. See the README, "Folding".
- `:w other.txt` writes a copy of the file, as in Vim, instead of being refused. The editor stays on
  its own file and keeps its unsaved changes; `:w!` overwrites an existing file, and `++p` creates
  missing folders. A copy over a file that another Eclipse editor has open with unsaved changes is
  refused.
- `:5,10w part.txt` writes those lines to another file, and `:w >> log.txt` (or `:5,10w >> log.txt`)
  adds them to the end of one, as in Vim. Both stay refused for the editor's own file, which Eclipse
  saves only whole.
- `:wq other.txt` and `:x other.txt` write the copy, then close the editor, as in Vim. Before, they
  ignored the name and saved the editor's own file. The copy goes where `:w other.txt` puts it: a
  relative name is relative to the project folder.
- `:saveas new.txt` saves the file as `new.txt` and moves the editor there, like *File → Save As*
  without the dialog. Neovim's undo history comes along; the old file stays as it was.

### Changed

- The *Neovim* menu has *Attach Neovim* again, and *Preferences...* is now last, below
  *Welcome...*, as in Eclipse's *Window* menu. *Attach* no longer opens a dialog when it works, and
  has no key any more (it was `Ctrl+Shift+N`; bind one in *General → Keys* if you miss it). In
  Quick Access the commands are now *Neovim: Attach Neovim* and *Neovim: Detach Neovim*, as in the
  menu.
- Moving into folded code no longer opens it unless your `'foldopen'` says so (Vim's default: a
  search opens a fold, `j` and `G` do not). `set foldopen=all` brings back the old behavior. The
  caret on a closed Java method stands on its signature.
- A `'foldmethod'` set in your `init.lua` no longer hides lines in Neovim that Eclipse shows: editors
  that cannot fold turn Neovim's folding off in their window.

### Fixed

- The toolbar button now always greys out after *Detach* (and enables again on *Attach*). It could
  stay clickable until you clicked elsewhere or switched editors, because Eclipse re-reads a toolbar
  button's enablement only when asked to, and the plug-in did not ask.
- Attaching Neovim to an editor whose caret is down in the file, when the file gets a new Neovim
  buffer (after *Restart Neovim...*, for example), no longer scrolls the caret's line to the bottom
  of the editor. The caret went to line 1 and back, following the new buffer's cursor.
- In insert mode, Enter after a `{` (which Eclipse answers with the closing brace and an indented
  line) could leave the caret at the start of the new line instead of after its indentation, when
  the line was opened with `O` or `o`.
- The plug-in no longer sets `'timeoutlen'` to 400 ms, over the value in your `init.lua`. With so
  short a wait, a pause between `[` and `d` ran Vim's older `[d` (E388: Couldn't find definition)
  instead of going to a problem. Neovim's default (1000 ms) or your own setting now applies.
- After `:w !cmd`, "Press any key to continue" shows once, in the `:` box, where Neovim asks for the
  key, instead of a second time at the bottom of the output above it.
- `:saveas other.txt` (and `:file other.txt` followed by `:w`) no longer saves the changes over the
  original file while Neovim calls the buffer by the new name. `:file other.txt` is now undone, since
  the editor's file stays the same.

## [0.1.8] - 2026-10-27

The plug-in itself is unchanged from 0.1.7; this release is for people who build it from source.

### Fixed

- The source repository imports into Eclipse again (*File → Import → Maven → Existing Maven
  Projects*). Since 0.1.7 the import stopped with "Resource '/shared' does not exist": the UI tests
  listed a folder outside their project, which Eclipse's plug-in tooling cannot resolve. A new test
  checks that every project keeps its folders inside it.

## [0.1.7] - 2026-10-27

### Changed

- A listing that fits on one line (`:set ts?`, `:ls` with one buffer, the file info of a typed `:b`)
  shows on the message line, as on Neovim's own screen, instead of opening the output box. Longer
  listings still open it.
- Output that comes while you type elsewhere in Eclipse (a view, a dialog, another editor), such as
  a timer's or an autocommand's, opens the output box without taking the keyboard from there. Like
  a message, it then closes with your next key or click in the editor. Output for the editor you
  type in takes the keyboard at once, as before.

### Fixed

- On a slow or busy machine, a `:` command that opens another editor (`:e new.txt`) no longer ends
  with "Type :qa and press <Enter> to exit Nvim" in place of its own message: the new editor taking
  the focus is no longer taken for a click away from the `:` box.
- A typed `:w | q` no longer quits the embedded Neovim (and with it every editor's Neovim) after a
  successful save, nor closes the editor without asking after a failed one: each command of a `|`
  chain goes to Eclipse like the first, so the editor closes as for `:q`, asking about unsaved
  changes. The same holds for `:w | bd`, `:echo 1 | q` and the other Eclipse commands.
- With a `'shortmess'` without `F` in your Neovim config (common in configs carried over from Vim),
  switching editors no longer shows Neovim's file info (`"a.txt" 3 lines --33%--`). Switching away
  and back no longer opens the output popup either, which took the keyboard focus, so that `jdd`
  after Eclipse's Undo deleted the wrong line. The plug-in adds `F` only while it switches; README,
  "Neovim settings the plug-in changes".
- Neovim's file info for an open file (`Ctrl-G`, `:file`, and with a `'shortmess'` without `F` a
  typed `:b`) no longer says `[Not edited]`: the plug-in names its buffers as `:e` does.
- The message of a command that moves Neovim into another open file, such as the file info of a
  typed `:b` without `F` in `'shortmess'`, now shows in that file's editor. It went to the editor
  being left, and was lost with it.

## [0.1.7] - 2026-10-26

### Added

- Every message Neovim gives now reaches the editor, whatever printed it: a mapping, a timer, an
  autocommand, a plug-in in your `init.lua`, a macro. Before, only the messages of keys and `:`
  commands were shown.

### Changed

- `:w` and `:update` are Neovim's own writes, which ask Eclipse to save: they show Vim's message
  (`"A.java" 12L, 340B written`), `:update` skips files without unsaved changes, `:w !cmd` pipes the text
  to a command as in Neovim (before, it saved the file), and `:w other.txt` is refused instead of
  saving the current file under its own name.
- `:w`, `:q`, `:e`, `:tabn` and the other commands Eclipse carries out are now read by Neovim, so they
  work from a mapping (`<leader>q` → `:q<CR>` closes the editor instead of quitting Neovim's window),
  a macro, `:normal` and `@:`, and are written exactly as in Neovim. A range of buffers works
  (`:3,5bd`), a count before a tab command (`:2tabn`), and a command after `|` still runs
  (`:w | echo "saved"`). New: `:Eclipse quit`, `:Eclipse! edit file` ... run them by Neovim's full name,
  for your own mappings.
- Neovim's `'modified'` now follows Eclipse's unsaved-changes marker: `:ls` shows `+` for exactly the
  files Eclipse would save, and `:bufdo update` writes those (before, a file saved with Ctrl+S still
  counted as changed, and one opened with unsaved changes did not).
- A buffer Neovim deletes by itself (a Lua plug-in, `<Cmd>bd<CR>`, `:bufdo bd`) now closes its editor,
  as `:bd` does, instead of leaving the editor on a buffer Neovim no longer has.
- A save that Eclipse cannot do, asked for by Neovim (`:wall`, `:bufdo update`, a mapping's `:w`),
  now fails the command, as a failed write does in Neovim: `:bufdo update` stops at that file instead of
  going on, and `:w | q` in a mapping no longer goes on to close the editor. The error is shown as
  Neovim gives it, with its `Error in BufWriteCmd Autocommands` line, in the output box.
- Messages are placed as Vim places them: one line on the message line (now also one-line `:echo`,
  `:pwd`, `:lua print()`), anything longer in the output box (two errors at once, a Lua error with its
  traceback), and listings and shell output (`:ls`, `:set ts?`, `:!make`) in the output box as before.
  Shell output that keeps coming is added to the open box.
- Listings from the `:` box (`:ls`, `:marks`, `:set ts?` ...) are typed into Neovim like every other
  command, so they are in the history, `@:` repeats them and a macro being recorded contains them.
- Commands from the `:` box are kept in Neovim's history as you typed them, without a `silent` in
  front.
- The `:`, `/` and `?` box is now Neovim's own command line, shown in the editor: every key goes to
  Neovim. Up and Down browse Neovim's history (the same as in a terminal, kept between sessions), Tab
  completes as your `'wildmode'` says, `Ctrl-R` inserts a register, and your command-line mappings and
  abbreviations work. The box also shows prompts (`input()`, `confirm()`), and opens for every command
  line Neovim opens, `:'<,'>` from visual mode included.
- While you type a search, the editor follows Neovim's match (`'incsearch'`); Esc goes back.
- In the `:` box the `Ctrl` letters ticked under *Preferences → Neovim → Ctrl Keys* go to Neovim's line
  (`Ctrl-U`, `Ctrl-N`, `Ctrl-P`, `Ctrl-R`), instead of running Eclipse shortcuts on the editor behind it.
- Switching to another application or input method (Super+Space for Japanese input) while the `:`
  box is open keeps the line; only a click elsewhere in Eclipse cancels it.
- The mouse in normal and visual mode is Neovim's: a double click selects a word, a triple click the
  line (`V`), dragging selects in visual mode, and Shift+click extends the selection, so `d`, `y` and
  `>` act on what you selected. Before, the editor made a selection of its own that Neovim did not
  know about, and `x` after dragging deleted one character. Ctrl/Cmd+click, the right and middle
  buttons, and the mouse in insert mode stay Eclipse's.
- `ZZ` and `ZQ` are now mappings in Neovim, so Neovim reads them as it reads any key: `fZ` typed
  quickly finds a `Z` instead of waiting for a second one, and `ZZ` in a macro saves and
  closes the editor. Every key outside insert mode now goes straight to Neovim; the plug-in no
  longer guesses Vim's grammar to decide.
- `:e` file names complete from the editor's project folder: Neovim's window now works there (`:lcd`).
  `:e` still looks next to the current file first.
- `q:` (Neovim's command-line window, which Eclipse cannot show) now closes at once with a message
  instead of leaving Neovim in a window you cannot see.
- The `Ctrl-V` block's highlight is now measured by Neovim itself, so it always covers exactly what
  Neovim's block commands act on, also for characters whose screen width the plug-in had to guess
  (emoji, rarer wide characters, your `'ambiwidth'`).

### Fixed

- `o` on the last line of a file without a final newline showed two new empty lines in Eclipse
  (and saved them), though Neovim had one and its motions could not reach the second. The empty
  line it adds now also stays a line after Esc, as in Vim: `Go` goes to it.
- Ctrl+Z back to the saved text after a Neovim edit (`yyp`, `dd`) left the file marked as changed,
  from the second time on. Ctrl+Z now undoes one Neovim command at a time (all of a `J` at once).
- After `:w` (or another `:` command Eclipse runs), the status bar could stay on `-- COMMAND --`
  in normal mode until the next key.

## [0.1.6] - 2026-10-26

### Changed

- **Neovim 0.12.6 or newer is required** (was 0.9). The plug-in now lets newer Neovim features do
  work it would otherwise imitate, and will raise this minimum again when a release helps that or
  fixes a security issue. If your system's Neovim is older, extract the release archive from
  <https://github.com/neovim/neovim/releases> anywhere and set its `bin/nvim` in
  *Window → Preferences → Neovim*.

### Added

- *Window → Preferences → Neovim* takes a folder that holds Neovim: its install folder (the one with
  `bin/nvim`), or a folder with release archives extracted into it, up to four levels down (so also
  as Windows Explorer's *Extract All* leaves them), with a *Browse...* button, and shows which Neovim
  it finds and its version. Left blank (the new default), it looks in the plug-in's own Neovim
  folder first, `~/.local/share/neovim4eclipse-plugin/neovim` (`$XDG_DATA_HOME/...` when set; `~`
  is `HOME`, on Windows `%USERPROFILE%` when `HOME` is not set), then on `PATH`, then in the usual
  install folders, now also Neovim's own `/opt` folders and per-user Windows installs. Several
  Neovims side by side in one folder: the newest that runs is used. An absolute path to `nvim` set
  before keeps working. Under the field's note, a line says what the field takes and links to
  [Finding Neovim](https://github.com/Jm6CnDDXBwFTakS/neovim4eclipse#finding-neovim) in the README;
  when nothing is found, the note says whether the default folders or the value entered came up
  empty, and how to go on. A note about a Neovim that would not start is shown in the theme's error
  color, with an error mark on the field.
- A Neovim older than the minimum is refused at start, with a message naming its version and the
  one needed, instead of failing later in unclear ways.

### Removed

- The Neovim preference no longer takes a command name such as `nvim` (the old default) or a
  relative path: a value must be an absolute path, to a folder or to the program, and anything else
  stops Neovim from starting with an error saying so. To use the `nvim` on `PATH`, leave the field
  blank. The old default was never saved, so an untouched preference is blank already.
- The home folder itself and `/snap` are no longer among the usual install folders searched on
  macOS and Linux; `~/bin/nvim` still is.

## [0.1.5] - 2026-10-25

### Added

- A welcome note the first time Neovim starts in an editor: how to turn it off and on, where the
  preferences are (including which `Ctrl` keys go to Neovim), and a link to the documentation.
  *Neovim → Welcome...* shows it again. After an update to a new minor version, a short note links
  to this change log instead. Both can be turned off on *Window → Preferences → Neovim*.

### Changed

- The toolbar button that toggles Neovim and Eclipse mode shows the project's logo instead of the
  text *NVIM* (sharp on 200% screens too). The preference that hides it is now named *Show the
  Neovim button on the main toolbar*.
- The toolbar button shows the active editor's mode: pressed with the color logo in Neovim mode,
  raised with a grey logo in Eclipse mode (macOS barely shows a pressed button), and greyed out
  while the editor in front has no Neovim attached. Its tooltip names the mode and the key that
  toggles it; greyed out, it says why: not a text editor (an image, a form page), or a text editor
  that `Ctrl+Shift+N` would attach Neovim to. In the *Neovim* menu the entry is now a checkbox,
  *Neovim Mode*.

### Fixed

- *Neovim: Attach to Active Editor* (`Ctrl+Shift+N`) on an editor without text (an image, a
  manifest's Overview page) says that Neovim works only in text editors, instead of seeming to do
  nothing.
- `g??` (ROT13 the current line) works: the second `?` no longer opens the backward-search box
  instead of finishing the command, and a quickly typed `g?` no longer can either. `g?` with a
  search motion (`g?/x`) and `?` after `g?` in visual mode still open the box.
- `:` after `di"`, `yi'`, ``di` ``, `vi"` (any quote naming a text object), after `gqq`, or after
  `<C-w>q` opens the command-line box again; it went to Neovim's own, invisible command line. The
  `q` of `gqq` and `<C-w>q` no longer counts as the start of a macro recording. `[/` and `]/` (to
  a comment's start or end) no longer open the search box when typed quickly, and neither does a `:`
  typed right after `o`, `s` or another key that starts an insert.
- A command line opened in Neovim rather than by `:`, `/` or `?` typed in the editor now opens the
  command-line box, with what Neovim has typed so far: a mapping from your `init.lua` such as
  `vim.keymap.set('n', ';', ':')` or `vim.keymap.set('n', '<Space>s', ':%s/')`, a count before `:`
  (`2:`), `!` with a motion. Before,
  Neovim waited in its own command line, which Eclipse does not show. A search opened this way inside
  an operator (`d/` from a mapping) keeps the operator.
- An error in your Neovim configuration (`init.lua`) no longer stops Neovim from starting with
  "Neovim not started: TimeoutException": Neovim waited at a "Press ENTER" prompt Eclipse does not
  show. Neovim now starts with what the configuration set up before the error, and a warning shows
  the error.
- A command whose only output is its status message (`:%s` printing "7 substitutions on 4 lines") no
  longer also opens the output popup; the message line shows the message, as for errors.

## [0.1.4] - 2026-10-24

The signed release: Eclipse now names who signed the plug-in.

### Security

- Releases are signed with the project's PGP key (fingerprint
  `A91F 53D8 931B 7FD0 D146  8618 6CC9 E446 147B 063C`). Eclipse names the key when it asks you to
  trust the plug-in, instead of warning about *unsigned content*, and it refuses a jar that was
  changed after signing. Once installed, the plug-in vouches for its own key, so updates install
  without asking. README, "Install", has the steps.

## [0.1.3] - 2026-10-21

The update site release: install once from a URL, and *Check for Updates* finds what comes next.

### Added

- A permanent update site, <https://jm6cnddxbwftaks.github.io/neovim4eclipse/>: add it once in
  *Install New Software*, and *Check for Updates* finds every later release. The ZIPs on the
  Releases page still work.
- `:bd 3` and `:bd other.txt` (and `:bw`, `:bun`) close the editor of that buffer, and `:%bd` closes
  all editors. Before, they went to Neovim, which could empty the editor of that file.

### Changed

- *Install New Software → Details* now says what the plug-in needs before you install it: Neovim
  0.9 or newer, installed separately, and Eclipse 2024-12 (4.34) or newer on Java 21.

## [0.1.2] - 2026-10-21

A bug-fix release.

### Fixed

- `:bd` (and `:bw`, `:bun`) closes the editor, as `:q` does. It used to empty the editor and
  mark it modified.
- The cursor position in Eclipse's status bar (`11 : 2 : 179`) follows Neovim's cursor. It used to
  stay where Eclipse last saw a key or click; Mark Occurrences and the Outline view's linking now
  follow too.

## [0.1.1] - 2026-10-21

A bug-fix release.

### Fixed

- `:e new.txt` for a file that does not exist yet inside a project now opens an empty editor you can
  type in. Before, Eclipse showed "File ... does not exist." in place of the text, so keys and `:w`
  did nothing (files outside the workspace were not affected). After the first `:w` the editor
  becomes an ordinary editor on the project's file, and Neovim keeps the buffer's undo history.

## [0.1.0] - 2026-10-20

The first release. Neovim for Eclipse embeds a real Neovim (`nvim --embed`) in Eclipse text editors:
Neovim does the editing, Eclipse keeps the files, the rendering and its own tools.

### Added

- Normal, visual and operator-pending mode run in Neovim: motions, text objects, operators, counts,
  registers, marks, undo, and your own `init.lua`.
- `vim.g.neovim4eclipse` is set before your config runs, so `init.lua` can leave out what does not
  belong in Eclipse (`if vim.g.neovim4eclipse then ... end`).
- Insert and replace mode type in Eclipse, so content assist and input methods (IME) keep working;
  `.` repeats the inserted text as in Vim. One `Esc` leaves insert mode also when Eclipse takes it
  first, to leave linked editing (a Java rename, a template's fields) or close the completion popup.
- Each open document has its own Neovim buffer, with its own undo history and marks. Jumps into
  another open file (`Ctrl-O`, `'A`, `:b`) activate its editor; changes made with `:bufdo` and
  `:argdo` reach the other editors.
- File and tab commands go to Eclipse: `:w`, `:wa`, `:q`, `:qa`, `:wq`, `:x`, `:xa`, `ZZ`, `ZQ`,
  `:e file`, `:e!`, `:tabn`, `:tabp`, `gt`, `gT`. `gd` opens the declaration and `K` shows hover
  information through the editor's own commands; where Eclipse knows no definition (a plain text
  file, in the Text Editor or the Generic Editor), `gd` is Vim's own.
- `:e newfile` opens an empty editor next to the current file, and `:w` creates the file (with Vim's
  `"new.txt" [New] 1L, 6B written`); `:w ++p` also creates missing folders. A failed write keeps
  `ZZ` and `:wq` from closing the editor.
- An inline command line for `:`, `/` and `?`, with separate histories. In `:`, Tab and Shift+Tab
  complete as in Neovim (following `'wildmode'`), with the candidates listed above the box; `:e`
  completes file names next to the current file, then in its project. A name starting with `./` or
  `../` is looked for, and completed, from the current file's folder only.
- Vim's mode, pending command, recording register and more in the Eclipse status bar, chosen in
  *Preferences → Neovim → Status Bar*.
- Neovim's messages and errors in a line along the bottom of the editor, errors in the error color,
  until the next key. *Preferences → Neovim → Flash the editor on errors* (off by default) adds a brief
  flash, like Vim's `'visualbell'`.
- Macros (`q`, `@`), including insert mode while recording.
- Blockwise visual mode (`Ctrl-V`), with `I`, `A`, `c`, `$` and a rectangle drawn in screen columns.
- Eclipse as Neovim's clipboard provider: `"+` and `"*` use the system clipboard, with no external
  tool needed.
- Indentation options (tabs or spaces, width) read from each document, so `o`, `O` and `>>` indent
  like the file.
- Preferences: the Neovim executable, the toolbar toggle, whether the *Neovim* menu is in the menu
  bar, and which `Ctrl` keys go to Neovim.
- `-Dneovim4eclipse.logLevel=FINE` (or any other `java.util.logging` level) prints the plug-in's
  details to the console, one line each; see the README, "If `Ctrl-V` does nothing".
