# DroidAgentKit Optimisations & Features Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix correctness bugs (binary screenshot, non-deterministic JSON), add MCP input schemas, harden the redactor, and deepen the inspector/auditor across three independently shippable batches.

**Architecture:** All changes are within the existing Kotlin/JVM monorepo. Batches 1–3 follow the existing pattern: pure-Kotlin implementations with no new third-party dependencies, JUnit 4 tests using real filesystem temp dirs (no mocks), conventional-commit messages.

**Tech Stack:** Kotlin 2.3.20, JVM 17, JUnit 4.13.2, Gradle Kotlin DSL. No new dependencies introduced.

## Global Constraints

- Zero new third-party dependencies — JDK stdlib only.
- All tests use `Files.createTempDirectory` for filesystem fixtures; never mock data classes.
- Test run command: `./gradlew :<module>:test --tests "<fully.qualified.ClassName>"`
- Commit format: `type(scope): description` (e.g. `fix(core): sort json map keys`).
- Do not change existing `ToolResult` status wire names or `ArtifactType` wire names.
- The `isScannable` predicate (introduced in Task 4) must be used by both `findPossibleSecrets` and `hasVisualHooks` — do not duplicate the logic.

---

## File Map

| File | Change |
|------|--------|
| `toolbox-core/src/main/kotlin/com/droidagentkit/core/Models.kt` | Add `OutputMode` enum; add `outputMode` field to `CommandSpec` |
| `toolbox-core/src/main/kotlin/com/droidagentkit/core/Artifacts.kt` | Add `ArtifactWriter.writeBytes()` |
| `toolbox-core/src/main/kotlin/com/droidagentkit/core/ProcessRunner.kt` | Branch on `outputMode` for binary vs text output |
| `toolbox-core/src/main/kotlin/com/droidagentkit/core/Json.kt` | Sort map keys before serialising |
| `toolbox-core/src/main/kotlin/com/droidagentkit/core/Redaction.kt` | Add 6 new redaction rules |
| `toolbox-core/src/test/kotlin/com/droidagentkit/core/JsonAndCommandTest.kt` | Add: JSON ordering test, binary process runner test |
| `toolbox-core/src/test/kotlin/com/droidagentkit/core/ConfigAndSafetyTest.kt` | Add: 5 new redaction rule tests |
| `mcp-server/src/main/kotlin/com/droidagentkit/mcp/DroidAgentMcpDispatcher.kt` | Add `inputSchema` to `McpTool`; populate schemas for all 8 tools; fix `snapshot()` to use `OutputMode.BINARY`; enrich `reportBundle()` |
| `mcp-server/src/main/kotlin/com/droidagentkit/mcp/DroidAgentMcpServer.kt` | Include `inputSchema` in HTTP GET response |
| `mcp-server/build.gradle.kts` | Add `implementation(project(":auditor-cli"))` |
| `mcp-server/src/test/kotlin/com/droidagentkit/mcp/McpDispatcherTest.kt` | Add: schema shape test, snapshot blocked-without-serial test, enriched bundle test |
| `auditor-cli/src/main/kotlin/com/droidagentkit/auditor/ReadinessAuditor.kt` | Add `isScannable`; filter both scanners; add 4 new readiness checks; replace implicit version-catalog score with named risk |
| `auditor-cli/src/test/kotlin/com/droidagentkit/auditor/ReadinessAuditorTest.kt` | Add: scanner-skip test, new-signal tests |
| `android-inspector/src/main/kotlin/com/droidagentkit/inspector/Models.kt` | Add `moduleDependencies`, `buildTypes`, `productFlavors` to `AndroidModuleSummary` |
| `android-inspector/src/main/kotlin/com/droidagentkit/inspector/AndroidProjectInspector.kt` | Parse module deps, build variants, flavors; enrich version catalog parsing |
| `android-inspector/src/test/kotlin/com/droidagentkit/inspector/AndroidInspectorTest.kt` | Add: deps test, variants test, libs.versions test |
| `cli/src/main/kotlin/com/droidagentkit/cli/ProjectLocator.kt` | Add Gemini env vars |
| `cli/src/test/kotlin/com/droidagentkit/cli/CliParserTest.kt` | Add: ProjectLocator Gemini test |
| `docs/security-and-permissions.md` | Document readiness score breakdown |

---

## BATCH 1 — Correctness + Protocol

---

### Task 1: Sort JSON map keys

**Files:**
- Modify: `toolbox-core/src/main/kotlin/com/droidagentkit/core/Json.kt:27`
- Test: `toolbox-core/src/test/kotlin/com/droidagentkit/core/JsonAndCommandTest.kt`

**Interfaces:**
- Consumes: nothing new
- Produces: `Json.write(Map)` now emits keys in alphabetical order — all callers benefit automatically

- [ ] **Step 1: Write the failing test** — add to `JsonAndCommandTest`:

```kotlin
@Test
fun `json map serialization uses stable alphabetical key order`() {
    val map = mapOf("z" to "last", "a" to "first", "m" to "middle")

    val json = Json.write(map)

    val indexA = json.indexOf("\"a\"")
    val indexM = json.indexOf("\"m\"")
    val indexZ = json.indexOf("\"z\"")
    assertTrue(indexA < indexM)
    assertTrue(indexM < indexZ)
}
```

- [ ] **Step 2: Run test to confirm it fails**

```bash
./gradlew :toolbox-core:test --tests "com.droidagentkit.core.JsonAndCommandTest"
```

Expected: FAIL — key order is not guaranteed.

- [ ] **Step 3: Fix `Json.kt` — sort map entries before joining**

Change the `is Map<*, *>` branch in `Json.write()` from:
```kotlin
is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") { (key, item) ->
```
to:
```kotlin
is Map<*, *> -> value.entries.sortedBy { it.key.toString() }.joinToString(prefix = "{", postfix = "}") { (key, item) ->
```

- [ ] **Step 4: Run tests to confirm pass**

```bash
./gradlew :toolbox-core:test --tests "com.droidagentkit.core.JsonAndCommandTest"
```

Expected: all tests PASS.

- [ ] **Step 5: Commit**

```bash
git add toolbox-core/src/main/kotlin/com/droidagentkit/core/Json.kt \
        toolbox-core/src/test/kotlin/com/droidagentkit/core/JsonAndCommandTest.kt
git commit -m "fix(core): sort json map keys alphabetically for deterministic mcp responses"
```

---

### Task 2: OutputMode enum + ProcessRunner binary branch

**Files:**
- Modify: `toolbox-core/src/main/kotlin/com/droidagentkit/core/Models.kt`
- Modify: `toolbox-core/src/main/kotlin/com/droidagentkit/core/Artifacts.kt`
- Modify: `toolbox-core/src/main/kotlin/com/droidagentkit/core/ProcessRunner.kt`
- Test: `toolbox-core/src/test/kotlin/com/droidagentkit/core/JsonAndCommandTest.kt`

**Interfaces:**
- Consumes: nothing new
- Produces:
  - `OutputMode` enum (`TEXT`, `BINARY`) — used by Task 3
  - `CommandSpec.outputMode: OutputMode = OutputMode.TEXT` — default keeps all existing callers unchanged
  - `ArtifactWriter.writeBytes(name, bytes, type, description): ArtifactRef`

- [ ] **Step 1: Write the failing test** — add to `JsonAndCommandTest`:

