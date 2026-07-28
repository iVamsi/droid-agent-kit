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

MOCK_SERVER="$ROOT/distribution/test-fixtures/mock-release-server.js"
MOCK_LOG="$(mktemp)"
MOCK_PID=""

# Sets MOCK_PID and MOCK_PORT in the caller's shell. Must NOT be invoked via `$(...)` —
# that would run it in a subshell and lose MOCK_PID, leaking the background server.
start_mock() {
  node "$MOCK_SERVER" "$1" >"$MOCK_LOG" 2>&1 &
  MOCK_PID=$!
  MOCK_PORT=""
  for _ in $(seq 1 50); do
    if grep -q "MOCK_PORT=" "$MOCK_LOG" 2>/dev/null; then
      MOCK_PORT="$(grep -o "MOCK_PORT=[0-9]*" "$MOCK_LOG" | head -n1 | cut -d= -f2)"
      return 0
    fi
    sleep 0.1
  done
  echo "smoke: mock release server never reported a port" >&2
  exit 1
}

stop_mock() {
  if [ -n "$MOCK_PID" ]; then
    kill "$MOCK_PID" >/dev/null 2>&1 || true
    wait "$MOCK_PID" 2>/dev/null || true
    MOCK_PID=""
  fi
}
trap stop_mock EXIT

if ! command -v java >/dev/null 2>&1; then
  echo "smoke: skipping auto-fetch tests (java not on PATH)"
else
  echo "smoke: launcher auto-fetch downloads, verifies, and caches the jar"
  CACHE_GOOD="$(mktemp -d)"
  start_mock good
  out="$(DROIDAGENT_CACHE_DIR="$CACHE_GOOD" DROIDAGENT_RELEASE_BASE_URL="http://127.0.0.1:$MOCK_PORT" \
    node "$LAUNCHER" 2>&1 || true)"
  stop_mock
  if [ ! -f "$CACHE_GOOD/droidagent-cli-0.2.1-alpha.jar" ]; then
    echo "smoke: auto-fetch did not cache the jar. Output:" >&2
    echo "$out" >&2
    exit 1
  fi
  case "$out" in
    *"could not fetch"*) echo "smoke: auto-fetch reported a fetch error unexpectedly: $out" >&2; exit 1 ;;
  esac

  echo "smoke: launcher reuses the cache without re-fetching"
  start_mock good
  DROIDAGENT_CACHE_DIR="$CACHE_GOOD" DROIDAGENT_RELEASE_BASE_URL="http://127.0.0.1:$MOCK_PORT" \
    node "$LAUNCHER" >/dev/null 2>&1 || true
  stop_mock
  if grep -q "REQUEST " "$MOCK_LOG" 2>/dev/null; then
    echo "smoke: cache hit still made a network request" >&2
    exit 1
  fi

  echo "smoke: launcher fails closed on checksum mismatch"
  CACHE_BAD="$(mktemp -d)"
  start_mock badchecksum
  set +e
  out="$(DROIDAGENT_CACHE_DIR="$CACHE_BAD" DROIDAGENT_RELEASE_BASE_URL="http://127.0.0.1:$MOCK_PORT" \
    node "$LAUNCHER" 2>&1)"
  code=$?
  set -e
  stop_mock
  if [ "$code" -eq 0 ]; then
    echo "smoke: checksum mismatch should have failed but exited 0" >&2
    exit 1
  fi
  case "$out" in
    *"checksum mismatch"*) ;;
    *) echo "smoke: expected a checksum mismatch error, got: $out" >&2; exit 1 ;;
  esac
  if [ -n "$(ls -A "$CACHE_BAD" 2>/dev/null)" ]; then
    echo "smoke: checksum mismatch left files behind in the cache dir" >&2
    exit 1
  fi
fi

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
# Needs a hang guard since the server keeps the stdio loop open after answering; `timeout` is
# standard on Linux but not preinstalled on macOS (only `gtimeout` via `brew install coreutils`).
TIMEOUT_CMD=""
if command -v timeout >/dev/null 2>&1; then
  TIMEOUT_CMD="timeout"
elif command -v gtimeout >/dev/null 2>&1; then
  TIMEOUT_CMD="gtimeout"
fi

if [ -z "$TIMEOUT_CMD" ]; then
  echo "smoke: skipping stdio initialize round-trip (no timeout/gtimeout on PATH)"
else
  echo "smoke: stdio initialize round-trip"
  init='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"smoke","version":"0"}}}'
  resp="$(printf '%s\n' "$init" | DROIDAGENT_BIN="$CLI" "$TIMEOUT_CMD" 20 node "$LAUNCHER" 2>/dev/null | head -n1 || true)"
  case "$resp" in
    *serverInfo*|*capabilities*) echo "smoke: initialize answered" ;;
    *) echo "smoke: initialize did not answer: $resp" >&2; exit 1 ;;
  esac
fi

echo "smoke: OK"
