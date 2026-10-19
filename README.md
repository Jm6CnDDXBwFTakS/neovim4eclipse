# Eclipse Neovim

Embeds **real Neovim** (`nvim --embed`) in Eclipse text editors so modal editing (motions, operators,
text objects, visual mode, registers, macros, ex commands) works on the buffer you see in the IDE.
Neovim owns editing; Eclipse owns files and rendering.

**Status: working prototype** for developers who will extend it. It has not been run through a
release process; see [Known limitations](#known-limitations).

**Documentation**

- [docs/LEARNING.md](docs/LEARNING.md): how Eclipse plug-ins and the Neovim API work, as used here.
  Start here if you want to understand or maintain the code.
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): design decisions and how each feature works.
- [docs/KEYS.md](docs/KEYS.md): which keys go to Neovim and which to Eclipse, and why.
- [CONTRIBUTING.md](CONTRIBUTING.md): building, testing, the rules the code relies on, and style.
- [docs/PACKAGING.md](docs/PACKAGING.md), [docs/HOWTO_ECLIPSE.md](docs/HOWTO_ECLIPSE.md),
  [docs/PLATFORM.md](docs/PLATFORM.md): building, running from the IDE, platform notes.
- [docs/UPGRADING.md](docs/UPGRADING.md): moving to a newer JDK, Eclipse, library, tool or Neovim.
- [docs/RPC_CLIENT.md](docs/RPC_CLIENT.md): replacing neovim-java with our own msgpack-rpc
  client (in progress, step by step).

## How it behaves

