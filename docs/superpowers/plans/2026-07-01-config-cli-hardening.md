# Config + CLI Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make malformed `.droidagentkit/config.yaml` produce actionable errors instead of crashes/silent wrong behavior, and give the CLI a declarative command registry that generates `--help` text and validates flags for all 8 commands.

**Architecture:** All changes are within the existing Kotlin/JVM monorepo, zero new third-party dependencies. `toolbox-core`'s `DroidAgentConfigLoader` gains a validating return type (`ConfigLoadResult`); `cli`'s `DroidAgentCliParser` gains a declarative `CliCommandSpec`/`CliCommandRegistry` layer that both commands are parsed against.

**Tech Stack:** Kotlin 2.x, JVM 17, JUnit 4.13.2, Gradle Kotlin DSL. No new dependencies.

## Global Constraints

- Zero new third-party dependencies — JDK stdlib only.
- Do not change the `.droidagentkit/config.yaml` format itself (flat 2-level nesting, `- item` lists) — only add validation around it.
- Do not change any existing `CliCommand` subtype's fields, or any command's actual flag names/behavior in the happy path — only add validation, help text, and error reporting.
- Tests use `Files.createTempDirectory` for filesystem fixtures; never mock data classes (matches existing repo convention).
- Test run command: `./gradlew :<module>:test --tests "<fully.qualified.ClassName>"`.
- Commit format: `type(scope): description` (e.g. `feat(core): validate config schema version`).
- `install-mcp` idempotency and user-scope default behavior must be unchanged.

---

## File Map

| File | Change |
|------|--------|
| `toolbox-core/src/main/kotlin/com/droidagentkit/core/Config.kt` | Add `ConfigError`, `ConfigLoadResult`; rewrite `DroidAgentConfigLoader.load()` to validate and return `ConfigLoadResult` |
| `toolbox-core/src/test/kotlin/com/droidagentkit/core/ConfigAndSafetyTest.kt` | Update existing loader test for new return type; add validation tests |
| `cli/src/test/kotlin/com/droidagentkit/cli/DroidAgentMainTest.kt` (new) | Test `resolveServerConfig()` fallback/warning behavior |
| `cli/src/main/kotlin/com/droidagentkit/cli/DroidAgentMain.kt` | Add `resolveServerConfig()`; handle `ConfigLoadResult` at both config-loading call sites; handle new `CliCommand.Help` fields; rewrite `help()`; add `usageFor()` |
| `cli/src/main/kotlin/com/droidagentkit/cli/CliCommand.kt` | `Help` becomes `data class Help(val error: String? = null, val commandName: String? = null)` |
| `cli/src/main/kotlin/com/droidagentkit/cli/CliCommandSpec.kt` (new) | `CliOption`, `CliCommandSpec`, `CliCommandRegistry` |
| `cli/src/main/kotlin/com/droidagentkit/cli/DroidAgentCliParser.kt` | Rewrite to validate against the registry before mapping to `CliCommand` |
| `cli/src/test/kotlin/com/droidagentkit/cli/CliParserTest.kt` | Add help/validation tests |
| `CHANGELOG.md` (new) | Note the config/CLI behavior changes |
| `docs/security-and-permissions.md` | Document config validation rules |
| `README.md` | Note `--help` availability |

---

## Task 1: Config loader validation

**Files:**
- Modify: `toolbox-core/src/main/kotlin/com/droidagentkit/core/Config.kt`
- Modify: `cli/src/main/kotlin/com/droidagentkit/cli/DroidAgentMain.kt:6,86-108`
- Test: `toolbox-core/src/test/kotlin/com/droidagentkit/core/ConfigAndSafetyTest.kt`
- Test: `cli/src/test/kotlin/com/droidagentkit/cli/DroidAgentMainTest.kt` (new)

**Interfaces:**
- Consumes: nothing new
- Produces: `ConfigError(line: Int, key: String, message: String)`; `sealed interface ConfigLoadResult` with `Loaded(config: DroidAgentConfig, warnings: List<String> = emptyList())` and `Invalid(errors: List<ConfigError>)`; `DroidAgentConfigLoader.load(projectRoot: Path): ConfigLoadResult` (was `: DroidAgentConfig`); `internal fun resolveServerConfig(configResult: ConfigLoadResult, onMessage: (String) -> Unit): DroidAgentConfig`

- [ ] **Step 1: Update the existing loader test for the new return type, then add new failing tests**

