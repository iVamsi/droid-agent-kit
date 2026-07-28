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
./cli/build/install/droidagent/bin/droidagent audit --project /path/to/android --write-agents --redact-public
```

## Distribution

- **Packaging decision:** thin npm launcher is the primary install path; MCPB (`distribution/mcp.json`) is secondary; OCI is rejected (no proven host adb/USB/socket passthrough). Registry metadata (`distribution/server.json`) is published only after clean-machine smoke tests pass.
- **npm launcher:** `distribution/npm-launcher/` spawns `droidagent serve-mcp --transport stdio --project auto`; override with `DROIDAGENT_BIN`. Node is install-time only — the runtime is pure JVM.
- **Smoke test:** `distribution/smoke-test.sh` verifies the launcher prints immutable version metadata; set `DROIDAGENT_E2E=1` to also build the CLI and run a stdio `initialize` round-trip.

## Architecture

This is a Kotlin/JVM monorepo with no Android SDK dependency — all modules target JVM 17. Modules:

- **`toolbox-core`** — shared primitives: `DroidAgentConfig`, `ProcessRunner`, `Redactor`, `ArtifactWriter`, `ToolResult`, user-policy merge. Everything else depends on this.
- **`android-inspector`** — `AndroidProjectInspector` statically parses a target Android project's Gradle files and manifests to produce `AndroidProjectReport` (modules, versions, command matrix). No Gradle execution.
- **`android-device-core`** — device parsers/context shared by MCP tool providers (UI hierarchy, dumpsys, permission audit).
- **`mcp-server`** — `DroidAgentMcpDispatcher` maps MCP tool names to actions (inspect, gradle run, adb, logcat, screenshot, report bundle). `DroidAgentMcpHttpServer` (stateless Streamable HTTP, JSON-response mode) and `DroidAgentStdioServer` wrap the dispatcher for HTTP and stdio transports respectively. Tools carry MCP `annotations` hints (`readOnlyHint`, `destructiveHint`, `idempotentHint`, `openWorldHint`); `McpResourceRegistry`/`McpPromptRegistry` expose project-scoped resources, resource templates, and workflow prompts (advertised only to non-AS hosts; the workspace dispatcher stays tools-only).
- **`auditor-cli`** — `ReadinessAuditor` scores an Android project's agent-readiness (0–100), producing a `ReadinessReport`. `AgentsDocumentGenerator` and `AgentDocumentWriter` write AGENTS.md and `.agents/skills/android-project/SKILL.md` into the target project.
- **`visuals-core`** — `PngDiffEngine` for pixel-level PNG comparison; `VisualReportBuilder` for diff report output.
- **`visuals-gradle-plugin`** — Gradle plugin `com.droidagentkit.visuals` exposing `droidAgentVisuals` extension and tasks `droidAgentVisualsReport` / `droidAgentVisualsUpdateGoldens`.
- **`visuals-android-test`** — `DroidAgentVisualRule` JUnit rule for use inside Android test projects.
- **`perfetto-core`** — Perfetto capture + Trace Processor analysis.
- **`storage-inspector`** — debuggable-app SQLite/prefs/file-tree inspection.
- **`network-core`** — emulator mitmproxy planner/proxy/HAR parse.
- **`cli`** — `DroidAgentCli` entry point; `DroidAgentCliParser` for arg parsing; `McpInstaller` for registering the MCP server with Claude Code / Codex / other tools; `ProjectLocator` resolves `--project auto` from env vars (`CLAUDE_PROJECT_DIR`, `CODEX_WORKSPACE`, `GEMINI_*`, `CURSOR_PROJECT_DIR`, cwd).

### Key design constraints

- **Security model is local-only**: `ProcessRunner` is a generic, capability-agnostic executor that streams output to artifacts and redacts text; it no longer enforces the Gradle task allowlist. Gradle task authorization lives in `SafetyConfig.isGradleTaskAllowed` (called before constructing the command). All output is passed through `Redactor` before being returned to agents.
- **No external dependencies** beyond JDK, with deliberate exceptions: `toolbox-core` uses zero third-party libraries. Runtime third-party deps are `kotlinx-serialization-json` and `sqlite-jdbc` only. The CLI has no DI framework.
- **Config trust split**: grants (capabilities, tool groups, binary paths, disabling redaction) live only in the user policy (`~/.droidagentkit/policy.yaml`). Project `.droidagentkit/config.yaml` can narrow the Gradle allowlist, `outputDir`, and `extraPatterns`. Parsed by `DroidAgentConfigLoader` (hand-rolled YAML line parser — no YAML library dependency).
- **Artifacts** always land under `build/droidagentkit/` in the target project (configurable via `reports.outputDir`).
- **MCP tool names** are stable public API. Core/read groups: `android_project_inspect`, `android_gradle_run`, `android_devices_list`, `android_app_install`, `android_app_launch`, `android_logcat_capture`, `android_screen_snapshot`, `android_accessibility_snapshot`, `android_report_bundle`, `android_lint_run`, `android_crash_triage`, `android_dependency_check`, `android_build_performance`, `android_test_run`, `android_build_diagnose`. Device-control group: `android_emulator_start`, `android_app_clear_data`, `android_app_uninstall`, `android_permission_grant`, `android_permission_revoke`, `android_input_tap`, `android_input_type`, `android_input_key`, `android_file_push`, `android_file_pull`, `android_run_flow`. Perfetto group: `android_perfetto_capture`, `android_perfetto_analyze`. Visuals group: `android_visual_diff`, `android_visual_report`, `android_visual_update_goldens`. Storage group: `android_db_list_databases`, `android_db_schema`, `android_db_query`, `android_prefs_dump`, `android_file_tree`. Network (experimental) group: `android_network_capture_start`, `android_network_capture_query`. Update docs when these change.
- **Opt-in tool groups**: beyond the default `core` group, the server can expose a `device_read` group (`android_permission_audit`, `android_dumpsys`, `android_memory_summary`, `android_battery_summary`, `android_bugreport`, `android_logcat_start`, `android_job_status`, `android_job_cancel`) via `exposedGroups`. These tools are not listed unless the group is enabled; `android_bugreport` additionally requires the `sensitive_diagnostics` capability. A `device_control` group adds bounded mutation tools (`android_emulator_list_avds`, `android_emulator_start`, `android_emulator_stop`, `android_emulator_snapshot_save`, `android_emulator_snapshot_restore`, `android_app_uninstall`, `android_app_clear_data`, `android_deep_link`, `android_intent_invoke`, `android_permission_grant`, `android_permission_revoke`, `android_input_tap`, `android_input_swipe`, `android_input_type`, `android_input_key`, `android_file_pull`, `android_file_push`, `android_run_flow`), each gated by capabilities in `safety.allowCapabilities` (e.g. `emulator_control`, `app_destructive`, `permission_mutation`, `device_input`, `file_export`, `file_import`); destructive tools also require `confirmDestructive=true`. A `visuals` group adds `android_visual_diff`, `android_visual_report`, and `android_visual_update_goldens` (the last requires `golden_update` + `confirmDestructive=true`); these tools emit only pixel-diff evidence and never synthesize accessibility/contrast/RTL/recomposition findings from pixel differences. A `storage` group adds read-only app-data inspection for debuggable packages: `android_db_list_databases`, `android_db_schema`, `android_db_query`, `android_prefs_dump`, `android_file_tree`, all gated by the `app_data_read` capability; SQLite access is opened read-only and rejects writes/multi-statement/unsafe-pragma SQL. A `network_experimental` group adds emulator-only mitmproxy interception: `android_network_capture_start` (destructive, requires `network_interception` + `confirmDestructive=true`, saves/restores the device global proxy around a bounded mitmdump managed job) and `android_network_capture_query` (parses a redacted HAR; bodies off by default; pinning/TLS failures reported as `unsupported`). Physical-device MITM, Frida, root ops, QUIC/pinning bypass, and automated CA install are out of scope.

## Agent Boundaries

- Do not add arbitrary shell execution to MCP tools — all command execution must go through `ProcessRunner` with the allowlist enforced.
- Do not introduce telemetry or network calls from the toolkit itself.
- Keep `install-mcp` idempotent and user-scope by default.
- Only add dependencies already present in the Gradle files unless a feature explicitly requires more.
- Update `docs/` when public CLI subcommands, MCP tool names/schemas, or Gradle plugin behavior changes.

## Definition of Done

- `./gradlew test` passes.
- Preserve the alpha security model: local-only, explicit allowlists, redacted command output.
