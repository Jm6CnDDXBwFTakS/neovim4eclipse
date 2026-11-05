# Manual checks before a release

Most of what the plug-in does is checked by its tests ([CONTRIBUTING.md](../CONTRIBUTING.md), "Build
and test"). This file lists what is left: the few checks that need a person, because they depend on
something a test cannot fake (a real download, macOS's Gatekeeper or input methods), or
on eyes (the gallery of dialogs, for the overall look), or because they *are* the release (the full
test runs, CI, the tag). Each is a *card* with an id, such as `gn-download`.

**Why so few.** A check stays here only while no test can do it. When a card can become a test, it
should: a test runs on every change and on every platform, a card only when someone has the time.
For 0.2.1, tests replaced 49 of the 60 cards its list once had. When you add a feature that needs a look
by hand, add a card here in the same commit, with the source files it depends on.

## How to run them

`releng/tools/checks/ManualChecks.java` reads this file. It is a single-file Java program (run it
from the repository's root with the JDK the build uses):

```bash
java releng/tools/checks/ManualChecks.java due              # the cards this release needs, and why
java releng/tools/checks/ManualChecks.java setup            # make the test project and folders
java releng/tools/checks/ManualChecks.java launch gn-download   # prepare the folders, start Eclipse for a card
java releng/tools/checks/ManualChecks.java list             # every card
java releng/tools/checks/ManualChecks.java json             # the cards as JSON, for a page that keeps ticks
```

- **`due`** lists the cards whose source files changed since the last release tag (`v*`), and the
  cards marked `always`. A card about macOS's input method does not come up in a release that did
  not touch the `:` box. `--since <ref>` compares with another commit; `--all` lists every card.
- **`setup`** creates the folders under `~/n4e-checks` (`--root <folder>` for another place): a
  project `n4e-alpha` to import into Eclipse, the workspaces `ws1` and `ws2`, and `xdg`, a plug-in
  folder of the checks' own.
- **`launch <card>`** starts Eclipse (from `--eclipse <program>`, else `$ECLIPSE`, else the usual
  place for the platform) in the state the card's **Launch** line names:
  - `fresh`: `XDG_DATA_HOME` pointing to an *empty* `xdg` folder, so the plug-in sees no dedicated
    Neovim, as on a new machine. It asks before emptying the folder.
  - `checks`: the same `xdg` folder as it is (after a `fresh` card installed Neovim there).
  - `normal`: your own plug-in folder and Neovim, workspace `ws1`.
  - `none`: no Eclipse (a command to run, or something to look at).

  `XDG_DATA_HOME` moves Neovim's own data folder too, so Neovim plug-ins installed by a plugin
  manager are missing in an Eclipse started `fresh` or `checks`. Your real dedicated Neovim is not
  touched.

Install the version under test first: build the update site ZIP (`./mvnw verify -DskipTests`; it is
`releng/io.github.jm6cnddxbwftaks.neovim4eclipse.updatesite/target/neovim4eclipse-<version>-SNAPSHOT.zip`)
and install it in Eclipse with *Help → Install New Software → Add… → Archive…*. After `setup`,
import `n4e-alpha` once in each workspace you use: *File → Import → General → Existing Projects
into Workspace*, root `~/n4e-checks/projects`.

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

A code block whose info string names platforms after the language (```` ```sh linux macos ````)
is shown on those platforms only.

## Gallery

### `gl-gallery` The gallery's pictures look right

- **On:** linux, macos, windows
- **Launch:** none
- **Areas:** dialogs/**, prefs/NeovimStatusSection.java, prefs/NeovimPreferencePage.java, prefs/GetNeovim.java, prefs/PageLayout.java, util/GetNeovimText.java, util/StartFailureReport.java, util/OldNeovimProgram.java, util/NvimProbe.java, util/HeadedMessage.java, util/LabelText.java, editor/StartFailureNotice.java, editor/OldNeovimProgramNotice.java, /tests/io.github.jm6cnddxbwftaks.neovim4eclipse.tests/src/**/ChecksGalleryTest.java, /pom.xml

The gallery draws the dialogs and preference-page notes that used to be checked by opening each
one by hand (`ChecksGalleryTest`, CONTRIBUTING.md, "Build and test"). Pictures are not stored or
compared: fonts, the operating system and SWT change pixels all the time, and a stored picture
could only be refreshed on its own platform. So a person looks, and only when `due` lists this card.

```sh linux
xvfb-run -a ./mvnw verify -Pgallery
```

```sh macos
./mvnw verify -Pgallery
```

```powershell windows
.\mvnw.cmd verify -Pgallery
```

1. On macOS, run it from a terminal that has the Screen Recording permission: the pictures are
   read from the screen there. On Linux, without `xvfb-run` on a desktop session.
2. Open `tests/io.github.jm6cnddxbwftaks.neovim4eclipse.tests/target/gallery/index.html` in a
   browser and look at each picture against the text under it.

**Expect:** every picture matches what its caption says to look for: the Get Neovim dialog at a
fresh start and with changed files, the done note with and without `-Dneovim4eclipse.nvim`, the
"Neovim not started" notice and the page note for each refused value, the old-preference note, the
leftovers note. The wording reads right, no stray `&` or markup, rows a little apart. (Text cut
off, over a window's edge or on top of other text fails the tests already: `DialogLayoutTest` in
`tests/swt` and `NeovimPreferencePageTest` check every one of these dialogs with `LayoutCheck`.) (On macOS with a Java
built for macOS 26 or later, such as Homebrew's, a frame around the page's links is expected;
Eclipse.app's own Java shows none.)

## Get Neovim

### `gn-download` Download installs, checks and attaches

- **On:** linux, macos, windows
- **Launch:** fresh
- **Areas:** util/NeovimDownload.java, util/NeovimInstaller.java, util/ArchiveExtractor.java, util/InstallTree.java, util/PluginNeovim.java, util/neovim-downloads.properties, util/ProcessorProbe.java, prefs/GetNeovim*.java, prefs/EclipseProxies.java, prefs/ExternalBrowser.java, editor/GetNeovimHandler.java

The one check with a real download from GitHub; the installer itself is tested with archives of
the tests' own (`NeovimInstallerTest`).

1. Launch for this card. Get Neovim opens by itself (its wording: the gallery). Cancel it; import
   `n4e-alpha` and open `notes.txt`.
2. *Neovim → Get Neovim...* → **Download**.

**Expect:** step 1: after the cancel, no second dialog, and the editor keeps Eclipse's keys. Step
2: progress, then done. SHA-256 *Copy* works; the GitHub and VirusTotal links open in the browser.
The editor attaches (or the note offers *Restart Neovim*). *Get Neovim...* leaves the menu. The
preference page says the dedicated Neovim is found and checked, with *Reinstall…*.

### `gn-gatekeeper` macOS runs it without the quarantine prompt

- **On:** macos
- **Launch:** checks
- **Areas:** util/NeovimDownload.java, util/NeovimInstaller.java, util/ArchiveExtractor.java, util/neovim-downloads.properties

macOS marks a downloaded file as quarantined, and Gatekeeper then asks before running it; the
plug-in's own download must not leave that mark on Neovim.

1. After `gn-download`, open any file so that Neovim starts.

```sh macos
xattr -l ~/n4e-checks/xdg/neovim4eclipse-plugin/neovim/ma/bin/nvim
```

**Expect:** no "cannot be opened" dialog; `xattr` lists no `com.apple.quarantine` (`mx` in place of
`ma` on an Intel Mac).

## Input method and accent menu in the `:` box

### `ime-compose` Japanese typing in the `:` box reaches Neovim once

- **On:** macos
- **Launch:** normal
- **Areas:** util/MarkedTextInput.java, editor/CommandLineBox*.java, /pom.xml

The input method's events come from macOS itself, so no test can send them; the logic behind them
is tested (`MarkedTextInputTest`). Add *Japanese – Romaji* in *System Settings → Keyboard → Input
Sources* first.

1. In an editor in Neovim mode, type `:`, switch to Japanese Romaji input, type `konnichiwa` and
   press <kbd>Enter</kbd>.
2. Press <kbd>Esc</kbd> to leave the line. Then `:`, type `kyou`, <kbd>Space</kbd> to convert
   (choose 今日 with the arrows if needed), <kbd>Enter</kbd> to keep it.
3. Switch back to ABC input and press <kbd>Esc</kbd>.

**Expect:** the text being composed shows underlined in the box while you type. After
<kbd>Enter</kbd> the box shows `:こんにちわ` (then `:今日`): each kana once, no `kkk`, no doubled
kana. The <kbd>Enter</kbd> that keeps the text does not run the line.

### `ime-keys` Esc, Enter, Ctrl keys and Cmd+V while composing

- **On:** macos
- **Launch:** normal
- **Areas:** util/MarkedTextInput.java, editor/CommandLineBox*.java, /pom.xml

1. `:` `abc`, switch to Japanese, type `ka`, press <kbd>Ctrl+U</kbd>, then <kbd>Enter</kbd>.
2. Type `ka`, press <kbd>Esc</kbd> twice, then <kbd>Esc</kbd> once more.
3. Copy `src` to the clipboard; `:`, type `kaka`, press <kbd>Cmd+V</kbd>.
4. <kbd>Esc</kbd>; in ABC input: `:` `echo "` <kbd>Option+E</kbd> <kbd>E</kbd> `"` <kbd>Enter</kbd>.
5. In Ex mode (<kbd>g</kbd><kbd>Q</kbd>), Japanese, type `ka`, click in the editor's text. Then, in
   ABC input, <kbd>Ctrl+U</kbd>, `visual` <kbd>Enter</kbd> to leave Ex mode.

**Expect:** 1: <kbd>Ctrl+U</kbd> does nothing while composing; the line becomes `:abcか`. 2: the
first two <kbd>Esc</kbd> cancel the composition and the box stays open; the third closes the line.
3: `:かかsrc`. 4: the message line shows `é` once. 5: the click keeps `か` in Ex mode's line.

### `ime-accent` Holding a letter shows the accent menu, and its pick replaces the letter

- **On:** macos
- **Launch:** normal
- **Areas:** util/PressAndHold*.java, util/MarkedTextInput.java, editor/CommandLineBox*.java, /pom.xml

1. ABC input, press-and-hold on (macOS's default). `:` `echo "`, hold <kbd>E</kbd> until the accent
   menu shows, let go, press <kbd>2</kbd>, type `"`, <kbd>Enter</kbd>.
2. The same, but pick with the arrow keys and <kbd>Enter</kbd> in place of <kbd>2</kbd>.
3. `:` `echo "`, hold <kbd>E</kbd>, let go, <kbd>Esc</kbd>; then <kbd>Esc</kbd> again.
4. `:` `echo "`, hold <kbd>E</kbd>, let go, and click an accent at once.
5. With the *ABC – AZERTY* input source, if you have it: step 1 with <kbd>Shift</kbd> and the
   <kbd>é</kbd> key (2) as the digit.

**Expect:** 1, 2 and 5: the message line shows the picked accent once (`é` for 2): no `eeee` from
the held key, no `2`, no leftover `e`. 3: the first <kbd>Esc</kbd> closes only the menu and the box
stays open on `:echo "e`; the second closes the line. 4: the clicked accent replaces the e. A click
made after a pause is lost (an SWT bug, described in the README: use the digit or the arrows).

## Release

These follow [CONTRIBUTING.md](../CONTRIBUTING.md), "Releasing"; each is done once, on any machine
that has the platform.

### `rl-pall-linux` Every test on Linux

- **On:** any
- **Launch:** none
- **Areas:** always

```sh
xvfb-run -a ./mvnw verify -Pall -Dnvim.path=$HOME/.local/share/neovim4eclipse-plugin/neovim/la/bin/nvim
```

**Expect:** every module passes, on the commit that will be released (`lx` in place of `la` on
x86_64; without `xvfb-run` on a desktop session).

### `rl-pall-macos` Every test on macOS

- **On:** any
- **Launch:** none
- **Areas:** always

```sh
./mvnw verify -Pall -Dnvim.path=$HOME/.local/share/neovim4eclipse-plugin/neovim/ma/bin/nvim
```

**Expect:** every module passes (`mx` in place of `ma` on an Intel Mac; the path names the program,
never a folder).

### `rl-pall-windows` Every test on Windows

- **On:** any
- **Launch:** none
- **Areas:** always

```powershell
.\mvnw.cmd verify -Pall "-Dnvim.path=$env:LOCALAPPDATA\neovim4eclipse-plugin\neovim\wx\bin\nvim.exe"
```

**Expect:** every module passes (`wa` on ARM Windows; with `XDG_DATA_HOME` set, the folder is
under it).

### `rl-pall-windows-1core` Every test on Windows, on one core

- **On:** any
- **Launch:** none
- **Areas:** always

A loaded machine shows timing bugs a fast one hides (CONTRIBUTING.md, "Tests on a loaded
machine"); right before the release.

```powershell
$env:JAVA_TOOL_OPTIONS = '-XX:ActiveProcessorCount=1'
.\mvnw.cmd verify -Pall "-Dnvim.path=$env:LOCALAPPDATA\neovim4eclipse-plugin\neovim\wx\bin\nvim.exe"
```

**Expect:** every module passes.

### `rl-build` Build workflow on the release branch

- **On:** any
- **Launch:** none
- **Areas:** always

1. Run the *Build* workflow by hand on the release branch (CONTRIBUTING.md, "Releasing", step 1).

**Expect:** green on all three platforms. The *Install Neovim* step's log shows `tree … (matches)`
for `lx`, `wx` and `ma`.

### `rl-tag` The tag's Release run reuses the Build on main

- **On:** any
- **Launch:** none
- **Areas:** always

1. After `main` has the release and its *Build* passed, push the tag `v<version>` (CONTRIBUTING.md,
   "Releasing", steps 2 and 3).

**Expect:** the Release log says the commit was tested; the job is named "Build (tested on main)",
with no integration or platform jobs; the tag check passes.

### `rl-publish` Release signed, published, installable

- **On:** any
- **Launch:** none
- **Areas:** always

1. Check the GitHub release's assets and signatures (`releng/verify-signatures.sh`).
2. Install the release from the update site into a clean Eclipse.

**Expect:** the signatures verify; the plug-in installs, opens Get Neovim at the first start, and
attaches after it.
