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
{
  cat <<'YAML'
schemaVersion: 1
safety:
  allowCapabilities:
    - app_install
    - app_control
    - device_input
    - sensitive_diagnostics
YAML
  # Only set traceProcessorPath when the workflow actually provisioned the binary; an empty value
  # would make the perfetto analysis report a missing tool rather than skip.
  if [ -n "${DAK_TRACE_PROCESSOR:-}" ]; then
    echo "  traceProcessorPath: $DAK_TRACE_PROCESSOR"
  fi
  cat <<'YAML'
mcp:
  exposedGroups:
    - device_read
    - device_control
    - perfetto
YAML
} > "$POLICY"

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
# A screenshot that reports success but writes nothing is the case worth catching.
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
# package) rather than arbitrary service names, so an agent cannot dumpsys whatever it likes.
resp="$(call_tool android_dumpsys "{\"deviceSerial\":\"$SERIAL\",\"preset\":\"meminfo\"}")"
assert_status "dumpsys meminfo" "$resp" "success" || fail=1

echo "e2e: android_dumpsys rejects an unknown preset"
# A passing meminfo does not show that the preset allowlist is enforced; this does.
resp="$(call_tool android_dumpsys "{\"deviceSerial\":\"$SERIAL\",\"preset\":\"anything-goes\"}")"
assert_status "unknown preset is refused" "$resp" "blocked" || fail=1

echo "e2e: android_screen_record round trip"
# The one check that needs real hardware: screenrecord, the pull, and the device-side delete are
# all adb invocations that the mocked unit tests cannot get wrong.
resp="$(call_tool android_screen_record_start "{\"deviceSerial\":\"$SERIAL\",\"durationSeconds\":5}")"
assert_status "screen record start" "$resp" "success" || fail=1
device_path="$(printf '%s' "$resp" | sed -n 's/.*"devicePath":"\([^"]*\)".*/\1/p')"
job_id="$(printf '%s' "$resp" | sed -n 's/.*"jobId":"\([^"]*\)".*/\1/p')"
if [ -z "$device_path" ] || [ -z "$job_id" ]; then
  echo "  FAIL start did not return devicePath and jobId: ${resp:0:400}" >&2
  fail=1
else
  # Give screenrecord time to open the file and write a frame; stopping instantly can yield a
  # zero-byte container that says nothing about whether the pull works.
  sleep 3
  resp="$(call_tool android_screen_record_stop "{\"deviceSerial\":\"$SERIAL\",\"jobId\":\"$job_id\"}")"
  assert_status "screen record stop" "$resp" "success" || fail=1
  printf '%s' "$resp" | grep -q '"type":"screen_recording"' \
    || { echo "  FAIL stop returned no screen_recording artifact" >&2; fail=1; }
  mp4="$(find "$ROOT/build/droidagentkit" -name "$job_id.mp4" -size +1k 2>/dev/null | head -n1)"
  [ -n "$mp4" ] || { echo "  FAIL no non-empty mp4 was pulled off the device" >&2; fail=1; }
  # Leaving a recording of the screen on shared storage is the failure mode that matters most here.
  if adb -s "$SERIAL" shell "ls $device_path" >/dev/null 2>&1; then
    echo "  FAIL $device_path still exists on the device after stop" >&2
    fail=1
  else
    echo "  ok   device recording removed"
  fi
fi

echo "e2e: android_screen_record_stop rejects a foreign job id"
# jobId becomes part of an on-device path, so the shape check has to hold on a real device too.
resp="$(call_tool android_screen_record_stop "{\"deviceSerial\":\"$SERIAL\",\"jobId\":\"../../etc/passwd\"}")"
assert_status "foreign job id is refused" "$resp" "blocked" || fail=1

if [ -n "${DAK_TRACE_PROCESSOR:-}" ]; then
  echo "e2e: android_perfetto_capture + analyze"
  resp="$(call_tool android_perfetto_capture "{\"deviceSerial\":\"$SERIAL\",\"durationSeconds\":5}")"
  assert_status "perfetto capture" "$resp" "success" || fail=1
  trace="$(find "$ROOT/build/droidagentkit/perfetto" -name '*.perfetto-trace' -size +1k 2>/dev/null | head -n1)"
  if [ -z "$trace" ]; then
    echo "  FAIL no non-empty trace was captured" >&2
    fail=1
  else
    # Runs the shipped SQL through a real Trace Processor. A malformed query reports
    # data-unavailable; an empty result reports no-rows. Only the first is a defect, and the
    # samples carry no runtime-tracing, so no-rows is the expected outcome here.
    resp="$(call_tool android_perfetto_analyze \
      "{\"rootPath\":\"$ROOT\",\"tracePath\":\"$trace\",\"analyses\":\"compose_recomposition\"}")"
    assert_status "perfetto analyze" "$resp" "success" || fail=1
    if printf '%s' "$resp" | grep -q '"data-unavailable"'; then
      echo "  FAIL compose_recomposition SQL did not run against Trace Processor: ${resp:0:400}" >&2
      fail=1
    else
      echo "  ok   compose_recomposition SQL ran against a real trace"
    fi
  fi
else
  echo "e2e: skipping perfetto analysis (DAK_TRACE_PROCESSOR is unset)"
fi

echo "e2e: a capability that was NOT granted is refused"
# Proves the policy is actually in force on a real device, not just in unit tests.
resp="$(call_tool android_app_clear_data "{\"deviceSerial\":\"$SERIAL\",\"packageName\":\"com.android.settings\",\"confirmDestructive\":true}")"
assert_status "ungranted destructive op is blocked" "$resp" "blocked" || fail=1

if [ "$fail" -ne 0 ]; then
  echo "e2e: FAILED" >&2
  exit 1
fi
echo "e2e: all emulator checks passed."
