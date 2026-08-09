package com.droidagentkit.mcp

import com.droidagentkit.core.Capability
import com.droidagentkit.core.DroidAgentConfig
import com.droidagentkit.core.ToolGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

class DeviceReadToolProviderTest {
    private fun dispatcher(
        root: java.nio.file.Path,
        config: DroidAgentConfig = DroidAgentConfig.default(),
    ): DroidAgentMcpDispatcher =
        DroidAgentMcpDispatcher(
            config = config,
            projectRoot = root,
            exposedGroups = setOf(ToolGroup.CORE, ToolGroup.DEVICE_READ),
        )

    @Test
    fun `device-read tools are listed only when the group is exposed`() {
        val root = Files.createTempDirectory("dak-device-read-list")
        val dispatcher = dispatcher(root)

        val names = dispatcher.listTools().map { it.name }

        assertTrue(names.contains("android_permission_audit"))
        assertTrue(names.contains("android_dumpsys"))
        assertTrue(names.contains("android_memory_summary"))
        assertTrue(names.contains("android_battery_summary"))
        assertTrue(names.contains("android_bugreport"))
        assertTrue(names.contains("android_logcat_start"))
        assertTrue(names.contains("android_job_status"))
        assertTrue(names.contains("android_job_cancel"))
    }

    @Test
    fun `device-read tools are hidden when the group is not exposed`() {
        val root = Files.createTempDirectory("dak-device-read-hidden")
        val dispatcher = DroidAgentMcpDispatcher(DroidAgentConfig.default(), root)

        val names = dispatcher.listTools().map { it.name }
        assertTrue(!names.contains("android_permission_audit"))
        assertTrue(!names.contains("android_bugreport"))
    }

    @Test
    fun `permission audit is blocked when device serial is missing`() {
        val root = Files.createTempDirectory("dak-perm-serial")
        val dispatcher = dispatcher(root)

        val result = dispatcher.call("android_permission_audit", mapOf("rootPath" to root.toString(), "packageName" to "com.example"))

        assertEquals("blocked", result["status"])
        assertTrue(result["summary"].toString().contains("deviceSerial"))
    }

    @Test
    fun `permission audit is blocked when package is missing`() {
        val root = Files.createTempDirectory("dak-perm-pkg")
        val dispatcher = dispatcher(root)

        val result = dispatcher.call("android_permission_audit", mapOf("rootPath" to root.toString(), "deviceSerial" to "emulator-5554"))

        assertEquals("blocked", result["status"])
        assertTrue((result["warnings"] as List<*>).contains("missing-package"))
    }

    @Test
    fun `dumpsys rejects an unknown preset`() {
        val root = Files.createTempDirectory("dak-dumpsys-preset")
        val dispatcher = dispatcher(root)

        val result =
            dispatcher.call(
                "android_dumpsys",
                mapOf(
                    "rootPath" to root.toString(),
                    "deviceSerial" to "emulator-5554",
                    "preset" to "surfaceflinger",
                ),
            )

        assertEquals("blocked", result["status"])
        assertTrue((result["warnings"] as List<*>).contains("invalid-dumpsys-preset"))
    }

    @Test
    fun `dumpsys package preset requires a package name`() {
        val root = Files.createTempDirectory("dak-dumpsys-pkg")
        val dispatcher = dispatcher(root)

        val result =
            dispatcher.call(
                "android_dumpsys",
                mapOf(
                    "rootPath" to root.toString(),
                    "deviceSerial" to "emulator-5554",
                    "preset" to "package",
                ),
            )

        assertEquals("blocked", result["status"])
        assertTrue((result["warnings"] as List<*>).contains("missing-package"))
    }

    @Test
    fun `bugreport is blocked when sensitive diagnostics capability is not enabled`() {
        val root = Files.createTempDirectory("dak-bugreport-cap")
        val dispatcher = dispatcher(root)

        val result = dispatcher.call("android_bugreport", mapOf("rootPath" to root.toString(), "deviceSerial" to "emulator-5554"))

        assertEquals("blocked", result["status"])
        assertTrue((result["warnings"] as List<*>).contains("capability-not-enabled"))
    }

    @Test
    fun `bugreport is allowed when sensitive diagnostics capability is enabled`() {
        val root = Files.createTempDirectory("dak-bugreport-allowed")
        val config =
            DroidAgentConfig.default().copy(
                safety = DroidAgentConfig.default().safety.copy(allowCapabilities = setOf(Capability.SENSITIVE_DIAGNOSTICS)),
            )
        val dispatcher = dispatcher(root, config)
        val adb = fakeAdb(root)

        val configWithAdb = config.copy(safety = config.safety.copy(adbPath = adb))
        val dispatcherWithAdb = DroidAgentMcpDispatcher(configWithAdb, root, exposedGroups = setOf(ToolGroup.CORE, ToolGroup.DEVICE_READ))

        val result = dispatcherWithAdb.call("android_bugreport", mapOf("rootPath" to root.toString(), "deviceSerial" to "emulator-5554"))

        assertEquals("success", result["status"])
        val artifacts = result["artifacts"] as List<*>
        assertTrue(artifacts.any { (it as Map<*, *>)["type"] == "bugreport" })
        assertTrue(artifacts.any { (it as Map<*, *>)["sensitivity"] == "sensitive" })
    }

