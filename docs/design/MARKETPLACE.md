# Plan: listing on the Eclipse Marketplace

Status: **planned**, nothing built yet. Written on `release/0.1.2` (after 0.1.1). Decisions made with
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
| 4 | Later: PGP-sign the artifacts | code + maintainer | medium |

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

## Phase 1: the update site on GitHub Pages

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

**Listing text and fields** (drafted in this file before submitting, so they are reviewed like code):

| Field | Draft |
|-------|-------|
| Name | Neovim for Eclipse |
| Short description | Real Neovim inside Eclipse text editors: motions, operators, macros, registers and `:` commands, with your own `init.lua`. |
| Update site URL | the Phase 1 root URL |
| Feature ID | `io.github.jm6cnddxbwftaks.neovim4eclipse.feature` |
| License | EPL-2.0 |
| Eclipse versions | 2024-12 (4.34) and newer |
| Platforms | Windows, Linux, macOS |
| Categories / tags | Editor; tags `vim`, `neovim`, `modal editing`, `keybindings` |
| Links | Homepage and support: the GitHub repository and its issues |

**Requirements, stated up front in the description:** Neovim 0.9+ must be installed and on the
`PATH` (or set in *Preferences → Neovim*); the plug-in does not ship Neovim.

**Images:**

- A **logo** of our own; Claude drafts SVG options for the maintainer to pick from *(decided)*.
  Not the Eclipse logo (a trademark). The Neovim logo is, as far as we know, licensed CC BY 3.0, so
  it could be used with attribution; check its license file at the time.
- Screenshots and the README demo GIF.

## Phase 3: account, submission, test

All maintainer steps; an AI assistant cannot create accounts or sign in.

1. Create an **Eclipse Foundation account** at accounts.eclipse.org.
2. On marketplace.eclipse.org, log in, *Add Content → Solution*, and fill in the fields from phase 2.
   The form may ask for things this plan does not list (image sizes, more fields); note them here.
3. Listings go through **moderation** before they appear (the Marketplace quickstart says about one
   business day).
4. Test the listing from a clean Eclipse on **Windows, Linux and macOS**: *Help → Eclipse
   Marketplace…*, search "Neovim", *Install*, restart, open a file. Then *Check for Updates* after
   the next release, to see the composite site deliver it.
5. Add the badge and drag-to-install link to the README.

Questions about listings go to marketplace@eclipse-foundation.org.

## Phase 4 (later): PGP signing

Today the jars are unsigned, so Eclipse asks the user to trust unsigned content. Two ways to sign:

- **PGP** *(decided, later)*: `tycho-gpg-plugin` signs the artifacts and records the signatures in
  `artifacts.jar`; p2 checks PGP signatures since 2022-09. The release workflow needs the private
  key and passphrase as repository secrets. Eclipse then shows the key and asks the user to trust
  *it*, which still needs a click, but names who signed. Publish the public key (repository, a key
  server) so users can check it.
- **jarsigner with a code-signing certificate**: no prompt at all, but a certificate from a
  certificate authority costs money every year. The Eclipse Foundation signs only its own
  projects' jars. Not planned.

## Every release afterwards

Nothing new by hand: the tag's Release workflow publishes the GitHub Release and rebuilds the site,
so *Check for Updates* and the Marketplace see the new version. Update the listing only when its
facts change (supported Eclipse versions, description), and answer reviews and questions there.

Sources: [Eclipse Marketplace quickstart](https://marketplace.eclipse.org/quickstart). It is short and
older than this plan; the *Add Content* form is the final word on what a listing needs.
