#!/usr/bin/env bash
# Drives the MCP server over real stdio against a real emulator.
#
# Everything else in the suite mocks AdbExecutor, which proves the parsing and the authorization
# but never that the adb invocations themselves are correct against a real device. This is the only
# place that gap is closed, so it deliberately goes through `serve-mcp --transport stdio` rather
# than calling Kotlin directly -- the transport is part of what is under test.
#
# Expects an emulator already booted (the CI job handles that) and the CLI already built.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CLI="$ROOT/cli/build/install/droidagent/bin/droidagent"
WORK="${DAK_E2E_DIR:-/tmp/dak-e2e}"
POLICY="$WORK/policy.yaml"

if [ ! -x "$CLI" ]; then
  echo "e2e: no CLI at $CLI (run ./gradlew :cli:installDist)" >&2
  exit 2
fi

mkdir -p "$WORK"

# Device tools need capabilities, and grants are only honored from the user policy -- the same
# trust split the docs describe, exercised here rather than bypassed.
cat > "$POLICY" <<'YAML'
schemaVersion: 1
safety:
  allowCapabilities:
    - app_install
    - app_control
    - device_input
mcp:
  exposedGroups:
    - device_read
    - device_control
YAML

echo "e2e: waiting for a device"
adb wait-for-device
adb shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 1; done'
SERIAL="$(adb devices | awk 'NR==2 {print $1}')"
echo "e2e: device $SERIAL is up"

# Sends one JSON-RPC request and prints the first response line. The server keeps stdin open, so
# each call is its own short-lived process -- simpler than multiplexing, and enough to prove the
# tool works end to end.
call_tool() {
  local name="$1" args="$2"
  printf '%s\n%s\n' \
    '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"e2e","version":"0"}}}' \
    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"$name\",\"arguments\":$args}}" \
    | DROIDAGENTKIT_POLICY="$POLICY" "$CLI" serve-mcp --transport stdio --project "$ROOT" 2>/dev/null \
    | grep '"id":2' | head -n1
}

assert_status() {
  local label="$1" response="$2" expected="$3"
  if [ -z "$response" ]; then
    echo "  FAIL $label: no response" >&2
    return 1
  fi
  if printf '%s' "$response" | grep -q "\"status\":\"$expected\""; then
    echo "  ok   $label"
  else
    echo "  FAIL $label: expected status $expected, got: ${response:0:400}" >&2
    return 1
  fi
}

fail=0

echo "e2e: android_devices_list"
resp="$(call_tool android_devices_list '{}')"
assert_status "devices list" "$resp" "success" || fail=1
printf '%s' "$resp" | grep -q "$SERIAL" || { echo "  FAIL the booted device is not in the listing" >&2; fail=1; }

echo "e2e: android_screen_snapshot"
resp="$(call_tool android_screen_snapshot "{\"deviceSerial\":\"$SERIAL\"}")"
assert_status "screenshot" "$resp" "success" || fail=1
# A screenshot that reports success but writes nothing is the failure worth catching here.
png="$(find "$ROOT/build/droidagentkit" -name '*.png' -size +1k 2>/dev/null | head -n1)"
[ -n "$png" ] || { echo "  FAIL no non-empty PNG was written" >&2; fail=1; }

echo "e2e: android_accessibility_snapshot"
resp="$(call_tool android_accessibility_snapshot "{\"deviceSerial\":\"$SERIAL\"}")"
assert_status "accessibility snapshot" "$resp" "success" || fail=1

echo "e2e: android_logcat_capture"
resp="$(call_tool android_logcat_capture "{\"deviceSerial\":\"$SERIAL\",\"maxLines\":50}")"
assert_status "logcat" "$resp" "success" || fail=1

echo "e2e: android_dumpsys (device_read group)"
# `preset`, not `service`: the tool exposes a fixed set (meminfo, gfxinfo, cpuinfo, batterystats,
# package) rather than arbitrary service names, precisely so an agent cannot dumpsys anything it
# likes. The first nightly caught this script guessing the wrong argument name.
resp="$(call_tool android_dumpsys "{\"deviceSerial\":\"$SERIAL\",\"preset\":\"meminfo\"}")"
assert_status "dumpsys meminfo" "$resp" "success" || fail=1

echo "e2e: android_dumpsys rejects an unknown preset"
# The allowlist is the point of the tool; a passing meminfo says nothing about whether it holds.
resp="$(call_tool android_dumpsys "{\"deviceSerial\":\"$SERIAL\",\"preset\":\"anything-goes\"}")"
assert_status "unknown preset is refused" "$resp" "blocked" || fail=1

echo "e2e: a capability that was NOT granted is refused"
# Proves the policy is actually in force on a real device, not just in unit tests.
resp="$(call_tool android_app_clear_data "{\"deviceSerial\":\"$SERIAL\",\"packageName\":\"com.android.settings\",\"confirmDestructive\":true}")"
assert_status "ungranted destructive op is blocked" "$resp" "blocked" || fail=1

if [ "$fail" -ne 0 ]; then
  echo "e2e: FAILED" >&2
  exit 1
fi
echo "e2e: all emulator checks passed."
