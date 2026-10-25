# Upgrading versions

How to move this project to a newer JDK, Eclipse release, library, build tool or Neovim. Each
version has **one source of truth**. A few files cannot read it (Tycho reads `MANIFEST.MF` and
`build.properties` as they are; GitHub reads the workflows), so they keep a literal copy, and
`VersionConsistencyTest` fails the build when a copy disagrees.

The safe loop for any upgrade:

1. Change the source of truth (table below).
2. Run the consistency tests; they list every copy you missed:
   ```bash
   ./mvnw test -f tests/unit/pom.xml -Dtest='VersionConsistencyTest,BundleClassPathConsistencyTest'
   ```
3. Run everything:
   ```bash
   ./mvnw verify -Pui -Dnvim.path=/usr/bin/nvim                               # plug-in, all tests incl. UI, ZIP
   ./mvnw test -f tests/unit/pom.xml -Pintegration -Dnvim.path=/usr/bin/nvim  # against a real Neovim
   ```
4. Install the ZIP in a real Eclipse and try it (the tests cannot cover every editor); then commit
   one upgrade per commit, so that a bad one can be reverted alone.

## Where each version lives

| What | Source of truth | Copies (checked by `VersionConsistencyTest` unless noted) |
|------|-----------------|-----------------------------------------------------------|
| Java release (minimum JRE) | `<java.release>` in the root `pom.xml` | `.java-version`; `Bundle-RequiredExecutionEnvironment` in both `MANIFEST.MF`; `jre.compilation.profile` in the plug-in's `build.properties`; "JDK/Java/JavaSE-N" in README, CONTRIBUTING, docs/HOWTO_ECLIPSE, docs/LEARNING |
| Eclipse release (oldest supported) | `<eclipse-repo.url>` in the root `pom.xml` | release name (`2024-12`) in the same docs. Not checked: the `(4.34)` number next to it, the jar versions for `tests/text` and `tests/swt` (the `<eclipse.*.version>` properties), and the 4.34 `platform-url` of the smoke test in `update-site.yml` |
| Eclipse release (newest tested) | the second `platform-url` (4.41) in the `smoke` job of `.github/workflows/update-site.yml` | not checked; see "Eclipse release", *Newest release* |
| Embedded library (msgpack-core) | `<msgpack.version>` in the root `pom.xml` | none; `BundleClassPathConsistencyTest` checks the jar list. By hand: `plugins/.../licenses/` |
| JUnit | `<junit.version>` in the root `pom.xml` | none |
| Tycho | `<tycho.version>` in the root `pom.xml` | `tycho-gpg-plugin`'s `<version>` in `releng/sign/pom.xml`, which has no parent (it signs releases without loading the target platform) |
| JaCoCo (coverage) | `<jacoco.version>` in the root `pom.xml` | none; used by the coverage agent and the UI tests' Ant report |
| Maven plug-ins | `<maven-*-plugin.version>` properties in the root `pom.xml` | none; child POMs may not pin one (checked) |
| Maven itself | `distributionUrl` in `.mvn/wrapper/maven-wrapper.properties` | none |
| Neovim (oldest supported, and the one CI tests) | `NeovimVersion.MINIMUM` in the plug-in's `util/NeovimVersion.java` | `NVIM_VERSION` in `.github/workflows/build.yml`; "Neovim 0.12.6" in README, CONTRIBUTING and `feature.xml`, and the update site's `category.xml`. Its download names are per OS (below) |
| GitHub Actions | `uses: ...@vN` lines in `.github/workflows/` | not checked; Dependabot proposes updates |
| CI operating systems | `runs-on: ubuntu-latest` in the workflows; the `platforms` job's matrix in `build.yml` adds `windows-latest` and `macos-latest` | not checked |

Dependabot (`.github/dependabot.yml`) opens weekly pull requests for the Maven properties (not for
major library versions) and the GitHub Actions. It cannot see the JDK, the Eclipse release or
Neovim; those are upgraded by hand with the steps below. It also skips, on purpose:

- the `org.eclipse.platform` jars: they must match the Eclipse release, so they move with it;
- the Maven wrapper (Maven itself and the wrapper scripts): Dependabot would update it by running
  `./mvnw wrapper:wrapper` on a copy of the repository without the `MANIFEST.MF` files, which
  Tycho needs before it runs any goal, so every such job fails. Check for a new Maven 3.9 release
  now and then, or with other upgrades, and follow "Maven" below.

The embedded msgpack-core comes as an ordinary pull request of its own; it has no partner
libraries to keep in step since the plug-in's own RPC client replaced neovim-java and Jackson
(`docs/design/RPC_CLIENT.md`).

Its pull requests target `main`, the default branch. Rather than merging them there, apply an
accepted update on the current release branch (`release/0.1.2`, see CONTRIBUTING.md, "Branches")
as its own commit (with the checks below); Dependabot closes its pull request by itself once
`main` has the new version, at the next release. GitHub reads `dependabot.yml` from `main` only,
so a change to it takes effect at the next release too.

## JDK / Java release

The Java release is the **minimum** Java the plug-in runs on, not the JDK you build with. Building
with a newer JDK is fine: `--release 21` produces Java 21 class files on JDK 25 too.

