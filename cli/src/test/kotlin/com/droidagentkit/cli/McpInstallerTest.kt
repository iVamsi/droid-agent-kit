package com.droidagentkit.cli

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
}
