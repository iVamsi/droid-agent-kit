package com.droidagentkit.core

/**
 * Renders the two config documents. Per ADR 0002 the project file holds only non-privileged
 * settings; grants (capabilities, opt-in tool groups) live in the user policy.
 */
object ConfigYaml {
    fun renderProject(projectName: String): String {
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
        lines += "  maxCommandSeconds: 600"
        lines += "reports:"
        lines += "  outputDir: \"build/droidagentkit\""
        lines += "redaction:"
        lines += "  extraPatterns: []"
        return lines.joinToString("\n")
    }

    fun renderUserPolicy(
        groups: Set<ToolGroup>,
        capabilities: Set<Capability>,
    ): String {
        val lines = mutableListOf<String>()
        lines += "schemaVersion: 1"
        lines += "# User policy — the only config that can grant capabilities or expose tool groups"
        lines += "# (see docs/adrs/0002-threat-model.md). Applies to every project this user opens."
        if (capabilities.isEmpty()) {
            lines += "# No extra capabilities granted; built-in defaults apply."
        } else {
            lines += "safety:"
            lines += "  allowCapabilities:"
            (capabilities + Capability.APP_INSTALL).map { it.name.lowercase() }.sorted().forEach { lines += "    - $it" }
        }
        if (groups.isNotEmpty()) {
            lines += "mcp:"
            lines += "  exposedGroups:"
            groups.map { it.name.lowercase() }.sorted().forEach { lines += "    - $it" }
        }
        return lines.joinToString("\n")
    }
}
