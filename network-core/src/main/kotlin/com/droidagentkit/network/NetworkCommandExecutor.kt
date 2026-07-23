package com.droidagentkit.network

/**
 * Hermetic seam for the network-interception tranche. The MCP provider supplies an implementation
 * that delegates to [com.droidagentkit.device.DeviceToolContext] (and ultimately ProcessRunner);
 * tests supply a fake that returns canned adb output. All commands are fully-formed adb argument
 * lists — no shell string escaping, no arbitrary host execution.
 */
interface NetworkCommandExecutor {
    /**
     * Run an adb command (already including the `adb` binary path and `-s serial`) and return its
     * stdout bytes. [binary] hints whether the caller intends to treat output as raw bytes.
     */
    fun run(
        command: List<String>,
        binary: Boolean = false,
    ): ByteArray
}

/** Outcome of saving the device's prior global HTTP proxy before installing our own. */
data class ProxySnapshot(
    /** Prior `settings global http_proxy` value, or null when the device had no proxy set. */
    val priorProxy: String?,
    /** The proxy address we installed on the device (e.g. `10.0.2.2:8080`). */
    val installedProxy: String,
)

/** A single redacted flow summary extracted from a mitmproxy HAR dump. */
data class FlowSummary(
    val method: String,
    val scheme: String,
    val host: String,
    val path: String,
    val status: Int,
    val contentType: String,
    val requestHeaders: Map<String, String>,
    val responseHeaders: Map<String, String>,
    val requestBody: String?,
    val responseBody: String?,
    val error: String?,
)

/** Result of querying a captured HAR. */
data class CaptureQueryResult(
    val flows: List<FlowSummary>,
    val warnings: List<String>,
    val redactionsApplied: List<String>,
    val pinningSuspected: Boolean,
)

/** A fully-resolved capture plan ready to hand to a managed job. */
data class NetworkCapturePlan(
    val deviceSerial: String,
    val packageName: String,
    val listenHost: String,
    val listenPort: Int,
    val deviceProxy: String,
    val harPath: String,
    val command: List<String>,
)

class NetworkCaptureException(
    val code: String,
    message: String,
) : RuntimeException(message)
