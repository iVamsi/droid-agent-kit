#!/usr/bin/env bash
# Exercises the built CLI against the checked-in sample projects.
#
# The inspector and auditor are static parsers -- they read Gradle files without executing
# Gradle -- so this needs no Android SDK and no emulator, which is what makes it cheap enough
# to run on every PR. It is the only place the CLI is driven end to end against real project
# trees rather than against unit-test fixtures.
#
# Usage: scripts/ci-samples-check.sh [path-to-droidagent-binary]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BIN="${1:-$ROOT/cli/build/install/droidagent/bin/droidagent}"

if [ ! -x "$BIN" ]; then
  echo "ci-samples-check: no executable CLI at $BIN" >&2
  echo "  build it first: ./gradlew :cli:installDist" >&2
  exit 2
fi

if ! command -v jq >/dev/null 2>&1; then
  echo "ci-samples-check: jq is required" >&2
  exit 2
fi

fail=0

# Reports a check result. Keeps every assertion on one line in the log so a CI failure shows
# which specific expectation broke without scrolling.
expect() {
  local label="$1" expected="$2" actual="$3"
  if [ "$expected" = "$actual" ]; then
    echo "  ok   $label"
  else
    echo "  FAIL $label: expected '$expected', got '$actual'" >&2
    fail=1
  fi
}

echo "== inspect: samples/basic-compose =="
out="$("$BIN" inspect --project "$ROOT/samples/basic-compose" --format json)"
expect "modules == [\":app\"]"   ':app'              "$(jq -r '.modules | join(",")' <<<"$out")"
expect "support"                 'supported'         "$(jq -r '.support' <<<"$out")"
expect "projectName"             'BasicComposeSample' "$(jq -r '.projectName' <<<"$out")"
expect "no warnings"             '0'                 "$(jq -r '.warnings | length' <<<"$out")"

echo "== inspect: samples/multimodule =="
out="$("$BIN" inspect --project "$ROOT/samples/multimodule" --format json)"
expect "both modules found"      ':app,:feature:scan' "$(jq -r '.modules | sort | join(",")' <<<"$out")"
expect "support"                 'supported'          "$(jq -r '.support' <<<"$out")"

echo "== inspect: samples/broken-project (degrades, does not crash) =="
# A tree with no settings.gradle must produce a *structured partial report*, not a stack trace
# and not a hard failure -- an agent pointed at a half-configured repo should still get an
# answer it can reason about.
out="$("$BIN" inspect --project "$ROOT/samples/broken-project" --format json)"
expect "support"                 'partial'  "$(jq -r '.support' <<<"$out")"
expect "explains why"            'true'     "$(jq -r '(.warnings | length) > 0' <<<"$out")"
expect "no modules"              '0'        "$(jq -r '.modules | length' <<<"$out")"

echo "== inspect: markdown format renders =="
out="$("$BIN" inspect --project "$ROOT/samples/basic-compose" --format markdown)"
case "$out" in
  *BasicComposeSample*) echo "  ok   markdown names the project" ;;
  *) echo "  FAIL markdown output did not name the project" >&2; fail=1 ;;
esac

echo "== audit: samples/basic-compose =="
out="$("$BIN" audit --project "$ROOT/samples/basic-compose")"
score="$(sed -n 's/^Readiness \([0-9][0-9]*\)\/100.*/\1/p' <<<"$out" | head -n1)"
if [ -z "$score" ]; then
  echo "  FAIL audit did not print a 'Readiness N/100' line (got: $out)" >&2
  fail=1
else
  echo "  ok   audit scored $score/100"
  if [ "$score" -lt 0 ] || [ "$score" -gt 100 ]; then
    echo "  FAIL score $score is outside 0..100" >&2
    fail=1
  fi
fi

echo "== audit: --fail-under threshold is enforced =="
# A guard that never fires is worse than no guard, so assert both directions.
if "$BIN" audit --project "$ROOT/samples/basic-compose" --fail-under 101 >/dev/null 2>&1; then
  echo "  FAIL --fail-under 101 should have exited non-zero" >&2
  fail=1
else
  echo "  ok   --fail-under 101 exits non-zero"
fi
if "$BIN" audit --project "$ROOT/samples/basic-compose" --fail-under 0 >/dev/null 2>&1; then
  echo "  ok   --fail-under 0 exits zero"
else
  echo "  FAIL --fail-under 0 should have exited zero" >&2
  fail=1
fi

if [ "$fail" -ne 0 ]; then
  echo "ci-samples-check: FAILED" >&2
  exit 1
fi
echo "ci-samples-check: all sample assertions passed."
