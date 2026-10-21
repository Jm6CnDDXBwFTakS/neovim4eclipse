#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
# SPDX-License-Identifier: EPL-2.0
#
# Builds the permanent p2 update site from release ZIPs: a *composite repository* whose children
# are the releases, one folder each. Users add its URL once (Help > Install New Software > Add >
# Location), and Check for Updates finds every later release under the same URL.
#
#   releng/update-site.sh <dir with neovim4eclipse-<version>.zip files> <output dir>
#
# Example (what .github/workflows/update-site.yml runs, after downloading every release's ZIP):
#
#   releng/update-site.sh dist site
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

if [ $# -ne 2 ]; then
  echo "usage: $0 <dir with neovim4eclipse-<version>.zip> <output dir>" >&2
  exit 2
fi
zips=$1
out=$2

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
  unzip -q "$zips/neovim4eclipse-$version.zip" -d "$dir"
  # The same check the Release workflow makes: a ZIP without content.jar is not an update site.
  if [ ! -f "$dir/content.jar" ] || [ ! -f "$dir/artifacts.jar" ]; then
    echo "error: neovim4eclipse-$version.zip is not a p2 update site (no content.jar/artifacts.jar)" >&2
    exit 1
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
</ol>
EOF
  echo "<p>Latest version: <b>$latest</b>. Every release is here: ${versions[*]}.</p>"
  echo '<p>Source, documentation and issues: <a href="https://github.com/Jm6CnDDXBwFTakS/neovim4eclipse">github.com/Jm6CnDDXBwFTakS/neovim4eclipse</a></p>'
  echo '</body>'
  echo '</html>'
} > "$out/index.html"

echo "site in $out: ${#versions[@]} release(s), latest $latest"