Replace the existing `config loader reads simple yaml overrides` test body in `ConfigAndSafetyTest.kt` and add new tests after it:

```kotlin
    @Test
    fun `config loader reads simple yaml overrides`() {
        val dir = Files.createTempDirectory("dak-config")
        val config = dir.resolve(".droidagentkit/config.yaml")
        Files.createDirectories(config.parent)
        Files.writeString(
            config,
            """
            schemaVersion: 1
            project:
              name: demo
            safety:
              allowGradleTasks:
                - ":app:connectedDebugAndroidTest"
              allowAppInstall: false
              maxCommandSeconds: 42
            reports:
              outputDir: "out/reports"
            redaction:
              enabled: false
              extraPatterns:
                - "PRIVATE_[A-Z]+"
            """.trimIndent(),
        )

        val result = DroidAgentConfigLoader.load(dir)

        assertTrue(result is ConfigLoadResult.Loaded)
        val loaded = (result as ConfigLoadResult.Loaded).config
        assertEquals("demo", loaded.project.name)
        assertTrue(loaded.safety.isGradleTaskAllowed(":app:connectedDebugAndroidTest"))
        assertFalse(loaded.safety.allowAppInstall)
        assertEquals(42, loaded.safety.maxCommandSeconds)
        assertEquals("out/reports", loaded.reports.outputDir)
        assertFalse(loaded.redaction.enabled)
        assertEquals(listOf("PRIVATE_[A-Z]+"), loaded.redaction.extraPatterns)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `config loader returns invalid for unsupported schema version`() {
        val dir = Files.createTempDirectory("dak-config-schema")
        val config = dir.resolve(".droidagentkit/config.yaml")
        Files.createDirectories(config.parent)
        Files.writeString(config, "schemaVersion: 2\nproject:\n  name: demo\n")

        val result = DroidAgentConfigLoader.load(dir)

        assertTrue(result is ConfigLoadResult.Invalid)
        val error = (result as ConfigLoadResult.Invalid).errors.single()
        assertEquals(1, error.line)
        assertEquals("schemaVersion", error.key)
        assertTrue(error.message.contains("schemaVersion 1"))
    }

    @Test
    fun `config loader returns invalid for non numeric schema version`() {
        val dir = Files.createTempDirectory("dak-config-schema-nan")
        val config = dir.resolve(".droidagentkit/config.yaml")
        Files.createDirectories(config.parent)
        Files.writeString(config, "schemaVersion: next\n")

        val result = DroidAgentConfigLoader.load(dir)

        assertTrue(result is ConfigLoadResult.Invalid)
        assertEquals("schemaVersion", (result as ConfigLoadResult.Invalid).errors.single().key)
    }

    @Test
    fun `config loader collects multiple value errors in one pass`() {
        val dir = Files.createTempDirectory("dak-config-multi")
        val config = dir.resolve(".droidagentkit/config.yaml")
        Files.createDirectories(config.parent)
        Files.writeString(
            config,
            """
            schemaVersion: 1
            safety:
              allowAppInstall: maybe
              maxCommandSeconds: soon
            """.trimIndent(),
        )

        val result = DroidAgentConfigLoader.load(dir)

        assertTrue(result is ConfigLoadResult.Invalid)
        val errors = (result as ConfigLoadResult.Invalid).errors
        assertEquals(2, errors.size)
        assertTrue(errors.any { it.key == "safety.allowAppInstall" })
        assertTrue(errors.any { it.key == "safety.maxCommandSeconds" })
    }

    @Test
    fun `config loader warns but succeeds on unknown key`() {
        val dir = Files.createTempDirectory("dak-config-unknown")
        val config = dir.resolve(".droidagentkit/config.yaml")
        Files.createDirectories(config.parent)
        Files.writeString(config, "schemaVersion: 1\nsafety:\n  saftey: true\n")

        val result = DroidAgentConfigLoader.load(dir)

        assertTrue(result is ConfigLoadResult.Loaded)
        val loaded = result as ConfigLoadResult.Loaded
        assertTrue(loaded.warnings.any { it.contains("safety.saftey") })
    }
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
./gradlew :toolbox-core:test --tests "com.droidagentkit.core.ConfigAndSafetyTest"
```

Expected: FAIL to compile — `ConfigLoadResult` does not exist yet, `DroidAgentConfigLoader.load()` returns `DroidAgentConfig`.

- [ ] **Step 3: Add `ConfigError` and `ConfigLoadResult` to `Config.kt`**

