# DroidAgentKit — Optimisations & Features Design

Date: 2026-06-20  
Status: Approved

---

## Overview

Three independent, sequentially-deliverable batches of improvements.  
Each batch is fully usable on its own and does not require the next batch to ship first.

---

## Batch 1 — Correctness + Protocol

### 1.1 Screenshot binary fix

**Problem:** `ProcessRunner.run()` reads all command output via `process.inputStream.bufferedReader().readText()`. This decodes bytes as UTF-8, silently corrupting any binary output. `android_screen_snapshot` pipes `adb exec-out screencap -p` through this path — every screenshot it writes is an invalid PNG.

**Design:**

Add `outputMode: OutputMode` to `CommandSpec`:

```kotlin
enum class OutputMode { TEXT, BINARY }

data class CommandSpec(
    val id: String,
    val command: List<String>,
    val workingDirectory: String,
    val mutatesProject: Boolean,
    val requiresDevice: Boolean,
    val timeoutSeconds: Long,
    val outputMode: OutputMode = OutputMode.TEXT,   // new, default keeps all callers unchanged
)
```

`ProcessRunner.run()` branches on `outputMode`:
- `TEXT`: existing path — read as string, redact, write via `ArtifactWriter.writeText()`.
- `BINARY`: read via `inputStream.readBytes()`, write via `Files.write(path, bytes)` (no redaction, no text conversion), return artifact with `ArtifactType.SCREENSHOT`.

`DroidAgentMcpDispatcher.snapshot()` sets `outputMode = OutputMode.BINARY` on the `CommandSpec` it builds. No other callers change.

---

### 1.2 JSON key ordering

**Problem:** `Json.write(Map)` iterates `Map.entries` without sorting. Key order depends on whichever `Map` implementation the caller provides. MCP responses are non-deterministic.

**Fix:** Change `value.entries.joinToString(...)` to `value.entries.sortedBy { it.key.toString() }.joinToString(...)` in `Json.kt`. One line. No API change.

---

### 1.3 MCP input schemas

**Problem:** `listTools()` returns only `name` and `description`. MCP clients need an `inputSchema` (JSON Schema object) per tool to auto-discover what arguments each tool accepts. Without it agents must guess.

**Design:**

`McpTool` gains one field:

```kotlin
data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: Map<String, Any>,
)
```

`DroidAgentMcpDispatcher.listTools()` populates a schema for all 8 tools. Schemas follow JSON Schema Draft 7 (`type: object`, `properties`, `required`). Example for `android_gradle_run`:

```json
{
  "type": "object",
  "properties": {
    "rootPath":       { "type": "string",  "description": "Absolute path to the Android project root. Defaults to cwd." },
    "task":           { "type": "string",  "description": "Gradle task to run. Must match the configured allowlist." },
    "arguments":      { "type": "array",   "items": { "type": "string" }, "description": "Extra Gradle arguments." },
    "rerunTasks":     { "type": "boolean", "description": "Pass --rerun-tasks to Gradle." },
    "stacktrace":     { "type": "boolean", "description": "Pass --stacktrace to Gradle." },
    "timeoutSeconds": { "type": "number",  "description": "Override default timeout (seconds)." }
  },
  "required": ["task"]
}
```

All 8 tools get equivalent schemas (`android_project_inspect`, `android_devices_list`, `android_app_install`, `android_app_launch`, `android_logcat_capture`, `android_screen_snapshot`, `android_report_bundle`). `rootPath` is optional on all tools; device-targeting tools require `deviceSerial`; `android_app_install` requires `apkPath`.

The HTTP `GET /mcp` response and stdio `listTools` path both include the full schema. No change to `call()` dispatch.

---

### 1.4 File scanner filtering

**Problem:** `ReadinessAuditor.findPossibleSecrets()` and `hasVisualHooks()` walk up to 1000/500 files without skipping build outputs, binary files, or dependency caches. They scan `.class` files (producing garbage redaction hits), are slow on large repos, and waste the file limits on non-source files.

**Design:**

Extract a shared predicate into `ReadinessAuditor`:

```kotlin
private fun isScannable(path: Path): Boolean {
    val str = path.toString()
    if (SKIP_DIRS.any { "/$it/" in str || str.endsWith("/$it") }) return false
    val ext = path.fileName.toString().substringAfterLast('.', "").lowercase()
    return ext !in BINARY_EXTENSIONS
}

companion object {
    private val SKIP_DIRS = setOf("build", ".gradle", "node_modules", ".git", ".idea", ".cxx")
    private val BINARY_EXTENSIONS = setOf(
        "class", "jar", "aar", "so", "dylib", "dll",
        "png", "jpg", "jpeg", "gif", "webp",
        "keystore", "jks", "bks", "p12",
        "zip", "apk", "aab", "apks",
    )
}
```

Both `findPossibleSecrets` and `hasVisualHooks` add `.filter { isScannable(it) }` to their stream pipelines. `findPossibleSecrets` also skips files larger than 1 MB (secrets files are never that large; large files are build artefacts or media).

---

## Batch 2 — Security Hardening

### 2.1 Extended Redactor patterns

Six new `Rule` entries added to `Redactor`'s rule list in `Redaction.kt`. All follow the existing `Rule(id, regex, replacement)` structure. No API change.

