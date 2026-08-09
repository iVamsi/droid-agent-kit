#!/usr/bin/env bash
# Regenerates distribution/npm-launcher/jre-manifest.json from the Adoptium API.
#
# The launcher provisions a JRE for users who have no Java, and it executes what it downloads, so
# every entry is pinned by sha256 and verified before extraction. Run this to move to a newer
# Temurin patch release; review the diff before committing -- the checksums are the trust anchor.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FEATURE="${1:-17}"
OUT="$ROOT/distribution/npm-launcher/jre-manifest.json"

curl -fsSL "https://api.adoptium.net/v3/assets/latest/${FEATURE}/hotspot?image_type=jre&vendor=eclipse" \
  | python3 -c "
import sys, json
data = json.load(sys.stdin)
wanted = {
    ('mac', 'x64'): 'darwin-x64',
    ('mac', 'aarch64'): 'darwin-arm64',
    ('linux', 'x64'): 'linux-x64',
    ('linux', 'aarch64'): 'linux-arm64',
    ('windows', 'x64'): 'win32-x64',
}
out = {
    '_comment': 'Pinned Eclipse Temurin ${FEATURE} JREs. Regenerate with scripts/update-jre-manifest.sh. Every entry is verified by sha256 before use; a mismatch is fatal.',
    'release': None,
    'platforms': {},
}
for asset in data:
    binary = asset['binary']
    key = (binary['os'], binary['architecture'])
    if key not in wanted:
        continue
    package = binary['package']
    out['release'] = asset['release_name']
    out['platforms'][wanted[key]] = {
        'url': package['link'],
        'sha256': package['checksum'],
        'archive': 'zip' if package['name'].endswith('.zip') else 'tar.gz',
    }
missing = set(wanted.values()) - set(out['platforms'])
if missing:
    raise SystemExit(f'Adoptium did not return every platform; missing: {sorted(missing)}')
json.dump(out, sys.stdout, indent=2, sort_keys=True)
print()
" > "$OUT"

echo "Wrote $OUT"
python3 -c "import json;d=json.load(open('$OUT'));print('release',d['release']);print('platforms',sorted(d['platforms']))"
