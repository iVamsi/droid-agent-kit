#!/usr/bin/env bash
# Table-driven tests for the npm dist-tag decision.
#
# This logic is only ever exercised during a release, which is the worst place to discover it is
# wrong -- a bad dist-tag either breaks `npx` for everyone or hides a stable release behind a
# prerelease, and both are visible to users before they are visible to us.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT="$ROOT/scripts/npm-dist-tag.sh"
fail=0

check() {
  local label="$1" version="$2" published="$3" want_tag="$4" want_latest="$5"
  local out
  # The empty-list cases must not fall through to a live npm query, so they are injected via the
  # env var rather than as zero arguments.
  # shellcheck disable=SC2086
  out="$(NPM_PUBLISHED_VERSIONS="$(printf '%s\n' $published)" bash "$SCRIPT" "$version")"
  local tag also
  tag="$(printf '%s' "$out" | sed -n 's/^tag=//p')"
  also="$(printf '%s' "$out" | sed -n 's/^also_latest=//p')"
  if [ "$tag" = "$want_tag" ] && [ "$also" = "$want_latest" ]; then
    echo "  ok   $label"
  else
    echo "  FAIL $label: got tag=$tag also_latest=$also, want tag=$want_tag also_latest=$want_latest" >&2
    fail=1
  fi
}

echo "npm dist-tag policy:"

# Today's situation: only prereleases exist, and `npx` resolves `latest`. A prerelease must keep
# claiming `latest` or the documented install command breaks for everyone.
check "prerelease with no stable ever published claims latest" \
  "0.2.8-alpha" "0.2.6-alpha 0.2.7-alpha" "next" "true"

# The case the old guard existed to prevent: once a stable exists, a later prerelease must not
# overwrite `latest`, or plain `npm install` silently downgrades users onto a prerelease.
check "prerelease after a stable release leaves latest alone" \
  "1.1.0-beta.1" "1.0.0 1.0.1" "next" "false"

check "first stable release takes latest" \
  "1.0.0" "0.2.7-alpha" "latest" "false"

check "subsequent stable release takes latest" \
  "1.0.1" "1.0.0 1.1.0-beta.1" "latest" "false"

# A stable buried among prereleases still counts; the scan must not stop at the first entry.
check "stable is detected anywhere in the published list" \
  "1.2.0-rc.1" "0.9.0-alpha 1.0.0 1.1.0-alpha" "next" "false"

# No published versions at all (a brand-new package).
check "very first publish, prerelease" \
  "0.1.0-alpha" "" "next" "true"

check "very first publish, stable" \
  "1.0.0" "" "latest" "false"

if [ "$fail" -ne 0 ]; then
  echo "npm dist-tag policy: FAILED" >&2
  exit 1
fi
echo "npm dist-tag policy: all cases passed."
