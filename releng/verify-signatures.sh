#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
# SPDX-License-Identifier: EPL-2.0
#
# Checks the PGP signatures of an unzipped, signed p2 update site, independently of the signer.
# The release workflow runs it (through releng/sign.sh) before anything is published, so a release
# with a missing or wrong signature never goes out.
#
#   releng/verify-signatures.sh <site dir> <public key file>
#
# Example, on a site signed with a test key:
#
#   releng/verify-signatures.sh /tmp/site /tmp/test-public.asc
#
# It fails unless every bundle (classifier osgi.bundle) and feature (org.eclipse.update.feature)
# listed in the site's artifacts.jar has a pgp.signatures property, and each signature verifies
# against the given public key, and only that key:
#
#   <artifact classifier='osgi.bundle' id='io.github...neovim4eclipse' version='0.1.4.2026...'>
#     <properties>
#       <property name='pgp.signatures' value='-----BEGIN PGP SIGNATURE-----&#xA;...'/>
#
# The jar of that artifact is plugins/<id>_<version>.jar (features/... for a feature), the layout
# Tycho writes. gpg checks it in a temporary keyring holding only the given key, so a signature by
# any other key fails, even a valid one.
#
# Needs bash, python3 (to read the XML) and gpg.

set -euo pipefail

if [ $# -ne 2 ]; then
  echo "usage: $0 <site dir> <public key file>" >&2
  exit 2
fi
site=$1
key=$2
for f in "$site/artifacts.jar" "$key"; do
  [ -f "$f" ] || { echo "error: $f not found" >&2; exit 1; }
done

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# A keyring of our own: the caller's ~/.gnupg is neither read nor changed.
export GNUPGHOME=$work/gnupg
mkdir -m 700 "$GNUPGHOME"
gpg --batch --quiet --import "$key"

# Write each artifact's signature to $work/<n>.asc and print "<n> <jar path>", one per line; or
# "MISSING <jar path>" for an artifact without a signature. python3 -I: isolated mode, so nothing
# from the current directory or the environment is imported. The value of pgp.signatures is
# XML-escaped in the file; ElementTree unescapes it.
python3 -I - "$site" "$work" > "$work/list" <<'PY'
import sys, zipfile, xml.etree.ElementTree as ET
site, work = sys.argv[1], sys.argv[2]
folders = {'osgi.bundle': 'plugins', 'org.eclipse.update.feature': 'features'}
root = ET.fromstring(zipfile.ZipFile(site + '/artifacts.jar').read('artifacts.xml'))
n = 0
for a in root.iter('artifact'):
    folder = folders.get(a.get('classifier'))
    if folder is None:
        continue  # e.g. binary artifacts, which this project does not have
    jar = f"{site}/{folder}/{a.get('id')}_{a.get('version')}.jar"
    sigs = [p.get('value') for p in a.iter('property') if p.get('name') == 'pgp.signatures']
    if not sigs:
        print('MISSING', jar)
        continue
    n += 1
    with open(f'{work}/{n}.asc', 'w') as f:
        f.write(sigs[0])
    print(n, jar)
PY

checked=0
failed=0
while read -r n jar; do
  if [ "$n" = MISSING ]; then
    echo "error: no PGP signature for $jar" >&2
    failed=$((failed + 1))
  elif [ ! -f "$jar" ]; then
    echo "error: $jar is listed in artifacts.jar but not in the site" >&2
    failed=$((failed + 1))
  elif gpg --batch --quiet --verify "$work/$n.asc" "$jar" 2> "$work/gpg.log"; then
    echo "ok: $(basename "$jar")"
    checked=$((checked + 1))
  else
    echo "error: bad signature for $jar:" >&2
    cat "$work/gpg.log" >&2
    failed=$((failed + 1))
  fi
done < "$work/list"

if [ "$failed" -gt 0 ]; then
  echo "error: $failed artifact(s) not signed correctly" >&2
  exit 1
fi
if [ "$checked" -eq 0 ]; then
  echo "error: no bundles or features found in $site/artifacts.jar" >&2
  exit 1
fi
echo "all $checked signatures verified"
