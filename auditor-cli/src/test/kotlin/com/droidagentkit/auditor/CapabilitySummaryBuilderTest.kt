package com.droidagentkit.auditor

import com.droidagentkit.core.Capability
import com.droidagentkit.core.DroidAgentConfig
import com.droidagentkit.core.ToolGroup
import com.droidagentkit.inspector.AndroidProjectInspector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class CapabilitySummaryBuilderTest {
    @Test
    fun `build reports exposed groups and enabled capabilities`() {
        val config =
            DroidAgentConfig.default().copy(
                safety =
                    DroidAgentConfig.default().safety.copy(
                        allowCapabilities = setOf(Capability.APP_DATA_READ, Capability.APP_DESTRUCTIVE),
                    ),
            )
        val summary = CapabilitySummaryBuilder.build(config, setOf(ToolGroup.CORE, ToolGroup.STORAGE))

        assertTrue(summary.exposedToolGroups.contains("core"))
        assertTrue(summary.exposedToolGroups.contains("storage"))
        assertTrue(summary.enabledCapabilities.contains("app_data_read"))
        assertTrue(summary.enabledCapabilities.contains("app_destructive"))
        assertTrue(summary.dangerousFlags.contains("app_destructive"))
        assertFalse(summary.dangerousFlags.contains("app_data_read"))
    }

    @Test
    fun `build surfaces prerequisites for perfetto and network groups`() {
        val config = DroidAgentConfig.default()
        val summary = CapabilitySummaryBuilder.build(config, setOf(ToolGroup.PERFETTO, ToolGroup.NETWORK_EXPERIMENTAL))

        assertTrue(summary.prerequisites.any { it.contains("trace_processor_shell") })
        assertTrue(summary.prerequisites.any { it.contains("mitmproxy") })
    }

    @Test
    fun `build reports optional executables as default or not-configured`() {
        val config =
            DroidAgentConfig.default().copy(
                safety =
                    DroidAgentConfig.default().safety.copy(
                        mitmProxyPath = "/usr/local/bin/mitmdump",
                        traceProcessorPath = "",
                    ),
            )
        val summary = CapabilitySummaryBuilder.build(config, setOf(ToolGroup.CORE))

        assertEquals("default", summary.optionalExecutables["adb"])
        assertEquals("not-configured", summary.optionalExecutables["traceProcessor"])
        assertEquals("configured", summary.optionalExecutables["mitmProxy"])
    }

    @Test
    fun `redactPublic scrubs absolute root, home, user, and device serials`() {
        val root = Files.createTempDirectory("dak-redact")
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"Redact\"\ninclude(\":app\")")
        Files.createDirectories(root.resolve("app"))
        Files.writeString(root.resolve("app/build.gradle.kts"), "plugins { id(\"com.android.application\") }")

        val plain = ReadinessAuditor(AndroidProjectInspector()).audit(root, redactPublic = false)
        val redacted = ReadinessAuditor(AndroidProjectInspector()).audit(root, redactPublic = true)

        assertEquals(".", redacted.project.rootPath)
        assertFalse(redacted.project.rootPath.contains(root.fileName.toString()))
        assertTrue(plain.project.rootPath.contains(root.fileName.toString()))
        // Module directories become project-relative, not absolute.
        assertTrue(redacted.moduleMap.all { !it.directory.startsWith("/") })
        // Risk ids (stable finding codes) are preserved.
        assertEquals(plain.risks.map { it.id }, redacted.risks.map { it.id })
    }
}