| Eclipse is responsible for | Neovim is responsible for |
|----------------------------|---------------------------|
| Displaying text, caret, visual selection, status line | Motions, text objects, operators, macros, marks, registers, counts, undo |
| Typing in **insert/replace** mode (so content assist and IME keep working) | Everything in normal / visual / operator-pending mode |
| File and tab commands: `:w` `:wa` `:q` `:qa` `:wq` `:x` `:xa` `ZZ` `ZQ`, `:e file`, `:e!`, `:tabn` `:tabp`, `gt` `gT` | All other `:` commands, e.g. `:%s/a/b/g` |
| `gd` (Open Declaration) and `K` (hover information), through the editor's own commands | Counts and the `g` prefix of those keys |
| The inline command line for `:`, `/`, `?` (typing, history) | Executing the command or search typed there |
| The OS clipboard behind the `"+` and `"*` registers, and a copy of every other yank (see [Clipboard](#clipboard)) | `p` / `P` / `.` / counts, from Neovim's own registers |

Details of what happens on each key are in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Prerequisites

- **JDK 21 or newer** for the build. Maven itself is not needed: `./mvnw` (Windows: `mvnw.cmd`) downloads
  the version pinned in `.mvn/wrapper/maven-wrapper.properties`.
- Eclipse IDE with PDE, **2024-12 (4.34) or newer** (the supported minimum; the build targets it), running on Java 21+, with a **JDK 21** registered for the JavaSE-21 execution environment
- **Neovim 0.9+** reachable from the Eclipse process (`nvim --version`), or an absolute path set in
  *Window → Preferences → Neovim*

> Eclipse started from a desktop launcher may not inherit your shell `PATH`. Start it from a
> terminal, or set the absolute path to `nvim` in the preferences.

## Run it (PDE)

1. Run `./mvnw generate-resources` once (or any full build) to download the Java dependencies into
   `plugins/io.github.jm6cnddxbwftaks.eclipseneovim/lib/`. Their versions are set only in the root `pom.xml`.
2. *File → Import → Existing Projects into Workspace* → `plugins/io.github.jm6cnddxbwftaks.eclipseneovim`.
3. Open `META-INF/MANIFEST.MF` → *Launch an Eclipse application*. See [docs/HOWTO_ECLIPSE.md](docs/HOWTO_ECLIPSE.md).
4. Open a text file; Neovim attaches automatically (or press `Ctrl+Shift+N`, *Neovim: Attach to Active Editor*).

> **Upgrading from the old `com.example.eclipsseneovim` id?** The bundle, packages, command/context ids and
> preference node were renamed to `io.github.jm6cnddxbwftaks.eclipseneovim`. Remove the old projects from your
> workspace and import the renamed ones, and delete any old *Run Configuration* that lists the old bundle.
> Stored preferences (executable path, toolbar toggle) were under the old id and must be set again.

Things to try: `hjkl`, `diw`, `ci"`, `dd`, `u`, `v` + motion, `i … Esc`, `:` then `%s/foo/bar/g`, `/pattern`.

## Build and test

```bash
./mvnw clean verify                          # plug-in + unit, text-model and SWT tests + feature + p2 update site
./mvnw clean verify -Pui                     # the same plus the UI tests, which open an Eclipse window
./mvnw verify -DskipTests                    # the same without any tests (no display needed)
./mvnw test -f tests/unit/pom.xml            # unit tests only (no Eclipse, no Neovim needed)
./mvnw test -f tests/unit/pom.xml -Pintegration   # needs `nvim` on PATH (or -Dnvim.path=...)
```

There are two test modules:

- `tests/unit` is a plain Maven jar module: part of the reactor, and also runnable alone. It compiles and
  tests the **headless** packages (`util`, `core`) without Eclipse.
- `tests/io.github.jm6cnddxbwftaks.eclipseneovim.tests` is a **test fragment** of the plug-in. Tycho starts
  a real Eclipse workbench with the plug-in installed and runs its tests on the UI thread: the Eclipse glue
  (`editor`, `prefs`) on real SWT widgets, and end-to-end tests that open a text editor, type keys and check
  the document. The end-to-end tests need `nvim` (skipped otherwise; `-Dnvim.path=...` selects one).
  The workbench needs a display: it opens briefly on your desktop, and on a headless Linux machine run the
  build under `xvfb-run -a`.

The p2 update site is produced under
`releng/.../target/repository/`, and as the installable archive
`releng/.../target/EclipseNeovim-<version>.zip` (see [docs/PACKAGING.md](docs/PACKAGING.md)).

### Continuous integration

GitHub Actions ([.github/workflows](.github/workflows)) runs the same commands:

- **Build** runs on every push and pull request to `main`, or on demand (Actions tab > Build >
  Run workflow). It runs `./mvnw verify -Pui` (UI tests under `xvfb-run`) and the integration tests against a pinned Neovim
  release, and attaches the installable ZIP to the run as it is: `EclipseNeovim-<version>.zip`, ready for
  *Install New Software → Add → Archive* without unpacking.
- **Release** runs when you push a tag such as `v0.1.0`. It builds that version (the tag replaces
  `0.1.0-SNAPSHOT`) and publishes a GitHub Release with `EclipseNeovim-0.1.0.zip` attached.

## Layout

```text
plugins/io.github.jm6cnddxbwftaks.eclipseneovim/
  src/io/github/jm6cnddxbwftaks/eclipseneovim/
    util/     pure Java: key mapping, mode strings, line/text conversion, range edits, UTF-8 columns
    core/     NeovimSession (process + RPC + events); no SWT/Eclipse imports allowed
      rpc/    the plug-in's own Neovim API interface and types, replacing neovim-java step by step
    sync/     BufferSynchronizer: IDocument <-> Neovim buffer
    editor/   attach/detach, key interception, caret/status UI, :w/:q handling
    prefs/    preference pages (Neovim, Ctrl Keys) and defaults
  lib/        neovim-java jars (generated by the build; versions in the root pom.xml)
tests/unit/   JUnit 5 tests for util + core (sources come from the plug-in; no copies)
features/     feature project (Tycho eclipse-feature)
releng/       p2 category / update-site (Tycho eclipse-repository)
docs/         LEARNING, ARCHITECTURE, HOWTO_ECLIPSE, PACKAGING, PLATFORM, UPGRADING, RPC_CLIENT
.github/      GitHub Actions workflows (build, release), Dependabot, issue templates
pom.xml       Maven/Tycho parent (aggregator)
```

## Status bar

*Window → Preferences → Neovim → Status Bar* chooses which parts of Vim's status information appear in the
Eclipse status bar; changes apply immediately:

| Item | Shows | Default |
|------|-------|---------|
| `mode` | `-- INSERT --`, `-- VISUAL BLOCK --`, `recording @q` | on |
| `cmd` | the pending command as Vim's `showcmd` shows it: `2d`, `"a`, `2f` | on |
| `msg` | the latest Neovim message or error (`E486: Pattern not found: x`), until the next key | on |
| `statusline` | Neovim's own `'statusline'` (your config's, or Vim's default: file, line,col, position) | off |

The parts are joined in that order. `-- ECLIPSE --` is always shown while Neovim mode is off. Only the enabled
parts are requested from Neovim. `cmd` needs Neovim 0.9 or newer (`showcmdloc`). `msg` shows messages
Neovim gives through its status-message and error variables; messages from `:echo`, and normal messages of
`:` commands (typed as `:silent`) are not included, but errors are.

## Command line (`:`, `/`, `?`)

`:`, `/` and `?` open a single text box near the bottom edge of the editor, as wide as the editor
minus a small padding and in the editor's font. Enter runs it; Esc or Backspace on the
empty box cancels; clicking elsewhere also cancels it; Up/Down browse a history (kept separately for commands and
searches). `:` from visual mode leaves visual mode and pre-fills `:'<,'>`. While the box has focus the
Vim `Ctrl` bindings are off, so `Ctrl-V` pastes into it.

File and tab commands are carried out by Eclipse, everything else by Neovim.

| Command | Eclipse does |
|---------|--------------|
| `:w`, `:up[date]` | save the editor (`:w file` ignores the name) |
| `:wa[ll]` | *File > Save All* |
| `:q[uit]` | close the editor (Eclipse asks about changes; `:q!` discards them) |
| `:qa[ll]`, `:quita[ll]` | close all editors (`:qa!` without asking) |
| `:wq`, `:x[it]`, `:exi[t]` | save, then close |
| `:wqa[ll]`, `:xa[ll]` | save all, then close all |
| `:e[dit] file` | open `file`, looked for next to the current file, then in its project; `~` and absolute paths work |
| `:e!` | revert the editor to the saved file; `:e` alone reloads it, refused while there are changes |
| `:tabn[ext] [N]`, `:tabp[revious] [N]`, `:tabN[ext] [N]` | next / previous editor tab, or tab N / N tabs back |
| `:tabfir[st]`, `:tabr[ewind]`, `:tabl[ast]` | first / last editor tab |

The commands above are the ones typed in the `:` box. Neovim can also write a buffer by itself:
`:update` inside `:bufdo` or `:argdo`, `:wall`, or a mapping in your `init.lua` such as
`<leader>w` → `:w<CR>`. Those writes save the file through Eclipse too (so save actions run as usual),
and Neovim never writes a file itself. A write under another name (`:w other.txt`) is refused; use
*File → Save As*.

Commands that only display something (`:ls`, `:buffers`, `:jumps`, `:marks`, `:registers`, `:changes`,
`:messages`, `:pwd`, `:echo`, `:set opt?` ...) show their output in a read-only box in the same place.
`j`/`k`, Space/`b`, `g`/`G` and the arrow and page keys scroll it; any other key or a click closes it.
These commands are not recorded into a macro (they change nothing). Any other command is typed into
Neovim, and whatever it prints (`:!ls`, `:echo` inside a longer command, a `:s` report) is collected with
`:redir` and shown in the same box. Errors appear in the status bar only. Messages printed later, by a
timer or an autocmd, are not shown, and nothing is collected while your own `:redir` is running.

## Indentation (`o`, `O`, `>>`)

Neovim indents the lines it creates. When a document is attached, the plug-in sets that buffer's
options to match the file:

- **Tabs or spaces, and the width of one level**, are read from the document's own indented lines.
  A file with none yet uses *Preferences › General › Editors › Text Editors › Insert spaces for tabs*.
  `'tabstop'` is always the editor's tab width.
- **Brace languages** (Java, C/C++, C#, JavaScript/TypeScript, Kotlin, Go, Rust, CSS, JSON, ...) get
  `'cindent'`: `o` on a line ending in `{` and `O` on a line starting with `}` start one level in.
  Other files keep `'autoindent'` (the new line copies the current line's indent).

These options replace any your `init.lua` sets for the buffer. Filetype detection is not used, so
your filetype plugins (and language servers they may start) do not run in the embedded Neovim.

## Macros (`q`)

`qa` starts recording into register `a` (the status line shows `recording @a`), `q` stops. Play back with
`@a`, `3@a`, `@@` (last macro) and `@:` (last `:` command). Recording and playback are done by Neovim itself;
the plugin makes sure everything you do is seen by Neovim as keys:

- **Insert mode while recording** is driven by Neovim (every key is forwarded, as for block `I`/`A`), so the
  macro contains exactly what you typed, including arrow keys and Backspace. Eclipse's content assist and
  IME composition are therefore unavailable *while recording*.
- **`:` commands and `/` searches** from the command line are typed into Neovim (`:silent <cmd>`), so they are
  recorded and `@:` works. `:w`, `:wa`, `:q`, `:wq`, `:x` are still handled by Eclipse and are **not** recorded; a
  `:w` inside a macro is run by Neovim, which asks Eclipse to save (see below). The editor gets the macro's
  changes only when it ends, so such a save writes the text from before the macro.
- **Playback** is applied to the editor as one change when the macro finishes, which is much faster than
  replaying each step and avoids half-finished states. Very long macros are given up to 60 seconds.
- `f/`, `t:`, `r/`, `@:`, `qa` ...: the character after `f F t T r @ m ' \` " q` is an *argument* and always goes
  to Neovim (previously `f/` opened the search box).

Not recorded: mouse clicks and other actions that happen only in Eclipse. Not supported: the command-line
window (`q:`, `q/`, `q?`). Macros live in Neovim's registers and last until Neovim restarts (for example
after changing the executable preference).

## Repeating inserted text with `.`

`A`, type some text, `Esc`, then `j` and `.` repeats the typed text on the next line (likewise `cw`, `o`,
`i`, ... followed by text, and `".p`). Eclipse does the typing; when you press Esc the plugin re-types that
text into Neovim's insert mode so Neovim records it for `.`, then makes Eclipse's text authoritative again.

This applies when the insert was a plain insertion at one spot. It is **not** recorded (the text stays
correct, but `.` repeats only the command without the text) when you delete text before the insertion
point, edit in two places in one insert, or paste more than 1000 characters. Counts (`3Afoo`) and Replace
mode (`R`) are also not repeated by Neovim in this mode. Insert-mode mappings and abbreviations from your
`init.lua` are cleared in the embedded Neovim (Eclipse never applied them anyway), so that they cannot
hijack the re-typed text.

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
`c`, `r`, `I`, `A`, `p` ... applied by Neovim. The rectangle is measured in screen columns (tabs and wide
characters count correctly) and drawn as an overlay. On attach, Neovim's `tabstop` is set to the editor's
tab width so both agree.

**Key bindings.** Eclipse handles shortcuts before the editor sees keys (`Ctrl-V` is *Paste* on
Windows/Linux), so `plugin.xml` binds every `Ctrl+letter` to a command that sends the key to Neovim.
Each letter's binding lives in its own key-binding context, and the editor activates only the contexts
of the letters you send to Neovim (*Window → Preferences → Neovim → Ctrl Keys*; by default every letter
except `Ctrl+S`, `Ctrl+W` and `Ctrl+Z`, see [docs/KEYS.md](docs/KEYS.md)). The bindings work only while Neovim owns normal/visual mode and the
editor has focus; in insert mode and in Eclipse mode (toggle with the toolbar button) Eclipse's own
shortcuts apply. On **macOS use the physical Control key**, as in MacVim or IdeaVim; Command-V remains
the system Paste.

**If `Ctrl-V` does nothing**, look for these lines in the console of the Eclipse process: the
terminal you started Eclipse from, or the Console view of the Eclipse that launched a runtime Eclipse.
The first three are written when Neovim mode turns on in an editor, not when you press `Ctrl-V`.
The warnings (the third and the last line) also appear in the Error Log (*Window › Show View › Error Log*).

The first two are details (level FINE), hidden by default. To see them, create a file such as
`vim-logging.properties` with

```properties
handlers = java.util.logging.ConsoleHandler
java.util.logging.ConsoleHandler.level = FINE
io.github.jm6cnddxbwftaks.eclipseneovim.level = FINE
```

and start Eclipse with `-Djava.util.logging.config.file=/path/to/vim-logging.properties` (after
`-vmargs` in `eclipse.ini`, or in the *Arguments* tab of a runtime Eclipse launch). The plug-in's
other details (each attach and detach, cursor pushes) then appear too.

| Log line | Meaning |
|----------|---------|
| `Neovim key context activated` | the context is on; if absent, the editor did not have focus or Neovim mode is off/insert |
| `Ctrl-V (M1+V) resolves to the Neovim Ctrl-key command` | binding works; the key goes to Neovim |
| `Ctrl-V (...) resolves to '<other command>'` | another binding outranks ours, or the context is not in effect |
| `Ctrl chord reached the editor key listener` | the key got past the workbench (so a binding is not consuming it) |
| `Ctrl-key command ran but there is nothing to send to` | the binding fired but no editor is attached (the line names the command and key) |

## Preferences

*Window → Preferences → Neovim* (also *Neovim → Preferences...*, which opens it with its sub-pages
expanded): the Neovim executable (command or absolute path) and whether to show the toolbar toggle.
Changing the executable restarts Neovim and re-attaches the active editor.

*Window → Preferences → Neovim → Status Bar*: which parts of the Vim status appear in the status bar.

*Window → Preferences → Neovim → Ctrl Keys*: one check box per letter. Ticked `Ctrl+letter` keys go to
Neovim in normal and visual mode; cleared ones keep their Eclipse shortcut. *Select All* and *Deselect
All* set every box; *Restore Defaults* ticks all but `Ctrl+S`, `Ctrl+W` and `Ctrl+Z`. Changes apply
immediately. [docs/KEYS.md](docs/KEYS.md) explains those three defaults and which keys go where.

## Known limitations

These are real and deliberate to know about before extending the code:

1. **One Neovim window.** Each open document has its own Neovim buffer (named after the file, so
   undo history and marks survive switching editors), but only the active editor is attached.
   Jumps into another open file (Ctrl-O, `'A`, `:b`) activate its editor (after `:bufdo` or `:argdo`,
   only the editor of the file they end in); jumps to anything else
   (`:e`, `:help`, a closed file) are undone. A file's buffer, with its undo history, is discarded
   when its last editor closes. Changes Neovim makes to another open file (`:bufdo`, `:argdo`)
   reach that file's editor, which is then dirty: save them with `:wa`, *File → Save All*, or in the
   same command (`:bufdo %s/old/new/g | update`).
2. **Block-visual `I` / `A` / `c` disable Eclipse typing for that insert.** To let Vim repeat the text on
   every line, all keys go to Neovim until Esc, so content assist and IME composition do not work in that
   one kind of insert. Plain `i`, `a`, `o` ... are unaffected.
3. **Host commands are a fixed set** (table under *Command line*). `:w <otherfile>` saves the current editor and ignores
   the name; `:e` cannot create a new file and does not expand `%` or wildcards; tab commands only move
   within the current editor stack (one half of a split editor area).
4. **No empty last line without a final newline.** A file that does not end with a line break
   cannot show a trailing empty line (the text would be identical to a file that does). This only
   matters briefly, e.g. right after `o` on the last line of such a file.

## Maintenance rules

- Never run blocking RPC on the SWT thread (causes freezes on `ciw`); use the `*Async` methods.
- Never full-pull the buffer after every key; rely on line events (`NvimKeys.isBufferMutating` lists the exceptions).
- Neovim columns are UTF-8 **bytes**; Eclipse's are UTF-16 chars. Convert with `Utf8Columns`.
- Keep `util/` and `core/` free of `org.eclipse.*` imports; the Maven build enforces it (`tests/unit` compiles them with no Eclipse jars available).
- Keep `Bundle-RequiredExecutionEnvironment` aligned with the target Eclipse (**JavaSE-21**).

## AI assistance

This project is developed with AI assistance: much of the code, tests and documentation was written
with [Claude Code](https://claude.com/claude-code), Anthropic's coding assistant, under the
maintainer's direction and review. Every change goes through the same checks as hand-written code:
the unit, text-model, SWT and UI test suites, mutation checks of new tests (break the code on
purpose, see the test fail), and manual checks on Linux, macOS and Windows.

The instructions such tools follow in this repository are in [AGENTS.md](AGENTS.md) (Claude Code
reads it through `CLAUDE.md`): the commands, the rules the code depends on, and the conventions.
They are the same rules a human contributor follows ([CONTRIBUTING.md](CONTRIBUTING.md)).

## License

Copyright (c) 2026 Jm6CnDDXBwFTakS.

Licensed under the [Eclipse Public License 2.0](LICENSE), the license of the Eclipse Platform
itself. Each source file carries a short [SPDX](https://spdx.dev/) header that says the same thing.

See [ACKNOWLEDGEMENTS.md](ACKNOWLEDGEMENTS.md) for the project whose design this one follows and for
the embedded libraries.
