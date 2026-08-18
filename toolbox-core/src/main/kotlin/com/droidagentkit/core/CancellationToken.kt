package com.droidagentkit.core

/**
 * One-way switch a caller flips to abandon work already in flight.
 *
 * MCP hosts send `notifications/cancelled` when a user stops a request, and an Android tool call is
 * often a multi-minute Gradle build or emulator boot. Without it that process runs to completion
 * with nobody waiting for the answer, holding the device and the build lock.
 *
 * A hook registered after cancellation runs immediately instead of being dropped. The process a
 * hook kills starts after the token is threaded through, so a cancel can land in that window.
 */
class CancellationToken {
    private val lock = Any()
    private var cancelled = false
    private val hooks = mutableListOf<() -> Unit>()

    val isCancelled: Boolean get() = synchronized(lock) { cancelled }

    fun cancel() {
        val toRun =
            synchronized(lock) {
                if (cancelled) return
                cancelled = true
                val snapshot = hooks.toList()
                hooks.clear()
                snapshot
            }
        toRun.forEach { runHook(it) }
    }

    fun onCancel(hook: () -> Unit) {
        val runNow =
            synchronized(lock) {
                if (cancelled) {
                    true
                } else {
                    hooks.add(hook)
                    false
                }
            }
        if (runNow) runHook(hook)
    }

    companion object {
        /** For call sites that cannot be cancelled; never transitions. */
        val NONE = CancellationToken()

        /**
         * A hook whose process has already exited throws, and that must not prevent the remaining
         * hooks from running: cancellation is best-effort cleanup, not a transaction.
         */
        private fun runHook(hook: () -> Unit) {
            runCatching { hook() }
        }
    }
}
