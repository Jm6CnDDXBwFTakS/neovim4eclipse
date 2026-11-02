#!/bin/sh
# SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
# SPDX-License-Identifier: EPL-2.0
#
# Downloads the Neovim release archive that the plug-in's "Get Neovim" installs, checks its size and
# SHA-256 against the plug-in's data file, extracts it, and prints the path of its nvim program.
# CI uses it, so CI tests exactly the Neovim users get, and checks what it downloads.
#
# The version, asset names and hashes come from util/neovim-downloads.properties (rewritten by
# releng/UpdateNeovimDownloads.java; see docs/UPGRADING.md, "Neovim"); nothing here names a version.
#
# Usage:   releng/install-neovim.sh <code> <folder>
#   code     a platform code from the data file: lx la mx ma wx wa
#            (OS letter l Linux, m macOS, w Windows; processor x x86_64, a arm64)
#   folder   where to extract; the archive's own top folder is created inside it
# Example: releng/install-neovim.sh lx "$RUNNER_TEMP"
#          prints /home/runner/work/_temp/nvim-linux-x86_64/bin/nvim
# Runs in sh on Linux and macOS and in Git Bash on Windows (sha256sum or shasum, curl, tar, unzip).

set -eu

if [ $# -ne 2 ]; then
    echo "Usage: $0 <code> <folder>" >&2
    exit 2
fi
code=$1
folder=$2
data="$(dirname "$0")/../plugins/io.github.jm6cnddxbwftaks.neovim4eclipse/src/io/github/jm6cnddxbwftaks/neovim4eclipse/util/neovim-downloads.properties"

# value KEY: the value of KEY=... in the data file (no spaces in these values).
value() {
    v=$(sed -n "s/^$1=//p" "$data" | tr -d '\r')
    if [ -z "$v" ]; then
        echo "$data has no $1" >&2
        exit 1
    fi
    printf '%s' "$v"
}

version=$(value version)
asset=$(value "$code.asset")
size=$(value "$code.size")
sha=$(value "$code.sha256")
url=$(value url | sed "s/{version}/$version/; s/{asset}/$asset/")

archive="$folder/$asset"
echo "Neovim $version for $code: $url" >&2
curl -fsSL -o "$archive" "$url"

actual_size=$(wc -c < "$archive" | tr -d ' ')
if command -v sha256sum >/dev/null 2>&1; then
    actual_sha=$(sha256sum "$archive" | cut -d' ' -f1)
else
    actual_sha=$(shasum -a 256 "$archive" | cut -d' ' -f1)   # macOS
fi
if [ "$actual_size" != "$size" ] || [ "$actual_sha" != "$sha" ]; then
    echo "$asset does not match the data file:" >&2
    echo "  expected $size bytes, sha256 $sha" >&2
    echo "  got      $actual_size bytes, sha256 $actual_sha" >&2
    exit 1
fi
echo "  $size bytes, sha256 $sha (matches)" >&2

case "$asset" in
    *.zip) unzip -q "$archive" -d "$folder"; exe=nvim.exe; top=${asset%.zip} ;;
    *)     tar -xzf "$archive" -C "$folder"; exe=nvim;     top=${asset%.tar.gz} ;;
esac
program="$folder/$top/bin/$exe"
"$program" --version | head -1 >&2
printf '%s\n' "$program"
