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
./mvnw verify                                   # everything: plug-in, all tests, update-site ZIP
./mvnw verify -DskipTests                       # build only
./mvnw test -f tests/unit/pom.xml               # fast unit tests, no Eclipse or Neovim
./mvnw test -f tests/unit/pom.xml -Pintegration # against a real Neovim (-Dnvim.path=... to choose)
```

`./mvnw verify` also runs the UI tests in `tests/io.github.jm6cnddxbwftaks.eclipseneovim.tests`. They
start an Eclipse workbench, which opens a window for a few seconds; on a headless Linux machine run
`xvfb-run -a ./mvnw verify`.

Avoid `clean` while a runtime Eclipse launched from the IDE is running: it deletes the plug-in's
`lib/*.jar`, which that Eclipse is using.

The installable archive is `releng/io.github.jm6cnddxbwftaks.eclipseneovim.updatesite/target/EclipseNeovim-<version>.zip`.

## Run it from Eclipse

See [docs/HOWTO_ECLIPSE.md](docs/HOWTO_ECLIPSE.md). In short: *File > Import > Existing Maven Projects*,
then open `plugins/.../META-INF/MANIFEST.MF` and click *Launch an Eclipse application*.

## Project layout

| Path | Contents |
|------|----------|
| `plugins/io.github.jm6cnddxbwftaks.eclipseneovim` | the plug-in |
| `tests/unit` | JUnit tests of `util` and `core`, without Eclipse |
| `tests/io.github.jm6cnddxbwftaks.eclipseneovim.tests` | workbench tests (a fragment of the plug-in) |
| `features/...feature` | the installable feature |
| `releng/...updatesite` | the p2 update site and its ZIP |
| `releng/eclipse-formatter.xml` | the Java formatter profile |
| `docs/` | learning guide, architecture, keys, packaging, platform notes |

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
  the Eclipse class a thin caller. Eclipse glue gets a test in the UI test fragment.
- Library versions live only in the root `pom.xml`. A new library needs a `dependencyManagement` entry,
  an `artifactItem` in the plug-in's `pom.xml`, a `Bundle-ClassPath` entry in `MANIFEST.MF`
  (`BundleClassPathConsistencyTest` checks them) and its license in `plugins/.../licenses/` and
  `about.html`.
- [docs/LEARNING.md](docs/LEARNING.md), part 4, has recipes: adding a preference, a Neovim event, an
  Eclipse-side `:` command.
- Update the documentation (README, `docs/`, Javadoc) when behavior changes.

## Style

- Format Java with `releng/eclipse-formatter.xml` (*Window > Preferences > Java > Code Style > Formatter
  > Import...*). It leaves comments alone; wrap them by hand at about 100 characters.
- [`.editorconfig`](.editorconfig) sets indentation and line endings for other files.
- Comments explain *why*, and name the Eclipse or Neovim concept involved, so that the code teaches
  how it works.
- Every source file starts with the SPDX header:

  ```java
  // SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
  // SPDX-License-Identifier: EPL-2.0
  ```

## Commits and pull requests

- One logical change per commit, with a message that says what changed and why.
- Pull requests use the template in [.github](.github): describe the change and how you tested it.
  The *Build* workflow must pass.

## License

By contributing you agree that your contribution is licensed under the
[Eclipse Public License 2.0](LICENSE), like the rest of the project.
