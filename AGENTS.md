# Notes for AI coding assistants

Context for AI tools working on this repository (Claude Code reads it through `CLAUDE.md`). Humans
are welcome to read it too; [CONTRIBUTING.md](CONTRIBUTING.md) is the full guide.

## The project

An Eclipse plug-in that embeds a real Neovim (`nvim --embed`) in Eclipse text editors. Built with
Maven and Tycho into a p2 update site ZIP. It is also a learning project: code and docs explain
Eclipse plug-in and Neovim API concepts, and must stay maintainable by humans for the long term.

Read first: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) (design), [docs/LEARNING.md](docs/LEARNING.md)
(concepts), [docs/KEYS.md](docs/KEYS.md) (key routing).

## Commands

```bash
./mvnw verify -Pui -Dnvim.path=/usr/bin/nvim      # everything: plug-in, all tests incl. UI, feature, ZIP
./mvnw verify                                     # the same without the UI tests (no Eclipse window)
./mvnw verify -DskipTests                         # build only
./mvnw test -f tests/unit/pom.xml                 # unit tests (no Eclipse, no Neovim)
./mvnw test -f tests/text/pom.xml                 # text-model tests (IDocument; no workbench, no display)
./mvnw test -f tests/swt/pom.xml                  # SWT widget tests (a display, no workbench)
./mvnw test -f tests/unit/pom.xml -Pintegration -Dnvim.path=/usr/bin/nvim   # real Neovim
xvfb-run -a ./mvnw verify -Pui                    # on a machine without a display
```

The UI tests need `-Pui` (off by default because they open a window); run them after changing
editor or session code. Each test module writes a JaCoCo coverage report to `target/site/jacoco/index.html` (see
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
- Neovim columns are UTF-8 bytes, Eclipse columns UTF-16 chars; convert with `Utf8Columns` /
  `DocumentPositions`.
- `StyledText` offsets are not document offsets when code is folded; convert with `WidgetOffsets`.
- `util/` and `core/` must not import `org.eclipse.*` (they are compiled and unit-tested without
  Eclipse). `sync/` and the `editor/` classes listed in `tests/text/pom.xml` may use only the text
  model (`org.eclipse.jface.text` types from the `org.eclipse.text` jar), no SWT or workbench. The
  `editor/` classes listed in `tests/swt/pom.xml` may use SWT and JFace, not the workbench.
- Put each test in the cheapest module that can run it: `tests/unit`, then `tests/text`, then
  `tests/swt`, then the UI tests (see CONTRIBUTING.md, "Which module a test belongs in").
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
- The same goes for a changed `static final String` constant (such as a `LuaScripts` script): javac
  copies its text into every class that uses it, so those classes keep the old text until they are
  recompiled. Delete `plugins/.../target/classes` after changing one.
- Synthetic SWT key events in UI tests need `event.doit = true`.

## Conventions

- License EPL-2.0. Every source file starts with the two-line SPDX header
  (`SPDX-FileCopyrightText` and `SPDX-License-Identifier: EPL-2.0`); Markdown files have none.
- American spelling (behavior, color, license).
- Comments and docs in an explanatory style: say why, give an example, link the concept.
- Add or update tests with every behavior change; update README and `docs/` when behavior changes.
- One commit per logical change. Do not push, and never to `main`, unless the maintainer asks.
- Commit in UTC (`TZ=UTC git commit ...`; in PowerShell set `$env:TZ = 'UTC'` first), so commit
  dates do not depend on the machine's time zone.
