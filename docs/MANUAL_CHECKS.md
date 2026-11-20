# Manual checks before a release

Most of what the plug-in does is checked by its tests ([CONTRIBUTING.md](../CONTRIBUTING.md), "Build
and test"). This file lists what is left: the few checks that need a person, because they depend on
something a test cannot fake (a real download, macOS's Gatekeeper or input methods), or
on eyes (the gallery of dialogs, for the overall look), or because they *are* the release (the full
test runs on each platform, the install from the update site). Each is a *card* with an id, such as `gn-download`.

**Why so few.** A check stays here only while no test can do it. When a card can become a test, it
should: a test runs on every change and on every platform, a card only when someone has the time.
For 0.2.1, tests replaced 49 of the 60 cards its list once had. When you add a feature that needs a look
by hand, add a card here in the same commit, with the source files it depends on.

## How to run them

`releng/tools/checks/ManualChecks.java` reads this file. Run it from the repository's root with a
JDK of release 22 or newer, as the Release tool (it is several source files, which `java` runs
without a build only from that release on; the plug-in itself stays on 21):

```bash
java releng/tools/checks/ManualChecks.java due              # the cards this release needs, and why
java releng/tools/checks/ManualChecks.java setup            # make the test project and folders
java releng/tools/checks/ManualChecks.java launch gn-download   # prepare the folders, start Eclipse for a card with the plug-in just built
java releng/tools/checks/ManualChecks.java list             # every card
java releng/tools/checks/ManualChecks.java json             # the cards as JSON, for a page that keeps ticks
```

The tool runs `git` (for `due`) and starts your Eclipse (for `launch`). Like every tool under
`releng/tools`, it runs on Linux, macOS, and in Git Bash on Windows; from cmd or PowerShell it stops
at once (CONTRIBUTING.md, "Developer tools: where they run"). On
Windows, open Git Bash (Git for Windows), which has `git` on `PATH`, and paste this block from the
repository's root, after changing the first line to where your Eclipse is (the setting lasts as
long as that window). It builds the update site ZIP `launch` installs, makes the folders, and
starts Eclipse for a card:

```sh windows
export ECLIPSE='C:\path\to\eclipse\eclipse.exe'    # the Eclipse program itself, not its folder
./mvnw verify -DskipTests
java releng/tools/checks/ManualChecks.java setup
java releng/tools/checks/ManualChecks.java launch gn-download
```

On Linux and macOS `export ECLIPSE=/path/to/eclipse` names an Eclipse the tool does not find by
itself (see below).

- **`due`** lists the cards whose source files changed since the last release tag (`v*`), and the
  cards marked `always`. A card about the input method does not come up in a release that did
  not touch its code. A POM, manifest or feature whose only change is the version number (each
  release branch starts by setting it) counts for no card. `--since <ref>` compares with another commit; `--all` lists every card.
- **`setup`** creates the folders under `~/var/n4e-checks` (`--root <folder>` for another place): a
  project `n4e-alpha` to import into Eclipse, the workspaces `ws1` and `ws2`, and `xdg`, a plug-in
  folder of the checks' own.
- **`launch <card>`** starts *the checks' Eclipse* with the plug-in just built (see below), in
  the state the card's **Launch** line names:
  - `fresh`: `XDG_DATA_HOME` pointing to an *empty* `xdg` folder, so the plug-in sees no dedicated
    Neovim, as on a new machine. It asks before emptying the folder.
  - `checks`: the same `xdg` folder as it is (after a `fresh` card installed Neovim there).
  - `normal`: your own plug-in folder and Neovim, workspace `ws1`.
  - `none`: no Eclipse (a command to run, or something to look at).

  `XDG_DATA_HOME` moves Neovim's own data folder too, so Neovim plug-ins installed by a plugin
  manager are missing in an Eclipse started `fresh` or `checks`. Your real dedicated Neovim is not
  touched.

### The checks' Eclipse

Build the version under test first: `./mvnw verify -DskipTests` makes the update site ZIP
(`releng/io.github.jm6cnddxbwftaks.neovim4eclipse.updatesite/target/neovim4eclipse-<version>-SNAPSHOT.zip`).
`launch` then installs it for you, into a copy of your Eclipse, never into your Eclipse itself:

- **The copy.** Your Eclipse (`--eclipse <program>`, else the `ECLIPSE` environment variable, else the usual place for
  the platform: `eclipse` on `PATH`, `/Applications/Eclipse.app`, `C:\eclipse\eclipse.exe`) is
  copied to `~/var/n4e-checks/eclipse/` the first time. Its `plugins/` and `features/` are hard links
  to yours (the same bytes under a second name), so the copy takes a few hundred MB at most, not the 1 GB or
  more of a whole Eclipse, and a few seconds. Most of what is copied is what lies outside those
  two folders, such as a Node.js that a plug-in unpacked into the Eclipse (about 200 MB), and
  `configuration/`, which Eclipse writes to (without its OSGi cache, which the copy rebuilds).
  On another disk than your Eclipse, the files are copied instead. The folder copied must be an
  Eclipse (an `eclipse.ini` and `plugins/`): if `eclipse` on `PATH` is a script that starts one,
  name the real program with `--eclipse`. An Eclipse at another place (another `--eclipse`)
  replaces the copy, after a question.
- **The install.** p2's *director*, Eclipse's installer without a window, installs the newest ZIP
  into the copy, replacing the build it had: the same as *Help → Install New Software → Add… →
  Archive…*, in about two seconds. `--zip <file>` names another ZIP. A ZIP already installed (the
  same SHA-256) is not installed again. `launch` warns when a file in `plugins/` or `features/`,
  committed or not, changed after the ZIP was built.
- **While a checks' Eclipse runs,** `launch` changes nothing it uses: it will not install into
  the copy, empty `xdg` for a `fresh` card (its Neovim may run from there), or start a second
  Eclipse in the same workspace. Close it first. (`launch` finds it by the lock Eclipse keeps in
  each workspace under `~/var/n4e-checks`.)
- **`--own-eclipse`** starts your Eclipse as it is, with whatever plug-in you installed in it.
- **To start over,** delete `~/var/n4e-checks/eclipse` and `~/var/n4e-checks/eclipse-copy.properties`.
  Deleting the hard links leaves your Eclipse's files alone.

After `setup`, import `n4e-alpha` once in each workspace you use: *File → Import → General →
Existing Projects into Workspace*, root `~/var/n4e-checks/projects`.

## The format

Each card is a `###` heading with its id in backticks and its title, three lines of facts, the
steps, and the expected result:

- **On:** the platforms (`linux`, `macos`, `windows`), or `any` for a check done once on any
  machine.
- **Launch:** `fresh`, `checks`, `normal` or `none` (see above).
- **Areas:** the files the card depends on, as globs. A path without a leading `/` is inside the
  plug-in's source package (`plugins/io.github.jm6cnddxbwftaks.neovim4eclipse/src/io/github/jm6cnddxbwftaks/neovim4eclipse/`),
  so `util/NeovimDownload.java` is that class; `/pom.xml` is the root POM. `**` matches any number
  of folders, `*` anything within one name. `always` makes the card due in every release.
  `ManualChecksTest` (in `tests/unit`) fails if a glob matches no file, so a rename cannot quietly
  take a card out of `due`.
- **Expect:** starts the expected result, after the steps.

A step's own result goes right under it, on a line that starts with `⇒`, so you check it while the
step is in front of you. **Expect:** then says "each step's result, after its ⇒", or holds what
belongs to no single step (the gallery's list of pictures, a command's result).

A code block whose info string names platforms after the language (```` ```sh linux macos ````)
is shown on those platforms only.

## Gallery

### `gl-gallery` The gallery's pictures look right

- **On:** linux, macos, windows
- **Launch:** none
- **Areas:** dialogs/**, prefs/NeovimStatusSection.java, prefs/NeovimPage.java, prefs/NeovimPreferencePage.java, prefs/GetNeovim.java, prefs/PageLayout.java, util/GetNeovimText.java, util/StartFailureReport.java, util/OldNeovimProgram.java, util/NvimProbe.java, util/HeadedMessage.java, util/LabelText.java, editor/StartFailureNotice.java, editor/OldNeovimProgramNotice.java, /tests/io.github.jm6cnddxbwftaks.neovim4eclipse.tests/src/**/ChecksGalleryTest.java, /tests/io.github.jm6cnddxbwftaks.neovim4eclipse.tests/src/**/GalleryCamera.java, /pom.xml

The gallery draws the dialogs and preference-page notes that used to be checked by opening each
one by hand (`ChecksGalleryTest`, CONTRIBUTING.md, "Build and test"). Pictures are not stored or
compared: fonts, the operating system and SWT change pixels all the time, and a stored picture
could only be refreshed on its own platform. So a person looks, and only when `due` lists this card.

```sh linux
xvfb-run -a -s "-screen 0 1600x1000x24" ./mvnw verify -Pgallery -Dnvim.path=/path/to/nvim
```

```sh macos
./mvnw verify -Pgallery -Dnvim.path=/path/to/nvim
```

```powershell windows
.\mvnw.cmd verify -Pgallery "-Dnvim.path=C:\path\to\nvim.exe"
```

1. On Linux, `xvfb-run` is optional: on a desktop session the command runs without it. On every
   system the windows draw themselves into the pictures, so other windows may cover them; leave
   the test Eclipse in front, though, so the pictures show its windows active, as users see them.
   In PowerShell keep the quotes around `-Dnvim.path=...`: it splits an unquoted argument that
   starts with `-` at its first dot, so Maven would get `-Dnvim` and `.path=...` apart.
2. Open `tests/io.github.jm6cnddxbwftaks.neovim4eclipse.tests/target/gallery/index.html` in a
   browser and look at each picture against the text under it.
   ⇒ Each picture matches its caption (the list below).

**Expect:** every picture matches what its caption says to look for: the Get Neovim dialog at a
fresh start and with changed files, the done note with and without `-Dneovim4eclipse.nvim`, the
"Neovim not started" notice and the page note for each refused value, the old-preference note, the
leftovers note. The wording reads right, no stray `&` or markup, rows a little apart. (Text cut
off, over a window's edge or on top of other text fails the tests already: `DialogLayoutTest` and
`NeovimPageTest` in `tests/swt` check every one of these dialogs with `LayoutCheck`.) (On macOS with *Show
borders* on and a Java built for macOS 26 or later, such as Homebrew's, a frame around the page's
links is expected; Eclipse.app's own Java shows none, nor does any Java with the setting off.)

## Get Neovim

### `gn-download` Download installs, checks and attaches

- **On:** linux, macos, windows
- **Launch:** fresh
- **Areas:** util/NeovimDownload.java, util/NeovimInstaller.java, util/ArchiveExtractor.java, util/InstallTree.java, util/PluginNeovim.java, util/neovim-downloads.properties, util/ProcessorProbe.java, prefs/GetNeovim*.java, prefs/EclipseProxies.java, prefs/ExternalBrowser.java, editor/GetNeovimHandler.java

The one check with a real download from GitHub; the installer itself is tested with archives of
the tests' own (`NeovimInstallerTest`).

1. Launch for this card. Get Neovim opens by itself (its wording: the gallery). Cancel it; import
   `n4e-alpha` and open `notes.txt`.
   ⇒ After the cancel, no second dialog, and the editor keeps Eclipse's keys.
2. *Neovim → Get Neovim...* → **Download**.
   ⇒ Progress, then done. SHA-256 *Copy* works; the GitHub and VirusTotal links open in the
   browser. The editor attaches (or the note offers *Restart Neovim*).
3. Open the *Neovim* menu, then *Window → Preferences → Neovim*.
   ⇒ *Get Neovim...* has left the menu. The page says the dedicated Neovim is found and checked,
   with *Reinstall…*.

**Expect:** each step's result, after its ⇒.

### `gn-gatekeeper` macOS runs it without the quarantine prompt

- **On:** macos
- **Launch:** checks
- **Areas:** util/NeovimDownload.java, util/NeovimInstaller.java, util/ArchiveExtractor.java, util/neovim-downloads.properties

macOS marks a downloaded file as quarantined, and Gatekeeper then asks before running it; the
plug-in's own download must not leave that mark on Neovim.

1. After `gn-download`, open any file so that Neovim starts.
   ⇒ No "cannot be opened" dialog.
2. Run the command below (`mx` in place of `ma` on an Intel Mac).
   ⇒ `xattr` lists no `com.apple.quarantine`.

```sh macos
xattr -l ~/var/n4e-checks/xdg/neovim4eclipse-plugin/neovim/ma/bin/nvim
```

**Expect:** each step's result, after its ⇒.

## Search highlighting

### `sh-colors` Search matches readable in a light and a dark theme

- **On:** any
- **Launch:** none
- **Areas:** editor/SearchHighlighter.java, editor/NeovimHighlighter.java, editor/JumpLabelPainter.java, editor/SearchColors.java, editor/ThemeColorSource.java, util/OverlayColors.java, editor/GridCanvas.java, editor/NeovimPage*.java, editor/WindowPane.java, util/ViewText.java, core/HealthCheck.java, editor/NeovimWindowPanel.java, editor/FloatingWindows.java, util/FloatPlacement.java, util/GridSnapshot.java, /plugins/io.github.jm6cnddxbwftaks.neovim4eclipse/css/**, /tests/io.github.jm6cnddxbwftaks.neovim4eclipse.tests/src/**/SearchGalleryTest.java, /tests/io.github.jm6cnddxbwftaks.neovim4eclipse.tests/src/**/GalleryCamera.java

Whether a color reads well is for a person to judge; the painting itself is tested
(`SearchHighlighterTest`, `SearchHighlightingEndToEndTest`). The gallery takes the pictures in two
test Eclipses, one started in the light theme and one in Eclipse's dark theme, as a user's Eclipse
starts after choosing Dark (`SearchGalleryTest`).

```sh linux
xvfb-run -a -s "-screen 0 1600x1000x24" ./mvnw verify -Pgallery -Dnvim.path=/path/to/nvim
```

```sh macos
./mvnw verify -Pgallery -Dnvim.path=/path/to/nvim
```

```powershell windows
.\mvnw.cmd verify -Pgallery "-Dnvim.path=C:\path\to\nvim.exe"
```

Three of the pictures show real Neovim plug-ins: which-key's panel of key hints after `z` and
after `g`, and flash's jump labels. The repository holds no plug-in; they are taken from the
pinned copies ([UPGRADING.md](UPGRADING.md), "The plug-ins the tests run"), so fetch those first,
from the repository's root (on Windows in Git Bash):

```sh linux macos windows
java releng/tools/plugins/TestPlugins.java fetch
```

A plug-in that is not fetched leaves its pictures out, and the page says so.

which-key draws icons from a Nerd Font. The gallery's Eclipse keeps its data in a folder of its
own (`/tmp/neovim4eclipse-pictures`; on Windows `neovim4eclipse-pictures` in the Public folder) and does
not see the fonts of your account, so on Linux copy an icon font, such as
`SymbolsNerdFontMono-Regular.ttf`, into `/tmp/neovim4eclipse-pictures/neovim-home/data/fonts/`
before the run, or install it for the system; on macOS and Windows install it for the system.
Without it the icons are boxes, which is what a user without the font sees, and the rest of the
picture is still to be judged.

1. Open `tests/io.github.jm6cnddxbwftaks.neovim4eclipse.tests/target/gallery/search.html` in a
   browser.
   ⇒ Each of the fourteen pictures is there in the light and the dark theme, side by side; one whose
   plug-in is not fetched says what it needs.
2. Look at each pair against the text above it.
   ⇒ In both themes every match stands out from the editor's background, its text (syntax colors
   included, in the Java editor) stays readable, and the match under the caret is told apart from
   the others without hiding its text. In the picture of Neovim's own highlights, the flash and the
   `:match` read in both themes, in Neovim's colors. In the picture of the command-line window,
   its history reads in both themes, on the editor's own background. In the picture of the floating
   windows, the panel lies on the editor's bottom edge and the note above it, both readable in both
   themes, with the editor's text showing around them. In the picture of Neovim's popup menu, the
   list hangs under the word being completed, every entry reads, and the selected one stands out
   in both themes. In the two pictures of the page editors, the text reads on the editor's
   background in both themes, the error of `:messages` in Neovim's error color and the WARNING of
   `:checkhealth` in its warning color. In the two pictures of which-key, its panel
   lies on the editor's bottom edge from the left edge on, its lines read in both themes, and no
   icon is cut off at the cell after it.

   On macOS the block caret inverts the cell under it: SWT's Cocoa `Canvas` draws a `Caret` as a
   white rectangle in the Difference blend mode, the same in Eclipse's own editors. On the dark
   theme's amber the caret's cell turns light blue, and a gray comment letter on it stays gray, as
   on the `f` of the current match in the dark Java picture. That cell is the caret, not the
   match: judge the match by its other letters.

**Expect:** each step's result, after its ⇒.

## Splits

### `sp-restart` Split stacks take dragged tabs and come back after a restart

- **On:** any
- **Launch:** normal
- **Areas:** editor/EditorSplits.java, editor/SplitEditorHandler.java

What the splits do is tested (`SplitsEndToEndTest`, `WindowLayoutTest`), and so is that a stack
the plug-in makes has the tags and the tab style of Eclipse's own editor stacks. A drag with the
mouse, and Eclipse's restore of the stacks at the next start, need a person and a restart.

1. Open `n4e-alpha`'s `notes.txt`, type `:vsplit` and Enter, then `:split ideas.txt` and Enter.
   ⇒ Three editors on screen: a second `notes.txt` on the left with `ideas.txt` (a new file, `[New]`)
   above it, the first `notes.txt` on the right; Neovim's cursor is in `ideas.txt`.
2. Drag the `ideas.txt` tab onto the tabs of the right-hand stack, then back onto the tabs of the
   lower left stack.
   ⇒ Each drop moves the tab there, as between Eclipse's own stacks; `ideas.txt` ends up where it was.
3. *File › Exit*, then start the checks' Eclipse again the same way.
   ⇒ The stacks are back, with their editors (`ideas.txt` too, if Eclipse keeps a new file's
   editor; it was never saved). A click into each puts Neovim's cursor there, and `:wincmd l` and
   `:wincmd h` move between the left and the right stacks.
4. Type `:only` and Enter.
   ⇒ One stack, holding every editor as a tab; none was closed.

**Expect:** each step's result, after its ⇒.

## Terminal

### `tm-open` `:terminal` opens Eclipse's Terminal

- **On:** any
- **Launch:** checks
- **Areas:** util/EclipseTerminal.java, editor/EclipseTerminals.java, util/HostCommand.java

Which of the Terminal's two command ids is run, the messages, and that a typed `:terminal` starts
nothing in Neovim are tested (`EclipseTerminalTest`, `terminal_spec.lua`), and so is the message of
an Eclipse without the Terminal (`TerminalEndToEndTest`). The test Eclipse has no Terminal, so
that the command opens one needs an Eclipse that has it, such as the *Eclipse IDE for Eclipse
Committers* package (2025-09 and later: the new command id).

1. Open `n4e-alpha`'s `notes.txt`, type `:terminal` and Enter.
   ⇒ Eclipse's *Terminal* view opens with a shell whose folder is the one `notes.txt` is in
   (`pwd` there shows it); the editor shows no message.
2. Click back into `notes.txt`, type `:terminal make` and Enter.
   ⇒ A second shell opens in the same folder. Neovim's line, at the editor's bottom, says
   `terminal: opened Eclipse's Terminal in …; "make" was not run, type it there`, and `make` did not
   run in either shell.
3. Close the Terminal view, then type `:ls!` and Enter in `notes.txt`.
   ⇒ No `term://` buffer is listed: nothing was started in Neovim.

If you have an Eclipse of 2025-06 or older with the TM Terminal (the old command id), step 1 there
too.

**Expect:** each step's result, after its ⇒.

## Page editors (`:help`, `:messages`, `:checkhealth`)

### `pg-ctrlw-mac` A real Ctrl-W in a page editor on macOS

- **On:** macos
- **Launch:** checks
- **Areas:** editor/NeovimPage*.java, editor/WindowPane.java, editor/PageCommandsRun.java, editor/SendKeyHandler.java, editor/GridMouse.java, /plugins/io.github.jm6cnddxbwftaks.neovim4eclipse/plugin.xml

Everything else about the page editors is tested (`NeovimPagesEndToEndTest`: where they open, the
error color, the cursor, the background, search, yank and paste, the wheel and a click,
`:checkhealth neovim4eclipse`, `:help` and `:messages` side by side, `Ctrl-W` both ways,
`Ctrl-O` passing over them, `q`; on Linux and Windows a real Ctrl+W posted through the system;
`NeovimPageEditorEndToEndTest`; the messages page as a window of the editing Neovim, with `/`
over it, `:split`, `:q`, `:e`, typing in the code after it, the focus back from a view, the code
closed under it, its window closed by Neovim, the command-line window over it:
`MessagesPageEndToEndTest`), and their look in both themes is in the gallery (card
`sh-colors`). On macOS posting keys needs the Accessibility permission, so the real key is checked
by hand.

1. Open `n4e-alpha`'s `notes.txt`, type `:checkhealth neovim4eclipse` and Enter.
   ⇒ The editor *Neovim: checkhealth* opens below `notes.txt`, with the keyboard.
2. Press Control and W together (not Command), then `k`.
   ⇒ `notes.txt` above has the keyboard back; the checkhealth editor is still open.

**Expect:** each step's result, after its ⇒.

### `pg-restart` No page editor after a restart

- **On:** any
- **Launch:** checks
- **Areas:** editor/NeovimPageEditor.java, editor/NeovimPageInput.java, editor/EditorAttachManager.java

A page editor is never brought back: its window was in the Neovim that ended with Eclipse. Eclipse still keeps its
tab in the saved layout, which the plug-in closes at the start (`NeovimPageEditor.closeLeftovers`).
The tests' Eclipse saves its layout differently from yours, so only a real restart shows it.

1. Open `n4e-alpha`'s `notes.txt`, type `:help`, Enter; then click `notes.txt` and type
   `:messages`, Enter.
   ⇒ *Neovim: help* and *Neovim: messages* below `notes.txt`, as two tabs.
2. *File › Exit*, then start the same Eclipse again (run this card's `launch` again).
   ⇒ `notes.txt` is back, attached to Neovim; no *Neovim: help* or *Neovim: messages* tab and no
   empty tab, not even for a moment.

**Expect:** each step's result, after its ⇒. If an empty tab shows for a moment at the start, note
it: closing the page editors at Eclipse's exit as well would avoid it.

## Starting Neovim

### `st-busy` The busy cursor while Neovim starts

- **On:** any
- **Launch:** checks
- **Areas:** util/BusyStart.java, editor/EditorAttachManager.java

That the start runs inside the busy cursor, on the same thread, is tested (`BusyStartTest`), and
every UI test's attach goes through it. Whether each system shows the cursor while the UI thread
waits is only seen on screen. The start is short (0.2 to 0.6 s), so keep the pointer over the
editor and watch it.

1. In `n4e-alpha`'s `notes.txt`, choose *Neovim → Restart Neovim...*, answer *Restart*, and type
   `dd` at once.
   ⇒ While Neovim starts, the pointer over Eclipse is the busy cursor; then it is the usual one
   again, and the line under the cursor is deleted.

**Expect:** each step's result, after its ⇒.

## Input method on Neovim's `:` line

### `ime-compose` Japanese typed on the `:` line reaches Neovim once

- **On:** linux, macos, windows
- **Launch:** normal
- **Areas:** editor/LineInputMethod.java, util/LineComposition.java, editor/FloatingWindows.java, /pom.xml

The input method's events come from the system itself, so no test can send them; the decisions
behind them are tested (`LineCompositionTest`), and the SWT side with synthetic events
(`LineInputMethodTest`). Add a Japanese input method first: on Linux an input method framework with
one (fcitx5 or IBus with Mozc), on macOS *Japanese – Romaji* in *System Settings → Keyboard → Input
Sources*, on Windows *Japanese* with the Microsoft IME in *Settings → Time & language → Language &
region*.

1. In `n4e-alpha`'s `notes.txt`, in Neovim mode, type `:`, switch to Japanese input and type
   `konnichiwa`.
   ⇒ Neovim's line opens on the editor's last row. The text being composed shows underlined at the
   line's cursor, and the document's text does not change.
2. Press <kbd>Enter</kbd> to keep the text.
   ⇒ The line shows `:こんにちわ`: each kana once, no `kkk`, nothing doubled, and the line has not
   run.
3. Switch back to direct (ABC) input and press <kbd>Esc</kbd>.
   ⇒ The line closes; `notes.txt` is as it was.
4. `:` `echo "`, switch to Japanese, type `kyou`, press <kbd>Space</kbd> to convert (choose 今日
   if another candidate is offered), <kbd>Enter</kbd> to keep it, switch to direct input, type `"`
   and <kbd>Enter</kbd>.
   ⇒ After <kbd>Space</kbd> the candidate list opens at the line's cursor, not elsewhere on the
   screen. The <kbd>Enter</kbd> that keeps 今日 does not run the line; the last one does, and
   Neovim's line shows `今日`.

**Expect:** each step's result, after its ⇒.

### `ime-keys` Esc, a click, leaving Eclipse and Paste while composing

- **On:** linux, macos, windows
- **Launch:** normal
- **Areas:** editor/LineInputMethod.java, util/LineComposition.java, editor/CommandLineEditHandlers.java, /pom.xml

1. `:` `abc`, switch to Japanese, type `ka`, press <kbd>Esc</kbd> (twice if the input method
   needs it to drop the text).
   ⇒ The composition is gone and the line stays open on `:abc`. In direct input, one more
   <kbd>Esc</kbd> closes the line.
2. `:` `abc`, Japanese, type `ka`, then click in the editor's text.
   ⇒ The click abandons the line, as any click in the text does: the line closes with its `:abc`
   and the composing `か`, and the cursor lands at the click. `か` reaches neither the line nor
   `notes.txt`; switch to direct input and type `:`: the new line is empty, no `か` comes back.
   (On macOS ending the composition is the plug-in's call to Cocoa's `discardMarkedText`.)
3. `:` `abc`, Japanese, type `ka`, switch to another application and back to Eclipse.
   ⇒ The line stays open and has not run. On Linux and macOS the composition ended unsent: in
   direct input, type `d` and the line shows `:abcd`. On Windows the Microsoft IME commits the
   composing text when the window loses the focus, as in Windows' own applications, so the line
   shows `:abcか`, once.
4. Copy `src` to the clipboard. <kbd>Esc</kbd> to close any line; `:`, Japanese, type `kaka`,
   <kbd>Enter</kbd> to keep it, then Eclipse's Paste (<kbd>Cmd+V</kbd> on macOS,
   <kbd>Shift+Insert</kbd> on Linux and Windows).
   ⇒ The line is `:かかsrc`; `notes.txt` is unchanged.
5. On macOS: <kbd>Esc</kbd>; in ABC input, `:` `echo "` <kbd>Option+E</kbd> <kbd>E</kbd> `"`
   <kbd>Enter</kbd>.
   ⇒ Neovim's line shows `é` once. (Holding <kbd>E</kbd> on the line opens no accent menu: the
   README's Known limitations say so.)

**Expect:** each step's result, after its ⇒.

## Marketplace

### `mp-setup` First-time setup from the Marketplace, direct and behind a proxy

- **On:** linux, macos, windows
- **Launch:** none
- **Areas:** /features/io.github.jm6cnddxbwftaks.neovim4eclipse.feature/feature.xml, /releng/io.github.jm6cnddxbwftaks.neovim4eclipse.updatesite/category.xml, /releng/marketplace/listing.txt, prefs/GetNeovim*.java, prefs/EclipseProxies.java, util/ProxyChoice.java, util/NeovimDownload.java

What a new user does, from end to end: install from the Marketplace into a new Eclipse, let Get
Neovim install Neovim, open a file. Twice, once with a direct connection and once through an HTTP
proxy, since a proxy is where installs and downloads go wrong at work. The pieces are tested
apart (`InstallCheck` installs from the update site, `NeovimDownloadProxyTest` takes the download
through a proxy); this card is the only place they run in one go, with Eclipse's own Marketplace
client, as a user meets them. It needs the listing live on the Marketplace. (The proxy tool is
not among its files: its own test, `ProxyTest`, covers it, and a change to it alone is no reason
to install from the Marketplace again.)

The Eclipse is a new one, not yours or the checks' copy: a package downloaded from
[eclipse.org/downloads/packages](https://www.eclipse.org/downloads/packages/) and unpacked into a
new folder, one with the Marketplace client (*Eclipse IDE for Java Developers*, say). It starts
with a plug-in folder and a workspace of its own, emptied first, so the plug-in finds no Neovim, as
on a new machine (`XDG_DATA_HOME` moves the plug-in's folder on every system):

```sh linux macos
rm -rf ~/var/n4e-checks/xdg-mp ~/var/n4e-checks/ws-mp
XDG_DATA_HOME=~/var/n4e-checks/xdg-mp /path/to/new/eclipse/eclipse -data ~/var/n4e-checks/ws-mp
```

```powershell windows
Remove-Item -Recurse -Force -ErrorAction SilentlyContinue "$HOME\var\n4e-checks\xdg-mp", "$HOME\var\n4e-checks\ws-mp"
$env:XDG_DATA_HOME = "$HOME\var\n4e-checks\xdg-mp"
C:\path\to\new\eclipse\eclipse.exe -data "$HOME\var\n4e-checks\ws-mp"
```

On macOS the package is a disk image: copy `Eclipse.app` out of it into a new folder; the program
is inside the app, `/path/to/new/Eclipse.app/Contents/MacOS/eclipse`. macOS marks a downloaded app
as quarantined, and Gatekeeper may then refuse to start it from a terminal ("cannot be opened").
Open the app once in Finder and confirm, or clear the mark, then start it as above:

```sh macos
xattr -dr com.apple.quarantine /path/to/new/Eclipse.app
```

In PowerShell, `Remove-Item Env:XDG_DATA_HOME` afterward, or close the window.

1. Unpack a new Eclipse and start it as above.
   ⇒ Eclipse starts; nothing about Neovim yet.
2. *Help → Eclipse Marketplace…*, search for `Neovim`, and *Install* on **Neovim for Eclipse**;
   confirm, accept the license (EPL-2.0), *Finish*.
   ⇒ Eclipse asks to trust the PGP key *Neovim for Eclipse releases*, fingerprint
   `A91F 53D8 931B 7FD0 D146 8618 6CC9 E446 147B 063C` (README, *Install*); after *Trust
   Selected*, it asks to restart. No question about *unsigned content*.
3. Restart.
   ⇒ *Get Neovim* opens by itself, its *Why* line saying the dedicated Neovim is missing.
4. **Download**.
   ⇒ Progress, then done.
5. *File → New → Project… → General → Project*, then a file `a.txt` in it, and open it.
   ⇒ Neovim attaches: the mode in the status bar, the welcome note. `ihello`, Esc, `:w` saves
   the file.
6. Behind a proxy: close Eclipse, unpack the package again into a new folder (the first one has
   the plug-in now), and start the proxy below in a window of its own; it prints a line for each
   connection. Start the new Eclipse as above (the commands empty the two folders again). Before
   anything else, *Window → Preferences → General → Network Connections*: *Active Provider*
   **Manual**, edit **HTTP** and **HTTPS** to host `127.0.0.1`, port `3128`; *Apply and Close*.
   Then steps 2 to 5 again.
   ⇒ The same results. The proxy's lines include `CONNECT marketplace.eclipse.org:443` (the
   Marketplace client), `CONNECT jm6cnddxbwftaks.github.io:443` (the update site), and
   `CONNECT github.com:443` and `CONNECT release-assets.githubusercontent.com:443` (Get Neovim's
   download): all of them took the proxy.

On Windows start the proxy in Git Bash, which every tool needs there (CONTRIBUTING.md, "Developer
tools: where they run"):

```sh
java releng/tools/checks/Proxy.java
```

**Expect:** each step's result, after its ⇒, on each platform.

## Release

These follow [CONTRIBUTING.md](../CONTRIBUTING.md), "Releasing"; each is done once, on any machine
that has the platform.

### `rl-pall-linux` Every test on Linux

- **On:** any
- **Launch:** none
- **Areas:** always

```sh
java releng/tools/release/Release.java check
```

**Expect:** every module passes, on the commit that will be released (the Release tool's `check`
runs `LikeCi.java --all` and records the commit for `promote`).

### `rl-pall-macos` Every test on macOS

- **On:** any
- **Launch:** none
- **Areas:** always

```sh
java releng/tools/ci/LikeCi.java --all
```

**Expect:** every module passes, on the commit `check` printed.

### `rl-pall-windows` Every test on Windows

- **On:** any
- **Launch:** none
- **Areas:** always

In Git Bash:

```sh
java releng/tools/ci/LikeCi.java --all
```

**Expect:** every module passes, on the commit `check` printed.

### `rl-pall-windows-1core` Every test on Windows, on one core

- **On:** any
- **Launch:** none
- **Areas:** always

A loaded machine shows timing bugs a fast one hides (CONTRIBUTING.md, "Tests on a loaded
machine"); right before the release.

In Git Bash, pin that shell to core 0, then run LikeCi from it, telling each JVM it has one core
(CONTRIBUTING.md, "Tests on a loaded machine", says why both). Open a new Git Bash afterward to
use every core again:

```sh
powershell -Command "(Get-Process -Id $(cat /proc/$$/winpid)).ProcessorAffinity = 1"
JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=1 java releng/tools/ci/LikeCi.java --all
```

**Expect:** every module passes.

### `rl-publish` Release installable

- **On:** any
- **Launch:** none
- **Areas:** always

1. Run `java releng/tools/checks/InstallCheck.java` (the Release tool offers it last, after
   `next`). It installs the release from the update site into a fresh copy of your Eclipse,
   trusting only the release key, and offers to start that Eclipse; say yes.
   ⇒ The install passes, and the Eclipse starts.
2. In that Eclipse, let Get Neovim install Neovim, then open a text file.
   ⇒ Get Neovim opens at the first start, and the file attaches after it (Neovim's mode in the
   status bar).

**Expect:** each step's result, after its ⇒.
