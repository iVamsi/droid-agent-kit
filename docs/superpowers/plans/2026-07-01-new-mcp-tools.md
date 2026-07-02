# New MCP Diagnostic Tools Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add four new MCP tools — `android_lint_run`, `android_crash_triage`, `android_dependency_check`, `android_build_performance` — that turn raw Gradle/logcat/dependency-file output into structured `DiagnosticFinding` lists, per `docs/superpowers/specs/2026-07-01-new-mcp-tools-design.md`.

**Architecture:** Each tool's parsing logic lives in its own file under a new `com.droidagentkit.mcp.tools` package in the `mcp-server` module (pure functions/objects, no `ProcessRunner`/`Redactor` dependency — testable with plain fixture strings). `DroidAgentMcpDispatcher` gains one schema entry + one thin handler function per tool, reusing the existing `ProcessRunner`/`CommandSpec`/allowlist machinery.

**Tech Stack:** Kotlin/JVM 17, JUnit 4.13.2, `javax.xml.parsers` (JDK built-in, for XML), `org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0` (new dependency, for SARIF JSON — see Global Constraints).

## Global Constraints

- Zero third-party dependencies is the project default, with **one explicit, user-approved exception**: `mcp-server/build.gradle.kts` gains the `kotlin("plugin.serialization") version "2.3.20"` Gradle plugin and the `org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0` runtime dependency, needed to parse Detekt SARIF (JSON) reports. No other module gains new dependencies. XML parsing uses `javax.xml.parsers` (part of the JDK, not a new dependency).
- No network calls anywhere in the four new tools — matches CLAUDE.md's Agent Boundaries ("Do not introduce telemetry or network calls from the toolkit itself").
- All Gradle task execution stays gated by `SafetyConfig.allowGradleTasks` (glob allowlist) — same enforcement `android_gradle_run` already uses.
- All adb-captured text passes through `Redactor` before any new parsing logic sees it — inherited automatically since parsing always reads the artifact file `ProcessRunner` already wrote (which is post-redaction).
- The four new MCP tool names are final, stable public API: `android_lint_run`, `android_crash_triage`, `android_dependency_check`, `android_build_performance`.
- No mocks in tests — use `Files.createTempDirectory` fixtures and real (or realistically-captured) sample data, matching this repo's existing convention.
- New shared type `DiagnosticFinding` lives in `toolbox-core/src/main/kotlin/com/droidagentkit/core/Models.kt`, next to `ArtifactRef`.

---

## File Map

| File | Change |
|---|---|
| `toolbox-core/src/main/kotlin/com/droidagentkit/core/Models.kt` | Add `DiagnosticFinding` data class (Task 1) |
| `mcp-server/build.gradle.kts` | Add `kotlin("plugin.serialization")` plugin + `kotlinx-serialization-json` dependency (Task 1) |
| `mcp-server/src/main/kotlin/com/droidagentkit/mcp/tools/LintResultParser.kt` | New (Task 1) |
| `mcp-server/src/main/kotlin/com/droidagentkit/mcp/tools/CrashLogTriage.kt` | New (Task 2) |
| `mcp-server/src/main/kotlin/com/droidagentkit/mcp/tools/DependencyVersionChecker.kt` | New (Task 3) |
| `mcp-server/src/main/kotlin/com/droidagentkit/mcp/tools/BuildProfileParser.kt` | New (Task 4) |
| `mcp-server/src/main/kotlin/com/droidagentkit/mcp/DroidAgentMcpDispatcher.kt` | Add 4 tool schemas + 4 handlers + 2 small refactors (Tasks 1, 2, 4) |
| `mcp-server/src/test/kotlin/com/droidagentkit/mcp/tools/LintResultParserTest.kt` | New (Task 1) |
| `mcp-server/src/test/kotlin/com/droidagentkit/mcp/tools/CrashLogTriageTest.kt` | New (Task 2) |
| `mcp-server/src/test/kotlin/com/droidagentkit/mcp/tools/DependencyVersionCheckerTest.kt` | New (Task 3) |
| `mcp-server/src/test/kotlin/com/droidagentkit/mcp/tools/BuildProfileParserTest.kt` | New (Task 4) |
| `mcp-server/src/test/kotlin/com/droidagentkit/mcp/McpDispatcherTest.kt` | Extend (Tasks 1-4) |

---

### Task 1: `android_lint_run`

**Files:**
- Modify: `toolbox-core/src/main/kotlin/com/droidagentkit/core/Models.kt`
- Modify: `mcp-server/build.gradle.kts`
- Create: `mcp-server/src/main/kotlin/com/droidagentkit/mcp/tools/LintResultParser.kt`
- Create: `mcp-server/src/test/kotlin/com/droidagentkit/mcp/tools/LintResultParserTest.kt`
- Modify: `mcp-server/src/main/kotlin/com/droidagentkit/mcp/DroidAgentMcpDispatcher.kt`
- Modify: `mcp-server/src/test/kotlin/com/droidagentkit/mcp/McpDispatcherTest.kt`

**Interfaces:**
- Produces: `data class DiagnosticFinding(val category: String, val severity: Severity, val title: String, val detail: String, val location: String? = null)` in `com.droidagentkit.core`
- Produces: `object LintResultParser` with `parseAndroidLintXml(xml: String): List<DiagnosticFinding>`, `parseDetektCheckstyleXml(xml: String): List<DiagnosticFinding>`, `parseDetektSarif(sarifJson: String): List<DiagnosticFinding>` in `com.droidagentkit.mcp.tools`
- Produces: `private fun runAllowlistedGradleTask(root: Path, task: String, extraArgs: List<String>, timeoutSeconds: Long): ToolResult` in `DroidAgentMcpDispatcher` — Task 4 reuses this.
- Produces: `private fun resultMapWithFindings(result: ToolResult, findings: List<DiagnosticFinding>): Map<String, Any>` and `private fun findingToMap(finding: DiagnosticFinding): Map<String, Any?>` in `DroidAgentMcpDispatcher` — Tasks 2-4 reuse these.
- Consumes: existing `ToolResult`, `ResultStatus`, `CommandSpec`, `ProcessRunner`, `SafetyConfig.isGradleTaskAllowed`, `resultMap(result: ToolResult): Map<String, Any>`, `runner(root: Path): ProcessRunner`, `rootPath(arguments): Path`, `String.safeId(): String` — all pre-existing in `DroidAgentMcpDispatcher`.

- [ ] **Step 1: Add the `DiagnosticFinding` type**

In `toolbox-core/src/main/kotlin/com/droidagentkit/core/Models.kt`, add after the `ArtifactRef` data class (after line 37):

```kotlin
data class DiagnosticFinding(
    val category: String,
    val severity: Severity,
    val title: String,
    val detail: String,
    val location: String? = null,
)
```

- [ ] **Step 2: Add the kotlinx.serialization dependency**

Replace the full contents of `mcp-server/build.gradle.kts` with:

```kotlin
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.3.20"
}

dependencies {
    implementation(project(":toolbox-core"))
    implementation(project(":android-inspector"))
    implementation(project(":auditor-cli"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    testImplementation("junit:junit:4.13.2")
}
```

- [ ] **Step 3: Write the failing tests for `LintResultParser`**

Create `mcp-server/src/test/kotlin/com/droidagentkit/mcp/tools/LintResultParserTest.kt`:

