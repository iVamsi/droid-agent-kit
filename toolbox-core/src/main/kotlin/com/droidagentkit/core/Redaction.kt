package com.droidagentkit.core

data class RedactionResult(
    val text: String,
    val applied: List<String>,
    val warnings: List<String> = emptyList(),
)

/** Raised when a single pattern exceeds its matching budget; see [BoundedCharSequence]. */
private class RedactionTimeout : RuntimeException(null, null, false, false)

/**
 * Aborts a match that runs past [deadlineNanos].
 *
 * Catastrophic backtracking shows up as an enormous number of character reads rather than as a
 * long-running single operation, so checking the clock on each read is what makes an otherwise
 * unbounded match interruptible. Guarding this at match time covers every pathological pattern,
 * including ones a static heuristic would not recognize.
 */
private class BoundedCharSequence(
    private val inner: CharSequence,
    private val deadlineNanos: Long,
) : CharSequence {
    override val length: Int get() = inner.length

    override fun get(index: Int): Char {
        if (System.nanoTime() > deadlineNanos) throw RedactionTimeout()
        return inner[index]
    }

    override fun subSequence(
        startIndex: Int,
        endIndex: Int,
    ): CharSequence = BoundedCharSequence(inner.subSequence(startIndex, endIndex), deadlineNanos)

    override fun toString(): String = inner.toString()
}

/**
 * Caps the identifier runs either side of a keyword in the assignment rules.
 *
 * Unbounded `[A-Z0-9_]*` on both sides of an alternation is ambiguous: for a long unbroken run of
 * identifier characters the engine retries an enormous number of splits, which turned these rules
 * quadratic. A single 50k-character token in command output was enough to stall redaction, and
 * command output is attacker-influenced (logcat, build and test output). Real key names are far
 * shorter than this bound, so capping it costs no coverage.
 */
private const val MAX_KEY_CHARS = 64

class Redactor(
    private val config: RedactionConfig,
) {
    private data class Rule(
        val id: String,
        val regex: Regex,
        val replacement: String,
    )

    private val rules =
        listOf(
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
                Regex("(?i)([A-Z0-9_]{0,$MAX_KEY_CHARS}PASSWORD[A-Z0-9_]{0,$MAX_KEY_CHARS}[ \\t]*[:=][ \\t]*)[^\\s\\n]+"),
                "$1[REDACTED]",
            ),
            // Rules below must precede token-assignment: specific AWS/GitHub patterns must fire
            // before the generic TOKEN= rule would consume their values.
            Rule(
                "aws-access-key",
                Regex("AKIA[0-9A-Z]{16}"),
                "[REDACTED]",
            ),
            Rule(
                "github-classic-token",
                Regex("ghp_[A-Za-z0-9]{36}"),
                "[REDACTED]",
            ),
            Rule(
                "github-fine-grained-token",
                Regex("github_pat_[A-Za-z0-9_]{82}"),
                "[REDACTED]",
            ),
            // firebase-private-key must precede pem-private-key: the PEM pattern would match
            // the BEGIN PRIVATE KEY fragment inside the Firebase JSON value first otherwise.
            Rule(
                "firebase-private-key",
                Regex("\"private_key\"\\s*:\\s*\"-----BEGIN"),
                "\"private_key\":\"[REDACTED]",
            ),
            Rule(
                "pem-private-key",
                Regex("-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
                "[REDACTED-PEM]",
            ),
            Rule(
                "token-assignment",
                Regex("(?i)([A-Z0-9_]{0,$MAX_KEY_CHARS}(TOKEN|SECRET)[A-Z0-9_]{0,$MAX_KEY_CHARS}[ \\t]*[:=][ \\t]*)[^\\s\\n]+"),
                "$1[REDACTED]",
            ),
            Rule(
                "generic-secret-assignment",
                Regex(
                    "(?i)([A-Z0-9_]{0,$MAX_KEY_CHARS}(?:KEY|SECRET|CREDENTIAL)[A-Z0-9_]{0,$MAX_KEY_CHARS}" +
                        "[ \\t]*[:=][ \\t]*)(?!\"?\\[)([^\\s\\n]{8,})",
                ),
                "$1[REDACTED]",
            ),
        )

    fun redact(input: String): RedactionResult {
        if (!config.enabled) return RedactionResult(input, emptyList())
        var output = input
        val applied = linkedSetOf<String>()
        val warnings = mutableListOf<String>()
        for (rule in rules) {
            output = apply(output, rule.regex, rule.replacement, rule.id, applied, warnings) ?: output
        }
        for ((index, pattern) in config.extraPatterns.withIndex()) {
            val regex =
                try {
                    Regex(pattern)
                } catch (_: Exception) {
                    continue
                }
            output = apply(output, regex, "[REDACTED]", "extra-$index", applied, warnings) ?: output
        }
        return RedactionResult(output, applied.toList(), warnings)
    }

    /**
     * Runs one pattern under a matching budget. A pattern that blows the budget is skipped and
     * reported rather than allowed to hang the caller — the remaining rules still run, so built-in
     * redaction is never lost because a user-supplied pattern misbehaved.
     */
    private fun apply(
        input: String,
        regex: Regex,
        replacement: String,
        id: String,
        applied: MutableSet<String>,
        warnings: MutableList<String>,
    ): String? {
        val deadline = System.nanoTime() + PATTERN_BUDGET_NANOS
        return try {
            val bounded = BoundedCharSequence(input, deadline)
            if (!regex.containsMatchIn(bounded)) return null
            val result = regex.replace(BoundedCharSequence(input, deadline), replacement)
            applied.add(id)
            result
        } catch (_: RedactionTimeout) {
            warnings += "redaction-pattern-timeout:$id"
            null
        } catch (_: StackOverflowError) {
            // java.util.regex recurses per input character for alternation-under-quantifier, so a
            // long enough line overflows the stack before any deadline can fire. That is an Error
            // rather than an Exception, so it would otherwise escape and kill the whole tool call.
            warnings += "redaction-pattern-overflow:$id"
            null
        }
    }

    private companion object {
        /**
         * Generous enough that a legitimate pattern over a large capture never trips it, bounded
         * enough that a catastrophic one cannot hang the tool call. Applied per pattern, so one
         * misbehaving rule can never consume the budget of the built-in secret rules.
         */
        val PATTERN_BUDGET_NANOS =
            java.util.concurrent.TimeUnit.MILLISECONDS
                .toNanos(500)
    }
}