Add after `RedactionConfig` and before `object DroidAgentConfigLoader`:

```kotlin
data class ConfigError(val line: Int, val key: String, val message: String)

sealed interface ConfigLoadResult {
    data class Loaded(val config: DroidAgentConfig, val warnings: List<String> = emptyList()) : ConfigLoadResult
    data class Invalid(val errors: List<ConfigError>) : ConfigLoadResult
}
```

- [ ] **Step 4: Rewrite `DroidAgentConfigLoader` in `Config.kt`**

Replace the entire `object DroidAgentConfigLoader { ... }` block:

```kotlin
object DroidAgentConfigLoader {
    private val knownSections = setOf("project", "safety", "reports", "redaction")
    private val knownKeys = setOf(
        "project.name", "safety.allowGradleTasks", "safety.allowAdbInput", "safety.allowAppInstall",
        "safety.allowEmulatorStart", "safety.maxCommandSeconds", "reports.outputDir",
        "redaction.enabled", "redaction.extraPatterns",
    )

    fun load(projectRoot: Path): ConfigLoadResult {
        val path = projectRoot.resolve(".droidagentkit/config.yaml")
        if (!path.exists()) return ConfigLoadResult.Loaded(DroidAgentConfig.default())

        val lines = Files.readAllLines(path)

        for ((index, rawLine) in lines.withIndex()) {
            val line = rawLine.trim()
            if (!line.startsWith("schemaVersion")) continue
            val rawValue = line.substringAfter(":", missingDelimiterValue = "").trim().unquote()
            val version = rawValue.toIntOrNull()
            if (version == null || version != 1) {
                return ConfigLoadResult.Invalid(
                    listOf(
                        ConfigError(
                            index + 1,
                            "schemaVersion",
                            "unsupported schema version '$rawValue'; this build supports schemaVersion 1",
                        ),
                    ),
                )
            }
            break
        }

        val errors = mutableListOf<ConfigError>()
        val warnings = mutableListOf<String>()
        var section = ""
        var projectName = "inferred"
        val allowGradleTasks = mutableListOf<String>()
        var allowAdbInput = false
        var allowAppInstall = true
        var allowEmulatorStart = false
        var maxCommandSeconds = 600L
        var outputDir = "build/droidagentkit"
        var redactionEnabled = true
        val extraPatterns = mutableListOf<String>()
        var listTarget = ""

        for ((index, rawLine) in lines.withIndex()) {
            val lineNumber = index + 1
            val line = rawLine.trim()
            if (line.isBlank() || line.startsWith("#") || line.startsWith("schemaVersion")) continue
            if (!rawLine.startsWith(" ") && line.endsWith(":")) {
                section = line.removeSuffix(":")
                listTarget = ""
                if (section !in knownSections) warnings += "line $lineNumber: unknown section '$section' — ignored"
                continue
            }
            if (line.endsWith(":")) {
                listTarget = "$section.${line.removeSuffix(":")}"
                continue
            }
            if (line.startsWith("- ")) {
                val value = line.removePrefix("- ").unquote()
                when (listTarget) {
                    "safety.allowGradleTasks" -> allowGradleTasks.add(value)
                    "redaction.extraPatterns" -> extraPatterns.add(value)
                }
                continue
            }
            val key = line.substringBefore(":", missingDelimiterValue = "").trim()
            val value = line.substringAfter(":", missingDelimiterValue = "").trim().unquote()
            val fullKey = "$section.$key"
            when (fullKey) {
                "project.name" -> projectName = value
                "safety.allowAdbInput" -> allowAdbInput = value.toStrictBooleanOrError(lineNumber, fullKey, errors) ?: allowAdbInput
                "safety.allowAppInstall" -> allowAppInstall = value.toStrictBooleanOrError(lineNumber, fullKey, errors) ?: allowAppInstall
                "safety.allowEmulatorStart" -> allowEmulatorStart = value.toStrictBooleanOrError(lineNumber, fullKey, errors) ?: allowEmulatorStart
                "safety.maxCommandSeconds" -> maxCommandSeconds = value.toLongOrError(lineNumber, fullKey, errors) ?: maxCommandSeconds
                "reports.outputDir" -> outputDir = value
                "redaction.enabled" -> redactionEnabled = value.toStrictBooleanOrError(lineNumber, fullKey, errors) ?: redactionEnabled
                else -> if (fullKey !in knownKeys) warnings += "line $lineNumber: unknown key '$fullKey' — ignored"
            }
        }

        if (errors.isNotEmpty()) return ConfigLoadResult.Invalid(errors)

        val safety = SafetyConfig(
            allowGradleTasks = allowGradleTasks.ifEmpty { SafetyConfig().allowGradleTasks },
            allowAdbInput = allowAdbInput,
            allowAppInstall = allowAppInstall,
            allowEmulatorStart = allowEmulatorStart,
            maxCommandSeconds = maxCommandSeconds,
        )
        return ConfigLoadResult.Loaded(
            DroidAgentConfig(
                project = ProjectConfig(projectName),
                safety = safety,
                reports = ReportsConfig(outputDir),
                redaction = RedactionConfig(redactionEnabled, extraPatterns),
            ),
            warnings = warnings,
        )
    }

    private fun String.unquote(): String = trim().removeSurrounding("\"").removeSurrounding("'")

    private fun String.toStrictBooleanOrError(line: Int, key: String, errors: MutableList<ConfigError>): Boolean? =
        when (this) {
            "true" -> true
            "false" -> false
            else -> {
                errors += ConfigError(line, key, "expected true or false, got '$this'")
                null
            }
        }

    private fun String.toLongOrError(line: Int, key: String, errors: MutableList<ConfigError>): Long? =
        toLongOrNull() ?: run {
            errors += ConfigError(line, key, "expected a number, got '$this'")
            null
        }
}
```

