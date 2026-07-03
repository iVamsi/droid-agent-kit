package com.droidagentkit.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class McpInstallerTest {
    @Test
    fun `codex installer writes a user-wide mcp server block idempotently`() {
        val home = Files.createTempDirectory("dak-home")
        val installer = McpInstaller(home = home, commandExecutor = { error("Claude should not run") })

        val first = installer.install(
            McpInstallOptions(
                targets = setOf(McpInstallTarget.CODEX),
                binPath = Path.of("/opt/droidagent/bin/droidagent"),
                dryRun = false,
                applyClaude = false,
            ),
        )
        val second = installer.install(
            McpInstallOptions(
                targets = setOf(McpInstallTarget.CODEX),
                binPath = Path.of("/opt/droidagent/bin/droidagent"),
                dryRun = false,
                applyClaude = false,
            ),
        )

        val config = Files.readString(home.resolve(".codex/config.toml"))
        assertTrue(config.contains("[mcp_servers.droidagentkit]"))
        assertTrue(config.contains("command = \"/opt/droidagent/bin/droidagent\""))
        assertTrue(config.contains("args = [\"serve-mcp\", \"--transport\", \"stdio\", \"--project\", \"auto\"]"))
        assertEquals(1, Regex("\\[mcp_servers\\.droidagentkit]").findAll(config).count())
        assertTrue(first.messages.any { it.contains("Codex") })
        assertTrue(second.messages.any { it.contains("Codex") })
    }

    @Test
    fun `claude installer produces user scope command and can run it`() {
        val home = Files.createTempDirectory("dak-home")
        val executed = mutableListOf<List<String>>()
        val installer = McpInstaller(home = home, commandExecutor = { command ->
            executed += command
            0
        })

        val result = installer.install(
            McpInstallOptions(
                targets = setOf(McpInstallTarget.CLAUDE),
                binPath = Path.of("/opt/droidagent/bin/droidagent"),
                dryRun = false,
                applyClaude = true,
            ),
        )

        assertEquals(
            listOf(
                "claude",
                "mcp",
                "add",
                "--scope",
                "user",
                "--transport",
                "stdio",
                "droidagentkit",
                "--",
                "/opt/droidagent/bin/droidagent",
                "serve-mcp",
                "--transport",
                "stdio",
                "--project",
                "auto",
            ),
            executed.single(),
        )
        assertTrue(result.messages.any { it.contains("Claude Code") })
    }

    @Test
    fun `generic config explains how unsupported tools can connect`() {
        val installer = McpInstaller(home = Files.createTempDirectory("dak-home"), commandExecutor = { 0 })

        val result = installer.install(
            McpInstallOptions(
                targets = setOf(McpInstallTarget.GENERIC),
                binPath = Path.of("/opt/droidagent/bin/droidagent"),
                dryRun = true,
                applyClaude = false,
            ),
        )

        assertTrue(result.genericJson.contains("\"droidagentkit\""))
        assertTrue(result.genericJson.contains("\"stdio\""))
        assertFalse(result.changedFiles.any { Files.exists(it) })
    }

    @Test
    fun `project locator prefers agent project variables before current directory`() {
        val cwd = Files.createTempDirectory("dak-cwd")
        val claudeProject = Files.createTempDirectory("dak-claude")
        val codexProject = Files.createTempDirectory("dak-codex")

        assertEquals(
            claudeProject,
            ProjectLocator.resolve(
                requested = "auto",
                environment = mapOf("CLAUDE_PROJECT_DIR" to claudeProject.toString(), "CODEX_WORKSPACE" to codexProject.toString()),
                currentDirectory = cwd,
            ),
        )
        assertEquals(
            codexProject,
            ProjectLocator.resolve(
                requested = "auto",
                environment = mapOf("CODEX_WORKSPACE" to codexProject.toString()),
                currentDirectory = cwd,
            ),
        )
        assertEquals(cwd, ProjectLocator.resolve("auto", emptyMap(), cwd))
        assertEquals(codexProject, ProjectLocator.resolve(codexProject.toString(), emptyMap(), cwd))
    }

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
}
