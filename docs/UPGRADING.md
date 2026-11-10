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
| Eclipse release (oldest supported) | `<eclipse-repo.url>` in the root `pom.xml` | the release name (`2024-12`) and its number (`4.34`) where `releng/version-copies.txt` lists them, sections `[eclipse.oldest]` and `[eclipse.oldest.number]` (docs, `feature.xml` with its `org.eclipse.platform` import, workflow comments); the first entry of the smoke job in `update-site.yml` and `rehearse-release.yml` (checked: its number and that its download is that release's). Not checked: the jar versions for `tests/text` and `tests/swt` (the `<eclipse.*.version>` properties). `java releng/tools/eclipse/EclipseUpgrade.java oldest <release>` moves all of them |
| Eclipse release (newest tested) | the second entry (`eclipse: '4.41'`, its `platform-url` and `platform-sha512`) of the `smoke` job in `.github/workflows/update-site.yml` | the same entry in `rehearse-release.yml`; the README's "tested up to 2026-09 (4.41)" and the copies in sections `[eclipse.newest]` and `[eclipse.newest.number]`. `java releng/tools/eclipse/EclipseUpgrade.java newest` moves them |
| Embedded library (msgpack-core) | `<msgpack.version>` in the root `pom.xml` | none; `BundleClassPathConsistencyTest` checks the jar list. By hand: `plugins/.../licenses/` |
| JUnit | `<junit.version>` in the root `pom.xml` | none |
| Tycho | `<tycho.version>` in the root `pom.xml` | `tycho-gpg-plugin`'s `<version>` in `releng/sign/pom.xml`, which has no parent (it signs releases without loading the target platform) |
| JaCoCo (coverage) | `<jacoco.version>` in the root `pom.xml` | none; used by the coverage agent and the UI tests' Ant report |
| Maven plug-ins | `<maven-*-plugin.version>` properties in the root `pom.xml` | none; child POMs may not pin one (checked) |
| Maven itself, and the Maven wrapper | `distributionUrl` and `distributionSha256Sum` (Maven), `wrapperVersion` (the scripts `mvnw` and `mvnw.cmd`) in `.mvn/wrapper/maven-wrapper.properties`; the line Maven stays on is `maven.line` in `releng/tools/maven/settings.properties` | none; `java releng/tools/maven/MavenUpgrade.java` moves both |
| Neovim (what *Get Neovim* installs, the oldest supported, and the one CI tests) | `version=` in the plug-in's `util/neovim-downloads.properties` (written by `releng/UpdateNeovimDownloads.java`; *Get Neovim* and CI both install from it; `NeovimVersion.MINIMUM` reads it) | the copies `releng/version-copies.txt` lists, section `[neovim]` (README, CONTRIBUTING, LEARNING, PACKAGING, `feature.xml`, the update site's `category.xml`); `java releng/tools/neovim/NeovimUpgrade.java` rewrites them |
| GitHub Actions | `uses: ...@vN` lines in `.github/workflows/` | not checked; Dependabot proposes updates |
| CI operating systems | `runs-on: ubuntu-latest` in the workflows; the `platforms` job's matrix in `build.yml` adds `windows-latest` and `macos-latest` | not checked |

Dependabot (`.github/dependabot.yml`) opens weekly pull requests for the Maven properties (not for
major library versions) and the GitHub Actions. It cannot see the JDK, the Eclipse release or
Neovim; those are upgraded by hand with the steps below. It also skips, on purpose:

- the `org.eclipse.platform` jars: they must match the Eclipse release, so they move with it;
- the Maven wrapper (Maven itself and the wrapper scripts): Dependabot would update it by running
  `./mvnw wrapper:wrapper` on a copy of the repository without the `MANIFEST.MF` files, which
  Tycho needs before it runs any goal, so every such job fails. `MavenUpgrade.java` does it in
  the real checkout (*Build tools* below); `Release.java check` says when there is something to do.

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

The plug-in supports a range of Eclipse releases, and each end lives in one place:

- the **oldest supported**, `<eclipse-repo.url>` in the root `pom.xml`: the p2 site the plug-in is
  compiled and tested against, so the oldest release known to work. Newer releases normally keep
  the APIs it uses, which is the promise "2024-12 (4.34) or newer". Raise it only when an API, a
  Java requirement or a bug forces it.
- the **newest tested**, the second entry of the `smoke` job in
  `.github/workflows/update-site.yml`: every release of the plug-in is installed into it (and into
  the oldest, the first entry) right after it is published, so both ends of the range the
  Marketplace listing claims are tried. Move it with each Eclipse release, every March, June,
  September and December.

