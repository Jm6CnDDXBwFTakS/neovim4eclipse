#!/bin/sh
# SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
# SPDX-License-Identifier: EPL-2.0
#
# Prints the section of CHANGELOG.md for one version, the release notes of that version, and fails
# when the changelog has no such section or it is empty. The release workflow runs it twice: first,
# before anything is built, so a forgotten changelog stops the release at once; then to write the
# notes of the GitHub Release.
#
# A section is everything from its heading to the next "## [" heading. For version 0.1.0:
#
#   ## [0.1.0] - 2026-11-02      <- the heading itself is left out (the release has its own title)
#
#   ### Added                    <- printed
#   - ...                        <- printed
#
#   ## [0.0.9] - 2026-10-01      <- next section: stop
#
# Usage: releng/release-notes.sh 0.1.0 [CHANGELOG.md]
# Try it locally with any version in the file, for example "Unreleased".
set -eu

if [ $# -lt 1 ] || [ $# -gt 2 ]; then
    echo "usage: $0 <version> [changelog]" >&2
    exit 2
fi
version=$1
changelog=${2:-CHANGELOG.md}

# index(...) == 1: the line starts with the heading, so "## [0.1.0]" does not match "## [0.1.0.1]"
# (the closing bracket is part of the text looked for), and a date or anything else may follow it.
notes=$(awk -v heading="## [$version]" '
    index($0, heading) == 1 { found = 1; next }
    found && /^## \[/       { exit }
    found                   { print }
' "$changelog")

# $(...) drops trailing blank lines; a section of only blank lines is empty here too.
if [ -z "$(printf '%s' "$notes" | tr -d '[:space:]')" ]; then
    echo "$changelog has no section \"## [$version]\", or it is empty." >&2
    echo "Rename \"## [Unreleased]\" to \"## [$version] - <date>\" before tagging (CONTRIBUTING.md, \"Releasing\")." >&2
    exit 1
fi
printf '%s\n' "$notes" | sed '/./,$!d'   # drop the blank lines right after the heading