```kotlin
@Test
fun `process runner preserves binary output without text corruption`() {
    val outputDir = Files.createTempDirectory("dak-binary")
    val runner = ProcessRunner(
        redactor = Redactor(DroidAgentConfig.default().redaction),
        artifactWriter = ArtifactWriter(outputDir),
    )
    // PNG magic bytes — would be corrupted if decoded as UTF-8
    val tmpFile = outputDir.resolve("test.bin")
    val expectedBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    java.nio.file.Files.write(tmpFile, expectedBytes)

    val result = runner.run(
        CommandSpec(
            id = "binary-cat",
            command = listOf("/bin/sh", "-c", "cat ${tmpFile.toAbsolutePath()}"),
            workingDirectory = outputDir.toString(),
            mutatesProject = false,
            requiresDevice = false,
            timeoutSeconds = 10,
            outputMode = OutputMode.BINARY,
        ),
    )

    assertEquals(ResultStatus.SUCCESS, result.status)
    assertEquals(1, result.artifacts.size)
    val written = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(result.artifacts[0].path))
    org.junit.Assert.assertArrayEquals(expectedBytes, written)
}
```

- [ ] **Step 2: Run test to confirm it fails**

```bash
./gradlew :toolbox-core:test --tests "com.droidagentkit.core.JsonAndCommandTest"
```

Expected: FAIL — `OutputMode` does not exist yet.

- [ ] **Step 3: Add `OutputMode` enum and update `CommandSpec` in `Models.kt`**

Add at the top of `Models.kt` (before `ArtifactType`):
```kotlin
enum class OutputMode { TEXT, BINARY }
```

Add `outputMode` as the last field in `CommandSpec`:
```kotlin
data class CommandSpec(
    val id: String,
    val command: List<String>,
    val workingDirectory: String,
    val mutatesProject: Boolean,
    val requiresDevice: Boolean,
    val timeoutSeconds: Long,
    val outputMode: OutputMode = OutputMode.TEXT,
)
```

- [ ] **Step 4: Add `writeBytes` to `ArtifactWriter` in `Artifacts.kt`**

Add after the existing `writeText` function:
```kotlin
fun writeBytes(name: String, bytes: ByteArray, type: ArtifactType = ArtifactType.SCREENSHOT, description: String = name): ArtifactRef {
    val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "-")
    val path = outputDir.resolve(safeName)
    Files.write(path, bytes)
    return ArtifactRef(type, path.toString(), mimeTypeFor(path), description)
}
```

- [ ] **Step 5: Update `ProcessRunner.run()` to branch on `outputMode`**

Replace the entire body of `ProcessRunner.run()` with:
```kotlin
fun run(spec: CommandSpec): ToolResult {
    val started = Instant.now()
    val process = try {
        ProcessBuilder(spec.command)
            .directory(Path.of(spec.workingDirectory).toFile())
            .redirectErrorStream(true)
            .start()
    } catch (error: Exception) {
        return ToolResult(
            status = ResultStatus.BLOCKED,
            summary = "Could not start command ${spec.command.joinToString(" ")}: ${error.message}",
            warnings = listOf("command-start-failed"),
        )
    }

    val completed = process.waitFor(spec.timeoutSeconds, TimeUnit.SECONDS)
    val durationMs = Duration.between(started, Instant.now()).toMillis()

    if (spec.outputMode == OutputMode.BINARY) {
        val bytes = process.inputStream.readBytes()
        if (!completed) process.destroyForcibly()
        val artifact = artifactWriter.writeBytes("${spec.id}.bin", bytes, ArtifactType.SCREENSHOT, "${spec.id} binary capture")
        val status = when {
            !completed -> ResultStatus.PARTIAL
            process.exitValue() == 0 -> ResultStatus.SUCCESS
            else -> ResultStatus.FAILED
        }
        return ToolResult(
            status = status,
            summary = "${spec.id} captured ${bytes.size} bytes in ${durationMs}ms",
            artifacts = listOf(artifact),
            warnings = if (completed) emptyList() else listOf("command-timeout"),
        )
    }

    val rawOutput = process.inputStream.bufferedReader().readText()
    if (!completed) process.destroyForcibly()
    val redacted = redactor.redact(rawOutput)
    val artifact = artifactWriter.writeText("${spec.id}.log", redacted.text)
    val status = when {
        !completed -> ResultStatus.PARTIAL
        process.exitValue() == 0 -> ResultStatus.SUCCESS
        else -> ResultStatus.FAILED
    }
    val summary = buildString {
        append("${spec.id} exited with ")
        append(if (completed) process.exitValue().toString() else "timeout")
        append(" in ${durationMs}ms")
        val preview = redacted.text.lineSequence().filter { it.isNotBlank() }.take(5).joinToString("\n")
        if (preview.isNotBlank()) append("\n").append(preview)
    }
    return ToolResult(
        status = status,
        summary = summary,
        artifacts = listOf(artifact),
        redactionsApplied = redacted.applied,
        warnings = if (completed) emptyList() else listOf("command-timeout"),
    )
}
```

- [ ] **Step 6: Run all toolbox-core tests**

```bash
./gradlew :toolbox-core:test
```

Expected: all tests PASS.

- [ ] **Step 7: Commit**

```bash
git add toolbox-core/src/main/kotlin/com/droidagentkit/core/Models.kt \
        toolbox-core/src/main/kotlin/com/droidagentkit/core/Artifacts.kt \
        toolbox-core/src/main/kotlin/com/droidagentkit/core/ProcessRunner.kt \
        toolbox-core/src/test/kotlin/com/droidagentkit/core/JsonAndCommandTest.kt
git commit -m "fix(core): add OutputMode.BINARY to ProcessRunner to preserve binary output"
```

---

### Task 3: Fix screenshot tool to use binary output mode

**Files:**
- Modify: `mcp-server/src/main/kotlin/com/droidagentkit/mcp/DroidAgentMcpDispatcher.kt`
- Test: `mcp-server/src/test/kotlin/com/droidagentkit/mcp/McpDispatcherTest.kt`

**Interfaces:**
- Consumes: `OutputMode.BINARY` and `CommandSpec.outputMode` from Task 2
- Produces: `snapshot()` no longer corrupts binary PNG output

- [ ] **Step 1: Write the failing test** — add to `McpDispatcherTest`:

```kotlin
@Test
fun `snapshot is blocked when device serial is missing`() {
    val root = Files.createTempDirectory("dak-snapshot")
    val dispatcher = DroidAgentMcpDispatcher(DroidAgentConfig.default())

    val result = dispatcher.call("android_screen_snapshot", mapOf("rootPath" to root.toString()))

    assertEquals("blocked", result["status"])
    assertTrue(result["summary"].toString().contains("deviceSerial"))
}
```

- [ ] **Step 2: Run test to confirm it passes (guard test)**

```bash
./gradlew :mcp-server:test --tests "com.droidagentkit.mcp.McpDispatcherTest"
```

Expected: PASS — the existing code already returns `blocked` when `deviceSerial` is missing. This test acts as a guard to ensure the fix in Step 3 does not break the validation.

- [ ] **Step 3: Fix `snapshot()` in `DroidAgentMcpDispatcher` to build a binary `CommandSpec` directly**

