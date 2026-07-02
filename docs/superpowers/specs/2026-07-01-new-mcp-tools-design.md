# New MCP Tools (Workstream D) — Design

Date: 2026-07-01
Status: Approved, ready for `writing-plans`

## Goal

Add four new MCP tools that turn existing raw command output (Gradle logs, logcat, Gradle
profile reports, dependency declarations) into structured, agent-consumable findings —
closing the gap the roadmap identified in
[2026-07-01-opensource-roadmap.md](2026-07-01-opensource-roadmap.md), workstream D.

## Current state

- `DroidAgentMcpDispatcher` (`mcp-server/src/main/kotlin/com/droidagentkit/mcp/DroidAgentMcpDispatcher.kt`,
  322 lines) handles all 8 existing tools inline: schema declaration in `listTools()`, dispatch in
  `call()`, one private handler function per tool.
- Every handler builds a `CommandSpec` and runs it via `ProcessRunner`/`Redactor`, returning a
  `ToolResult` (`schemaVersion`, `status: ResultStatus`, `summary`, `artifacts: List<ArtifactRef>`,
  `redactionsApplied`, `warnings`) — defined in `toolbox-core/.../core/Models.kt`.
- `SafetyConfig.allowGradleTasks` (glob patterns) gates every Gradle task execution; `Redactor`
  scrubs command output before it's returned.
- `visuals-core` already established a structured-findings precedent: `VisualFinding(id, category,
  severity, caseName, title, evidence, likelyCause, suggestedFixPrompt)`.
