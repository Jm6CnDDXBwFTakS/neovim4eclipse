#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
# SPDX-License-Identifier: EPL-2.0
#
# Checks the PGP signature of a whole release ZIP: <zip>.asc, a detached signature that
# releng/sign.sh makes and the release attaches beside the ZIP (from 0.2.1).
#
#   releng/verify-zip.sh <zip> <public key file>
#
# Example, on a release downloaded with gh:
#
#   gh release download v0.2.1 --pattern 'neovim4eclipse-*.zip*'
#   releng/verify-zip.sh neovim4eclipse-0.2.1.zip releng/signing-key.asc
#
# Why the whole ZIP, when every jar inside is signed already (releng/verify-signatures.sh): the
# jars are not all of it. content.jar says what p2 installs and how ("touchpoint" instructions:
# lines added to eclipse.ini, update sites added, files changed), and p2 follows it without
# asking. Nothing in the jar signatures covers it, so a ZIP replaced on GitHub (by anyone with a
# token that may write the repository's releases) could keep the signed jars and change
# content.jar. A signature over the ZIP's bytes covers every file in it.
#
# gpg checks it in a temporary keyring holding only the given key, so a signature by any other
# key fails, even a valid one. Needs bash and gpg.

set -euo pipefail

if [ $# -ne 2 ]; then
  echo "usage: $0 <zip> <public key file>" >&2
  exit 2
fi
zip=$1
key=$2
for f in "$zip" "$zip.asc" "$key"; do
  [ -f "$f" ] || { echo "error: $f not found" >&2; exit 1; }
done

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
# A keyring of our own: the caller's ~/.gnupg is neither read nor changed.
export GNUPGHOME=$work/gnupg
mkdir -m 700 "$GNUPGHOME"
gpg --batch --quiet --import "$key"
if gpg --batch --quiet --verify "$zip.asc" "$zip" 2> "$work/gpg.log"; then
  echo "ok: $(basename "$zip") (whole ZIP)"
else
  echo "error: bad signature for $zip:" >&2
  cat "$work/gpg.log" >&2
  exit 1
fi