Replace the existing `snapshot()` function:
```kotlin
private fun snapshot(arguments: Map<String, Any?>): Map<String, Any> {
    val serial = arguments["deviceSerial"]?.toString()
        ?: return resultMap(ToolResult(status = ResultStatus.BLOCKED, summary = "deviceSerial is required for screenshots.", warnings = listOf("missing-device-serial")))
    val root = rootPath(arguments)
    return resultMap(
        runner(root).run(
            com.droidagentkit.core.CommandSpec(
                id = "adb-screenshot",
                command = listOf("adb", "-s", serial, "exec-out", "screencap", "-p"),
                workingDirectory = root.toString(),
                mutatesProject = false,
                requiresDevice = true,
                timeoutSeconds = 60,
                outputMode = com.droidagentkit.core.OutputMode.BINARY,
            ),
        ),
    )
}
```

- [ ] **Step 4: Run mcp-server tests**

```bash
./gradlew :mcp-server:test
```

Expected: all tests PASS.

- [ ] **Step 5: Commit**

```bash
git add mcp-server/src/main/kotlin/com/droidagentkit/mcp/DroidAgentMcpDispatcher.kt \
        mcp-server/src/test/kotlin/com/droidagentkit/mcp/McpDispatcherTest.kt
git commit -m "fix(mcp): use binary output mode for android_screen_snapshot to avoid PNG corruption"
```

---

### Task 4: File scanner filtering in auditor

**Files:**
- Modify: `auditor-cli/src/main/kotlin/com/droidagentkit/auditor/ReadinessAuditor.kt`
- Test: `auditor-cli/src/test/kotlin/com/droidagentkit/auditor/ReadinessAuditorTest.kt`

**Interfaces:**
- Consumes: nothing new
- Produces: `private fun isScannable(path: Path): Boolean` — shared by `findPossibleSecrets` and `hasVisualHooks`

- [ ] **Step 1: Write the failing test** — add to `ReadinessAuditorTest`:

```kotlin
@Test
fun `secret scanner skips files inside build directories`() {
    val root = Files.createTempDirectory("dak-scanner-skip")
    Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"Skip\"\n")
    // Secret in build/ — should be ignored
    val buildSecrets = root.resolve("build/outputs")
    Files.createDirectories(buildSecrets)
    Files.writeString(buildSecrets.resolve("secret.properties"), "STORE_PASSWORD=shouldbeskipped")
    // Secret outside build/ — should be caught
    Files.writeString(root.resolve("local.properties"), "STORE_PASSWORD=shouldbecaught")

    val report = ReadinessAuditor(AndroidProjectInspector()).audit(root)

    val evidence = report.risks.filter { it.id == "possible-secret" }
        .flatMap { it.evidence }
        .joinToString()
    assertTrue(evidence.contains("local.properties"))
    assertFalse(evidence.contains("build/outputs"))
}
```

- [ ] **Step 2: Run test to confirm it fails**

```bash
./gradlew :auditor-cli:test --tests "com.droidagentkit.auditor.ReadinessAuditorTest"
```

Expected: FAIL — build/ files are currently scanned.

- [ ] **Step 3: Add `isScannable` + companion object to `ReadinessAuditor`**

Add inside the `ReadinessAuditor` class body (before the closing `}`):

```kotlin
private fun isScannable(path: Path): Boolean {
    val str = path.toString().replace('\\', '/')
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

- [ ] **Step 4: Update `findPossibleSecrets` to use the filter and 1 MB limit**

Replace the `Files.walk` stream pipeline inside `findPossibleSecrets`:
```kotlin
stream.filter { Files.isRegularFile(it) }
    .filter { isScannable(it) && Files.size(it) <= 1_048_576L }
    .filter { it.fileName.toString() !in setOf("readiness-report.json", "readiness-report.md") }
    .limit(1000)
```

- [ ] **Step 5: Update `hasVisualHooks` to use the filter**

Replace the `Files.walk` stream pipeline inside `hasVisualHooks`:
```kotlin
stream.filter { Files.isRegularFile(it) }
    .filter { isScannable(it) }
    .limit(500)
```

- [ ] **Step 6: Run auditor tests**

```bash
./gradlew :auditor-cli:test
```

Expected: all tests PASS.

- [ ] **Step 7: Commit**

```bash
git add auditor-cli/src/main/kotlin/com/droidagentkit/auditor/ReadinessAuditor.kt \
        auditor-cli/src/test/kotlin/com/droidagentkit/auditor/ReadinessAuditorTest.kt
git commit -m "fix(auditor): skip build dirs and binary files in secret scanner and visual hooks check"
```

---

### Task 5: MCP input schemas for all 8 tools

**Files:**
- Modify: `mcp-server/src/main/kotlin/com/droidagentkit/mcp/DroidAgentMcpDispatcher.kt`
- Modify: `mcp-server/src/main/kotlin/com/droidagentkit/mcp/DroidAgentMcpServer.kt`
- Test: `mcp-server/src/test/kotlin/com/droidagentkit/mcp/McpDispatcherTest.kt`

**Interfaces:**
- Consumes: nothing new
- Produces: `McpTool.inputSchema: Map<String, Any>` — included in `listTools()` response

- [ ] **Step 1: Write the failing test** — add to `McpDispatcherTest`:

```kotlin
@Test
fun `each tool exposes an input schema with type object and properties`() {
    val dispatcher = DroidAgentMcpDispatcher(DroidAgentConfig.default())

    val tools = dispatcher.listTools()

    assertEquals(8, tools.size)
    tools.forEach { tool ->
        assertEquals("object", tool.inputSchema["type"], "tool ${tool.name} missing type:object")
        assertTrue(
            "tool ${tool.name} missing properties",
            tool.inputSchema.containsKey("properties"),
        )
    }
}

@Test
fun `gradle run tool schema marks task as required`() {
    val dispatcher = DroidAgentMcpDispatcher(DroidAgentConfig.default())

    val gradleTool = dispatcher.listTools().first { it.name == "android_gradle_run" }

    @Suppress("UNCHECKED_CAST")
    val required = gradleTool.inputSchema["required"] as List<*>
    assertTrue(required.contains("task"))
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
./gradlew :mcp-server:test --tests "com.droidagentkit.mcp.McpDispatcherTest"
```

Expected: FAIL — `McpTool` has no `inputSchema` field yet.

- [ ] **Step 3: Add `inputSchema` to `McpTool` in `DroidAgentMcpDispatcher.kt`**

Replace the existing `McpTool` data class:
```kotlin
data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: Map<String, Any> = emptyMap(),
)
```

- [ ] **Step 4: Add schema helper functions inside `DroidAgentMcpDispatcher`** (private, keep them at the bottom of the class)

```kotlin
private fun schema(vararg required: String, props: Map<String, Map<String, Any>>): Map<String, Any> {
    val base: MutableMap<String, Any> = mutableMapOf("type" to "object", "properties" to props)
    if (required.isNotEmpty()) base["required"] = required.toList()
    return base
}

private fun str(desc: String): Map<String, Any> = mapOf("type" to "string", "description" to desc)
private fun bool(desc: String): Map<String, Any> = mapOf("type" to "boolean", "description" to desc)
private fun num(desc: String): Map<String, Any> = mapOf("type" to "number", "description" to desc)
private fun arrStr(desc: String): Map<String, Any> =
    mapOf("type" to "array", "items" to mapOf("type" to "string"), "description" to desc)

