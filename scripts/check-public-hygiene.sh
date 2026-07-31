#!/usr/bin/env bash
set -euo pipefail

private_content_pattern='(/Users/[^/[:space:]]+|/home/[^/[:space:]]+|[A-Za-z]:\\Users\\[^\\[:space:]]+|[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,})'

if git grep -n -I -E "$private_content_pattern" -- . \
  ':!scripts/check-public-hygiene.sh' \
  ':!toolbox-core/src/test/kotlin/com/droidagentkit/core/JsonAndCommandTest.kt'; then
  echo "Tracked files contain a personal home path or email address." >&2
  exit 1
fi

# The scan above uses `git grep -I`, which skips anything it considers binary. A single stray NUL
# byte in a source file is enough to earn that classification, which would quietly exempt the file
# from the check -- and from any other binary-skipping scanner pointed at this repo. Sources are
# text, so treat an embedded NUL as an error rather than as a file to skip.
binary_sources="$(git grep -I --name-only -e '' -- \
  '*.kt' '*.kts' '*.java' '*.md' '*.yml' '*.yaml' '*.json' '*.sh' '*.toml' '*.xml' \
  > /tmp/dak-text-sources.$$ 2>/dev/null; \
  git ls-files -- '*.kt' '*.kts' '*.java' '*.md' '*.yml' '*.yaml' '*.json' '*.sh' '*.toml' '*.xml' \
  | sort > /tmp/dak-all-sources.$$; \
  sort /tmp/dak-text-sources.$$ -o /tmp/dak-text-sources.$$; \
  comm -23 /tmp/dak-all-sources.$$ /tmp/dak-text-sources.$$)"
rm -f /tmp/dak-text-sources.$$ /tmp/dak-all-sources.$$
if [ -n "$binary_sources" ]; then
  echo "Source files are being treated as binary (likely an embedded NUL byte):" >&2
  echo "$binary_sources" >&2
  echo "Binary-skipping scanners, including the check above, silently ignore these." >&2
  exit 1
fi
