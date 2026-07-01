# DroidAgentKit — Config + CLI Hardening Design

Date: 2026-07-01
Status: Approved
Part of: [2026-07-01-opensource-roadmap.md](2026-07-01-opensource-roadmap.md) (workstream B)

---

## Overview

Two independent, sequentially-deliverable pieces. Both preserve the project's zero-third-party-dependency
constraint — this was an explicit decision: the hand-rolled parsers stay hand-rolled, they just get
validation and help generation added on top.

1. Config loader: malformed/outdated config produces actionable errors instead of a crash or silent
   wrong behavior.
2. CLI: a declarative command registry drives `--help` generation and flag validation for all 8 commands,
   replacing hand-copied per-command logic with no help text and silently-ignored unknown flags.

---

## Part 1 — Config loader hardening

### 1.1 Problem

`DroidAgentConfigLoader.load()` (`toolbox-core/src/main/kotlin/com/droidagentkit/core/Config.kt`) today:

- Declares `schemaVersion: Int = 1` on `DroidAgentConfig` but never reads or validates it from the file —
  a future schema bump would silently misparse an old-format file.
- Calls `.toBoolean()` / `.toLong()` directly on unvalidated strings — a bad value like
  `maxCommandSeconds: soon` throws an uncaught `NumberFormatException` that crashes the caller.
- Silently drops any key it doesn't recognize — a typo like `saftey:` produces a config that quietly
  falls back to defaults with no signal to the user.
- Only ever returns a fully-formed `DroidAgentConfig` — there is no way for a caller to distinguish
  "valid config" from "fell back to defaults because something was wrong."

### 1.2 Design

```kotlin
sealed interface ConfigLoadResult {
    data class Loaded(val config: DroidAgentConfig, val warnings: List<String> = emptyList()) : ConfigLoadResult
    data class Invalid(val errors: List<ConfigError>) : ConfigLoadResult
}

data class ConfigError(val line: Int, val key: String, val message: String)
```

`DroidAgentConfigLoader.load(projectRoot: Path): ConfigLoadResult` replaces the current
`load(projectRoot: Path): DroidAgentConfig`:

- Parses line-by-line as today, but tracks the 1-based line number per entry.
- `schemaVersion` (top-level key, not nested under a section) is read and validated **first, before any
  other parsing**: if the value is missing it defaults to `1`; if present but not parseable as an `Int`,
  or parseable but `!= 1`, parsing stops immediately and `Invalid(listOf(ConfigError(line, "schemaVersion", "unsupported schema version '<raw value>'; this build supports schemaVersion 1")))` is returned —
  a future-schema file's remaining keys are not guaranteed to mean what today's parser assumes, so it is
  not safe to keep scanning for more errors.
- Once `schemaVersion` passes, the rest of the file is parsed in a single pass that collects **every**
  error before returning — it does not stop at the first bad value. `.toBoolean()` calls are replaced with
  a helper that only accepts literal `true`/`false` and produces a `ConfigError` otherwise (Kotlin's
  `String.toBoolean()` silently maps anything non-"true" to `false`, which is exactly the kind of
  silent-wrong-behavior this fixes). `.toLong()` is wrapped in a try/catch producing a `ConfigError` with
  the offending line and value. If any errors were collected during this pass, `Invalid` is returned with
  all of them, so a user fixing config sees every problem in one pass rather than one-by-one.
- Unrecognized top-level keys or section keys become entries in `Loaded.warnings` (a `List<String>`),
  not errors — the load still succeeds with defaults for the unrecognized part. This distinguishes
  "small typo, tell the user, keep going" from "the file is unusable."

### 1.3 Callers

- `DroidAgentMain` (CLI): on `Invalid`, print each error to stderr in `line N: key — message` format and
  exit non-zero before running any command. On `Loaded` with non-empty `warnings`, print them to stderr
  as advisory and continue.
- MCP server bootstrap: on `Invalid`, log the errors and fall back to `DroidAgentConfig.default()` — an
  MCP server is long-running and must not crash the whole process over a bad project config; it should
  degrade to safe defaults and let the user notice via logs.

### 1.4 Explicitly not changing

The YAML-subset format itself (flat 2-level nesting, `- item` lists under a key) is not being replaced
with a general-purpose recursive parser. The actual config schema (`project`, `safety`, `reports`,
`redaction`) is flat and has no foreseeable need for deeper nesting — a fuller YAML parser would solve a
problem that doesn't exist yet.

---

## Part 2 — CLI command registry

### 2.1 Problem

`DroidAgentCliParser` (`cli/src/main/kotlin/com/droidagentkit/cli/DroidAgentCliParser.kt`) hand-codes
option extraction per command with no shared validation:

