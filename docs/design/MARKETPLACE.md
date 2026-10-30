# Plan: listing on the Eclipse Marketplace

Status: **phase 1 built and deployed** (with 0.1.3), phases 2–3 planned; phase 4 built (signing, the real key, the plug-in trusting it); the first signed release is 0.1.4, and updates from it (to 0.1.5) install without a trust dialog (S4). Written on `release/0.1.2` (after 0.1.1). Decisions made with
the maintainer are marked *(decided)*; none are open. When a phase is done, change its heading to
say so and note where the result differs from this plan, as
[TAB_COMPLETION.md](TAB_COMPLETION.md) does with *As built*.

## Summary

The [Eclipse Marketplace](https://marketplace.eclipse.org) is the catalog Eclipse users search from
inside the IDE (*Help → Eclipse Marketplace…*). A listing there gives an *Install* button and a
drag-to-install link, and is where Eclipse users look first. A listing does not host the plug-in: it
points at a **p2 update site URL** and names the **feature ID** to install, and the Marketplace
Client installs from that URL with p2, the same way *Install New Software* does.

So the work is mostly ours, not the Marketplace's:

| Phase | What | Who | Size |
|-------|------|-----|------|
| 1 | A permanent p2 update site on GitHub Pages, published by the Release workflow | code | medium |
| 2 | Install by URL in the README; the listing text, logo and screenshots | code + maintainer | small |
| 3 | Eclipse account, submit the listing, test the Install button on all three OSes | maintainer | small |
| 4 | Later: PGP-sign the artifacts (an Ed25519 release key, signed in its own release job) | code + maintainer | medium |

Decided so far:

- Hosting on **GitHub Pages**, at its default URL *(decided)*.
- Display name **Neovim for Eclipse**; machine name `neovim4eclipse` *(decided)*. The Eclipse
  Foundation's trademark rules allow "*X* for Eclipse" and do not allow "Eclipse" as part of a
  product name, which is why it is not "Eclipse Neovim".
- Platforms: **Windows, Linux, macOS** *(decided)*; the plug-in is pure Java, so one listing covers
  all three.
- PGP signing comes **later**, not before the listing *(decided)*.

## Background: what a p2 update site is

*Install New Software* and the Marketplace Client both read a **p2 repository**: a folder (or ZIP)
with two index files and the jars they describe.

```text
content.jar     metadata: which installable units exist, their versions and dependencies
artifacts.jar   where each jar lives and its checksums
features/…      the feature jar
plugins/…       the plug-in jar
```

Our build already makes one (`releng/.../target/repository/`, and the same as the release ZIP). What
is missing is a **URL that never changes**. Users and the Marketplace keep that URL forever, and
*Check for Updates* polls it, so it must keep working across releases.

A **composite repository** solves that. It is a folder with two small XML files that list other
repositories (its *children*); p2 reads them all as one. Each release adds a child, and the root URL
stays the same:

```text
https://jm6cnddxbwftaks.github.io/neovim4eclipse/          ← the URL users add (the root)
    compositeContent.xml      lists the children below
    compositeArtifacts.xml    the same list, for the artifacts
    p2.index                  tells p2 to read the composite files, saving it some 404s
    index.html                a human page: "paste this URL into Install New Software"
    releases/0.1.1/           the 0.1.1 update site, unpacked from its release ZIP
    releases/0.1.2/           …
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
    <child location='releases/0.1.1'/>
    <child location='releases/0.1.2'/>
  </children>
</repository>
```

Keeping every version (rather than only the latest) lets a user who needs an older one install it,
and lets *Check for Updates* always find the newest *(decided)*.

## Phase 1: the update site on GitHub Pages (built)

### As built

Where the code differs from the plan below, and why:

- **Its own workflow**, `.github/workflows/update-site.yml`, which `release.yml` calls as a fourth
  job (`site`) after `publish`. Adding *Run workflow* to `release.yml` itself would have made the
  whole release startable by hand; this way only the site is. *Run workflow* appears only once the
  file is on `main` (GitHub's rule for manual runs), so the first deployment comes with 0.1.3.
- **The smoke test gives p2 only the update site**, no download.eclipse.org repository: the install
  fails if the plug-in needs anything Eclipse Platform 4.34 lacks. Tried locally on 0.1.0–0.1.2: it
  installs 0.1.2 (the newest) in about 4 seconds, and a site with a release folder missing fails as
  intended (`p2.atomic.composite.loading`).
- **Java 21 for the smoke test**: Eclipse 4.34's `eclipse.ini` passes
  `-Djava.security.manager=allow`, which Java 24 and newer refuse, so `smoke-install.sh` runs
  Eclipse with `$JAVA_HOME`.
- **One more setting**: the `github-pages` environment allows only `main` to deploy by default; the
  tags `v*` must be allowed too, since the Release workflow runs for a tag.
- The scripts are `releng/update-site.sh` and `releng/smoke-install.sh`; how to run them is in
  [PACKAGING.md](../PACKAGING.md), "The update site on GitHub Pages".

### Plan

**Publish with GitHub Actions, not a `gh-pages` branch.** The repository rulesets let only the
owner push branches, so a workflow pushing to `gh-pages` would be refused (and exempting it would
weaken the rule). Pages' own deployment (`actions/upload-pages-artifact` +
`actions/deploy-pages`) needs no branch: each run uploads the whole site, and Pages serves it.

Because each deployment *replaces* the whole site, the site is **rebuilt from the GitHub Releases**
every time, not added to:

1. List the releases (`gh release list`) and download each one's
   `neovim4eclipse-<version>.zip` (`gh release download`).
2. Unpack each into `site/releases/<version>/`, and check it has `content.jar` (the same check the
   publish job makes).
3. Write `compositeContent.xml`, `compositeArtifacts.xml` and `p2.index` with one child per version,
   oldest first, and an `index.html` from a template.
4. Upload `site/` and deploy it.

This makes the releases the single source of truth: deleting a bad release and re-running the job
removes it from the site, and nothing on the site exists that is not a release.

**Where the code goes:**

- `releng/update-site.sh <dir with ZIPs> <out dir>`: steps 2 and 3, a plain shell script that runs
  the same locally and in CI, with explanatory comments on the composite format. Steps 1 and 4 stay
  in the workflow.
- `.github/workflows/release.yml`: a fourth job `site`, after `publish` on every release tag
  *(decided)*, so the new release is already listed. It needs the `pages: write` and
  `id-token: write` permissions and the `github-pages` environment. It can also run on demand (*Run workflow*, `workflow_dispatch`) to rebuild the site
  without a release *(decided)*.
- A smoke test in that job, after deploying. Two levels were weighed; the real install was chosen *(decided)*:
  - **Fetch the files:** `compositeContent.xml`, `compositeArtifacts.xml` and each child's
    `content.jar` answer from the live URL. Seconds; catches a missing or misnamed file.
  - **A real install:** download the oldest supported Eclipse Platform (4.34), and run p2's
    *director* application against the live URL, the way *Install New Software* would:

    ```bash
    eclipse/eclipse -nosplash -application org.eclipse.equinox.p2.director \
      -repository https://jm6cnddxbwftaks.github.io/neovim4eclipse/,https://download.eclipse.org/eclipse/updates/4.34/ \
      -installIU io.github.jm6cnddxbwftaks.neovim4eclipse.feature.feature.group
    ```

    (`.feature.group` is the name p2 gives a feature's installable unit.) About 2–4 minutes and a
    ~100 MB download, and it can fail when download.eclipse.org is slow. In return it catches what
    users would hit: composite XML p2 cannot read, a wrong feature ID, and a dependency the oldest
    supported Eclipse lacks, which also tests the "2024-12 or newer" claim. It runs once per
    release, not per push, so the cost is small. The file fetch then adds nothing and is left out.
- Docs: [PACKAGING.md](../PACKAGING.md) (how the site is built), CONTRIBUTING "Releasing" (the site
  updates itself; how to rebuild it), and this file.

**Maintainer, once:** *Settings → Pages → Source: GitHub Actions*.

**The URL is permanent once published:** `https://jm6cnddxbwftaks.github.io/neovim4eclipse/`, the
GitHub Pages default *(decided)*; GitHub lower-cases the account name. A custom domain would let the
site move off GitHub later without breaking installed copies and the listing, but must be renewed
forever; not worth it while GitHub hosts everything else.

## Phase 2: install by URL, and the listing material

**README "Install":** make *Add… → Location:* the URL the first way, and keep the ZIP as the
offline way. Add a *Marketplace* badge and drag-to-install link once phase 3 is done.

**Feature metadata** (`feature.xml`), which *Install New Software → Details* and the listing show:

- A fuller `<description>`: what it does, that it needs Neovim 0.9+ installed separately, and the
  supported Eclipse (2024-12 / 4.34 or newer).
- Keep `<license>` as it is (EPL-2.0, matching the listing).

**Listing text and fields** (drafted in this file before submitting, so they are reviewed like code).
The rows follow marketplace.eclipse.org's *Create Solution* form as it was on 2026-10-24, top to
bottom; `*` marks a field the form requires.

| Form field | Value |
|------------|-------|
| Solution Name `*` | Neovim for Eclipse (the form asks for the product name only, no tagline) |
| Solution URL | https://github.com/Jm6CnDDXBwFTakS/neovim4eclipse |
| Organization Name | empty (a personal project) |
| Eclipse Foundation Member Organization `*` | No |
| Solution Logo | [docs/images/marketplace-logo.png](../images/marketplace-logo.png): the form scales every logo to 110×80 pixels (png, gif or jpg), so this is `logo.svg` centered on white at exactly that size, rendered with headless Firefox and scaled down (below) |
| Summary | Real Neovim inside Eclipse text editors: motions, operators, macros, registers and `:` commands, with your own `init.lua`. |
| Solution Description `*` | the text below the table |
| Status | Beta, or the closest choice the list offers: the README calls it an early release |
| License Type | EPL 2.0 |
| Solution Type | This product requires an existing Eclipse installation to function |
| Support URL | https://github.com/Jm6CnDDXBwFTakS/neovim4eclipse/issues |
| RSS Feed | https://github.com/Jm6CnDDXBwFTakS/neovim4eclipse/releases.atom (GitHub's feed of the releases) |
| Categories `*` (5 or fewer) | Editor, plus whatever else in the list fits a key-binding/editing tool; not checked yet which names the list has |
| Markets `*` | Tools |
| Tags | vim, neovim, vi, modal editing, keybindings |
| **Solution version:** Version number | 0.1.8 (update with each release, or leave empty: the update site always offers the latest) |
| Update Site URL `*` | https://jm6cnddxbwftaks.github.io/neovim4eclipse/ (the composite site; never changes) |
| Supported Eclipse Release(s) `*` | 2024-12 (4.34) through 2026-09 (4.41). Not 2026-12 (4.42): it is listed but not yet released; tick it after testing on it |
| Supported Platform(s) `*` | Windows, Mac, Linux/GTK |
| Minimum Java Version `*` | Java 21 |
| Feature ID `*` / Install state | `io.github.jm6cnddxbwftaks.neovim4eclipse.feature` / Required (the feature's ID, without the `.feature.group` suffix p2 adds) |
| Screenshot (up to 4; png, gif or jpg; 16 MB) | in this order: [demo.gif](../images/demo.gif) (2.7 MB, the README's demo), [screenshot-visual-mode.png](../images/screenshot-visual-mode.png), [screenshot-command-line.png](../images/screenshot-command-line.png), [screenshot-preferences.png](../images/screenshot-preferences.png) (all in `docs/images/`; how they are made: *Images*, below) |
| Additional Editors | none |

Solution Description (the form's editor takes formatted text; paste this and set the list as a
list):

> Neovim for Eclipse embeds a real Neovim (`nvim --embed`) in Eclipse's text editors, so Vim's
> modal editing works on the file you see in the IDE: motions, operators, text objects, visual mode,
> counts, registers, macros, marks, undo and `:` commands such as `:%s`, with your own `init.lua`.
> Neovim owns the editing; Eclipse keeps owning files, rendering, content
> assist and its own commands. `gd` and `K` use Eclipse's Open Declaration and hover, `:w`, `:q`,
> `:e` and `gt` act on Eclipse's editors, and the `"+` register is the system clipboard.
>
> Requirements:
> - Neovim 0.9 or newer, installed separately (`nvim` on the PATH, or its path set in
>   Window › Preferences › Neovim); the plug-in does not include Neovim.
> - Eclipse 2024-12 (4.34) or newer, on Java 21 or newer.
>
> Releases are signed with the project's PGP key; check that Eclipse shows the fingerprint
> `A91F 53D8 931B 7FD0 D146  8618 6CC9 E446 147B 063C` before trusting it.
>
> Known limitations, worth reading before you install (for example, Neovim is attached to the
> active editor only, and file and tab commands are a fixed set):
> https://github.com/Jm6CnDDXBwFTakS/neovim4eclipse#known-limitations
>
> Documentation and issues: https://github.com/Jm6CnDDXBwFTakS/neovim4eclipse

**Known limitations, linked straight from the description** (README's `#known-limitations`
anchor, which GitHub makes from the heading; renaming the heading breaks the link): users who know
the limits before installing review and report fewer surprises.

**Requirements, stated up front in the description:** Neovim 0.9+ must be installed and on the
`PATH` (or set in *Preferences → Neovim*); the plug-in does not ship Neovim.

**Images:**

- The **logo**, [docs/images/logo.svg](../images/logo.svg) *(decided)*: an N whose left stem is a
  colon (green, Neovim) and whose right stem is Eclipse's I-beam caret (indigo), so it also reads as
  "VI" with a hint of "E". It borrows nothing from the Eclipse or Neovim logos, which are their
  owners' marks; an early draft with the Eclipse logo's stripes was dropped for that reason. With
  the name beside it in the logo's green (`logo-wordmark.svg`, one file for light and dark themes)
  it heads the README. For the listing: `marketplace-logo.png`, 110×80 pixels, the size the form
  scales every logo to. To render it again after changing `logo.svg` (no SVG tool is needed besides
  Firefox and Python's Pillow):

  ```bash
  printf '<body style="margin:0;background:#fff;width:440px;height:320px;display:flex;align-items:center;justify-content:center"><img src="logo.svg" width="304" height="304">' > docs/images/page.html
  firefox --headless --window-size=440,320 --screenshot /tmp/big.png "file://$PWD/docs/images/page.html"
  rm docs/images/page.html
  python3 -c "from PIL import Image; Image.open('/tmp/big.png').convert('RGB').resize((110, 80), Image.LANCZOS).save('docs/images/marketplace-logo.png', optimize=True)"
  ```
- **Screenshots**, taken by `ScreenshotCaptureTest` in the UI test module rather than by hand, so
  they can be taken again after the UI changes: a Java editor in visual line mode (the mode in the
  status bar), the `:` box with a substitution typed, and the Neovim preference page. It runs only
  with its own profile, which adds the Java tools for a highlighted Java editor:

  ```bash
  xvfb-run -a -s "-screen 0 1600x1000x24" ./mvnw verify -Pscreenshots -Dnvim.path=/opt/nvim-linux-x86_64/bin/nvim
  cp tests/io.github.jm6cnddxbwftaks.neovim4eclipse.tests/target/screenshots/01-visual-mode.png docs/images/screenshot-visual-mode.png
  cp tests/io.github.jm6cnddxbwftaks.neovim4eclipse.tests/target/screenshots/02-command-line.png docs/images/screenshot-command-line.png
  cp tests/io.github.jm6cnddxbwftaks.neovim4eclipse.tests/target/screenshots/03-preferences.png docs/images/screenshot-preferences.png
  ```

  Xvfb's `-screen` gives it room for the 1280×800 window in full color (its default is 640×480 at
  8 bits). They show Eclipse's default light theme on Linux/GTK, without a window title bar.

  The preference page shows `-Dnvim.path` in its field and its *Found:* note, so use a Neovim
  (`NeovimVersion.MINIMUM` or newer, or the note turns red) at a path that names no one: not one under
  your home folder. Without write access to `/opt`, a private mount namespace shows your own Neovim
  there for this one build, changing nothing on the system (Linux; used for the 2026-10-30 retake):

  ```bash
  NV=$(readlink -f ~/.local/share/neovim4eclipse-plugin/neovim/current)   # its top folder, e.g. nvim-linux-arm64_0.12.6
  unshare -rm sh -c "mount -t tmpfs tmpfs /opt && mkdir /opt/nvim-linux-arm64 && mount --bind '$NV' /opt/nvim-linux-arm64 &&
    exec unshare -U --map-user=$(id -u) --map-group=$(id -g) sh -c \"xvfb-run -a -s '-screen 0 1600x1000x24' ./mvnw verify -Pscreenshots -Dnvim.path=/opt/nvim-linux-arm64/bin/nvim\""
  ```

  The outer `unshare -rm` is root in a new user and mount namespace, which may mount; the inner one
  maps you back to your own user id, so Maven finds `~/.m2` and writes files you own.
- The README demo GIF, recorded by hand.

## Phase 3: account, submission, test

All maintainer steps; an AI assistant cannot create accounts or sign in.

1. Create an **Eclipse Foundation account** at accounts.eclipse.org. *(Done, 2026-10-24.)*
2. On marketplace.eclipse.org, log in, *Add Content → Solution*, and fill in the fields from phase 2.
   The form's fields, as of 2026-10-24, are in phase 2's table.
3. Listings go through **moderation** before they appear (the Marketplace quickstart says about one
   business day).
4. Test the listing from a clean Eclipse on **Windows, Linux and macOS**: *Help → Eclipse
   Marketplace…*, search "Neovim", *Install*, restart, open a file. Then *Check for Updates* after
   the next release, to see the composite site deliver it.
5. Add the badge and drag-to-install link to the README.

Questions about listings go to marketplace@eclipse-foundation.org.

## Phase 4 (later): PGP signing

Planned in detail on 2026-10-22, against `release/0.1.3` at `64abd26` (Tycho 5.0.4).

Today the jars are unsigned, so Eclipse's install wizard asks the user to trust *unsigned content*.
Two ways to sign:

- **PGP** *(decided)*: `tycho-gpg-plugin` signs the artifacts and records the signatures in
  `artifacts.jar`; p2 checks PGP signatures since Eclipse 4.21 (2021-09). Eclipse then shows *our
  key* in the trust dialog and asks the user to trust it: still one click, but it names who signed,
  and a tampered jar fails the install.
- **jarsigner with a code-signing certificate**: no prompt at all, but a certificate from a
  certificate authority costs money every year. The Eclipse Foundation signs only its own
  projects' jars. Not planned.

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

### The key *(decided)*

| | | |
|-|-|-|
| Identity | `Neovim for Eclipse releases <58570069+Jm6CnDDXBwFTakS@users.noreply.github.com>` | a key used only for releases, not the maintainer's personal key: if it leaks, revoking it touches nothing else. The GitHub *noreply* address, because a public key is public forever |
| Algorithm | **Ed25519** | modern, small, fast. Not yet confirmed that p2's verifier accepts EdDSA keys, so the first signed release is preceded by a test (experiment S1); if it fails, RSA 4096 instead |
| Expiry | **none**; a revocation certificate kept offline | Tycho copies the public key, with its expiry date, into each release's metadata, and p2 checks expiry (`p2.pgp.verifyExpiration`). An expiry date would make old releases warn or fail after it, even if the key were extended later. If the key leaks, publishing the revocation certificate retires it; p2 checks revocation too (`p2.pgp.verifyRevocation`) |
| Passphrase | yes | the key is stored in GitHub encrypted, but a passphrase means a leaked secret alone cannot sign |

### Where the secrets live *(decided)*

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
                                                      │  4. zip it again, upload it for publish
```

- **Build stays the same.** `build.yml` gets no secrets and no signing profile, so `main`, pull
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
  repository). A release with a missing or wrong signature never reaches `publish`.
- **The update site** needs no change: the composite simply has signed and unsigned children. Old
  releases (0.1.0–0.1.3) stay unsigned; re-signing them would change published artifacts.
- **Smoke test** (`update-site.yml`): the director install from the live site also checks the
  signatures, since a bad one aborts it. Whether the headless director refuses *untrusted* keys
  (there is no dialog to answer) is experiment S3; if it does, the smoke test passes our key as
  trusted.

### The plug-in vouches for its own key *(decided)*

p2's extension point `org.eclipse.equinox.p2.engine.pgp` lets an installed plug-in declare keys as
trusted. The first install still shows the dialog (nothing is installed yet); every later update
installs without asking. In `plugin.xml`:

```xml
<extension point="org.eclipse.equinox.p2.engine.pgp">
  <trustedKeys path="keys/release-signing-key.asc"/>
</extension>
```

The file is the same public key as `releng/signing-key.asc` (a test checks that the two are equal,
like `VersionConsistencyTest`), listed in `build.properties`' `bin.includes`. The extension point
is older than our minimum Eclipse, 4.34 (its schema says p2 2.7.300; Eclipse's notes date trusted-key
contributions to 4.23).

### Publishing the public key

So that anyone can check that a release was signed by this project, the fingerprint is printed in
the README (*Install*), in [SECURITY.md](../../SECURITY.md), and on the update site's page
(`releng/update-site.sh`), and the key is uploaded to [keys.openpgp.org](https://keys.openpgp.org)
(which asks the address to confirm; a noreply address cannot, so the key is findable there by
fingerprint only). The README's install steps change from "tick the unsigned entry" to "check that
the key's fingerprint is …, then trust it".

### The maintainer's part

An AI assistant must not create or handle the private key, so these steps are the maintainer's, on
their own machine. The commands, with GnuPG 2.2 or newer:

```bash
# 1. The key: Ed25519, signing only, no expiry. Prompts for the passphrase.
gpg --quick-generate-key "Neovim for Eclipse releases <58570069+Jm6CnDDXBwFTakS@users.noreply.github.com>" ed25519 sign never

# 2. Its fingerprint (40 hex digits), for the commands below and the docs.
gpg --list-keys --with-colons "Neovim for Eclipse releases" | awk -F: '/^fpr/ {print $10; exit}'

# 3. Keep offline (a USB stick, a password manager): the secret key and the revocation certificate.
gpg --armor --export-secret-keys FINGERPRINT > release-signing-secret.asc
gpg --output release-signing-revoke.asc --gen-revoke FINGERPRINT

# 4. The public key, for the repository (the assistant commits it).
gpg --armor --export FINGERPRINT > releng/signing-key.asc

# 5. The secrets, into the release environment (gh asks for the passphrase interactively).
gh secret set MAVEN_GPG_KEY --env release < release-signing-secret.asc
gh secret set MAVEN_GPG_PASSPHRASE --env release

# 6. The key server.
gpg --keyserver hkps://keys.openpgp.org --send-keys FINGERPRINT
```

Step 5 needs the `release` environment to exist first, with its tag rule (the assistant can create
both with `gh api`, since that holds no secret).

### Experiments, before the first signed release

Run on 2026-10-24, locally (Linux aarch64), on `feature/pgp-signing` at `c93ec7d`, with a
throwaway Ed25519 key in a temporary `GNUPGHOME` and the unsigned ZIP from `./mvnw verify
-DskipTests`. Eclipse 4.34 platform, run with Java 21 (4.34's `eclipse.ini` sets
`-Djava.security.manager=allow`, which Java 24 and newer refuse at start-up).

- **S1. Ed25519 in p2: works headless.** The director installed the Ed25519-signed site. A control
  showed that p2 really checks the signature: replacing the bundle's `pgp.signatures` with a valid
  signature *of other bytes* made the install fail (`Can't download artifact osgi.bundle,…`), while
  the same `artifacts.jar` rewritten with the original signature installed. The trust dialog was
  checked by the maintainer with the real key on 2026-10-24: a ZIP signed locally by `releng/sign.sh`,
  installed through *Install New Software › Archive…*, showed the key and its fingerprint.
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
  by an unknown key succeeded, so the smoke test needs no change.
- **S4. Trusted-key extension: works (checked 2026-10-25 on macOS).** The extension is built; its
  element and attribute names were checked against p2's `CertificateChecker` in Eclipse 4.34,
  which reads `trustedKeys`/`path` with `Bundle.getEntry`. The test: no plug-in installed and no
  trust remembered, then 0.1.4 installed from the live site, then *Check for Updates* to 0.1.5.
  What it showed:
  - **The first install asks.** Installing 0.1.4 showed the trust dialog, accepted *without*
    "remember". Expected: before the plug-in is installed, nothing on the machine vouches for
    its key, so a user's first install always shows this dialog once.
  - **The installed plug-in vouches for its key.** Afterwards *Preferences › Install/Update ›
    Trust* listed the project key with no *Remove* button: a key that an installed plug-in
    declares (this extension), which the user cannot remove, unlike one they chose to remember.
  - **Updates do not ask.** The update to 0.1.5 showed no dialog.

  An earlier check (2026-10-24, reinstalling a local build) saw no dialog the second time too, but
  a trust remembered from the first install could have explained that; this test rules it out.

### Commits

1. *(Built.)* `releng/sign/pom.xml`, `releng/sign.sh`, `releng/verify-signatures.sh`, the `sign`
   job, which fails while `releng/signing-key.asc` is missing; tested locally with a throwaway key
   (S1–S3). The `release` environment is created by `gh api` (no commit) before the maintainer's
   step 5.
2. *(Built.)* Key `A91F 53D8 931B 7FD0 D146  8618 6CC9 E446 147B 063C`, generated by the
   maintainer on 2026-10-24 (Ed25519, no expiry) and uploaded to keys.openpgp.org.
   The real public key (`releng/signing-key.asc`, after the maintainer's steps), the trusted-key
   extension and its test (S4), the fingerprint in README, SECURITY.md and the update site page;
   PACKAGING.md and CONTRIBUTING.md (*Releasing*) updated; CHANGELOG.

The next release after commit 2 is the first signed one.

## Every release afterwards

Nothing new by hand: the tag's Release workflow publishes the GitHub Release and rebuilds the site,
so *Check for Updates* and the Marketplace see the new version. Update the listing only when its
facts change (supported Eclipse versions, description), and answer reviews and questions there.

Sources: [Eclipse Marketplace quickstart](https://marketplace.eclipse.org/quickstart). It is short and
older than this plan; the *Add Content* form is the final word on what a listing needs.
