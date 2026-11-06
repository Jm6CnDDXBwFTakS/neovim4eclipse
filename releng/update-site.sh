#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
# SPDX-License-Identifier: EPL-2.0
#
# Builds the permanent p2 update site from release ZIPs: a *composite repository* whose children
# are the releases, one folder each. Users add its URL once (Help > Install New Software > Add >
# Location), and Check for Updates finds every later release under the same URL.
#
#   releng/update-site.sh <dir with neovim4eclipse-<version>.zip files> <output dir> [<public key>]
#
# Example (what .github/workflows/update-site.yml runs, after downloading every release's ZIP):
#
#   releng/update-site.sh dist site releng/signing-key.asc
#
# With a public key, every release must carry valid PGP signatures by that key
# (releng/verify-signatures.sh) or nothing is built, and releases from before signing began
# (FIRST_SIGNED, 0.1.4) are left out. The whole ZIP is checked too, before it is unpacked, as
# the jar signatures do not cover content.jar (what p2 installs and how; releng/verify-zip.sh):
# from FIRST_ZIP_SIGNED (0.2.1) by its signature neovim4eclipse-<version>.zip.asc, which must lie
# beside it, and before that by the hash pinned in releng/release-zips.sha256. Why: the site is rebuilt from the release ZIPs on GitHub at
# every release, and a ZIP replaced there later (by anyone holding a token that may write the
# repository) would otherwise go out unchecked; the release workflow checks each ZIP only once,
# before it publishes it (0.2.1 security review). Leaving out the unsigned 0.1.0 to 0.1.3 also
# means the site never asks to install unsigned content, which a user might then accept for a
# forged jar too.
#
#   site/
#     index.html               a page for people: the URL to add and the versions inside
#     p2.index                 tells p2 to read the composite files first (saves it some 404s)
#     compositeContent.xml     the children, for the metadata (what can be installed)
#     compositeArtifacts.xml   the children, for the artifacts (the jars)
#     releases/0.1.0/          the 0.1.0 ZIP, unpacked: content.jar, artifacts.jar, plugins/, ...
#     releases/0.1.1/ ...
#
# The output is rebuilt from scratch every time, so the site holds exactly the ZIPs given: a
# release that is deleted on GitHub disappears from the site at the next run. Background and the
# decisions behind it: docs/design/MARKETPLACE.md, phase 1.

set -euo pipefail

