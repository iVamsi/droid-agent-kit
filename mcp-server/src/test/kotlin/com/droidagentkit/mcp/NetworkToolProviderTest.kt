package com.droidagentkit.mcp

import com.droidagentkit.core.Capability
import com.droidagentkit.core.DroidAgentConfig
import com.droidagentkit.core.ToolGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class NetworkToolProviderTest {
    @Test
    fun `network tools are listed only when the group is exposed`() {
        val root = Files.createTempDirectory("dak-net-list")
        val dispatcher = dispatcher(root, config(root, mitmPath = "/bin/true"))

        val names = dispatcher.listTools().map { it.name }
        assertTrue(names.contains("android_network_capture_start"))
        assertTrue(names.contains("android_network_capture_query"))
    }

    @Test
    fun `network tools are hidden when the group is not exposed`() {
        val root = Files.createTempDirectory("dak-net-hidden")
        val dispatcher = DroidAgentMcpDispatcher(DroidAgentConfig.default(), root)

        assertTrue(dispatcher.listTools().map { it.name }.none { it.startsWith("android_network_") })
    }

    @Test
    fun `start is blocked without network_interception capability`() {
        val root = Files.createTempDirectory("dak-net-nocap")
        val base = DroidAgentConfig.default()
        val config = base.copy(safety = base.safety.copy(adbPath = fakeAdb(root, proxyLog(root), prior = "")))
        val dispatcher = dispatcher(root, config)

        val result = dispatcher.call("android_network_capture_start", startArgs(root, confirm = true))

        assertEquals("blocked", result["status"])
        assertTrue((result["warnings"] as List<*>).contains("capability-not-enabled"))
    }

    @Test
    fun `start is blocked without confirmDestructive`() {
        val root = Files.createTempDirectory("dak-net-noconfirm")
        val dispatcher = dispatcher(root, config(root, mitmPath = "/bin/true"))

        val result = dispatcher.call("android_network_capture_start", startArgs(root, confirm = false))

        assertEquals("blocked", result["status"])
        assertTrue((result["warnings"] as List<*>).contains("destructive-confirmation-required"))
    }

    @Test
    fun `start is blocked when mitmproxy is not configured`() {
        val root = Files.createTempDirectory("dak-net-nomitm")
        val dispatcher = dispatcher(root, config(root, mitmPath = ""))

        val result = dispatcher.call("android_network_capture_start", startArgs(root, confirm = true))

        assertEquals("blocked", result["status"])
        assertTrue((result["warnings"] as List<*>).contains("mitmproxy-not-configured"))
    }

    @Test
    fun `start is blocked for non-emulator devices`() {
        val root = Files.createTempDirectory("dak-net-nophy")
        val config = config(root, mitmPath = "/bin/true", qemu = "0")
        val dispatcher = dispatcher(root, config)

        val result = dispatcher.call("android_network_capture_start", startArgs(root, confirm = true))

        assertEquals("blocked", result["status"])
        assertTrue((result["warnings"] as List<*>).contains("not-emulator"))
    }

    @Test
    fun `start restores the prior proxy after a clean exit`() {
        val root = Files.createTempDirectory("dak-net-success")
        val log = proxyLog(root)
        val mitm = fakeMitmdump(root, mode = "exit0")
        val dispatcher = dispatcher(root, config(root, mitmPath = mitm.toString(), prior = "10.0.2.2:8888", proxyLog = log))

        val result = dispatcher.call("android_network_capture_start", startArgs(root, confirm = true))
        assertEquals("success", result["status"])
        val jobId = result["jobId"] as String
        waitForTerminal(dispatcher, jobId)

        val lines = Files.readAllLines(log)
        assertTrue(lines.any { it.startsWith("10.0.2.2:") })
        assertEquals("10.0.2.2:8888", lines.last())
    }

    @Test
    fun `start restores the proxy after a forced process failure`() {
        val root = Files.createTempDirectory("dak-net-crash")
        val log = proxyLog(root)
        val mitm = fakeMitmdump(root, mode = "exit1")
        val dispatcher = dispatcher(root, config(root, mitmPath = mitm.toString(), prior = "", proxyLog = log))

        val result = dispatcher.call("android_network_capture_start", startArgs(root, confirm = true))
        val jobId = result["jobId"] as String
        waitForTerminal(dispatcher, jobId)

        val lines = Files.readAllLines(log)
        assertEquals(":none", lines.last())
    }

    @Test
    fun `start restores the proxy after cancel`() {
        val root = Files.createTempDirectory("dak-net-cancel")
        val log = proxyLog(root)
        val mitm = fakeMitmdump(root, mode = "sleep")
        val dispatcher = dispatcher(root, config(root, mitmPath = mitm.toString(), prior = "", proxyLog = log))

        val result = dispatcher.call("android_network_capture_start", startArgs(root, confirm = true))
        val jobId = result["jobId"] as String
        waitForProxyInstall(log)
        val cancel = dispatcher.call("android_job_cancel", mapOf("rootPath" to root.toString(), "jobId" to jobId))
        assertEquals("cancelled", cancel["status"])
        waitForTerminal(dispatcher, jobId)

        val lines = Files.readAllLines(log)
        assertTrue(lines.any { it.startsWith("10.0.2.2:") })
        assertEquals(":none", lines.last())
    }

    @Test
    fun `start restores the proxy after timeout`() {
        val root = Files.createTempDirectory("dak-net-timeout")
        val log = proxyLog(root)
        val mitm = fakeMitmdump(root, mode = "sleep")
        val dispatcher = dispatcher(root, config(root, mitmPath = mitm.toString(), prior = "", proxyLog = log))

        val result =
            dispatcher.call(
                "android_network_capture_start",
                startArgs(root, confirm = true, duration = 1),
            )
        val jobId = result["jobId"] as String
        waitForTerminal(dispatcher, jobId)

        val lines = Files.readAllLines(log)
        assertEquals(":none", lines.last())
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `query parses a finalized HAR and redacts sensitive headers`() {
        val root = Files.createTempDirectory("dak-net-query")
        val dispatcher = dispatcher(root, config(root, mitmPath = "/bin/true"))
        val har = harInArtifactDir(root, "cap.har")
        Files.writeString(har, sampleHar())
        val result =
            dispatcher.call(
                "android_network_capture_query",
                mapOf("rootPath" to root.toString(), "capturePath" to har.toString(), "includeBodies" to false),
            )

        assertEquals("success", result["status"])
        @Suppress("UNCHECKED_CAST")
        val flows = result["flows"] as List<Map<String, Any>>
        assertEquals(1, flows.size)
        val req = flows[0]["requestHeaders"] as Map<String, Any>
        assertEquals("[REDACTED]", req["Authorization"])
        assertEquals("[REDACTED]", (flows[0]["responseHeaders"] as Map<String, Any>)["Set-Cookie"])
        assertFalse(result["pinningSuspected"] as Boolean)
        val artifacts = result["artifacts"] as List<*>
        assertTrue(artifacts.any { (it as Map<*, *>)["type"] == "network_capture" })
    }

    @Test
    fun `query blocks path escape`() {
        val root = Files.createTempDirectory("dak-net-escape")
        val dispatcher = dispatcher(root, config(root, mitmPath = "/bin/true"))
        val outside = Files.createTempDirectory("dak-outside").resolve("cap.har")
        Files.writeString(outside, sampleHar())

        val result =
            dispatcher.call(
                "android_network_capture_query",
                mapOf("rootPath" to root.toString(), "capturePath" to outside.toString()),
            )

        assertEquals("blocked", result["status"])
        assertTrue((result["warnings"] as List<*>).contains("path-escape"))
    }

    @Test
    fun `query reports capture-not-finalized when the HAR is missing`() {
        val root = Files.createTempDirectory("dak-net-missing")
        val dispatcher = dispatcher(root, config(root, mitmPath = "/bin/true"))
        val missing = root.resolve("never-written.har")

        val result =
            dispatcher.call(
                "android_network_capture_query",
                mapOf("rootPath" to root.toString(), "capturePath" to missing.toString()),
            )

        assertEquals("partial", result["status"])
        assertTrue((result["warnings"] as List<*>).contains("capture-not-finalized"))
    }

    @Test
    fun `query flags pinning suspected when all flows lack a response`() {
        val root = Files.createTempDirectory("dak-net-pinning")
        val dispatcher = dispatcher(root, config(root, mitmPath = "/bin/true"))
        val har = harInArtifactDir(root, "pinned.har")
        Files.writeString(har, pinnedHar())
        val result =
            dispatcher.call(
                "android_network_capture_query",
                mapOf("rootPath" to root.toString(), "capturePath" to har.toString()),
            )

        assertEquals("unsupported", result["status"])
        assertTrue(result["pinningSuspected"] as Boolean)
        assertTrue((result["warnings"] as List<*>).contains("pinning-or-tls-suspected"))
    }

    private fun startArgs(
        root: Path,
        confirm: Boolean,
        duration: Long = 30,
    ): Map<String, Any> =
        mapOf(
            "rootPath" to root.toString(),
            "deviceSerial" to "emulator-5554",
            "packageName" to "com.example.app",
            "durationSeconds" to duration,
            "confirmDestructive" to confirm,
        )

    private fun config(
        root: Path,
        mitmPath: String,
        qemu: String = "1",
        prior: String = "",
        proxyLog: Path? = null,
    ): DroidAgentConfig {
        val base = DroidAgentConfig.default()
        return base.copy(
            safety =
                base.safety.copy(
                    allowCapabilities = setOf(Capability.NETWORK_INTERCEPTION),
                    adbPath = fakeAdb(root, proxyLog ?: proxyLog(root), qemu = qemu, prior = prior),
                    mitmProxyPath = mitmPath,
                ),
        )
    }

    private fun dispatcher(
        root: Path,
        config: DroidAgentConfig,
    ): DroidAgentMcpDispatcher =
        DroidAgentMcpDispatcher(
            config,
            root,
            exposedGroups = setOf(ToolGroup.CORE, ToolGroup.NETWORK_EXPERIMENTAL, ToolGroup.DEVICE_READ),
        )

    private fun waitForTerminal(
        dispatcher: DroidAgentMcpDispatcher,
        jobId: String,
    ) {
        val deadline = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < deadline) {
            val snap = dispatcher.call("android_job_status", mapOf("rootPath" to "/", "jobId" to jobId))
            val state = snap["jobState"] as String
            if (state != "running" && state != "pending") return
            Thread.sleep(150)
        }
    }

    private fun waitForProxyInstall(log: Path) {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            val lines = runCatching { Files.readAllLines(log) }.getOrDefault(emptyList())
            if (lines.any { it.startsWith("10.0.2.2:") }) return
            Thread.sleep(50)
        }
    }

    private fun proxyLog(root: Path): Path = root.resolve("proxy.log")

    private fun harInArtifactDir(
        root: Path,
        name: String,
    ): Path {
        val dir = root.resolve("build/droidagentkit")
        Files.createDirectories(dir)
        return dir.resolve(name)
    }

    private fun fakeAdb(
        root: Path,
        proxyLog: Path,
        qemu: String = "1",
        prior: String = "",
    ): String {
        // These fakes are POSIX shell scripts, and that is load-bearing rather than incidental:
        // the `shell` branch re-evaluates joined argv the way a real device's /system/bin/sh does,
        // which is what lets them exercise shell-injection regressions at all. Reimplementing that
        // in batch would weaken the coverage it exists to provide, so on Windows these skip.
        org.junit.Assume.assumeTrue(
            "requires a POSIX shell for the fake adb/emulator scripts",
            !System.getProperty("os.name").startsWith("Windows"),
        )
        val script = root.resolve("fake-net-adb.sh")
        Files.writeString(
            script,
            """
            #!/bin/bash
            verb="${'$'}3"
            case "${'$'}verb" in
              shell)
                sub="${'$'}4"
                case "${'$'}sub" in
                  getprop) echo "$qemu" ;;
                  run-as) echo "uid=10234" ;;
                  settings)
                    op="${'$'}5"; key="${'$'}7"; val="${'$'}8"
                    if [ "${'$'}op" = "put" ] && [ "${'$'}key" = "http_proxy" ]; then
                      echo "${'$'}val" >> "$proxyLog"
                      echo "ok"
                    elif [ "${'$'}op" = "get" ] && [ "${'$'}key" = "http_proxy" ]; then
                      printf '%s' '$prior'
                    else
                      echo "ok"
                    fi
                    ;;
                  *) echo "shell-ok" ;;
                esac
                ;;
              *) echo "unknown" ;;
            esac
            exit 0
            """.trimIndent(),
        )
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"))
        return script.toString()
    }

    private fun fakeMitmdump(
        root: Path,
        mode: String,
    ): Path {
        val script = root.resolve("fake-mitmdump-$mode.sh")
        Files.writeString(
            script,
            """
            #!/bin/bash
            case "$mode" in
              exit0) exit 0 ;;
              exit1) exit 1 ;;
              sleep) sleep 30 ;;
              *) exit 0 ;;
            esac
            """.trimIndent(),
        )
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"))
        return script
    }

    private fun sampleHar(): String =
        """{"log":{"entries":[{"request":{"method":"GET","url":"https://api.example.com/v1/users","headers":[{"name":"Authorization","value":"Bearer secret"}]},"response":{"status":200,"headers":[{"name":"Set-Cookie","value":"sid=secret"}],"content":{"mimeType":"application/json","text":"{}"}}}]}}"""

    private fun pinnedHar(): String =
        """{"log":{"entries":[{"request":{"method":"GET","url":"https://pinned.example.com/a","headers":[]},"response":{"status":0,"headers":[],"content":{}}}]}}"""
}
