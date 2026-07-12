#!/usr/bin/env bash
set -euo pipefail

private_content_pattern='(/Users/[^/[:space:]]+|/home/[^/[:space:]]+|[A-Za-z]:\\Users\\[^\\[:space:]]+|[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,})'

if git grep -n -I -E "$private_content_pattern" -- . ':!scripts/check-public-hygiene.sh'; then
  echo "Tracked files contain a personal home path or email address." >&2
  exit 1
fi

if command -v gitleaks >/dev/null 2>&1; then
  gitleaks git --redact --no-banner --log-opts=-1 .
fi
