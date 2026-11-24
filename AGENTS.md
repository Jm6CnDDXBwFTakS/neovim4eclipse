# Notes for AI coding assistants

Context for AI tools working on this repository (Claude Code reads it through `CLAUDE.md`). Humans
are welcome to read it too; [CONTRIBUTING.md](CONTRIBUTING.md) is the full guide.

## The project

An Eclipse plug-in that embeds a real Neovim (`nvim --embed`) in Eclipse text editors. Built with
Maven and Tycho into a p2 update site ZIP. It is also a learning project: code and docs explain
Eclipse plug-in and Neovim API concepts, and must stay maintainable by humans for the long term.

Read first: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) (design), [docs/LEARNING.md](docs/LEARNING.md)
(concepts), [docs/KEYBOARD.md](docs/KEYBOARD.md) (key routing), [docs/GLOSSARY.md](docs/GLOSSARY.md)
(Eclipse's and Neovim's words that look alike).

## How to work

- **Ask when a spec is unclear**: a choice of behavior, a name, a default or text a user sees,
  before building it, saying what you would assume. Code or docs answer it only if still current:
  one older than a later change to the same behavior (`git log -- <file>`, `CHANGELOG.md`), or two
  sources that disagree, is a reason to ask, naming both. Why: a question costs a minute, a wrong
  guess a rebuild.
