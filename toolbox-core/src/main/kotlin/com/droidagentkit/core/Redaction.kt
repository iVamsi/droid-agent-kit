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
    private val patternBudgetNanos: Long = DEFAULT_PATTERN_BUDGET_NANOS,
) {
    private sealed interface Step {
        data class Pattern(
            val id: String,
            val regex: Regex,
            val replacement: String,
        ) : Step

        /**
         * Assignment keys are scanned directly. A regex with a bounded identifier run on either
         * side of the keyword is still quadratic enough to blow the match budget on a long token,
         * and a built-in timeout withholds the whole output.
         */
        data class Assignment(
            val id: String,
            val keywords: List<String>,
            val minValueLength: Int,
            val skipBracketValues: Boolean,
        ) : Step
    }

    private val steps =
        listOf(
            Step.Pattern(
                "authorization-bearer",
                Regex("(?i)(Authorization\\s*:\\s*Bearer\\s+)[A-Za-z0-9._\\-]+"),
                "$1[REDACTED]",
            ),
            Step.Pattern(
                "authorization-basic",
                Regex("(?i)(Authorization\\s*:\\s*Basic\\s+)[A-Za-z0-9+/=]+"),
                "$1[REDACTED]",
            ),
            Step.Pattern(
                "cookie-header",
                Regex("(?i)(Cookie\\s*:\\s*)[^\\r\\n]+"),
                "$1[REDACTED]",
            ),
            Step.Pattern(
                "google-api-key",
                Regex("AIza[0-9A-Za-z_\\-]{10,}"),
                "[REDACTED]",
            ),
            Step.Assignment(
                "password-assignment",
                listOf("PASSWORD"),
                minValueLength = 1,
                skipBracketValues = false,
            ),
            Step.Pattern(
                "jwt",
                Regex("eyJ[A-Za-z0-9_\\-]{10,2000}\\.[A-Za-z0-9_\\-]{10,2000}\\.[A-Za-z0-9_\\-]{10,2000}"),
                "[REDACTED]",
            ),
            // Rules below must precede token-assignment: specific AWS/GitHub patterns must fire
            // before the generic TOKEN= rule would consume their values.
            Step.Pattern(
                "aws-access-key",
                Regex("AKIA[0-9A-Z]{16}"),
                "[REDACTED]",
            ),
            Step.Pattern(
                "github-classic-token",
                Regex("gh[opusr]_[A-Za-z0-9]{36}"),
                "[REDACTED]",
            ),
            Step.Pattern(
                "slack-token",
                Regex("xox[baprs]-[A-Za-z0-9\\-]{10,200}"),
                "[REDACTED]",
            ),
            Step.Pattern(
                "github-fine-grained-token",
                Regex("github_pat_[A-Za-z0-9_]{82}"),
                "[REDACTED]",
            ),
            // firebase-private-key must precede pem-private-key: the PEM pattern would match
            // the BEGIN PRIVATE KEY fragment inside the Firebase JSON value first otherwise.
            Step.Pattern(
                "firebase-private-key",
                Regex("\"private_key\"\\s*:\\s*\"(?:\\\\.|[^\"\\\\])*\""),
                "\"private_key\":\"[REDACTED]\"",
            ),
            Step.Pattern(
                "pem-private-key",
                Regex(
                    "-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----" +
                        "(?:(?:\\r?\\n|\\\\n)[A-Za-z0-9+/=]+)*" +
                        "(?:\\r?\\n-----END (?:RSA |EC |OPENSSH )?PRIVATE KEY-----)?",
                ),
                "[REDACTED-PEM]",
            ),
            Step.Assignment(
                "token-assignment",
                listOf("TOKEN", "SECRET"),
                minValueLength = 1,
                skipBracketValues = false,
            ),
            Step.Assignment(
                "generic-secret-assignment",
                listOf("KEY", "SECRET", "CREDENTIAL"),
                minValueLength = 8,
                skipBracketValues = true,
            ),
        )

    fun redact(input: String): RedactionResult {
        if (!config.enabled) return RedactionResult(input, emptyList())
        var output = input
        val applied = linkedSetOf<String>()
        val warnings = mutableListOf<String>()
        for (step in steps) {
            when (step) {
                is Step.Pattern -> {
                    val appliedRule =
                        apply(output, step.regex, step.replacement, step.id, applied, warnings, withholdOnTimeout = true)
                    if (appliedRule.withheld) {
                        return RedactionResult(appliedRule.text ?: "[REDACTED]", applied.toList(), warnings)
                    }
                    output = appliedRule.text ?: output
                }
                is Step.Assignment -> {
                    output =
                        redactAssignments(
                            output,
                            step.keywords,
                            step.minValueLength,
                            step.skipBracketValues,
                            step.id,
                            applied,
                        )
                }
            }
        }
        for ((index, pattern) in config.extraPatterns.withIndex()) {
            val regex =
                try {
                    Regex(pattern)
                } catch (_: Exception) {
                    continue
                }
            output = apply(output, regex, "[REDACTED]", "extra-$index", applied, warnings, withholdOnTimeout = false).text ?: output
        }
        return RedactionResult(output, applied.toList(), warnings)
    }

    /**
     * Replaces the value of `keyword = value` and `"keyword": "value"`.
     *
     * The key may contain the keyword anywhere in a run of at most [MAX_KEY_CHARS] identifier
     * characters after it. A quote is allowed on the key and on the value so JSON assignments are
     * covered without a second, ambiguous regex.
     */
    private fun redactAssignments(
        input: String,
        keywords: List<String>,
        minValueLength: Int,
        skipBracketValues: Boolean,
        id: String,
        applied: MutableSet<String>,
    ): String {
        val upper = input.uppercase()
        val out = StringBuilder(input.length)
        var cursor = 0
        var index = 0
        var changed = false
        while (index < input.length) {
            val keywordLength = keywordAt(upper, index, keywords)
            if (keywordLength == 0) {
                index++
                continue
            }
            val value = assignmentValue(input, index + keywordLength, minValueLength, skipBracketValues)
            if (value == null) {
                index++
                continue
            }
            out.append(input, cursor, value.first)
            out.append("[REDACTED]")
            cursor = value.second
            index = value.second
            changed = true
        }
        if (!changed) return input
        out.append(input, cursor, input.length)
        applied.add(id)
        return out.toString()
    }

    private fun keywordAt(
        upper: String,
        index: Int,
        keywords: List<String>,
    ): Int {
        var found = 0
        for (keyword in keywords) {
            if (keyword.length > found && index + keyword.length <= upper.length && upper.startsWith(keyword, index)) {
                found = keyword.length
            }
        }
        return found
    }

    /** @return value start and end, exclusive, or null when this keyword is not an assignment. */
    private fun assignmentValue(
        input: String,
        afterKeyword: Int,
        minValueLength: Int,
        skipBracketValues: Boolean,
    ): Pair<Int, Int>? {
        var suffix = afterKeyword
        var suffixChars = 0
        while (suffix < input.length && suffixChars < MAX_KEY_CHARS && isKeyChar(input[suffix])) {
            suffix++
            suffixChars++
        }
        if (suffix < input.length && isKeyChar(input[suffix])) return null
        var cursor = suffix
        if (cursor < input.length && input[cursor] == '"') cursor++
        while (cursor < input.length && (input[cursor] == ' ' || input[cursor] == '\t')) cursor++
        if (cursor >= input.length || (input[cursor] != ':' && input[cursor] != '=')) return null
        cursor++
        while (cursor < input.length && (input[cursor] == ' ' || input[cursor] == '\t')) cursor++
        val quoted = cursor < input.length && input[cursor] == '"'
        if (quoted) cursor++
        val valueStart = cursor
        if (quoted) {
            while (cursor < input.length && input[cursor] != '"' && input[cursor] != '\n' && input[cursor] != '\r') {
                cursor++
            }
        } else {
            while (cursor < input.length && !isValueSpace(input[cursor])) cursor++
        }
        if (cursor - valueStart < minValueLength) return null
        if (skipBracketValues && input[valueStart] == '[') return null
        return valueStart to cursor
    }

    private fun isKeyChar(char: Char): Boolean = char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char == '_'

    private fun isValueSpace(char: Char): Boolean = char == ' ' || char == '\t' || char == '\n' || char == '\r'

    private data class Applied(
        val text: String?,
        val withheld: Boolean = false,
    )

    /**
     * Runs one pattern under a matching budget. A user-supplied pattern that blows the budget is
     * skipped. A built-in rule that blows it withholds the whole output: returning the original
     * text would publish whatever that rule did not finish hiding.
     */
    private fun apply(
        input: String,
        regex: Regex,
        replacement: String,
        id: String,
        applied: MutableSet<String>,
        warnings: MutableList<String>,
        withholdOnTimeout: Boolean,
    ): Applied {
        val deadline = System.nanoTime() + patternBudgetNanos
        return try {
            val bounded = BoundedCharSequence(input, deadline)
            if (!regex.containsMatchIn(bounded)) return Applied(null)
            val result = regex.replace(BoundedCharSequence(input, deadline), replacement)
            applied.add(id)
            Applied(result)
        } catch (_: RedactionTimeout) {
            warnings += "redaction-pattern-timeout:$id"
            if (withholdOnTimeout) {
                warnings += "redaction-output-withheld"
                Applied("[REDACTED]", withheld = true)
            } else {
                Applied(null)
            }
        } catch (_: StackOverflowError) {
            // java.util.regex recurses per input character for alternation-under-quantifier, so a
            // long enough line overflows the stack before any deadline can fire. That is an Error
            // rather than an Exception, so it would otherwise escape and kill the whole tool call.
            warnings += "redaction-pattern-overflow:$id"
            if (withholdOnTimeout) {
                warnings += "redaction-output-withheld"
                Applied("[REDACTED]", withheld = true)
            } else {
                Applied(null)
            }
        }
    }

    private companion object {
        /**
         * Generous enough that a legitimate pattern over a large capture never trips it, bounded
         * enough that a catastrophic one cannot hang the tool call. Applied per pattern, so one
         * misbehaving rule can never consume the budget of the built-in secret rules.
         */
        val DEFAULT_PATTERN_BUDGET_NANOS =
            java.util.concurrent.TimeUnit.MILLISECONDS
                .toNanos(500)
    }
}
