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
## Publishing (npm + MCP registry) — fully automated as of 0.2.2-alpha

`.github/workflows/release.yml` publishes both `@droidagentkit/launcher` to npm and
`distribution/server.json` to the MCP registry on every `v*` tag push, using GitHub Actions
OIDC for both (`npm publish` and `mcp-publisher login github-oidc`) — no stored tokens.

**npm Trusted Publisher gotcha (already fixed, worth remembering):** npmjs.com's config form
took the GitHub owner as free text; it was saved as `ivamsi` (lowercase). GitHub's own URLs are
case-insensitive so that looked fine, but the OIDC token's `repository` claim carries the
exact-cased login (`iVamsi/droid-agent-kit`), and npm compares case-sensitively. Every publish
failed with a generic 404 until the saved value was corrected to `iVamsi` — check this first if
OIDC publishing ever starts failing again after looking correctly configured.

### One-time bootstrap history (for reference, not needed again for this package)

OIDC trusted publishing cannot create a **new** scoped package or MCP registry name — the first
publish of each had to be done manually, once:

```bash
npm login
cd distribution/npm-launcher && npm publish --access public --tag alpha
# then: npmjs.com → package → Settings → Trusted Publisher → GitHub Actions → this repo → release.yml

brew install mcp-publisher   # or install binary from modelcontextprotocol/registry releases
mcp-publisher login github
mcp-publisher publish distribution/server.json
```

If `@droidagentkit/launcher` or `io.github.iVamsi/droidagentkit` ever need to be recreated (new
scope, new registry name), repeat this manual bootstrap once, then trusted publishing takes over.

```bash
curl "https://registry.modelcontextprotocol.io/v0/servers?search=io.github.iVamsi/droidagentkit"
```


## Before 1.0

- Revisit whether an external security audit is warranted once the MCP tool surface is API-stable.
- Consider enabling signature verification in `verification-metadata.xml` once keyring maintenance
  is staffed.
