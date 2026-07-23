package com.droidagentkit.network

import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path

/**
 * Pre-flight checks + plan assembly for an emulator-only network capture. Enforces:
 * - emulator target (reject physical devices),
 * - debuggable app (run-as succeeds),
 * - a configured mitmproxy executable (no runtime download).
 *
 * The host loopback is exposed to the emulator as `10.0.2.2`, so the device proxy is always
 * `10.0.2.2:<port>` regardless of the host's own IP. The HAR is written to a project-confined
 * scratch directory supplied by the caller.
 */
object NetworkCapturePlanner {
    fun isEmulator(
        exec: NetworkCommandExecutor,
        adbPath: String,
        serial: String,
    ): Boolean {
        val out =
            runCatching { exec.run(listOf(adbPath, "-s", serial, "shell", "getprop", "ro.kernel.qemu")) }
                .getOrDefault(ByteArray(0))
        val out2 =
            runCatching { exec.run(listOf(adbPath, "-s", serial, "shell", "getprop", "ro.boot.qemu")) }
                .getOrDefault(ByteArray(0))
        return String(out).trim() == "1" || String(out2).trim() == "1"
    }

    fun isDebuggable(
        exec: NetworkCommandExecutor,
        adbPath: String,
        serial: String,
        packageName: String,
    ): Boolean {
        val out =
            runCatching { exec.run(listOf(adbPath, "-s", serial, "shell", "run-as", packageName, "id")) }
                .getOrDefault(ByteArray(0))
        return String(out).contains("uid=")
    }

    fun pickPort(): Int = ServerSocket(0).use { it.localPort }

    fun plan(
        exec: NetworkCommandExecutor,
        adbPath: String,
        serial: String,
        packageName: String,
        mitmProxyPath: String,
        harDir: Path,
        harName: String,
    ): NetworkCapturePlan {
        if (mitmProxyPath.isBlank()) {
            throw NetworkCaptureException(
                "mitmproxy-not-configured",
                "mitmproxy executable not configured (set safety.mitmProxyPath).",
            )
        }
        if (!isEmulator(
                exec,
                adbPath,
                serial,
            )
        ) {
            throw NetworkCaptureException("not-emulator", "Network interception is emulator-only; '$serial' is not an emulator.")
        }
        if (!isDebuggable(
                exec,
                adbPath,
                serial,
                packageName,
            )
        ) {
            throw NetworkCaptureException("not-debuggable", "Package is not debuggable or run-as failed: $packageName.")
        }
        Files.createDirectories(harDir)
        val port = pickPort()
        val harPath = harDir.resolve(harName)
        val command = MitmProxyCommandFactory.build(mitmProxyPath, "127.0.0.1", port, harPath)
        return NetworkCapturePlan(
            deviceSerial = serial,
            packageName = packageName,
            listenHost = "127.0.0.1",
            listenPort = port,
            deviceProxy = "10.0.2.2:$port",
            harPath = harPath.toString(),
            command = command,
        )
    }
}
