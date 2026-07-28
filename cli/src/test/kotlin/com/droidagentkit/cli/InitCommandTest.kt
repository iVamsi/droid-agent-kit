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
    fun `init with a profile writes grants to the user policy and seeds a grant-free project config`() {
        val root = Files.createTempDirectory("dak-init-profile")
        val policyPath = Files.createTempFile("dak-init-policy", ".yaml")
        Files.deleteIfExists(policyPath)

        val exitCode =
            withPolicyPath(policyPath) {
                DroidAgentCli().run(arrayOf("init", "--project", root.toString(), "--profile", "storage"))
            }

        assertEquals(0, exitCode)
        val policy = Files.readString(policyPath)
        assertTrue(policy.contains("app_data_read"))
        assertTrue(policy.contains("storage"))
        val project = Files.readString(root.resolve(".droidagentkit/config.yaml"))
        assertFalse(project.contains("app_data_read"))
        assertFalse(project.contains("exposedGroups"))
        assertTrue(project.contains("allowGradleTasks"))
    }

    @Test
    fun `init refuses to overwrite an existing user policy without force`() {
        val root = Files.createTempDirectory("dak-init-refuse")
        val policyPath = Files.createTempFile("dak-init-policy-refuse", ".yaml")
        Files.writeString(policyPath, "schemaVersion: 1\n")

        val exitCode =
            withPolicyPath(policyPath) {
                DroidAgentCli().run(arrayOf("init", "--project", root.toString(), "--profile", "full"))
            }

        assertEquals(1, exitCode)
        assertEquals("schemaVersion: 1\n", Files.readString(policyPath))
    }

    @Test
    fun `init with force overwrites an existing user policy`() {
        val root = Files.createTempDirectory("dak-init-force")
        val policyPath = Files.createTempFile("dak-init-policy-force", ".yaml")
        Files.writeString(policyPath, "schemaVersion: 1\n")

        val exitCode =
            withPolicyPath(policyPath) {
                DroidAgentCli().run(arrayOf("init", "--project", root.toString(), "--profile", "storage", "--force"))
            }

        assertEquals(0, exitCode)
        assertTrue(Files.readString(policyPath).contains("app_data_read"))
    }

    @Test
    fun `init keeps an existing project config when seeding the user policy`() {
        val root = Files.createTempDirectory("dak-init-keep-project")
        val configPath = root.resolve(".droidagentkit/config.yaml")
        Files.createDirectories(configPath.parent)
        Files.writeString(configPath, "schemaVersion: 1\nproject:\n  name: kept\n")
        val policyPath = Files.createTempFile("dak-init-policy-keep", ".yaml")
        Files.deleteIfExists(policyPath)

        val exitCode =
            withPolicyPath(policyPath) {
                DroidAgentCli().run(arrayOf("init", "--project", root.toString(), "--profile", "storage"))
            }

        assertEquals(0, exitCode)
        assertEquals("schemaVersion: 1\nproject:\n  name: kept\n", Files.readString(configPath))
        assertTrue(Files.readString(policyPath).contains("storage"))
    }

    @Test
    fun `init rejects an unknown profile name and lists valid ones`() {
        val root = Files.createTempDirectory("dak-init-unknown-profile")
        val policyPath = Files.createTempFile("dak-init-policy-unknown", ".yaml")
        Files.deleteIfExists(policyPath)

        val exitCode =
            withPolicyPath(policyPath) {
                DroidAgentCli().run(arrayOf("init", "--project", root.toString(), "--profile", "not-a-profile"))
            }

        assertEquals(1, exitCode)
        assertFalse(Files.exists(policyPath))
        assertFalse(Files.exists(root.resolve(".droidagentkit/config.yaml")))
    }

    @Test
    fun `init list-profiles exits zero and writes nothing, even over an existing policy`() {
        val root = Files.createTempDirectory("dak-init-list")
        val policyPath = Files.createTempFile("dak-init-policy-list", ".yaml")
        Files.writeString(policyPath, "schemaVersion: 1\n")

        val originalOut = System.out
        val captured = ByteArrayOutputStream()
        System.setOut(PrintStream(captured))
        val exitCode =
            try {
                withPolicyPath(policyPath) {
                    DroidAgentCli().run(arrayOf("init", "--project", root.toString(), "--list-profiles"))
                }
            } finally {
                System.setOut(originalOut)
            }

        assertEquals(0, exitCode)
        assertEquals("schemaVersion: 1\n", Files.readString(policyPath))
        assertTrue(captured.toString().contains(ProfileCatalog.description("core")))
    }

    @Test
    fun `init with no profile and no terminal exits non-zero without writing`() {
        val root = Files.createTempDirectory("dak-init-no-tty")
        val policyPath = Files.createTempFile("dak-init-policy-notty", ".yaml")
        Files.deleteIfExists(policyPath)

        val exitCode =
            withPolicyPath(policyPath) {
                DroidAgentCli().run(arrayOf("init", "--project", root.toString()))
            }

        assertEquals(1, exitCode)
        assertFalse(Files.exists(policyPath))
        assertFalse(Files.exists(root.resolve(".droidagentkit/config.yaml")))
    }

    @Test
    fun `combining two profiles writes the union to the user policy`() {
        val root = Files.createTempDirectory("dak-init-combo")
        val policyPath = Files.createTempFile("dak-init-policy-combo", ".yaml")
        Files.deleteIfExists(policyPath)

        val exitCode =
            withPolicyPath(policyPath) {
                DroidAgentCli().run(
                    arrayOf("init", "--project", root.toString(), "--profile", "storage,network-experimental"),
                )
            }

        assertEquals(0, exitCode)
        val yaml = Files.readString(policyPath)
        assertTrue(yaml.contains("app_data_read"))
        assertTrue(yaml.contains("network_interception"))
    }

    private fun <T> withPolicyPath(
        policyPath: java.nio.file.Path,
        block: () -> T,
    ): T {
        val previous = System.getProperty("droidagentkit.policy")
        System.setProperty("droidagentkit.policy", policyPath.toString())
        return try {
            block()
        } finally {
            if (previous == null) {
                System.clearProperty("droidagentkit.policy")
            } else {
                System.setProperty("droidagentkit.policy", previous)
            }
        }
    }
}
