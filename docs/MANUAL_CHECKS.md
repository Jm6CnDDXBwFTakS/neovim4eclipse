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

- **`due`** lists the cards whose source files changed since the last release tag (`v*`), and the
  cards marked `always`. A card about macOS's input method does not come up in a release that did
  not touch the `:` box. A POM, manifest or feature whose only change is the version number (each
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
   ⇒ Each picture matches its caption (the list below).

**Expect:** every picture matches what its caption says to look for: the Get Neovim dialog at a
fresh start and with changed files, the done note with and without `-Dneovim4eclipse.nvim`, the
"Neovim not started" notice and the page note for each refused value, the old-preference note, the
leftovers note. The wording reads right, no stray `&` or markup, rows a little apart. (Text cut
off, over a window's edge or on top of other text fails the tests already: `DialogLayoutTest` in
`tests/swt` and `NeovimPreferencePageTest` check every one of these dialogs with `LayoutCheck`.) (On macOS with *Show
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
- **Launch:** checks
- **Areas:** editor/SearchHighlighter.java, editor/SearchColors.java, util/OverlayColors.java

Whether a color reads well is for a person to judge; the painting itself is tested
(`SearchHighlighterTest`, `SearchHighlightingEndToEndTest`).

1. Open a Java file of `n4e-alpha` with the Java editor, in Neovim mode. Type `/e` and Enter.
   ⇒ Every `e` on screen has a light background behind it, the syntax colors of the text unchanged;
   the `e` under the caret is a shade darker.
2. Press `n` a few times, then type `:noh` and Enter.
   ⇒ The darker match moves with the caret; after `:noh` no background is left.
3. Switch to the dark theme (*Window → Preferences → General → Appearance*, Dark), restart if asked,
   and repeat step 1.
   ⇒ The matches stand out from the dark background and their text stays readable; the match under
   the caret is a shade lighter.
4. In *General → Editors → Text Editors → Annotations*, select *Search Results* and change its color.
   Apply.
   ⇒ The highlights in the open editor take the new color at once.

**Expect:** each step's result, after its ⇒.

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
   ⇒ While you type, the text being composed shows underlined in the box. After <kbd>Enter</kbd>
   the box shows `:こんにちわ`: each kana once, no `kkk`, no doubled kana, and the line has not run.
2. Press <kbd>Esc</kbd> to leave the line. Then `:`, type `kyou`, <kbd>Space</kbd> to convert
   (choose 今日 with the arrows if needed), <kbd>Enter</kbd> to keep it.
   ⇒ The box shows `:今日`; the <kbd>Enter</kbd> that keeps the text does not run the line.
3. Switch back to ABC input and press <kbd>Esc</kbd>.
   ⇒ The line closes.

**Expect:** each step's result, after its ⇒.

### `ime-keys` Esc, Enter, Ctrl keys and Cmd+V while composing

- **On:** macos
- **Launch:** normal
- **Areas:** util/MarkedTextInput.java, editor/CommandLineBox*.java, /pom.xml

1. `:` `abc`, switch to Japanese, type `ka`, press <kbd>Ctrl+U</kbd>, then <kbd>Enter</kbd>.
   ⇒ <kbd>Ctrl+U</kbd> does nothing while composing; the line becomes `:abcか`.
2. Type `ka`, press <kbd>Esc</kbd> twice, then <kbd>Esc</kbd> once more.
   ⇒ The first two <kbd>Esc</kbd> cancel the composition and the box stays open; the third
   closes the line.
3. Copy `src` to the clipboard; `:`, type `kaka`, press <kbd>Cmd+V</kbd>.
   ⇒ The line is `:かかsrc`.
4. <kbd>Esc</kbd>; in ABC input: `:` `echo "` <kbd>Option+E</kbd> <kbd>E</kbd> `"` <kbd>Enter</kbd>.
   ⇒ The message line shows `é` once.
5. In Ex mode (<kbd>g</kbd><kbd>Q</kbd>), Japanese, type `ka`, click in the editor's text. Then, in
   ABC input, <kbd>Ctrl+U</kbd>, `visual` <kbd>Enter</kbd> to leave Ex mode.
   ⇒ The click keeps `か` in Ex mode's line.

**Expect:** each step's result, after its ⇒.

### `ime-accent` Holding a letter shows the accent menu, and its pick replaces the letter

- **On:** macos
- **Launch:** normal
- **Areas:** util/PressAndHold*.java, util/MarkedTextInput.java, editor/CommandLineBox*.java, /pom.xml

1. ABC input, press-and-hold on (macOS's default). `:` `echo "`, hold <kbd>E</kbd> until the accent
   menu shows, let go, press <kbd>2</kbd>, type `"`, <kbd>Enter</kbd>.
   ⇒ The message line shows `é` once: no `eeee` from the held key, no `2`, no leftover `e`.
2. The same, but pick with the arrow keys and <kbd>Enter</kbd> in place of <kbd>2</kbd>.
   ⇒ The picked accent, once, as in step 1.
3. `:` `echo "`, hold <kbd>E</kbd>, let go, <kbd>Esc</kbd>; then <kbd>Esc</kbd> again.
   ⇒ The first <kbd>Esc</kbd> closes only the menu, and the box stays open on `:echo "e`; the
   second closes the line.
4. `:` `echo "`, hold <kbd>E</kbd>, let go, and click an accent at once.
   ⇒ The clicked accent replaces the e. (A click made after a pause is lost, an SWT bug described
   in the README: use the digit or the arrows.)
5. With the *ABC – AZERTY* input source, if you have it: step 1 with <kbd>Shift</kbd> and the
   <kbd>é</kbd> key (2) as the digit.
   ⇒ The picked accent, once, as in step 1.

**Expect:** each step's result, after its ⇒.

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

```powershell
java releng/tools/ci/LikeCi.java --all
```

**Expect:** every module passes, on the commit `check` printed.

### `rl-pall-windows-1core` Every test on Windows, on one core

- **On:** any
- **Launch:** none
- **Areas:** always

A loaded machine shows timing bugs a fast one hides (CONTRIBUTING.md, "Tests on a loaded
machine"); right before the release.

```powershell
$env:JAVA_TOOL_OPTIONS = '-XX:ActiveProcessorCount=1'
java releng/tools/ci/LikeCi.java --all
```

**Expect:** every module passes.

### `rl-publish` Release installable

- **On:** any
- **Launch:** none
- **Areas:** always

1. Run `java releng/tools/checks/InstallCheck.java` (the Release tool's `tag` step offers it). It
   installs the release from the update site into a fresh copy of your Eclipse, trusting only the
   release key, and offers to start that Eclipse; say yes.
   ⇒ The install passes, and the Eclipse starts.
2. In that Eclipse, let Get Neovim install Neovim, then open a text file.
   ⇒ Get Neovim opens at the first start, and the file attaches after it (Neovim's mode in the
   status bar).

**Expect:** each step's result, after its ⇒.