- [ ] **Step 5: Run toolbox-core tests to confirm they pass**

```bash
./gradlew :toolbox-core:test
```

Expected: all tests PASS.

- [ ] **Step 6: Write the failing test for the server's config-fallback helper** — create `cli/src/test/kotlin/com/droidagentkit/cli/DroidAgentMainTest.kt`:

```kotlin
package com.droidagentkit.cli

import com.droidagentkit.core.ConfigError
import com.droidagentkit.core.ConfigLoadResult
import com.droidagentkit.core.DroidAgentConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DroidAgentMainTest {
    @Test
    fun `resolveServerConfig falls back to defaults and reports each error on invalid config`() {
        val messages = mutableListOf<String>()
        val invalid = ConfigLoadResult.Invalid(
            listOf(ConfigError(3, "safety.maxCommandSeconds", "expected a number, got 'soon'")),
        )

        val config = resolveServerConfig(invalid) { messages.add(it) }

        assertEquals(DroidAgentConfig.default(), config)
        assertEquals(1, messages.size)
        assertTrue(messages.first().contains("safety.maxCommandSeconds"))
    }

    @Test
    fun `resolveServerConfig passes through loaded config and reports warnings`() {
        val messages = mutableListOf<String>()
        val loaded = ConfigLoadResult.Loaded(DroidAgentConfig.default(), warnings = listOf("unknown key 'x'"))

        val config = resolveServerConfig(loaded) { messages.add(it) }

        assertEquals(DroidAgentConfig.default(), config)
        assertEquals(1, messages.size)
    }
}
```

- [ ] **Step 7: Run test to confirm it fails**

```bash
./gradlew :cli:test --tests "com.droidagentkit.cli.DroidAgentMainTest"
```

Expected: FAIL to compile — `resolveServerConfig` does not exist yet.

- [ ] **Step 8: Add `resolveServerConfig` and update both `DroidAgentConfigLoader.load()` call sites in `DroidAgentMain.kt`**

Add imports after the existing `import com.droidagentkit.core.DroidAgentConfigLoader` line:

```kotlin
import com.droidagentkit.core.ConfigLoadResult
import com.droidagentkit.core.DroidAgentConfig
```

Add this top-level function after the imports, before `fun main(args: Array<String>)`:

```kotlin
internal fun resolveServerConfig(configResult: ConfigLoadResult, onMessage: (String) -> Unit): DroidAgentConfig =
    when (configResult) {
        is ConfigLoadResult.Loaded -> {
            configResult.warnings.forEach { onMessage("droidagentkit config warning: $it") }
            configResult.config
        }
        is ConfigLoadResult.Invalid -> {
            configResult.errors.forEach {
                onMessage("droidagentkit config warning: line ${it.line}: ${it.key} — ${it.message} (using defaults)")
            }
            DroidAgentConfig.default()
        }
    }
```

Replace `serveMcp()` — it now uses the pure, tested helper instead of inline handling:

