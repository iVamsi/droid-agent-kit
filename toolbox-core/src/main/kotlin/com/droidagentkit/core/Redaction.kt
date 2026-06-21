package com.droidagentkit.core

data class RedactionResult(
    val text: String,
    val applied: List<String>,
)

class Redactor(private val config: RedactionConfig) {
    private data class Rule(val id: String, val regex: Regex, val replacement: String)

    private val rules = listOf(
        Rule(
            "authorization-bearer",
            Regex("(?i)(Authorization\\s*:\\s*Bearer\\s+)[A-Za-z0-9._\\-]+"),
            "$1[REDACTED]",
        ),
        Rule(
            "google-api-key",
            Regex("AIza[0-9A-Za-z_\\-]{10,}"),
            "[REDACTED]",
        ),
        Rule(
            "password-assignment",
            Regex("(?i)([A-Z0-9_]*PASSWORD[A-Z0-9_]*\\s*[:=]\\s*)[^\\s\\n]+"),
            "$1[REDACTED]",
        ),
        Rule(
            "token-assignment",
            Regex("(?i)([A-Z0-9_]*(TOKEN|SECRET)[A-Z0-9_]*\\s*[:=]\\s*)[^\\s\\n]+"),
            "$1[REDACTED]",
        ),
    )

    fun redact(input: String): RedactionResult {
        if (!config.enabled) return RedactionResult(input, emptyList())
        var output = input
        val applied = linkedSetOf<String>()
        for (rule in rules) {
            if (rule.regex.containsMatchIn(output)) {
                output = output.replace(rule.regex, rule.replacement)
                applied.add(rule.id)
            }
        }
        for ((index, pattern) in config.extraPatterns.withIndex()) {
            val regex = Regex(pattern)
            if (regex.containsMatchIn(output)) {
                output = output.replace(regex, "[REDACTED]")
                applied.add("extra-$index")
            }
        }
        return RedactionResult(output, applied.toList())
    }
}
