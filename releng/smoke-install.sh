#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
# SPDX-License-Identifier: EPL-2.0
#
# Installs Neovim for Eclipse from an update site into an Eclipse installation, the way
# Help > Install New Software does, and checks that the expected version is installed. It is the
# smoke test after each deployment of the update site (.github/workflows/update-site.yml).
#
#   releng/smoke-install.sh <eclipse dir> <update site URL> [<expected version>]
#
# Example, against a site built locally by releng/update-site.sh:
#
#   releng/smoke-install.sh ~/eclipse-4.34 file:///tmp/site 0.1.2
#
# How: p2's *director* application, which every Eclipse contains, installs an installable unit
# (IU) from a repository without opening a window. A feature's IU is its ID plus ".feature.group".
# Only the update site is given as a repository, nothing from download.eclipse.org: if the plug-in
# needed anything the Eclipse installation lacks, the install fails. Run it on the oldest Eclipse
# the plug-in supports (4.34, 2024-12) and that also tests the "2024-12 or newer" promise.
#
# The Eclipse installation is changed (the plug-in is added): use a throwaway copy.

set -euo pipefail

if [ $# -lt 2 ] || [ $# -gt 3 ]; then
  echo "usage: $0 <eclipse dir> <update site URL> [<expected version>]" >&2
  exit 2
fi
eclipse=$1
site=$2
expected=${3:-}
feature=io.github.jm6cnddxbwftaks.neovim4eclipse.feature.feature.group

# The launcher is "eclipse" on Linux and Windows, and inside Eclipse.app on macOS.
launcher=$eclipse/eclipse
[ -x "$launcher" ] || launcher=$eclipse/Contents/MacOS/eclipse
if [ ! -x "$launcher" ]; then
  echo "error: no Eclipse launcher in $eclipse" >&2
  exit 1
fi

# The Java to run Eclipse with: $JAVA_HOME when set (as actions/setup-java sets it), otherwise
# whatever the launcher finds. Use Java 21 for Eclipse 4.34: its eclipse.ini passes
# -Djava.security.manager=allow, which Java 24 and newer refuse ("Enabling a Security Manager is
# not supported"), so the JVM stops before Eclipse starts.
vm=()
if [ -n "${JAVA_HOME:-}" ]; then
  vm=(-vm "$JAVA_HOME/bin/java")
fi

# SDKProfile is the p2 profile of the Eclipse Platform and SDK downloads; it records what is
# installed, so the director adds the plug-in to that installation and not to a new one.
director() {
  "$launcher" "${vm[@]}" -nosplash -consoleLog -application org.eclipse.equinox.p2.director \
    -destination "$eclipse" -profile SDKProfile "$@"
}

echo "installing $feature from $site"
director -repository "$site" -installIU "$feature"

# -listInstalledRoots prints "id/version" for each root the profile has, for example
# io.github.jm6cnddxbwftaks.neovim4eclipse.feature.feature.group/0.1.2
installed=$(director -listInstalledRoots | grep "^$feature/" || true)
if [ -z "$installed" ]; then
  echo "error: $feature is not installed after the install" >&2
  exit 1
fi
echo "installed: $installed"
if [ -n "$expected" ] && [ "${installed#"$feature"/}" != "$expected" ]; then
  echo "error: expected version $expected, the site installed ${installed#"$feature"/}" >&2
  exit 1
fi
