package com.droidagentkit.visuals

import com.droidagentkit.core.ResultStatus

class VisualReportBuilder {
    fun build(
        cases: List<VisualCaseResult>,
        warnings: List<String> = emptyList(),
    ): VisualReport {
        val findings = cases.flatMap { it.findings }
        val status =
            when {
                cases.any {
                    it.status == ResultStatus.FAILED
                } ||
                    findings.any { it.severity == VisualSeverity.ERROR } -> ResultStatus.FAILED
                cases.any { it.status == ResultStatus.PARTIAL } -> ResultStatus.PARTIAL
                else -> ResultStatus.SUCCESS
            }
        val fixPacket =
            AgentFixPacket(
                markdown =
                    buildString {
                        appendLine("# DroidAgentKit Visual Fix Packet")
                        appendLine()
                        if (findings.isEmpty()) {
                            appendLine("No visual findings.")
                        } else {
                            findings.forEach { finding ->
                                appendLine("## ${finding.caseName}: ${finding.title}")
                                appendLine("- Severity: ${finding.severity}")
                                appendLine("- Category: ${finding.category.wireName}")
                                appendLine("- Likely cause: ${finding.likelyCause}")
                                appendLine("- Suggested fix: ${finding.suggestedFixPrompt}")
                                appendLine()
                            }
                        }
                    },
            )
        return VisualReport(
            status = status,
            cases = cases,
            findings = findings,
            artifacts = findings.flatMap { it.evidence },
            agentFixPacket = fixPacket,
            warnings = warnings,
        )
    }
}
