#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
# SPDX-License-Identifier: EPL-2.0
#
# Signs an update site ZIP with PGP and checks the result: the release workflow's sign job, as a
# script, so the same code runs locally (with a test key) and in the release.
#
#   MAVEN_GPG_KEY=... MAVEN_GPG_PASSPHRASE=... \
#     releng/sign.sh <unsigned zip> <signed zip> <public key file>
#
# MAVEN_GPG_KEY is the armored secret key (gpg --armor --export-secret-keys), MAVEN_GPG_PASSPHRASE
# its passphrase; both come from the GitHub environment "release". The public key file is
# releng/signing-key.asc in the release; it must belong to that secret key, or the check fails.
#
# Example, with a throwaway key (see docs/design/MARKETPLACE.md, "Experiments"):
#
#   export GNUPGHOME=$(mktemp -d)
#   gpg --batch --pinentry-mode loopback --passphrase test \
#       --quick-generate-key "test <test@example.invalid>" ed25519 sign never
#   gpg --armor --export > /tmp/test-public.asc
#   MAVEN_GPG_KEY=$(gpg --batch --pinentry-mode loopback --passphrase test \
#       --armor --export-secret-keys) MAVEN_GPG_PASSPHRASE=test \
#     releng/sign.sh releng/io.github.jm6cnddxbwftaks.neovim4eclipse.updatesite/target/neovim4eclipse-0.1.4-SNAPSHOT.zip \
#       /tmp/signed.zip /tmp/test-public.asc
#
# Steps: unzip into a temporary directory; sign it in place with releng/sign/pom.xml; check every
# signature with releng/verify-signatures.sh; zip it again. The secret key is written to a file
# only for the signer (Tycho's Bouncy Castle signer reads it from a file, not from the
# environment), readable only by this user, and deleted on exit, also on failure.

set -euo pipefail

if [ $# -ne 3 ]; then
  echo "usage: $0 <unsigned zip> <signed zip> <public key file>" >&2
  exit 2
fi
unsigned=$(realpath "$1")
signed=$(realpath -m "$2")
public_key=$(realpath "$3")
: "${MAVEN_GPG_KEY:?MAVEN_GPG_KEY (the armored secret key) is not set}"
: "${MAVEN_GPG_PASSPHRASE:?MAVEN_GPG_PASSPHRASE is not set}"
[ -f "$unsigned" ] || { echo "error: $unsigned not found" >&2; exit 1; }

repo=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
site=$work/site

# 1. Unzip. The ZIP holds the site at its top level (artifacts.jar, content.jar, plugins/, ...).
mkdir "$site"
unzip -q "$unsigned" -d "$site"
[ -f "$site/artifacts.jar" ] || { echo "error: $unsigned is not an update site (no artifacts.jar)" >&2; exit 1; }

# 2. Sign. umask 077: the key file is created readable by this user only.
(umask 077 && printf '%s\n' "$MAVEN_GPG_KEY" > "$work/secret-key.asc")
"$repo/mvnw" -B -q -f "$repo/releng/sign/pom.xml" package \
  -Dsite="$site" -Dtycho.pgp.signer.bc.secretKeys="$work/secret-key.asc"
rm -f "$work/secret-key.asc"

# 3. Check, with gpg and the public key, not with the signer that just wrote the signatures.
"$repo/releng/verify-signatures.sh" "$site" "$public_key"

# 4. Zip again, with the same layout. -X leaves out extra file attributes (owner ids), so the ZIP
#    depends only on the files.
#    env -u ZIP -u ZIPOPT: zip(1) reads these environment variables as extra command-line
#    arguments. The v0.1.4 tag's first run had ZIP=neovim4eclipse-0.1.4.zip set by the workflow, so
#    zip wrote the archive under that name inside $site, which the trap then deleted, and the
#    signed ZIP was missing ("No files were found").
rm -f "$signed"
(cd "$site" && env -u ZIP -u ZIPOPT zip -q -r -X "$signed" .)
[ -s "$signed" ] || { echo "error: $signed was not written" >&2; exit 1; }
echo "signed: $signed"