| Rule ID | Regex | Replacement | Rationale |
|---|---|---|---|
| `aws-access-key` | `AKIA[0-9A-Z]{16}` | `[REDACTED]` | AWS IAM access key IDs have a fixed prefix and length |
| `github-classic-token` | `ghp_[A-Za-z0-9]{36}` | `[REDACTED]` | GitHub classic PATs |
| `github-fine-grained-token` | `github_pat_[A-Za-z0-9_]{82}` | `[REDACTED]` | GitHub fine-grained PATs |
| `pem-private-key` | `-----BEGIN (?:RSA \|EC \|OPENSSH )?PRIVATE KEY-----` | `[REDACTED-PEM]` | Any PEM private key header |
| `firebase-private-key` | `"private_key"\s*:\s*"-----BEGIN` | `"private_key":"[REDACTED]` | Firebase/GCP service account JSON fragment |
| `generic-secret-assignment` | `(?i)([A-Z0-9_]*(?:KEY\|SECRET\|CREDENTIAL)[A-Z0-9_]*\s*[:=]\s*)([^\s\n]{8,})` | `$1[REDACTED]` | `*_KEY=`, `*_SECRET=`, `*_CREDENTIAL=` with ≥ 8-char values; 8-char minimum avoids short config values like `KEY=debug` |

New rules are appended after the existing four so existing `applied` IDs remain stable.

---

## Batch 3 — Inspector + Auditor Depth

### 3.1 Module dependency graph

**Design:** `AndroidModuleSummary` gains:

```kotlin
val moduleDependencies: List<String> = emptyList()
```

`AndroidProjectInspector.inspectModule()` parses `implementation(project(":X"))` and `api(project(":X"))` patterns from the build file text and populates the field. The `android_project_inspect` MCP response includes `moduleDependencies` in each module's map entry. Additive — no schema break.

---

### 3.2 Build variant and flavor detection

`AndroidModuleSummary` gains:

```kotlin
val buildTypes: List<String> = emptyList()
val productFlavors: List<String> = emptyList()
```

`inspectModule()` extracts block names from `buildTypes { ... }` and `productFlavors { ... }` using a block-name regex (same approach as namespace extraction). If flavors are present, `commandSpecsFor()` generates `test<Flavor>DebugUnitTest` and `assemble<Flavor>Debug` variants in addition to the generic ones.

---

### 3.3 Richer version catalog parsing

`parseVersions()` currently only reads the `[versions]` TOML table. It will scan all tables, keying every `alias = "group:name:version"` or `alias = "version-string"` entry under the alias name. Duplicate keys (same alias in `[versions]` and derived from `[libraries]`) prefer `[versions]` values. The combined map returns through the existing `AndroidProjectReport.versions: Map<String, String>` field — no schema change.

---

### 3.4 New auditor readiness signals

Four new checks, each worth 5 points, added to `ReadinessAuditor.audit()`. The existing `libs.versions.toml` bonus (currently implicit in the score) becomes one of these named checks to make the breakdown explicit.

| Points | Check | Files/patterns looked for | Risk ID if absent |
|---|---|---|---|
| 5 | Static analysis config | `detekt.yml`, `.detekt/config.yml`, `ktlint` in any `build.gradle.kts` | `missing-static-analysis` |
| 5 | ProGuard rules | `proguard-rules.pro` in any module directory | `missing-proguard` |
| 5 | Baseline Profile | `baselineProfile` in any `build.gradle.kts` | `missing-baseline-profile` |
| 5 | Version catalog | `gradle/libs.versions.toml` (named risk, previously implicit) | `missing-version-catalog` |

The existing `libs.versions.toml` check already awards 5 points but has no named risk ID — it becomes `missing-version-catalog`. The other three checks are net-new: 15 additional scoring paths on top of the existing 100-point system. The `coerceIn(0, 100)` cap is unchanged, so the effect is that projects previously stuck below 100 due to missing visual hooks or other gaps now have more ways to reach higher bands. `ReadinessLevel` thresholds (90/75/50) are unchanged. Score breakdown added to `docs/security-and-permissions.md`.

---

### 3.5 Enriched `android_report_bundle`

The tool currently writes 5 lines of markdown. New output is a structured document consumed by agents reading a project cold:

```
# Android Report — <project name>

Generated: <ISO timestamp>   Readiness: <score>/100 (<level>)

## Modules
| Path | Type | Namespace | Unit Tests | Android Tests | Compose |
|------|------|-----------|------------|---------------|---------|
| :app | application | com.example | yes | no | yes |
...

## Safe Commands
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
...

## Key Versions
kotlin: 2.1.0   compose-bom: 2025.01.01   hilt: 2.54

## Warnings
[CRITICAL] possible-secret — local.properties: token-assignment
[WARNING]  missing-static-analysis — No Detekt or ktlint config found
```

`android_project_inspect` and `ReadinessAuditor` are both called inside the tool handler. No new MCP tool; same `android_report_bundle` name and endpoint.

---

### 3.6 ProjectLocator — Gemini support

Add `GEMINI_PROJECT_DIR` and `GEMINI_WORKSPACE` to the env var lookup list in `ProjectLocator.resolve()`, inserted before `PWD` in the priority order. One-line change.

---

## What is NOT in scope

- Streaming Gradle output (requires significant ProcessRunner restructure and MCP transport changes — separate initiative)
- Real screenshot capture in `DroidAgentVisualRule` (requires Android instrumentation test environment — separate initiative)
- MCP JSON-RPC 2.0 full compliance (stdio framing, `id` field, `jsonrpc` field) — the current line-per-request protocol works for the tools registered; full JSON-RPC is a larger transport rewrite
- Dependency vulnerability checking against CVE databases (requires network access, violates local-only security model)

---

## Constraints preserved across all batches

- Local-only: no network calls from the toolkit itself
- Zero new third-party dependencies: all changes use JDK stdlib only
- Allowlist enforced: `ProcessRunner` and `DroidAgentMcpDispatcher` command gating unchanged
- All existing test IDs and `ToolResult` status wire names remain stable
