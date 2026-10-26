# Contributing

Thank you for helping. This page says how to build, test and change the plug-in. To understand how
it works, read [docs/LEARNING.md](docs/LEARNING.md) (Eclipse and Neovim concepts) and
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) (the design) first.

## Set up

- **JDK 21** or newer.
- **Neovim 0.12.6**, the oldest supported version and the one CI tests, for the integration and
  end-to-end tests. Without `-Dnvim.path` the tests search as the plug-in does with a blank
  preference: the release archive extracted into `~/.local/share/neovim4eclipse-plugin/neovim`
  (Windows: `%USERPROFILE%\.local\share\neovim4eclipse-plugin\neovim`) first, then `nvim` on `PATH`,
  then the usual install folders.
  `-Dnvim.path=...` (a folder, or the absolute path of the program) picks another. A newer Neovim works
  too, but only 0.12.6 is what CI checks. Tests that find none are skipped, not failed: check the
  "Skipped" count.
- **Eclipse IDE for Eclipse Committers** (it includes PDE, the plug-in development tools), 2024-12 or
  newer, if you want to run the plug-in from the IDE.
- No Maven installation is needed: `./mvnw` (`mvnw.cmd` on Windows) downloads the pinned version.

## Build and test

```bash
./mvnw verify                                   # plug-in, unit/text/SWT tests, update-site ZIP
./mvnw verify -Pui                              # ... and the UI tests (opens an Eclipse window)
./mvnw verify -PuiHeavyJava                     # only the UI tests that need Java tooling (slow)
xvfb-run -a -s "-screen 0 1600x1000x24" ./mvnw verify -Pscreenshots   # retake the listing screenshots
./mvnw verify -DskipTests                       # build only
./mvnw test -f tests/unit/pom.xml               # fast unit tests, no Eclipse or Neovim
./mvnw test -f tests/text/pom.xml               # text-model tests (IDocument), no workbench or display
./mvnw test -f tests/swt/pom.xml                # SWT widget tests: a display, but no workbench
./mvnw test -f tests/unit/pom.xml -Pintegration # against a real Neovim (-Dnvim.path=... to choose)
./mvnw verify -Pui -Dtest.wait.seconds=60       # any of these with more time per wait (default 15)
xvfb-run -a ./mvnw verify -PtimeoutReliant       # only the tests that depend on the machine's speed
xvfb-run -a ./mvnw verify -Pall                  # every test of every group in one run (see below)
taskset -c 0,1 ./mvnw verify -Pui               # any of these on two cores, as a CI runner (Linux; Windows and macOS below)
```

**Running every test.** `-Pall` runs every test in one build: the unit, text, SWT and UI tests as
`-Pui` does, the integration tests (`-Pintegration`), the Java editor tests (`-PuiHeavyJava`, so the
test Eclipse gets the Java development tools) and the `timeoutReliant` ones. Only the screenshots
stay out, since they are not tests and rewrite the Marketplace images. Listing the profiles instead
(`-Pui,uiHeavyJava,timeoutReliant`) does not add them up: each one sets which tags run, and the last
one wins. Pass `-Dnvim.path` as for `-Pintegration`. It is the slowest run (a large first download
for the Java tools), so use it before a release or after a wide change, not for every commit. One
coverage report per module covers all its groups (`tests/unit/target/site/jacoco-all`).

