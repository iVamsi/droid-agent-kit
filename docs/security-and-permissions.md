# Security and Permissions Model

DroidAgentKit is local-only by default.

## Command Safety

- MCP v1 does not expose arbitrary shell execution.
- Gradle tasks must match configured allowlist patterns.
- adb install and input-style actions are controlled by config.
- Device-specific commands require explicit serials.

Default `.droidagentkit/config.yaml`:

```yaml
schemaVersion: 1
project:
  name: inferred
safety:
  allowGradleTasks:
    - ":*:test*UnitTest"
    - ":*:lint*"
    - ":*:assemble*Debug"
  allowAdbInput: false
  allowAppInstall: true
  allowEmulatorStart: false
  maxCommandSeconds: 600
reports:
  outputDir: "build/droidagentkit"
redaction:
  enabled: true
  extraPatterns: []
```

## MCP Tools

| Tool | Description |
|------|-------------|
| `android_project_inspect` | Inspect Android Gradle modules, versions, manifests, and safe commands. |
| `android_gradle_run` | Run a configured allowlisted Gradle task and capture redacted logs. |
| `android_devices_list` | List adb devices and basic status. |
| `android_app_install` | Install an APK when app install is enabled. |
| `android_app_launch` | Launch an Android package/activity on an explicit device. |
| `android_logcat_capture` | Capture redacted logcat output for a device or package. |
| `android_screen_snapshot` | Capture screenshot and UIAutomator XML from an explicit device. |
| `android_report_bundle` | Create an agent-readable Android diagnostic report bundle. |
| `android_lint_run` | Run an allowlisted lint/detekt Gradle task and parse its XML/SARIF report into structured findings. |
| `android_crash_triage` | Capture logcat from a device and extract structured crash/ANR findings. |
| `android_dependency_check` | Check declared dependency versions for drift and orphaned version-catalog entries. Local-only, no network calls, no "latest version" data. |
| `android_build_performance` | Run an allowlisted Gradle task with `--profile` and surface the slowest tasks from the profile report. |

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
- Unrecognized sections or keys produce a warning (printed to stderr) but do not fail the load.

When validation fails, the CLI prints every error and exits non-zero. The MCP server logs the errors
and falls back to default configuration rather than crashing a long-running process.
