#!/usr/bin/env bash
# Packs the npm launcher into an MCPB bundle for one-click install in MCP hosts.
#
# The bundle is `type: node` rather than `type: binary` because the launcher *is* a Node script and
# the host supplies the Node runtime. What it does not contain is the server itself: the JVM jar
# (~30 MB) and, when the machine has no JDK, a Temurin JRE (~130 MB) are fetched and SHA-256
# verified on first run. Embedding either would multiply the bundle size by an order of magnitude
# and pin the runtime at package time instead of at install time.
#
# Usage: scripts/build-mcpb.sh [version] [output-path]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="${1:-$(sed -n 's/^version = "\(.*\)"$/\1/p' "$ROOT/build.gradle.kts" | head -n1)}"
OUTPUT="${2:-$ROOT/build/distribution/android-agent-kit-${VERSION}.mcpb}"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

if [ -z "$VERSION" ]; then
  echo "build-mcpb: could not determine version" >&2
  exit 2
fi

echo "build-mcpb: staging $VERSION"
mkdir -p "$STAGE/server"

# The manifest's version is a placeholder in the repo so it cannot drift from build.gradle.kts;
# the real value is stamped here from the single source of truth.
python3 - "$ROOT/distribution/mcpb/manifest.json" "$STAGE/manifest.json" "$VERSION" <<'PY'
import json, sys
src, dst, version = sys.argv[1], sys.argv[2], sys.argv[3]
manifest = json.load(open(src))
manifest["version"] = version
json.dump(manifest, open(dst, "w"), indent=2)
open(dst, "a").write("\n")
PY

# Exactly what index.js needs at runtime: jre.js and jre-manifest.json for JVM provisioning, and
# package.json because index.js reads its own version from it to pick the matching release jar.
for f in index.js jre.js jre-manifest.json package.json README.md; do
  cp "$ROOT/distribution/npm-launcher/$f" "$STAGE/server/$f"
done

# package.json's version drives which release jar the launcher fetches, so a bundle built at a
# version the launcher does not agree with would silently download the wrong artifact.
launcher_version="$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['version'])" "$STAGE/server/package.json")"
if [ "$launcher_version" != "$VERSION" ]; then
  echo "build-mcpb: launcher package.json is $launcher_version but building $VERSION." >&2
  echo "  These must match, or the bundle fetches a different jar than it claims to be." >&2
  exit 1
fi

echo "build-mcpb: validating manifest"
npx -y @anthropic-ai/mcpb@2.1.2 validate "$STAGE/manifest.json"

mkdir -p "$(dirname "$OUTPUT")"
echo "build-mcpb: packing"
npx -y @anthropic-ai/mcpb@2.1.2 pack "$STAGE" "$OUTPUT"

echo "build-mcpb: wrote $OUTPUT"
npx -y @anthropic-ai/mcpb@2.1.2 info "$OUTPUT" 2>/dev/null || true
