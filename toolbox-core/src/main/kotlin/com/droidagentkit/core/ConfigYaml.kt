package com.droidagentkit.core

object ConfigYaml {
    fun render(
        groups: Set<ToolGroup>,
        capabilities: Set<Capability>,
        projectName: String,
    ): String {
        val lines = mutableListOf<String>()
        lines += "schemaVersion: 1"
        lines += "project:"
        lines += "  name: $projectName"
        lines += "safety:"
        lines += "  allowGradleTasks:"
        lines += "    - \":*:test*UnitTest\""
        lines += "    - \":*:lint*\""
        lines += "    - \":*:assemble*Debug\""
        lines += "    - \":*:*AndroidTest\""
        lines += "    - \":*:validate*ScreenshotTest\""
        if (capabilities.isEmpty()) {
            lines += "  allowAdbInput: false"
            lines += "  allowAppInstall: true"
            lines += "  allowEmulatorStart: false"
        } else {
            lines += "  allowCapabilities:"
            (capabilities + Capability.APP_INSTALL).map { it.name.lowercase() }.sorted().forEach { lines += "    - $it" }
        }
        lines += "  maxCommandSeconds: 600"
        if (groups.isNotEmpty()) {
            lines += "mcp:"
            lines += "  exposedGroups:"
            groups.map { it.name.lowercase() }.sorted().forEach { lines += "    - $it" }
        }
        lines += "reports:"
        lines += "  outputDir: \"build/droidagentkit\""
        lines += "redaction:"
        lines += "  enabled: true"
        lines += "  extraPatterns: []"
        return lines.joinToString("\n")
    }
}
