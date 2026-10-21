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
| Eclipse release (oldest supported) | `<eclipse-repo.url>` in the root `pom.xml` | release name (`2024-12`) in the same docs. Not checked: the `(4.34)` number next to it, and the jar versions for `tests/text` and `tests/swt` (the `<eclipse.*.version>` properties) |
| Embedded library (msgpack-core) | `<msgpack.version>` in the root `pom.xml` | none; `BundleClassPathConsistencyTest` checks the jar list. By hand: `plugins/.../licenses/` |
| JUnit | `<junit.version>` in the root `pom.xml` | none |
| Tycho | `<tycho.version>` in the root `pom.xml` | none |
| JaCoCo (coverage) | `<jacoco.version>` in the root `pom.xml` | none; used by the coverage agent and the UI tests' Ant report |
| Maven plug-ins | `<maven-*-plugin.version>` properties in the root `pom.xml` | none; child POMs may not pin one (checked) |
| Maven itself | `distributionUrl` in `.mvn/wrapper/maven-wrapper.properties` | none |
| Neovim used by CI | `NVIM_VERSION` in `.github/workflows/build.yml` | not checked |
| Oldest supported Neovim | "Neovim 0.9" in README and CONTRIBUTING | not checked; see below |
| GitHub Actions | `uses: ...@vN` lines in `.github/workflows/` | not checked; Dependabot proposes updates |
| CI operating system | `runs-on: ubuntu-latest` in the workflows | not checked |

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
(`docs/RPC_CLIENT.md`).

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
2. Update the release name and its `4.x` number in README and CONTRIBUTING (each quarterly
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
5. Run everything, then try the ZIP in that Eclipse and in the newest one.

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

- **Tycho**: change `<tycho.version>`. Read the release notes for removed parameters; Tycho also
  sets which Java the build itself needs.
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

- **CI**: change `NVIM_VERSION` in `.github/workflows/build.yml`. Both jobs download
  `nvim-linux-x86_64.tar.gz` from the GitHub release; if a release renames that file, update the
  download and the `bin/nvim` paths there too.
- **Oldest supported version**: README and CONTRIBUTING say 0.9, because the status bar's pending
  command needs `showcmdloc` (0.9). Raise it when the code starts using a newer API (for example a
  Lua function from `vim.*` or an `nvim_*` call added later); run the integration tests against
  that oldest version before lowering it.

## GitHub Actions and the runner

- Actions are pinned to a major version (`actions/checkout@v7`). Dependabot proposes new majors;
  read their release notes (for example `upload-artifact`'s `archive: false` needs v7).
- `ubuntu-latest` moves to a new Ubuntu now and then (to Ubuntu 26 from October 19, 2026). If a run
  breaks after such a move, pin `runs-on: ubuntu-24.04` until it is fixed.
