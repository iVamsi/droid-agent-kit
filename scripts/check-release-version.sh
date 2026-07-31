#!/usr/bin/env bash
# Verifies the version string is consistent across every place it is hardcoded, so a release
# tag can't ship with a stale value in one of them. Run before cutting a release:
#   scripts/check-release-version.sh 0.2.0
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "usage: $0 <version>  (e.g. 0.2.0 or 0.2.0-alpha)" >&2
  exit 2
fi

version="$1"
root="$(cd "$(dirname "$0")/.." && pwd)"
fail=0

check() {
  local label="$1" file="$2" pattern="$3"
  if [ ! -f "$file" ]; then
    echo "MISSING: $file ($label)" >&2
    fail=1
    return
  fi
  if ! grep -qF "$pattern" "$file"; then
    echo "MISMATCH: $label expected to contain: $pattern" >&2
    echo "  in $file" >&2
    fail=1
  fi
}

check "root project version" \
  "$root/build.gradle.kts" \
  "version = \"$version\""

check "MCP server version constant" \
  "$root/mcp-server/src/main/kotlin/com/droidagentkit/mcp/McpJsonRpcHandler.kt" \
  "SERVER_VERSION = \"$version\""

check "MCP server version test assertion" \
  "$root/mcp-server/src/test/kotlin/com/droidagentkit/mcp/McpJsonRpcHandlerTest.kt" \
  "\\\"version\\\":\\\"$version\\\""

check "npm launcher package.json" \
  "$root/distribution/npm-launcher/package.json" \
  "\"version\": \"$version\""

check "MCPB manifest" \
  "$root/distribution/mcp.json" \
  "\"version\": \"$version\""

check "MCP registry metadata" \
  "$root/distribution/server.json" \
  "\"version\": \"$version\""

changelog="$root/CHANGELOG.md"
if [ ! -f "$changelog" ]; then
  echo "MISSING: $changelog (changelog entry)" >&2
  fail=1
elif ! grep -qF "## [$version]" "$changelog"; then
  echo "MISMATCH: CHANGELOG.md has no released heading '## [$version]'" >&2
  echo "  (did you move the Unreleased section under a dated release heading?)" >&2
  fail=1
fi

if [ "$fail" -ne 0 ]; then
  echo "check-release-version: version $version is not consistent across the repo." >&2
  exit 1
fi

# release.yml publishes the npm launcher with `--tag latest`, so every version claims `latest`.
# That is correct while pre-1.0 -- there is no stable release to shadow -- but wrong the moment
# one exists: the next prerelease published after it would overwrite `latest` and hide the
# stable version from `npm install`. Fail here so the first stable release forces the decision,
# then delete this block as part of adding the real dist-tag logic.
if [[ "$version" != *-* ]]; then
  echo "check-release-version: $version is a stable (non-prerelease) version." >&2
  echo "  release.yml publishes with no --tag, so a later prerelease would overwrite \`latest\`." >&2
  echo "  Add dist-tag logic (stable -> latest, prerelease -> next) and remove this guard" >&2
  echo "  before shipping $version." >&2
  exit 1
fi

echo "check-release-version: $version is consistent."
