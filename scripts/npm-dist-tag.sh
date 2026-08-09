#!/usr/bin/env bash
# Decides which npm dist-tag a release should publish under.
#
# The naive rule -- stable to `latest`, prerelease to `next` -- breaks this package today. `npx`
# and `npm install` resolve `latest`, and every version published so far is a prerelease, so
# sending prereleases to `next` would leave `latest` pointing at nothing and break the documented
# install command for everyone.
#
# So the rule is conditional on whether a stable release exists yet:
#
#   stable version                        -> publish to `latest`
#   prerelease, no stable published yet   -> publish to `next`, and also move `latest`
#   prerelease, a stable already exists   -> publish to `next` only, leaving `latest` alone
#
# That last line is the one that matters after 1.0: without it, the next prerelease would silently
# overwrite `latest` and hide the stable release from every plain `npm install`.
#
# Usage:
#   scripts/npm-dist-tag.sh <version> [published-versions...]
#
# Published versions come from NPM_PUBLISHED_VERSIONS (newline-separated) when that variable is
# set -- including when it is set but empty, which means "nothing published yet". Otherwise they
# are queried from the npm registry.
#
# Deliberately no stdin fallback: an earlier version read stdin whenever no arguments were given,
# which blocked forever the moment it ran with a pipe attached and no data -- exactly what a
# release runner looks like. A hang in release plumbing is worse than a wrong answer, because
# nothing times out and nothing reports.
#
# Prints two shell-assignable lines:
#   tag=<dist-tag to publish under>
#   also_latest=<true|false>
set -euo pipefail

PACKAGE="${NPM_PACKAGE:-@droidagentkit/launcher}"
VERSION="${1:-}"

if [ -z "$VERSION" ]; then
  echo "usage: $0 <version> [published-versions...]" >&2
  exit 2
fi
shift || true

published=""
if [ "$#" -gt 0 ]; then
  published="$(printf '%s\n' "$@")"
elif [ -n "${NPM_PUBLISHED_VERSIONS+set}" ]; then
  published="$NPM_PUBLISHED_VERSIONS"
else
  published="$(npm view "$PACKAGE" versions --json 2>/dev/null \
    | python3 -c 'import sys,json
raw = sys.stdin.read().strip()
if not raw:
    sys.exit(0)
v = json.loads(raw)
print("\n".join([v] if isinstance(v, str) else v))' || true)"
fi

# A version is a prerelease if it carries a SemVer prerelease suffix (0.2.7-alpha).
is_prerelease() { case "$1" in *-*) return 0 ;; *) return 1 ;; esac; }

stable_exists=false
while IFS= read -r v; do
  [ -z "$v" ] && continue
  if ! is_prerelease "$v"; then
    stable_exists=true
    break
  fi
done <<< "$published"

if is_prerelease "$VERSION"; then
  tag="next"
  # Claim `latest` only while nothing stable has ever shipped, so `npx` keeps working pre-1.0.
  if [ "$stable_exists" = true ]; then also_latest=false; else also_latest=true; fi
else
  tag="latest"
  also_latest=false
fi

echo "tag=$tag"
echo "also_latest=$also_latest"