Both are written into the docs by name and by number, `2024-12 (4.34)`: four releases a year, each
adding 1 to the `4.x` number, so one gives the other (`common/EclipseRelease`).
`releng/version-copies.txt` lists those copies (sections `[eclipse.oldest]`, `[eclipse.newest]` and
their `.number` twins), and `VersionConsistencyTest` checks them.

**The tool** (a JDK of release 22 or newer runs it, as the Release tool):

```bash
java releng/tools/eclipse/EclipseUpgrade.java newest          # test with the newest Eclipse release
java releng/tools/eclipse/EclipseUpgrade.java oldest 2025-12  # raise the oldest supported release
java releng/tools/eclipse/EclipseUpgrade.java smoke           # install this checkout into both, on this machine
```

Each one asks before it commits (`--yes` skips the question, `--dry-run` only prints the plan),
and puts the working tree back if a step fails. Downloads are kept in
`target/eclipse-upgrade/downloads/` and checked against the drop's SHA-512 every time.

- **`newest`** reads the newest Platform release from
  <https://download.eclipse.org/eclipse/downloads/data.json> (the first entry of `"releases"`, for
  example `drops4/R-4.41-202608281142`) and does nothing if the smoke job has it. Otherwise it takes
  the Linux x86_64 Platform's SHA-512 from the drop's checksums file for CI, downloads this
  machine's Platform (or only CI's, with `--no-tests` or where the drop has none for this
  machine, which skips the local install), stops if its `eclipse.ini` (`-Dosgi.requiredJavaVersion`) needs a newer Java
  than `.java-version` (the smoke job runs it on that Java; raise the Java release first), sets the
  smoke entry in `update-site.yml` and `rehearse-release.yml`, rewrites the `[eclipse.newest]`
  copies, adds a changelog entry, builds the update site and installs it into that Eclipse, and
  commits *Test with Eclipse 2026-12 (4.42)*. Once the plug-in is listed, tick the release in the
  Marketplace listing.
- **`oldest <release>`** finds the release's drop on its p2 repository's page
  (`https://download.eclipse.org/eclipse/updates/4.38/`), downloads its Platform the same way,
  stops if it needs a newer Java than the plug-in is built for (raise the Java release first, in a
  commit of its own), and reads the eight `<eclipse.*.version>` properties (text, equinox.common,
  core.commands, swt, jface, jface.text, core.runtime, osgi) from the Platform's `plugins/` folder:
  `tests/text` and `tests/swt` take these jars from Maven Central, and they should be the ones the
  plug-in is built against. Then it sets `<eclipse-repo.url>` and those properties, the first smoke
  entry, the `[eclipse.oldest]` copies and the changelog, runs every test (`LikeCi --all`), installs
  into the new oldest and the newest Eclipse, and commits. What stays yours: the release notes,
  the workarounds for older releases, and the comments that name the old release for itself
  (such as 4.34's `eclipse.ini`, which does not start on Java 24).
- **`smoke`** builds the update site from this checkout and installs it into both pinned releases
  with p2's director, as the smoke job does after a release, on Linux, macOS (from the `.dmg`) or
  Windows (from the `.zip`). Run it on macOS and Windows after a move. A release with no Platform
  for this machine is skipped with a note (Eclipse 4.34 has no Windows on ARM build); it fails only
  if none can be installed into. A Platform brings no Java:
  it runs on `--java <home>`, else `eclipse.java` in `releng/tools/common/local.properties`, else
  `JAVA_HOME`, else the tool's own Java. Eclipse 4.34 needs Java 21 to 23 (its `eclipse.ini` asks
  for a security manager, which Java 24 removed):

  ```bash
  java releng/tools/eclipse/EclipseUpgrade.java smoke --java /usr/lib/jvm/java-21-openjdk-amd64
  ```

The release names come from counting quarters; the tool checks each against its drop, which is
built within two months of the release (`R-4.41-202608281142`, August, is 2026-09; a respin may
come after it), and stops if Eclipse's cycle ever changes. The Release tool's `check` prints a line when Eclipse has a
release newer than the smoke job's.

