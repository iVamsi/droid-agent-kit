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
## Bootstrap `@droidagentkit/launcher` (one-time)

OIDC trusted publishing cannot create a **new** scoped package. First publish must be
local after creating the npm org/user scope:

```bash
npm login
cd distribution/npm-launcher
npm publish --access public --tag alpha
```

Then on npmjs.com → package → Settings → Trusted Publisher → GitHub Actions → this repo →
`release.yml`. Re-run the failed Release workflow job (or push the next tag).

Until that lands, install via the GitHub Release jar:

```bash
java -jar droidagent-cli-0.2.0-alpha.jar serve-mcp --transport stdio --project auto
```

Do **not** submit `distribution/server.json` to the MCP registry until
`npx -y @droidagentkit/launcher@alpha` smoke is green on a clean machine.


## Before 1.0

- Revisit whether an external security audit is warranted once the MCP tool surface is API-stable.
- Consider enabling signature verification in `verification-metadata.xml` once keyring maintenance
  is staffed.