- No network calls anywhere in the toolkit today (`toolbox-core` has zero third-party dependencies;
  CLAUDE.md's Agent Boundaries explicitly forbid telemetry/network calls from the toolkit itself).

## Non-goals

- No live Maven Central / CVE database lookups. The dependency-check tool is local-only
  (version-drift + orphaned-catalog-entry detection), not an "is this outdated" checker. This was
  discussed explicitly: JDK 17's `HttpClient` would make network calls technically easy without a
  new dependency, but the toolkit's trust story ("never phones home, fully local, allowlisted") was
  judged more valuable than a live version check, and was chosen deliberately over an opt-in-flag
  compromise.
- No ktlint-specific parser this round — its default plain-text report isn't structured enough to
  be worth a bespoke parser; `android_lint_run` falls back to a raw-log summary for any task whose
  report file it doesn't recognize.
- No new Gradle module. The project is intentionally small (~2,200 main LOC across 8 modules); four
  more parser files fit inside `mcp-server` without needing a `diagnostics-core`-style module.

## Architecture

New package `mcp-server/src/main/kotlin/com/droidagentkit/mcp/tools/`, one file per tool:

- `LintResultParser.kt`
- `CrashLogTriage.kt`
- `DependencyVersionChecker.kt`
- `BuildProfileParser.kt`

Each exposes a small class with a pure-ish `parse(...)` or `check(...)` entry point that takes
already-available data (report file contents, captured logcat text, file contents) and returns a
list of structured findings — no `ProcessRunner`/`Redactor` dependency inside the parser itself, so
each is unit-testable with plain fixture strings/files and no mocks. `DroidAgentMcpDispatcher`
gains four new private handler functions that: build the `CommandSpec` (reusing the existing
`runner()`/`CommandSpec` machinery, same as today's handlers), run it, then hand the result to the
matching parser class and fold its findings into the returned map alongside the existing
`ToolResult` fields.

A shared finding shape, added to `toolbox-core/.../core/Models.kt` next to `ArtifactRef`:

```kotlin
data class DiagnosticFinding(
    val category: String,       // e.g. "lint", "crash", "anr", "dependency_drift", "slow_task"
    val severity: Severity,     // reuses existing Severity enum (INFO/WARNING/ERROR/CRITICAL)
    val title: String,
    val detail: String,
    val location: String? = null,  // file:line, package/process name, or task path, as applicable
)
```

`ToolResult` is NOT changed (stays a stable, existing shape) — the four new tools instead return an
envelope map with the existing `ToolResult` fields plus a `"findings"` key
(`List<DiagnosticFinding>`), the same pattern `resultMap()` already uses to shape dispatcher output
as `Map<String, Any>`.

## Tool 1 — `android_lint_run`

**Input schema:** `rootPath` (optional, default cwd), `task` (required — must match
`SafetyConfig.allowGradleTasks`, same allowlist enforcement as `android_gradle_run`).

**Behavior:**
1. Reuses the existing allowlist check and `CommandSpec` execution path from `runGradle()`.
2. After the task completes, scans the project tree for the newest matching report file:
   - Android Lint: `**/build/reports/lint-results*.xml`
   - Detekt: `**/build/reports/detekt/*.xml` and `**/build/reports/detekt/*.sarif`
3. `LintResultParser` parses whichever format is found:
   - Android Lint XML: `<issue severity="..." message="..."><location file="..." line="..."/></issue>`
   - Detekt XML (checkstyle-style) and SARIF (`runs[].results[]`) — both map to
     `DiagnosticFinding(category="lint", severity, title=rule/message, location="file:line")`.
4. If no recognized report file is found, falls back to `status=PARTIAL` with the raw command
   summary and a warning `"no-structured-lint-report-found"` — never fails the whole call just
   because parsing didn't apply.

**Files:** `LintResultParser.kt` (+ test), dispatcher wiring (+ test).

## Tool 2 — `android_crash_triage`

**Input schema:** `deviceSerial` (required), `maxLines` (optional, default 500) — same shape as
`android_logcat_capture`.

**Behavior:**
1. Captures its own fresh logcat via the same `adb -s <serial> logcat -d -t <maxLines>` command
   `logcat()` already runs (does not require a prior separate capture call).
2. `CrashLogTriage` scans the captured (already-redacted) text for two patterns:
   - **Crash:** blocks starting at `FATAL EXCEPTION:` — captures thread name, exception
     type/message (next line), and subsequent `\tat ...` stack frame lines until a blank line or a
     new logcat entry starts.
   - **ANR:** lines matching `ANR in <package>` or `Input dispatching timed out` — captures the
     package/process name and the reason text.
3. Each match becomes `DiagnosticFinding(category="crash"|"anr", severity=CRITICAL, title=headline,
   detail=stack excerpt or reason, location=package/process name)`.
4. No matches → `status=SUCCESS`, empty findings list, summary states none were found in the
   captured window (not an error).

**Files:** `CrashLogTriage.kt` (+ test), dispatcher wiring (+ test).

## Tool 3 — `android_dependency_check`

**Input schema:** `rootPath` (optional, default cwd) only.

**Behavior:**
1. Reads `gradle/libs.versions.toml` if present — parses `[versions]` and `[libraries]` tables
   (hand-rolled line/regex parsing, consistent with `DroidAgentConfigLoader`'s existing approach to
   avoid a TOML library dependency).
2. Scans each module's `build.gradle.kts`/`build.gradle` for direct (non-catalog) dependency
   declarations matching `(implementation|api|testImplementation|...)\("([\w.-]+):([\w.-]+):([\w.-]+)"\)`.
3. Flags:
   - **Version drift:** the same `group:artifact` coordinate appears with two different explicit
     version strings across files.
   - **Orphaned catalog entries:** a `[versions]` key with no `[libraries]` entry referencing it, or
     a `[libraries]` entry no module's build file applies via `libs.<alias>`.
4. This is a best-effort text scan, not full dependency resolution (that would require actually
   running Gradle) — documented as a known limitation in the tool description.
5. No network calls; no "latest version" or "outdated" data at all.

**Files:** `DependencyVersionChecker.kt` (+ test), dispatcher wiring (+ test).

## Tool 4 — `android_build_performance`

**Input schema:** `rootPath` (optional, default cwd), `task` (required, allowlisted, same
enforcement as `android_gradle_run`).

**Behavior:**
1. Runs the task with `--profile` appended, reusing the `runGradle()` execution path.
2. Gradle writes `build/reports/profile/profile-<timestamp>.html`. `BuildProfileParser` locates the
   newest such file and extracts the "Task Execution" table via targeted regex/string scanning of
   the known, stable HTML structure (no HTML-parser dependency) — pulling `(taskPath, durationMs)`
   pairs, plus the summary section's total build time and configuration-phase duration if present.
3. Returns the top 10 slowest tasks as `DiagnosticFinding(category="slow_task", severity=INFO,
   title=taskPath, detail="Xms")`, sorted descending, plus the full HTML report as an
   `ArtifactRef` for drill-down.
4. If the HTML structure doesn't match what the parser expects (Gradle version drift in the report
   format), fails soft: returns the artifact reference with a warning
   `"could-not-parse-profile-report"` instead of crashing.

**Files:** `BuildProfileParser.kt` (+ test), dispatcher wiring (+ test).

## Testing

Every parser is pure (string/file content in → `List<DiagnosticFinding>` out), tested with fixture
data (real-shaped Android Lint XML, Detekt SARIF, logcat crash/ANR blocks, `libs.versions.toml`
snippets, a Gradle `--profile` HTML excerpt) — no mocks, `Files.createTempDirectory` for
file-based fixtures, matching existing repo convention. Dispatcher wiring gets integration tests
alongside the existing 8 in `DroidAgentMcpDispatcherTest`, following the same pattern.

## File map

| File | Change |
|---|---|
| `toolbox-core/.../core/Models.kt` | Add `DiagnosticFinding` data class |
| `mcp-server/.../mcp/tools/LintResultParser.kt` | New |
| `mcp-server/.../mcp/tools/CrashLogTriage.kt` | New |
| `mcp-server/.../mcp/tools/DependencyVersionChecker.kt` | New |
| `mcp-server/.../mcp/tools/BuildProfileParser.kt` | New |
| `mcp-server/.../mcp/DroidAgentMcpDispatcher.kt` | Add 4 tool schemas + 4 handler functions |
| `mcp-server/src/test/.../tools/*Test.kt` | New, one per parser |
| `mcp-server/src/test/.../DroidAgentMcpDispatcherTest.kt` | Extend with 4 new tool cases |
| `docs/security-and-permissions.md` | Document the 4 new tool names/schemas (MCP tool names are stable public API per CLAUDE.md) |
| `README.md` | Update MCP tool list if enumerated there |

## Constraints preserved

- Zero third-party dependencies (hand-rolled XML/SARIF/HTML/TOML parsing, no libraries added).
- No network calls anywhere in the four new tools.
- All Gradle execution stays behind `SafetyConfig.allowGradleTasks`.
- All captured text (logcat, command output) passes through `Redactor` before parsing, same as
  today.
- MCP tool names are stable public API — the 4 new names (`android_lint_run`,
  `android_crash_triage`, `android_dependency_check`, `android_build_performance`) are final as
  designed here.
