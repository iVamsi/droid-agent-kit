# Security and Permissions Model

DroidAgentKit is local-only by default. Configuration trust is split (see
[`docs/adrs/0002-threat-model.md`](adrs/0002-threat-model.md)): **grants live only in the user
policy** (`~/.droidagentkit/policy.yaml`, override with `DROIDAGENTKIT_POLICY`); the per-project
`.droidagentkit/config.yaml` can narrow settings (task allowlist, output dir, extra redaction
patterns, `allowAppInstall: false`) but cannot escalate privileges.

## Command Safety

- MCP v1 does not expose arbitrary shell execution.
- Gradle tasks must match configured allowlist patterns (or the user policy must set
  `safety.allowAnyGradleTask: true`). Project configs cannot use catch-all `*` / `**` patterns.
- Gradle arguments are restricted to a small safe set (`--continue`, logging, stacktrace, offline,
  daemon, and rerun controls); init scripts, alternate settings files, project properties, and custom
  JVM properties are blocked.
- Capabilities, opt-in tool groups, host binary paths (`adbPath` etc.), and disabling redaction are
  controlled by the **user policy**, never by the project config.
- Device-specific commands require explicit serials.
- An MCP server is bound to the project root supplied at startup. Tool calls cannot replace that root,
  and generated artifacts must remain under it.

Default project `.droidagentkit/config.yaml` (no grants):

```yaml
schemaVersion: 1
project:
  name: my-app # inferred from settings.gradle(.kts) rootProject.name, or the directory name
safety:
  allowGradleTasks:
    - ":*:test*UnitTest"
    - ":*:lint*"
    - ":*:assemble*Debug"
    - ":*:*AndroidTest"
    - ":*:validate*ScreenshotTest"
  maxCommandSeconds: 600
reports:
  outputDir: "build/droidagentkit"
redaction:
  extraPatterns: []
```

Example user policy `~/.droidagentkit/policy.yaml` that grants storage inspection:

```yaml
schemaVersion: 1
safety:
  allowCapabilities:
    - app_data_read
    - app_install
mcp:
  exposedGroups:
    - storage
```

## Generating config with `droidagent init`

`droidagent init` writes **grants to the user policy** (`~/.droidagentkit/policy.yaml`) and seeds a
grant-free project `.droidagentkit/config.yaml` when one is missing. Run it with no flags in a
terminal for six yes/no prompts (one per tool group, each explaining the risk in plain language,
with follow-ups for bugreport capture, irreversible device-control actions, and golden-image
overwrites). For scripted/CI setup, use a named profile instead:

| Profile | Enables |
|---|---|
| `core` | Nothing extra (the default). |
| `device-control` | Device diagnostics (excluding bugreport) + device control (excluding uninstall/clear-data/permission mutation). |
| `full` | Everything: device diagnostics including bugreport + full device control including uninstall/clear-data/permission mutation + performance tracing + visual regression including golden-image overwrite + storage inspection + network capture. |
| `storage` | Read-only SQLite/SharedPreferences inspection for a debuggable app, alone. |
| `network-experimental` | Emulator-only mitmproxy interception, alone. |

`--profile` accepts a comma-separated list (e.g. `--profile device-control,storage`). `init` refuses
to overwrite an existing user policy unless `--force` is passed. Existing project configs are kept as-is
(they hold no grants). The generated policy is always fully explicit — literal group/capability names,
never a reference to the profile that produced it — so it stays reviewable without needing to know what
a profile currently expands to.

## MCP Tools

| Tool | Description |
|------|-------------|
| `android_project_inspect` | Inspect Android Gradle modules, versions, manifests, and safe commands. |
| `android_gradle_run` | Run a configured allowlisted Gradle task and capture redacted logs. |
| `android_devices_list` | List adb devices and basic status. |
| `android_app_install` | Install an APK when app install is enabled. |
| `android_app_launch` | Launch an Android package/activity on an explicit device. |
| `android_logcat_capture` | Capture redacted logcat output for a device or package. |
| `android_screen_snapshot` | Capture a PNG screenshot from an explicit device. Use `android_accessibility_snapshot` for the UI hierarchy. |
| `android_accessibility_snapshot` | Capture the UIAutomator accessibility hierarchy (not Layout Inspector) from an explicit device and return structured nodes plus the raw XML artifact. |
| `android_report_bundle` | Create an agent-readable Android diagnostic report bundle. |
| `android_lint_run` | Run an allowlisted lint/detekt Gradle task and parse its XML/SARIF report into structured findings. |
| `android_crash_triage` | Capture logcat from a device and extract structured crash/ANR findings. |
| `android_dependency_check` | Check declared dependency versions for drift and orphaned version-catalog entries. Local-only, no network calls, no "latest version" data. |
| `android_build_performance` | Run an allowlisted Gradle task with `--profile` and surface the slowest tasks from the profile report. |
| `android_test_run` | Run an allowlisted unit, device, managed-device, or screenshot task and parse bounded JUnit XML results. |
| `android_build_diagnose` | Run an allowlisted Gradle task and classify recognized compiler, resource, manifest, and configuration-cache failures. |

