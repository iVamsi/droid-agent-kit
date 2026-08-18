package com.droidagentkit.mcp

import com.droidagentkit.core.Capability
import com.droidagentkit.core.DroidAgentConfig
import com.droidagentkit.core.ToolGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

class PerfettoToolProviderTest {
    private fun config(
        root: java.nio.file.Path,
        capabilities: Set<Capability> = setOf(Capability.SENSITIVE_DIAGNOSTICS),
    ): DroidAgentConfig {
        val base = DroidAgentConfig.default()
        return base.copy(
            safety =
                base.safety.copy(
                    allowCapabilities = capabilities,
                    adbPath = fakeAdb(root),
                    traceProcessorPath = fakeShell(root),
                ),
        )
    }

    private fun dispatcher(
        root: java.nio.file.Path,
        config: DroidAgentConfig,
    ): DroidAgentMcpDispatcher = DroidAgentMcpDispatcher(config, root, exposedGroups = setOf(ToolGroup.CORE, ToolGroup.PERFETTO))

    @Test
    fun `perfetto tools are listed only when the group is exposed`() {
        val root = Files.createTempDirectory("dak-perfetto-list")
        val dispatcher = dispatcher(root, config(root))

        val names = dispatcher.listTools().map { it.name }
        assertTrue(names.contains("android_perfetto_capture"))
        assertTrue(names.contains("android_perfetto_analyze"))
    }

    @Test
    fun `perfetto tools are hidden when the group is not exposed`() {
        val root = Files.createTempDirectory("dak-perfetto-hidden")
        val dispatcher = DroidAgentMcpDispatcher(DroidAgentConfig.default(), root)

        val names = dispatcher.listTools().map { it.name }
        assertTrue(!names.contains("android_perfetto_capture"))
    }

    @Test
    fun `capture is blocked without sensitive diagnostics capability`() {
        val root = Files.createTempDirectory("dak-perfetto-cap")
        val dispatcher = dispatcher(root, config(root, capabilities = emptySet()))

        val result = dispatcher.call("android_perfetto_capture", mapOf("rootPath" to root.toString(), "deviceSerial" to "emulator-5554"))

        assertEquals("blocked", result["status"])
        assertTrue((result["warnings"] as List<*>).contains("capability-not-enabled"))
    }

    @Test
    fun `capture is blocked without a device serial`() {
        val root = Files.createTempDirectory("dak-perfetto-serial")
        val dispatcher = dispatcher(root, config(root))

        val result = dispatcher.call("android_perfetto_capture", mapOf("rootPath" to root.toString()))

        assertEquals("blocked", result["status"])
        assertTrue((result["warnings"] as List<*>).contains("missing-device-serial"))
    }

    @Test
    fun `capture pulls a trace and registers a sensitive artifact`() {
        val root = Files.createTempDirectory("dak-perfetto-capture")
        val dispatcher = dispatcher(root, config(root))

        val result =
            dispatcher.call(
                "android_perfetto_capture",
                mapOf("rootPath" to root.toString(), "deviceSerial" to "emulator-5554", "durationSeconds" to 2),
            )

        assertEquals("success", result["status"])
        val artifacts = result["artifacts"] as List<*>
        assertTrue(artifacts.any { (it as Map<*, *>)["type"] == "perfetto_trace" })
        assertTrue(artifacts.any { (it as Map<*, *>)["sensitivity"] == "sensitive" })
    }

    @Test
    fun `analyze is blocked when trace processor is not configured`() {
        val root = Files.createTempDirectory("dak-perfetto-no-shell")
        val base = DroidAgentConfig.default()
        val config =
            base.copy(
                safety = base.safety.copy(allowCapabilities = setOf(Capability.SENSITIVE_DIAGNOSTICS), traceProcessorPath = ""),
            )
        val dispatcher = dispatcher(root, config)
        val trace = root.resolve("trace.perfetto-trace")
        Files.write(trace, byteArrayOf(0x0a))

        val result = dispatcher.call("android_perfetto_analyze", mapOf("rootPath" to root.toString(), "tracePath" to trace.toString()))

        assertEquals("blocked", result["status"])
        assertTrue((result["warnings"] as List<*>).contains("trace-processor-not-configured"))
    }

    @Test
    fun `analyze runs queries and returns a structured report`() {
        val root = Files.createTempDirectory("dak-perfetto-analyze")
        val dispatcher = dispatcher(root, config(root))
        val trace = root.resolve("trace.perfetto-trace")
        Files.write(trace, byteArrayOf(0x0a))

        val result =
            dispatcher.call(
                "android_perfetto_analyze",
                mapOf("rootPath" to root.toString(), "tracePath" to trace.toString()),
            )

        assertEquals("success", result["status"])
        @Suppress("UNCHECKED_CAST")
        val analyses = result["analyses"] as List<Map<String, Any>>
        assertTrue(analyses.any { it["analysis"] == "cpu_utilization" })
        assertEquals("high", result["confidence"])
    }

    private fun fakeAdb(root: java.nio.file.Path): String {
        val script = root.resolve("fake-perfetto-adb.sh")
        Files.writeString(
            script,
            """
            #!/bin/bash
            # argv: ${'$'}1=-s ${'$'}2=serial ${'$'}3=verb ...
            case "${'$'}3" in
              push) echo "push ok" ;;
              shell)
                case "${'$'}4" in
                  perfetto) echo "perfetto ok" ;;
                  rm) echo "cleanup ok" ;;
                  *) echo "shell ok" ;;
                esac
                ;;
              pull) printf 'fake-trace' > "${'$'}5"; echo "pull ok" ;;
              *) echo "unknown adb" ;;
            esac
            exit 0
            """.trimIndent(),
        )
        assumePosixFilesystem()
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"))
        return script.toString()
    }

    private fun fakeShell(root: java.nio.file.Path): String {
        val script = root.resolve("fake-trace-processor.sh")
        Files.writeString(
            script,
            """
            #!/bin/bash
            # argv: ${'$'}1=trace ${'$'}2=--json ${'$'}3=--query-file ${'$'}4=sqlfile
            echo '{"columns":["process_name","cpu_seconds"],"rows":[["com.example",1.5]]}'
            exit 0
            """.trimIndent(),
        )
        assumePosixFilesystem()
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"))
        return script.toString()
    }
}
