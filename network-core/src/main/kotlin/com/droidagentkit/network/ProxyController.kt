package com.droidagentkit.network

/**
 * Manages the device's global HTTP proxy (`settings global http_proxy`) around an interception
 * session. The prior value is saved before we install our own proxy and restored on every
 * terminal path (success, cancel, timeout, crash) by the managed-job cleanup hook.
 *
 * Android uses the sentinel `:none` to mean "no proxy"; an empty value is treated the same way.
 * All commands are built here and executed through [NetworkCommandExecutor] so the controller is
 * hermetically testable.
 */
class ProxyController(
    private val adbPath: String,
) {
    /** Read the current global proxy. Returns null when no proxy is set. */
    fun readProxy(
        exec: NetworkCommandExecutor,
        serial: String,
    ): String? {
        val out =
            runCatching {
                exec.run(listOf(adbPath, "-s", serial, "shell", "settings", "get", "global", "http_proxy"))
            }.getOrDefault(ByteArray(0))
        return String(out).trim().takeIf { it.isNotBlank() && it != ":none" }
    }

    /** Save the prior proxy and install [host]:[port] as the device global proxy. */
    fun installProxy(
        exec: NetworkCommandExecutor,
        serial: String,
        host: String,
        port: Int,
    ): ProxySnapshot {
        val prior = readProxy(exec, serial)
        val proxy = "$host:$port"
        exec.run(listOf(adbPath, "-s", serial, "shell", "settings", "put", "global", "http_proxy", proxy))
        return ProxySnapshot(priorProxy = prior, installedProxy = proxy)
    }

    /** Restore the prior proxy, or clear it if there was none. */
    fun restoreProxy(
        exec: NetworkCommandExecutor,
        serial: String,
        priorProxy: String?,
    ) {
        if (priorProxy.isNullOrBlank()) {
            exec.run(listOf(adbPath, "-s", serial, "shell", "settings", "put", "global", "http_proxy", ":none"))
        } else {
            exec.run(listOf(adbPath, "-s", serial, "shell", "settings", "put", "global", "http_proxy", priorProxy))
        }
    }
}
