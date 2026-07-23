package com.droidagentkit.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class NetworkCapturePlannerTest {
    private val adb = "/usr/bin/adb"

    private fun fake(responses: Map<String, ByteArray>): NetworkCommandExecutor =
        object : NetworkCommandExecutor {
            override fun run(
                command: List<String>,
                binary: Boolean,
            ): ByteArray {
                val key = command.drop(3).joinToString(" ")
                return responses[key] ?: ByteArray(0)
            }
        }

    private fun emulatorResponses(): MutableMap<String, ByteArray> =
        mutableMapOf(
            "shell getprop ro.kernel.qemu" to "1".toByteArray(),
            "shell run-as com.example.app id" to "uid=10234".toByteArray(),
            "shell settings get global http_proxy" to ByteArray(0),
        )

    @Test
    fun `plan rejects missing mitmproxy executable`() {
        val exec = fake(emulatorResponses())
        val tmp = Files.createTempDirectory("dak-net-plan")
        val ex =
            runCatching {
                NetworkCapturePlanner.plan(exec, adb, "emulator-5554", "com.example.app", "", tmp, "cap.har")
            }.exceptionOrNull()
        assertTrue(ex is NetworkCaptureException)
        assertEquals("mitmproxy-not-configured", (ex as NetworkCaptureException).code)
    }

    @Test
    fun `plan rejects non-emulator devices`() {
        val responses = emulatorResponses()
        responses["shell getprop ro.kernel.qemu"] = "0".toByteArray()
        responses["shell getprop ro.boot.qemu"] = "".toByteArray()
        val exec = fake(responses)
        val tmp = Files.createTempDirectory("dak-net-plan")
        val ex =
            runCatching {
                NetworkCapturePlanner.plan(exec, adb, "emulator-5554", "com.example.app", "/usr/local/bin/mitmdump", tmp, "cap.har")
            }.exceptionOrNull()
        assertTrue(ex is NetworkCaptureException)
        assertEquals("not-emulator", (ex as NetworkCaptureException).code)
    }

    @Test
    fun `plan rejects non-debuggable packages`() {
        val responses = emulatorResponses()
        responses["shell run-as com.example.app id"] = "Permission denied".toByteArray()
        val exec = fake(responses)
        val tmp = Files.createTempDirectory("dak-net-plan")
        val ex =
            runCatching {
                NetworkCapturePlanner.plan(exec, adb, "emulator-5554", "com.example.app", "/usr/local/bin/mitmdump", tmp, "cap.har")
            }.exceptionOrNull()
        assertTrue(ex is NetworkCaptureException)
        assertEquals("not-debuggable", (ex as NetworkCaptureException).code)
    }

    @Test
    fun `plan assembles a capture plan with device proxy and har path`() {
        val exec = fake(emulatorResponses())
        val tmp: Path = Files.createTempDirectory("dak-net-plan")
        val plan = NetworkCapturePlanner.plan(exec, adb, "emulator-5554", "com.example.app", "/usr/local/bin/mitmdump", tmp, "cap.har")
        assertEquals("emulator-5554", plan.deviceSerial)
        assertEquals("com.example.app", plan.packageName)
        assertEquals("127.0.0.1", plan.listenHost)
        assertTrue(plan.listenPort > 0)
        assertTrue(plan.deviceProxy.startsWith("10.0.2.2:"))
        assertTrue(plan.harPath.endsWith("cap.har"))
        assertEquals("/usr/local/bin/mitmdump", plan.command.first())
    }
}