```kotlin
    private fun serveMcp(command: CliCommand.ServeMcp): Int {
        val projectRoot = ProjectLocator.resolve(command.project)
        val config = resolveServerConfig(DroidAgentConfigLoader.load(projectRoot)) { System.err.println(it) }
        val dispatcher = DroidAgentMcpDispatcher(config)
        if (command.transport == "stdio") {
            val stdio = DroidAgentStdioServer(dispatcher)
            generateSequence(::readLine).forEach { println(stdio.runOnce(it)) }
        } else {
            val server = DroidAgentMcpHttpServer(dispatcher, command.host, command.port)
            server.start()
            println("DroidAgentKit MCP server listening at http://${command.host}:${command.port}/mcp")
            Thread.currentThread().join()
        }
        return 0
    }
```

`mcpCall()` keeps its own inline handling (not the shared helper) because an invalid config must abort with a
non-zero exit rather than fall back to defaults — a different control-flow shape than `serveMcp()`'s
must-not-crash requirement. Replace `mcpCall()`:

```kotlin
    private fun mcpCall(project: String, tool: String, args: Map<String, Any>): Int {
        val root = ProjectLocator.resolve(project)
        val config = when (val configResult = DroidAgentConfigLoader.load(root)) {
            is ConfigLoadResult.Loaded -> {
                configResult.warnings.forEach { System.err.println("droidagentkit config warning: $it") }
                configResult.config
            }
            is ConfigLoadResult.Invalid -> {
                configResult.errors.forEach {
                    System.err.println("droidagentkit config error: line ${it.line}: ${it.key} — ${it.message}")
                }
                return 1
            }
        }
        val result = DroidAgentMcpDispatcher(config).call(tool, args + ("rootPath" to root.toString()))
        println(Json.write(result))
        return if (result["status"] == "failed" || result["status"] == "blocked") 2 else 0
    }
```

- [ ] **Step 9: Run all affected tests**

```bash
./gradlew :toolbox-core:test :cli:test
```

Expected: all tests PASS, including the two new `DroidAgentMainTest` cases from Step 6.

- [ ] **Step 10: Commit**

```bash
git add toolbox-core/src/main/kotlin/com/droidagentkit/core/Config.kt \
        toolbox-core/src/test/kotlin/com/droidagentkit/core/ConfigAndSafetyTest.kt \
        cli/src/main/kotlin/com/droidagentkit/cli/DroidAgentMain.kt \
        cli/src/test/kotlin/com/droidagentkit/cli/DroidAgentMainTest.kt
git commit -m "feat(core): validate config schemaVersion and value types with structured errors"
```

---

## Task 2: CLI command registry with help and flag validation

**Files:**
- Create: `cli/src/main/kotlin/com/droidagentkit/cli/CliCommandSpec.kt`
- Modify: `cli/src/main/kotlin/com/droidagentkit/cli/CliCommand.kt`
- Modify: `cli/src/main/kotlin/com/droidagentkit/cli/DroidAgentCliParser.kt`
- Modify: `cli/src/main/kotlin/com/droidagentkit/cli/DroidAgentMain.kt`
- Test: `cli/src/test/kotlin/com/droidagentkit/cli/CliParserTest.kt`

**Interfaces:**
- Consumes: nothing new
- Produces: `CliOption(flag: String, description: String, required: Boolean = false, takesValue: Boolean = true)`; `CliCommandSpec(name: String, description: String, options: List<CliOption>, freeformOptions: Boolean = false)`; `object CliCommandRegistry { val all: List<CliCommandSpec> }`; `CliCommand.Help(error: String? = null, commandName: String? = null)` (was `data object Help`)

- [ ] **Step 1: Write the failing tests** — add to `CliParserTest.kt`:

