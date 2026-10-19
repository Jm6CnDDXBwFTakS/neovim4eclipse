# Packaging and installation

## Automated build (Maven / Tycho)

From the repository root:

```bash
# Build plug-in, feature, p2 repository, and run unit tests
./mvnw clean verify
```

Artifacts:

| Path | Content |
|------|---------|
| `plugins/.../target/*.jar` | OSGi plug-in jar |
| `features/.../target/*.jar` | Feature jar |
| `releng/.../target/repository/` | p2 update site (installable) |
| `releng/.../target/EclipseNeovim-<version>.zip` | Same update site as one archive (installable; attach to GitHub releases) |

### Install from the local update site

1. *Help → Install New Software… → Add…*, then either
   - *Archive…* and select `releng/io.github.jm6cnddxbwftaks.eclipseneovim.updatesite/target/EclipseNeovim-<version>.zip`, or
   - *Local…* and select `releng/io.github.jm6cnddxbwftaks.eclipseneovim.updatesite/target/repository`
2. Choose **Eclipse Neovim**, finish, restart (the bundle is unsigned; accept the warning)

### Unit tests only

```bash
./mvnw test -f tests/unit/pom.xml
# Integration tests (real nvim):
./mvnw test -f tests/unit/pom.xml -Pintegration -Dnvim.path=/usr/bin/nvim
```

`tests/unit` is a plain Maven jar module: it compiles `util/` and `core/` from the plug-in
sources and runs JUnit 5. It does **not** need Eclipse or SWT.

### UI tests

`tests/io.github.jm6cnddxbwftaks.eclipseneovim.tests` runs inside a real Eclipse workbench as part of
`./mvnw verify`. It needs a display (`xvfb-run -a ./mvnw verify` on a headless machine) and, for its
end-to-end tests, `nvim`. Because Maven cannot see that this fragment depends on the plug-in (the
dependency is in `MANIFEST.MF`), build it with the whole reactor rather than with `-pl ... -am`;
otherwise Tycho may test against a stale copy of the plug-in in `~/.m2`. The plug-in exports its
packages only to this fragment (`x-friends` in its `MANIFEST.MF`).

On macOS the test Eclipse must run its UI on the process's first thread: the fragment's
`macos-first-thread` profile (active on macOS only) passes `-XstartOnFirstThread`, which an installed
Eclipse gets from its `eclipse.ini`. Without it the run stops at once with
`SWTException: Invalid thread access` (exit code 13).

Tests of code that uses only the text model (`IDocument`) are not here but in `tests/text`, a plain
Maven module that compiles those classes against the `org.eclipse.text` jar from Maven Central and
runs without a workbench. Widget tests that need SWT but not the workbench are in `tests/swt`, which
does the same with the SWT jar for the build machine's OS and processor (a profile picks it) and
needs a display.

## PDE (optional, for interactive development)

You can still import the projects into Eclipse PDE and launch from `MANIFEST.MF`
as described in [HOWTO_ECLIPSE.md](HOWTO_ECLIPSE.md). Tycho and PDE share the same
`MANIFEST.MF`, `plugin.xml`, `feature.xml`, and `category.xml`.

## Runtime requirement

`nvim` must be on the `PATH` of the Eclipse process, or an absolute path set in
*Preferences → Neovim*.
