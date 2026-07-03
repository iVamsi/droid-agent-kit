# Broader Agent/IDE Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add first-class `install-mcp` support for Cursor, Zed, and VS Code (GitHub Copilot Chat's config format), per `docs/superpowers/specs/2026-07-02-broader-ide-support-design.md`.

**Architecture:** A new `McpJsonConfigMerger` object (pure JSON parse-merge-write, built on `kotlinx-serialization-json`) is shared by all three new `McpInstaller` targets, each supplying its own top-level key and per-server JSON shape. Path resolution is OS-aware via an injectable `osName` constructor parameter, entirely relative to the existing injectable `home` parameter (no real environment-variable reads), keeping tests fully deterministic regardless of the host machine.

**Tech Stack:** Kotlin/JVM, `org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0` (already used in `mcp-server`; this plan extends the same dependency to `cli`), JUnit 4.13.2.

## Global Constraints

- `cli` module gains `kotlin("plugin.serialization") version "2.3.20"` and `org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0` — the exact same versions already used in `mcp-server`. No other new dependencies.
- No network calls anywhere.
- All 3 new targets are user-scope only (no project-level `.cursor/mcp.json`/`.vscode/mcp.json` variants).
- Verified config paths/schemas (from official docs, not recalled from training data):
  - Cursor: `~/.cursor/mcp.json`, top-level key `mcpServers`, entry `{command, args}`.
  - Zed: mac/linux `~/.config/zed/settings.json`, windows `<home>/AppData/Roaming/Zed/settings.json`, top-level key `context_servers`, entry `{command, args, env}`.
  - VS Code: mac `~/Library/Application Support/Code/User/mcp.json`, linux `~/.config/Code/User/mcp.json`, windows `<home>/AppData/Roaming/Code/User/mcp.json`, top-level key `servers`, entry `{type: "stdio", command, args}`.