### Opt-in `device_read` group

These tools are not listed unless the `device_read` tool group is exposed at server startup. `android_bugreport` additionally requires the `sensitive_diagnostics` capability.

| Tool | Description |
| ---- | ----------- |
| `android_permission_audit` | Run `dumpsys package` for a package and report runtime permission grant state as evidence. Read-only. |
| `android_dumpsys` | Run one of a fixed set of dumpsys presets (meminfo, gfxinfo, cpuinfo, batterystats, package) and return a bounded summary plus raw evidence. No arbitrary service names. |
| `android_memory_summary` | Run `dumpsys meminfo` and return Total/Free/Used RAM as evidence with device provenance. Read-only. |
| `android_battery_summary` | Run `dumpsys battery` and return level, status, health, temperature, and voltage as evidence. Read-only. |
| `android_bugreport` | Run `adb bugreport` and stream the ZIP to sensitive artifact storage. Requires `sensitive_diagnostics`. |
| `android_logcat_start` | Start a bounded managed logcat job and return a job id plus a concrete log resource URI. Clients poll `android_job_status`. |
| `android_job_status` | Return the current state and artifact (if any) of a managed job. |
| `android_job_cancel` | Cancel a running managed job and release its device lock. |

### Opt-in `device_control` group

These tools are not listed unless the `device_control` tool group is exposed at server startup. Each tool is gated by one or more capabilities that must be enabled in `safety.allowCapabilities` (or via the legacy aliases). Destructive tools also require `confirmDestructive=true` on the call.

| Tool | Capabilities | Description |
| ---- | ------------ | ----------- |
| `android_emulator_list_avds` | _(none)_ | List available Android Virtual Devices via `emulator -list-avds`. Read-only. |
| `android_emulator_start` | `emulator_control` | Start an emulator AVD as a managed job and return its job id. |
| `android_emulator_stop` | `emulator_control` | Stop a running emulator via `adb emu kill`. |
| `android_emulator_snapshot_save` | `emulator_control` | Save an emulator snapshot via `adb emu avd snapshot save`. |
| `android_emulator_snapshot_restore` | `emulator_restore` | Restore an emulator snapshot via `adb emu avd snapshot load`. |
| `android_app_uninstall` | `app_destructive` | Uninstall a package. Destructive: requires `confirmDestructive`. |
| `android_app_clear_data` | `app_destructive` | Clear app data for a package via `pm clear`. Destructive: requires `confirmDestructive`. |
| `android_deep_link` | `app_control` | Open a deep link URI on a device, scoped to a target package. |
| `android_intent_invoke` | `app_control` | Invoke an `am start` intent with a bounded action, data, mime type, and component. |
| `android_permission_grant` | `permission_mutation` | Grant a runtime permission to a package via `pm grant`. |
| `android_permission_revoke` | `permission_mutation` | Revoke a runtime permission from a package via `pm revoke`. |
| `android_input_tap` | `device_input` | Tap screen coordinates via `input tap`. |
| `android_input_swipe` | `device_input` | Swipe between two coordinates via `input swipe`. |
| `android_input_type` | `device_input` | Type text via `input text`. |
| `android_input_key` | `device_input` | Send a keyevent via `input keyevent`, with optional long-press. |
| `android_file_pull` | `file_export` | Pull a device file into `build/droidagentkit/pulls` and register it as a sensitive artifact. Device path must be under public/external storage; app-private storage (`/data/data`, `/data/user`, `/data/app`) is rejected — use the `storage` group for a debuggable app's own data. |
| `android_file_push` | `file_import` | Push a host file to a device path. Host paths must stay inside an allowed root; device paths are restricted the same way as `android_file_pull`. Destructive: requires `confirmDestructive`. |
| `android_run_flow` | `device_input`, `app_control` | Run a small sequence of primitive device-control actions against one device serial, stopping on the first error by default. Each step is re-authorized individually. |

### Opt-in `perfetto` group

These tools are not listed unless the `perfetto` tool group is exposed at server startup. Both require the `sensitive_diagnostics` capability.

| Tool | Capabilities | Description |
| ---- | ------------ | ----------- |
| `android_perfetto_capture` | `sensitive_diagnostics` | Capture a bounded Perfetto trace on a device (duration, data sources, buffer size, and max file size are all configurable with safe defaults), pull it to sensitive artifact storage, and delete the remote file. |
| `android_perfetto_analyze` | `sensitive_diagnostics` | Run versioned Trace Processor SQL analyses (CPU utilization, main-thread slices, frame jank, binder latency, contention) over a local trace file and return a correlated evidence report. Read-only. Requires a configured `trace_processor_shell` (`safety.traceProcessorPath`, or pass `traceProcessorShell` per call) — there is no bundled or auto-downloaded binary. |

