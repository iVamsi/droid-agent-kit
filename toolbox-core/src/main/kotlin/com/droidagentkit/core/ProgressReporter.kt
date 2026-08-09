package com.droidagentkit.core

/**
 * Reports incremental progress for work that takes long enough that silence looks like a hang.
 *
 * Android tool calls are the worst case for this: a cold Gradle build or an emulator boot can run
 * for minutes with nothing on the wire, and a host has no way to tell that apart from a wedged
 * server. Implementations must be safe to call from the thread doing the work.
 */
fun interface ProgressReporter {
    /**
     * @param progress work done so far, in the same unit as [total]; null when unknown.
     * @param total expected total, or null when the work has no bounded size.
     * @param message short human-readable description of the current step.
     */
    fun report(
        progress: Double?,
        total: Double?,
        message: String,
    )

    companion object {
        /** For call sites with nobody listening; discards everything. */
        val NONE = ProgressReporter { _, _, _ -> }
    }
}