private val rootPathProp get() = str("Absolute path to the Android project root. Defaults to cwd.")
private val deviceSerialProp get() = str("adb device serial from `adb devices`.")
```

- [ ] **Step 5: Replace `listTools()` to populate schemas**

```kotlin
fun listTools(): List<McpTool> = listOf(
    McpTool(
        name = "android_project_inspect",
        description = "Inspect Android Gradle modules, versions, manifests, and safe commands.",
        inputSchema = schema(props = mapOf("rootPath" to rootPathProp)),
    ),
    McpTool(
        name = "android_gradle_run",
        description = "Run a configured allowlisted Gradle task and capture redacted logs.",
        inputSchema = schema(
            "task",
            props = mapOf(
                "rootPath" to rootPathProp,
                "task" to str("Gradle task to run (must match the configured allowlist)."),
                "arguments" to arrStr("Extra Gradle arguments to append."),
                "rerunTasks" to bool("Pass --rerun-tasks to Gradle."),
                "stacktrace" to bool("Pass --stacktrace to Gradle."),
                "timeoutSeconds" to num("Override command timeout in seconds."),
            ),
        ),
    ),
    McpTool(
        name = "android_devices_list",
        description = "List adb devices and basic status.",
        inputSchema = schema(props = mapOf("rootPath" to rootPathProp)),
    ),
    McpTool(
        name = "android_app_install",
        description = "Install an APK when app install is enabled.",
        inputSchema = schema(
            "apkPath", "deviceSerial",
            props = mapOf(
                "rootPath" to rootPathProp,
                "apkPath" to str("Absolute path to the APK file to install."),
                "deviceSerial" to deviceSerialProp,
                "reinstall" to bool("Pass -r to adb install to reinstall keeping data."),
            ),
        ),
    ),
    McpTool(
        name = "android_app_launch",
        description = "Launch an Android package/activity on an explicit device.",
        inputSchema = schema(
            "deviceSerial", "packageName",
            props = mapOf(
                "deviceSerial" to deviceSerialProp,
                "packageName" to str("Android package name (e.g. com.example.app)."),
                "activityName" to str("Fully qualified activity name. Omit to use the default launcher."),
            ),
        ),
    ),
    McpTool(
        name = "android_logcat_capture",
        description = "Capture redacted logcat output for a device or package.",
        inputSchema = schema(
            "deviceSerial",
            props = mapOf(
                "deviceSerial" to deviceSerialProp,
                "maxLines" to num("Maximum number of log lines to capture. Default: 500."),
            ),
        ),
    ),
    McpTool(
        name = "android_screen_snapshot",
        description = "Capture screenshot and UIAutomator XML from an explicit device.",
        inputSchema = schema(
            "deviceSerial",
            props = mapOf(
                "deviceSerial" to deviceSerialProp,
                "outputName" to str("Base name for the output artifact. Optional."),
            ),
        ),
    ),
    McpTool(
        name = "android_report_bundle",
        description = "Create an agent-readable Android diagnostic report bundle.",
        inputSchema = schema(props = mapOf("rootPath" to rootPathProp)),
    ),
)
```

- [ ] **Step 6: Update HTTP GET response in `DroidAgentMcpServer.kt` to include `inputSchema`**

Replace the existing GET response line:
```kotlin
Json.write(mapOf("tools" to dispatcher.listTools().map { mapOf("name" to it.name, "description" to it.description) }))
```
with:
```kotlin
Json.write(mapOf("tools" to dispatcher.listTools().map {
    mapOf("name" to it.name, "description" to it.description, "inputSchema" to it.inputSchema)
}))
```

- [ ] **Step 7: Run mcp-server tests**

```bash
./gradlew :mcp-server:test
```

Expected: all tests PASS.

- [ ] **Step 8: Commit**

```bash
git add mcp-server/src/main/kotlin/com/droidagentkit/mcp/DroidAgentMcpDispatcher.kt \
        mcp-server/src/main/kotlin/com/droidagentkit/mcp/DroidAgentMcpServer.kt \
        mcp-server/src/test/kotlin/com/droidagentkit/mcp/McpDispatcherTest.kt
git commit -m "feat(mcp): add json schema input definitions to all 8 mcp tools"
```

---

## BATCH 2 — Security Hardening

---

### Task 6: Extend Redactor with 6 new secret patterns

**Files:**
- Modify: `toolbox-core/src/main/kotlin/com/droidagentkit/core/Redaction.kt`
- Test: `toolbox-core/src/test/kotlin/com/droidagentkit/core/ConfigAndSafetyTest.kt`

**Interfaces:**
- Consumes: existing `Rule` structure
- Produces: 6 new rule IDs: `aws-access-key`, `github-classic-token`, `github-fine-grained-token`, `pem-private-key`, `firebase-private-key`, `generic-secret-assignment`

- [ ] **Step 1: Write failing tests** — add to `ConfigAndSafetyTest`:

```kotlin
@Test
fun `redactor hides aws access key ids`() {
    val redactor = Redactor(DroidAgentConfig.default().redaction)

    val result = redactor.redact("aws_access_key_id=AKIAIOSFODNN7EXAMPLEOK")

    assertFalse(result.text.contains("AKIAIOSFODNN7EXAMPLEOK"))
    assertTrue(result.applied.contains("aws-access-key"))
}

@Test
fun `redactor hides github classic personal access tokens`() {
    val redactor = Redactor(DroidAgentConfig.default().redaction)
    val token = "ghp_" + "A".repeat(36)

    val result = redactor.redact("GH_TOKEN=$token")

    assertFalse(result.text.contains(token))
    assertTrue(result.applied.contains("github-classic-token"))
}

@Test
fun `redactor hides github fine grained tokens`() {
    val redactor = Redactor(DroidAgentConfig.default().redaction)
    val token = "github_pat_" + "B".repeat(82)

    val result = redactor.redact("token=$token")

    assertFalse(result.text.contains(token))
    assertTrue(result.applied.contains("github-fine-grained-token"))
}

@Test
fun `redactor hides pem private key headers`() {
    val redactor = Redactor(DroidAgentConfig.default().redaction)

    val result = redactor.redact("-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEA...")

    assertFalse(result.text.contains("BEGIN RSA PRIVATE KEY"))
    assertTrue(result.applied.contains("pem-private-key"))
}

@Test
fun `redactor hides firebase private key json fragment`() {
    val redactor = Redactor(DroidAgentConfig.default().redaction)
    val input = """{"type":"service_account","private_key":"-----BEGIN PRIVATE KEY-----\nMIIEvAIBADA"}"""

    val result = redactor.redact(input)

    assertFalse(result.text.contains("-----BEGIN PRIVATE KEY"))
    assertTrue(result.applied.contains("firebase-private-key"))
}