The UI tests in `tests/io.github.jm6cnddxbwftaks.neovim4eclipse.tests` run only with `-Pui`. They
start an Eclipse workbench, which opens a window for about 15 seconds, so a plain `./mvnw verify`
leaves them out (it still compiles them). On a headless Linux machine run
`xvfb-run -a ./mvnw verify -Pui`, which keeps the window on a virtual display. CI always runs them,
on Linux, Windows and macOS (the *Build* workflow's `platforms` job covers the latter two),
but run `-Pui` yourself before you push a change to the editor, the session or the end-to-end
behavior: CI only shows a failure after the push.

The Java-editor checks (Format, Rename, Organize Imports, a save action, a template, `gd`/`K` as
Open Declaration and the Javadoc hover, folded imports) need the Java development tools in the
test Eclipse, which the normal UI run leaves out to stay fast. They are in `JavaEditorEndToEndTest`,
tagged `heavyJava`, and run only with `./mvnw verify -PuiHeavyJava`: that profile adds the
`org.eclipse.jdt` feature to the test Eclipse (a large first download, a slower start) and runs
only the tagged tests, while `-Pui` leaves them out. CI does not run them. Run them after changing
how Eclipse's own edits reach Neovim (`sync/`, `DocumentPush`, `CaretSync`) or the `gd`/`K` key
lookup. The fragment uses JDT only through IDs (editor, commands, natures, preferences), so it
does not depend on JDT and still starts without it.

`ScreenshotCaptureTest`, tagged `screenshots`, is not a check: it takes the Marketplace screenshots
(`docs/design/MARKETPLACE.md`, *Images*). Only `-Pscreenshots` runs it; `-Pui` leaves it out.

**Which module a test belongs in.** Put a test in the cheapest module that can run it:

1. `tests/unit` when the code is in `util` or `core` (no Eclipse at all).
   - **Lua that runs inside Neovim** (`core/LuaScripts`: which `:` commands Eclipse carries out,
     the write hook, the yank hook, leaving a folder) is tested in Lua, in
     `tests/unit/src/test/lua/*_spec.lua`, inside a headless Neovim without the Java session:
     what a script sends to Eclipse is recorded instead of sent, and keys typed with
     `nvim_feedkeys(keys, 'xt')` are done when the call returns, so nothing waits.
     `LuaScriptsInNeovimTest` runs each Lua test in a Neovim of its own, under `-Pintegration`
     (it needs a real Neovim). `src/test/lua/harness.lua` explains the helpers and the one limit:
     a test cannot show that something stays open. Example:
     `t.type(':wa<CR>'); t.eq({ { 'wall', false, {} } }, t.sent('eclipse_command'))`.
     Test the Java side of the same case (what the plug-in does with the notification) without
     Neovim, and keep one test through the real session only for the connection between the two.
2. `tests/text` when the code uses `IDocument`, `Document` or `DocumentEvent` but no SWT widget and
   no workbench. Example: `BufferSynchronizer` applies Neovim's line changes to a real `Document`;
   its test needs the text model, not an editor. The class must also be listed in the compiler
   includes of `tests/text/pom.xml`.
3. `tests/swt` when the code works on SWT widgets (a `StyledText`, painting, folded-code offsets)
   but not on the workbench (no `ITextEditor`, `IWorkbenchPage`, preferences). These tests create
   their own `Display` and `Shell` (`new Shell(Display.getDefault())`), so they run as plain JUnit,
   without starting Eclipse; they still need a display (`xvfb-run` on a headless machine). Example:
   `BlockOverlayTest` paints the overlay into an image and checks the pixels. A class that also
   needs Neovim or the editor can still go here once those are behind a small interface:
   `CommandLineBoxTest` drives the real `:` box with a fake `CommandLineBox.Commands`. List the class in
   the compiler includes of `tests/swt/pom.xml`.
4. The UI tests only for what really needs the workbench: editors and their commands, preference
   pages, key bindings, and the end-to-end tests with Neovim.

The compiler keeps 1 to 3 honest: each module has only the jars its tier may use (none, the text
model, SWT and JFace), so a class that starts using more no longer compiles there.

### Test helpers and fakes

No mocking framework is used; tests use real JFace objects and small hand-written fakes:

- `VimText` (`tests/text`): a real `Document` from marked text. `VimText.parse("al|pha")` puts the caret
  before `pha`, `«beta»` selects `beta`, and `VimText.SAMPLE` holds tabs, multi-byte characters and
  an empty line.
- `FakeCaret` (`tests/text`): an in-memory caret and selection with `assertCaretPosition(line, col)` and
  `assertSelectionRange(start, length)`.
- `UiTestSupport` (UI tests): `waitUntil(what, timeout, condition)` runs the event loop until a
  condition holds, and `settle(session, timeout)` waits until the session has handled everything
  queued so far and the UI thread has applied the result. Use these, not fixed sleeps: a sleep is
  either too short on a slow CI machine or wasted time on a fast one. `requireKeyboardFocus(control,
  timeout)` activates the control's window, gives the control the focus and waits until SWT reports
  it, or fails with "lost keyboard focus" and where the focus is (`focusReport`). Call it before a
  step whose result depends on focus (the Vim key context, the `:` box). Why it is needed: SWT
  counts focus only inside the active window, and `xvfb-run` has no window manager, so when a
  dialog or shell that a test opened closes, no window is active any more and `setFocus()` alone
  does nothing visible. On a desktop, typing in another window while the UI tests run does the same.
  `KeyboardFocus` (`tests/swt`) is the same helper for the SWT tests: `CommandLineBoxTest` uses it
  so that a `:` box closed by focus loss fails with "lost keyboard focus", not "Widget is disposed".
- `EndToEndTestBase` (UI tests): the setup of the end-to-end tests (`test.txt` open and attached,
  Neovim in normal mode) and their helpers: `expectText`, `openFile`, `waitForAttach`, `runCommand`
  (an Eclipse command by ID, for what a key binding would run), `runInBox` (a `:` command typed
  into the box; it fails with "lost keyboard focus" if the box closed before Enter), `statusLineTexts` (the window's status line, where Eclipse-side errors appear).
  `SessionProbe` runs Lua in Neovim, for what the editor cannot show (the buffer list).
- `TestWait` (`tests/shared`, used by every test module): how long a test waits for something it
  expects, 15 s unless `-Dtest.wait.seconds=N` says otherwise. Every wait uses it (`TestWait.millis()`,
  or `EndToEndTestBase.TIMEOUT_MS` in the UI tests), never a number of its own; see
  [Tests on a loaded machine](#tests-on-a-loaded-machine).
- `ExpectedLog` (`tests/shared`, used by every test module): for a test that makes the
  code fail on purpose. `try (ExpectedLog log = ExpectedLog.of(DocumentPush.class)) { ... }` keeps
  that class's log records off the console while the block runs, and
  `log.assertLogged(Level.SEVERE, "Could not switch")` checks the failure was reported. Without it
  such a test prints a warning and a stack trace into every build's output, where a real problem
  then goes unnoticed. Everything else still logs to the console.
- Fakes of the session's ports (`KeyRouter.Neovim`, `BufferPort`, `CursorPort`, `CommandRunner.Neovim`, `StateSync.Neovim`, `KeyInput.Neovim`) and `UiExecutor`
  (`IMMEDIATE`, or one that holds work back) are defined next to the tests that use them.

### Tests on a loaded machine

A test that waits for Neovim, a thread or the event loop must pass on a busy machine too: a CI
runner has two cores and shares them, and there an answer that usually takes 50 ms can take seconds.
Most tests that failed only under load failed in one of these ways, all fixed in the test, not by
waiting longer:

- **Checking before Neovim finished.** `nvim_input` only queues keys, so a check right after it can
  run before Neovim read them. Wait for the result (`waitUntil`, `expectText`), or for a value that
  only the last queued command sets (the `:redir` test waits for `g:mine`, set by `:redir END`).
- **Waiting for a state the test passes through.** The triple-click test waited for "beta selected",
  which the second click already does (charwise); it now waits for linewise visual mode, which only
  the third click gives.
- **Reading output that arrives in pieces.** Shell output reaches the popup a part at a time; the
  test waits for the whole of it (several lines), not for the first part.
- **Neovim settings that count real time.** `'mousetime'` (500 ms) decides whether two clicks are a
  double click; on a loaded machine two clicks sent at once can arrive further apart. The mouse
  tests set it to 10 s for themselves.

**Checking that something did not happen** needs care too: "sleep 200 ms, then check nothing
arrived" passes on a loaded machine even when the bug is there, because the stray event comes later.
The integration tests use `NeovimBarrier.pass(session)` instead: one request that Neovim answers
only after the keys before it, with every notification they caused sent and handled before the
answer (see that class for why the order holds). After it, what has not arrived will not.

**Tests that cannot help depending on the machine** are tagged `timeoutReliant`: a test that measures
time (`pendingCommandsDoNotStallTheInputThread` compares how long keys take) fails on a busy machine
with nothing wrong. The name says why: such a test relies on a time limit of its own (here, at
most 4 s more than plain keys), which the machine's speed decides, not only on the `TestWait` limit
every test has for an outcome that never comes. `-Pui` and `-Pintegration`, and so CI, leave them out; `-PtimeoutReliant` runs
only them, in `tests/unit` (with a real Neovim, so pass `-Dnvim.path` as for `-Pintegration`) and in
the UI tests (none tagged yet). Tag a test only when no rewrite helps: first look for the cause in
the list above, move the decision into a class with a unit test, or check a state instead of a
duration. Each tagged test says in its comment what checks the same thing without a clock. Run them
before a release (see [Releasing](#releasing)), on a quiet machine.

If they should ever run in CI, the plan is a checkbox on the *Build* workflow's manual run (an
`inputs:` entry under `workflow_dispatch` that adds `-PtimeoutReliant` steps), not every push, so a
busy runner cannot block a merge. It is not set up.

Every wait has the same limit, `TestWait` (15 s). A passing test returns as soon as its condition
holds, so the limit only matters when something is broken. To give a slow machine more time, or a
fast one less so that a hang fails sooner, pass `-Dtest.wait.seconds=N` to any test command above.

To reproduce CI's conditions, run the tests on fewer cores rather than next to a CPU hog: the run
then behaves the same way each time, and the JVM, the test Eclipse and Neovim all see the small
machine (the JVM sizes its thread pools and garbage collector by the cores it may use). Child
processes inherit the restriction:

**Linux.** `taskset -c` takes the list of cores the command may use (`0` is core 0 alone, `0,1`
the first two). Put it in front of any build command; with `xvfb-run`, in front of that:

```bash
taskset -c 0 xvfb-run -a ./mvnw verify -Pui -Dnvim.path=/path/to/nvim                       # one core
taskset -c 0,1 ./mvnw test -f tests/unit/pom.xml -Pintegration -Dnvim.path=/path/to/nvim   # two cores
taskset -c 0,1 xvfb-run -a ./mvnw verify -Pall -Dnvim.path=/path/to/nvim                   # everything, two cores
taskset -c 0,1 ./mvnw verify -PtimeoutReliant -Dnvim.path=/path/to/nvim                    # the clock tests
```

**Windows.** `start /affinity` takes a hexadecimal bit mask of cores: `1` is core 0 alone, `3`
cores 0 and 1 (binary 11). `/wait` waits for the build to end, `/b` keeps it in the same console,
and the empty `""` is the window title `start` expects first. From `cmd`:

```bat
start "" /affinity 3 /wait /b mvnw.cmd verify -Pui -Dnvim.path=C:\path\to\nvim
start "" /affinity 1 /wait /b mvnw.cmd test -f tests\unit\pom.xml -Pintegration -Dnvim.path=C:\path\to\nvim
```

`start` belongs to `cmd`, so from PowerShell run it through `cmd /c`. `--%` (PowerShell's
stop-parsing token) hands the rest of the line to `cmd` as written, so PowerShell neither drops the
empty `""` nor splits `-Dnvim.path=...` at the dot:

```powershell
cmd /c --% start "" /affinity 3 /wait /b mvnw.cmd verify -Pall -Dnvim.path=C:\path\to\nvim
```

To check that the mask took effect, open Task Manager, *Details*, right-click a `java.exe` or
`nvim.exe` process and choose *Set affinity*: only the chosen cores are ticked.

**macOS** cannot pin a process to cores. The nearest substitute makes every JVM size itself as if the
machine had two cores (thread pools, garbage collector), though they still run on all of them, and
so do Neovim and the test Eclipse's native code. It shows sizing problems, not slowness:

```bash
JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=2 ./mvnw verify -Pui -Dnvim.path=/path/to/nvim
```

`JAVA_TOOL_OPTIONS` reaches the forked test JVMs too, where `MAVEN_OPTS` would not; each JVM prints
"Picked up JAVA_TOOL_OPTIONS" as it starts. For a slow machine, use a Linux or Windows one, or a
virtual machine with two cores.

### Code coverage

Every test run also measures coverage with [JaCoCo](https://www.jacoco.org/jacoco/): a Java agent
records which lines and branches the tests execute, and a report shows them per package, class and
source line (green: run, yellow: a branch only partly taken, red: never run). Open the report in a
browser:

| Tests | Report |
|-------|--------|
| Unit tests (`./mvnw verify` or `./mvnw test -f tests/unit/pom.xml`) | `tests/unit/target/site/jacoco/index.html` |
| Unit tests against real Neovim (`-Pintegration`) | `tests/unit/target/site/jacoco-integration/index.html` |
| Text-model tests (`./mvnw verify` or `./mvnw test -f tests/text/pom.xml`) | `tests/text/target/site/jacoco/index.html` |
| SWT tests (`./mvnw verify` or `./mvnw test -f tests/swt/pom.xml`) | `tests/swt/target/site/jacoco/index.html` |
| UI tests (`./mvnw verify -Pui`) | `tests/io.github.jm6cnddxbwftaks.neovim4eclipse.tests/target/site/jacoco/index.html` |
| Every unit-module group at once (`-Pall`) | `tests/unit/target/site/jacoco-all/index.html` (the UI tests' report is the usual one) |

**Why one report per kind of test, not one merged report.** JaCoCo matches what it recorded to class
files by a checksum of their bytecode. The unit tests compile their own copy of `core/` and `util/`
with `javac`, while Tycho compiles the plug-in with the Eclipse compiler (ecj), so the same source
gives different bytecode and the two recordings cannot be added up. To see the combined picture for
one class, open it in both reports. (Merging would need the unit tests to run against the plug-in's
own class files, which would also let `core/` import Eclipse classes without the compiler noticing.)

**How to read it.** Coverage says which code *no* test runs; it does not say the tests check the
right thing (a line can run without any assertion looking at its result). Use it to find untested
logic and to check that moving a test from one module to another did not lose anything. There is no
minimum percentage: a threshold rewards tests written for the number.

Baseline on 2026-10-18 (lines covered; before the text-model tests moved from the UI tests to
`tests/text`, whose report is `tests/text/target/site/jacoco/index.html` and covers `sync` and the
`editor` classes it compiles). After that move no line covered before lost its coverage: the
end-to-end tests still run the rest.

| Package | Unit tests | UI tests |
|---------|-----------|----------|
| `util` | 98% (808/823) | 67% |
| `core` | 59% (539/919; the rest needs Neovim) | 78% (with real Neovim) |
| `sync` | not compiled | 87% (131/151) |
| `editor` | not compiled | 68% (1013/1498) |
| `prefs` | not compiled | 74% |
| All | 77% of 1742 lines | 71% of 3660 lines |

The integration profile alone covers 49% of `core` and `util`.

Avoid `clean` while a runtime Eclipse launched from the IDE is running: it deletes the plug-in's
`lib/*.jar`, which that Eclipse is using.

The installable archive is `releng/io.github.jm6cnddxbwftaks.neovim4eclipse.updatesite/target/neovim4eclipse-<version>.zip`.

## Run it from Eclipse

See [docs/HOWTO_ECLIPSE.md](docs/HOWTO_ECLIPSE.md). In short: *File > Import > Existing Maven Projects*,
then open `plugins/.../META-INF/MANIFEST.MF` and click *Launch an Eclipse application*.

## Project layout

| Path | Contents |
|------|----------|
| `plugins/io.github.jm6cnddxbwftaks.neovim4eclipse` | the plug-in |
| `tests/unit` | JUnit tests of `util` and `core`, without Eclipse |
| `tests/text` | JUnit tests of code that uses Eclipse's text model but no widgets (`sync`, parts of `editor`), without a workbench |
| `tests/swt` | JUnit tests of `editor` classes that use SWT widgets but not the workbench; need a display |
| `tests/shared` | test helpers shared by those modules (`ExpectedLog`); not a module of its own |
| `tests/io.github.jm6cnddxbwftaks.neovim4eclipse.tests` | workbench tests (a fragment of the plug-in) |
| `features/...feature` | the installable feature |
| `releng/...updatesite` | the p2 update site and its ZIP |
| `releng/eclipse-formatter.xml` | the Java formatter profile |
| `releng/release-notes.sh` | prints a version's `CHANGELOG.md` section, the release notes |
| `releng/render-icon.py` | renders `docs/images/logo.svg` to the toolbar icon PNGs (`plugins/.../icons/`), with headless Firefox |
| `releng/update-site.sh` | builds the permanent update site (a composite p2 repository) from the release ZIPs |
| `releng/smoke-install.sh` | installs the plug-in from an update site into an Eclipse with p2's director, and checks the version |
| `docs/` | learning guide, architecture, keys, packaging, platform notes, upgrading versions |
| `docs/design/` | design notes: one per larger change, planned or built ([index](docs/design/README.md)) |

The project has two names. People see **Neovim for Eclipse** (feature, bundle name, preference
page, release titles); everything a machine reads uses **neovim4eclipse**: the repository, the
Maven artifactIds, the ZIP (`neovim4eclipse-0.1.0.zip`), the bundle IDs and Java packages
(`io.github.jm6cnddxbwftaks.neovim4eclipse`), `-Dneovim4eclipse.logLevel` and the guard variable
`vim.g.neovim4eclipse`. Why these two:

- The Eclipse Foundation's trademark guidelines do not allow "Eclipse" inside a third-party product
  name; "*product* for Eclipse" is the accepted form (it matters for an Eclipse Marketplace listing).
- Java packages and OSGi bundle IDs cannot contain a hyphen, so the machine name is one word, and
  the "4" keeps it readable. Using the same word everywhere means there is only one to remember.

A bundle ID is part of what users install: p2 sees a renamed bundle as a different plug-in (no
update, only uninstall and reinstall), and saved preferences live under it. Do not rename it after a
release.

## Rules the code relies on

These are explained in [docs/LEARNING.md](docs/LEARNING.md); breaking them causes freezes or wrong
text that tests do not always catch.

1. **Never wait for Neovim on the SWT UI thread.** Use the session's `...Async` methods; blocking calls
   belong on the session's input thread. The one exception is bounded: before Eclipse deletes or
   closes a project, `ProjectFolderExit` waits at most 1 s for Neovim to leave its folder, since the
   delete cannot wait for a callback (see ARCHITECTURE.md, "Working folder").
2. **Touch widgets and documents only on the UI thread.** From other threads, use
   `Display.asyncExec`.
3. **Keep `util` and `core` free of `org.eclipse.*` imports.** `tests/unit` compiles them without
   Eclipse, so the build fails if they gain such an import.
4. **Convert columns.** Neovim columns are UTF-8 bytes, Eclipse offsets UTF-16 characters
   (`DocumentPositions`, `Utf8Columns`).
5. **While Neovim is blocked** (after `f`, `r`, `"`, ...), send nothing but `nvim_input` and
   `nvim_get_mode`.

## Making a change

- Put decisions that need no Eclipse in `util` (or `core` for Neovim logic) with a unit test, and keep
  the Eclipse class a thin caller. Code that needs only the text model gets a test in `tests/text`,
  widget code in `tests/swt`; only workbench glue gets a test in the UI test fragment.
- Library versions live only in the root `pom.xml`. A new library needs a `dependencyManagement` entry,
  an `artifactItem` in the plug-in's `pom.xml`, a `Bundle-ClassPath` entry in `MANIFEST.MF`
  (`BundleClassPathConsistencyTest` checks them) and its license in `plugins/.../licenses/` and
  `about.html`.
- [docs/LEARNING.md](docs/LEARNING.md), part 4, has recipes: adding a preference, a Neovim event, an
  Eclipse-side `:` command.
- Plan a larger change (several commits, a new subsystem, a release process) in a design note in
  [docs/design/](docs/design/README.md) first, and keep its status current as it is built.
- Update the documentation (README, `docs/`, Javadoc) when behavior changes.
- Add a line to [CHANGELOG.md](CHANGELOG.md), under *Unreleased*, for every change a user of the
  plug-in would notice (a feature, a fix, a changed default). Internal changes (refactoring, tests,
  build) need none.
- To change a version (JDK, Eclipse, a library, a build tool, Neovim), follow
  [docs/UPGRADING.md](docs/UPGRADING.md); `VersionConsistencyTest` lists any copy left behind.
- `AGENTS.md` (read by Claude Code through `CLAUDE.md`) gives AI assistants the same rules in short.

## Style

- Format Java with `releng/eclipse-formatter.xml` (*Window > Preferences > Java > Code Style > Formatter
  > Import...*). It leaves comments alone; wrap them by hand at about 100 characters.
- [`.editorconfig`](.editorconfig) sets indentation and line endings for other files.
- Comments explain *why*, and name the Eclipse or Neovim concept involved, so that the code teaches
  how it works.
- An exception class of our own extends `util/NeovimExceptionBase` (abstract, unchecked), so that
  `catch (NeovimExceptionBase e)` catches everything the plug-in raises itself. Examples:
  `core/rpc/NeovimErrorException` (Neovim refused a call), `NeovimConnectionClosedException`
  (Neovim is gone).
- Every source file starts with the SPDX header:

  ```java
  // SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
  // SPDX-License-Identifier: EPL-2.0
  ```

## Branches

Work flows up through three kinds of branches, and a branch is deleted once it has been merged
into the next one up (right away, or at a cleanup):

```text
fix/<name>, feature/<name>  ──►  release/<next version>  ──►  main  (tagged v<version>)
```

- **`main`** holds released code only. It moves forward only at a release (step 2 of
  "Releasing"), and every tag `v<version>` is on it.
- **`release/<version>`**, for example `release/0.1.2`, collects the fixes and features of the
  next release. It is cut from `main` right after a release, and its first commit sets the version
  in the POMs to `<version>-SNAPSHOT` (see "Releasing", step 4). The *Build* workflow does not run
  on it automatically, to save Actions minutes: run it by hand when a change needs CI, and always
  before `main` moves (step 1 of "Releasing"). If the scope grows, rename it (0.1.2 to 0.2.0)
  and set the version again.
- **`fix/<name>`** and **`feature/<name>`** hold one piece of work, cut from the release branch and
  merged back into it, by pull request or directly. They are not built automatically; run the
  *Build* workflow on one by hand when a change needs CI before the merge.
- **An urgent fix** while the release branch holds work that is not ready: rename that branch to
  the version after (`release/0.1.2` to `release/0.1.3`, setting its version again), cut a new
  `release/0.1.2` from `main` for the fix alone, release it, then merge `main` into
  `release/0.1.3` so it has the fix too.

Pull requests are optional: merging directly (preferably as a fast-forward) keeps the same flow.

## Commits and pull requests

- One logical change per commit, with a message that says what changed and why.
- Pull requests use the template in [.github](.github): describe the change and how you tested it.
  The *Build* workflow must pass.

## Releasing

A release is a `v<version>` tag on `main`; pushing it starts the *Release* workflow
(`.github/workflows/release.yml`), which builds and tests that version like every CI run,
signs the ZIP with the project's PGP key (see [docs/PACKAGING.md](docs/PACKAGING.md), "PGP
signing of releases"), publishes a GitHub Release with it, and then rebuilds the update site on GitHub Pages
(`.github/workflows/update-site.yml`, see [docs/PACKAGING.md](docs/PACKAGING.md)) with the new
release in it. For 0.1.2, released from `release/0.1.2`:

1. On `release/0.1.2`, in [CHANGELOG.md](CHANGELOG.md), rename `## [Unreleased]` to
   `## [0.1.2] - 2026-11-02` (the release date, in ISO form) and add a new, empty
   `## [Unreleased]` above it. That section becomes the release notes;
   `releng/release-notes.sh 0.1.2` prints them, exactly as they will appear. Update the version in
   the README's status line. Run every test on your machine, those CI leaves out included (the Java
   editor and the timeout-reliant ones; see [Tests on a loaded machine](#tests-on-a-loaded-machine)):

   ```bash
   xvfb-run -a ./mvnw verify -Pall -Dnvim.path=/path/to/nvim
   ```

   Commit and push, then run the *Build* workflow on the branch by hand and wait for it to pass:

   ```bash
   gh workflow run build.yml --ref release/0.1.2
   gh run watch   # pick the new run from the list; it can take a few seconds to appear
   ```
2. Fast-forward `main` to the release branch (or merge a pull request from it), push `main`, and
   wait for the *Build* workflow on `main` to pass:

   ```bash
   git push origin release/0.1.2:main
   ```

3. Tag the commit on `main` and push the tag:

   ```bash
   git tag v0.1.2 origin/main && git push origin v0.1.2
   ```

4. Start the next release: cut `release/0.1.3` from `main`, set its version, commit, and push it;
   then delete `release/0.1.2` (and any merged `fix/` or `feature/` branches):

   ```bash
   git switch -c release/0.1.3 origin/main
   ./mvnw org.eclipse.tycho:tycho-versions-plugin:set-version -DnewVersion=0.1.3-SNAPSHOT
   git commit -am "Start 0.1.3: set the version to 0.1.3-SNAPSHOT" && git push -u origin release/0.1.3
   git push origin --delete release/0.1.2
   ```

The workflow stops before building anything if the tagged commit is not on `main` or the changelog
has no `0.1.2` section. Nothing is committed back: the build replaces the committed
`0.1.2-SNAPSHOT` / `0.1.2.qualifier` with the tag's `0.1.2` inside the runner only. Because each
release branch carries its own snapshot version, a test build of it (`0.1.3.<timestamp>`) sorts
above the last release, so Eclipse installs it as an update.

## License

By contributing you agree that your contribution is licensed under the
[Eclipse Public License 2.0](LICENSE), like the rest of the project.
