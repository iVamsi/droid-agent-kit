#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

if ! git rev-parse --verify "d19d517f09e79ad234ab6ceaa9aee87e7769f96c^{commit}" >/dev/null 2>&1; then
  echo "Restore commit missing locally. Run: git fetch origin main" >&2
  exit 1
fi

bash scripts/restore-dispatcher.sh
bash scripts/restore-verification-metadata.sh

if [[ "${1:-}" == "--no-git" ]]; then
  echo "Restored files (no commit)."
  exit 0
fi

git add mcp-server/src/main/kotlin/com/droidagentkit/mcp/DroidAgentMcpDispatcher.kt gradle/verification-metadata.xml
if git diff --cached --quiet; then
  echo "Nothing to commit"
  exit 0
fi
git commit -m "fix: restore MCP patch files (dispatcher exposedGroups + junit-bom verification)"
git push origin main
