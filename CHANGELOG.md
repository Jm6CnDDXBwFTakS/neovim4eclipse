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
