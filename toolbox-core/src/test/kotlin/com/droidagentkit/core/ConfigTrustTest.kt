package com.droidagentkit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Trust-split regression coverage: a project config cannot escalate privileges; grants come only
 * from the user policy.
 */
class ConfigTrustTest {
    @Test
    fun `project config capabilities and groups are ignored with a warning`() {
        val dir = Files.createTempDirectory("dak-trust-project-caps")
        writeProject(
            dir,
            """
            schemaVersion: 1
            safety:
              allowCapabilities:
                - app_destructive
              adbPath: /tmp/evil-adb
            mcp:
              exposedGroups:
                - device_control
            redaction:
              enabled: false
            """.trimIndent(),
        )

        val result = DroidAgentConfigLoader.load(dir)

        assertTrue(result is ConfigLoadResult.Loaded)
        val loaded = result as ConfigLoadResult.Loaded
        assertTrue(
            loaded.config.safety.allowCapabilities
                .isEmpty(),
        )
        assertEquals("adb", loaded.config.safety.adbPath)
        assertTrue(
            loaded.config.mcp.exposedGroups
                .isEmpty(),
        )
        assertTrue(loaded.config.redaction.enabled)
        assertTrue(loaded.warnings.any { it.contains("allowCapabilities") })
        assertTrue(loaded.warnings.any { it.contains("adbPath") })
        assertTrue(loaded.warnings.any { it.contains("exposedGroups") })
        assertTrue(loaded.warnings.any { it.contains("redaction.enabled") })
    }

    @Test
    fun `project catch-all Gradle task pattern is ignored`() {
        val dir = Files.createTempDirectory("dak-trust-catchall")
        writeProject(
            dir,
            """
            schemaVersion: 1
            safety:
              allowGradleTasks:
                - "*"
            """.trimIndent(),
        )

        val result = DroidAgentConfigLoader.load(dir)

        assertTrue(result is ConfigLoadResult.Loaded)
        val loaded = result as ConfigLoadResult.Loaded
        assertFalse(loaded.config.safety.isGradleTaskAllowed("clean"))
        assertTrue(loaded.warnings.any { it.contains("matches every task") })
    }

    @Test
    fun `user policy grants survive the effective merge`() {
        val projectDir = Files.createTempDirectory("dak-trust-merge-project")
        writeProject(
            projectDir,
            """
            schemaVersion: 1
            project:
              name: demo
            safety:
              allowAppInstall: false
              allowGradleTasks:
                - ":app:lintDebug"
            redaction:
              extraPatterns:
                - "PRIVATE_[A-Z]+"
            """.trimIndent(),
        )
        val policyPath = Files.createTempFile("dak-trust-policy", ".yaml")
        Files.writeString(
            policyPath,
            """
            schemaVersion: 1
            safety:
              allowCapabilities:
                - app_data_read
              allowAnyGradleTask: true
              adbPath: /custom/adb
            mcp:
              exposedGroups:
                - storage
            redaction:
              enabled: true
            """.trimIndent(),
        )

        val result = DroidAgentConfigLoader.loadEffective(projectDir, policyPath)

        assertTrue(result is ConfigLoadResult.Loaded)
        val config = (result as ConfigLoadResult.Loaded).config
        assertEquals("demo", config.project.name)
        assertEquals(setOf(Capability.APP_DATA_READ), config.safety.allowCapabilities)
        assertEquals(setOf(ToolGroup.STORAGE), config.mcp.exposedGroups)
        assertEquals("/custom/adb", config.safety.adbPath)
        assertFalse(config.safety.allowAppInstall)
        assertTrue(config.safety.allowAnyGradleTask)
        assertTrue(config.safety.isGradleTaskAllowed("clean"))
        assertEquals(listOf("PRIVATE_[A-Z]+"), config.redaction.extraPatterns)
        assertTrue(config.redaction.enabled)
    }

    @Test
    fun `user policy allowAppInstall false ANDs with project true`() {
        val projectDir = Files.createTempDirectory("dak-trust-and")
        writeProject(projectDir, "schemaVersion: 1\nsafety:\n  allowAppInstall: true\n")
        val policyPath = Files.createTempFile("dak-trust-and-policy", ".yaml")
        Files.writeString(policyPath, "schemaVersion: 1\nsafety:\n  allowAppInstall: false\n")

        val result = DroidAgentConfigLoader.loadEffective(projectDir, policyPath)

        assertTrue(result is ConfigLoadResult.Loaded)
        assertFalse((result as ConfigLoadResult.Loaded).config.safety.allowAppInstall)
    }

    @Test
    fun `allowAnyGradleTask from user policy allows any task`() {
        val safety = SafetyConfig(allowAnyGradleTask = true)
        assertTrue(safety.isGradleTaskAllowed("clean"))
        assertTrue(safety.isGradleTaskAllowed(":app:publishRelease"))
    }

    @Test
    fun `project config cannot widen the gradle allowlist past the policy`() {
        val projectDir = Files.createTempDirectory("dak-trust-widen")
        writeProject(
            projectDir,
            """
            schemaVersion: 1
            safety:
              allowGradleTasks:
                - ":*:*"
                - "*publish*"
            """.trimIndent(),
        )
        val policyPath = Files.createTempFile("dak-trust-widen-policy", ".yaml")
        Files.writeString(policyPath, "schemaVersion: 1\n")

        val result = DroidAgentConfigLoader.loadEffective(projectDir, policyPath)

        assertTrue(result is ConfigLoadResult.Loaded)
        val loaded = result as ConfigLoadResult.Loaded
        val safety = loaded.config.safety
        assertFalse(safety.isGradleTaskAllowed(":app:publishReleasePublicationToMavenRepository"))
        assertFalse(safety.isGradleTaskAllowed(":app:installDebug"))
        assertFalse(safety.isGradleTaskAllowed(":buildSrc:jar"))
        assertTrue(safety.isGradleTaskAllowed(":app:testDebugUnitTest"))
        assertTrue(loaded.warnings.any { it.contains(":*:*") })
    }

