#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
REF="${RESTORE_REF:-d19d517f09e79ad234ab6ceaa9aee87e7769f96c}"

if ! git rev-parse --verify "${REF}^{commit}" >/dev/null 2>&1; then
  echo "Commit ${REF} not in local repo. Run: git fetch origin main" >&2
  exit 1
fi

git show "${REF}:mcp-server/src/main/kotlin/com/droidagentkit/mcp/DroidAgentMcpDispatcher.kt" \
  > mcp-server/src/main/kotlin/com/droidagentkit/mcp/DroidAgentMcpDispatcher.kt

python3 - <<'PY'
from pathlib import Path
path = Path("mcp-server/src/main/kotlin/com/droidagentkit/mcp/DroidAgentMcpDispatcher.kt")
text = path.read_text()
old = "private val exposedGroups: Set<ToolGroup> = setOf(ToolGroup.CORE),"
new = "private val exposedGroups: Set<ToolGroup> = config.resolvedExposedToolGroups(),"
if new not in text:
    if old not in text:
        raise SystemExit("exposedGroups default anchor not found in DroidAgentMcpDispatcher.kt")
    path.write_text(text.replace(old, new, 1))
PY
echo "Restored DroidAgentMcpDispatcher.kt"
