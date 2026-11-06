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
| Eclipse release (oldest supported) | `<eclipse-repo.url>` in the root `pom.xml` | release name (`2024-12`) in the same docs. Not checked: the `(4.34)` number next to it, the jar versions for `tests/text` and `tests/swt` (the `<eclipse.*.version>` properties), and the 4.34 `platform-url` and `platform-sha512` of the smoke test in `update-site.yml` |
| Eclipse release (newest tested) | the second `platform-url` (4.41) in the `smoke` job of `.github/workflows/update-site.yml` | not checked; see "Eclipse release", *Newest release* |
| Embedded library (msgpack-core) | `<msgpack.version>` in the root `pom.xml` | none; `BundleClassPathConsistencyTest` checks the jar list. By hand: `plugins/.../licenses/` |
| JUnit | `<junit.version>` in the root `pom.xml` | none |
| Tycho | `<tycho.version>` in the root `pom.xml` | `tycho-gpg-plugin`'s `<version>` in `releng/sign/pom.xml`, which has no parent (it signs releases without loading the target platform) |
| JaCoCo (coverage) | `<jacoco.version>` in the root `pom.xml` | none; used by the coverage agent and the UI tests' Ant report |
| Maven plug-ins | `<maven-*-plugin.version>` properties in the root `pom.xml` | none; child POMs may not pin one (checked) |
| Maven itself | `distributionUrl` and `distributionSha256Sum` in `.mvn/wrapper/maven-wrapper.properties` | none |
| Neovim (what *Get Neovim* installs, the oldest supported, and the one CI tests) | `version=` in the plug-in's `util/neovim-downloads.properties` (written by `releng/UpdateNeovimDownloads.java`; *Get Neovim* and CI both install from it; `NeovimVersion.MINIMUM` reads it) | the copies `releng/version-copies.txt` lists, section `[neovim]` (README, CONTRIBUTING, LEARNING, PACKAGING, `feature.xml`, the update site's `category.xml`); `java releng/tools/neovim/NeovimUpgrade.java` rewrites them |
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
accepted update on the current release branch (`release/<next version>`, see CONTRIBUTING.md, "Branches")
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
5. Set the 4.34 entry of the `smoke` job's matrix in `.github/workflows/update-site.yml` (`eclipse`,
   `platform-url` and `platform-sha512`) to that release's Eclipse Platform download for Linux x86_64
   (from <https://download.eclipse.org/eclipse/downloads/>): the update site's smoke test installs
   into it, so it must be the oldest supported release too. The SHA-512 is the file's line in the
   drop's `eclipse-<version>-checksums` (older drops: `checksum/eclipse-<version>-SUMSSHA512`). Set
   the same three in `.github/workflows/rehearse-release.yml` (`RehearsalTest` checks they agree).
6. Run everything, then try the ZIP in that Eclipse and in the newest one.

**Newest release.** The smoke test's second entry is the newest Eclipse release, so a release of the
plug-in is also installed into the top of the range the Marketplace listing ticks. Move it when a
new Eclipse ships (every March, June, September and December), as its own commit:

1. Find the new drop in <https://download.eclipse.org/eclipse/downloads/data.json>: the first entry
   of `"releases"`, for example `drops4/R-4.41-202608281142`. The download is
   `<that path>/eclipse-platform-4.41-linux-gtk-x86_64.tar.gz`.
2. Set `eclipse`, `platform-url` and `platform-sha512` of the second matrix entry, in
   `update-site.yml` and `rehearse-release.yml`. The SHA-512 is the download's line in
   `<that path>/eclipse-4.41-checksums`, for example
   `curl -sSfL https://download.eclipse.org/eclipse/downloads/drops4/R-4.41-202608281142/eclipse-4.41-checksums | grep platform-4.41-linux-gtk-x86_64`.
   Check that release's `eclipse.ini`
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
  Tycho supports Maven 4 releases. Then add back `distributionSha256Sum=` with the new archive's
  SHA-256, which `./mvnw` checks before it runs the downloaded Maven (the signing job runs it with
  the PGP key at hand): download the archive, check it against Maven Central's `.sha512` file
  next to it, and take `sha256sum` of it. Example for 3.9.16:
  `curl -fsSLO https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.16/apache-maven-3.9.16-bin.zip`.
- **JaCoCo**: change `<jacoco.version>`. A JaCoCo release supports class files only up to the Java
  version it knows: when the build JDK gets newer than that, the agent fails while the tests start
  ("Unsupported class file major version"), so upgrade JaCoCo together with the JDK.
- **JUnit**: change `<junit.version>`. The UI tests use the JUnit from the Eclipse target instead.

## Neovim

One version is what *Get Neovim* installs, the **oldest supported** Neovim, and the one CI tests:
`version=` in `util/neovim-downloads.properties`, which `NeovimVersion.MINIMUM` reads.
The plug-in refuses an older Neovim at start, with a message naming both versions
(`Neovim 0.12.4 is too old: Neovim for Eclipse needs 0.12.6 or newer.`), so users never meet a
half-working plug-in.

**When to raise it**: readily, whenever a new release

- lets Neovim do something the plug-in would otherwise work around (a new API, option, event or
  default; for example `vim.diagnostic.jump`'s `on_jump` instead of a `CursorMoved` check), or
- fixes a security issue.

The project's goal is that Neovim handles as much as possible itself; supporting old versions
would mean keeping the workarounds. Users need no Neovim of their own: the plug-in
runs only the dedicated Neovim that *Get Neovim* installs, this one version, hash-checked before
every start (README, *Finding Neovim*). So a new version costs them one *Get Neovim* when they
update the plug-in: the dedicated Neovim of the old version fails the check, and the plug-in
opens *Get Neovim* by itself at Eclipse's start. (A program named with the startup option
`-Dneovim4eclipse.nvim` instead runs unchecked and unsupported; one older than the minimum is
refused, as above.)

**How** (example: 0.12.6 to 0.13.0), on the release branch:

```bash
java releng/tools/neovim/NeovimUpgrade.java 0.13.0
```

It asks once, then does each step and commits *Move to Neovim 0.13.0*; if a step fails, the tree is
put back as it was. It needs a JDK of release 22 or newer, as the Release tool does
(CONTRIBUTING.md, "Releasing"); `--dry-run` shows the steps, `--yes` skips the question.

1. **The data file**: `java releng/UpdateNeovimDownloads.java 0.13.0` rewrites
   `util/neovim-downloads.properties` (asset names, sizes, SHA-256 and tree hashes of the six release
   archives), after downloading each archive and comparing its hash with GitHub's published digest;
   it writes nothing if a step fails. It refuses a release less than a week old, so that an archive
   replaced on GitHub would likely have been noticed first (`--allow-young` after the version, for
   an urgent fix), and prints each archive's VirusTotal page: the tool asks whether you looked. If
   Neovim renamed an asset, the updater stops with a 404: fix that `.asset` line by hand and run the
   tool again. `NeovimVersion.MINIMUM` reads the file, so it moves too.
2. **The copies** of the number that `releng/version-copies.txt` lists (README, CONTRIBUTING,
   LEARNING, PACKAGING, `feature.xml`, `category.xml`). `VersionConsistencyTest` checks that every
   listed pattern still finds the version: a reworded sentence needs its pattern changed with it.
3. **The changelog**: a *Changed* entry under *Unreleased* naming the new minimum, so users see it
   before updating.
4. **The archives as *Get Neovim* installs them**: the plug-in is compiled, and all six archives
   are unpacked with its own extractor (`util/ArchiveExtractor`, whose tar reader refuses links,
   which Neovim's archives have not had so far) and checked against their tree hashes; then this
   machine's Neovim must run. This replaces running *Get Neovim* once in a development Eclipse.
5. **Every test** with the new Neovim: `java releng/tools/ci/LikeCi.java --all`. Run it on macOS and
   Windows too.

What stays yours: read the release notes (`:help news`, and each patch release's changelog on
GitHub) for changes to what the plug-in uses (the API calls in `core/rpc`, the Lua in `LuaScripts`,
`ext_cmdline`, autocommand events, options it sets), then remove the workarounds the new version
makes unnecessary, each as its own commit after the move.

By hand, the same: run the updater, change each copy `releng/version-copies.txt` lists, add the
changelog entry, run the tests with `-Dnvim.path=/path/to/nvim-0.13.0/bin/nvim` and *Get Neovim*
once in a development Eclipse, and commit.

**Following Neovim's releases**: the plug-in runs only the Neovim it pins, so its users get a
Neovim fix, a security fix included, only when the pin moves. Neovim ships those fixes as releases,
patch releases (0.12.x) included, and *Get Neovim* installs only a published release, checked
against its hash; a fix that waits on one of Neovim's branches is out of the plug-in's reach until
Neovim releases it. So there is no separate security check: follow each Neovim release about a week
after it appears (the updater's own wait, above), moving the pin and, where the release calls for
it, the minimum, with the steps above. For a release that fixes something urgent, `--allow-young`
moves sooner.

Where the plug-in can guard against a known issue in the pinned Neovim, it does so in
`LuaScripts.SETUP` until a release fixes it. Example: Neovim 0.12.6 runs a crafted R Markdown chunk
header's backticks in a shell (Vim 9.2.1178, GHSA-x2w5-pj55-7hpc; fixed on master, not in
release-0.12 when 0.12.6 was pinned), so SETUP turns `g:rmd_dynamic_fenced_languages` off; remove
that guard, and the `setup_spec.lua` test for it, once the pinned Neovim has the fix.

**Downloads in CI**: every job installs Neovim with
`java releng/UpdateNeovimDownloads.java install auto <folder>`, which takes the archive for the
runner's platform from the data file and checks its size, SHA-256 and tree hash. So CI needs no change of
its own; the release workflow also runs `java releng/UpdateNeovimDownloads.java --check`, which
compares all six archives with the file before anything is published.

## GitHub Actions and the runner

- Actions are pinned to a full commit SHA, with the release it is in as a comment:
  `actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1`. A tag such as `v7` can be
  moved by whoever controls the action's repository, a commit SHA cannot; the `sign` job, which
  holds the PGP key, runs these actions, so a moved tag would run someone else's code next to the
  key. Dependabot (`github-actions` in `.github/dependabot.yml`) proposes new SHAs and updates the
  comment; read their release notes (for example `upload-artifact`'s `archive: false` needs v7). To
  pin a new action by hand: `gh api repos/actions/checkout/commits/v7 --jq .sha`.
- `ubuntu-latest` moves to a new Ubuntu now and then (to Ubuntu 26 from October 19, 2026). If a run
  breaks after such a move, pin `runs-on: ubuntu-24.04` until it is fixed.
