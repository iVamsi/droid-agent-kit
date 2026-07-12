package com.droidagentkit.mcp.tools

import com.droidagentkit.core.DiagnosticFinding
import com.droidagentkit.core.Severity

object BuildFailureParser {
    private const val MAX_FINDINGS = 100

    fun parse(log: String): List<DiagnosticFinding> {
        val findings = mutableListOf<DiagnosticFinding>()
        log.lineSequence().forEach { line ->
            if (findings.size >= MAX_FINDINGS) return@forEach
            parseLine(line)?.let(findings::add)
        }
        if ("Configuration cache problems found" in log) {
            findings +=
                DiagnosticFinding(
                    category = "gradle.configuration-cache",
                    severity = Severity.ERROR,
                    title = "Configuration cache incompatibility",
                    detail = "Gradle reported configuration cache problems. Inspect the generated configuration-cache report.",
                )
        }
        return findings.distinct()
    }

    private fun parseLine(line: String): DiagnosticFinding? {
        val kotlin = Regex("""^e: (?:file://)?(.+\.kt):(\d+):(\d+)\s+(.+)$""").find(line)
        if (kotlin != null) {
            return finding(
                "compiler.kotlin",
                kotlin.groupValues[4],
                kotlin.groupValues
                    .drop(1)
                    .take(3)
                    .joinToString(":"),
            )
        }
        val java = Regex("""^(.+\.java):(\d+): error: (.+)$""").find(line)
        if (java != null) {
            return finding("compiler.java", java.groupValues[3], "${java.groupValues[1]}:${java.groupValues[2]}")
        }
        if ("Android resource linking failed" in line) {
            return finding("android.resources", "Android resource linking failed", null)
        }
        if ("Manifest merger failed" in line) {
            return finding("android.manifest", line.trim(), null)
        }
        return null
    }

    private fun finding(
        category: String,
        detail: String,
        location: String?,
    ) = DiagnosticFinding(category, Severity.ERROR, category, detail.take(8_000), location)
}
