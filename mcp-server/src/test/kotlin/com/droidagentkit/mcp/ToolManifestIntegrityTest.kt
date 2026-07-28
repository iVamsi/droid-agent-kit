package com.droidagentkit.mcp

import com.droidagentkit.core.Capability
import com.droidagentkit.core.DroidAgentConfig
import com.droidagentkit.core.Json
import com.droidagentkit.core.ToolGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Pins every advertised tool's name + description + inputSchema. Drift fails the build so
 * accidental public-API / tool-poisoning-style description changes are reviewed deliberately.
 */
class ToolManifestIntegrityTest {
    @Test
    fun `full tool manifest hash matches the pinned snapshot`() {
        val root = Files.createTempDirectory("dak-manifest")
        val config =
            DroidAgentConfig.default().copy(
                safety =
                    DroidAgentConfig.default().safety.copy(
                        allowCapabilities = Capability.entries.toSet(),
                    ),
                mcp =
                    DroidAgentConfig.default().mcp.copy(
                        exposedGroups = ToolGroup.entries.filter { it != ToolGroup.CORE }.toSet(),
                    ),
            )
        val tools =
            DroidAgentMcpDispatcher(config, root)
                .listTools()
                .sortedBy { it.name }
                .map {
                    mapOf(
                        "name" to it.name,
                        "description" to it.description,
                        "inputSchema" to it.inputSchema,
                    )
                }
        val canonical = Json.write(tools)
        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .digest(canonical.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        // Bump PINNED_MANIFEST_SHA256 deliberately when tool public API changes; review the diff.
        assertEquals(
            "Tool manifest drifted. New SHA-256=$digest\nUpdate PINNED_MANIFEST_SHA256 after reviewing the tool list.",
            PINNED_MANIFEST_SHA256,
            digest,
        )
        assertTrue(tools.size >= 15)
    }

    companion object {
        // Generated from the full-group listTools() snapshot; update only with intentional API changes.
        const val PINNED_MANIFEST_SHA256 = "342b8098707b47f8300b86490ba660f23178a7f6487b036a16fca918cd5f2646"
    }
}
