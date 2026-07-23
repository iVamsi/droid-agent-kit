#!/usr/bin/env bash
# DroidAgentKit distribution smoke test.
#
# Default (no env): verifies the npm launcher prints immutable version metadata and exits 0.
# DROIDAGENT_E2E=1: also builds the CLI (./gradlew :cli:installDist) and runs a stdio
#   initialize round-trip against `droidagent serve-mcp --transport stdio`.
#
# Intended to run on a clean-ish machine (macOS/Linux; Windows under WSL/Git Bash). Node 18+
# is required for the launcher half; JDK 17+ for the e2e half.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LAUNCHER="$ROOT/distribution/npm-launcher/index.js"

if ! command -v node >/dev/null 2>&1; then
  echo "smoke: node not found on PATH" >&2
  exit 1
fi

echo "smoke: launcher --version"
out="$(node "$LAUNCHER" --version)"
echo "$out"
case "$out" in
  *launcher*) ;;
  *) echo "smoke: launcher did not print version metadata" >&2; exit 1 ;;
esac

echo "smoke: launcher --help exits 0"
node "$LAUNCHER" --help >/dev/null

if [ "${DROIDAGENT_E2E:-0}" != "1" ]; then
  echo "smoke: skipping JVM e2e (set DROIDAGENT_E2E=1 to enable)"
  exit 0
fi

echo "smoke: building CLI"
( cd "$ROOT" && ./gradlew :cli:installDist -q )

CLI="$ROOT/cli/build/install/droidagent/bin/droidagent"
if [ ! -x "$CLI" ]; then
  echo "smoke: CLI not found at $CLI" >&2
  exit 1
fi

echo "smoke: launcher spawns droidagent serve-mcp via DROIDAGENT_BIN"
DROIDAGENT_BIN="$CLI" node "$LAUNCHER" --version >/dev/null

# stdio initialize round-trip: send initialize, expect a JSON-RPC response with capabilities.
echo "smoke: stdio initialize round-trip"
init='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"smoke","version":"0"}}}'
resp="$(printf '%s\n' "$init" | DROIDAGENT_BIN="$CLI" timeout 20 node "$LAUNCHER" 2>/dev/null | head -n1 || true)"
case "$resp" in
  *serverInfo*|*capabilities*) echo "smoke: initialize answered" ;;
  *) echo "smoke: initialize did not answer: $resp" >&2; exit 1 ;;
esac

echo "smoke: OK"
