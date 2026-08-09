package com.droidagentkit.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DoctorCommandTest {
    @Test
    fun `parser understands doctor with defaults`() {
        assertEquals(
            CliCommand.Doctor(project = ".", format = "text"),
            DroidAgentCliParser().parse(arrayOf("doctor")),
        )
    }

    @Test
    fun `parser understands doctor flags`() {
        assertEquals(
            CliCommand.Doctor(project = "/tmp/app", format = "json"),
            DroidAgentCliParser().parse(arrayOf("doctor", "--project", "/tmp/app", "--format", "json")),
        )
    }

    @Test
    fun `doctor rejects unknown flags like every other command`() {
        val command = DroidAgentCliParser().parse(arrayOf("doctor", "--verbose"))

        assertTrue(command is CliCommand.Help)
        assertTrue((command as CliCommand.Help).error!!.contains("--verbose"))
    }

    @Test
    fun `doctor appears in help output`() {
        // The command only helps people who can discover it.
        val help = DroidAgentCli().let { cli -> captureStdout { cli.run(arrayOf("--help")) } }

        assertTrue("help should list doctor: $help", help.contains("doctor"))
    }

    private fun captureStdout(block: () -> Unit): String {
        val original = System.out
        val buffer = java.io.ByteArrayOutputStream()
        System.setOut(java.io.PrintStream(buffer, true, Charsets.UTF_8))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return buffer.toString(Charsets.UTF_8)
    }
}
