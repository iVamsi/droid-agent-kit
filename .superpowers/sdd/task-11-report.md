# Task 11 Report — Enriched `android_report_bundle`

## Status
DONE

## Commit
`bee79e4` — feat(mcp): enrich android_report_bundle with module table, safe commands, versions, and readiness

## Test Summary
`./gradlew :mcp-server:test` — BUILD SUCCESSFUL, all 7 tests pass (6 pre-existing + 1 new)

## What Was Done

1. **`mcp-server/build.gradle.kts`** — added `implementation(project(":auditor-cli"))` dependency.

2. **`McpDispatcherTest.kt`** — added `report bundle writes structured markdown with modules table and safe commands` test per the brief's template. Confirmed it failed before implementation.

3. **`DroidAgentMcpDispatcher.kt`** — replaced the 5-line stub `reportBundle()` with the full implementation:
   - Calls `AndroidProjectInspector.inspect()` for project structure
   - Calls `ReadinessAuditor(inspector).audit()` for readiness scoring
   - Builds structured markdown: `# Android Report — <name>`, Generated + Readiness line, `## Modules` table (path, type, namespace, unit tests, android tests, compose), `## Safe Commands`, `## Key Versions` (filtered to interesting keys), `## Warnings` (risks with severity)
   - Writes to `build/droidagentkit/android-report.md` via `Files.writeString()`
   - Returns `ToolResult` with artifact ref pointing to the written file

## Concerns
None. The implementation matches the brief exactly. No new third-party dependencies were introduced; `:auditor-cli` is an internal module already present in the monorepo.

---

## Fix Note (2026-06-20)

### Changes Applied

1. **Fix 1 — `ArtifactWriter.writeText()` in `reportBundle()`**
   - Removed manual `Files.createDirectories()` + `Files.writeString()` block.
   - Replaced with `ArtifactWriter(root.resolve(config.reports.outputDir)).writeText("android-report.md", markdown, ArtifactType.MARKDOWN, "Android project report")`.
   - `ToolResult` now uses the returned `ArtifactRef` directly instead of a manually constructed one.
   - Removed the now-unused `java.nio.file.Files` import; added `ArtifactType` import.

2. **Fix 2 — `## Key Versions` and `## Warnings` always emitted**
   - Removed the `if (report.versions.isNotEmpty())` guard; section header is always written.
   - Empty versions now emits `_(none detected)_` as the body.
   - Removed the `if (auditorReport.risks.isNotEmpty())` guard; section header is always written.
   - Empty risks now emits `_(none detected)_` as the body.

3. **Fix 3 — Test assertions for always-present sections**
   - Added to `report bundle writes structured markdown with modules table and safe commands`:
     - `assertTrue("report must include ## Key Versions section", content.contains("## Key Versions"))`
     - `assertTrue("report must include ## Warnings section", content.contains("## Warnings"))`
   - Corrected JUnit4 `assertTrue(message, condition)` argument order during implementation.

### Test Result
`./gradlew :mcp-server:test` — BUILD SUCCESSFUL, all tests pass.
