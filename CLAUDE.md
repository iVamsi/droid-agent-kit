# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
# Build CLI distribution
./gradlew :cli:installDist

# Run full test suite
./gradlew test

# Run tests for a specific module
./gradlew :toolbox-core:test
./gradlew :android-inspector:test
./gradlew :mcp-server:test
./gradlew :auditor-cli:test
./gradlew :visuals-core:test
./gradlew :visuals-gradle-plugin:test
./gradlew :cli:test

# Run a single test class
./gradlew :toolbox-core:test --tests "com.droidagentkit.core.ConfigAndSafetyTest"
```

## CLI Usage (after installDist)

```bash
./cli/build/install/droidagent/bin/droidagent inspect --project /path/to/android --format markdown
./cli/build/install/droidagent/bin/droidagent audit --project /path/to/android --write-agents
./cli/build/install/droidagent/bin/droidagent serve-mcp --transport stdio --project auto
./cli/build/install/droidagent/bin/droidagent install-mcp          # register user-wide MCP server
./cli/build/install/droidagent/bin/droidagent install-mcp --dry-run
```

## Architecture

This is a Kotlin/JVM monorepo with no Android SDK dependency — all modules target JVM 17. Modules:

- **`toolbox-core`** — shared primitives: `DroidAgentConfig`, `ProcessRunner`, `Redactor`, `ArtifactWriter`, `ToolResult`. Everything else depends on this.
- **`android-inspector`** — `AndroidProjectInspector` statically parses a target Android project's Gradle files and manifests to produce `AndroidProjectReport` (modules, versions, command matrix). No Gradle execution.
- **`mcp-server`** — `DroidAgentMcpDispatcher` maps MCP tool names to actions (inspect, gradle run, adb, logcat, screenshot, report bundle). `DroidAgentMcpHttpServer` and `DroidAgentStdioServer` wrap the dispatcher for HTTP and stdio transports respectively.
- **`auditor-cli`** — `ReadinessAuditor` scores an Android project's agent-readiness (0–100), producing a `ReadinessReport`. `AgentsDocumentGenerator` and `AgentDocumentWriter` write AGENTS.md and `.agents/skills/android-project/SKILL.md` into the target project.
- **`visuals-core`** — `PngDiffEngine` for pixel-level PNG comparison; `VisualReportBuilder` for diff report output.
- **`visuals-gradle-plugin`** — Gradle plugin `com.droidagentkit.visuals` exposing `droidAgentVisuals` extension and tasks `droidAgentVisualsReport` / `droidAgentVisualsUpdateGoldens`.
- **`visuals-android-test`** — `DroidAgentVisualRule` JUnit rule for use inside Android test projects.
- **`cli`** — `DroidAgentCli` entry point; `DroidAgentCliParser` for arg parsing; `McpInstaller` for registering the MCP server with Claude Code / Codex / other tools; `ProjectLocator` resolves `--project auto` from env vars (`CLAUDE_PROJECT_DIR`, `CODEX_WORKSPACE`, cwd).

### Key design constraints

- **Security model is local-only**: `ProcessRunner` only executes commands whose Gradle task matches the allowlist in `SafetyConfig.allowGradleTasks` (glob patterns). All output is passed through `Redactor` before being returned to agents.
- **No external dependencies** beyond JDK, with one deliberate exception: `toolbox-core` uses zero third-party libraries. `mcp-server` uses `com.sun.net.httpserver` (bundled in JDK) plus `org.jetbrains.kotlinx:kotlinx-serialization-json` (the project's only third-party runtime dependency, added specifically to parse Detekt SARIF reports for `android_lint_run`). The CLI has no DI framework.
- **Config file**: target Android projects can place `.droidagentkit/config.yaml` at their root to override the Gradle task allowlist, redaction patterns, output directory, and adb permissions. Parsed by `DroidAgentConfigLoader` (hand-rolled YAML line parser — no YAML library dependency).
- **Artifacts** always land under `build/droidagentkit/` in the target project (configurable via `reports.outputDir`).
- **MCP tool names** are stable public API: `android_project_inspect`, `android_gradle_run`, `android_devices_list`, `android_app_install`, `android_app_launch`, `android_logcat_capture`, `android_screen_snapshot`, `android_report_bundle`, `android_lint_run`, `android_crash_triage`, `android_dependency_check`, `android_build_performance`. Update docs when these change.

## Agent Boundaries

- Do not add arbitrary shell execution to MCP tools — all command execution must go through `ProcessRunner` with the allowlist enforced.
- Do not introduce telemetry or network calls from the toolkit itself.
- Keep `install-mcp` idempotent and user-scope by default.
- Only add dependencies already present in the Gradle files unless a feature explicitly requires more.
- Update `docs/` when public CLI subcommands, MCP tool names/schemas, or Gradle plugin behavior changes.

## Definition of Done

- `./gradlew test` passes.
- Preserve the alpha security model: local-only, explicit allowlists, redacted command output.
