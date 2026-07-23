package com.droidagentkit.network

import java.nio.file.Path

/**
 * Builds the mitmproxy (`mitmdump`) command for an emulator-only capture. The proxy listens on a
 * host loopback address and writes a HAR archive on graceful shutdown via the `hardump` option.
 * No runtime download: the executable path is resolved from configuration by the caller.
 */
object MitmProxyCommandFactory {
    fun build(
        mitmProxyPath: String,
        listenHost: String,
        listenPort: Int,
        harPath: Path,
    ): List<String> =
        listOf(
            mitmProxyPath,
            "--listen-host",
            listenHost,
            "--listen-port",
            listenPort.toString(),
            "--set",
            "hardump=$harPath",
            "--set",
            "ssl_insecure=true",
            "--quiet",
        )
}
