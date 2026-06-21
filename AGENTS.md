# AGENTS.md instructions

## Project Overview

DroidAgentKit is a Kotlin/JVM monorepo for Android agentic developer tooling. It includes a safe MCP/CLI toolbox, an agent readiness auditor, and Compose visual regression report tooling.

## Safe Commands

- `gradle test`
- `gradle :toolbox-core:test`
- `gradle :android-inspector:test`
- `gradle :mcp-server:test`
- `gradle :auditor-cli:test`
- `gradle :visuals-core:test`
- `gradle :visuals-gradle-plugin:test`
- `gradle :visuals-android-test:test`
- `gradle :cli:test`
- `gradle :cli:installDist`

## Agent Boundaries

- Do not add arbitrary shell execution to MCP tools.
- Keep command execution allowlisted and redacted.
- Keep reports deterministic and local-only.
- Do not introduce telemetry.
- Keep `install-mcp` idempotent and user-scope by default.
- Use JDK/Kotlin dependencies already declared in Gradle unless a feature explicitly requires more.

## Definition of Done

- Run `gradle test`.
- Update docs when public CLI, MCP tool names, schemas, or Gradle plugin behavior changes.
- Preserve the alpha security model: local-only, explicit allowlists, redacted command output.
