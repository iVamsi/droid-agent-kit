#!/usr/bin/env bash
# Marks the pre-rename MCP registry listing deprecated, pointing at the new one.
#
# Run this ONCE, and only AFTER a release has successfully published the new name
# (io.github.iVamsi/android-agent-kit). Running it before that would leave users with a
# deprecated listing and nothing to migrate to.
#
# Why the rename happened: the registry's `search` matches the server `name` only -- not the
# description, not the title. The old name contained no "android", so a search for the single most
# obvious term never returned this server. `name` is the registry's primary key, so becoming
# findable required publishing under a new one.
#
# This script is the other half of that migration. Without it the old listing sits at status
# `active` forever, competing with the new one and telling nobody where the project went.
set -euo pipefail

OLD_NAME="io.github.iVamsi/droidagentkit"
NEW_NAME="io.github.iVamsi/android-agent-kit"
REGISTRY="${MCP_REGISTRY:-https://registry.modelcontextprotocol.io}"

if [ "${1:-}" != "--confirm" ]; then
  cat >&2 <<EOF
This marks every published version of:
    $OLD_NAME
as DEPRECATED, with a message pointing at:
    $NEW_NAME

Deprecation is a lifecycle change on a public listing. Re-run with --confirm to proceed:
    $0 --confirm
EOF
  exit 2
fi

if ! command -v mcp-publisher >/dev/null 2>&1; then
  echo "mcp-publisher not on PATH." >&2
  echo "Install it from https://github.com/modelcontextprotocol/registry/releases (the release" >&2
  echo "workflow pins a checksummed copy; see .github/workflows/release.yml)." >&2
  exit 1
fi

echo "Confirming the new listing exists before deprecating the old one..."
new_count="$(curl -fsSL "$REGISTRY/v0/servers?search=android-agent-kit" \
  | python3 -c 'import sys,json; print(len(json.load(sys.stdin).get("servers",[])))')"
if [ "$new_count" -eq 0 ]; then
  echo "Refusing to deprecate: $NEW_NAME is not published yet." >&2
  echo "Cut a release first so the new listing exists, then re-run." >&2
  exit 1
fi
echo "  found $new_count version(s) of $NEW_NAME"

echo "Authenticating (GitHub OIDC in CI, or a GitHub token locally)..."
mcp-publisher login github-oidc 2>/dev/null || mcp-publisher login github

echo "Marking $OLD_NAME deprecated..."
# --all-versions so no older version is left looking current. `yes` answers the prompt, which
# --confirm above already covers.
yes | mcp-publisher status --status deprecated --all-versions \
  --message "Renamed to $NEW_NAME so the listing is findable when searching for 'android'. Same project, same npm package (@droidagentkit/launcher), no install change needed." \
  "$OLD_NAME"

echo
echo "Done. Verify:"
echo "  curl -s '$REGISTRY/v0/servers/io.github.iVamsi%2Fdroidagentkit/versions' | python3 -m json.tool | grep status"
