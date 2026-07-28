# ADR 0002 — Threat model and config trust split

- **Status:** Accepted
- **Date:** 2026-07-28
- **Decision driver:** pre-publish security hardening (see `docs/adrs/0001-packaging.md` for distribution)

## Context

DroidAgentKit runs as a local MCP server with the user's adb/Gradle privileges. The server reads
`.droidagentkit/config.yaml` from the *target Android project* — a file that any contributor,
dependency script, or coding agent with workspace write access can modify. Before this ADR, that
project file could grant capabilities (`safety.allowCapabilities`), expose opt-in tool groups
(`mcp.exposedGroups`), point tools at arbitrary host binaries (`safety.adbPath` and friends),
disable output redaction (`redaction.enabled: false`), and widen the Gradle task allowlist to `*`.
A malicious or compromised project could therefore escalate its own privileges the next time the
MCP server started — a confused-deputy attack against the user.

## Threat agents

1. **Compromised/malicious target project tree.** Files under the project (including
   `.droidagentkit/config.yaml`, Gradle build scripts, lint/SARIF reports) are attacker-controlled
   input. Build scripts legitimately execute during allowlisted Gradle runs, but they must not be
   able to *expand* the server's authority.
2. **Agent-writable config.** The primary consumer is a coding agent that can edit workspace
   files. "The agent wrote a permissive config for itself" must not grant new powers.
3. **Other processes on loopback.** The HTTP transport is local-only, but any local process can
   connect; the bearer token and Origin checks are the boundary.
4. **Poisoned tool output.** Command output and report-file content flow back into the agent's
   context; secrets must be redacted and content treated as untrusted.

## What is trusted

- The **user policy** (`~/.droidagentkit/policy.yaml`, overridable via `DROIDAGENTKIT_POLICY`).
  It lives outside any project tree and is the *only* configuration that can grant.
- The CLI flags and environment of the `droidagent` process owner.
- The Gradle task allowlist enforcement, capability policy (`DefaultOperationPolicy`), and
  redaction pipeline inside the server itself.

## Decision

Split configuration trust into two tiers:

| Key | Project `config.yaml` | User policy |
|-----|----------------------|-------------|
| `project.name`, `reports.outputDir`, `safety.maxCommandSeconds`, `redaction.extraPatterns` | honored | honored (merged) |
| `safety.allowGradleTasks` | honored, except `*`/`**` (rejected with a warning) | — |
| `safety.allowAppInstall` | honored (can only restrict the default-`true` value; effective = project AND policy) | honored |
| `safety.allowCapabilities`, `safety.allowAdbInput`, `safety.allowEmulatorStart` | **ignored with warning** | honored |
| `safety.adbPath`, `emulatorPath`, `traceProcessorPath`, `mitmProxyPath` | **ignored with warning** | honored |
| `safety.allowAnyGradleTask` (new) | **ignored with warning** | honored — escape hatch that allows any Gradle task |
| `mcp.exposedGroups` | **ignored with warning** | honored |
| `redaction.enabled` | **ignored with warning** (always `true` from project files) | honored |

Principles:

- **Grants are user-scope only.** A project file can narrow privileges (e.g.
  `allowAppInstall: false`, a tighter task allowlist, extra redaction patterns) but never widen
  them beyond the user policy and built-in defaults.
- **Warn, don't silently drop.** Ignored privileged keys produce config warnings naming the user
  policy as the correct location.
- **Fail safe.** Invalid config (either file) falls back to built-in defaults, which are
  capability-free and redaction-on.
- `droidagent init` (wizard and `--profile`) writes grants to the **user policy** and seeds a
  non-privileged project `config.yaml` when missing.

## Consequences

- Existing projects that set privileged keys in `.droidagentkit/config.yaml` lose those grants and
  see warnings; the equivalent must be moved to `~/.droidagentkit/policy.yaml` (or re-run
  `droidagent init --profile ...`, which now writes the policy).
- Per-project grants are still possible by pointing `DROIDAGENTKIT_POLICY` at a different file per
  shell, but the default path is global to the user.
- The readiness auditor reports capability enablement from the *effective* merged configuration.
- Audit checklist items (config-escalation E2E, binary-path confinement) are tracked as tests in
  `toolbox-core` (`ConfigTrustTest`) and `cli`.
