# AGENTS.md instructions

## Project Overview

DroidAgentKit is a Kotlin/JVM monorepo for Android agentic developer tooling. It includes a safe MCP/CLI toolbox, an agent readiness auditor, and Compose visual regression report tooling.

## Safe Commands

- `gradle test`
- `gradle ktlintCheck`
- `gradle :toolbox-core:test`
- `gradle :android-inspector:test`
- `gradle :android-device-core:test`
- `gradle :mcp-server:test`
- `gradle :auditor-cli:test`
- `gradle :perfetto-core:test`
- `gradle :visuals-core:test`
- `gradle :visuals-gradle-plugin:test`
- `gradle :visuals-android-test:test`
- `gradle :storage-inspector:test`
- `gradle :network-core:test`
- `gradle :cli:test`
- `gradle :cli:installDist`
- `bash distribution/smoke-test.sh` (launcher smoke; `DROIDAGENT_E2E=1` for JVM stdio round-trip)

## Agent Boundaries

- Do not add arbitrary shell execution to MCP tools.
- Keep command execution allowlisted and redacted.
- Keep reports deterministic and local-only.
- Do not introduce telemetry.
- Keep `install-mcp` idempotent and user-scope by default.
- Use JDK/Kotlin dependencies already declared in Gradle unless a feature explicitly requires more.
- Do not publish `distribution/server.json` until clean-machine smoke tests pass (see `docs/adrs/0001-packaging.md`).
- Capability enablement is reported, never rewarded in readiness scoring.

## Definition of Done

- Run `gradle test`.
- Update docs when public CLI, MCP tool names, schemas, or Gradle plugin behavior changes.
- Preserve the alpha security model: local-only, explicit allowlists, redacted command output.