### Opt-in `visuals` group

These tools are not listed unless the `visuals` tool group is exposed at server startup. They operate on host PNG/golden files under the project root and emit only pixel-diff evidence (no synthesized text-clipping, contrast, RTL, semantics, or recomposition findings).

| Tool | Capabilities | Description |
| ---- | ------------ | ----------- |
| `android_visual_diff` | _(none)_ | Diff a baseline PNG against a candidate PNG with the configured tolerance and write a pixel-diff overlay artifact. Read-only. |
| `android_visual_report` | _(none)_ | Read the captures manifest, diff each case against its golden, and return a structured report. Pass a matrix to detect missing captures. Read-only. |
| `android_visual_update_goldens` | `golden_update` | Copy current captures over golden images. Destructive: requires `confirmDestructive=true`. |

The Gradle plugin's `droidAgentVisualsReport` task now wires the extension `matrix` (devices/themes/fontScales/locales) as the expected report matrix and emits `missing-capture:<case>:<envKey>` warnings for any expected environment that was never captured. `failOnAccessibilityWarnings` is deprecated (no evidence-producing accessibility adapter exists yet); setting it is a no-op that emits a deterministic `fail-on-accessibility-warnings-deprecated` config warning rather than being silently ignored.

### Opt-in `storage` group

These tools are not listed unless the `storage` tool group is exposed at server startup. They inspect app data for a **debuggable** package via `adb run-as` — no root required, no production apps. Every call requires the `app_data_read` capability and a `packageName` + `deviceSerial`. SQLite access is strictly read-only: the JDBC connection is opened read-only, write/multi-statement/unsafe-pragma SQL is rejected before execution, and results are row- and cell-bounded. Pulled database files (with `-wal`/`-shm` sidecars) and prefs XML land under `build/droidagentkit/storage/<package>/snapshot/` and are registered as sensitive artifacts.

| Tool | Capabilities | Description |
| ---- | ------------ | ----------- |
| `android_db_list_databases` | `app_data_read` | Snapshot the app's `databases/` dir and list `.db` files with sizes and WAL/SHM sidecar flags. |
| `android_db_schema` | `app_data_read` | Return table + column schemas for a named database (from `sqlite_master` + `PRAGMA table_info`). |
| `android_db_query` | `app_data_read` | Run a single read-only `SELECT` against a named database. Rejects writes, multi-statement input, and unsafe pragmas. |
| `android_prefs_dump` | `app_data_read` | Dump SharedPreferences XML files under `shared_prefs/` as typed key/value entries. |
| `android_file_tree` | `app_data_read` | List files under a relative path (optionally recursive) via `run-as ls -la` / `find`. Paths may not traverse `..`. |

SQLCipher/encrypted or corrupt databases are reported as `unsupported` (not a crash); a non-debuggable package returns `not-debuggable`. The snapshotter force-stops the app first and emits an `app-force-stopped` warning so callers know live state was paused.

### Opt-in `network_experimental` group

These tools are not listed unless the `network_experimental` tool group is exposed at server startup. The group is **experimental and disabled by default**. Interception is **emulator-only** and targets **debuggable** apps; physical-device MITM, Frida, root/device-owner operations, QUIC bypass, and certificate-pinning bypass are explicitly out of scope. A user-installed, debug-trusted CA is a prerequisite — DroidAgentKit never automates CA installation. The mitmproxy executable path is resolved from `safety.mitmProxyPath` (or `MITMPROXY_PATH` env); there is no runtime download.

| Tool | Capabilities | Description |
| ---- | ------------ | ----------- |
| `android_network_capture_start` | `network_interception` | Pre-flight (emulator + debuggable + mitmproxy configured), save the prior global HTTP proxy, install `10.0.2.2:<port>`, and start a bounded mitmproxy (`mitmdump`) managed job that writes a HAR on shutdown. Destructive: requires `confirmDestructive=true`. The prior proxy is restored on success, cancel, timeout, and forced process failure via the managed-job cleanup hook. |
| `android_network_capture_query` | `network_interception` | Parse a finalized HAR into redacted flow summaries (method/scheme/host/path/status/content-type + headers). Bodies are disabled by default (`includeBodies` opts them in, still redacted). Sensitive headers (Authorization, Cookie, Set-Cookie, API keys) are always redacted. Returns `unsupported` with `pinning-or-tls-suspected` when flows were captured but none produced a response. |

