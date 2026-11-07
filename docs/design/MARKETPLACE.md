# Design note: the update site, signed releases and the Eclipse Marketplace listing

## Summary

The [Eclipse Marketplace](https://marketplace.eclipse.org) is the catalog Eclipse users search from
inside the IDE (*Help → Eclipse Marketplace…*). A listing there gives an *Install* button and a
drag-to-install link, and is where Eclipse users look first. A listing does not host the plug-in: it
points at a **p2 update site URL** and names the **feature ID** to install, and the Marketplace
Client installs from that URL with p2, the same way *Install New Software* does.

So most of the work is on the project's side, in four parts (the phase numbers are referred to
from the code and other docs):

| Phase | What | Who |
|-------|------|-----|
| 1 | A permanent p2 update site on GitHub Pages, published by the Release workflow | code |
| 2 | Install by URL in the README; the listing's fields, logo and screenshots | code + maintainer |
| 3 | Eclipse account, submitting the listing, testing the Install button on all three OSes | maintainer |
| 4 | PGP-signed releases (an Ed25519 release key, signed in a release job of its own) | code + maintainer |

Facts the parts share:

- Hosting is on **GitHub Pages**, at its default URL (phase 1 says why not a custom domain).
- Display name **Neovim for Eclipse**; machine name `neovim4eclipse`. The Eclipse Foundation's
  trademark rules allow "*X* for Eclipse" and do not allow "Eclipse" as part of a product name,
  which is why it is not "Eclipse Neovim".
- Platforms: **Windows, Linux, macOS**; the plug-in is pure Java, so one listing covers all three.

Not done: the listing itself (phase 3) is not submitted yet, so the README has no Marketplace badge.

## Background: what a p2 update site is

*Install New Software* and the Marketplace Client both read a **p2 repository**: a folder (or ZIP)
with two index files and the jars they describe.

```text
content.jar     metadata: which installable units exist, their versions and dependencies
artifacts.jar   where each jar lives and its checksums
features/…      the feature jar
plugins/…       the plug-in jar
```

The build makes one (`releng/.../target/repository/`, and the same as the release ZIP). A ZIP per
release is not enough for the Marketplace: it needs a **URL that never changes**. Users and the
Marketplace keep that URL forever, and *Check for Updates* polls it, so it must keep working across
releases.

A **composite repository** solves that. It is a folder with two small XML files that list other
repositories (its *children*); p2 reads them all as one. Each release adds a child, and the root URL
stays the same:

```text
https://jm6cnddxbwftaks.github.io/neovim4eclipse/          ← the URL users add (the root)
    compositeContent.xml      lists the children below
    compositeArtifacts.xml    the same list, for the artifacts
    p2.index                  tells p2 to read the composite files, saving it some 404s
    index.html                a human page: "paste this URL into Install New Software"
    releases/0.1.4/           the 0.1.4 update site, unpacked from its release ZIP
    releases/0.1.5/           …
```

Example `compositeContent.xml` (`compositeArtifacts.xml` is the same with `Artifact` in place of
`Metadata`):

```xml
<?xml version='1.0' encoding='UTF-8'?>
<?compositeMetadataRepository version='1.0.0'?>
<repository name='Neovim for Eclipse'
    type='org.eclipse.equinox.internal.p2.metadata.repository.CompositeMetadataRepository'
    version='1.0.0'>
  <properties size='2'>
    <property name='p2.timestamp' value='1792555896000'/>
    <!-- Fail the whole load if one child is missing, instead of silently showing fewer versions. -->
    <property name='p2.atomic.composite.loading' value='true'/>
  </properties>
  <children size='2'>
    <child location='releases/0.1.4'/>
    <child location='releases/0.1.5'/>
  </children>
</repository>
```

The site keeps every (signed) version rather than only the latest, so a user who needs an older
one can install it, and *Check for Updates* always finds the newest.

## Phase 1: the update site on GitHub Pages

**Published with GitHub Actions, not a `gh-pages` branch.** The repository rulesets let only the
owner push branches, so a workflow pushing to `gh-pages` would be refused (and exempting it would
weaken the rule). Pages' own deployment (`actions/upload-pages-artifact` +
`actions/deploy-pages`) needs no branch: each run uploads the whole site, and Pages serves it.

