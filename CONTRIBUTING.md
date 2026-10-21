# Contributing

Thank you for helping. This page says how to build, test and change the plug-in. To understand how
it works, read [docs/LEARNING.md](docs/LEARNING.md) (Eclipse and Neovim concepts) and
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) (the design) first.

## Set up

- **JDK 21** or newer.
- **Neovim 0.9** or newer, as `nvim` on `PATH`, for the integration and end-to-end tests.
- **Eclipse IDE for Eclipse Committers** (it includes PDE, the plug-in development tools), 2024-12 or
  newer, if you want to run the plug-in from the IDE.
- No Maven installation is needed: `./mvnw` (`mvnw.cmd` on Windows) downloads the pinned version.

## Build and test

```bash
./mvnw verify                                   # plug-in, unit/text/SWT tests, update-site ZIP
./mvnw verify -Pui                              # ... and the UI tests (opens an Eclipse window)
./mvnw verify -PuiHeavyJava                     # only the UI tests that need Java tooling (slow)
./mvnw verify -DskipTests                       # build only
./mvnw test -f tests/unit/pom.xml               # fast unit tests, no Eclipse or Neovim
./mvnw test -f tests/text/pom.xml               # text-model tests (IDocument), no workbench or display
./mvnw test -f tests/swt/pom.xml                # SWT widget tests: a display, but no workbench
./mvnw test -f tests/unit/pom.xml -Pintegration # against a real Neovim (-Dnvim.path=... to choose)
```

The UI tests in `tests/io.github.jm6cnddxbwftaks.neovim4eclipse.tests` run only with `-Pui`. They
start an Eclipse workbench, which opens a window for about 15 seconds, so a plain `./mvnw verify`
leaves them out (it still compiles them). On a headless Linux machine run
`xvfb-run -a ./mvnw verify -Pui`, which keeps the window on a virtual display. CI always runs them,
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

**Which module a test belongs in.** Put a test in the cheapest module that can run it:

1. `tests/unit` when the code is in `util` or `core` (no Eclipse at all).
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
- `EndToEndTestBase` (UI tests): the setup of the end-to-end tests (`test.txt` open and attached,
  Neovim in normal mode) and their helpers: `expectText`, `openFile`, `waitForAttach`, `runCommand`
  (an Eclipse command by ID, for what a key binding would run), `runInBox` (a `:` command typed
  into the box), `statusLineTexts` (the window's status line, where Eclipse-side errors appear).
  `SessionProbe` runs Lua in Neovim, for what the editor cannot show (the buffer list).
- `ExpectedLog` (`tests/shared`, used by `tests/unit` and `tests/text`): for a test that makes the
  code fail on purpose. `try (ExpectedLog log = ExpectedLog.of(DocumentPush.class)) { ... }` keeps
  that class's log records off the console while the block runs, and
  `log.assertLogged(Level.SEVERE, "Could not switch")` checks the failure was reported. Without it
  such a test prints a warning and a stack trace into every build's output, where a real problem
  then goes unnoticed. Everything else still logs to the console.
- Fakes of the session's ports (`KeyRouter.Neovim`, `BufferPort`, `CursorPort`, `CommandRunner.Neovim`, `StateSync.Neovim`, `KeyInput.Neovim`) and `UiExecutor`
  (`IMMEDIATE`, or one that holds work back) are defined next to the tests that use them.

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
| `docs/` | learning guide, architecture, keys, packaging, platform notes, upgrading versions |

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
   belong on the session's input thread.
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

## Commits and pull requests

- One logical change per commit, with a message that says what changed and why.
- Pull requests use the template in [.github](.github): describe the change and how you tested it.
  The *Build* workflow must pass.

## Releasing

A release is a `v<version>` tag on `main`; pushing it starts the *Release* workflow
(`.github/workflows/release.yml`), which builds and tests that version like every CI run and
publishes a GitHub Release with the ZIP. For 0.1.0:

1. In [CHANGELOG.md](CHANGELOG.md), rename `## [Unreleased]` to `## [0.1.0] - 2026-11-02` (the
   release date, in ISO form) and add a new, empty `## [Unreleased]` above it. That section becomes
   the release notes; `releng/release-notes.sh 0.1.0` prints them, exactly as they will appear.
2. Commit that on `develop`, fast-forward `main` to it, push both, and wait for the *Build* workflow
   on `main` to pass.
3. Tag the commit on `main` and push the tag:

   ```bash
   git tag v0.1.0 main && git push origin v0.1.0
   ```

The workflow stops before building anything if the tagged commit is not on `main` or the changelog
has no `0.1.0` section. Nothing is committed back: the version in the POMs stays
`0.1.0-SNAPSHOT` until it is raised for the next release.

## License

By contributing you agree that your contribution is licensed under the
[Eclipse Public License 2.0](LICENSE), like the rest of the project.