The HAR artifact is registered as a sensitive `network_capture` artifact; `capturePath` is confined to the project root. If the HAR is absent the query returns `partial` (`capture-not-finalized`) so the caller knows the capture job is still running or was killed before mitmproxy could flush.

### Capability reporting and public redaction

`android_report_bundle` and the CLI `audit` command surface a **capability summary** — the server's exposed tool groups, enabled capabilities, dangerous flags, optional executables, and prerequisites — as a factual report. It is never used to inflate the 0–100 readiness score; invasive capabilities (Perfetto, DB access, network interception) earn zero readiness points.

`droidagent audit --redact-public` (and the auditor's `redactPublic` flag) produce a share-safe report: absolute project roots become `.`, the home directory and user name are masked, device serials (`emulator-NNNN`, `IP:port`) become `[serial]`, and module directories are relativized to the project root. Stable finding codes (`risk.id`) and project-relative locations are preserved so the report stays diffable.

### Tool annotations

Every tool advertises MCP `annotations` hints so hosts can surface intent: `readOnlyHint` for inspection/snapshot/diagnostic tools, `destructiveHint` for uninstall/clear-data, `idempotentHint` for gradle/install/launch/test, and `openWorldHint` for tools that interact with a device or emulator. Tool groups are filtered once at startup; the server does not emit `notifications/tools/list_changed`.

## MCP Resources & Prompts

Beyond tools, the server advertises MCP `resources` and `prompts` (Cursor/Claude Code/Codex hosts). Android Studio is tools-only — the workspace dispatcher advertises no resources or prompts.

**Resources** (concrete, project-scoped, read-only):

- `droidagent://project/inspect` — static Gradle/manifest inspection report (JSON).
- `droidagent://project/agents-doc` — the project's `AGENTS.md`, if present.
- `droidagent://project/readiness` — the agent-readiness audit report.

**Resource templates** (advertised via `resources/templates/list`):

- `droidagent://artifacts/{id}` — resolve a captured artifact by its opaque, project-scoped id.
- `droidagent://goldens/{case}` — resolve a golden screenshot image by test case name.

Artifact ids are opaque, project-scoped, authenticated, and path-contained; reads of unknown ids return a `resource-not-found` error (-32002).

**Prompts** (advertised only when every required tool is exposed):

`crash-investigation`, `anr-evidence-review`, `build-failure-fix`, `test-failure-triage`, `permission-audit`, `app-startup-profile`, `visual-regression-review`, `dependency-upgrade`. Each returns a user-role workflow that names the tools to run.

## Redaction

Built-in redaction covers:

- `Authorization: Bearer ...`
- Google-style API keys beginning with `AIza`
- password assignments
- token and secret assignments

Extra project-specific regexes can be added under `redaction.extraPatterns`.

## Readiness Score Breakdown

Maximum score: 100 (capped via `coerceIn`).

| Points | Check |
|--------|-------|
| 20 | Build and test commands are discoverable (`testDebugUnitTest`, `assembleDebug`) |
| 15 | At least one module has unit tests (`src/test`) or Android tests (`src/androidTest`) |
| 15 | At least one Gradle module is detected |
| 10 | Agent instructions file exists (`AGENTS.md`, `CLAUDE.md`, `GEMINI.md`, etc.) |
| 10 | CI workflow file detected (`.github/workflows`, `.gitlab-ci.yml`, etc.) |
| 10 | Device/emulator expectations documented in `AGENTS.md` or `README.md` |
| 10 | No likely secrets detected in tracked files |
| 5  | Visual testing hooks detected (`droidAgentVisuals`, Paparazzi, or Roborazzi) |
| 5  | `gradle/libs.versions.toml` version catalog present |
| 5  | Static analysis config detected (`detekt.yml`, `.detekt/`, or ktlint in build files) |
| 5  | `proguard-rules.pro` present in at least one module |
| 5  | Baseline Profile configured in at least one module's build file |

`ReadinessLevel` thresholds: ≥ 90 → AGENT_READY, ≥ 75 → USABLE_WITH_REVIEW, ≥ 50 → SMALL_TASKS_ONLY, < 50 → UNSAFE_FOR_AUTONOMY.

## Config Validation

`droidagent` validates `.droidagentkit/config.yaml` before using it:

- `schemaVersion` must be `1` (or omitted, which defaults to `1`). Any other value is rejected.
- Boolean fields (`allowAdbInput`, `allowAppInstall`, `allowEmulatorStart`, `redaction.enabled`) must be
  literal `true`/`false`.
- `safety.maxCommandSeconds` must be a whole number.
- `reports.outputDir` must be a non-empty relative path inside the project root.
- Unrecognized sections or keys produce a warning (printed to stderr) but do not fail the load.

When validation fails, the CLI prints every error and exits non-zero. The MCP server logs the errors
and falls back to default configuration rather than crashing a long-running process.