Because each deployment *replaces* the whole site, the site is **rebuilt from the GitHub Releases**
every time, not added to:

1. List the releases (`gh release list`) and download each one's
   `neovim4eclipse-<version>.zip` (`gh release download`).
2. Unpack each into `site/releases/<version>/`, and check it has `content.jar` (the same check the
   publish job makes) and valid signatures (phase 4, "The update site").
3. Write `compositeContent.xml`, `compositeArtifacts.xml` and `p2.index` with one child per version,
   oldest first, and an `index.html` from a template.
4. Upload `site/` and deploy it.

This makes the releases the single source of truth: deleting a bad release and re-running the job
removes it from the site, and nothing on the site exists that is not a release.

**Where the code is:**

- `releng/update-site.sh <dir with ZIPs> <out dir> <public key>`: steps 2 and 3, a plain shell
  script that runs the same locally and in CI, with explanatory comments on the composite format.
  Steps 1 and 4 are in the workflow. How to run it locally is in [PACKAGING.md](../PACKAGING.md),
  "The update site on GitHub Pages".
- `.github/workflows/update-site.yml`, a workflow of its own, which `release.yml` calls as its last
  job (`site`), after `publish`, so the new release is already listed. It needs the `pages: write`
  and `id-token: write` permissions and the `github-pages` environment. It can also run on demand
  (*Run workflow*, `workflow_dispatch`) to rebuild the site without a release. Putting *Run
  workflow* on `release.yml` itself would have made the whole release startable by hand; this way
  only the site is. GitHub shows *Run workflow* only for a file that is on `main`.