- **Offer the option that is easy to prove**: when you give the maintainer a choice (a design, a
  rule for the docs, how to close a gap a review found), say which option's safety one check
  establishes, and recommend it unless it costs something real. Easy to prove: check where a path
  ends, not the route to it (the real path through `util/FilePaths`, not each link on the way);
  allow a known set instead of refusing a list of bad cases (`NewFiles.internalOr` lets only
  Eclipse's own text editors open a file); filter in the one place everything passes through, not on
  each route to it (every `:` line passes `CmdlineLeavePre`, every jump `followNeovimIntoFile`); act
  only on what the code found itself, never on text a program printed; decide from the state itself,
  read at the check (Neovim's current window, asked of Neovim), not from a copy kept up to date
  along the way. Say what the option costs (what it refuses that the other allowed) and what the
  other option would need instead. Why: a rule proven by one check needs one review; a rule that
  guards every route keeps turning up new routes.
- **Honor Neovim's own settings, within reason**: when a feature is added or changed, the
  standard Vim and Neovim options that govern it in a terminal (`'cmdheight'`, `'laststatus'`,
  `'statusline'`, `'showcmd'`, ui2's config) decide it here too, before any preference of the
  plug-in's, where that is reasonable to build. List the options that touch the feature, and say
  which are honored, which are not, and why. Leave one out, and say so in the docs, when
  following it would cost far more than it gives: much plug-in code for a rarely used option, a
  fight with how Eclipse works (an option that would move Eclipse's text), or a workaround that is
  hard to keep correct. A new plug-in setting is for what no Vim option covers. Why: users
  configure Neovim in `init.lua` and expect it to behave as configured; a setting of our own beside
  a Vim option is a second place to look.
- **State the rules before building**: a plan lists the few rules the change must keep, one line
  each, each one a check the code can be pointed at. Example, for a plug-in's floating window
  with the keyboard: keys go only to a window Eclipse draws; closing a window never discards a
  changed buffer. The scope stays as planned: a case found while building or reviewing that breaks
  none of these rules goes to the next step or the report, not into this change. Why: a review
  against stated rules ends; a hunt for edge cases finds one more each round.
- **Review before the final test run**: once a change is built and its own tests pass, a code review
  (correctness against the docs and spec, edge cases, error handling, docs kept current) and a
  security review (untrusted input, paths, links, what a later step trusts) of the branch's diff (in
  Claude Code `/code-review`, `/security-review`), each by a fresh reviewer that reads the code, not
  this session's conclusions; then the fixes. **What counts as real:** a finding that breaks one of
  the change's own rules (above) or a rule this file states (a cloned repository's file that makes
  something run: a name reaching a command, a script-running editor, the system's program; Eclipse's
  and Neovim's text drifting apart, or a user's text lost; a blocking call on the UI thread;
  personal data in anything pushed), that stops a real use from working as documented, or that makes
  the code disagree with its docs. A case that needs the user's own config or programs to act
  against them, a crafted input that can only spoil its own result, or a message's wording is noted
  in the report, not fixed in another round. Ask the reviewer to sort its findings by this rule. At
  most two rounds (the review, and one over its fixes); list what is left and ask before a third.
  Then `LikeCi --all` or `-Pall`, last, since review fixes change the code that run must test.

## Commands

```bash
java releng/tools/ci/LikeCi.java                  # BEFORE A PUSH: the tests as the Build workflow runs them (pinned Neovim, xvfb-run)
java releng/tools/ci/LikeCi.java --all            # the same with every test group (-Pall)
java releng/tools/ci/LikeCi.java --all --clean    # the same from an empty build (not while a runtime Eclipse is open)
./mvnw verify -Pall -Dnvim.path=/usr/bin/nvim     # every test of every group (integration, heavyJava, timeoutReliant; not screenshots); slow
./mvnw verify -Pui -Dnvim.path=/usr/bin/nvim      # as CI: plug-in, unit, text, SWT and UI tests, feature, ZIP (not integration: add the -Pintegration line)
./mvnw verify                                     # the same without the UI tests (no Eclipse window)
./mvnw verify -PuiHeavyJava -Dnvim.path=/usr/bin/nvim   # only the UI tests that need Java tooling (slow; not in CI)
./mvnw verify -PtimeoutReliant -Dnvim.path=/usr/bin/nvim   # only the tests tagged timeoutReliant (measure time; not in CI)
./mvnw verify -Pscreenshots                       # take the Marketplace screenshots, uncommitted (target/screenshots; docs/design/MARKETPLACE.md)
./mvnw verify -Pgallery -Dnvim.path=/usr/bin/nvim  # draw the dialogs and search highlights (light and dark) the manual checks look at (target/gallery; CONTRIBUTING.md)
./mvnw verify -DskipTests                         # build only
java releng/tools/checks/ManualChecks.java due    # the manual checks a release needs (docs/MANUAL_CHECKS.md; JDK 22+)
java releng/tools/checks/InstallCheck.java        # install the newest release from the update site into a copy of your Eclipse (JDK 22+)
java releng/tools/checks/Proxy.java               # an HTTP proxy on 127.0.0.1:3128 that prints each host it reaches, for the card mp-setup
java releng/tools/neovim/NeovimUpgrade.java 0.13.0  # move to a new Neovim release (data file, copies, checks, tests; docs/UPGRADING.md)
java releng/tools/eclipse/EclipseUpgrade.java newest  # test with the newest Eclipse release; oldest <release> raises the floor; smoke installs into both (docs/UPGRADING.md)
java releng/tools/maven/MavenUpgrade.java        # move to the newest Maven 3.9.x and Maven wrapper (checked downloads, tests, one commit each; docs/UPGRADING.md)
java releng/tools/javarelease/JavaUpgrade.java 25 --java <JDK 25 home>  # raise the Java release and its copies (LTS only; smoke installs and every test on that JDK, one commit; docs/UPGRADING.md)
java releng/tools/plugins/TestPlugins.java fetch  # the pinned Neovim plug-ins the jump-label tests and the gallery run, into .tool-cache/test-plugins (update <name> <commit>, then accept <name> <commit> <sha256>, moves a pin to a commit you chose; docs/UPGRADING.md)
java releng/tools/dependencies/Dependencies.java  # move libraries, Maven plug-ins and GitHub Actions to their newest releases, by dependabot.yml's rules (docs/UPGRADING.md)
java releng/tools/marketplace/Marketplace.java    # the Marketplace listing page; pictures takes the screenshots and records demo.gif, logo renders the PNGs (docs/design/MARKETPLACE.md)
java releng/tools/release/Release.java            # a release, step by step: status and next step (a JDK of release 22 or newer; CONTRIBUTING.md, "Releasing")
./mvnw test -f tests/unit/pom.xml                 # unit tests (no Eclipse, no Neovim)
./mvnw test -f tests/text/pom.xml                 # text-model tests (IDocument; no workbench, no display)
./mvnw test -f tests/swt/pom.xml                  # SWT widget tests (a display, no workbench)
./mvnw test -f tests/unit/pom.xml -Pintegration -Dnvim.path=/usr/bin/nvim   # real Neovim
xvfb-run -a ./mvnw verify -Pui                    # on a machine without a display
taskset -c 0,1 ./mvnw verify -Pui                # as a two-core CI runner; Windows (start /affinity) and macOS: CONTRIBUTING.md, "Tests on a loaded machine"
```

The UI tests need `-Pui` (off by default because they open a window); run them after changing
editor or session code. Every run that starts Neovim needs `-Dnvim.path` (enforced): the tests use
folders of their own under `target/`, without the dedicated Neovim (CONTRIBUTING.md, "Your Neovim
config and the UI tests"). Run `LikeCi` before every push; compile at the least. Each test module writes a JaCoCo coverage report to `target/site/jacoco/index.html` (see
CONTRIBUTING.md, "Code coverage"; one report per module, they cannot be merged).

Use the Maven wrapper (`./mvnw`), not a system Maven.

## Changing versions (JDK, Eclipse, libraries, tools, Neovim)

Follow [docs/UPGRADING.md](docs/UPGRADING.md). In short:

- Each version has one source of truth, mostly properties in the root `pom.xml`
  (`java.release`, `eclipse-repo.url`, library and plug-in versions).
- Literal copies exist where Maven cannot fill one in (`MANIFEST.MF`, `build.properties`,
  `.java-version`, docs). `VersionConsistencyTest` fails if any disagrees; run it after a change.
- Never pin a plug-in version in a child POM; never put a version number in a `lib/` jar name.
- Check license and NOTICE files in `plugins/.../licenses/` when a library version changes.
- Do not upgrade anything as a side effect of other work; one upgrade per commit.

## Rules the code depends on

- No blocking Neovim RPC on the SWT UI thread; queue work through the session's `*Async` methods.
  Two bounded exceptions (docs/ARCHITECTURE.md, "Threading rules"): `editor/ProjectFolderExit`
  waits at most 1 s before a project delete, and the first editor's attach (and the next one
  after a restart) starts the editing Neovim synchronously (`util/BusyStart`), so that keys
  typed meanwhile reach it in order.
- Compare file paths only through the one rule: `util/FilePaths` in Java, `neovim4eclipse.paths`
  (`LuaScripts.PATHS`) in Lua, `t.same_file` in Lua specs; find a path's workspace file only with
  `NewFiles.workspaceFileAt`. A new case goes into `tests/unit/src/test/resources/path-cases.tsv`,
  which both rules must pass. Never `equals` or `==` on two paths: the same file is spelled
  differently on Windows (`\` or `/`, any case) and macOS (case ignored, `/var` is `/private/var`).
- Neovim columns are UTF-8 bytes, Eclipse columns UTF-16 chars; convert with `Utf8Columns` /
  `DocumentPositions`.
- `StyledText` offsets are not document offsets when code is folded; convert with `WidgetOffsets`.
- `util/` and `core/` must not import `org.eclipse.*` (they are compiled and unit-tested without
  Eclipse). `sync/` and the `editor/` classes listed in `tests/text/pom.xml` may use only the text
  model (`org.eclipse.jface.text` types from the `org.eclipse.text` jar), no SWT or workbench. The
  `editor/` and `prefs/` classes listed in `tests/swt/pom.xml`, and `dialogs/`, may use SWT and
  JFace, not the workbench (a preference page's body: `prefs/package-info`, "Two classes per page").
- A test waits with `TestWait` (`-Dtest.wait.seconds`, default 15) for an outcome, never with a
  fixed sleep or a limit of its own; to check that nothing more arrived, an integration test uses
  `NeovimBarrier.pass`, not a sleep. A test that must depend on the machine's speed is tagged
  `timeoutReliant` (left out of -Pui, -Pintegration and CI), only when no rewrite helps. See
  CONTRIBUTING.md, "Tests on a loaded machine".
- No test reads the developer's own Neovim config (`--clean`; for the UI tests `NVIM_APPNAME` and
  the `XDG_*` folders in their `pom.xml`). A test about a Neovim setting sets it itself. See
  CONTRIBUTING.md, "Your Neovim config and the UI tests".
- Put each test in the cheapest module that can run it: `tests/unit`, then `tests/text`, then
  `tests/swt`, then the UI tests (see CONTRIBUTING.md, "Which module a test belongs in"). The Lua
  the plug-in sends (`core/LuaScripts`, and the files in `core/lua/` it reads) is tested in Lua
  (`tests/unit/src/test/lua/*_spec.lua`, a headless Neovim, no Java session), not through
  `NeovimSession`.
- Lua reaches Neovim only as text a `core/LuaScripts` field holds: a short script there, or a file
  in `core/lua/` that `core/LuaModules` reads from the plug-in's own jar. Never put a folder of the
  plug-in's Lua on `'runtimepath'` or write it to disk for Neovim: a file earlier on the
  runtimepath (the user's config, a plug-in) would stand in for a module. A new file needs a field
  that names it (`LuaModulesTest` fails on a file no script reads). The one Lua file the plug-in
  writes for Neovim is `core/HealthCheck`'s `:checkhealth` stub, on the runtimepath because
  `:checkhealth` finds checks only there: it holds none of the plug-in's code, and answers only when
  the check the plug-in sends was not registered. Beside it are only the help page and its tags
  (`core/PluginHelp`), which run nothing.
- Prefer putting decisions in small pure classes (testable in `tests/unit`) and keeping Eclipse
  classes as thin glue. Large classes are split by concern (see `core/package-info.java`).

## Pitfalls

- Do not run `clean` while a runtime Eclipse launched from the IDE is running: it deletes
  `plugins/.../lib/*.jar`, which that Eclipse uses.
- Build the UI test fragment with the whole reactor, never `-pl ... -am`: Maven cannot see its
  dependency on the plug-in (declared in `MANIFEST.MF`) and would test a stale copy from `~/.m2`.
- Stale classes in `tests/.../target/classes` survive deleting their source; remove them by hand.
- After changing a constructor or method signature, callers whose source did not change are not
  recompiled and fail at run time with `NoSuchMethodError` (in UI tests: every attach times out).
  Delete `plugins/.../target/classes` (not `clean`) and build again.
- The same goes for a changed `static final String` constant (such as a short `LuaScripts` script,
  a text block): javac copies its text into every class that uses it, so those classes keep the old
  text until they are recompiled. Delete `plugins/.../target/classes` after changing one. A file in
  `core/lua/` needs no such rebuild of other classes, but it is a resource: the build copies it
  into `target/classes` (Maven's resources step, or Eclipse's own build), and a running Eclipse
  read it once, when `LuaScripts` loaded, so it needs a restart to send the new text.
- Synthetic SWT key events in UI tests need `event.doit = true`.
- Folding has several deliberate workarounds (a key watcher that drops `zE` and its like while `z` is pending, `:noautocmd
  normal! zE`, fold sends held and cursor reports dropped while Eclipse types). Read the table
  "Folding: the workarounds, and why" in docs/ARCHITECTURE.md before changing `LuaScripts.FOLDS`,
  `FoldMirror` or `CaretSync`. A script the plug-in sends may run while Neovim is in insert mode:
  never use a plain `:normal` in one, since it fires `ModeChanged` and the plug-in then takes
  insert mode as ended (use `:noautocmd normal!`, or an API call).

## Conventions

- License EPL-2.0. Every source file starts with the two-line SPDX header
  (`SPDX-FileCopyrightText` and `SPDX-License-Identifier: EPL-2.0`); Markdown files have none.
- American spelling (behavior, color, license).
- Every exception class the plug-in defines extends `util/NeovimExceptionBase` (abstract, unchecked).
- The names in the README's *Settings* table are promised (users' `init.lua`, `eclipse.ini`, key
  bindings and preferences hold them): never rename one, and remove one only with the part it set,
  once it has no effect left, listing it under the README's *Removed* (`SettingsNamesTest.REMOVED`).
  A new user-set name goes into that table or is marked internal; `SettingsNamesTest` checks both
  (CONTRIBUTING.md, "Making a change").
- Comments and docs in an explanatory style: say why, give an example, link the concept.
- A check only a person can do gets a card in `docs/MANUAL_CHECKS.md` (with its source files),
  in the same commit; keep that file free of anything personal (`ManualChecksTest` checks).
- Add or update tests with every behavior change; update README and `docs/` when behavior changes,
  and add a user-visible change to `CHANGELOG.md` under *Unreleased*.
- The repository is public: docs, comments and commit messages say how the code works and why,
  never how it was discussed. No "decided with the maintainer", numbered questions, plan status,
  phases, dates of decisions, "review fix" labels, or pointers to private notes, checklists,
  artifacts or chats (see `docs/design/README.md`, "What goes in a note"). Plans stay outside the
  repository until built.
- Nothing that reaches GitHub holds personal data: no real or account name, home path
  (`/home/<name>`, `/Users/<name>`, `C:\Users\<name>`), personal email address or machine name.
  This covers code, docs, commit messages and metadata, tags, release notes and assets, CI output,
  and every picture (screenshots, GIFs, galleries). Use placeholders such as `/home/you`,
  `C:\Users\you` and `pat@example.org`, and the GitHub noreply address. Before a commit, search
  the diff for home paths and open every new or changed image. Why: a pushed leak stays in the
  history; removing it means rewriting history, and tags of immutable releases cannot be moved.
- One commit per logical change. Do not push, and never to `main`, unless the maintainer asks.
- Branches (CONTRIBUTING.md, "Branches"): work on `fix/<name>` or `feature/<name>` cut from the
  current release branch (`release/<next version>`, e.g. `release/0.1.2`), merge it back into that
  branch; `main` only moves to a release branch at a release. There is no `develop` branch.
- Commit in UTC (`TZ=UTC git commit ...`; in PowerShell set `$env:TZ = 'UTC'` first), so commit
  dates do not depend on the machine's time zone.