- No `--help`/`-h` output anywhere — a user must read source or docs to discover flags.
- Unknown flags are silently accepted and ignored (`parseOptions` puts anything starting with `--` into
  a map; nothing ever checks the keys against what a command expects).
- Missing required options use `error()`, which throws `IllegalStateException` and prints a raw Kotlin
  stack trace instead of a usage message.
- An unknown command name silently falls through to `CliCommand.Help` with no explanation of what went
  wrong.

### 2.2 Design

New file `cli/src/main/kotlin/com/droidagentkit/cli/CliCommandSpec.kt`:

```kotlin
data class CliOption(
    val flag: String,              // e.g. "--task"
    val description: String,
    val required: Boolean = false,
    val takesValue: Boolean = true, // false = boolean switch, e.g. --dry-run
)

data class CliCommandSpec(
    val name: String,               // e.g. "gradle"
    val description: String,
    val options: List<CliOption>,
    val freeformOptions: Boolean = false, // true: skip unknown-flag rejection (see 2.4)
)

object CliCommandRegistry {
    val all: List<CliCommandSpec> = listOf(
        CliCommandSpec("serve-mcp", "Run the DroidAgentKit MCP server.", listOf(
            CliOption("--project", "Project root path. Defaults to cwd."),
            CliOption("--transport", "Transport: stdio or http. Defaults to http."),
            CliOption("--host", "Bind host for http transport. Defaults to 127.0.0.1."),
            CliOption("--port", "Bind port for http transport. Defaults to 8765."),
        )),
        CliCommandSpec("inspect", "Inspect an Android project's modules and versions.", listOf(
            CliOption("--project", "Project root path. Defaults to cwd."),
            CliOption("--format", "Output format: markdown or json. Defaults to markdown."),
            CliOption("--output", "Write report to this file instead of stdout."),
        )),
        CliCommandSpec("gradle", "Run an allowlisted Gradle task.", listOf(
            CliOption("--project", "Project root path. Defaults to cwd."),
            CliOption("--task", "Gradle task to run (must match the configured allowlist).", required = true),
        )),
        CliCommandSpec("devices", "List connected adb devices.", listOf(
            CliOption("--project", "Project root path. Defaults to cwd."),
            CliOption("--format", "Output format: json or markdown. Defaults to json."),
        )),
        CliCommandSpec("snapshot", "Capture a device screenshot.", listOf(
            CliOption("--device", "adb device serial.", required = true),
            CliOption("--output", "Output path prefix. Defaults to build/droidagentkit/snapshot."),
        )),
        CliCommandSpec("audit", "Run the agent-readiness auditor.", listOf(
            CliOption("--project", "Project root path. Defaults to cwd."),
            CliOption("--write-agents", "Write AGENTS.md, skill, and config files.", takesValue = false),
            CliOption("--verify", "Exit non-zero if readiness regresses.", takesValue = false),
            CliOption("--fail-under", "Exit non-zero if score is under this threshold."),
            CliOption("--redact-public", "Redact evidence before writing public-facing output.", takesValue = false),
        )),
        CliCommandSpec("visuals", "Run a visual-regression report/golden-update action.", emptyList(), freeformOptions = true),
        CliCommandSpec("install-mcp", "Register DroidAgentKit as a user-scope MCP server.", listOf(
            CliOption("--targets", "Comma-separated: codex, claude, generic, all. Defaults to all."),
            CliOption("--bin", "Override path to the droidagent binary."),
            CliOption("--dry-run", "Preview changes without writing files.", takesValue = false),
            CliOption("--no-claude-apply", "Skip running the Claude Code apply step.", takesValue = false),
        )),
    )
}
```

`DroidAgentCliParser.parse()` becomes:

1. No args, or first token is `-h`/`--help` → return `CliCommand.Help()` (global); `DroidAgentMain` prints
   all command names + descriptions from `CliCommandRegistry.all`.
2. First token doesn't match any `CliCommandSpec.name` → return
   `CliCommand.Help("Unknown command '<name>'. Run 'droidagent --help' to see available commands.")`.
3. Remaining args contain `-h`/`--help` → return `CliCommand.Help()` with that command's name attached;
   `DroidAgentMain` prints that command's flags (from its `CliCommandSpec.options`) with required markers.
4. Otherwise: parse remaining tokens against the matched spec.
   - Unless `freeformOptions` is set, any `--flag` not present in `options` is a collected error.
   - Any `required = true` option missing from the parsed tokens is a collected error.
   - If any errors were collected, return `CliCommand.Help("<joined errors>")`.
   - Otherwise, map the validated option map into the existing concrete `CliCommand` subtype exactly as
     today (this mapping stays hand-written and type-safe — no reflection is introduced).

`CliCommand.Help` changes from a `data object` to:

```kotlin
data class Help(val error: String? = null, val commandName: String? = null) : CliCommand()
```

