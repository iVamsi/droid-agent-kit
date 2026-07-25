#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
bash scripts/restore-verification-metadata.sh
git add gradle/verification-metadata.xml
if git diff --cached --quiet; then
  echo "verification-metadata.xml already correct"
  exit 0
fi
git commit -m "fix: restore verification-metadata.xml with junit-bom-5.12.2.module sha"
git push origin main