- `McpJsonConfigMerger.merge` must preserve every sibling server entry under the same top-level key, and every unrelated top-level key in the document (critical for Zed's shared `settings.json`) — never a blind overwrite.
- Invalid existing JSON must produce a warning message and skip that target's write, never crash `install()` or block other targets in the same call.
- `McpInstallTargets.parse()`'s `"all"` keyword must expand to all 6 targets (codex, claude, generic, cursor, zed, vscode).
- No mocks in tests — `Files.createTempDirectory` fixtures, matching the existing `McpInstallerTest.kt` convention.

---

## File Map

| File | Change |
|---|---|
| `cli/build.gradle.kts` | Add `kotlin("plugin.serialization")` plugin + `kotlinx-serialization-json` dependency |
| `cli/src/main/kotlin/com/droidagentkit/cli/McpJsonConfigMerger.kt` | New |
| `cli/src/test/kotlin/com/droidagentkit/cli/McpJsonConfigMergerTest.kt` | New |
| `cli/src/main/kotlin/com/droidagentkit/cli/McpInstaller.kt` | Add `CURSOR`/`ZED`/`VSCODE` targets, path resolution, per-target server config builders, `install()` branches, `osName` constructor param, `McpInstallTargets.parse()` update |
| `cli/src/test/kotlin/com/droidagentkit/cli/McpInstallerTest.kt` | Extended |
| `docs/easy-mcp-installation.md` | Document the 3 new targets |
| `README.md` | Add the 3 new targets to the `install-mcp` bullet list |

---

### Task 1: `McpJsonConfigMerger`

**Files:**
- Modify: `cli/build.gradle.kts`
- Create: `cli/src/main/kotlin/com/droidagentkit/cli/McpJsonConfigMerger.kt`
- Create: `cli/src/test/kotlin/com/droidagentkit/cli/McpJsonConfigMergerTest.kt`

**Interfaces:**
- Produces: `object McpJsonConfigMerger { fun merge(existingJson: String, topLevelKey: String, serverName: String, serverConfig: JsonObject): String }` in `com.droidagentkit.cli` — Task 2 calls this for all three new targets.

- [ ] **Step 1: Add the kotlinx.serialization dependency**

Replace the full contents of `cli/build.gradle.kts` with:

```kotlin
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.3.20"
    application
}

dependencies {
    implementation(project(":toolbox-core"))
    implementation(project(":android-inspector"))
    implementation(project(":mcp-server"))
    implementation(project(":auditor-cli"))
    implementation(project(":visuals-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    testImplementation("junit:junit:4.13.2")
}

application {
    mainClass.set("com.droidagentkit.cli.DroidAgentMainKt")
    applicationName = "droidagent"
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
```

- [ ] **Step 2: Write the failing tests**

Create `cli/src/test/kotlin/com/droidagentkit/cli/McpJsonConfigMergerTest.kt`:

```kotlin
package com.droidagentkit.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class McpJsonConfigMergerTest {
    @Test
    fun `merge creates the top-level key and server entry when starting from an empty document`() {
        val serverConfig = buildJsonObject { put("command", "/bin/droidagent") }

        val result = McpJsonConfigMerger.merge("", "mcpServers", "droidagentkit", serverConfig)

        val servers = Json.parseToJsonElement(result).jsonObject["mcpServers"]!!.jsonObject
        assertEquals("/bin/droidagent", servers["droidagentkit"]!!.jsonObject["command"]!!.jsonPrimitive.content)
    }

    @Test
    fun `merge preserves a sibling server entry under the same top-level key`() {
        val existing = """{"mcpServers":{"other-tool":{"command":"/bin/other"}}}"""
        val serverConfig = buildJsonObject { put("command", "/bin/droidagent") }

        val result = McpJsonConfigMerger.merge(existing, "mcpServers", "droidagentkit", serverConfig)

        val servers = Json.parseToJsonElement(result).jsonObject["mcpServers"]!!.jsonObject
        assertEquals(2, servers.size)
        assertEquals("/bin/other", servers["other-tool"]!!.jsonObject["command"]!!.jsonPrimitive.content)
    }

    @Test
    fun `merge replaces a pre-existing droidagentkit entry instead of duplicating it`() {
        val existing = """{"mcpServers":{"droidagentkit":{"command":"/old/path"}}}"""
        val serverConfig = buildJsonObject { put("command", "/new/path") }

        val result = McpJsonConfigMerger.merge(existing, "mcpServers", "droidagentkit", serverConfig)

        val servers = Json.parseToJsonElement(result).jsonObject["mcpServers"]!!.jsonObject
        assertEquals(1, servers.size)
        assertEquals("/new/path", servers["droidagentkit"]!!.jsonObject["command"]!!.jsonPrimitive.content)
    }

    @Test
    fun `merge preserves unrelated top-level keys`() {
        val existing = """{"context_servers":{},"theme":"dark","some_other_setting":42}"""
        val serverConfig = buildJsonObject { put("command", "/bin/droidagent") }

        val result = McpJsonConfigMerger.merge(existing, "context_servers", "droidagentkit", serverConfig)

        val root = Json.parseToJsonElement(result).jsonObject
        assertEquals("dark", root["theme"]!!.jsonPrimitive.content)
        assertEquals("42", root["some_other_setting"]!!.jsonPrimitive.content)
    }

    @Test
    fun `merge throws on invalid existing json`() {
        val serverConfig = buildJsonObject { put("command", "/bin/droidagent") }

        assertThrows(Exception::class.java) {
            McpJsonConfigMerger.merge("not valid json at all {{{", "mcpServers", "droidagentkit", serverConfig)
        }
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :cli:test --tests "com.droidagentkit.cli.McpJsonConfigMergerTest"`
Expected: FAIL — compile error, `McpJsonConfigMerger` does not exist yet.

- [ ] **Step 4: Implement `McpJsonConfigMerger`**

Create `cli/src/main/kotlin/com/droidagentkit/cli/McpJsonConfigMerger.kt`:

```kotlin
package com.droidagentkit.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

object McpJsonConfigMerger {
    private val prettyJson = Json { prettyPrint = true }

    fun merge(existingJson: String, topLevelKey: String, serverName: String, serverConfig: JsonObject): String {
        val root = if (existingJson.isBlank()) buildJsonObject { } else Json.parseToJsonElement(existingJson).jsonObject
        val existingServers = (root[topLevelKey] as? JsonObject) ?: buildJsonObject { }
        val updatedServers = buildJsonObject {
            existingServers.forEach { (name, config) -> put(name, config) }
            put(serverName, serverConfig)
        }
        val updatedRoot = buildJsonObject {
            root.forEach { (key, value) -> if (key != topLevelKey) put(key, value) }
            put(topLevelKey, updatedServers)
        }
        return prettyJson.encodeToString(JsonObject.serializer(), updatedRoot)
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :cli:test --tests "com.droidagentkit.cli.McpJsonConfigMergerTest"`
Expected: PASS, 5/5 tests green.

- [ ] **Step 6: Commit**

```bash
git add cli/build.gradle.kts cli/src/main/kotlin/com/droidagentkit/cli/McpJsonConfigMerger.kt cli/src/test/kotlin/com/droidagentkit/cli/McpJsonConfigMergerTest.kt
git commit -m "feat(cli): add McpJsonConfigMerger for install-mcp JSON targets"
```

---

### Task 2: Wire Cursor, Zed, and VS Code targets into `McpInstaller`

**Files:**
- Modify: `cli/src/main/kotlin/com/droidagentkit/cli/McpInstaller.kt`
- Modify: `cli/src/test/kotlin/com/droidagentkit/cli/McpInstallerTest.kt`
- Modify: `docs/easy-mcp-installation.md`
- Modify: `README.md`

**Interfaces:**
- Consumes (from Task 1): `McpJsonConfigMerger.merge(existingJson, topLevelKey, serverName, serverConfig): String`.
- Produces: `McpInstaller(home, osName, commandExecutor)` — `osName` is a new constructor parameter, defaulting to `System.getProperty("os.name")`, inserted between the existing `home` and `commandExecutor` parameters.

- [ ] **Step 1: Write the failing tests**

Add these imports to the top of `cli/src/test/kotlin/com/droidagentkit/cli/McpInstallerTest.kt` (after the existing `java.nio.file.Path` import):

```kotlin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
```

Append these test methods to the `McpInstallerTest` class (before the closing `}`):

```kotlin
    @Test
    fun `cursor installer writes user mcp json idempotently`() {
        val home = Files.createTempDirectory("dak-home-cursor")
        val installer = McpInstaller(home = home, commandExecutor = { error("Claude should not run") })

        installer.install(
            McpInstallOptions(
                targets = setOf(McpInstallTarget.CURSOR),
                binPath = Path.of("/opt/droidagent/bin/droidagent"),
                dryRun = false,
                applyClaude = false,
            ),
        )
        val result = installer.install(
            McpInstallOptions(
                targets = setOf(McpInstallTarget.CURSOR),
                binPath = Path.of("/opt/droidagent/bin/droidagent"),
                dryRun = false,
                applyClaude = false,
            ),
        )

        val config = Files.readString(home.resolve(".cursor/mcp.json"))
        val servers = Json.parseToJsonElement(config).jsonObject["mcpServers"]!!.jsonObject
        assertEquals(1, servers.size)
        assertEquals("/opt/droidagent/bin/droidagent", servers["droidagentkit"]!!.jsonObject["command"]!!.jsonPrimitive.content)
        assertTrue(result.messages.any { it.contains("Cursor") })
    }

    @Test
    fun `zed installer preserves unrelated settings when adding the context server`() {
        val home = Files.createTempDirectory("dak-home-zed")
        Files.createDirectories(home.resolve(".config/zed"))
        Files.writeString(
            home.resolve(".config/zed/settings.json"),
            """{"theme":"dark","context_servers":{"other-tool":{"command":"/bin/other"}}}""",
        )
        val installer = McpInstaller(home = home, osName = "Mac OS X", commandExecutor = { error("Claude should not run") })

        installer.install(
            McpInstallOptions(
                targets = setOf(McpInstallTarget.ZED),
                binPath = Path.of("/opt/droidagent/bin/droidagent"),
                dryRun = false,
                applyClaude = false,
            ),
        )

        val config = Files.readString(home.resolve(".config/zed/settings.json"))
        val root = Json.parseToJsonElement(config).jsonObject
        assertEquals("dark", root["theme"]!!.jsonPrimitive.content)
        val servers = root["context_servers"]!!.jsonObject
        assertEquals(2, servers.size)
        assertEquals("/bin/other", servers["other-tool"]!!.jsonObject["command"]!!.jsonPrimitive.content)
        assertEquals("/opt/droidagent/bin/droidagent", servers["droidagentkit"]!!.jsonObject["command"]!!.jsonPrimitive.content)
    }

    @Test
    fun `zed installer resolves windows path relative to home`() {
        val home = Files.createTempDirectory("dak-home-zed-win")
        val installer = McpInstaller(home = home, osName = "Windows 11", commandExecutor = { error("Claude should not run") })

        installer.install(
            McpInstallOptions(
                targets = setOf(McpInstallTarget.ZED),
                binPath = Path.of("/opt/droidagent/bin/droidagent"),
                dryRun = false,
                applyClaude = false,
            ),
        )

        assertTrue(Files.exists(home.resolve("AppData/Roaming/Zed/settings.json")))
    }

    @Test
    fun `vscode installer resolves mac path and writes stdio type`() {
        val home = Files.createTempDirectory("dak-home-vscode-mac")
        val installer = McpInstaller(home = home, osName = "Mac OS X", commandExecutor = { error("Claude should not run") })

        installer.install(
            McpInstallOptions(
                targets = setOf(McpInstallTarget.VSCODE),
                binPath = Path.of("/opt/droidagent/bin/droidagent"),
                dryRun = false,
                applyClaude = false,
            ),
        )

        val config = Files.readString(home.resolve("Library/Application Support/Code/User/mcp.json"))
        val servers = Json.parseToJsonElement(config).jsonObject["servers"]!!.jsonObject
        assertEquals("stdio", servers["droidagentkit"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `vscode installer resolves linux path`() {
        val home = Files.createTempDirectory("dak-home-vscode-linux")
        val installer = McpInstaller(home = home, osName = "Linux", commandExecutor = { error("Claude should not run") })

        installer.install(
            McpInstallOptions(
                targets = setOf(McpInstallTarget.VSCODE),
                binPath = Path.of("/opt/droidagent/bin/droidagent"),
                dryRun = false,
                applyClaude = false,
            ),
        )

        assertTrue(Files.exists(home.resolve(".config/Code/User/mcp.json")))
    }

    @Test
    fun `invalid existing json produces a warning message without crashing`() {
        val home = Files.createTempDirectory("dak-home-invalid")
        Files.createDirectories(home.resolve(".cursor"))
        Files.writeString(home.resolve(".cursor/mcp.json"), "not valid json {{{")
        val installer = McpInstaller(home = home, commandExecutor = { error("Claude should not run") })

        val result = installer.install(
            McpInstallOptions(
                targets = setOf(McpInstallTarget.CURSOR),
                binPath = Path.of("/opt/droidagent/bin/droidagent"),
                dryRun = false,
                applyClaude = false,
            ),
        )

        assertTrue(result.messages.any { it.contains("Could not update") })
    }

    @Test
    fun `all target expansion includes the new ide targets`() {
        assertEquals(
            setOf(
                McpInstallTarget.CODEX,
                McpInstallTarget.CLAUDE,
                McpInstallTarget.GENERIC,
                McpInstallTarget.CURSOR,
                McpInstallTarget.ZED,
                McpInstallTarget.VSCODE,
            ),
            McpInstallTargets.parse(listOf("all")),
        )
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :cli:test --tests "com.droidagentkit.cli.McpInstallerTest"`
Expected: FAIL — compile error, `McpInstallTarget.CURSOR`/`ZED`/`VSCODE` don't exist yet, and `McpInstaller`'s constructor doesn't accept `osName`.

- [ ] **Step 3: Wire the new targets into `McpInstaller`**

Replace the full contents of `cli/src/main/kotlin/com/droidagentkit/cli/McpInstaller.kt`:

```kotlin
package com.droidagentkit.cli

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

enum class McpInstallTarget {
    CODEX,
    CLAUDE,
    GENERIC,
    CURSOR,
    ZED,
    VSCODE,
}

data class McpInstallOptions(
    val targets: Set<McpInstallTarget>,
    val binPath: Path,
    val dryRun: Boolean,
    val applyClaude: Boolean,
)

data class McpInstallResult(
    val messages: List<String>,
    val changedFiles: List<Path>,
    val genericJson: String,
)

class McpInstaller(
    private val home: Path = Path.of(System.getProperty("user.home")),
    private val osName: String = System.getProperty("os.name"),
    private val commandExecutor: (List<String>) -> Int = { command ->
        ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
            .waitFor()
    },
) {
    fun install(options: McpInstallOptions): McpInstallResult {
        val messages = mutableListOf<String>()
        val changed = mutableListOf<Path>()
        val generic = genericJson(options.binPath)

        if (McpInstallTarget.CODEX in options.targets) {
            val path = home.resolve(".codex/config.toml")
            val newText = installCodexBlock(path, options.binPath)
            if (!options.dryRun) {
                Files.createDirectories(path.parent)
                Files.writeString(path, newText)
                changed.add(path)
            }
            messages += "Codex user MCP config ${if (options.dryRun) "would be updated" else "updated"} at $path"
        }

        if (McpInstallTarget.CLAUDE in options.targets) {
            val command = claudeCommand(options.binPath)
            if (options.applyClaude && !options.dryRun) {
                val exit = runCatching { commandExecutor(command) }.getOrElse { -1 }
                if (exit == 0) {
                    messages += "Claude Code user-scope MCP server installed with claude mcp add."
                } else {
                    messages += "Claude Code command could not be completed automatically. Run manually: ${command.joinToString(" ")}"
                }
            } else {
                messages += "Claude Code user-scope install command: ${command.joinToString(" ")}"
            }
        }

        if (McpInstallTarget.GENERIC in options.targets) {
            messages += "Generic MCP stdio config:\n$generic"
        }

        if (McpInstallTarget.CURSOR in options.targets) {
            installJsonTarget("Cursor", cursorConfigPath(), "mcpServers", cursorServerConfig(options.binPath), options.dryRun, messages, changed)
        }

        if (McpInstallTarget.ZED in options.targets) {
            installJsonTarget("Zed", zedConfigPath(), "context_servers", zedServerConfig(options.binPath), options.dryRun, messages, changed)
        }

        if (McpInstallTarget.VSCODE in options.targets) {
            installJsonTarget("VS Code", vsCodeConfigPath(), "servers", vsCodeServerConfig(options.binPath), options.dryRun, messages, changed)
        }

        return McpInstallResult(messages, changed, generic)
    }

    private fun installJsonTarget(
        label: String,
        path: Path,
        topLevelKey: String,
        serverConfig: JsonObject,
        dryRun: Boolean,
        messages: MutableList<String>,
        changed: MutableList<Path>,
    ) {
        val existingJson = if (path.exists()) Files.readString(path) else ""
        val merged = runCatching { McpJsonConfigMerger.merge(existingJson, topLevelKey, "droidagentkit", serverConfig) }
        merged.fold(
            onSuccess = { newText ->
                if (!dryRun) {
                    Files.createDirectories(path.parent)
                    Files.writeString(path, newText)
                    changed.add(path)
                }
                messages += "$label user MCP config ${if (dryRun) "would be updated" else "updated"} at $path"
            },
            onFailure = { error ->
                messages += "Could not update $path: ${error.message ?: "invalid JSON"}. Check the file manually."
            },
        )
    }

    fun installCodexBlock(path: Path, binPath: Path): String {
        val existing = if (path.exists()) Files.readString(path) else ""
        val block = codexBlock(binPath)
        val pattern = Regex("(?s)\\n?# >>> droidagentkit mcp >>>.*?# <<< droidagentkit mcp <<<\\n?")
        val withoutOld = existing.replace(pattern, "\n").trimEnd()
        return buildString {
            if (withoutOld.isNotBlank()) {
                append(withoutOld)
                appendLine()
                appendLine()
            }
            append(block)
            appendLine()
        }
    }

    private fun codexBlock(binPath: Path): String =
        """
        # >>> droidagentkit mcp >>>
        [mcp_servers.droidagentkit]
        command = "${binPath.toString().escapeToml()}"
        args = ["serve-mcp", "--transport", "stdio", "--project", "auto"]
        # <<< droidagentkit mcp <<<
        """.trimIndent()

    fun claudeCommand(binPath: Path): List<String> = listOf(
        "claude",
        "mcp",
        "add",
        "--scope",
        "user",
        "--transport",
        "stdio",
        "droidagentkit",
        "--",
        binPath.toString(),
        "serve-mcp",
        "--transport",
        "stdio",
        "--project",
        "auto",
    )

    fun genericJson(binPath: Path): String =
        """
        {
          "mcpServers": {
            "droidagentkit": {
              "type": "stdio",
              "command": "${binPath.toString().escapeJson()}",
              "args": ["serve-mcp", "--transport", "stdio", "--project", "auto"]
            }
          }
        }
        """.trimIndent()

    private fun cursorConfigPath(): Path = home.resolve(".cursor/mcp.json")

    private fun zedConfigPath(): Path =
        if (osName.lowercase().contains("win")) appDataPath().resolve("Zed/settings.json") else home.resolve(".config/zed/settings.json")

    private fun vsCodeConfigPath(): Path {
        val name = osName.lowercase()
        return when {
            name.contains("win") -> appDataPath().resolve("Code/User/mcp.json")
            name.contains("mac") -> home.resolve("Library/Application Support/Code/User/mcp.json")
            else -> home.resolve(".config/Code/User/mcp.json")
        }
    }

    private fun appDataPath(): Path = home.resolve("AppData/Roaming")

    private fun serveArgsArray(): JsonArray =
        JsonArray(listOf("serve-mcp", "--transport", "stdio", "--project", "auto").map(::JsonPrimitive))

    private fun cursorServerConfig(binPath: Path): JsonObject = buildJsonObject {
        put("command", binPath.toString())
        put("args", serveArgsArray())
    }

    private fun zedServerConfig(binPath: Path): JsonObject = buildJsonObject {
        put("command", binPath.toString())
        put("args", serveArgsArray())
        put("env", buildJsonObject { })
    }

    private fun vsCodeServerConfig(binPath: Path): JsonObject = buildJsonObject {
        put("type", "stdio")
        put("command", binPath.toString())
        put("args", serveArgsArray())
    }

    private fun String.escapeToml(): String = replace("\\", "\\\\").replace("\"", "\\\"")

    private fun String.escapeJson(): String = replace("\\", "\\\\").replace("\"", "\\\"")
}

object McpInstallTargets {
    fun parse(values: List<String>): Set<McpInstallTarget> {
        val expanded = values.flatMap { if (it == "all") listOf("codex", "claude", "generic", "cursor", "zed", "vscode") else listOf(it) }
        return expanded.mapNotNull {
            when (it.lowercase()) {
                "codex" -> McpInstallTarget.CODEX
                "claude", "claude-code" -> McpInstallTarget.CLAUDE
                "generic", "json" -> McpInstallTarget.GENERIC
                "cursor" -> McpInstallTarget.CURSOR
                "zed" -> McpInstallTarget.ZED
                "vscode" -> McpInstallTarget.VSCODE
                else -> null
            }
        }.toSet().ifEmpty {
            setOf(
                McpInstallTarget.CODEX,
                McpInstallTarget.CLAUDE,
                McpInstallTarget.GENERIC,
                McpInstallTarget.CURSOR,
                McpInstallTarget.ZED,
                McpInstallTarget.VSCODE,
            )
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :cli:test --tests "com.droidagentkit.cli.McpInstallerTest"`
Expected: PASS, all tests green (existing 4 tests plus the 7 new ones).

- [ ] **Step 5: Run the full cli suite**

Run: `./gradlew :cli:test`
Expected: PASS, all tests green — the pre-existing `CliParserTest`/`DroidAgentCliIntegrationTest`/`DroidAgentMainTest` suites are unaffected by this change (nothing in `DroidAgentCliParser.kt` or `DroidAgentMain.kt` changed).

- [ ] **Step 6: Commit**

```bash
git add cli/src/main/kotlin/com/droidagentkit/cli/McpInstaller.kt cli/src/test/kotlin/com/droidagentkit/cli/McpInstallerTest.kt
git commit -m "feat(cli): add Cursor, Zed, and VS Code targets to install-mcp"
```

- [ ] **Step 7: Update `docs/easy-mcp-installation.md`**

Replace the "For only one target" code block:

```bash
./cli/build/install/droidagent/bin/droidagent install-mcp --targets codex
./cli/build/install/droidagent/bin/droidagent install-mcp --targets claude
./cli/build/install/droidagent/bin/droidagent install-mcp --targets generic
```

with:

```bash
./cli/build/install/droidagent/bin/droidagent install-mcp --targets codex
./cli/build/install/droidagent/bin/droidagent install-mcp --targets claude
./cli/build/install/droidagent/bin/droidagent install-mcp --targets cursor
./cli/build/install/droidagent/bin/droidagent install-mcp --targets zed
./cli/build/install/droidagent/bin/droidagent install-mcp --targets vscode
./cli/build/install/droidagent/bin/droidagent install-mcp --targets generic
```

Add these three sections to `## What It Installs`, immediately after the existing "### Other MCP Clients" section (at the end of that section, before "## How Project Auto-Detection Works"):

```markdown
### Cursor

Merges a `droidagentkit` entry into `~/.cursor/mcp.json` (creating the file if needed) under the
standard `mcpServers` key, preserving any other MCP servers already configured there:

```json
{
  "mcpServers": {
    "droidagentkit": {
      "command": "/absolute/path/to/droidagent",
      "args": ["serve-mcp", "--transport", "stdio", "--project", "auto"]
    }
  }
}
```

### Zed

Merges a `droidagentkit` entry into Zed's `settings.json` (`~/.config/zed/settings.json` on
macOS/Linux, `%APPDATA%\Zed\settings.json` on Windows) under the `context_servers` key. Since this is
Zed's general editor settings file, the installer only ever touches the `context_servers.droidagentkit`
entry — every other setting in the file is left untouched:

```json
{
  "context_servers": {
    "droidagentkit": {
      "command": "/absolute/path/to/droidagent",
      "args": ["serve-mcp", "--transport", "stdio", "--project", "auto"],
      "env": {}
    }
  }
}
```

### VS Code (GitHub Copilot Chat)

Merges a `droidagentkit` entry into VS Code's user-level `mcp.json` (`~/Library/Application
Support/Code/User/mcp.json` on macOS, `~/.config/Code/User/mcp.json` on Linux,
`%APPDATA%\Code\User\mcp.json` on Windows) under the `servers` key — this is VS Code's own MCP support,
which GitHub Copilot Chat's agent mode reads:

```json
{
  "servers": {
    "droidagentkit": {
      "type": "stdio",
      "command": "/absolute/path/to/droidagent",
      "args": ["serve-mcp", "--transport", "stdio", "--project", "auto"]
    }
  }
}
```
```

- [ ] **Step 8: Update `README.md`**

In the bullet list following "That command:", replace:

```markdown
- updates the Codex user config at `~/.codex/config.toml`;
- runs Claude Code's user-scope MCP install command when `claude` is available;
- prints a generic stdio MCP config for other tools;
- registers the server with `--project auto`, so it resolves the active project from agent-provided environment variables such as `CLAUDE_PROJECT_DIR`, `CODEX_WORKSPACE`, or the current working directory.
```

with:

```markdown
- updates the Codex user config at `~/.codex/config.toml`;
- runs Claude Code's user-scope MCP install command when `claude` is available;
- merges a `droidagentkit` entry into Cursor's, Zed's, and VS Code's user-level MCP configs, preserving any other servers/settings already there;
- prints a generic stdio MCP config for other tools;
- registers the server with `--project auto`, so it resolves the active project from agent-provided environment variables such as `CLAUDE_PROJECT_DIR`, `CODEX_WORKSPACE`, or the current working directory.
```

- [ ] **Step 9: Run the full project test suite**

Run: `./gradlew test`
Expected: PASS, `BUILD SUCCESSFUL`, no regressions in any module.

- [ ] **Step 10: Commit the docs**

```bash
git add docs/easy-mcp-installation.md README.md
git commit -m "docs: document Cursor, Zed, and VS Code install-mcp targets"
```