- A smoke test in that workflow, after deploying: `releng/smoke-install.sh` downloads the oldest
  supported Eclipse Platform (4.34) and runs p2's *director* application against the live URL, the
  way *Install New Software* would:

  ```bash
  eclipse/eclipse -nosplash -application org.eclipse.equinox.p2.director \
    -repository https://jm6cnddxbwftaks.github.io/neovim4eclipse/ \
    -installIU io.github.jm6cnddxbwftaks.neovim4eclipse.feature.feature.group
  ```

  (`.feature.group` is the name p2 gives a feature's installable unit.) It gives p2 **only the
  update site**, no download.eclipse.org repository, so the install fails if the plug-in needs
  anything Eclipse Platform 4.34 lacks; that also tests the "2024-12 or newer" claim. It catches what
  users would hit: composite XML p2 cannot read, a wrong feature ID, a missing dependency, a bad
  signature. A site with a release folder missing fails as intended
  (`p2.atomic.composite.loading`). Locally it installs the newest release in about 4 seconds; in CI,
  with the Eclipse download (~100 MB), it takes a few minutes and can fail when download.eclipse.org
  is slow. It runs once per release, not per push, so the cost is small.

  The weaker alternative, fetching `compositeContent.xml`, `compositeArtifacts.xml` and each child's
  `content.jar` from the live URL, takes seconds but catches only a missing or misnamed file, which
  the real install catches too; it is left out.
- **Java 21 for the smoke test**: Eclipse 4.34's `eclipse.ini` passes
  `-Djava.security.manager=allow`, which Java 24 and newer refuse, so `smoke-install.sh` runs
  Eclipse with `$JAVA_HOME`.

**Repository settings, made once:** *Settings → Pages → Source: GitHub Actions*; and the
`github-pages` environment, which allows only `main` to deploy by default, must also allow the tags
`v*`, since the Release workflow runs for a tag.

**The URL is permanent:** `https://jm6cnddxbwftaks.github.io/neovim4eclipse/`, the GitHub Pages
default; GitHub lower-cases the account name. A custom domain would let the site move off GitHub
later without breaking installed copies and the listing, but must be renewed forever; not worth it
while GitHub hosts everything else.

## Phase 2: install by URL, and the listing material

**README "Install":** *Add… → Location:* the update site URL is the first way, the ZIP the offline
way. The Marketplace badge and drag-to-install link go there once the listing exists (phase 3).

**Feature metadata** (`feature.xml`), which *Install New Software → Details* and the listing show: a
`<description>` saying what it does, which Neovim it needs and how it gets one, and the supported
Eclipse (2024-12 / 4.34 or newer); `<license>` EPL-2.0, matching the listing.

**The listing's fields.** The rows follow marketplace.eclipse.org's *Create Solution* form, top to
bottom; `*` marks a field the form requires. The form may change; it is the final word.

| Form field | Value |
|------------|-------|
| Solution Name `*` | Neovim for Eclipse (the form asks for the product name only, no tagline) |
| Solution URL | https://github.com/Jm6CnDDXBwFTakS/neovim4eclipse |
| Organization Name | empty (a personal project) |
| Eclipse Foundation Member Organization `*` | No |
| Solution Logo | [docs/images/marketplace-logo.png](../images/marketplace-logo.png): the form scales every logo to 110×80 pixels (png, gif or jpg), so this is `logo.svg` centered on white at exactly that size (*Images*, below) |
| Summary | one sentence, like `feature.xml`'s first line |
| Solution Description `*` | formatted text; see below |
| Status | Beta, or the closest choice the list offers: the README calls it an early release |
| License Type | EPL 2.0 |
| Solution Type | This product requires an existing Eclipse installation to function |
| Support URL | https://github.com/Jm6CnDDXBwFTakS/neovim4eclipse/issues |
| RSS Feed | https://github.com/Jm6CnDDXBwFTakS/neovim4eclipse/releases.atom (GitHub's feed of the releases) |
| Categories `*` (5 or fewer) | Editor, plus whichever others in the list fit a key-binding/editing tool |
| Markets `*` | Tools |
| Tags | vim, neovim, vi, modal editing, keybindings |
| **Solution version:** Version number | the current release, or empty: the update site always offers the latest |
| Update Site URL `*` | https://jm6cnddxbwftaks.github.io/neovim4eclipse/ (the composite site; never changes) |
| Supported Eclipse Release(s) `*` | 2024-12 (4.34) and later; tick a release only once it is out and tested on |
| Supported Platform(s) `*` | Windows, Mac, Linux/GTK |
| Minimum Java Version `*` | Java 21 |
| Feature ID `*` / Install state | `io.github.jm6cnddxbwftaks.neovim4eclipse.feature` / Required (the feature's ID, without the `.feature.group` suffix p2 adds) |
| Screenshot (up to 4; png, gif or jpg; 16 MB) | in this order: [demo.gif](../images/demo.gif) (the README's demo), then `01-visual-mode.png`, `02-command-line.png` and `03-preferences.png`, taken fresh for each upload and not kept in the repository (*Images*, below) |
| Additional Editors | none |

**The description** says, up front:

- what the plug-in does (Neovim owns the editing; Eclipse keeps owning files, rendering, content
  assist and its own commands);
- the requirements, as in `feature.xml`: which Neovim and how it gets one, Eclipse 2024-12 (4.34)
  or newer, Java 21 or newer;
- that releases are signed with the project's PGP key, with the fingerprint to check in the trust
  dialog (README, *Install*);
- a link to the README's known limitations,
  `https://github.com/Jm6CnDDXBwFTakS/neovim4eclipse#known-limitations`: users who know the limits
  before installing review and report fewer surprises. GitHub makes the anchor from the heading, so
  renaming the heading breaks the link.

### Images

- The **logo**, [docs/images/logo.svg](../images/logo.svg): an N whose left stem is a colon (green,
  Neovim) and whose right stem is Eclipse's I-beam caret (indigo), so it also reads as "VI" with a
  hint of "E". It borrows nothing from the Eclipse or Neovim logos, which are their owners' marks;
  a draft with the Eclipse logo's stripes was dropped for that reason. With the name beside it in
  the logo's green (`logo-wordmark.svg`, one file for light and dark themes) it heads the README.
  For the listing: `marketplace-logo.png`, 110×80 pixels, the size the form scales every logo to.
  To render it again after changing `logo.svg` (no SVG tool is needed besides Firefox and Python's
  Pillow):

  ```bash
  printf '<body style="margin:0;background:#fff;width:440px;height:320px;display:flex;align-items:center;justify-content:center"><img src="logo.svg" width="304" height="304">' > docs/images/page.html
  firefox --headless --window-size=440,320 --screenshot /tmp/big.png "file://$PWD/docs/images/page.html"
  rm docs/images/page.html
  python3 -c "from PIL import Image; Image.open('/tmp/big.png').convert('RGB').resize((110, 80), Image.LANCZOS).save('docs/images/marketplace-logo.png', optimize=True)"
  ```
- **Screenshots**, taken by `ScreenshotCaptureTest` in the UI test module rather than by hand, so
  they show the current UI each time: a Java editor in visual line mode (the mode in the status
  bar), the `:` box with a substitution typed, and the Neovim preference page. Nothing shows them
  from the repository (the README has the demo GIF), so they are not committed: take them when the
  listing is submitted or refreshed, and upload them from
  `tests/io.github.jm6cnddxbwftaks.neovim4eclipse.tests/target/screenshots/`. A picture in the
  repository stays in its history for good, along with anything it shows by mistake. The test runs
  only with its own profile, which adds the Java tools for a highlighted Java editor:

  ```bash
  xvfb-run -a -s "-screen 0 1600x1000x24" ./mvnw verify -Pscreenshots -Dnvim.path="$PWD"/target/like-ci/nvim-linux-x86_64/bin/nvim
  ```

  Xvfb's `-screen` gives it room for the 1280×800 window in full color (its default is 640×480 at
  8 bits). They show Eclipse's default light theme on Linux/GTK, without a window title bar.

  The preference page is taken as a user's Eclipse shows it: with the dedicated Neovim (a copy of
  the `-Dnvim.path` release, so use the pinned one from `LikeCi`, under `target/like-ci/`) and
  without `-Dneovim4eclipse.nvim`. Its *Found:* note names the dedicated Neovim's folder, which the
  `screenshots` profile keeps under `pictures.home` (`/tmp/neovim4eclipse-pictures`; on Windows
  `C:\Users\Public\neovim4eclipse-pictures`) instead of `target/`, so no picture shows a home
  folder and with it an account name (`gallery/PicturesFolder` in the test fragment). Look at the
  pictures before uploading them all the same: they are public.
- The README demo GIF, recorded by hand.

## Phase 3: account, submission, test

All maintainer steps: they need an Eclipse Foundation account and its sign-in.

1. Create an **Eclipse Foundation account** at accounts.eclipse.org (the project has one).
2. On marketplace.eclipse.org, log in, *Add Content → Solution*, and fill in the fields from
   phase 2's table.
3. Listings go through **moderation** before they appear (the Marketplace quickstart says about one
   business day).
4. Test the listing from a clean Eclipse on **Windows, Linux and macOS**: *Help → Eclipse
   Marketplace…*, search "Neovim", *Install*, restart, open a file. Then *Check for Updates* after
   the next release, to see the composite site deliver it.
5. Add the badge and drag-to-install link to the README.

Questions about listings go to marketplace@eclipse-foundation.org.

## Phase 4: PGP signing

Releases from 0.1.4 on are signed; 0.1.0–0.1.3 are not. Unsigned jars make Eclipse's install wizard
ask the user to trust *unsigned content*. There are two ways to sign:

- **PGP**, the one used: `tycho-gpg-plugin` signs the artifacts and records the signatures in
  `artifacts.jar`; p2 checks PGP signatures since Eclipse 4.21 (2021-09). Eclipse then shows *the
  project's key* in the trust dialog and asks the user to trust it: still one click, but it names
  who signed, and a tampered jar fails the install.
- **jarsigner with a code-signing certificate**: no prompt at all, but a certificate from a
  certificate authority costs money every year. The Eclipse Foundation signs only its own
  projects' jars. Not used.

The signing was added after the update site, separately from the listing, which does not need it.

### How PGP signing works in p2

A p2 PGP signature is *extrinsic*: the jar is not changed. The signature (an armored detached
signature, as `gpg --armor --detach-sign` makes) is stored as the `pgp.signatures` property of the
artifact's entry in `artifacts.xml`, and the signer's public key as `pgp.publicKeys`. Example:

```xml
<artifact classifier='osgi.bundle' id='io.github.jm6cnddxbwftaks.neovim4eclipse' version='0.1.4'>
  <properties size='…'>
    <property name='pgp.signatures' value='-----BEGIN PGP SIGNATURE-----&#xA;…'/>
    <property name='pgp.publicKeys' value='-----BEGIN PGP PUBLIC KEY BLOCK-----&#xA;…'/>
```

When installing, p2 checks each signature against the jar (a bad one aborts the install), then
collects the keys nobody has trusted yet and shows them in the trust dialog. A key the user trusted
(*Remember*) or that an installed plug-in vouches for (below) is not asked about again. Users see
and manage trusted keys under *Preferences › Install/Update › Trust*.

### The key

Fingerprint `A91F 53D8 931B 7FD0 D146  8618 6CC9 E446 147B 063C`, uploaded to keys.openpgp.org.

| | | |
|-|-|-|
| Identity | `Neovim for Eclipse releases <58570069+Jm6CnDDXBwFTakS@users.noreply.github.com>` | a key used only for releases, not the maintainer's personal key: if it leaks, revoking it touches nothing else. The GitHub *noreply* address, because a public key is public forever |
| Algorithm | **Ed25519** | modern, small, fast; p2's verifier accepts it (experiment S1). The fallback would have been RSA 4096 |
| Expiry | **none**; a revocation certificate kept offline | Tycho copies the public key, with its expiry date, into each release's metadata, and p2 checks expiry (`p2.pgp.verifyExpiration`). An expiry date would make old releases warn or fail after it, even if the key were extended later. If the key leaks, publishing the revocation certificate retires it; p2 checks revocation too (`p2.pgp.verifyRevocation`) |
| Passphrase | yes | the key is stored in GitHub encrypted, but a passphrase means a leaked secret alone cannot sign |

### Where the secrets live

In a GitHub **environment** named `release` (*Settings › Environments*), not as repository secrets:

- secrets `MAVEN_GPG_KEY` (the armored secret key) and `MAVEN_GPG_PASSPHRASE`. Tycho 5.0.4's `bc`
  signer reads only the passphrase from the environment; the key it wants as a *file*
  (`tycho.pgp.signer.bc.secretKeys`), so the sign job writes `MAVEN_GPG_KEY` to a file in
  `$RUNNER_TEMP` and deletes it afterwards (experiment S2);
- *Deployment branches and tags*: only tags `v*`.

A job can read the secrets only if it names the environment, and only when the run is for a `v*`
tag. A workflow edited on some branch, or a pull request, cannot reach them.

### The workflow

Signing is its own job in `release.yml`, between `build` and `publish`; the job only checks out,
sets up Java and calls `releng/sign.sh`, which does steps 1–4:

```
version ──► build (unsigned, exactly as on main) ──► sign ──► publish ──► site
                                                      │
                          environment: release        │  1. download the unsigned ZIP, unzip it
                          (secrets readable only here)│  2. ./mvnw -f releng/sign/pom.xml package
                                                      │     (tycho-gpg-plugin, sign-p2-artifacts,
                                                      │      signer bc, repository = the unzipped site,
                                                      │      the secret key from a temporary file)
                                                      │  3. releng/verify-signatures.sh
                                                      │  4. zip it again
                                                      │  5. gpg --detach-sign the ZIP (.zip.asc),
                                                      │     releng/verify-zip.sh; upload both
```

- **The build stays the same.** `build.yml` gets no secrets and no signing profile, so `main`, pull
  requests and the release all build identically; only the release then signs the result.
- **`releng/sign/pom.xml`**: a small Maven project whose only job is the
  `tycho-gpg-plugin:sign-p2-artifacts` goal with `signer = bc` (Bouncy Castle, in Java: no `gpg`
  program needed on the runner), `repository` = the unzipped site, and `bestPractices = true` (the
  build fails if the passphrase is ever passed in a way that leaks, such as a `-D` property). The
  plug-in version is a literal copy of the root `pom.xml`'s `tycho.version`, checked by
  `VersionConsistencyTest`: the POM has no parent, because the root POM loads Tycho as a build
  extension, which would resolve the whole target platform just to sign.
- **`releng/verify-signatures.sh`**: fails unless every `osgi.bundle` and
  `org.eclipse.update.feature` artifact in `artifacts.jar` has a `pgp.signatures` property, and each
  signature verifies with `gpg --verify` against `releng/signing-key.asc` (the public key in the
  repository). A release with a missing or wrong signature never reaches `publish`. It also fails
  on anything else in the site (another classifier, a file no artifact accounts for, a link) and on
  `.xml.xz` metadata that differs from the XML in the jars, since p2 reads the `.xz` copies first.
- **The ZIP's own signature** (`<zip>.asc`, from 0.2.1; `releng/verify-zip.sh`): the jar
  signatures leave `content.jar` uncovered, which holds the install instructions p2 follows
  without asking, so a ZIP replaced on GitHub could keep the signed jars and change it. A detached
  signature over the ZIP covers every file. The releases before it (0.1.4–0.2.0) are pinned by
  SHA-256 in `releng/release-zips.sha256` instead.
- The `sign` job fails at once while `releng/signing-key.asc` is missing, rather than publish an
  unsigned release.
- **The update site**: `releng/update-site.sh`, given the public key, checks every release's
  signatures (the ZIP's, or its pinned hash, and each jar's) again each time it rebuilds the site (a release ZIP on GitHub could be replaced after
  it was checked once at release) and leaves out the unsigned 0.1.0–0.1.3, so the site never
  prompts for unsigned content, a prompt users learn to accept. Re-signing those would change
  published artifacts; they stay on GitHub's release pages.
- **Smoke test** (`update-site.yml`): the director install from the live site also checks the
  signatures, since a bad one aborts it. The headless director accepts a key nobody has trusted
  (experiment S3), so the smoke test needs no trust flag.

### The plug-in vouches for its own key

p2's extension point `org.eclipse.equinox.p2.engine.pgp` lets an installed plug-in declare keys as
trusted. The first install still shows the dialog (nothing is installed yet); every later update
installs without asking (experiment S4). In `plugin.xml`:

```xml
<extension point="org.eclipse.equinox.p2.engine.pgp">
  <trustedKeys path="keys/release-signing-key.asc"/>
</extension>
```

The file is the same public key as `releng/signing-key.asc` (`ReleaseSigningKeyTest` checks that
the two are equal), listed in `build.properties`' `bin.includes`. The extension point is older than
the minimum Eclipse, 4.34 (its schema says p2 2.7.300; Eclipse's notes date trusted-key
contributions to 4.23).

### Publishing the public key

So that anyone can check that a release was signed by this project, the fingerprint is printed in
the README (*Install*), in [SECURITY.md](../../SECURITY.md), and on the update site's page
(`releng/update-site.sh`); `ReleaseSigningKeyTest` checks all three. The key is on
[keys.openpgp.org](https://keys.openpgp.org) (which asks the address to confirm; a noreply address
cannot, so the key is findable there by fingerprint only). The README's install steps say to check
the key's fingerprint, then trust it.

### The maintainer's part

The private key is made on the maintainer's own machine and goes from there only to an offline
copy and the `release` environment's secrets, which only the `sign` job reads; no other tool or
assistant creates or handles it. Creating (or replacing) the key, with GnuPG 2.2 or newer:

```bash
# 1. The key: Ed25519, signing only, no expiry. Prompts for the passphrase.
gpg --quick-generate-key "Neovim for Eclipse releases <58570069+Jm6CnDDXBwFTakS@users.noreply.github.com>" ed25519 sign never

# 2. Its fingerprint (40 hex digits), for the commands below and the docs.
gpg --list-keys --with-colons "Neovim for Eclipse releases" | awk -F: '/^fpr/ {print $10; exit}'

# 3. Keep offline (a USB stick, a password manager): the secret key and the revocation certificate.
gpg --armor --export-secret-keys FINGERPRINT > release-signing-secret.asc
gpg --output release-signing-revoke.asc --gen-revoke FINGERPRINT

# 4. The public key, for the repository (committed; copy it to the plug-in's keys/ too).
gpg --armor --export FINGERPRINT > releng/signing-key.asc

# 5. The secrets, into the release environment (gh asks for the passphrase interactively).
gh secret set MAVEN_GPG_KEY --env release < release-signing-secret.asc
gh secret set MAVEN_GPG_PASSPHRASE --env release

# 6. The key server.
gpg --keyserver hkps://keys.openpgp.org --send-keys FINGERPRINT
```

Step 5 needs the `release` environment to exist first, with its tag rule (it holds no secret, so
`gh api` can create both). Replacing the key also means changing `keys/release-signing-key.asc`
and the three printed fingerprints (see [PACKAGING.md](../PACKAGING.md), "PGP signing of
releases").

### Experiments

S1–S3 ran on Linux aarch64 with a throwaway Ed25519 key in a temporary `GNUPGHOME` and the unsigned
ZIP from `./mvnw verify -DskipTests`; Eclipse 4.34 platform, run with Java 21 (4.34's `eclipse.ini`
sets `-Djava.security.manager=allow`, which Java 24 and newer refuse at start-up); Tycho 5.0.4.

- **S1. Ed25519 in p2: works headless.** The director installed the Ed25519-signed site. A control
  showed that p2 really checks the signature: replacing the bundle's `pgp.signatures` with a valid
  signature *of other bytes* made the install fail (`Can't download artifact osgi.bundle,…`), while
  the same `artifacts.jar` rewritten with the original signature installed. The trust dialog, with
  the real key: a ZIP signed locally by `releng/sign.sh`, installed through *Install New Software ›
  Archive…*, showed the key and its fingerprint.
- **S2. The signer on an unpacked site: works, with one change to the design.** A one-plug-in POM
  (`packaging pom`, goal `sign-p2-artifacts`, `repository` = the unzipped site) signed both
  artifacts (bundle and feature) and rewrote both `artifacts.jar` and `artifacts.xml.xz`; the
  checksums are unchanged, and `gpg --verify` accepts the bundle's signature. The change:
  `signer = bc` alone still runs the `gpg` program (to find the key, it signs a dummy file with the
  default key: `gpg: signing failed: No secret key`). The `bc` signer stays in Java only when given
  the armored secret key as a file, `-Dtycho.pgp.signer.bc.secretKeys=<file>`; the passphrase
  comes from `MAVEN_GPG_PASSPHRASE`. The source (`ProxySignerWithPublicKeyAccess.getSigner`) shows
  that `MAVEN_GPG_KEY` is not read.
- **S3. The director and untrusted keys: no flag needed.** The headless install of content signed
  by an unknown key succeeds.
- **S4. Trusted-key extension: works (on macOS).** The extension's element and attribute names
  match p2's `CertificateChecker` in Eclipse 4.34, which reads `trustedKeys`/`path` with
  `Bundle.getEntry`. The test: no plug-in installed and no trust remembered, then 0.1.4 installed
  from the live site, then *Check for Updates* to 0.1.5. What it showed:
  - **The first install asks.** Installing 0.1.4 showed the trust dialog, accepted *without*
    "remember". Before the plug-in is installed, nothing on the machine vouches for its key, so a
    user's first install always shows this dialog once.
  - **The installed plug-in vouches for its key.** Afterwards *Preferences › Install/Update ›
    Trust* listed the project key with no *Remove* button: a key that an installed plug-in
    declares (this extension), which the user cannot remove, unlike one they chose to remember.
  - **Updates do not ask.** The update to 0.1.5 showed no dialog.

  Reinstalling a local build also shows no dialog the second time, but there a trust remembered
  from the first install could explain it; the test above starts with none remembered, which rules
  that out.

## Every release afterwards

Nothing new by hand: the tag's Release workflow publishes the GitHub Release and rebuilds the site,
so *Check for Updates* and the Marketplace see the new version. Update the listing only when its
facts change (supported Eclipse versions, description), and answer reviews and questions there.

Sources: [Eclipse Marketplace quickstart](https://marketplace.eclipse.org/quickstart). It is short;
the *Add Content* form is the final word on what a listing needs.
