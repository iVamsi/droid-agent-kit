package com.droidagentkit.core

/**
 * One-way switch a caller flips to abandon work already in flight.
 *
 * MCP hosts send `notifications/cancelled` when a user stops a request, and an Android tool call
 * is frequently a multi-minute Gradle build or emulator boot. Without this the process kept
 * running to completion with nobody waiting for the answer, holding the device and the build lock.
 *
 * A hook registered after cancellation runs immediately rather than being dropped: the process a
 * hook is meant to kill is started *after* the token is threaded through, so the cancel can land
 * in that window, and losing it there would leak exactly what the token exists to clean up.
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
         * hooks from running -- cancellation is best-effort cleanup, not a transaction.
         */
        private fun runHook(hook: () -> Unit) {
            runCatching { hook() }
        }
    }
}
