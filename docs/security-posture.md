# Ongoing security posture

## Weekly

- Triage Dependabot, CodeQL, and OpenSSF Scorecard alerts (see [SECURITY.md](../SECURITY.md) SLA).
- Review any open Dependabot PRs; keep `gradle/verification-metadata.xml` updated when deps change
  (`./gradlew --write-verification-metadata sha256 help`).

## Every release

1. `bash scripts/check-release-version.sh <version>`
2. Confirm `ToolManifestIntegrityTest` is green (or deliberately bump `PINNED_MANIFEST_SHA256` after
   reviewing tool schema/description diffs).
3. `bash distribution/smoke-test.sh` and `DROIDAGENT_E2E=1 bash distribution/smoke-test.sh` on a
   clean machine before publishing `distribution/server.json` to any MCP registry.
4. First npm publish of `@droidagentkit/launcher` is a **manual bootstrap**; later releases use OIDC
   trusted publishing in `.github/workflows/release.yml`.

## Before 1.0

- Revisit whether an external security audit is warranted once the MCP tool surface is API-stable.
- Consider enabling signature verification in `verification-metadata.xml` once keyring maintenance
  is staffed.
