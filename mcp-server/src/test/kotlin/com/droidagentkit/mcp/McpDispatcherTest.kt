package com.droidagentkit.mcp

import com.droidagentkit.core.DroidAgentConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class McpDispatcherTest {
    @Test
    fun `dispatcher lists expected android tools`() {
        val dispatcher = DroidAgentMcpDispatcher(DroidAgentConfig.default())

        val tools = dispatcher.listTools().map { it.name }

        assertEquals(
            listOf(
                "android_project_inspect",
                "android_gradle_run",
                "android_devices_list",
                "android_app_install",
                "android_app_launch",
                "android_logcat_capture",
                "android_screen_snapshot",
                "android_report_bundle",
            ),
            tools,
        )
    }

    @Test
    fun `gradle run blocks denied task`() {
        val root = Files.createTempDirectory("dak-mcp")
        val dispatcher = DroidAgentMcpDispatcher(DroidAgentConfig.default())

        val result = dispatcher.call("android_gradle_run", mapOf("rootPath" to root.toString(), "task" to "clean"))

        assertEquals("blocked", result["status"])
        assertTrue(result["summary"].toString().contains("not allowlisted"))
    }

    @Test
    fun `project inspect returns useful partial result`() {
        val root = Files.createTempDirectory("dak-mcp-inspect")
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"McpDemo\"\ninclude(\":app\")")
        Files.createDirectories(root.resolve("app"))
        Files.writeString(root.resolve("app/build.gradle.kts"), "plugins { id(\"com.android.application\") }")
        val dispatcher = DroidAgentMcpDispatcher(DroidAgentConfig.default())

        val result = dispatcher.call("android_project_inspect", mapOf("rootPath" to root.toString()))

        assertEquals("success", result["status"])
        assertTrue(result["summary"].toString().contains("McpDemo"))
    }

    @Test
    fun `snapshot is blocked when device serial is missing`() {
        val root = Files.createTempDirectory("dak-snapshot")
        val dispatcher = DroidAgentMcpDispatcher(DroidAgentConfig.default())

        val result = dispatcher.call("android_screen_snapshot", mapOf("rootPath" to root.toString()))

        assertEquals("blocked", result["status"])
        assertTrue(result["summary"].toString().contains("deviceSerial"))
    }

    @Test
    fun `each tool exposes an input schema with type object and properties`() {
        val dispatcher = DroidAgentMcpDispatcher(DroidAgentConfig.default())

        val tools = dispatcher.listTools()

        assertEquals(8, tools.size)
        tools.forEach { tool ->
            assertEquals("tool ${tool.name} missing type:object", "object", tool.inputSchema["type"])
            assertTrue(
                "tool ${tool.name} missing properties",
                tool.inputSchema.containsKey("properties"),
            )
        }
    }

    @Test
    fun `gradle run tool schema marks task as required`() {
        val dispatcher = DroidAgentMcpDispatcher(DroidAgentConfig.default())

        val gradleTool = dispatcher.listTools().first { it.name == "android_gradle_run" }

        @Suppress("UNCHECKED_CAST")
        val required = gradleTool.inputSchema["required"] as List<*>
        assertTrue(required.contains("task"))
    }
}