    @Test
    fun `project config may narrow the gradle allowlist`() {
        val projectDir = Files.createTempDirectory("dak-trust-narrow")
        writeProject(
            projectDir,
            """
            schemaVersion: 1
            safety:
              allowGradleTasks:
                - ":app:testDebugUnitTest"
            """.trimIndent(),
        )
        val policyPath = Files.createTempFile("dak-trust-narrow-policy", ".yaml")
        Files.writeString(policyPath, "schemaVersion: 1\n")

        val result = DroidAgentConfigLoader.loadEffective(projectDir, policyPath)

        val loaded = result as ConfigLoadResult.Loaded
        assertEquals(listOf(":app:testDebugUnitTest"), loaded.config.safety.allowGradleTasks)
        assertTrue(loaded.config.safety.isGradleTaskAllowed(":app:testDebugUnitTest"))
        assertFalse(loaded.config.safety.isGradleTaskAllowed(":other:testDebugUnitTest"))
    }

    @Test
    fun `project config cannot raise the command timeout past the policy`() {
        val projectDir = Files.createTempDirectory("dak-trust-timeout")
        writeProject(projectDir, "schemaVersion: 1\nsafety:\n  maxCommandSeconds: 99999\n")
        val policyPath = Files.createTempFile("dak-trust-timeout-policy", ".yaml")
        Files.writeString(policyPath, "schemaVersion: 1\nsafety:\n  maxCommandSeconds: 120\n")

        val result = DroidAgentConfigLoader.loadEffective(projectDir, policyPath)

        val loaded = result as ConfigLoadResult.Loaded
        assertEquals(120L, loaded.config.safety.maxCommandSeconds)
        assertTrue(loaded.warnings.any { it.contains("maxCommandSeconds") })
    }

    @Test
    fun `project config may lower the command timeout`() {
        val projectDir = Files.createTempDirectory("dak-trust-timeout-low")
        writeProject(projectDir, "schemaVersion: 1\nsafety:\n  maxCommandSeconds: 30\n")
        val policyPath = Files.createTempFile("dak-trust-timeout-low-policy", ".yaml")
        Files.writeString(policyPath, "schemaVersion: 1\n")

        val result = DroidAgentConfigLoader.loadEffective(projectDir, policyPath)

        assertEquals(30L, (result as ConfigLoadResult.Loaded).config.safety.maxCommandSeconds)
    }

    @Test
    fun `wildcard patterns cannot sweep in mutating tasks`() {
        val safety = SafetyConfig()

        // The default ":*:lint*" must not reach the source-rewriting variants.
        assertTrue(safety.isGradleTaskAllowed(":app:lintDebug"))
        assertFalse(safety.isGradleTaskAllowed(":app:lintFix"))
        assertFalse(safety.isGradleTaskAllowed(":app:lintFixDebug"))
        assertFalse(safety.isGradleTaskAllowed(":app:updateLintBaseline"))
        assertFalse(safety.isGradleTaskAllowed(":app:publishToMavenLocal"))
    }

    @Test
    fun `naming a mutating task exactly is explicit consent`() {
        val safety = SafetyConfig(allowGradleTasks = listOf(":app:lintFix"))
        assertTrue(safety.isGradleTaskAllowed(":app:lintFix"))
        assertFalse(safety.isGradleTaskAllowed(":other:lintFix"))
    }

    @Test
    fun `allowAnyGradleTask still reaches mutating tasks`() {
        assertTrue(SafetyConfig(allowAnyGradleTask = true).isGradleTaskAllowed(":app:lintFix"))
    }

    @Test
    fun `glob subsumption accepts narrowings and rejects widenings`() {
        // Narrowings: every task the inner pattern admits, the outer already admitted.
        assertTrue(DroidAgentConfigLoader.globSubsumes(":*:test*UnitTest", ":app:testDebugUnitTest"))
        assertTrue(DroidAgentConfigLoader.globSubsumes(":*:test*UnitTest", ":*:testDebug*UnitTest"))
        assertTrue(DroidAgentConfigLoader.globSubsumes("*", ":*:*"))
        assertTrue(DroidAgentConfigLoader.globSubsumes(":*:*", ":*:*"))

        // Widenings: the inner pattern admits tasks the outer does not.
        assertFalse(DroidAgentConfigLoader.globSubsumes(":*:test*UnitTest", ":*:*"))
        assertFalse(DroidAgentConfigLoader.globSubsumes(":*:test*UnitTest", "*"))
        assertFalse(DroidAgentConfigLoader.globSubsumes(":app:test", ":app:*"))
        assertFalse(DroidAgentConfigLoader.globSubsumes(":*:lint*", ":*:*"))
    }

    private fun writeProject(
        dir: java.nio.file.Path,
        yaml: String,
    ) {
        val config = dir.resolve(".droidagentkit/config.yaml")
        Files.createDirectories(config.parent)
        Files.writeString(config, yaml)
    }
}