```kotlin
package com.droidagentkit.mcp.tools

import com.droidagentkit.core.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LintResultParserTest {
    @Test
    fun `parses android lint xml issues with severity and location`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <issues format="6" by="lint 8.5.0">
                <issue
                    id="HardcodedText"
                    severity="Warning"
                    message="Hardcoded string &quot;Submit&quot;, should use `@string` resource"
                    category="Internationalization">
                    <location
                        file="src/main/res/layout/activity_main.xml"
                        line="12"
                        column="9"/>
                </issue>
                <issue
                    id="UnusedResources"
                    severity="Error"
                    message="The resource `R.string.old_label` appears to be unused"
                    category="Performance">
                    <location
                        file="src/main/res/values/strings.xml"
                        line="45"/>
                </issue>
            </issues>
        """.trimIndent()

        val findings = LintResultParser.parseAndroidLintXml(xml)

        assertEquals(2, findings.size)
        assertEquals("HardcodedText", findings[0].title)
        assertEquals(Severity.WARNING, findings[0].severity)
        assertEquals("src/main/res/layout/activity_main.xml:12", findings[0].location)
        assertEquals("UnusedResources", findings[1].title)
        assertEquals(Severity.ERROR, findings[1].severity)
    }

    @Test
    fun `returns empty list for malformed android lint xml`() {
        val findings = LintResultParser.parseAndroidLintXml("not xml at all")

        assertTrue(findings.isEmpty())
    }

    @Test
    fun `parses detekt checkstyle xml errors with rule name and location`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <checkstyle version="4.3">
                <file name="/project/app/src/main/kotlin/com/example/MainActivity.kt">
                    <error line="23" column="5" severity="warning" message="Function name is too long" source="detekt.style.FunctionNaming"/>
                    <error line="41" column="1" severity="error" message="Class has too many functions" source="detekt.complexity.TooManyFunctions"/>
                </file>
            </checkstyle>
        """.trimIndent()

        val findings = LintResultParser.parseDetektCheckstyleXml(xml)

        assertEquals(2, findings.size)
        assertEquals("FunctionNaming", findings[0].title)
        assertEquals(Severity.WARNING, findings[0].severity)
        assertEquals("/project/app/src/main/kotlin/com/example/MainActivity.kt:23", findings[0].location)
        assertEquals("TooManyFunctions", findings[1].title)
        assertEquals(Severity.ERROR, findings[1].severity)
    }

    @Test
    fun `parses detekt sarif results with rule id, message, and location`() {
        val sarif = """
            {
              "version": "2.1.0",
              "runs": [
                {
                  "tool": { "driver": { "name": "detekt" } },
                  "results": [
                    {
                      "ruleId": "LongMethod",
                      "level": "warning",
                      "message": { "text": "Method is too long" },
                      "locations": [
                        {
                          "physicalLocation": {
                            "artifactLocation": { "uri": "app/src/main/kotlin/com/example/Util.kt" },
                            "region": { "startLine": 88 }
                          }
                        }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val findings = LintResultParser.parseDetektSarif(sarif)

        assertEquals(1, findings.size)
        assertEquals("LongMethod", findings[0].title)
        assertEquals("Method is too long", findings[0].detail)
        assertEquals(Severity.WARNING, findings[0].severity)
        assertEquals("app/src/main/kotlin/com/example/Util.kt:88", findings[0].location)
    }
}
```

- [ ] **Step 4: Run the tests to verify they fail**

Run: `./gradlew :mcp-server:test --tests "com.droidagentkit.mcp.tools.LintResultParserTest"`
Expected: FAIL — compile error, `LintResultParser` does not exist yet.

- [ ] **Step 5: Implement `LintResultParser`**

Create `mcp-server/src/main/kotlin/com/droidagentkit/mcp/tools/LintResultParser.kt`:

```kotlin
package com.droidagentkit.mcp.tools

import com.droidagentkit.core.DiagnosticFinding
import com.droidagentkit.core.Severity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

object LintResultParser {
    fun parseAndroidLintXml(xml: String): List<DiagnosticFinding> {
        val doc = parseXml(xml) ?: return emptyList()
        val issues = doc.getElementsByTagName("issue")
        return (0 until issues.length).mapNotNull { index ->
            val issue = issues.item(index) as? Element ?: return@mapNotNull null
            val locations = issue.getElementsByTagName("location")
            val location = if (locations.length > 0) {
                val loc = locations.item(0) as Element
                val file = loc.getAttribute("file")
                val line = loc.getAttribute("line")
                if (line.isNotBlank()) "$file:$line" else file.ifBlank { null }
            } else {
                null
            }
            DiagnosticFinding(
                category = "lint",
                severity = mapSeverityWord(issue.getAttribute("severity")),
                title = issue.getAttribute("id").ifBlank { "lint-issue" },
                detail = issue.getAttribute("message"),
                location = location,
            )
        }
    }

    fun parseDetektCheckstyleXml(xml: String): List<DiagnosticFinding> {
        val doc = parseXml(xml) ?: return emptyList()
        val files = doc.getElementsByTagName("file")
        val findings = mutableListOf<DiagnosticFinding>()
        for (fileIndex in 0 until files.length) {
            val fileElement = files.item(fileIndex) as? Element ?: continue
            val fileName = fileElement.getAttribute("name")
            val errors = fileElement.getElementsByTagName("error")
            for (errorIndex in 0 until errors.length) {
                val error = errors.item(errorIndex) as? Element ?: continue
                val line = error.getAttribute("line")
                findings += DiagnosticFinding(
                    category = "lint",
                    severity = mapSeverityWord(error.getAttribute("severity")),
                    title = error.getAttribute("source").substringAfterLast('.').ifBlank { "detekt-issue" },
                    detail = error.getAttribute("message"),
                    location = if (line.isNotBlank()) "$fileName:$line" else fileName,
                )
            }
        }
        return findings
    }

    fun parseDetektSarif(sarifJson: String): List<DiagnosticFinding> {
        val root = try {
            Json.parseToJsonElement(sarifJson).jsonObject
        } catch (error: Exception) {
            return emptyList()
        }
        val runs = root["runs"]?.jsonArray ?: return emptyList()
        val findings = mutableListOf<DiagnosticFinding>()
        for (run in runs) {
            val results = run.jsonObject["results"]?.jsonArray ?: continue
            for (result in results) {
                val obj = result.jsonObject
                val ruleId = obj["ruleId"]?.jsonPrimitive?.content ?: "detekt-issue"
                val message = obj["message"]?.jsonObject?.get("text")?.jsonPrimitive?.content ?: ""
                val level = obj["level"]?.jsonPrimitive?.content ?: "warning"
                val physicalLocation = obj["locations"]?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("physicalLocation")?.jsonObject
                val uri = physicalLocation?.get("artifactLocation")?.jsonObject
                    ?.get("uri")?.jsonPrimitive?.content
                val startLine = physicalLocation?.get("region")?.jsonObject
                    ?.get("startLine")?.jsonPrimitive?.content
                val location = when {
                    uri != null && startLine != null -> "$uri:$startLine"
                    uri != null -> uri
                    else -> null
                }
                findings += DiagnosticFinding(
                    category = "lint",
                    severity = mapSeverityWord(level),
                    title = ruleId,
                    detail = message,
                    location = location,
                )
            }
        }
        return findings
    }

    private fun mapSeverityWord(value: String): Severity = when (value.lowercase()) {
        "fatal", "error" -> Severity.ERROR
        "warning" -> Severity.WARNING
        else -> Severity.INFO
    }

    private fun parseXml(xml: String): Document? = try {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
    } catch (error: Exception) {
        null
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :mcp-server:test --tests "com.droidagentkit.mcp.tools.LintResultParserTest"`
Expected: PASS, 4/4 tests green.

- [ ] **Step 7: Commit the parser**

```bash
git add toolbox-core/src/main/kotlin/com/droidagentkit/core/Models.kt mcp-server/build.gradle.kts mcp-server/src/main/kotlin/com/droidagentkit/mcp/tools/LintResultParser.kt mcp-server/src/test/kotlin/com/droidagentkit/mcp/tools/LintResultParserTest.kt
git commit -m "feat(mcp): add DiagnosticFinding type and LintResultParser"
```

- [ ] **Step 8: Write the failing dispatcher tests**

In `mcp-server/src/test/kotlin/com/droidagentkit/mcp/McpDispatcherTest.kt`, first update the two existing exact-count assertions. Replace the `dispatcher lists expected android tools` test body's expected list (lines 17-26) — change:

```kotlin
        assertEquals(
            listOf(
                "android_project_inspect",
                "android_gradle_run",
                "android_devices_list",
                "android_app_install",
                "android_app_launch",
                "android_logcat_capture",
                "android_screen_snapshot",
                "android_report_bundle",
            ),
            tools,
        )
```

to:

