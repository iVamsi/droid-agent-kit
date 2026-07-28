# Security Policy

## Supported Versions

DroidAgentKit is pre-1.0 (alpha). Security fixes land on `main` and are only guaranteed for the
latest release — there are no backported patches to older tags.

## Reporting a Vulnerability

Please **do not** open a public GitHub issue for security vulnerabilities.

Instead, use GitHub's private vulnerability reporting:

1. Go to the [Security tab](https://github.com/iVamsi/droid-agent-kit/security) of this repository.
2. Click **Report a vulnerability**.
3. Include what you found, the affected version/commit, and steps to reproduce.

You should get an initial response within **3 business days**. Confirmed issues will be fixed and
disclosed via a GitHub Security Advisory once a patch is available.

### Triage SLA (internal)

| Severity | Initial response | Target fix on `main` |
|----------|------------------|----------------------|
| Critical (allowlist/capability/redaction bypass, RCE) | 1 business day | 7 days |
| High (privilege escalation via config/path) | 3 business days | 14 days |
| Medium / Low | 5 business days | next minor release |

Dependabot, CodeQL, and OpenSSF Scorecard alerts are reviewed weekly. Tool-manifest hash drift
(`ToolManifestIntegrityTest`) must be reviewed on every PR that touches tool schemas.

## Scope Notes

DroidAgentKit's security model is described in
[docs/security-and-permissions.md](docs/security-and-permissions.md). In short: everything runs
local-only, Gradle task execution is allowlisted, tool output is redacted before reaching an agent,
and destructive device actions require explicit capability flags plus `confirmDestructive=true`.
Reports that a capability behaves as documented (e.g. `device_control` can uninstall an app when
explicitly enabled and confirmed) are working as intended, not vulnerabilities — reports that a
tool bypasses an allowlist, capability gate, or redaction rule are exactly what this policy is for.
