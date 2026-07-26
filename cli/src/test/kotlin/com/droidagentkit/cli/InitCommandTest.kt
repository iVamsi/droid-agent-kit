package com.droidagentkit.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files

class InitCommandTest {
    @Test
    fun `init with a profile writes the expected config without prompting`() {
        val root = Files.createTempDirectory("dak-init-profile")

        val exitCode = DroidAgentCli().run(arrayOf("init", "--project", root.toString(), "--profile", "storage"))

        assertEquals(0, exitCode)
        val yaml = Files.readString(root.resolve(".droidagentkit/config.yaml"))
        assertTrue(yaml.contains("app_data_read"))
        assertTrue(yaml.contains("storage"))
    }

    @Test
    fun `init refuses to overwrite an existing config without force`() {
        val root = Files.createTempDirectory("dak-init-refuse")
        val configPath = root.resolve(".droidagentkit/config.yaml")
        Files.createDirectories(configPath.parent)
        Files.writeString(configPath, "schemaVersion: 1\n")

        val exitCode = DroidAgentCli().run(arrayOf("init", "--project", root.toString(), "--profile", "full"))

        assertEquals(1, exitCode)
        assertEquals("schemaVersion: 1\n", Files.readString(configPath))
    }

    @Test
    fun `init with force overwrites an existing config`() {
        val root = Files.createTempDirectory("dak-init-force")
        val configPath = root.resolve(".droidagentkit/config.yaml")
        Files.createDirectories(configPath.parent)
        Files.writeString(configPath, "schemaVersion: 1\n")

        val exitCode = DroidAgentCli().run(arrayOf("init", "--project", root.toString(), "--profile", "storage", "--force"))

        assertEquals(0, exitCode)
        assertTrue(Files.readString(configPath).contains("app_data_read"))
    }

    @Test
    fun `init rejects an unknown profile name and lists valid ones`() {
        val root = Files.createTempDirectory("dak-init-unknown-profile")

        val exitCode = DroidAgentCli().run(arrayOf("init", "--project", root.toString(), "--profile", "not-a-profile"))

        assertEquals(1, exitCode)
        assertFalse(Files.exists(root.resolve(".droidagentkit/config.yaml")))
    }

    @Test
    fun `init list-profiles exits zero and writes nothing, even over an existing config`() {
        val root = Files.createTempDirectory("dak-init-list")
        val configPath = root.resolve(".droidagentkit/config.yaml")
        Files.createDirectories(configPath.parent)
        Files.writeString(configPath, "schemaVersion: 1\n")

        val originalOut = System.out
        val captured = ByteArrayOutputStream()
        System.setOut(PrintStream(captured))
        val exitCode =
            try {
                DroidAgentCli().run(arrayOf("init", "--project", root.toString(), "--list-profiles"))
            } finally {
                System.setOut(originalOut)
            }

        assertEquals(0, exitCode)
        assertEquals("schemaVersion: 1\n", Files.readString(configPath))
        assertTrue(captured.toString().contains(ProfileCatalog.description("core")))
    }

    @Test
    fun `init with no profile and no terminal exits non-zero without writing`() {
        // Gradle's test JVM has no attached console, so System.console() is reliably null here —
        // this exercises the real no-TTY guard, not a fake.
        val root = Files.createTempDirectory("dak-init-no-tty")

        val exitCode = DroidAgentCli().run(arrayOf("init", "--project", root.toString()))

        assertEquals(1, exitCode)
        assertFalse(Files.exists(root.resolve(".droidagentkit/config.yaml")))
    }

    @Test
    fun `combining two profiles writes the union`() {
        val root = Files.createTempDirectory("dak-init-combo")

        val exitCode =
            DroidAgentCli().run(arrayOf("init", "--project", root.toString(), "--profile", "storage,network-experimental"))

        assertEquals(0, exitCode)
        val yaml = Files.readString(root.resolve(".droidagentkit/config.yaml"))
        assertTrue(yaml.contains("app_data_read"))
        assertTrue(yaml.contains("network_interception"))
    }
}
