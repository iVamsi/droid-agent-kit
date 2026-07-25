#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
REF="d19d517f09e79ad234ab6ceaa9aee87e7769f96c"
curl -fsSL "https://raw.githubusercontent.com/iVamsi/droid-agent-kit/${REF}/mcp-server/src/main/kotlin/com/droidagentkit/mcp/DroidAgentMcpDispatcher.kt" -o mcp-server/src/main/kotlin/com/droidagentkit/mcp/DroidAgentMcpDispatcher.kt
python3 - <<'PY'
from pathlib import Path
path = Path("mcp-server/src/main/kotlin/com/droidagentkit/mcp/DroidAgentMcpDispatcher.kt")
text = path.read_text()
old = "private val exposedGroups: Set<ToolGroup> = setOf(ToolGroup.CORE),"
new = "private val exposedGroups: Set<ToolGroup> = config.resolvedExposedToolGroups(),"
if new not in text:
    if old not in text:
        raise SystemExit("exposedGroups default anchor not found")
    path.write_text(text.replace(old, new, 1))
PY
echo "Restored DroidAgentMcpDispatcher.kt"