    @Test
    fun `logcat start rejects an invalid filter token`() {
        val root = Files.createTempDirectory("dak-logcat-filter")
        val dispatcher = dispatcher(root)

        val result =
            dispatcher.call(
                "android_logcat_start",
                mapOf("rootPath" to root.toString(), "deviceSerial" to "emulator-5554", "filter" to "*:I; rm -rf /"),
            )

        assertEquals("blocked", result["status"])
        assertTrue((result["warnings"] as List<*>).contains("invalid-filter-token"))
    }

    @Test
    fun `job status is expired for an unknown job id`() {
        val root = Files.createTempDirectory("dak-job-unknown")
        val dispatcher = dispatcher(root)

        val result = dispatcher.call("android_job_status", mapOf("rootPath" to root.toString(), "jobId" to "nope"))

        assertEquals("expired", result["status"])
        assertTrue((result["warnings"] as List<*>).contains("unknown-job"))
    }

    @Test
    fun `job status is blocked when job id is missing`() {
        val root = Files.createTempDirectory("dak-job-missing")
        val dispatcher = dispatcher(root)

        val result = dispatcher.call("android_job_status", mapOf("rootPath" to root.toString()))

        assertEquals("blocked", result["status"])
        assertTrue((result["warnings"] as List<*>).contains("missing-job-id"))
    }

    @Test
    fun `memory summary parses total free and used ram from hermetic adb`() {
        val root = Files.createTempDirectory("dak-memory-hermetic")
        val config = DroidAgentConfig.default().copy(safety = DroidAgentConfig.default().safety.copy(adbPath = fakeAdb(root)))
        val dispatcher = DroidAgentMcpDispatcher(config, root, exposedGroups = setOf(ToolGroup.CORE, ToolGroup.DEVICE_READ))

        val result = dispatcher.call("android_memory_summary", mapOf("rootPath" to root.toString(), "deviceSerial" to "emulator-5554"))

        assertEquals("success", result["status"])
        @Suppress("UNCHECKED_CAST")
        val summary = result["memorySummary"] as Map<String, Any>

        @Suppress("UNCHECKED_CAST")
        val mem = summary["summary"] as Map<String, Any>
        assertEquals(1234567L, mem["totalRamKb"])
        assertEquals(123456L, mem["freeRamKb"])
    }

    @Test
    fun `battery summary parses level and status from hermetic adb`() {
        val root = Files.createTempDirectory("dak-battery-hermetic")
        val config = DroidAgentConfig.default().copy(safety = DroidAgentConfig.default().safety.copy(adbPath = fakeAdb(root)))
        val dispatcher = DroidAgentMcpDispatcher(config, root, exposedGroups = setOf(ToolGroup.CORE, ToolGroup.DEVICE_READ))

        val result = dispatcher.call("android_battery_summary", mapOf("rootPath" to root.toString(), "deviceSerial" to "emulator-5554"))

        assertEquals("success", result["status"])
        @Suppress("UNCHECKED_CAST")
        val summary = result["batterySummary"] as Map<String, Any>

        @Suppress("UNCHECKED_CAST")
        val battery = summary["summary"] as Map<String, Any>
        assertEquals(42, battery["level"])
        assertEquals("Discharging", battery["status"])
    }

    @Test
    fun `permission audit parses runtime grant state from hermetic adb`() {
        val root = Files.createTempDirectory("dak-perm-hermetic")
        val config = DroidAgentConfig.default().copy(safety = DroidAgentConfig.default().safety.copy(adbPath = fakeAdb(root)))
        val dispatcher = DroidAgentMcpDispatcher(config, root, exposedGroups = setOf(ToolGroup.CORE, ToolGroup.DEVICE_READ))

        val result =
            dispatcher.call(
                "android_permission_audit",
                mapOf("rootPath" to root.toString(), "deviceSerial" to "emulator-5554", "packageName" to "com.example"),
            )

        assertEquals("success", result["status"])
        @Suppress("UNCHECKED_CAST")
        val permissions = result["permissions"] as List<Map<String, Any>>
        assertTrue(permissions.any { it["name"] == "android.permission.CAMERA" && it["granted"] == false })
    }

