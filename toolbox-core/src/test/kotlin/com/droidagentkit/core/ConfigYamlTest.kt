package com.droidagentkit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ConfigYamlTest {
    @Test
    fun `renderProject writes a grant-free project config`() {
        val expected =
            """
            schemaVersion: 1
            project:
              name: demo
            safety:
              allowGradleTasks:
                - ":*:test*UnitTest"
                - ":*:lint*"
                - ":*:assemble*Debug"
                - ":*:*AndroidTest"
                - ":*:validate*ScreenshotTest"
              maxCommandSeconds: 600
            reports:
              outputDir: "build/droidagentkit"
            redaction:
              extraPatterns: []
            """.trimIndent()

        assertEquals(expected, ConfigYaml.renderProject("demo"))
    }

    @Test
    fun `renderUserPolicy with capabilities includes APP_INSTALL and omits legacy aliases`() {
        val yaml = ConfigYaml.renderUserPolicy(emptySet(), setOf(Capability.DEVICE_INPUT, Capability.APP_CONTROL))

        assertTrue(yaml.contains("  allowCapabilities:"))
        assertTrue(yaml.contains("    - app_control"))
        assertTrue(yaml.contains("    - app_install"))
        assertTrue(yaml.contains("    - device_input"))
        assertFalse(yaml.contains("allowAdbInput"))
        assertFalse(yaml.contains("allowAppInstall"))
        assertFalse(yaml.contains("allowEmulatorStart"))
        assertFalse(yaml.contains("project:"))
    }

    @Test
    fun `renderUserPolicy with groups adds an mcp exposedGroups section`() {
        val yaml = ConfigYaml.renderUserPolicy(setOf(ToolGroup.DEVICE_READ, ToolGroup.DEVICE_CONTROL), emptySet())

        assertTrue(yaml.contains("mcp:"))
        assertTrue(yaml.contains("  exposedGroups:"))
        assertTrue(yaml.contains("    - device_control"))
        assertTrue(yaml.contains("    - device_read"))
    }

    @Test
    fun `renderUserPolicy with empty groups omits the mcp section`() {
        val yaml = ConfigYaml.renderUserPolicy(emptySet(), setOf(Capability.DEVICE_INPUT))

        assertFalse(yaml.contains("mcp:"))
    }

    @Test
    fun `renderUserPolicy round-trips through the user-policy loader`() {
        val path = Files.createTempFile("dak-policy", ".yaml")
        val yaml =
            ConfigYaml.renderUserPolicy(
                setOf(ToolGroup.DEVICE_READ, ToolGroup.DEVICE_CONTROL),
                setOf(Capability.DEVICE_INPUT, Capability.APP_CONTROL),
            )
        Files.writeString(path, yaml)

        val result = DroidAgentConfigLoader.loadUserPolicy(path)

        assertTrue(result is ConfigLoadResult.Loaded)
        val loaded = result as ConfigLoadResult.Loaded
        assertTrue(loaded.warnings.isEmpty())
        assertEquals(setOf(ToolGroup.DEVICE_READ, ToolGroup.DEVICE_CONTROL), loaded.config.mcp.exposedGroups)
        assertEquals(
            setOf(Capability.DEVICE_INPUT, Capability.APP_CONTROL, Capability.APP_INSTALL),
            loaded.config.safety.allowCapabilities,
        )
    }

    @Test
    fun `renderProject round-trips through the project loader without grants`() {
        val root = Files.createTempDirectory("dak-configyaml-project")
        val configPath = root.resolve(".droidagentkit/config.yaml")
        Files.createDirectories(configPath.parent)
        Files.writeString(configPath, ConfigYaml.renderProject("demo"))

        val result = DroidAgentConfigLoader.load(root)

        assertTrue(result is ConfigLoadResult.Loaded)
        val loaded = result as ConfigLoadResult.Loaded
        assertTrue(loaded.warnings.isEmpty())
        assertEquals("demo", loaded.config.project.name)
        assertTrue(
            loaded.config.mcp.exposedGroups
                .isEmpty(),
        )
        assertTrue(
            loaded.config.safety.allowCapabilities
                .isEmpty(),
        )
    }
}