```kotlin
    @Test
    fun `parser shows global help for no args`() {
        val command = DroidAgentCliParser().parse(emptyArray())

        assertEquals(CliCommand.Help(), command)
    }

    @Test
    fun `parser shows command help for gradle --help`() {
        val command = DroidAgentCliParser().parse(arrayOf("gradle", "--help"))

        assertEquals(CliCommand.Help(commandName = "gradle"), command)
    }

    @Test
    fun `parser rejects unknown command with an error`() {
        val command = DroidAgentCliParser().parse(arrayOf("frobnicate"))

        assertTrue(command is CliCommand.Help)
        assertTrue((command as CliCommand.Help).error!!.contains("Unknown command 'frobnicate'"))
    }

    @Test
    fun `parser rejects unknown flag on gradle`() {
        val command = DroidAgentCliParser().parse(arrayOf("gradle", "--tsak", ":app:testDebugUnitTest"))

        assertTrue(command is CliCommand.Help)
        assertTrue((command as CliCommand.Help).error!!.contains("--tsak"))
    }

    @Test
    fun `parser rejects missing required task flag on gradle`() {
        val command = DroidAgentCliParser().parse(arrayOf("gradle", "--project", "."))

        assertTrue(command is CliCommand.Help)
        assertTrue((command as CliCommand.Help).error!!.contains("--task"))
    }

    @Test
    fun `parser still accepts freeform visuals flags`() {
        val command = DroidAgentCliParser().parse(arrayOf("visuals", "compare", "--some-freeform-flag", "value"))

        assertTrue(command is CliCommand.Visuals)
        val visuals = command as CliCommand.Visuals
        assertEquals("compare", visuals.action)
        assertEquals("value", visuals.options["some-freeform-flag"])
    }
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
./gradlew :cli:test --tests "com.droidagentkit.cli.CliParserTest"
```

