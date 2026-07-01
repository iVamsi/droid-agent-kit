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
