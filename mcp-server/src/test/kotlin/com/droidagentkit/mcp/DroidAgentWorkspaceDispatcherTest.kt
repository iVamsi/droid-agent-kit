package com.droidagentkit.mcp

import com.droidagentkit.core.DroidAgentConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class DroidAgentWorkspaceDispatcherTest {
    @Test
    fun `workspace tools require the current project root`() {
        val workspace = Files.createTempDirectory("dak-workspace")
        val templateRoot = createGradleProject(workspace.resolve("template"))
        val template = DroidAgentMcpDispatcher(DroidAgentConfig.default(), templateRoot)
        val dispatcher =
            DroidAgentWorkspaceDispatcher(workspace, template) { root ->
                DroidAgentMcpDispatcher(DroidAgentConfig.default(), root)
            }

        dispatcher.listTools().forEach { tool ->
            val required = tool.inputSchema["required"] as List<*>
            val properties = tool.inputSchema["properties"] as Map<*, *>
            assertTrue("${tool.name} should require rootPath", "rootPath" in required)
            assertTrue("${tool.name} should describe rootPath", properties.containsKey("rootPath"))
        }
    }

    @Test
    fun `workspace dispatches a valid nested Gradle project`() {
        val workspace = Files.createTempDirectory("dak-workspace")
        val templateRoot = createGradleProject(workspace.resolve("template"))
        val appRoot = createGradleProject(workspace.resolve("apps/sample"))
        val createdFor = mutableListOf<Path>()
        val dispatcher =
            DroidAgentWorkspaceDispatcher(
                workspace,
                DroidAgentMcpDispatcher(DroidAgentConfig.default(), templateRoot),
            ) { root ->
                createdFor.add(root)
                DroidAgentMcpDispatcher(DroidAgentConfig.default(), root)
            }

        val result = dispatcher.call("android_project_inspect", mapOf("rootPath" to appRoot.toString()))

        assertTrue(result["status"] in setOf("success", "partial"))
        assertEquals(listOf(appRoot.toRealPath()), createdFor)
    }

    @Test
    fun `workspace blocks missing outside and non Gradle project roots`() {
        val workspace = Files.createTempDirectory("dak-workspace")
        val templateRoot = createGradleProject(workspace.resolve("template"))
        val outside = createGradleProject(Files.createTempDirectory("dak-outside"))
        val notGradle = Files.createDirectories(workspace.resolve("not-gradle"))
        val dispatcher =
            DroidAgentWorkspaceDispatcher(
                workspace,
                DroidAgentMcpDispatcher(DroidAgentConfig.default(), templateRoot),
            ) { root -> DroidAgentMcpDispatcher(DroidAgentConfig.default(), root) }

        val missing = dispatcher.call("android_project_inspect", emptyMap())
        val escaped = dispatcher.call("android_project_inspect", mapOf("rootPath" to outside.toString()))
        val invalid = dispatcher.call("android_project_inspect", mapOf("rootPath" to notGradle.toString()))

        assertEquals("blocked", missing["status"])
        assertEquals(listOf("missing-project-root"), missing["warnings"])
        assertEquals("blocked", escaped["status"])
        assertEquals(listOf("project-root-denied"), escaped["warnings"])
        assertEquals("blocked", invalid["status"])
        assertEquals(listOf("invalid-project-root"), invalid["warnings"])
    }

    @Test
    fun `workspace resolves symlinks before enforcing the trusted boundary`() {
        val workspace = Files.createTempDirectory("dak-workspace")
        val templateRoot = createGradleProject(workspace.resolve("template"))
        val outside = createGradleProject(Files.createTempDirectory("dak-outside"))
        val linked = workspace.resolve("linked-outside")
        Files.createSymbolicLink(linked, outside)
        val dispatcher =
            DroidAgentWorkspaceDispatcher(
                workspace,
                DroidAgentMcpDispatcher(DroidAgentConfig.default(), templateRoot),
            ) { root -> DroidAgentMcpDispatcher(DroidAgentConfig.default(), root) }

        val result = dispatcher.call("android_project_inspect", mapOf("rootPath" to linked.toString()))

        assertEquals("blocked", result["status"])
        assertEquals(listOf("project-root-denied"), result["warnings"])
    }

    @Test
    fun `workspace advertises no resources or prompts so Android Studio stays tools-only`() {
        val workspace = Files.createTempDirectory("dak-workspace")
        val templateRoot = createGradleProject(workspace.resolve("template"))
        val dispatcher =
            DroidAgentWorkspaceDispatcher(workspace, DroidAgentMcpDispatcher(DroidAgentConfig.default(), templateRoot)) { root ->
                DroidAgentMcpDispatcher(DroidAgentConfig.default(), root)
            }

        assertTrue(dispatcher.resourceRegistry().list().isEmpty())
        assertTrue(dispatcher.promptRegistry().list(dispatcher.exposedToolNames()).isEmpty())
    }

    private fun createGradleProject(root: Path): Path {
        Files.createDirectories(root)
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"sample\"\n")
        return root
    }
}