```kotlin
        assertEquals(
            listOf(
                "android_project_inspect",
                "android_gradle_run",
                "android_devices_list",
                "android_app_install",
                "android_app_launch",
                "android_logcat_capture",
                "android_screen_snapshot",
                "android_report_bundle",
                "android_lint_run",
            ),
            tools,
        )
```

And in `each tool exposes an input schema with type object and properties` (line 73), change `assertEquals(8, tools.size)` to `assertEquals(9, tools.size)`.

Then add these new tests at the end of the class, before the final closing `}`:

```kotlin
    @Test
    fun `lint run blocks denied task`() {
        val root = Files.createTempDirectory("dak-lint-denied")
        val dispatcher = DroidAgentMcpDispatcher(DroidAgentConfig.default())

        val result = dispatcher.call("android_lint_run", mapOf("rootPath" to root.toString(), "task" to "clean"))

        assertEquals("blocked", result["status"])
    }

    @Test
    fun `lint run parses android lint xml report into findings`() {
        val root = Files.createTempDirectory("dak-lint-xml")
        val config = DroidAgentConfig.default().copy(
            safety = DroidAgentConfig.default().safety.copy(allowGradleTasks = listOf(":app:lintDebug")),
        )
        writeFakeGradlew(root)
        val reportDir = root.resolve("app/build/reports")
        Files.createDirectories(reportDir)
        Files.writeString(
            reportDir.resolve("lint-results-debug.xml"),
            """
            <issues>
              <issue id="HardcodedText" severity="Warning" message="Hardcoded string">
                <location file="src/main/res/layout/main.xml" line="12"/>
              </issue>
            </issues>
            """.trimIndent(),
        )
        val dispatcher = DroidAgentMcpDispatcher(config)

        val result = dispatcher.call("android_lint_run", mapOf("rootPath" to root.toString(), "task" to ":app:lintDebug"))

        assertEquals("success", result["status"])
        @Suppress("UNCHECKED_CAST")
        val findings = result["findings"] as List<Map<*, *>>
        assertEquals(1, findings.size)
        assertEquals("HardcodedText", findings[0]["title"])
        assertEquals("warning", findings[0]["severity"])
    }

    @Test
    fun `lint run returns partial status when no structured report is found`() {
        val root = Files.createTempDirectory("dak-lint-none")
        val config = DroidAgentConfig.default().copy(
            safety = DroidAgentConfig.default().safety.copy(allowGradleTasks = listOf(":app:ktlintCheck")),
        )
        writeFakeGradlew(root)
        val dispatcher = DroidAgentMcpDispatcher(config)

        val result = dispatcher.call("android_lint_run", mapOf("rootPath" to root.toString(), "task" to ":app:ktlintCheck"))

        assertEquals("partial", result["status"])
        @Suppress("UNCHECKED_CAST")
        val warnings = result["warnings"] as List<*>
        assertTrue(warnings.contains("no-structured-lint-report-found"))
    }

    private fun writeFakeGradlew(root: java.nio.file.Path) {
        val wrapper = root.resolve("gradlew")
        Files.writeString(wrapper, "#!/bin/sh\nexit 0\n")
        Files.setPosixFilePermissions(wrapper, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"))
    }
```

- [ ] **Step 9: Run the dispatcher tests to verify they fail**

Run: `./gradlew :mcp-server:test --tests "com.droidagentkit.mcp.McpDispatcherTest"`
Expected: FAIL — `android_lint_run` is not a recognized tool name yet (falls into the `UNSUPPORTED` branch), and the tool-count/tool-list assertions fail.

- [ ] **Step 10: Wire `android_lint_run` into the dispatcher**

In `mcp-server/src/main/kotlin/com/droidagentkit/mcp/DroidAgentMcpDispatcher.kt`:

Add to the imports (after line 13, before the `data class McpTool` declaration):

```kotlin
import com.droidagentkit.core.DiagnosticFinding
import com.droidagentkit.mcp.tools.LintResultParser
import java.nio.file.Files
```

