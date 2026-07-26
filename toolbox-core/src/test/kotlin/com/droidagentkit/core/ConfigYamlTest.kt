package com.droidagentkit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ConfigYamlTest {
    @Test
    fun `render with empty groups and capabilities matches the historical default config`() {
        val expected =
            """
            schemaVersion: 1
            project:
              name: inferred
            safety:
              allowGradleTasks:
                - ":*:test*UnitTest"
                - ":*:lint*"
                - ":*:assemble*Debug"
                - ":*:*AndroidTest"
                - ":*:validate*ScreenshotTest"
              allowAdbInput: false
              allowAppInstall: true
              allowEmulatorStart: false
              maxCommandSeconds: 600
            reports:
              outputDir: "build/droidagentkit"
            redaction:
              enabled: true
              extraPatterns: []
            """.trimIndent()

        assertEquals(expected, ConfigYaml.render(emptySet(), emptySet()))
    }

    @Test
    fun `render with capabilities omits the legacy boolean aliases`() {
        val yaml = ConfigYaml.render(emptySet(), setOf(Capability.DEVICE_INPUT, Capability.APP_CONTROL))

        assertTrue(yaml.contains("  allowCapabilities:"))
        assertTrue(yaml.contains("    - app_control"))
        assertTrue(yaml.contains("    - device_input"))
        assertTrue(!yaml.contains("allowAdbInput"))
        assertTrue(!yaml.contains("allowAppInstall"))
        assertTrue(!yaml.contains("allowEmulatorStart"))
    }

    @Test
    fun `render with groups adds an mcp exposedGroups section`() {
        val yaml = ConfigYaml.render(setOf(ToolGroup.DEVICE_READ, ToolGroup.DEVICE_CONTROL), emptySet())

        assertTrue(yaml.contains("mcp:"))
        assertTrue(yaml.contains("  exposedGroups:"))
        assertTrue(yaml.contains("    - device_control"))
        assertTrue(yaml.contains("    - device_read"))
    }

    @Test
    fun `render with empty groups omits the mcp section entirely`() {
        val yaml = ConfigYaml.render(emptySet(), setOf(Capability.DEVICE_INPUT))

        assertTrue(!yaml.contains("mcp:"))
    }

    @Test
    fun `render output round-trips through the config loader`() {
        val root = Files.createTempDirectory("dak-configyaml-roundtrip")
        val yaml =
            ConfigYaml.render(
                setOf(ToolGroup.DEVICE_READ, ToolGroup.DEVICE_CONTROL),
                setOf(Capability.DEVICE_INPUT, Capability.APP_CONTROL),
            )
        val configPath = root.resolve(".droidagentkit/config.yaml")
        Files.createDirectories(configPath.parent)
        Files.writeString(configPath, yaml)

        val result = DroidAgentConfigLoader.load(root)

        assertTrue(result is ConfigLoadResult.Loaded)
        val loaded = result as ConfigLoadResult.Loaded
        assertTrue(loaded.warnings.isEmpty())
        assertEquals(setOf(ToolGroup.DEVICE_READ, ToolGroup.DEVICE_CONTROL), loaded.config.mcp.exposedGroups)
        assertEquals(setOf(Capability.DEVICE_INPUT, Capability.APP_CONTROL), loaded.config.safety.allowCapabilities)
    }
}
