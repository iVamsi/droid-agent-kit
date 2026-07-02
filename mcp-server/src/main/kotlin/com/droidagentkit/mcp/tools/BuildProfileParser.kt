package com.droidagentkit.mcp.tools

data class TaskTiming(val taskPath: String, val durationMs: Long)

data class BuildProfileResult(
    val taskTimings: List<TaskTiming>,
    val totalBuildTimeMs: Long?,
    val configurationTimeMs: Long?,
)

object BuildProfileParser {
    private val taskRowRegex = Regex(
        """<tr>\s*<td[^>]*>([^<]+)</td>\s*<td class="numeric">([^<]+)</td>\s*<td>([^<]*)</td>\s*</tr>""",
    )
    private val durationRegex = Regex("""(?:(\d+)m\s*)?(?:([\d.]+)s)?""")

    fun parse(html: String): BuildProfileResult {
        val tab4Start = html.indexOf("id=\"tab4\"")
        if (tab4Start == -1) return BuildProfileResult(emptyList(), null, null)
        val nextTabStart = html.indexOf("<div class=\"tab\"", tab4Start + 1)
        val section = if (nextTabStart == -1) html.substring(tab4Start) else html.substring(tab4Start, nextTabStart)

        val timings = taskRowRegex.findAll(section).mapNotNull { match ->
            val (task, durationText, result) = match.destructured
            if (result.trim() == "(total)") return@mapNotNull null
            val durationMs = parseDurationToMs(durationText.trim()) ?: return@mapNotNull null
            TaskTiming(task.trim(), durationMs)
        }.toList()

        return BuildProfileResult(
            taskTimings = timings.sortedByDescending { it.durationMs },
            totalBuildTimeMs = extractSummaryDuration(html, "Total Build Time"),
            configurationTimeMs = extractSummaryDuration(html, "Configuring Projects"),
        )
    }

    private fun extractSummaryDuration(html: String, label: String): Long? {
        val regex = Regex(Regex.escape("<td>$label</td>") + """\s*<td class="numeric">([^<]+)</td>""")
        val match = regex.find(html) ?: return null
        return parseDurationToMs(match.groupValues[1].trim())
    }

    private fun parseDurationToMs(text: String): Long? {
        val match = durationRegex.matchEntire(text) ?: return null
        if (match.groupValues[1].isBlank() && match.groupValues[2].isBlank()) return null
        val minutes = match.groupValues[1].toLongOrNull() ?: 0L
        val seconds = match.groupValues[2].toDoubleOrNull() ?: 0.0
        return minutes * 60_000 + Math.round(seconds * 1000)
    }
}