if [ $# -lt 2 ] || [ $# -gt 3 ]; then
  echo "usage: $0 <dir with neovim4eclipse-<version>.zip> <output dir> [<public key>]" >&2
  exit 2
fi
zips=$1
out=$2
key=${3:-}
# The first release signed with the project's key; older ones are left out when a key is given.
FIRST_SIGNED=0.1.4
# The first release whose whole ZIP is signed (neovim4eclipse-<version>.zip.asc).
FIRST_ZIP_SIGNED=0.2.1
here=$(cd "$(dirname "$0")" && pwd)

# Whether version $1 comes before version $2 (sort -V puts the older one first).
older_than() {
  [ "$1" != "$2" ] && [ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | head -n 1)" = "$1" ]
}

# The SHA-256 of file $1: sha256sum on Linux, shasum on macOS. Read from standard input, as GNU
# sha256sum starts its line with a backslash for a name holding one (a Windows path).
sha256_of() {
  if command -v sha256sum > /dev/null; then sha256sum < "$1"; else shasum -a 256 < "$1"; fi | cut -d ' ' -f 1
}

# Checks the whole ZIP of version $1 (see the top), or stops everything.
check_zip() {
  local file="$zips/neovim4eclipse-$1.zip" pinned actual
  if older_than "$1" "$FIRST_ZIP_SIGNED"; then
    pinned=$(awk -v name="neovim4eclipse-$1.zip" '$2 == name { print $1 }' "$here/release-zips.sha256")
    actual=$(sha256_of "$file")
    if [ -z "$pinned" ] || [ "$actual" != "$pinned" ]; then
      echo "error: neovim4eclipse-$1.zip has SHA-256 $actual, not the ${pinned:-(none pinned)} in releng/release-zips.sha256" >&2
      exit 1
    fi
    echo "ok: neovim4eclipse-$1.zip (pinned SHA-256)"
  else
    "$here/verify-zip.sh" "$file" "$key"
  fi
}

# Versions from the file names, oldest first (sort -V knows that 0.1.10 comes after 0.1.9).
# Written without mapfile and GNU find so that it also runs with macOS's bash 3.2.
# shellcheck disable=SC2207  # versions never contain spaces (checked below)
versions=($(
  for zip in "$zips"/neovim4eclipse-*.zip; do
    [ -e "$zip" ] || continue  # the pattern itself, when nothing matches
    name=${zip##*/}
    name=${name#neovim4eclipse-}
    echo "${name%.zip}"
  done | sort -V))
if [ -n "$key" ]; then
  [ -f "$key" ] || { echo "error: public key $key not found" >&2; exit 1; }
  kept=()
  for version in ${versions[@]+"${versions[@]}"}; do
    # sort -V puts the older one first: version is kept if FIRST_SIGNED comes first (or they match).
    if [ "$(printf '%s\n%s\n' "$FIRST_SIGNED" "$version" | sort -V | head -n 1)" = "$FIRST_SIGNED" ]; then
      kept+=("$version")
    else
      echo "left out $version: released before signing began ($FIRST_SIGNED)"
    fi
  done
  versions=(${kept[@]+"${kept[@]}"})
fi
if [ ${#versions[@]} -eq 0 ]; then
  echo "error: no neovim4eclipse-<version>.zip in $zips" >&2
  exit 1
fi

rm -rf "$out"
mkdir -p "$out/releases"

for version in "${versions[@]}"; do
  case $version in
    *[!0-9.]* | '' )
      echo "error: '$version' is not a release version (neovim4eclipse-$version.zip)" >&2
      exit 1 ;;
  esac
  dir="$out/releases/$version"
  if [ -n "$key" ]; then
    check_zip "$version"  # before unzip reads it
  fi
  unzip -q "$zips/neovim4eclipse-$version.zip" -d "$dir"
  # The same check the Release workflow makes: a ZIP without content.jar is not an update site.
  if [ ! -f "$dir/content.jar" ] || [ ! -f "$dir/artifacts.jar" ]; then
    echo "error: neovim4eclipse-$version.zip is not a p2 update site (no content.jar/artifacts.jar)" >&2
    exit 1
  fi
  if [ -n "$key" ]; then
    "$here/verify-signatures.sh" "$dir" "$key"  # fails the whole build on any bad jar or extra file
  fi
  echo "added $version"
done

# p2 caches repositories by this timestamp (milliseconds); a new one makes it read them again.
timestamp=$(($(date +%s) * 1000))

# Writes one composite file. $1: file name, $2: the processing instruction, $3: repository type.
# p2.atomic.composite.loading=true makes p2 fail the whole load when a child is missing, instead
# of quietly offering fewer versions.
write_composite() {
  {
    echo "<?xml version='1.0' encoding='UTF-8'?>"
    echo "<?$2 version='1.0.0'?>"
    echo "<repository name='Neovim for Eclipse' type='$3' version='1.0.0'>"
    echo "  <properties size='2'>"
    echo "    <property name='p2.timestamp' value='$timestamp'/>"
    echo "    <property name='p2.atomic.composite.loading' value='true'/>"
    echo "  </properties>"
    echo "  <children size='${#versions[@]}'>"
    for version in "${versions[@]}"; do
      echo "    <child location='releases/$version'/>"
    done
    echo "  </children>"
    echo "</repository>"
  } > "$out/$1"
}
write_composite compositeContent.xml compositeMetadataRepository \
  org.eclipse.equinox.internal.p2.metadata.repository.CompositeMetadataRepository
write_composite compositeArtifacts.xml compositeArtifactRepository \
  org.eclipse.equinox.internal.p2.artifact.repository.CompositeArtifactRepository

# "!" at the end of each list means: only these files, do not look for others.
cat > "$out/p2.index" <<'EOF'
version=1
metadata.repository.factory.order=compositeContent.xml,\!
artifact.repository.factory.order=compositeArtifacts.xml,\!
EOF

# A plain page for anyone who opens the URL in a browser.
latest=${versions[${#versions[@]}-1]}
{
  cat <<'EOF'
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Neovim for Eclipse update site</title>
<style>
  body { font: 16px/1.6 system-ui, sans-serif; max-width: 42rem; margin: 2rem auto; padding: 0 1rem; color: #1c2029; }
  code { background: #eef1ea; padding: 2px 6px; border-radius: 4px; }
  h1 { color: #3c8f3f; }
</style>
</head>
<body>
<h1>Neovim for Eclipse</h1>
<p>This is a p2 update site, read by Eclipse rather than by a browser. To install the plug-in:</p>
<ol>
  <li>In Eclipse: <i>Help → Install New Software… → Add…</i></li>
  <li>Paste this page's address as the <i>Location</i>, name it <i>Neovim for Eclipse</i>, and choose <i>Add</i>.</li>
  <li>Tick <b>Neovim for Eclipse</b> and follow the wizard (README, "Install", has every step).</li>
  <li>When Eclipse asks to trust the PGP key <i>Neovim for Eclipse releases</i>, check that its fingerprint is
      <code>A91F 53D8 931B 7FD0 D146  8618 6CC9 E446 147B 063C</code>, then trust it.</li>
</ol>
EOF
  echo "<p>Latest version: <b>$latest</b>. Every release is here: ${versions[*]}.</p>"
  echo '<p>Source, documentation and issues: <a href="https://github.com/Jm6CnDDXBwFTakS/neovim4eclipse">github.com/Jm6CnDDXBwFTakS/neovim4eclipse</a></p>'
  echo '</body>'
  echo '</html>'
} > "$out/index.html"

echo "site in $out: ${#versions[@]} release(s), latest $latest"
