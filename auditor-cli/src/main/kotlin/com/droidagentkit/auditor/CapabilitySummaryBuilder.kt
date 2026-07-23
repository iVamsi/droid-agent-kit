package com.droidagentkit.auditor

import com.droidagentkit.core.Capability
import com.droidagentkit.core.DroidAgentConfig
import com.droidagentkit.core.ToolGroup

/**
 * Builds a [CapabilitySummary] from the running server's configuration. The summary is a factual
 * report of what the server exposes and enables — it is never used to inflate readiness scoring.
 */
object CapabilitySummaryBuilder {
    private val DANGEROUS_CAPABILITIES =
        setOf(
            Capability.APP_DESTRUCTIVE,
            Capability.PERMISSION_MUTATION,
            Capability.EMULATOR_RESTORE,
            Capability.FILE_IMPORT,
            Capability.NETWORK_INTERCEPTION,
            Capability.GOLDEN_UPDATE,
            Capability.DEVICE_INPUT,
        )

    fun build(
        config: DroidAgentConfig,
        exposedGroups: Set<ToolGroup>,
    ): CapabilitySummary {
        val enabled = config.safety.allowedCapabilities()
        val dangerousFlags = enabled.intersect(DANGEROUS_CAPABILITIES).map { it.name.lowercase() }.sorted()
        val optionalExecutables =
            linkedMapOf(
                "adb" to configuredState(config.safety.adbPath, default = "adb"),
                "emulator" to configuredState(config.safety.emulatorPath, default = "emulator"),
                "traceProcessor" to configuredState(config.safety.traceProcessorPath),
                "mitmProxy" to configuredState(config.safety.mitmProxyPath),
            )
        val prerequisites = mutableListOf<String>()
        if (ToolGroup.DEVICE_CONTROL in exposedGroups && config.safety.emulatorPath == "emulator") {
            prerequisites += "emulator binary on PATH (or set safety.emulatorPath)"
        }
        if (ToolGroup.PERFETTO in exposedGroups && config.safety.traceProcessorPath.isBlank()) {
            prerequisites += "Perfetto trace_processor_shell executable (set safety.traceProcessorPath)"
        }
        if (ToolGroup.NETWORK_EXPERIMENTAL in exposedGroups) {
            prerequisites += "mitmproxy executable (set safety.mitmProxyPath)"
            if (Capability.NETWORK_INTERCEPTION in enabled) {
                prerequisites += "user-installed, debug-trusted CA on the emulator"
            }
        }
        return CapabilitySummary(
            exposedToolGroups = exposedGroups.map { it.name.lowercase() }.sorted(),
            enabledCapabilities = enabled.map { it.name.lowercase() }.sorted(),
            dangerousFlags = dangerousFlags,
            optionalExecutables = optionalExecutables,
            prerequisites = prerequisites,
        )
    }

    private fun configuredState(
        path: String,
        default: String = "",
    ): String =
        when {
            path.isBlank() -> "not-configured"
            path == default -> "default"
            else -> "configured"
        }
}