To raise it (example: 21 to 25):

1. Check that the oldest supported Eclipse (`eclipse-repo.url`) runs on that Java and knows the
   execution environment `JavaSE-25`. If not, raise the Eclipse release first (next section).
2. Check that the Tycho version supports building for it (Tycho release notes).
3. Set `<java.release>25</java.release>` in the root `pom.xml`, and `25` in `.java-version`.
4. Update `Bundle-RequiredExecutionEnvironment: JavaSE-25` in
   `plugins/io.github.jm6cnddxbwftaks.neovim4eclipse/META-INF/MANIFEST.MF` and
   `tests/io.github.jm6cnddxbwftaks.neovim4eclipse.tests/META-INF/MANIFEST.MF`, and
   `jre.compilation.profile = JavaSE-25` in the plug-in's `build.properties`.
5. Update the docs; the test prints each `file:line` that still names the old version.
6. In the IDE: register a JDK 25 under *Preferences > Java > Installed JREs > Execution
   Environments*, then *Maven > Update Project*.

Effect on users: an Eclipse running on an older Java refuses to install the plug-in ("missing
requirement: osgi.ee"). Say so in the release notes.

## Eclipse release

`eclipse-repo.url` is the p2 site the plug-in is compiled and tested against, so it is the **oldest**
release that is known to work. Newer Eclipse releases normally keep these APIs.

To raise it (example: 2024-12 to 2025-12):

1. Set `<eclipse-repo.url>https://download.eclipse.org/releases/2025-12</eclipse-repo.url>`.
2. Update the release name and its `4.x` number in README, CONTRIBUTING and the feature's
   description in `feature.xml` (each quarterly
   release adds 1 to the `4.x` number; check <https://projects.eclipse.org/projects/eclipse>).
3. Check the Java that release needs; raise the Java release with it if required.
4. Set the `<eclipse.*.version>` properties next to `<eclipse.text.version>` (text, equinox.common,
   core.commands, swt, jface, jface.text, core.runtime, osgi) to the versions in the new release.
   `tests/text` and `tests/swt` run against these jars from Maven Central (not the p2 site), so they
   should be the ones the plug-in is built with. SWT is published once per platform
   (`org.eclipse.swt.gtk.linux.x86_64`, ...); one version property covers all of them. To find them: after a build, look in
   `~/.m2/repository/p2/osgi/bundle/org.eclipse.text/` (the folder name is the version Tycho
   resolved, for example `3.14.200.v20240915-0736` means `3.14.200`; the same for `org.eclipse.swt`
   and the others), or open the release's `plugins/` folder on download.eclipse.org. Nothing checks
   this automatically: a mismatch only means these tests run against slightly different jars.
5. Set the 4.34 entry of the `smoke` job's matrix in `.github/workflows/update-site.yml` (`eclipse`
   and `platform-url`) to that release's Eclipse Platform download for Linux x86_64 (from
   <https://download.eclipse.org/eclipse/downloads/>): the update site's smoke test installs into it,
   so it must be the oldest supported release too.
6. Run everything, then try the ZIP in that Eclipse and in the newest one.

**Newest release.** The smoke test's second entry is the newest Eclipse release, so a release of the
plug-in is also installed into the top of the range the Marketplace listing ticks. Move it when a
new Eclipse ships (every March, June, September and December), as its own commit:

1. Find the new drop in <https://download.eclipse.org/eclipse/downloads/data.json>: the first entry
   of `"releases"`, for example `drops4/R-4.41-202608281142`. The download is
   `<that path>/eclipse-platform-4.41-linux-gtk-x86_64.tar.gz`.
2. Set `eclipse` and `platform-url` of the second matrix entry. Check that release's `eclipse.ini`
   still accepts the Java in `.java-version` (`-Dosgi.requiredJavaVersion`).
3. Try it locally before the next release, with `releng/smoke-install.sh` against the live site
   (docs/PACKAGING.md), then tick the release in the Marketplace listing.

It is pinned rather than looked up at run time, so a release of the plug-in never fails just
because Eclipse shipped that day.

`Require-Bundle` in `MANIFEST.MF` has no version ranges, so nothing else stops an install on an
Eclipse older than the target; the target release is the promise.

## Embedded library (msgpack-core)

The plug-in embeds one library, msgpack-core, under its own RPC client in `core/rpc`. The jars are copied into `plugins/.../lib/` by the plug-in's `pom.xml` and redistributed inside the
plug-in, so their license files ship with it.

To change a version:

1. Change its property in the root `pom.xml`. The jar names in `lib/` carry no version, so
   `MANIFEST.MF` does not change.
2. Compare the jar's own license files with the copies in `plugins/.../licenses/`; a new version can
   add or change a `NOTICE`, which must then be updated. For example:
   ```bash
   unzip -l ~/.m2/repository/org/msgpack/msgpack-core/0.9.12/msgpack-core-0.9.12.jar | grep -i -E 'notice|license'
   ```
   msgpack-core (0.9.3 to 0.9.12 at least) carries none in its jar: the `NOTICE` in `licenses/` comes from the
   [msgpack-java](https://github.com/msgpack/msgpack-java) repository, so compare with the one
   there at the new version's tag.
3. Check for new transitive dependencies, which are **not** copied automatically:
   ```bash
   ./mvnw dependency:tree -f tests/unit/pom.xml
   ```
4. Read the release notes for changes to the API `core/rpc/MsgpackValues` and `RpcChannel` use
   (`MessagePacker`, `MessageUnpacker`, the `MessagePackException`s). A major version changes APIs
   and needs code changes; Dependabot skips those on purpose.
5. Run everything, integration tests included (they exercise the RPC client against a real
   Neovim): `MsgpackValuesTest` pins the bytes on the wire, `RpcChannelTest` the protocol.

To add a library: add it to the root `dependencyManagement`, to `artifactItems` in the plug-in's
`pom.xml`, to `Bundle-ClassPath` in `MANIFEST.MF`, its license to `licenses/` and `about.html`, and
a row to `ACKNOWLEDGEMENTS.md`. `BundleClassPathConsistencyTest` checks the first three agree.

Avoid `./mvnw clean` while a runtime Eclipse started from the IDE is running: it deletes
`lib/*.jar`, which that Eclipse has open (on Windows the files are locked).

## Build tools

- **Tycho**: change `<tycho.version>`, and the same version in `releng/sign/pom.xml`. Read the
  release notes for removed parameters; Tycho also sets which Java the build itself needs. A new
  Tycho may change how `tycho-gpg-plugin` finds the key (see the comments in
  `releng/sign/pom.xml`), so test signing with a throwaway key before the next release:
  `releng/sign.sh` has the commands at its top.
- **Maven plug-ins**: change the matching `<maven-...-plugin.version>` property in the root
  `pom.xml` only.
- **Maven**: `./mvnw -N wrapper:wrapper -Dmaven=3.9.16` rewrites
  `.mvn/wrapper/maven-wrapper.properties` (use the version you want). Stay on the 3.9 line until
  Tycho supports Maven 4 releases.
- **JaCoCo**: change `<jacoco.version>`. A JaCoCo release supports class files only up to the Java
  version it knows: when the build JDK gets newer than that, the agent fails while the tests start
  ("Unsupported class file major version"), so upgrade JaCoCo together with the JDK.
- **JUnit**: change `<junit.version>`. The UI tests use the JUnit from the Eclipse target instead.

## Neovim

One version is both the **oldest supported** Neovim and the one CI tests: `NeovimVersion.MINIMUM`.
The plug-in refuses an older Neovim at start, with a message naming both versions
(`Neovim 0.12.4 is too old: Neovim for Eclipse needs 0.12.6 or newer.`), so users never meet a
half-working plug-in.

**When to raise it** *(decided 2026-10-25)*: readily, whenever a new release

- lets Neovim do something the plug-in would otherwise work around (a new API, option, event or
  default; for example `vim.diagnostic.jump`'s `on_jump` instead of a `CursorMoved` check), or
- fixes a security issue.

The project's goal is that Neovim handles as much as possible itself; supporting old versions
would mean keeping the workarounds. Users are expected to have a Neovim of their own for Eclipse
when their system's is older: the release archive extracted anywhere, its `bin/nvim` set in the
preferences (README, *Installation*). So a newer minimum costs them a download, not a system
upgrade.

**How** (example: 0.12.6 to 0.13.0):

1. Read the release notes (`:help news`, and each patch release's changelog on GitHub) for changes
   to what the plug-in uses: the API calls in `core/rpc`, the Lua in `LuaScripts`, `ext_cmdline`,
   autocommand events, options it sets. Note what can now be done by Neovim instead of the plug-in.
2. Set `MINIMUM = new NeovimVersion(0, 13, 0)` in `util/NeovimVersion.java`, `NVIM_VERSION: v0.13.0`
   in `build.yml`, and the version in README, CONTRIBUTING, `feature.xml` and `category.xml`;
   `VersionConsistencyTest` lists any copy you missed.
3. Install that Neovim locally (the release archive is enough) and run everything with it:
   `-Dnvim.path=/path/to/nvim-0.13.0/bin/nvim`.
4. Add a *Changed* entry to `CHANGELOG.md` saying the new minimum, so users see it before updating.
5. Remove the workarounds the new version makes unnecessary, each as its own commit after this one.

**Downloads in CI**: the Linux jobs download `nvim-linux-x86_64.tar.gz` from the GitHub release,
the `platforms` job `nvim-win64.zip` (Windows) and `nvim-macos-arm64.tar.gz` (macOS, Apple
silicon); if a release renames one, update its download and `bin/nvim` path there too (the
`nvim-archive` and `nvim` matrix values).

## GitHub Actions and the runner

- Actions are pinned to a major version (`actions/checkout@v7`). Dependabot proposes new majors;
  read their release notes (for example `upload-artifact`'s `archive: false` needs v7).
- `ubuntu-latest` moves to a new Ubuntu now and then (to Ubuntu 26 from October 19, 2026). If a run
  breaks after such a move, pin `runs-on: ubuntu-24.04` until it is fixed.