Add a new entry to the `listTools()` list, immediately after the `android_report_bundle` entry (after line 102's `),` and before the closing `)` of `listOf(...)` at line 103):

```kotlin
        McpTool(
            name = "android_lint_run",
            description = "Run an allowlisted lint/detekt Gradle task and parse its XML/SARIF report into structured findings.",
            inputSchema = schema(
                "task",
                props = mapOf(
                    "rootPath" to rootPathProp,
                    "task" to str("Gradle task to run (must match the configured allowlist)."),
                    "timeoutSeconds" to num("Override command timeout in seconds."),
                ),
            ),
        ),
```

Add a new branch to the `call()` `when` expression, immediately after the `"android_report_bundle" -> reportBundle(arguments)` line:

```kotlin
        "android_lint_run" -> lintRun(arguments)
```

Replace the existing `runGradle` function (lines 146-178) with this refactored version that extracts the shared allowlist/wrapper/execution logic into `runAllowlistedGradleTask`:

```kotlin
    private fun runGradle(arguments: Map<String, Any?>): Map<String, Any> {
        val root = rootPath(arguments)
        val task = arguments["task"]?.toString().orEmpty()
        val args = (arguments["arguments"] as? List<*> ?: emptyList<String>()).map { it.toString() }
        val timeout = arguments["timeoutSeconds"]?.toString()?.toLongOrNull() ?: config.safety.maxCommandSeconds
        val extraFlags = buildList {
            if (arguments["rerunTasks"] == true) add("--rerun-tasks")
            if (arguments["stacktrace"] == true) add("--stacktrace")
        }
        return resultMap(runAllowlistedGradleTask(root, task, extraFlags + args, timeout))
    }

    private fun runAllowlistedGradleTask(root: Path, task: String, extraArgs: List<String>, timeoutSeconds: Long): ToolResult {
        if (!config.safety.isGradleTaskAllowed(task)) {
            return ToolResult(
                status = ResultStatus.BLOCKED,
                summary = "Gradle task '$task' is not allowlisted. Update .droidagentkit/config.yaml to allow it.",
                warnings = listOf("gradle-task-denied"),
            )
        }
        val wrapper = if (System.getProperty("os.name").startsWith("Windows")) "gradlew.bat" else "./gradlew"
        if (!root.resolve(wrapper.removePrefix("./")).exists()) {
            return ToolResult(
                status = ResultStatus.BLOCKED,
                summary = "Gradle wrapper was not found at ${root.resolve(wrapper.removePrefix("./"))}.",
                warnings = listOf("missing-gradle-wrapper"),
            )
        }
        val command = buildList {
            add(wrapper)
            add(task)
            extraArgs.forEach { add(it) }
        }
        return runner(root).run(com.droidagentkit.core.CommandSpec("gradle-${task.safeId()}", command, root.toString(), false, false, timeoutSeconds))
    }

    private fun lintRun(arguments: Map<String, Any?>): Map<String, Any> {
        val root = rootPath(arguments)
        val task = arguments["task"]?.toString().orEmpty()
        val timeout = arguments["timeoutSeconds"]?.toString()?.toLongOrNull() ?: config.safety.maxCommandSeconds
        val runResult = runAllowlistedGradleTask(root, task, emptyList(), timeout)
        if (runResult.status == ResultStatus.BLOCKED) {
            return resultMap(runResult)
        }
        val reportFile = findNewestLintReport(root)
            ?: return resultMapWithFindings(
                runResult.copy(
                    status = ResultStatus.PARTIAL,
                    warnings = runResult.warnings + "no-structured-lint-report-found",
                ),
                emptyList(),
            )
        val text = Files.readString(reportFile)
        val findings = when {
            reportFile.toString().endsWith(".sarif") -> LintResultParser.parseDetektSarif(text)
            text.contains("<issue ") -> LintResultParser.parseAndroidLintXml(text)
            else -> LintResultParser.parseDetektCheckstyleXml(text)
        }
        return resultMapWithFindings(runResult, findings)
    }

    private fun findNewestLintReport(root: Path): Path? {
        val candidates = mutableListOf<Path>()
        Files.walk(root, 6).use { stream ->
            stream.filter { path -> Files.isRegularFile(path) }
                .filter { path ->
                    val parts = root.relativize(path).map { it.toString() }
                    val fileName = path.fileName.toString()
                    parts.contains("build") && parts.contains("reports") &&
                        ((fileName.startsWith("lint-results") && fileName.endsWith(".xml")) ||
                            (parts.contains("detekt") && (fileName.endsWith(".xml") || fileName.endsWith(".sarif"))))
                }
                .forEach { candidates.add(it) }
        }
        return candidates.maxByOrNull { Files.getLastModifiedTime(it).toMillis() }
    }

    private fun resultMapWithFindings(result: ToolResult, findings: List<DiagnosticFinding>): Map<String, Any> =
        resultMap(result) + mapOf("findings" to findings.map(::findingToMap))

    private fun findingToMap(finding: DiagnosticFinding): Map<String, Any?> = mapOf(
        "category" to finding.category,
        "severity" to finding.severity.wireName,
        "title" to finding.title,
        "detail" to finding.detail,
        "location" to finding.location,
    )
```

- [ ] **Step 11: Run the full mcp-server test suite to verify everything passes**

Run: `./gradlew :mcp-server:test`
Expected: PASS, all tests green (existing 8-tool tests plus the new lint tests), no regressions in `android_gradle_run` behavior.

- [ ] **Step 12: Commit the dispatcher wiring**

```bash
git add mcp-server/src/main/kotlin/com/droidagentkit/mcp/DroidAgentMcpDispatcher.kt mcp-server/src/test/kotlin/com/droidagentkit/mcp/McpDispatcherTest.kt
git commit -m "feat(mcp): add android_lint_run tool"
```

---

### Task 2: `android_crash_triage`

**Files:**
- Create: `mcp-server/src/main/kotlin/com/droidagentkit/mcp/tools/CrashLogTriage.kt`
- Create: `mcp-server/src/test/kotlin/com/droidagentkit/mcp/tools/CrashLogTriageTest.kt`
- Modify: `mcp-server/src/main/kotlin/com/droidagentkit/mcp/DroidAgentMcpDispatcher.kt`
- Modify: `mcp-server/src/test/kotlin/com/droidagentkit/mcp/McpDispatcherTest.kt`

**Interfaces:**
- Produces: `object CrashLogTriage` with `triage(logcatText: String): List<DiagnosticFinding>` in `com.droidagentkit.mcp.tools`
- Produces: `private fun runAdbCommand(args: List<String>, id: String, root: Path): ToolResult` in `DroidAgentMcpDispatcher`
- Consumes (from Task 1): `DiagnosticFinding`, `resultMapWithFindings`, `findingToMap`, `resultMap`, `rootPath`

- [ ] **Step 1: Write the failing tests for `CrashLogTriage`**

Create `mcp-server/src/test/kotlin/com/droidagentkit/mcp/tools/CrashLogTriageTest.kt`:

```kotlin
package com.droidagentkit.mcp.tools

import com.droidagentkit.core.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLogTriageTest {
    @Test
    fun `extracts fatal exception block with thread and stack frames`() {
        val logcat = """
            07-01 10:00:00.000  1234  1234 I ActivityManager: Start proc
            07-01 10:00:01.000  1234  1234 E AndroidRuntime: FATAL EXCEPTION: main
            07-01 10:00:01.001  1234  1234 E AndroidRuntime: java.lang.NullPointerException: name must not be null
            07-01 10:00:01.002  1234  1234 E AndroidRuntime: 	at com.example.MainActivity.onCreate(MainActivity.kt:42)
            07-01 10:00:01.003  1234  1234 E AndroidRuntime: 	at android.app.Activity.performCreate(Activity.java:8000)
            07-01 10:00:01.004  1234  1234 I ActivityManager: Process com.example died
        """.trimIndent()

        val findings = CrashLogTriage.triage(logcat)

        assertEquals(1, findings.size)
        assertEquals("crash", findings[0].category)
        assertEquals(Severity.CRITICAL, findings[0].severity)
        assertEquals("main", findings[0].location)
        assertTrue(findings[0].detail.contains("NullPointerException"))
        assertTrue(findings[0].detail.contains("MainActivity.kt:42"))
    }

    @Test
    fun `extracts anr block with package name`() {
        val logcat = """
            07-01 10:00:00.000  1234  1234 I ActivityManager: ANR in com.example.app
            07-01 10:00:00.001  1234  1234 I ActivityManager: Reason: Input dispatching timed out (waiting to send key event)
        """.trimIndent()

        val findings = CrashLogTriage.triage(logcat)

        assertEquals(1, findings.size)
        assertEquals("anr", findings[0].category)
        assertEquals(Severity.CRITICAL, findings[0].severity)
        assertEquals("com.example.app", findings[0].location)
    }

    @Test
    fun `extracts input dispatching timeout without an explicit anr in line`() {
        val logcat = "07-01 10:00:00.000  1234  1234 W InputDispatcher: Input dispatching timed out (Waiting because no window)"

        val findings = CrashLogTriage.triage(logcat)

        assertEquals(1, findings.size)
        assertEquals("anr", findings[0].category)
    }

    @Test
    fun `returns empty list when no crash or anr patterns are present`() {
        val logcat = """
            07-01 10:00:00.000  1234  1234 I ActivityManager: Start proc
            07-01 10:00:01.000  1234  1234 D MyApp: onCreate called
        """.trimIndent()

        val findings = CrashLogTriage.triage(logcat)

        assertTrue(findings.isEmpty())
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :mcp-server:test --tests "com.droidagentkit.mcp.tools.CrashLogTriageTest"`
Expected: FAIL — compile error, `CrashLogTriage` does not exist yet.

- [ ] **Step 3: Implement `CrashLogTriage`**

Create `mcp-server/src/main/kotlin/com/droidagentkit/mcp/tools/CrashLogTriage.kt`:

```kotlin
package com.droidagentkit.mcp.tools

import com.droidagentkit.core.DiagnosticFinding
import com.droidagentkit.core.Severity

object CrashLogTriage {
    private val fatalExceptionPattern = Regex("""FATAL EXCEPTION:\s*(\S+)""")
    private val anrPattern = Regex("""ANR in (\S+)""")
    private val inputTimeoutPattern = Regex("""Input dispatching timed out\s*(\(.*)?""")
    private val stackFramePattern = Regex("""at\s""")

    fun triage(logcatText: String): List<DiagnosticFinding> {
        val lines = logcatText.lines()
        val findings = mutableListOf<DiagnosticFinding>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            val fatalMatch = fatalExceptionPattern.find(line)
            val anrMatch = anrPattern.find(line)
            val timeoutMatch = if (anrMatch == null) inputTimeoutPattern.find(line) else null
            when {
                fatalMatch != null -> {
                    val thread = fatalMatch.groupValues[1]
                    val headline = lines.getOrNull(index + 1)?.substringAfter(": ")?.trim().orEmpty()
                    val frames = mutableListOf<String>()
                    var cursor = index + 2
                    while (cursor < lines.size && stackFramePattern.containsMatchIn(lines[cursor])) {
                        frames.add(lines[cursor].substringAfter(": ").trim())
                        cursor++
                    }
                    findings += DiagnosticFinding(
                        category = "crash",
                        severity = Severity.CRITICAL,
                        title = headline.ifBlank { "Fatal exception on thread $thread" },
                        detail = (listOf(headline) + frames).joinToString("\n"),
                        location = thread,
                    )
                    index = if (cursor > index + 1) cursor else index + 1
                }
                anrMatch != null -> {
                    val process = anrMatch.groupValues[1]
                    val reason = lines.getOrNull(index + 1)?.substringAfter(": ")?.trim().orEmpty()
                    findings += DiagnosticFinding(
                        category = "anr",
                        severity = Severity.CRITICAL,
                        title = "ANR in $process",
                        detail = reason.ifBlank { line.trim() },
                        location = process,
                    )
                    // Skip the reason line too, or its own "Input dispatching timed out" text
                    // would be re-matched as a second, spurious standalone ANR finding.
                    index += 2
                }
                timeoutMatch != null -> {
                    findings += DiagnosticFinding(
                        category = "anr",
                        severity = Severity.CRITICAL,
                        title = "Input dispatching timed out",
                        detail = line.substringAfter(": ").trim().ifBlank { line.trim() },
                        location = null,
                    )
                    index += 1
                }
                else -> index += 1
            }
        }
        return findings
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :mcp-server:test --tests "com.droidagentkit.mcp.tools.CrashLogTriageTest"`
Expected: PASS, 4/4 tests green.

- [ ] **Step 5: Commit the parser**

```bash
git add mcp-server/src/main/kotlin/com/droidagentkit/mcp/tools/CrashLogTriage.kt mcp-server/src/test/kotlin/com/droidagentkit/mcp/tools/CrashLogTriageTest.kt
git commit -m "feat(mcp): add CrashLogTriage parser"
```

- [ ] **Step 6: Write the failing dispatcher test**

In `mcp-server/src/test/kotlin/com/droidagentkit/mcp/McpDispatcherTest.kt`:

Update the tools list in `dispatcher lists expected android tools` — add `"android_crash_triage",` after `"android_lint_run",`.

Update `each tool exposes an input schema with type object and properties` — change `assertEquals(9, tools.size)` to `assertEquals(10, tools.size)`.

Add this test at the end of the class, before the final closing `}`:

```kotlin
    @Test
    fun `crash triage is blocked when device serial is missing`() {
        val root = Files.createTempDirectory("dak-crash-triage")
        val dispatcher = DroidAgentMcpDispatcher(DroidAgentConfig.default())

        val result = dispatcher.call("android_crash_triage", mapOf("rootPath" to root.toString()))

        assertEquals("blocked", result["status"])
        assertTrue(result["summary"].toString().contains("deviceSerial"))
    }
```

- [ ] **Step 7: Run the dispatcher tests to verify they fail**

Run: `./gradlew :mcp-server:test --tests "com.droidagentkit.mcp.McpDispatcherTest"`
Expected: FAIL — `android_crash_triage` is not a recognized tool name yet, and the tool-count/tool-list assertions fail.

- [ ] **Step 8: Wire `android_crash_triage` into the dispatcher**

In `mcp-server/src/main/kotlin/com/droidagentkit/mcp/DroidAgentMcpDispatcher.kt`:

Add to the imports:

```kotlin
import com.droidagentkit.mcp.tools.CrashLogTriage
```

Add a new `McpTool` entry to `listTools()`, immediately after the `android_lint_run` entry added in Task 1:

```kotlin
        McpTool(
            name = "android_crash_triage",
            description = "Capture logcat from a device and extract structured crash/ANR findings.",
            inputSchema = schema(
                "deviceSerial",
                props = mapOf(
                    "deviceSerial" to deviceSerialProp,
                    "maxLines" to num("Maximum number of log lines to capture. Default: 500."),
                ),
            ),
        ),
```

Add a new branch to `call()`'s `when` expression, immediately after `"android_lint_run" -> lintRun(arguments)`:

```kotlin
        "android_crash_triage" -> crashTriage(arguments)
```

Replace the existing `runAdb` function with this refactored version, and add `crashTriage` right after it:

```kotlin
    private fun runAdb(args: List<String>, id: String, root: Path): Map<String, Any> = resultMap(runAdbCommand(args, id, root))

    private fun runAdbCommand(args: List<String>, id: String, root: Path): ToolResult =
        runner(root).run(com.droidagentkit.core.CommandSpec(id, listOf("adb") + args, root.toString(), false, true, 60))

    private fun crashTriage(arguments: Map<String, Any?>): Map<String, Any> {
        val serial = arguments["deviceSerial"]?.toString()
            ?: return resultMap(ToolResult(status = ResultStatus.BLOCKED, summary = "deviceSerial is required for crash triage.", warnings = listOf("missing-device-serial")))
        val maxLines = arguments["maxLines"]?.toString()?.toIntOrNull() ?: 500
        val root = rootPath(arguments)
        val runResult = runAdbCommand(listOf("-s", serial, "logcat", "-d", "-t", maxLines.toString()), "adb-crash-triage", root)
        val logArtifact = runResult.artifacts.firstOrNull() ?: return resultMap(runResult)
        val logText = Files.readString(Path.of(logArtifact.path))
        val findings = CrashLogTriage.triage(logText)
        val summary = if (findings.isEmpty()) {
            "No crashes or ANRs found in the captured logcat window."
        } else {
            "Found ${findings.size} crash/ANR finding(s) in the captured logcat window."
        }
        return resultMapWithFindings(runResult.copy(summary = summary), findings)
    }
```

- [ ] **Step 9: Run the full mcp-server test suite to verify everything passes**

Run: `./gradlew :mcp-server:test`
Expected: PASS, all tests green, no regressions in `android_devices_list`/`android_app_install`/`android_app_launch`/`android_logcat_capture` (all still route through `runAdb`, now delegating to `runAdbCommand`).

- [ ] **Step 10: Commit the dispatcher wiring**

```bash
git add mcp-server/src/main/kotlin/com/droidagentkit/mcp/DroidAgentMcpDispatcher.kt mcp-server/src/test/kotlin/com/droidagentkit/mcp/McpDispatcherTest.kt
git commit -m "feat(mcp): add android_crash_triage tool"
```

---

### Task 3: `android_dependency_check`

**Files:**
- Create: `mcp-server/src/main/kotlin/com/droidagentkit/mcp/tools/DependencyVersionChecker.kt`
- Create: `mcp-server/src/test/kotlin/com/droidagentkit/mcp/tools/DependencyVersionCheckerTest.kt`
- Modify: `mcp-server/src/main/kotlin/com/droidagentkit/mcp/DroidAgentMcpDispatcher.kt`
- Modify: `mcp-server/src/test/kotlin/com/droidagentkit/mcp/McpDispatcherTest.kt`

**Interfaces:**
- Produces: `object DependencyVersionChecker` with `check(root: Path): List<DiagnosticFinding>` in `com.droidagentkit.mcp.tools`
- Consumes (from Task 1): `DiagnosticFinding`, `resultMapWithFindings`, `findingToMap`, `rootPath`

**Known limitation (documented, not a defect):** this is a best-effort text scan (no full Gradle dependency resolution), supports only double-quoted direct dependency declarations, and normalizes catalog aliases by replacing `.` with `-` (handles the common dash-based alias convention; does not special-case underscore-based aliases). This matches the design doc's stated scope.

- [ ] **Step 1: Write the failing tests for `DependencyVersionChecker`**

Create `mcp-server/src/test/kotlin/com/droidagentkit/mcp/tools/DependencyVersionCheckerTest.kt`:

```kotlin
package com.droidagentkit.mcp.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DependencyVersionCheckerTest {
    @Test
    fun `flags same coordinate declared with different versions across modules`() {
        val root = Files.createTempDirectory("dak-dep-drift")
        Files.createDirectories(root.resolve("app"))
        Files.createDirectories(root.resolve("core"))
        Files.writeString(root.resolve("app/build.gradle.kts"), "implementation(\"com.squareup.okhttp3:okhttp:4.11.0\")")
        Files.writeString(root.resolve("core/build.gradle.kts"), "implementation(\"com.squareup.okhttp3:okhttp:4.12.0\")")

        val findings = DependencyVersionChecker.check(root)

        assertEquals(1, findings.size)
        assertEquals("dependency_drift", findings[0].category)
        assertTrue(findings[0].title.contains("com.squareup.okhttp3:okhttp"))
    }

    @Test
    fun `does not flag consistent versions`() {
        val root = Files.createTempDirectory("dak-dep-consistent")
        Files.createDirectories(root.resolve("app"))
        Files.createDirectories(root.resolve("core"))
        Files.writeString(root.resolve("app/build.gradle.kts"), "implementation(\"com.squareup.okhttp3:okhttp:4.12.0\")")
        Files.writeString(root.resolve("core/build.gradle.kts"), "implementation(\"com.squareup.okhttp3:okhttp:4.12.0\")")

        val findings = DependencyVersionChecker.check(root)

        assertTrue(findings.isEmpty())
    }

    @Test
    fun `flags unused catalog library alias`() {
        val root = Files.createTempDirectory("dak-dep-catalog")
        Files.createDirectories(root.resolve("gradle"))
        Files.createDirectories(root.resolve("app"))
        Files.writeString(
            root.resolve("gradle/libs.versions.toml"),
            """
            [versions]
            okhttp = "4.12.0"
            unusedLib = "1.0.0"

            [libraries]
            okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
            orphan-lib = { module = "com.example:orphan", version.ref = "unusedLib" }
            """.trimIndent(),
        )
        Files.writeString(root.resolve("app/build.gradle.kts"), "implementation(libs.okhttp)")

        val findings = DependencyVersionChecker.check(root)

        assertTrue(findings.any { it.title.contains("orphan-lib") })
    }

    @Test
    fun `does not flag when no version catalog is present`() {
        val root = Files.createTempDirectory("dak-dep-no-catalog")
        Files.createDirectories(root.resolve("app"))
        Files.writeString(root.resolve("app/build.gradle.kts"), "implementation(\"com.squareup.okhttp3:okhttp:4.12.0\")")

        val findings = DependencyVersionChecker.check(root)

        assertTrue(findings.isEmpty())
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :mcp-server:test --tests "com.droidagentkit.mcp.tools.DependencyVersionCheckerTest"`
Expected: FAIL — compile error, `DependencyVersionChecker` does not exist yet.

- [ ] **Step 3: Implement `DependencyVersionChecker`**

Create `mcp-server/src/main/kotlin/com/droidagentkit/mcp/tools/DependencyVersionChecker.kt`:

```kotlin
package com.droidagentkit.mcp.tools

import com.droidagentkit.core.DiagnosticFinding
import com.droidagentkit.core.Severity
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

object DependencyVersionChecker {
    private val directDependencyRegex = Regex(
        """(?:implementation|api|testImplementation|androidTestImplementation|compileOnly|runtimeOnly)\(\s*"([\w.-]+):([\w.-]+):([\w.-]+)"\s*\)""",
    )
    private val versionEntryRegex = Regex("""^(\w[\w.-]*)\s*=\s*"([^"]+)"""")
    private val libraryAliasRegex = Regex("""^(\w[\w.-]*)\s*=""")
    private val libraryVersionRefRegex = Regex("""version\.ref\s*=\s*"([\w.-]+)"""")
    private val libraryAliasUsageRegex = Regex("""libs\.([a-zA-Z][\w]*(?:\.[a-zA-Z][\w]*)*)""")

    fun check(root: Path): List<DiagnosticFinding> {
        val buildFiles = findBuildFiles(root)
        val findings = mutableListOf<DiagnosticFinding>()
        findings += checkVersionDrift(buildFiles)

        val catalogPath = root.resolve("gradle/libs.versions.toml")
        if (catalogPath.exists()) {
            findings += checkOrphanedCatalogEntries(catalogPath, buildFiles)
        }
        return findings
    }

    private fun findBuildFiles(root: Path): List<Path> {
        val files = mutableListOf<Path>()
        Files.walk(root, 6).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString() == "build.gradle.kts" || it.fileName.toString() == "build.gradle" }
                .forEach { files.add(it) }
        }
        return files
    }

    private fun checkVersionDrift(buildFiles: List<Path>): List<DiagnosticFinding> {
        val coordinateVersions = mutableMapOf<String, MutableMap<String, MutableList<String>>>()
        for (file in buildFiles) {
            val text = Files.readString(file)
            directDependencyRegex.findAll(text).forEach { match ->
                val (group, artifact, version) = match.destructured
                val coordinate = "$group:$artifact"
                coordinateVersions.getOrPut(coordinate) { mutableMapOf() }
                    .getOrPut(version) { mutableListOf() }
                    .add(file.toString())
            }
        }
        return coordinateVersions.filter { it.value.size > 1 }.map { (coordinate, versions) ->
            val summary = versions.entries.joinToString("; ") { (version, files) -> "$version in ${files.joinToString(", ")}" }
            DiagnosticFinding(
                category = "dependency_drift",
                severity = Severity.WARNING,
                title = "Version drift for $coordinate",
                detail = summary,
                location = coordinate,
            )
        }
    }

    private fun checkOrphanedCatalogEntries(catalogPath: Path, buildFiles: List<Path>): List<DiagnosticFinding> {
        val lines = Files.readAllLines(catalogPath)
        val versionKeys = mutableSetOf<String>()
        val libraryAliases = mutableSetOf<String>()
        val referencedVersionKeys = mutableSetOf<String>()
        var section = ""
        for (rawLine in lines) {
            val line = rawLine.trim()
            when {
                line == "[versions]" -> section = "versions"
                line == "[libraries]" -> section = "libraries"
                line.startsWith("[") -> section = ""
                section == "versions" -> versionEntryRegex.find(line)?.let { versionKeys.add(it.groupValues[1]) }
                section == "libraries" -> {
                    libraryAliasRegex.find(line)?.let { libraryAliases.add(it.groupValues[1]) }
                    libraryVersionRefRegex.find(line)?.let { referencedVersionKeys.add(it.groupValues[1]) }
                }
            }
        }
        val usedAliases = mutableSetOf<String>()
        for (file in buildFiles) {
            val text = Files.readString(file)
            libraryAliasUsageRegex.findAll(text).forEach { usedAliases.add(it.groupValues[1].replace('.', '-')) }
        }

        val findings = mutableListOf<DiagnosticFinding>()
        for (versionKey in versionKeys - referencedVersionKeys) {
            findings += DiagnosticFinding(
                category = "dependency_drift",
                severity = Severity.INFO,
                title = "Unused version catalog entry: $versionKey",
                detail = "No [libraries] entry in libs.versions.toml references version.ref = \"$versionKey\".",
                location = "gradle/libs.versions.toml",
            )
        }
        for (alias in libraryAliases - usedAliases) {
            findings += DiagnosticFinding(
                category = "dependency_drift",
                severity = Severity.INFO,
                title = "Unused catalog library: $alias",
                detail = "No build file references libs.${alias.replace('-', '.')}.",
                location = "gradle/libs.versions.toml",
            )
        }
        return findings
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :mcp-server:test --tests "com.droidagentkit.mcp.tools.DependencyVersionCheckerTest"`
Expected: PASS, 4/4 tests green.

- [ ] **Step 5: Commit the checker**

```bash
git add mcp-server/src/main/kotlin/com/droidagentkit/mcp/tools/DependencyVersionChecker.kt mcp-server/src/test/kotlin/com/droidagentkit/mcp/tools/DependencyVersionCheckerTest.kt
git commit -m "feat(mcp): add DependencyVersionChecker"
```

- [ ] **Step 6: Write the failing dispatcher test**

In `mcp-server/src/test/kotlin/com/droidagentkit/mcp/McpDispatcherTest.kt`:

Update the tools list in `dispatcher lists expected android tools` — add `"android_dependency_check",` after `"android_crash_triage",`.

Update `each tool exposes an input schema with type object and properties` — change `assertEquals(10, tools.size)` to `assertEquals(11, tools.size)`.

Add this test at the end of the class, before the final closing `}`:

```kotlin
    @Test
    fun `dependency check flags version drift across modules`() {
        val root = Files.createTempDirectory("dak-dispatch-dep")
        Files.createDirectories(root.resolve("app"))
        Files.createDirectories(root.resolve("core"))
        Files.writeString(root.resolve("app/build.gradle.kts"), "implementation(\"com.squareup.okhttp3:okhttp:4.11.0\")")
        Files.writeString(root.resolve("core/build.gradle.kts"), "implementation(\"com.squareup.okhttp3:okhttp:4.12.0\")")
        val dispatcher = DroidAgentMcpDispatcher(DroidAgentConfig.default())

        val result = dispatcher.call("android_dependency_check", mapOf("rootPath" to root.toString()))

        assertEquals("success", result["status"])
        @Suppress("UNCHECKED_CAST")
        val findings = result["findings"] as List<Map<*, *>>
        assertEquals(1, findings.size)
    }
```

- [ ] **Step 7: Run the dispatcher tests to verify they fail**

Run: `./gradlew :mcp-server:test --tests "com.droidagentkit.mcp.McpDispatcherTest"`
Expected: FAIL — `android_dependency_check` is not a recognized tool name yet, and the tool-count/tool-list assertions fail.

- [ ] **Step 8: Wire `android_dependency_check` into the dispatcher**

In `mcp-server/src/main/kotlin/com/droidagentkit/mcp/DroidAgentMcpDispatcher.kt`:

Add to the imports:

```kotlin
import com.droidagentkit.mcp.tools.DependencyVersionChecker
```

Add a new `McpTool` entry to `listTools()`, immediately after the `android_crash_triage` entry added in Task 2:

```kotlin
        McpTool(
            name = "android_dependency_check",
            description = "Check declared dependency versions for drift and orphaned version-catalog entries. Local-only, no network calls, no 'latest version' data.",
            inputSchema = schema(props = mapOf("rootPath" to rootPathProp)),
        ),
```

Add a new branch to `call()`'s `when` expression, immediately after `"android_crash_triage" -> crashTriage(arguments)`:

```kotlin
        "android_dependency_check" -> dependencyCheck(arguments)
```

Add the new handler function, near `crashTriage`:

```kotlin
    private fun dependencyCheck(arguments: Map<String, Any?>): Map<String, Any> {
        val root = rootPath(arguments)
        val findings = DependencyVersionChecker.check(root)
        val summary = if (findings.isEmpty()) {
            "No dependency version drift or orphaned catalog entries found."
        } else {
            "Found ${findings.size} dependency finding(s)."
        }
        return resultMapWithFindings(ToolResult(status = ResultStatus.SUCCESS, summary = summary), findings)
    }
```

- [ ] **Step 9: Run the full mcp-server test suite to verify everything passes**

Run: `./gradlew :mcp-server:test`
Expected: PASS, all tests green.

- [ ] **Step 10: Commit the dispatcher wiring**

```bash
git add mcp-server/src/main/kotlin/com/droidagentkit/mcp/DroidAgentMcpDispatcher.kt mcp-server/src/test/kotlin/com/droidagentkit/mcp/McpDispatcherTest.kt
git commit -m "feat(mcp): add android_dependency_check tool"
```

---

### Task 4: `android_build_performance`

**Files:**
- Create: `mcp-server/src/main/kotlin/com/droidagentkit/mcp/tools/BuildProfileParser.kt`
- Create: `mcp-server/src/test/kotlin/com/droidagentkit/mcp/tools/BuildProfileParserTest.kt`
- Modify: `mcp-server/src/main/kotlin/com/droidagentkit/mcp/DroidAgentMcpDispatcher.kt`
- Modify: `mcp-server/src/test/kotlin/com/droidagentkit/mcp/McpDispatcherTest.kt`

**Interfaces:**
- Produces: `data class TaskTiming(val taskPath: String, val durationMs: Long)`, `data class BuildProfileResult(val taskTimings: List<TaskTiming>, val totalBuildTimeMs: Long?, val configurationTimeMs: Long?)`, `object BuildProfileParser` with `parse(html: String): BuildProfileResult` in `com.droidagentkit.mcp.tools`
- Consumes (from Task 1): `runAllowlistedGradleTask`, `resultMapWithFindings`, `DiagnosticFinding`, `rootPath`

**Ground truth used for the HTML structure below:** captured by running `./gradlew :toolbox-core:test --profile` in this repo and inspecting the generated `build/reports/profile/profile-*.html` — the "Task Execution" section (`id="tab4"`) and "Summary" section (`id="tab0"`) table structures are copied verbatim from that real output, not guessed.

- [ ] **Step 1: Write the failing tests for `BuildProfileParser`**

Create `mcp-server/src/test/kotlin/com/droidagentkit/mcp/tools/BuildProfileParserTest.kt`:

```kotlin
package com.droidagentkit.mcp.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildProfileParserTest {
    @Test
    fun `parses task timings sorted by duration descending excluding total rows`() {
        val result = BuildProfileParser.parse(SAMPLE_PROFILE_HTML)

        assertEquals(2, result.taskTimings.size)
        assertEquals(":toolbox-core:compileKotlin", result.taskTimings[0].taskPath)
        assertEquals(12L, result.taskTimings[0].durationMs)
        assertEquals(":toolbox-core:compileTestKotlin", result.taskTimings[1].taskPath)
        assertEquals(6L, result.taskTimings[1].durationMs)
    }

    @Test
    fun `extracts total build time and configuration time from the summary tab`() {
        val result = BuildProfileParser.parse(SAMPLE_PROFILE_HTML)

        assertEquals(487L, result.totalBuildTimeMs)
        assertEquals(66L, result.configurationTimeMs)
    }

    @Test
    fun `returns empty result when the task execution section is missing`() {
        val result = BuildProfileParser.parse("<html><body>no profile data</body></html>")

        assertTrue(result.taskTimings.isEmpty())
        assertNull(result.totalBuildTimeMs)
    }

    companion object {
        private val SAMPLE_PROFILE_HTML = """
            <html><body>
            <div class="tab" id="tab0">
            <h2>Summary</h2>
            <table>
            <thead>
            <tr>
            <th>Description</th>
            <th class="numeric">Duration</th>
            </tr>
            </thead>
            <tr>
            <td>Total Build Time</td>
            <td class="numeric">0.487s</td>
            </tr>
            <tr>
            <td>Configuring Projects</td>
            <td class="numeric">0.066s</td>
            </tr>
            </table>
            </div>
            <div class="tab" id="tab4">
            <h2>Task Execution</h2>
            <table>
            <thead>
            <tr>
            <th>Task</th>
            <th class="numeric">Duration</th>
            <th>Result</th>
            </tr>
            </thead>
            <tr>
            <td>:toolbox-core</td>
            <td class="numeric">0.021s</td>
            <td>(total)</td>
            </tr>
            <tr>
            <td class="indentPath">:toolbox-core:compileKotlin</td>
            <td class="numeric">0.012s</td>
            <td>UP-TO-DATE</td>
            </tr>
            <tr>
            <td class="indentPath">:toolbox-core:compileTestKotlin</td>
            <td class="numeric">0.006s</td>
            <td>UP-TO-DATE</td>
            </tr>
            </table>
            </div>
            </body></html>
        """.trimIndent()
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :mcp-server:test --tests "com.droidagentkit.mcp.tools.BuildProfileParserTest"`
Expected: FAIL — compile error, `BuildProfileParser` does not exist yet.

- [ ] **Step 3: Implement `BuildProfileParser`**

Create `mcp-server/src/main/kotlin/com/droidagentkit/mcp/tools/BuildProfileParser.kt`:

```kotlin
package com.droidagentkit.mcp.tools

data class TaskTiming(val taskPath: String, val durationMs: Long)

data class BuildProfileResult(
    val taskTimings: List<TaskTiming>,
    val totalBuildTimeMs: Long?,
    val configurationTimeMs: Long?,
)

object BuildProfileParser {
    private val taskRowRegex = Regex(
        """<tr>\s*<td[^>]*>([^<]+)</td>\s*<td class="numeric">([^<]+)</td>\s*<td>([^<]*)</td>\s*</tr>""",
    )
    private val durationRegex = Regex("""(?:(\d+)m\s*)?(?:([\d.]+)s)?""")

    fun parse(html: String): BuildProfileResult {
        val tab4Start = html.indexOf("id=\"tab4\"")
        if (tab4Start == -1) return BuildProfileResult(emptyList(), null, null)
        val nextTabStart = html.indexOf("<div class=\"tab\"", tab4Start + 1)
        val section = if (nextTabStart == -1) html.substring(tab4Start) else html.substring(tab4Start, nextTabStart)

        val timings = taskRowRegex.findAll(section).mapNotNull { match ->
            val (task, durationText, result) = match.destructured
            if (result.trim() == "(total)") return@mapNotNull null
            val durationMs = parseDurationToMs(durationText.trim()) ?: return@mapNotNull null
            TaskTiming(task.trim(), durationMs)
        }.toList()

        return BuildProfileResult(
            taskTimings = timings.sortedByDescending { it.durationMs },
            totalBuildTimeMs = extractSummaryDuration(html, "Total Build Time"),
            configurationTimeMs = extractSummaryDuration(html, "Configuring Projects"),
        )
    }

    private fun extractSummaryDuration(html: String, label: String): Long? {
        val regex = Regex(Regex.escape("<td>$label</td>") + """\s*<td class="numeric">([^<]+)</td>""")
        val match = regex.find(html) ?: return null
        return parseDurationToMs(match.groupValues[1].trim())
    }

    private fun parseDurationToMs(text: String): Long? {
        val match = durationRegex.matchEntire(text) ?: return null
        if (match.groupValues[1].isBlank() && match.groupValues[2].isBlank()) return null
        val minutes = match.groupValues[1].toLongOrNull() ?: 0L
        val seconds = match.groupValues[2].toDoubleOrNull() ?: 0.0
        return minutes * 60_000 + Math.round(seconds * 1000)
    }
}
```

Note the use of `Math.round` (not `.toLong()` truncation) when converting seconds to milliseconds: `0.487` is not exactly representable in a `Double` (it's stored as `≈0.48699999999999999`), so `0.487 * 1000 = 486.99999999999994`, and naive truncation would silently produce `486` instead of `487`. `Math.round` avoids this class of floating-point bug.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :mcp-server:test --tests "com.droidagentkit.mcp.tools.BuildProfileParserTest"`
Expected: PASS, 3/3 tests green.

- [ ] **Step 5: Commit the parser**

```bash
git add mcp-server/src/main/kotlin/com/droidagentkit/mcp/tools/BuildProfileParser.kt mcp-server/src/test/kotlin/com/droidagentkit/mcp/tools/BuildProfileParserTest.kt
git commit -m "feat(mcp): add BuildProfileParser"
```

- [ ] **Step 6: Write the failing dispatcher test**

In `mcp-server/src/test/kotlin/com/droidagentkit/mcp/McpDispatcherTest.kt`:

Update the tools list in `dispatcher lists expected android tools` — add `"android_build_performance",` after `"android_dependency_check",`.

Update `each tool exposes an input schema with type object and properties` — change `assertEquals(11, tools.size)` to `assertEquals(12, tools.size)`.

Add this test at the end of the class, before the final closing `}` (reuses the `writeFakeGradlew` helper added in Task 1):

```kotlin
    @Test
    fun `build performance parses slowest tasks from the profile report`() {
        val root = Files.createTempDirectory("dak-build-perf")
        val config = DroidAgentConfig.default().copy(
            safety = DroidAgentConfig.default().safety.copy(allowGradleTasks = listOf(":app:assembleDebug")),
        )
        writeFakeGradlew(root)
        val profileDir = root.resolve("build/reports/profile")
        Files.createDirectories(profileDir)
        Files.writeString(
            profileDir.resolve("profile-test.html"),
            """
            <html><body>
            <div class="tab" id="tab0">
            <table>
            <tr><td>Total Build Time</td><td class="numeric">0.487s</td></tr>
            </table>
            </div>
            <div class="tab" id="tab4">
            <table>
            <tr><td>:toolbox-core</td><td class="numeric">0.021s</td><td>(total)</td></tr>
            <tr><td class="indentPath">:toolbox-core:compileKotlin</td><td class="numeric">0.012s</td><td>UP-TO-DATE</td></tr>
            </table>
            </div>
            </body></html>
            """.trimIndent(),
        )
        val dispatcher = DroidAgentMcpDispatcher(config)

        val result = dispatcher.call("android_build_performance", mapOf("rootPath" to root.toString(), "task" to ":app:assembleDebug"))

        assertEquals("success", result["status"])
        @Suppress("UNCHECKED_CAST")
        val findings = result["findings"] as List<Map<*, *>>
        assertEquals(1, findings.size)
        assertEquals(":toolbox-core:compileKotlin", findings[0]["title"])
    }
```

- [ ] **Step 7: Run the dispatcher tests to verify they fail**

Run: `./gradlew :mcp-server:test --tests "com.droidagentkit.mcp.McpDispatcherTest"`
Expected: FAIL — `android_build_performance` is not a recognized tool name yet, and the tool-count/tool-list assertions fail.

- [ ] **Step 8: Wire `android_build_performance` into the dispatcher**

In `mcp-server/src/main/kotlin/com/droidagentkit/mcp/DroidAgentMcpDispatcher.kt`:

Add to the imports:

```kotlin
import com.droidagentkit.core.ArtifactRef
import com.droidagentkit.core.Severity
import com.droidagentkit.mcp.tools.BuildProfileParser
```

Add a new `McpTool` entry to `listTools()`, immediately after the `android_dependency_check` entry added in Task 3:

```kotlin
        McpTool(
            name = "android_build_performance",
            description = "Run an allowlisted Gradle task with --profile and surface the slowest tasks from the profile report.",
            inputSchema = schema(
                "task",
                props = mapOf(
                    "rootPath" to rootPathProp,
                    "task" to str("Gradle task to run with --profile (must match the configured allowlist)."),
                    "timeoutSeconds" to num("Override command timeout in seconds."),
                ),
            ),
        ),
```

Add a new branch to `call()`'s `when` expression, immediately after `"android_dependency_check" -> dependencyCheck(arguments)`:

```kotlin
        "android_build_performance" -> buildPerformance(arguments)
```

Add the new handler function, near `dependencyCheck`:

```kotlin
    private fun buildPerformance(arguments: Map<String, Any?>): Map<String, Any> {
        val root = rootPath(arguments)
        val task = arguments["task"]?.toString().orEmpty()
        val timeout = arguments["timeoutSeconds"]?.toString()?.toLongOrNull() ?: config.safety.maxCommandSeconds
        val runResult = runAllowlistedGradleTask(root, task, listOf("--profile"), timeout)
        if (runResult.status == ResultStatus.BLOCKED) {
            return resultMap(runResult)
        }
        val reportFile = findNewestProfileReport(root)
            ?: return resultMapWithFindings(
                runResult.copy(warnings = runResult.warnings + "no-profile-report-found"),
                emptyList(),
            )
        val html = Files.readString(reportFile)
        val profile = BuildProfileParser.parse(html)
        val findings = profile.taskTimings.take(10).map { timing ->
            DiagnosticFinding(
                category = "slow_task",
                severity = Severity.INFO,
                title = timing.taskPath,
                detail = "${timing.durationMs}ms",
                location = timing.taskPath,
            )
        }
        val reportArtifact = ArtifactRef(
            type = ArtifactType.REPORT,
            path = reportFile.toString(),
            mimeType = "text/html",
            description = "Gradle --profile report",
        )
        val summaryText = buildString {
            append("Ran '$task' with --profile.")
            profile.totalBuildTimeMs?.let { append(" Total build time: ${it}ms.") }
            if (findings.isEmpty()) append(" No task timing data could be parsed from the profile report.")
        }
        return resultMapWithFindings(
            runResult.copy(summary = summaryText, artifacts = runResult.artifacts + reportArtifact),
            findings,
        )
    }

    private fun findNewestProfileReport(root: Path): Path? {
        val reportsDir = root.resolve("build/reports/profile")
        if (!Files.isDirectory(reportsDir)) return null
        return Files.list(reportsDir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".html") }
                .toList()
                .maxByOrNull { Files.getLastModifiedTime(it).toMillis() }
        }
    }
```

- [ ] **Step 9: Run the full mcp-server test suite to verify everything passes**

Run: `./gradlew :mcp-server:test`
Expected: PASS, all tests green — 12 tools total, no regressions.

- [ ] **Step 10: Commit the dispatcher wiring**

```bash
git add mcp-server/src/main/kotlin/com/droidagentkit/mcp/DroidAgentMcpDispatcher.kt mcp-server/src/test/kotlin/com/droidagentkit/mcp/McpDispatcherTest.kt
git commit -m "feat(mcp): add android_build_performance tool"
```

- [ ] **Step 11: Run the full project test suite**

Run: `./gradlew test`
Expected: PASS, `BUILD SUCCESSFUL`, no regressions in any module.

- [ ] **Step 12: Update documentation**

In `docs/security-and-permissions.md`, add the four new tool names and their one-line descriptions to wherever the existing 8 MCP tools are documented (follow the existing format for that section).

If `README.md` enumerates the 8 existing MCP tool names anywhere, add the 4 new ones to that list too.

```bash
git add docs/security-and-permissions.md README.md
git commit -m "docs: document 4 new MCP diagnostic tools"
```
