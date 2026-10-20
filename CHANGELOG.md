# Changelog

What changed in each release of Eclipse Neovim, newest first, for people who install it. The format
follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/): each release has a section, and
inside it the groups *Added*, *Changed*, *Deprecated*, *Removed*, *Fixed* and *Security*, as needed.
Version numbers follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html); before 1.0.0, a
minor version (0.2.0) may change behavior.

New entries go under **Unreleased** with the change that makes them. A release renames that heading
to the version and date, and the release workflow publishes that section as the release notes: it
stops if the section is missing (see CONTRIBUTING.md, "Releasing").

## [Unreleased]

The first release. Eclipse Neovim embeds a real Neovim (`nvim --embed`) in Eclipse text editors:
Neovim does the editing, Eclipse keeps the files, the rendering and its own tools.

### Added

- Normal, visual and operator-pending mode run in Neovim: motions, text objects, operators, counts,
  registers, marks, undo, and your own `init.lua`.
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
- An inline command line for `:`, `/` and `?`, with separate histories. In `:`, Tab and Shift+Tab
  complete as in Neovim (following `'wildmode'`), with the candidates listed above the box; `:e`
  completes file names next to the current file, then in its project.
- Vim's mode, pending command, recording register and more in the Eclipse status bar, chosen in
  *Preferences → Neovim → Status Bar*.
- Macros (`q`, `@`), including insert mode while recording.
- Blockwise visual mode (`Ctrl-V`), with `I`, `A`, `c`, `$` and a rectangle drawn in screen columns.
- Eclipse as Neovim's clipboard provider: `"+` and `"*` use the system clipboard, with no external
  tool needed.
- Indentation options (tabs or spaces, width) read from each document, so `o`, `O` and `>>` indent
  like the file.
- Preferences: the Neovim executable, the toolbar toggle, whether the *Neovim* menu is in the menu
  bar, and which `Ctrl` keys go to Neovim.
- `-Declipseneovim.logLevel=FINE` (or any other `java.util.logging` level) prints the plug-in's
  details to the console, one line each; see the README, "If `Ctrl-V` does nothing".
