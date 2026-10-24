# Packaging and installation

## Automated build (Maven / Tycho)

From the repository root:

```bash
# Build plug-in, feature, p2 repository, and run the unit, text-model and SWT tests
./mvnw clean verify
# ... and the UI tests too (opens an Eclipse window)
./mvnw clean verify -Pui
```

Artifacts:

| Path | Content |
|------|---------|
| `plugins/.../target/*.jar` | OSGi plug-in jar |
| `features/.../target/*.jar` | Feature jar |
| `releng/.../target/repository/` | p2 update site (installable) |
| `releng/.../target/neovim4eclipse-<version>.zip` | Same update site as one archive (installable; attach to GitHub releases) |

### Install from the local update site

1. *Help → Install New Software… → Add…*, then either
   - *Archive…* and select `releng/io.github.jm6cnddxbwftaks.neovim4eclipse.updatesite/target/neovim4eclipse-<version>.zip`, or
   - *Local…* and select `releng/io.github.jm6cnddxbwftaks.neovim4eclipse.updatesite/target/repository`
2. Choose **Neovim for Eclipse**, finish, restart (a local build is unsigned, unlike a release: trust the *unsigned content* when Eclipse asks; README, "Install", has every step)

### Unit tests only

```bash
./mvnw test -f tests/unit/pom.xml
# Integration tests (real nvim):
./mvnw test -f tests/unit/pom.xml -Pintegration -Dnvim.path=/usr/bin/nvim
```

`tests/unit` is a plain Maven jar module: it compiles `util/` and `core/` from the plug-in
sources and runs JUnit 5. It does **not** need Eclipse or SWT.

### UI tests

`tests/io.github.jm6cnddxbwftaks.neovim4eclipse.tests` runs inside a real Eclipse workbench as part of
`./mvnw verify -Pui` (only with the `ui` profile, because the workbench opens a window). It needs a
display (`xvfb-run -a ./mvnw verify -Pui` on a headless machine) and, for its
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

## The update site on GitHub Pages

Users install from a URL that never changes, <https://jm6cnddxbwftaks.github.io/neovim4eclipse/>, and *Check for Updates*
finds each new release there. It is a **composite p2 repository**: two small XML files that list
other repositories (its children), one per release, which p2 reads as one:

```text
index.html               a page for people who open the URL in a browser
p2.index                 tells p2 to read the composite files first
compositeContent.xml     lists the children, for the metadata (what can be installed)
compositeArtifacts.xml   lists the children, for the artifacts (the jars)
releases/0.1.0/          the 0.1.0 release ZIP, unpacked
releases/0.1.1/ ...
```

The *Update site* workflow (`.github/workflows/update-site.yml`) builds it and runs at the end of
every release (`release.yml` calls it), or by hand (*Actions → Update site → Run workflow*, once the
workflow is on `main`). It downloads every published release's ZIP, runs
`releng/update-site.sh` on them, deploys the result to GitHub Pages, and then installs from the live
URL into Eclipse 4.34 with `releng/smoke-install.sh`. Each deployment replaces the whole site, so it
always holds exactly the published releases: to withdraw one, delete the GitHub Release and run the
workflow.

Both scripts run locally too:

```bash
mkdir -p /tmp/zips && gh release download v0.1.2 --pattern '*.zip' --dir /tmp/zips
releng/update-site.sh /tmp/zips /tmp/site
# into a throwaway Eclipse Platform 4.34 (it is changed), run with Java 21
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 releng/smoke-install.sh ~/eclipse-4.34 file:///tmp/site 0.1.2
```

The repository needs two settings for it, made once: *Settings → Pages → Source: GitHub Actions*,
and in *Settings → Environments → github-pages*, deployment tags `v*` allowed besides `main`
(the Release workflow runs for a tag). The design and its decisions are in
[design/MARKETPLACE.md](design/MARKETPLACE.md), phase 1.

## PGP signing of releases

Releases are signed with the project's PGP key, so that Eclipse names the signer when it asks
the user to trust the content, and refuses a jar that was changed after signing. The signature
does not change the jars: p2 keeps each one, armored, in the artifact's entry in `artifacts.jar`
(the property `pgp.signatures`), next to the public key (`pgp.publicKeys`).

The build itself stays unsigned, on every branch. The *Release* workflow signs its result in a job
of its own, `sign`, which alone can read the secret key (a secret of the GitHub environment
`release`, open only to `v*` tags). The job runs `releng/sign.sh`:

1. unzips the update site ZIP;
2. signs it in place with `releng/sign/pom.xml` (`tycho-gpg-plugin`, goal `sign-p2-artifacts`,
   Bouncy Castle signer, so no `gpg` program is needed for signing);
3. checks every bundle's and feature's signature with `gpg` against `releng/signing-key.asc`
   (`releng/verify-signatures.sh`), so a missing or wrong signature stops the release;
4. zips it again; the signed ZIP replaces the unsigned one for the rest of the run.

The public key is in two places: `releng/signing-key.asc`, which the job checks against, and a
copy in the plug-in (`keys/release-signing-key.asc`), which `plugin.xml` declares as trusted
(extension point `org.eclipse.equinox.p2.engine.pgp`), so that updates install without the trust
dialog. `ReleaseSigningKeyTest` checks that the two are equal and that README, SECURITY.md and the
update site's page print its fingerprint. To replace the key, change both files and the three
fingerprints.

The job fails at once while `releng/signing-key.asc` does not exist, rather than publish an
unsigned release. To try it locally with a throwaway key, see the commands at the top of
`releng/sign.sh`; `releng/verify-signatures.sh <site dir> <public key>` checks any unzipped site.
The design, the key and the experiments behind it are in
[design/MARKETPLACE.md](design/MARKETPLACE.md), phase 4.

## PDE (optional, for interactive development)

You can still import the projects into Eclipse PDE and launch from `MANIFEST.MF`
as described in [HOWTO_ECLIPSE.md](HOWTO_ECLIPSE.md). Tycho and PDE share the same
`MANIFEST.MF`, `plugin.xml`, `feature.xml`, and `category.xml`.

## Runtime requirement

`nvim` must be on the `PATH` of the Eclipse process, or an absolute path set in
*Preferences → Neovim*.
