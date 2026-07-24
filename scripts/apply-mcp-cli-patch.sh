#!/usr/bin/env bash
set -euo pipefail
cd /Users/vamsi/Developer/StudioProjects/droid-agent-kit
git pull origin main
git apply patches/mcp-cli-and-verification.patch
./gradlew test :cli:installDist --no-configuration-cache
git add patches/mcp-cli-and-verification.patch cli/src/main/kotlin/com/droidagentkit/cli/DroidAgentMain.kt mcp-server/src/main/kotlin/com/droidagentkit/mcp/DroidAgentMcpDispatcher.kt gradle/verification-metadata.xml
git commit -m "Wire mcpDispatcher for mcp.exposedGroups and fix junit-bom verification"
git push origin main