`DroidAgentMain` branches: `error != null` → print to stderr, exit 1. `commandName != null` → print that
command's usage, exit 0. Otherwise → print global help, exit 0.

### 2.3 `visuals` stays freeform

`visuals <action> [...options]` forwards its action and options to a downstream visuals task whose
option set isn't fixed at the CLI layer (it varies by action and by what the Gradle plugin/report engine
accepts). Its `CliCommandSpec` sets `freeformOptions = true`: the registry still recognizes `visuals` as a
valid command name and includes it in global help, but does not reject unrecognized flags for it. Every
other command gets full validation.

### 2.4 Behavior change to call out publicly

Unknown flags are accepted and silently ignored today; after this change they are a hard error (except
for `visuals`). This is the intended fix, but it is a user-visible CLI behavior change — call it out in
`CHANGELOG.md` (workstream A) and in the CLI section of the README.

---

## Testing

Real fixtures/filesystem, no mocks, following existing test conventions in this repo.

**Config (`toolbox-core/src/test/kotlin/com/droidagentkit/core/`, extend `ConfigAndSafetyTest` or add
`ConfigLoaderTest`):**
- Valid config parses to `Loaded` with the same values as before (regression).
- `schemaVersion: 2` → `Invalid` with an error naming the supported version.
- `schemaVersion: next` (non-numeric) → `Invalid` with an error naming the supported version, not a crash.
- `safety.maxCommandSeconds: soon` → `Invalid` with the correct line number and offending value.
- A config with two separate bad values → `Invalid` with both errors present (not fail-fast).
- An unrecognized key (e.g. `saftey:`) → `Loaded` with a non-empty `warnings` list, config still usable.
- MCP server bootstrap path falls back to `DroidAgentConfig.default()` on `Invalid` rather than throwing.

**CLI (`cli/src/test/kotlin/com/droidagentkit/cli/CliParserTest.kt`):**
- Global `--help` (no args, and explicit `--help`) lists all 8 command names.
- Per-command `--help` for `gradle` and `install-mcp` shows their declared flags.
- Unknown command name → `Help` with a non-null `error` naming the bad command.
- Unknown flag on `gradle` (e.g. `--tsak`) → `Help` with a non-null `error`.
- Missing required `--task` on `gradle` → `Help` with a non-null `error` mentioning `--task`.
- `visuals report --some-freeform-flag value` still parses successfully (no false-positive rejection).
- All 8 existing happy-path invocations still produce the exact same `CliCommand` values as before
  (no regression in the common case).

---

## File map

| File | Change |
|------|--------|
| `toolbox-core/src/main/kotlin/com/droidagentkit/core/Config.kt` | Add `ConfigError`, `ConfigLoadResult` sealed interface |
| `toolbox-core/src/main/kotlin/com/droidagentkit/core/Config.kt` (`DroidAgentConfigLoader`) | Rewrite `load()` to validate `schemaVersion`, collect all errors, return `ConfigLoadResult` |
| `toolbox-core/src/test/kotlin/com/droidagentkit/core/ConfigAndSafetyTest.kt` | Add validation/warning/regression tests above |
| `cli/src/main/kotlin/com/droidagentkit/cli/CliCommandSpec.kt` (new) | `CliOption`, `CliCommandSpec`, `CliCommandRegistry` |
| `cli/src/main/kotlin/com/droidagentkit/cli/DroidAgentCliParser.kt` | Rewrite to consume the registry: help generation, validation, then existing explicit mapping to `CliCommand` |
| `cli/src/main/kotlin/com/droidagentkit/cli/CliCommand.kt` | `Help` becomes `data class Help(val error: String? = null, val commandName: String? = null)` |
| `cli/src/main/kotlin/com/droidagentkit/cli/DroidAgentMain.kt` | Handle `ConfigLoadResult` (print errors/warnings, exit codes); handle new `Help` fields (print usage/errors, correct exit code) |
| `mcp-server/src/main/kotlin/com/droidagentkit/mcp/...` (server bootstrap) | On `ConfigLoadResult.Invalid`, log and fall back to `DroidAgentConfig.default()` |
| `cli/src/test/kotlin/com/droidagentkit/cli/CliParserTest.kt` | Add help/validation/regression tests above |
| `README.md`, `docs/security-and-permissions.md` | Document config validation behavior, `schemaVersion`, and the CLI unknown-flag behavior change |

## Constraints preserved

- Zero new third-party dependencies — JDK stdlib only (explicit project decision, revisited and confirmed
  during this brainstorm rather than assumed).
- No change to the YAML-subset config format or its nesting depth.
- No change to existing `CliCommand` subtypes' fields, or to any command's actual flag names/behavior —
  only validation, help text, and error reporting are new.
- `install-mcp` idempotency and user-scope default behavior unchanged.
