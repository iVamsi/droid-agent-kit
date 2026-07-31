# Security Policy

## Supported Versions

DroidAgentKit is pre-1.0 (alpha) and maintained by a single person. Security fixes land on
`main` and are only guaranteed for the latest release — there are no backported patches to older
tags. Plan around that before depending on it in an environment where a delayed fix would matter.

## Reporting a Vulnerability

Please **do not** open a public GitHub issue for security vulnerabilities.

Instead, use GitHub's private vulnerability reporting:

1. Go to the [Security tab](https://github.com/iVamsi/droid-agent-kit/security) of this repository.
2. Click **Report a vulnerability**.
3. Include what you found, the affected version/commit, and steps to reproduce.

This project is maintained by one person in their own time, so please treat response times as
best-effort rather than guaranteed: expect an initial acknowledgement within **7 days**.
Confirmed issues are fixed and disclosed via a GitHub Security Advisory once a patch is available.

### Triage targets (internal)

Targets, not commitments — see the response-time note above. They set the order work is picked up
in, not a guarantee to a reporter.

| Severity | Target response | Target fix on `main` |
|----------|-----------------|----------------------|
| Critical (allowlist/capability/redaction bypass, RCE) | 2 days | 7 days |
| High (privilege escalation via config/path) | 7 days | 14 days |
| Medium / Low | 14 days | next minor release |

Dependabot, CodeQL, and OpenSSF Scorecard alerts are reviewed weekly. Tool-manifest hash drift
(`ToolManifestIntegrityTest`) must be reviewed on every PR that touches tool schemas. See
[docs/security-posture.md](docs/security-posture.md) for the per-release checklist.

## Scope Notes

DroidAgentKit's security model is described in
[docs/security-and-permissions.md](docs/security-and-permissions.md). In short: everything runs
local-only, Gradle task execution is allowlisted (and a project config can only narrow that
allowlist, never widen it), tool output is redacted on a best-effort basis before reaching an
agent, and destructive device actions require an explicit capability granted in the user policy.
Note that `confirmDestructive=true` is supplied by the agent, so it guards against an accidental
call rather than a hostile one; the capability is the real boundary.
Reports that a capability behaves as documented (e.g. `device_control` can uninstall an app when
explicitly enabled and confirmed) are working as intended, not vulnerabilities — reports that a
tool bypasses an allowlist, capability gate, or redaction rule are exactly what this policy is for.
