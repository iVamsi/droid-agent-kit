package com.droidagentkit.mcp.tools

import com.droidagentkit.core.DiagnosticFinding
import com.droidagentkit.core.Severity

object CrashLogTriage {
    private val fatalExceptionPattern = Regex("""FATAL EXCEPTION:\s*(\S+)""")
    private val anrPattern = Regex("""ANR in (\S+)""")
    private val inputTimeoutPattern = Regex("""Input dispatching timed out\s*(\(.*)?""")
    private val stackFramePattern = Regex("""at\s""")

    fun triage(logcatText: String): List<DiagnosticFinding> {
        val lines = logcatText.lines()
        val findings = mutableListOf<DiagnosticFinding>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            val fatalMatch = fatalExceptionPattern.find(line)
            val anrMatch = anrPattern.find(line)
            val timeoutMatch = if (anrMatch == null) inputTimeoutPattern.find(line) else null
            when {
                fatalMatch != null -> {
                    val thread = fatalMatch.groupValues[1]
                    val headline =
                        lines
                            .getOrNull(index + 1)
                            ?.substringAfter(": ")
                            ?.trim()
                            .orEmpty()
                    val frames = mutableListOf<String>()
                    var cursor = index + 2
                    while (cursor < lines.size && stackFramePattern.containsMatchIn(lines[cursor])) {
                        frames.add(lines[cursor].substringAfter(": ").trim())
                        cursor++
                    }
                    findings +=
                        DiagnosticFinding(
                            category = "crash",
                            severity = Severity.CRITICAL,
                            title = headline.ifBlank { "Fatal exception on thread $thread" },
                            detail = (listOf(headline) + frames).joinToString("\n"),
                            location = thread,
                        )
                    index = if (cursor > index + 1) cursor else index + 1
                }
                anrMatch != null -> {
                    val process = anrMatch.groupValues[1]
                    val reason =
                        lines
                            .getOrNull(index + 1)
                            ?.substringAfter(": ")
                            ?.trim()
                            .orEmpty()
                    findings +=
                        DiagnosticFinding(
                            category = "anr",
                            severity = Severity.CRITICAL,
                            title = "ANR in $process",
                            detail = reason.ifBlank { line.trim() },
                            location = process,
                        )
                    // Skip the reason line too, or its own "Input dispatching timed out" text
                    // would be re-matched as a second, spurious standalone ANR finding.
                    index += 2
                }
                timeoutMatch != null -> {
                    findings +=
                        DiagnosticFinding(
                            category = "anr",
                            severity = Severity.CRITICAL,
                            title = "Input dispatching timed out",
                            detail = line.substringAfter(": ").trim().ifBlank { line.trim() },
                            location = null,
                        )
                    index += 1
                }
                else -> index += 1
            }
        }
        return findings
    }
}
