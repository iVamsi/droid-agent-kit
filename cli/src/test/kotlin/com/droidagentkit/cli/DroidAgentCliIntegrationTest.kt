package com.droidagentkit.cli

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class DroidAgentCliIntegrationTest {
    @Test
    fun `run exits non-zero when config is invalid for a command that loads config`() {
        val root = Files.createTempDirectory("dak-cli-invalid-config")
        val configFile = root.resolve(".droidagentkit/config.yaml")
        Files.createDirectories(configFile.parent)
        Files.writeString(configFile, "schemaVersion: 1\nsafety:\n  maxCommandSeconds: soon\n")

        val exitCode = DroidAgentCli().run(arrayOf("devices", "--project", root.toString()))

        assertEquals(1, exitCode)
    }
}