The smoke entries are pinned rather than looked up at run time, so a release of the plug-in never
fails just because Eclipse shipped that day. `Require-Bundle` in `MANIFEST.MF` has no version
ranges, so nothing but the feature's `org.eclipse.platform` import (`version="4.34.0"
match="greaterOrEqual"`) stops an install on an Eclipse older than the target; the target release
is the promise.

**By hand**, if the tool cannot run: the same edits. For the newest, the second smoke entry in both
workflows (`eclipse`, `platform-url`, `platform-sha512`: the download's line in
`<drop>/eclipse-4.41-checksums`, for example
`curl -sSfL https://download.eclipse.org/eclipse/downloads/drops4/R-4.41-202608281142/eclipse-4.41-checksums | grep platform-4.41-linux-gtk-x86_64`)
and the `[eclipse.newest]` copies; then `releng/smoke-install.sh` into that Eclipse
(docs/PACKAGING.md). For the oldest, `<eclipse-repo.url>`, the eight jar versions (the folder names
in the release's `plugins/`, `org.eclipse.text_3.14.200.v20240915-0736.jar` meaning `3.14.200`),
the first smoke entry (older drops name their checksums `checksum/eclipse-<version>-SUMSSHA512`)
and the `[eclipse.oldest]` copies. `VersionConsistencyTest` lists every copy still missed.

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
- **Maven and the Maven wrapper**: `java releng/tools/maven/MavenUpgrade.java` (a JDK of release
  22 or newer) moves Maven to the newest release on `maven.line` (3.9, in
  `releng/tools/maven/settings.properties`) and the wrapper scripts to their newest release, one
  commit each, after y/N. `--maven <version>` or `--wrapper <version>` picks a release; a release
  under a week old is refused unless `--allow-young`; `--dry-run` shows the steps. Dependabot cannot
  do this (`.github/dependabot.yml` says why). What it does:
  1. Downloads each new archive from Maven Central and checks it: Maven's against Central's
     `.sha512`, the wrapper's against its `.sha1` (Central has no SHA-512 for it), and, when `gpg`
     is installed, both `.asc` signatures against Maven's
     [KEYS](https://downloads.apache.org/maven/KEYS) file, in a keyring of its own (a temporary
     folder, deleted at the end). Without `gpg` it says the PGP check was skipped.
  2. Runs Maven's own setup goal, `maven-wrapper-plugin` at the wrapper's exact version, with
     Maven's version and the SHA-256 of its archive, which `./mvnw` checks before it runs a
     downloaded Maven:
     `./mvnw -N -C org.apache.maven.plugins:maven-wrapper-plugin:<wrapper>:wrapper -Dtype=only-script -Dmaven=<maven> -DdistributionSha256Sum=<sha256>`.
  3. Checks what the plug-in wrote: `mvnw` and `mvnw.cmd` must be the checked archive's, and the
     settings file must name Maven's archive on Central (the plug-in takes the address from your
     `~/.m2/settings.xml`, so a mirror there would end up in the file) with that SHA-256.
  4. Runs every test (`LikeCi --all`, which downloads the new Maven through `./mvnw`) once, after
     the last of the two moves, then commits *Move to Maven Wrapper …* and *Move to Maven …*.

  Then read the release notes ([Maven](https://maven.apache.org/docs/history.html),
  [wrapper](https://github.com/apache/maven-wrapper/releases)) and run `LikeCi --all` on macOS and
  Windows. Stay on the 3.9 line until Tycho supports Maven 4; moving to it means changing
  `maven.line`, deliberately. `Release.java check` prints a line when either has a newer release.

  By hand, if the tool breaks: run the goal of step 2 with the versions you want, after taking the
  SHA-256 of Maven's archive (`curl -fsSLO` its URL from Central, compare its SHA-512 with the
  `.sha512` file next to it, then `sha256sum`), and check `git diff` as in step 3.
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

**When it moves**: with every Neovim release, about a week after it appears (see *Following
Neovim's releases* below). The pin and the minimum are one setting, so each move also raises the
oldest Neovim the plug-in accepts, even for a patch release: a program named with
`-Dneovim4eclipse.nvim` that is older than the new pin is refused from then on. Moving is what lets
the plug-in

- rely on what a new release adds instead of working around its absence (a new API, option,
  event or default; for example `vim.diagnostic.jump`'s `on_jump` instead of a `CursorMoved`
  check), and
- give users each Neovim fix, security fixes included.

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
4. **The archives as *Get Neovim* installs them**: the plug-in is compiled, and each archive the
   updater kept (one download serves both) is installed into a scratch folder by its own
   `util/NeovimInstaller`: size, SHA-256, unpacking (the tar reader refuses links, which Neovim's
   archives have not had so far), tree hash, and for this machine's archive, that Neovim runs. On
   Linux all six are installed; on macOS or Windows, whose file names ignore case, only that
   system's, and the others are hashed. This replaces running *Get Neovim* once in a development
   Eclipse.
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
after it appears (the updater's own wait, above), with the steps above; each move raises the minimum
too, as they are one setting. For a release that fixes something urgent, `--allow-young` moves
sooner.

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