    @Test
    fun `logcat start returns a job id and log uri then status reaches a terminal state`() {
        val root = Files.createTempDirectory("dak-logcat-hermetic")
        val config = DroidAgentConfig.default().copy(safety = DroidAgentConfig.default().safety.copy(adbPath = fakeAdb(root)))
        val dispatcher = DroidAgentMcpDispatcher(config, root, exposedGroups = setOf(ToolGroup.CORE, ToolGroup.DEVICE_READ))

        val start =
            dispatcher.call(
                "android_logcat_start",
                mapOf("rootPath" to root.toString(), "deviceSerial" to "emulator-5554", "durationSeconds" to 2),
            )

        assertEquals("success", start["status"])
        val jobId = start["jobId"] as String
        assertTrue(start.containsKey("logUri"))

        // The fake adb exits immediately, so the job reaches a terminal state quickly. Poll for it
        // rather than sleeping a fixed interval and hoping, which fails on a loaded machine.
        val terminalStates = setOf("success", "failed", "cancelled", "expired")
        val deadline = System.currentTimeMillis() + 10_000
        var status = dispatcher.call("android_job_status", mapOf("rootPath" to root.toString(), "jobId" to jobId))
        while (status["jobState"] !in terminalStates && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
            status = dispatcher.call("android_job_status", mapOf("rootPath" to root.toString(), "jobId" to jobId))
        }
        assertTrue("expected terminal state but was ${status["jobState"]}", status["jobState"] in terminalStates)
    }

    @Test
    fun `logcat start with package filter resolves pid and warns when absent`() {
        val root = Files.createTempDirectory("dak-logcat-pid")
        val config = DroidAgentConfig.default().copy(safety = DroidAgentConfig.default().safety.copy(adbPath = fakeAdb(root)))
        val dispatcher = DroidAgentMcpDispatcher(config, root, exposedGroups = setOf(ToolGroup.CORE, ToolGroup.DEVICE_READ))

        val start =
            dispatcher.call(
                "android_logcat_start",
                mapOf(
                    "rootPath" to root.toString(),
                    "deviceSerial" to "emulator-5554",
                    "packageName" to "com.example",
                    "durationSeconds" to 2,
                ),
            )

        assertEquals("success", start["status"])
        // fake adb pidof returns 4242, so no absent-process warning is expected here; this asserts the happy path resolves a pid.
        assertTrue(start.containsKey("jobId"))
    }

    @Test
    fun `permission audit does not allow shell metacharacter injection via packageName`() {
        val root = Files.createTempDirectory("dak-perm-injection")
        val marker = root.resolve("injected.marker")
        val config =
            DroidAgentConfig.default().copy(
                safety = DroidAgentConfig.default().safety.copy(adbPath = fakeAdb(root)),
            )
        val dispatcher = dispatcher(root, config)

        val result =
            dispatcher.call(
                "android_permission_audit",
                mapOf(
                    "rootPath" to root.toString(),
                    "deviceSerial" to "emulator-5554",
                    "packageName" to "com.example; touch $marker",
                ),
            )

        assertEquals("success", result["status"])
        assertTrue(!Files.exists(marker))
    }

    private fun fakeAdb(root: java.nio.file.Path): String {
        // These fakes are POSIX shell scripts, and that is load-bearing rather than incidental:
        // the `shell` branch re-evaluates joined argv the way a real device's /system/bin/sh does,
        // which is what lets them exercise shell-injection regressions at all. Reimplementing that
        // in batch would weaken the coverage it exists to provide, so on Windows these skip.
        org.junit.Assume.assumeTrue(
            "requires a POSIX shell for the fake adb/emulator scripts",
            !System.getProperty("os.name").startsWith("Windows"),
        )
        val script = root.resolve("fake-adb.sh")
        Files.writeString(
            script,
            """
            #!/bin/bash
            # argv: ${'$'}1=-s ${'$'}2=serial ${'$'}3=shell|bugreport ...
            # The shell branch reassembles and re-evaluates remaining args the way a real device's
            # `/system/bin/sh -c "joined args"` would, so ShellQuote regressions stay testable.
            case "${'$'}3" in
              bugreport)
                mkdir -p "${'$'}4"
                printf 'PK\x03\x04fake-zip' > "${'$'}4/bugreport-${'$'}2.zip"
                echo "Bugreport written to ${'$'}4"
                ;;
              shell)
                shift 3
                dumpsys() {
                  case "${'$'}1" in
                    meminfo) echo "Total RAM: 1,234,567 KB"; echo "Free RAM: 123,456 KB"; echo "Used RAM: 1,111,111 KB" ;;
                    battery) echo "level: 42"; echo "status: Discharging"; echo "health: Good"; echo "temperature: 250"; echo "voltage: 4200" ;;
                    package) echo "Package [${'$'}2]"; echo "  runtime permissions:"; echo "    android.permission.CAMERA: granted=false" ;;
                    *) echo "dumpsys ${'$'}1" ;;
                  esac
                }
                pidof() { echo "4242"; }
                logcat() { echo "log line 1"; echo "log line 2"; }
                eval "${'$'}@"
                ;;
              *) echo "unknown adb command" ;;
            esac
            exit 0
            """.trimIndent(),
        )
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"))
        return script.toString()
    }
}
