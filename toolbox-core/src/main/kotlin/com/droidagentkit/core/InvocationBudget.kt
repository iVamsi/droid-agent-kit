package com.droidagentkit.core

import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Caps how much a single server session can spend, independent of whether each individual call was
 * authorized.
 *
 * The capability system answers "may this agent do this at all?" but says nothing about volume. A
 * prompt-injected agent holding a legitimately granted capability can still clear app data in a
 * loop, or fill the disk with bugreports, and every one of those calls is individually allowed. The
 * budget bounds the blast radius without needing to predict the abuse.
 *
 * The clock is injected because rate limits are otherwise untestable without sleeping, and tests
 * that sleep are flaky.
 */
class InvocationBudget(
    private val limits: BudgetLimits = BudgetLimits(),
    private val clock: Clock = Clock.systemUTC(),
) {
    private val lock = Any()
    private val destructiveCalls = ArrayDeque<Instant>()
    private var artifactBytesWritten = 0L

    /**
     * Records one destructive invocation, or explains why it is over budget.
     *
     * Callers must treat a [BudgetDecision.Exceeded] as a denial: the call is *not* recorded, so
     * refusing and retrying later works as expected.
     */
    fun recordDestructive(operationId: String): BudgetDecision =
        synchronized(lock) {
            val now = clock.instant()
            val windowStart = now.minus(DESTRUCTIVE_WINDOW)
            while (destructiveCalls.isNotEmpty() && destructiveCalls.first() <= windowStart) {
                destructiveCalls.removeFirst()
            }
            if (destructiveCalls.size >= limits.maxDestructivePerMinute) {
                return BudgetDecision.Exceeded(
                    "destructive-budget-exceeded",
                    "'$operationId' would be destructive invocation ${destructiveCalls.size + 1} within " +
                        "${DESTRUCTIVE_WINDOW.toSeconds()}s; the limit is ${limits.maxDestructivePerMinute}. " +
                        "Raise safety.maxDestructivePerMinute in ~/.droidagentkit/policy.yaml if this is intended.",
                )
            }
            destructiveCalls.addLast(now)
            BudgetDecision.Allowed
        }

    /** Records artifact bytes; refuses once the session total would exceed the cap. */
    fun recordArtifactBytes(bytes: Long): BudgetDecision =
        synchronized(lock) {
            if (artifactBytesWritten + bytes > limits.maxArtifactBytesPerSession) {
                return BudgetDecision.Exceeded(
                    "artifact-budget-exceeded",
                    "writing $bytes more bytes would pass this session's artifact cap of " +
                        "${limits.maxArtifactBytesPerSession} bytes ($artifactBytesWritten already written). " +
                        "Raise safety.maxArtifactBytesPerSession in ~/.droidagentkit/policy.yaml if this is intended.",
                )
            }
            artifactBytesWritten += bytes
            BudgetDecision.Allowed
        }

    fun artifactBytes(): Long = synchronized(lock) { artifactBytesWritten }

    private companion object {
        val DESTRUCTIVE_WINDOW: Duration = Duration.ofMinutes(1)
    }
}

data class BudgetLimits(
    val maxDestructivePerMinute: Int = 6,
    val maxArtifactBytesPerSession: Long = 1L shl 30,
)

sealed interface BudgetDecision {
    data object Allowed : BudgetDecision

    data class Exceeded(
        val code: String,
        val reason: String,
    ) : BudgetDecision
}
