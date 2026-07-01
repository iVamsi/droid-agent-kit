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

    @Test
    fun `project locator resolves gemini project dir env var`() {
        val env = mapOf("GEMINI_PROJECT_DIR" to "/tmp/my-android-project")

        val resolved = ProjectLocator.resolve("auto", environment = env)

        assertEquals(java.nio.file.Path.of("/tmp/my-android-project").toAbsolutePath().normalize(), resolved)
    }

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
}
