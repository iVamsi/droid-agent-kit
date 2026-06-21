package com.droidagentkit.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CliParserTest {
    @Test
    fun `parser understands inspect command`() {
        val command = DroidAgentCliParser().parse(arrayOf("inspect", "--project", ".", "--format", "markdown", "--output", "out.md"))

        assertEquals(CliCommand.Inspect(project = ".", format = "markdown", output = "out.md"), command)
    }

    @Test
    fun `parser understands mcp and audit commands`() {
        val mcp = DroidAgentCliParser().parse(arrayOf("serve-mcp", "--project", ".", "--transport", "http", "--port", "8765"))
        val audit = DroidAgentCliParser().parse(arrayOf("audit", "--project", ".", "--write-agents", "--fail-under", "80"))

        assertEquals(CliCommand.ServeMcp(project = ".", transport = "http", host = "127.0.0.1", port = 8765), mcp)
        assertEquals(CliCommand.Audit(project = ".", writeAgents = true, verify = false, failUnder = 80, redactPublic = false), audit)
    }

    @Test
    fun `parser keeps visuals subcommand arguments`() {
        val command = DroidAgentCliParser().parse(arrayOf("visuals", "compare", "--project", ".", "--baseline", "main", "--candidate", "HEAD"))

        assertTrue(command is CliCommand.Visuals)
        assertEquals("compare", (command as CliCommand.Visuals).action)
    }

    @Test
    fun `parser understands install mcp defaults and options`() {
        val command = DroidAgentCliParser().parse(
            arrayOf(
                "install-mcp",
                "--targets",
                "codex,claude",
                "--bin",
                "/opt/droidagent/bin/droidagent",
                "--dry-run",
            ),
        )

        assertEquals(
            CliCommand.InstallMcp(
                targets = listOf("codex", "claude"),
                binPath = "/opt/droidagent/bin/droidagent",
                dryRun = true,
                applyClaude = false,
            ),
            command,
        )
    }
}