Expected: FAIL to compile — `CliCommand.Help()` cannot be called as a constructor yet (it's a `data object`), `commandName`/`error` params don't exist.

- [ ] **Step 3: Change `Help` in `CliCommand.kt`**

Replace:

```kotlin
    data object Help : CliCommand()
```

with:

```kotlin
    data class Help(val error: String? = null, val commandName: String? = null) : CliCommand()
```

- [ ] **Step 4: Create `CliCommandSpec.kt`**

```kotlin
package com.droidagentkit.cli

data class CliOption(
    val flag: String,
    val description: String,
    val required: Boolean = false,
    val takesValue: Boolean = true,
)

data class CliCommandSpec(
    val name: String,
    val description: String,
    val options: List<CliOption>,
    val freeformOptions: Boolean = false,
)

object CliCommandRegistry {
    val all: List<CliCommandSpec> = listOf(
        CliCommandSpec(
            "serve-mcp",
            "Run the DroidAgentKit MCP server.",
            listOf(
                CliOption("--project", "Project root path. Defaults to cwd."),
                CliOption("--transport", "Transport: stdio or http. Defaults to http."),
                CliOption("--host", "Bind host for http transport. Defaults to 127.0.0.1."),
                CliOption("--port", "Bind port for http transport. Defaults to 8765."),
            ),
        ),
        CliCommandSpec(
            "inspect",
            "Inspect an Android project's modules and versions.",
            listOf(
                CliOption("--project", "Project root path. Defaults to cwd."),
                CliOption("--format", "Output format: markdown or json. Defaults to markdown."),
                CliOption("--output", "Write report to this file instead of stdout."),
            ),
        ),
        CliCommandSpec(
            "gradle",
            "Run an allowlisted Gradle task.",
            listOf(
                CliOption("--project", "Project root path. Defaults to cwd."),
                CliOption("--task", "Gradle task to run (must match the configured allowlist).", required = true),
            ),
        ),
        CliCommandSpec(
            "devices",
            "List connected adb devices.",
            listOf(
                CliOption("--project", "Project root path. Defaults to cwd."),
                CliOption("--format", "Output format: json or markdown. Defaults to json."),
            ),
        ),
        CliCommandSpec(
            "snapshot",
            "Capture a device screenshot.",
            listOf(
                CliOption("--device", "adb device serial.", required = true),
                CliOption("--output", "Output path prefix. Defaults to build/droidagentkit/snapshot."),
            ),
        ),
        CliCommandSpec(
            "audit",
            "Run the agent-readiness auditor.",
            listOf(
                CliOption("--project", "Project root path. Defaults to cwd."),
                CliOption("--write-agents", "Write AGENTS.md, skill, and config files.", takesValue = false),
                CliOption("--verify", "Exit non-zero if readiness regresses.", takesValue = false),
                CliOption("--fail-under", "Exit non-zero if score is under this threshold."),
                CliOption("--redact-public", "Redact evidence before writing public-facing output.", takesValue = false),
            ),
        ),
        CliCommandSpec(
            "visuals",
            "Run a visual-regression report/golden-update action.",
            emptyList(),
            freeformOptions = true,
        ),
        CliCommandSpec(
            "install-mcp",
            "Register DroidAgentKit as a user-scope MCP server.",
            listOf(
                CliOption("--targets", "Comma-separated: codex, claude, generic, all. Defaults to all."),
                CliOption("--bin", "Override path to the droidagent binary."),
                CliOption("--dry-run", "Preview changes without writing files.", takesValue = false),
                CliOption("--no-claude-apply", "Skip running the Claude Code apply step.", takesValue = false),
            ),
        ),
    )
}
```

- [ ] **Step 5: Rewrite `DroidAgentCliParser.kt`**

```kotlin
package com.droidagentkit.cli

class DroidAgentCliParser {
    fun parse(args: Array<String>): CliCommand {
        if (args.isEmpty() || args.first() == "-h" || args.first() == "--help") return CliCommand.Help()

        val commandName = args.first()
        val spec = CliCommandRegistry.all.find { it.name == commandName }
            ?: return CliCommand.Help(error = "Unknown command '$commandName'. Run 'droidagent --help' to see available commands.")

        val rest = args.drop(1)
        if (rest.any { it == "-h" || it == "--help" }) return CliCommand.Help(commandName = commandName)

        val options = parseOptions(rest)

        if (!spec.freeformOptions) {
            val allowedFlags = spec.options.map { it.flag }.toSet()
            val errors = mutableListOf<String>()
            options.keys.filter { "--$it" !in allowedFlags }.forEach { errors += "Unknown flag '--$it' for command '$commandName'." }
            spec.options.filter { it.required && it.flag.removePrefix("--") !in options }
                .forEach { errors += "${it.flag} is required for command '$commandName'." }
            if (errors.isNotEmpty()) return CliCommand.Help(error = errors.joinToString(" "))
        }

        return when (commandName) {
            "serve-mcp" -> CliCommand.ServeMcp(
                project = options["project"] ?: ".",
                transport = options["transport"] ?: "http",
                host = options["host"] ?: "127.0.0.1",
                port = options["port"]?.toIntOrNull() ?: 8765,
            )
            "inspect" -> CliCommand.Inspect(
                project = options["project"] ?: ".",
                format = options["format"] ?: "markdown",
                output = options["output"],
            )
            "gradle" -> CliCommand.Gradle(
                project = options["project"] ?: ".",
                task = options.getValue("task"),
            )
            "devices" -> CliCommand.Devices(
                project = options["project"] ?: ".",
                format = options["format"] ?: "json",
            )
            "snapshot" -> CliCommand.Snapshot(
                device = options.getValue("device"),
                output = options["output"] ?: "build/droidagentkit/snapshot",
            )
            "audit" -> CliCommand.Audit(
                project = options["project"] ?: ".",
                writeAgents = options.containsKey("write-agents"),
                verify = options.containsKey("verify"),
                failUnder = options["fail-under"]?.toIntOrNull(),
                redactPublic = options.containsKey("redact-public"),
            )
            "visuals" -> {
                val action = args.getOrNull(1) ?: "report"
                CliCommand.Visuals(action, parseOptions(args.drop(2)))
            }
            "install-mcp" -> CliCommand.InstallMcp(
                targets = (options["targets"] ?: "all")
                    .split(',')
                    .map { it.trim().lowercase() }
                    .filter { it.isNotBlank() },
                binPath = options["bin"],
                dryRun = options.containsKey("dry-run"),
                applyClaude = !options.containsKey("dry-run") && !options.containsKey("no-claude-apply"),
            )
            else -> CliCommand.Help(error = "Unknown command '$commandName'.")
        }
    }

    private fun parseOptions(tokens: List<String>): Map<String, String> {
        val options = linkedMapOf<String, String>()
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            if (token.startsWith("--")) {
                val key = token.removePrefix("--")
                val next = tokens.getOrNull(index + 1)
                if (next != null && !next.startsWith("--")) {
                    options[key] = next
                    index += 2
                } else {
                    options[key] = "true"
                    index += 1
                }
            } else {
                index += 1
            }
        }
        return options
    }
}
```

Note: the `visuals` branch re-derives `action` from the raw `args` (not `options`) exactly as the original code did — this is unchanged behavior, kept because `visuals`'s first token after the command name is a positional action, not a `--flag`.

- [ ] **Step 6: Run `cli` tests to confirm the new tests pass and check for compile errors in `DroidAgentMain.kt`**

```bash
./gradlew :cli:test
```

Expected: compile FAILS in `DroidAgentMain.kt` — `is CliCommand.Help ->` branch references the old `data object` shape.

- [ ] **Step 7: Update `DroidAgentMain.kt` to handle the new `Help` fields**

Replace the `run()` function's `is CliCommand.Help ->` branch (inside the `when (val command = parser.parse(args))` block):

```kotlin
            is CliCommand.Help -> when {
                command.error != null -> {
                    System.err.println(command.error)
                    1
                }
                command.commandName != null -> {
                    println(usageFor(command.commandName))
                    0
                }
                else -> {
                    println(help())
                    0
                }
            }
```

Replace the `help()` function:

```kotlin
    private fun help(): String = buildString {
        appendLine("DroidAgentKit alpha")
        appendLine()
        appendLine("Commands:")
        CliCommandRegistry.all.forEach { spec -> appendLine("  ${spec.name} — ${spec.description}") }
        appendLine()
        appendLine("Run 'droidagent <command> --help' for command-specific flags.")
    }
```

Add a new function right after `help()`:

```kotlin
    private fun usageFor(commandName: String): String {
        val spec = CliCommandRegistry.all.first { it.name == commandName }
        return buildString {
            appendLine("${spec.name} — ${spec.description}")
            if (spec.options.isNotEmpty()) {
                appendLine()
                appendLine("Flags:")
                spec.options.forEach { option ->
                    val marker = if (option.required) " (required)" else ""
                    appendLine("  ${option.flag}$marker — ${option.description}")
                }
            }
        }
    }
```

- [ ] **Step 8: Run all `cli` tests**

```bash
./gradlew :cli:test
```

Expected: all tests PASS.

- [ ] **Step 9: Run the full test suite to confirm no regressions**

```bash
./gradlew test
```

Expected: all modules PASS.

- [ ] **Step 10: Commit**

```bash
git add cli/src/main/kotlin/com/droidagentkit/cli/CliCommand.kt \
        cli/src/main/kotlin/com/droidagentkit/cli/CliCommandSpec.kt \
        cli/src/main/kotlin/com/droidagentkit/cli/DroidAgentCliParser.kt \
        cli/src/main/kotlin/com/droidagentkit/cli/DroidAgentMain.kt \
        cli/src/test/kotlin/com/droidagentkit/cli/CliParserTest.kt
git commit -m "feat(cli): add declarative command registry with help text and flag validation"
```

---

## Task 3: Document the behavior changes

**Files:**
- Create: `CHANGELOG.md`
- Modify: `docs/security-and-permissions.md`
- Modify: `README.md`

**Interfaces:**
- Consumes: nothing new (docs only)
- Produces: nothing new (docs only)

- [ ] **Step 1: Create `CHANGELOG.md`**

```markdown
# Changelog

All notable changes to DroidAgentKit are documented here. This project uses date-based alpha
development until a first tagged release.

## Unreleased

### Changed

- `DroidAgentConfigLoader.load()` now returns `ConfigLoadResult` (`Loaded` or `Invalid`) instead of a
  bare `DroidAgentConfig`. `schemaVersion` and value types (booleans, numbers) are validated; malformed
  config previously fell back to defaults silently or threw an uncaught exception.
- CLI commands now reject unknown flags and print `--help` usage generated from a command registry.
  Previously, unrecognized flags were silently ignored. The `visuals` command still accepts arbitrary
  passthrough flags, since its option set varies by action.
```

- [ ] **Step 2: Append a "Config Validation" section to `docs/security-and-permissions.md`**

Append to the end of the file:

```markdown

## Config Validation

`droidagent` validates `.droidagentkit/config.yaml` before using it:

- `schemaVersion` must be `1` (or omitted, which defaults to `1`). Any other value is rejected.
- Boolean fields (`allowAdbInput`, `allowAppInstall`, `allowEmulatorStart`, `redaction.enabled`) must be
  literal `true`/`false`.
- `safety.maxCommandSeconds` must be a whole number.
- Unrecognized sections or keys produce a warning (printed to stderr) but do not fail the load.

When validation fails, the CLI prints every error and exits non-zero. The MCP server logs the errors
and falls back to default configuration rather than crashing a long-running process.
```

- [ ] **Step 3: Add a `--help` note to `README.md`**

In the `## Direct CLI Usage` section, after the existing code block, add:

```markdown
Run any subcommand with `--help` to see its flags, e.g. `droidagent gradle --help`.
```

- [ ] **Step 4: Commit**

```bash
git add CHANGELOG.md docs/security-and-permissions.md README.md
git commit -m "docs: document config validation and CLI flag validation behavior change"
```

---

## Definition of Done

- [ ] `./gradlew test` passes for the whole project.
- [ ] `CHANGELOG.md` exists and documents both behavior changes.
- [ ] `docs/security-and-permissions.md` and `README.md` reflect the new behavior.
