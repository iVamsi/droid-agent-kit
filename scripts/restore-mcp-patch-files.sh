#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
bash scripts/restore-dispatcher.sh
bash scripts/restore-verification-metadata.sh
git add mcp-server/src/main/kotlin/com/droidagentkit/mcp/DroidAgentMcpDispatcher.kt gradle/verification-metadata.xml
if git diff --cached --quiet; then
  echo "Nothing to commit"
  exit 0
fi
git commit -m "fix: restore MCP patch files (dispatcher exposedGroups + junit-bom verification)"
git push origin main