@Test
fun `redactor hides generic key and secret assignments with at least 8 char values`() {
    val redactor = Redactor(DroidAgentConfig.default().redaction)

    val hit = redactor.redact("MY_API_KEY=supersecret123")
    val miss = redactor.redact("MY_KEY=short")  // 5 chars — under threshold

    assertTrue(hit.applied.contains("generic-secret-assignment"))
    assertFalse(miss.applied.contains("generic-secret-assignment"))
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
./gradlew :toolbox-core:test --tests "com.droidagentkit.core.ConfigAndSafetyTest"
```

Expected: 6 new tests FAIL.

- [ ] **Step 3: Add 6 new rules to `Redaction.kt`**

Append after the existing 4 rules in the `rules` list inside `Redactor`:

```kotlin
Rule(
    "aws-access-key",
    Regex("AKIA[0-9A-Z]{16}"),
    "[REDACTED]",
),
Rule(
    "github-classic-token",
    Regex("ghp_[A-Za-z0-9]{36}"),
    "[REDACTED]",
),
Rule(
    "github-fine-grained-token",
    Regex("github_pat_[A-Za-z0-9_]{82}"),
    "[REDACTED]",
),
Rule(
    "pem-private-key",
    Regex("-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    "[REDACTED-PEM]",
),
Rule(
    "firebase-private-key",
    Regex("\"private_key\"\\s*:\\s*\"-----BEGIN"),
    "\"private_key\":\"[REDACTED]",
),
Rule(
    "generic-secret-assignment",
    Regex("(?i)([A-Z0-9_]*(?:KEY|SECRET|CREDENTIAL)[A-Z0-9_]*\\s*[:=]\\s*)([^\\s\\n]{8,})"),
    "$1[REDACTED]",
),
```

- [ ] **Step 4: Run all toolbox-core tests**

```bash
./gradlew :toolbox-core:test
```

Expected: all tests PASS.

- [ ] **Step 5: Commit**

```bash
git add toolbox-core/src/main/kotlin/com/droidagentkit/core/Redaction.kt \
        toolbox-core/src/test/kotlin/com/droidagentkit/core/ConfigAndSafetyTest.kt
git commit -m "feat(core): extend redactor with aws, github, pem, firebase, and generic secret patterns"
```

---

## BATCH 3 — Inspector + Auditor Depth

---

### Task 7: Module dependency graph in inspector

**Files:**
- Modify: `android-inspector/src/main/kotlin/com/droidagentkit/inspector/Models.kt`
- Modify: `android-inspector/src/main/kotlin/com/droidagentkit/inspector/AndroidProjectInspector.kt`
- Test: `android-inspector/src/test/kotlin/com/droidagentkit/inspector/AndroidInspectorTest.kt`

**Interfaces:**
- Consumes: nothing new
- Produces: `AndroidModuleSummary.moduleDependencies: List<String>` — used by Task 11

- [ ] **Step 1: Write the failing test** — add to `AndroidInspectorTest`:

```kotlin
@Test
fun `inspector captures inter-module project dependencies`() {
    val root = Files.createTempDirectory("dak-deps")
    Files.writeString(
        root.resolve("settings.gradle.kts"),
        "rootProject.name = \"Deps\"\ninclude(\":app\", \":core\")",
    )
    Files.createDirectories(root.resolve("app"))
    Files.writeString(
        root.resolve("app/build.gradle.kts"),
        """
        plugins { id("com.android.application") }
        android { namespace = "com.example.app" }
        dependencies {
            implementation(project(":core"))
        }
        """.trimIndent(),
    )
    Files.createDirectories(root.resolve("core"))
    Files.writeString(
        root.resolve("core/build.gradle.kts"),
        "plugins { id(\"com.android.library\") }\nandroid { namespace = \"com.example.core\" }",
    )

    val report = AndroidProjectInspector().inspect(root)

    val appModule = report.modules.first { it.path == ":app" }
    assertEquals(listOf(":core"), appModule.moduleDependencies)
    val coreModule = report.modules.first { it.path == ":core" }
    assertTrue(coreModule.moduleDependencies.isEmpty())
}
```

- [ ] **Step 2: Run test to confirm it fails**

```bash
./gradlew :android-inspector:test --tests "com.droidagentkit.inspector.AndroidInspectorTest"
```

Expected: FAIL — `moduleDependencies` field does not exist.

- [ ] **Step 3: Add `moduleDependencies` to `AndroidModuleSummary` in `Models.kt`**

```kotlin
data class AndroidModuleSummary(
    val path: String,
    val directory: String,
    val type: AndroidModuleType,
    val namespace: String?,
    val packageName: String?,
    val launcherActivities: List<String>,
    val usesCompose: Boolean,
    val hasUnitTests: Boolean,
    val hasAndroidTests: Boolean,
    val moduleDependencies: List<String> = emptyList(),
)
```

- [ ] **Step 4: Add `parseModuleDependencies` to `AndroidProjectInspector` and call it**

Add private function to `AndroidProjectInspector`:
```kotlin
private fun parseModuleDependencies(buildText: String): List<String> =
    Regex("""(?:implementation|api|runtimeOnly|compileOnly)\s*\(\s*project\s*\(\s*["'](:[^"']+)["']\s*\)\s*\)""")
        .findAll(buildText)
        .map { it.groupValues[1] }
        .distinct()
        .toList()
```

In `inspectModule()`, add the field to the returned `AndroidModuleSummary` (add after `hasAndroidTests`):
```kotlin
moduleDependencies = parseModuleDependencies(buildText),
```

- [ ] **Step 5: Update `android_project_inspect` MCP response to include the field**

In `DroidAgentMcpDispatcher.inspect()`, add to the module map entry:
```kotlin
"moduleDependencies" to it.moduleDependencies,
```

- [ ] **Step 6: Run all inspector and mcp-server tests**

```bash
./gradlew :android-inspector:test :mcp-server:test
```

Expected: all tests PASS.

- [ ] **Step 7: Commit**

```bash
git add android-inspector/src/main/kotlin/com/droidagentkit/inspector/Models.kt \
        android-inspector/src/main/kotlin/com/droidagentkit/inspector/AndroidProjectInspector.kt \
        android-inspector/src/test/kotlin/com/droidagentkit/inspector/AndroidInspectorTest.kt \
        mcp-server/src/main/kotlin/com/droidagentkit/mcp/DroidAgentMcpDispatcher.kt
git commit -m "feat(inspector): parse inter-module project dependencies into module summary"
```

---

### Task 8: Build variant and flavor detection

**Files:**
- Modify: `android-inspector/src/main/kotlin/com/droidagentkit/inspector/Models.kt`
- Modify: `android-inspector/src/main/kotlin/com/droidagentkit/inspector/AndroidProjectInspector.kt`
- Test: `android-inspector/src/test/kotlin/com/droidagentkit/inspector/AndroidInspectorTest.kt`

**Interfaces:**
- Consumes: nothing new
- Produces: `AndroidModuleSummary.buildTypes: List<String>`, `AndroidModuleSummary.productFlavors: List<String>`

- [ ] **Step 1: Write the failing test** — add to `AndroidInspectorTest`:

```kotlin
@Test
fun `inspector extracts build types and product flavors from application module`() {
    val root = Files.createTempDirectory("dak-variants")
    Files.writeString(
        root.resolve("settings.gradle.kts"),
        "rootProject.name = \"Variants\"\ninclude(\":app\")",
    )
    Files.createDirectories(root.resolve("app"))
    Files.writeString(
        root.resolve("app/build.gradle.kts"),
        """
        plugins { id("com.android.application") }
        android {
            namespace = "com.example.variants"
            buildTypes {
                release { minifyEnabled = true }
                staging { initWith(debug) }
            }
            productFlavors {
                demo { dimension = "tier" }
                full { dimension = "tier" }
            }
        }
        """.trimIndent(),
    )

    val report = AndroidProjectInspector().inspect(root)

    val app = report.modules.first()
    assertTrue(app.buildTypes.contains("release"))
    assertTrue(app.buildTypes.contains("staging"))
    assertTrue(app.productFlavors.contains("demo"))
    assertTrue(app.productFlavors.contains("full"))
    // flavor-specific commands should be generated
    assertTrue(report.commandMatrix.any { ":app:testDemoDebugUnitTest" in it.command })
}
```

- [ ] **Step 2: Run test to confirm it fails**

```bash
./gradlew :android-inspector:test --tests "com.droidagentkit.inspector.AndroidInspectorTest"
```

Expected: FAIL.

- [ ] **Step 3: Add `buildTypes` and `productFlavors` to `AndroidModuleSummary` in `Models.kt`**

```kotlin
data class AndroidModuleSummary(
    val path: String,
    val directory: String,
    val type: AndroidModuleType,
    val namespace: String?,
    val packageName: String?,
    val launcherActivities: List<String>,
    val usesCompose: Boolean,
    val hasUnitTests: Boolean,
    val hasAndroidTests: Boolean,
    val moduleDependencies: List<String> = emptyList(),
    val buildTypes: List<String> = emptyList(),
    val productFlavors: List<String> = emptyList(),
)
```

- [ ] **Step 4: Add `parseBlockNames` to `AndroidProjectInspector` and call it**

Add private function:
```kotlin
private fun parseBlockNames(buildText: String, sectionName: String): List<String> {
    // Find the opening '{' of the named block
    val sectionEnd = Regex("""(?m)^\s*$sectionName\s*\{""").find(buildText)?.range?.last
        ?: return emptyList()
    // Collect everything between the section's braces (start AFTER the '{')
    val body = StringBuilder()
    var depth = 0
    for (i in (sectionEnd + 1) until buildText.length) {
        when (buildText[i]) {
            '{' -> { body.append('{'); depth++ }
            '}' -> {
                if (depth == 0) break   // closing brace of the section itself
                depth--
                body.append('}')
            }
            else -> body.append(buildText[i])
        }
    }
    val excluded = setOf("android", "kotlin", "dependencies", "buildTypes", "productFlavors",
        "defaultConfig", "signingConfigs", "composeOptions", "lint", "packaging")
    return Regex("""^\s*(\w+)\s*\{""", RegexOption.MULTILINE)
        .findAll(body)
        .map { it.groupValues[1] }
        .filter { it !in excluded }
        .distinct()
        .toList()
}
```

In `inspectModule()`, add two fields to the returned `AndroidModuleSummary`:
```kotlin
buildTypes = parseBlockNames(buildText, "buildTypes"),
productFlavors = parseBlockNames(buildText, "productFlavors"),
```

- [ ] **Step 5: Update `commandSpecsFor` to generate flavour-specific test tasks**

Inside `commandSpecsFor`, after the existing `commands` list, add:
```kotlin
if (module.productFlavors.isNotEmpty()) {
    module.productFlavors.forEach { flavor ->
        val cap = flavor.replaceFirstChar { it.uppercaseChar() }
        commands += CommandSpec(
            id = "$moduleName-test-$flavor-unit",
            command = listOf("./gradlew", "${module.path}:test${cap}DebugUnitTest"),
            workingDirectory = root.toString(),
            mutatesProject = false,
            requiresDevice = false,
            timeoutSeconds = 600,
        )
    }
}
```

- [ ] **Step 6: Run all inspector tests**

```bash
./gradlew :android-inspector:test
```

Expected: all tests PASS.

- [ ] **Step 7: Commit**

```bash
git add android-inspector/src/main/kotlin/com/droidagentkit/inspector/Models.kt \
        android-inspector/src/main/kotlin/com/droidagentkit/inspector/AndroidProjectInspector.kt \
        android-inspector/src/test/kotlin/com/droidagentkit/inspector/AndroidInspectorTest.kt
git commit -m "feat(inspector): detect build types and product flavors, generate flavor-specific test commands"
```

---

### Task 9: Richer version catalog parsing

**Files:**
- Modify: `android-inspector/src/main/kotlin/com/droidagentkit/inspector/AndroidProjectInspector.kt`
- Test: `android-inspector/src/test/kotlin/com/droidagentkit/inspector/AndroidInspectorTest.kt`

**Interfaces:**
- Consumes: nothing new
- Produces: `AndroidProjectReport.versions` now includes library aliases resolved via `version.ref`

- [ ] **Step 1: Write the failing test** — add to `AndroidInspectorTest`:

```kotlin
@Test
fun `inspector resolves library aliases from version catalog`() {
    val root = Files.createTempDirectory("dak-catalog")
    Files.writeString(
        root.resolve("settings.gradle.kts"),
        "rootProject.name = \"CatalogTest\"\n",
    )
    Files.createDirectories(root.resolve("gradle"))
    Files.writeString(
        root.resolve("gradle/libs.versions.toml"),
        """
        [versions]
        kotlin = "2.1.0"
        hilt = "2.54"

        [libraries]
        hilt-android = { group = "com.google.dagger", name = "hilt-android", version.ref = "hilt" }
        kotlin-stdlib = { module = "org.jetbrains.kotlin:kotlin-stdlib", version.ref = "kotlin" }

        [plugins]
        kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
        """.trimIndent(),
    )

    val report = AndroidProjectInspector().inspect(root)

    assertEquals("2.1.0", report.versions["kotlin"])
    assertEquals("2.54", report.versions["hilt"])
    assertEquals("2.54", report.versions["hilt-android"])
    assertEquals("2.1.0", report.versions["kotlin-stdlib"])
    assertEquals("2.1.0", report.versions["kotlin-android"])
}
```

- [ ] **Step 2: Run test to confirm it fails**

```bash
./gradlew :android-inspector:test --tests "com.droidagentkit.inspector.AndroidInspectorTest"
```

Expected: FAIL — library aliases are not resolved.

- [ ] **Step 3: Rewrite `parseVersions` in `AndroidProjectInspector`**

Replace the existing `parseVersions` function:
```kotlin
private fun parseVersions(root: Path): Map<String, String> {
    val catalog = root.resolve("gradle/libs.versions.toml")
    if (!catalog.exists()) return emptyMap()

    val lines = Files.readAllLines(catalog)
    val versionTable = linkedMapOf<String, String>()   // [versions] → raw string values
    val result = linkedMapOf<String, String>()
    var currentTable = ""

    // First pass: collect [versions]
    for (raw in lines) {
        val line = raw.trim()
        if (line.isBlank() || line.startsWith("#")) continue
        if (line.startsWith("[") && line.endsWith("]")) {
            currentTable = line.removeSurrounding("[", "]").trim()
            continue
        }
        if (currentTable == "versions") {
            val m = Regex("""([A-Za-z0-9_.-]+)\s*=\s*"([^"]+)"""").find(line) ?: continue
            versionTable[m.groupValues[1]] = m.groupValues[2]
            result[m.groupValues[1]] = m.groupValues[2]
        }
    }

    // Second pass: resolve [libraries] and [plugins]
    currentTable = ""
    for (raw in lines) {
        val line = raw.trim()
        if (line.isBlank() || line.startsWith("#")) continue
        if (line.startsWith("[") && line.endsWith("]")) {
            currentTable = line.removeSurrounding("[", "]").trim()
            continue
        }
        if (currentTable != "libraries" && currentTable != "plugins") continue
        val alias = line.substringBefore("=").trim().takeIf { it.isNotBlank() } ?: continue
        val versionRef = Regex("""version\.ref\s*=\s*"([^"]+)"""").find(line)?.groupValues?.get(1)
        val versionDirect = Regex(""",?\s*version\s*=\s*"([^"]+)"""").find(line)?.groupValues?.get(1)
        val resolved = when {
            versionRef != null -> versionTable[versionRef]
            versionDirect != null -> versionDirect
            else -> null
        } ?: continue
        result.putIfAbsent(alias, resolved)
    }

    return result
}
```

- [ ] **Step 4: Run all inspector tests**

```bash
./gradlew :android-inspector:test
```

Expected: all tests PASS.

- [ ] **Step 5: Commit**

```bash
git add android-inspector/src/main/kotlin/com/droidagentkit/inspector/AndroidProjectInspector.kt \
        android-inspector/src/test/kotlin/com/droidagentkit/inspector/AndroidInspectorTest.kt
git commit -m "feat(inspector): resolve library and plugin version aliases from libs.versions.toml"
```

---

### Task 10: New auditor readiness signals + score documentation

**Files:**
- Modify: `auditor-cli/src/main/kotlin/com/droidagentkit/auditor/ReadinessAuditor.kt`
- Modify: `docs/security-and-permissions.md`
- Test: `auditor-cli/src/test/kotlin/com/droidagentkit/auditor/ReadinessAuditorTest.kt`

**Interfaces:**
- Consumes: `AndroidModuleSummary.directory` (already present)
- Produces: 3 new risk IDs (`missing-static-analysis`, `missing-proguard`, `missing-baseline-profile`); existing implicit version-catalog score becomes `missing-version-catalog` risk

- [ ] **Step 1: Write the failing tests** — add to `ReadinessAuditorTest`:

```kotlin
@Test
fun `auditor awards points and skips risk for static analysis config`() {
    val root = sampleAndroidProject()
    Files.writeString(root.resolve("detekt.yml"), "build:\n  maxIssues: 0\n")

    val withDetekt = ReadinessAuditor(AndroidProjectInspector()).audit(root)
    val withoutDetekt = ReadinessAuditor(AndroidProjectInspector()).audit(sampleAndroidProject())

    assertTrue(withDetekt.score > withoutDetekt.score)
    assertTrue(withDetekt.risks.none { it.id == "missing-static-analysis" })
    assertTrue(withoutDetekt.risks.any { it.id == "missing-static-analysis" })
}

@Test
fun `auditor awards points for proguard rules presence`() {
    val root = sampleAndroidProject()
    Files.writeString(root.resolve("app/proguard-rules.pro"), "-keep class * { *; }\n")

    val report = ReadinessAuditor(AndroidProjectInspector()).audit(root)

    assertTrue(report.risks.none { it.id == "missing-proguard" })
}

@Test
fun `auditor surfaces missing version catalog as named risk`() {
    val root = Files.createTempDirectory("dak-no-catalog")
    Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"NoCatalog\"\ninclude(\":app\")")
    Files.createDirectories(root.resolve("app"))
    Files.writeString(root.resolve("app/build.gradle.kts"), "plugins { id(\"com.android.application\") }")

    val report = ReadinessAuditor(AndroidProjectInspector()).audit(root)

    assertTrue(report.risks.any { it.id == "missing-version-catalog" })
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
./gradlew :auditor-cli:test --tests "com.droidagentkit.auditor.ReadinessAuditorTest"
```

Expected: new tests FAIL.

- [ ] **Step 3: Update `ReadinessAuditor.audit()` — replace implicit version-catalog score and add 3 new checks**

Find the existing implicit version-catalog line in `audit()`:
```kotlin
if (root.resolve("gradle/libs.versions.toml").exists()) score += 5
```

Replace it with:
```kotlin
// Version catalog (previously implicit, now named)
if (root.resolve("gradle/libs.versions.toml").exists()) score += 5
else risks += risk("missing-version-catalog", Severity.INFO, "No Gradle version catalog detected",
    "No gradle/libs.versions.toml found.", "Add a version catalog to centralise dependency versions.")

// Static analysis config
val hasStaticAnalysis = root.resolve("detekt.yml").exists() ||
    root.resolve(".detekt/config.yml").exists() ||
    project.modules.any { mod ->
        val buildFile = java.nio.file.Path.of(mod.directory).resolve("build.gradle.kts")
        buildFile.exists() && Files.readString(buildFile).contains("ktlint", ignoreCase = true)
    }
if (hasStaticAnalysis) score += 5
else risks += risk("missing-static-analysis", Severity.WARNING, "No static analysis config detected",
    "No detekt.yml, .detekt/config.yml, or ktlint reference in any build file.",
    "Add Detekt or ktlint to catch style and quality issues automatically.")

// ProGuard rules
val hasProguard = project.modules.any { mod ->
    java.nio.file.Path.of(mod.directory).resolve("proguard-rules.pro").toFile().exists()
}
if (hasProguard) score += 5
else risks += risk("missing-proguard", Severity.WARNING, "No ProGuard rules file detected",
    "No proguard-rules.pro found in any module directory.",
    "Add proguard-rules.pro and enable R8 minification in release builds.")

// Baseline Profile
val hasBaselineProfile = project.modules.any { mod ->
    val buildFile = java.nio.file.Path.of(mod.directory).resolve("build.gradle.kts")
    buildFile.exists() && Files.readString(buildFile).contains("baselineProfile", ignoreCase = true)
}
if (hasBaselineProfile) score += 5
```

Note: `project` here is the local variable `val project = inspector.inspect(root)` already present in `audit()`. Verify the variable name in the existing code before substituting.

- [ ] **Step 4: Update `docs/security-and-permissions.md` — add score breakdown section**

Append to the end of the file:
```markdown
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
```

- [ ] **Step 5: Run all auditor tests**

```bash
./gradlew :auditor-cli:test
```

Expected: all tests PASS.

- [ ] **Step 6: Commit**

```bash
git add auditor-cli/src/main/kotlin/com/droidagentkit/auditor/ReadinessAuditor.kt \
        auditor-cli/src/test/kotlin/com/droidagentkit/auditor/ReadinessAuditorTest.kt \
        docs/security-and-permissions.md
git commit -m "feat(auditor): add static-analysis, proguard, baseline-profile readiness checks and document score"
```

---

### Task 11: Enriched `android_report_bundle`

**Files:**
- Modify: `mcp-server/build.gradle.kts`
- Modify: `mcp-server/src/main/kotlin/com/droidagentkit/mcp/DroidAgentMcpDispatcher.kt`
- Test: `mcp-server/src/test/kotlin/com/droidagentkit/mcp/McpDispatcherTest.kt`

**Interfaces:**
- Consumes: `ReadinessAuditor` from `auditor-cli`; `AndroidProjectReport.versions` (enriched by Task 9); `AndroidModuleSummary.usesCompose`, `hasUnitTests`, `hasAndroidTests`
- Produces: `android_report_bundle` writes a structured markdown document instead of 5 lines

- [ ] **Step 1: Write the failing test** — add to `McpDispatcherTest`:

```kotlin
@Test
fun `report bundle writes structured markdown with modules table and safe commands`() {
    val root = Files.createTempDirectory("dak-bundle")
    Files.writeString(
        root.resolve("settings.gradle.kts"),
        "rootProject.name = \"BundleTest\"\ninclude(\":app\")",
    )
    Files.createDirectories(root.resolve("app/src/test/java"))
    Files.writeString(
        root.resolve("app/build.gradle.kts"),
        "plugins { id(\"com.android.application\") }\nandroid { namespace = \"com.example.bundle\" }",
    )
    val dispatcher = DroidAgentMcpDispatcher(DroidAgentConfig.default())

    val result = dispatcher.call("android_report_bundle", mapOf("rootPath" to root.toString()))

    assertEquals("success", result["status"])
    val artifact = (result["artifacts"] as List<*>).first() as Map<*, *>
    val content = java.nio.file.Files.readString(java.nio.file.Path.of(artifact["path"].toString()))
    assertTrue(content.contains("## Modules"))
    assertTrue(content.contains("## Safe Commands"))
    assertTrue(content.contains(":app"))
    assertTrue(content.contains("Readiness:"))
}
```

- [ ] **Step 2: Run test to confirm it fails**

```bash
./gradlew :mcp-server:test --tests "com.droidagentkit.mcp.McpDispatcherTest"
```

Expected: FAIL — current bundle has no "## Modules" section.

- [ ] **Step 3: Add `auditor-cli` dependency to `mcp-server/build.gradle.kts`**

```kotlin
dependencies {
    implementation(project(":toolbox-core"))
    implementation(project(":android-inspector"))
    implementation(project(":auditor-cli"))
    testImplementation("junit:junit:4.13.2")
}
```

- [ ] **Step 4: Replace `reportBundle()` in `DroidAgentMcpDispatcher`**

```kotlin
private fun reportBundle(arguments: Map<String, Any?>): Map<String, Any> {
    val root = rootPath(arguments)
    val report = inspector.inspect(root)
    val auditorReport = com.droidagentkit.auditor.ReadinessAuditor(inspector).audit(root)
    val timestamp = java.time.Instant.now().toString()

    val markdown = buildString {
        appendLine("# Android Report — ${report.projectName}")
        appendLine()
        appendLine("Generated: $timestamp   Readiness: ${auditorReport.score}/100 (${auditorReport.level})")
        appendLine()
        appendLine("## Modules")
        appendLine("| Path | Type | Namespace | Unit Tests | Android Tests | Compose |")
        appendLine("|------|------|-----------|------------|---------------|---------|")
        report.modules.forEach { mod ->
            appendLine(
                "| `${mod.path}` | ${mod.type.name.lowercase()} | ${mod.namespace ?: "—"}" +
                    " | ${if (mod.hasUnitTests) "yes" else "no"}" +
                    " | ${if (mod.hasAndroidTests) "yes" else "no"}" +
                    " | ${if (mod.usesCompose) "yes" else "no"} |",
            )
        }
        appendLine()
        appendLine("## Safe Commands")
        report.commandMatrix.forEach { cmd -> appendLine(cmd.command.joinToString(" ")) }
        appendLine()
        if (report.versions.isNotEmpty()) {
            appendLine("## Key Versions")
            val interestingKeys = setOf("kotlin", "compose", "hilt", "room", "retrofit", "coroutines", "agp")
            val keyVersions = report.versions.entries
                .filter { (k, _) -> interestingKeys.any { k.contains(it, ignoreCase = true) } }
                .take(8)
            val display = keyVersions.ifEmpty { report.versions.entries.take(5) }
            appendLine(display.joinToString("   ") { (k, v) -> "$k: $v" })
            appendLine()
        }
        if (auditorReport.risks.isNotEmpty()) {
            appendLine("## Warnings")
            auditorReport.risks.forEach { risk ->
                appendLine("[${risk.severity.wireName.uppercase()}] ${risk.id} — ${risk.title}")
            }
        }
    }

    val out = root.resolve(config.reports.outputDir).resolve("android-report.md")
    Files.createDirectories(out.parent)
    Files.writeString(out, markdown)

    return resultMap(
        ToolResult(
            status = ResultStatus.SUCCESS,
            summary = "Wrote enriched report bundle to $out (${auditorReport.score}/100 ${auditorReport.level})",
            artifacts = listOf(
                com.droidagentkit.core.ArtifactRef(
                    com.droidagentkit.core.ArtifactType.MARKDOWN,
                    out.toString(),
                    "text/markdown",
                    "Android report",
                ),
            ),
        ),
    )
}
```

- [ ] **Step 5: Run all mcp-server tests**

```bash
./gradlew :mcp-server:test
```

Expected: all tests PASS.

- [ ] **Step 6: Commit**

```bash
git add mcp-server/build.gradle.kts \
        mcp-server/src/main/kotlin/com/droidagentkit/mcp/DroidAgentMcpDispatcher.kt \
        mcp-server/src/test/kotlin/com/droidagentkit/mcp/McpDispatcherTest.kt
git commit -m "feat(mcp): enrich android_report_bundle with module table, safe commands, versions, and readiness"
```

---

### Task 12: ProjectLocator Gemini environment variable support

**Files:**
- Modify: `cli/src/main/kotlin/com/droidagentkit/cli/ProjectLocator.kt`
- Test: `cli/src/test/kotlin/com/droidagentkit/cli/CliParserTest.kt`

**Interfaces:**
- Consumes: nothing new
- Produces: `ProjectLocator.resolve()` checks `GEMINI_PROJECT_DIR` and `GEMINI_WORKSPACE` before `PWD`

- [ ] **Step 1: Write the failing test** — add to `CliParserTest`:

```kotlin
@Test
fun `project locator resolves gemini project dir env var`() {
    val env = mapOf("GEMINI_PROJECT_DIR" to "/tmp/my-android-project")

    val resolved = ProjectLocator.resolve("auto", environment = env)

    assertEquals(java.nio.file.Path.of("/tmp/my-android-project").toAbsolutePath().normalize(), resolved)
}
```

- [ ] **Step 2: Run test to confirm it fails**

```bash
./gradlew :cli:test --tests "com.droidagentkit.cli.CliParserTest"
```

Expected: FAIL — `GEMINI_PROJECT_DIR` is not in the lookup list.

- [ ] **Step 3: Add Gemini env vars to `ProjectLocator.resolve()`**

Replace the env var list:
```kotlin
val envPath = listOf(
    "CLAUDE_PROJECT_DIR",
    "CODEX_WORKSPACE",
    "CODEX_PROJECT_DIR",
    "GEMINI_PROJECT_DIR",
    "GEMINI_WORKSPACE",
    "PWD",
).firstNotNullOfOrNull { key ->
    environment[key]?.takeIf { it.isNotBlank() }?.let(Path::of)
}
```

- [ ] **Step 4: Run all CLI tests**

```bash
./gradlew :cli:test
```

Expected: all tests PASS.

- [ ] **Step 5: Run full test suite to confirm no regressions**

```bash
./gradlew test
```

Expected: all modules PASS.

- [ ] **Step 6: Commit**

```bash
git add cli/src/main/kotlin/com/droidagentkit/cli/ProjectLocator.kt \
        cli/src/test/kotlin/com/droidagentkit/cli/CliParserTest.kt
git commit -m "feat(cli): support GEMINI_PROJECT_DIR and GEMINI_WORKSPACE in project auto-resolution"
```
