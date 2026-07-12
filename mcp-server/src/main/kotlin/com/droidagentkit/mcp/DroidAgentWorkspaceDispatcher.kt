package com.droidagentkit.mcp

import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists

class DroidAgentWorkspaceDispatcher(
    trustedProjectsRoot: Path,
    private val templateDispatcher: DroidAgentMcpDispatcher,
    private val dispatcherFactory: (Path) -> DroidAgentMcpDispatcher,
) : McpDispatcher {
    private val realTrustedProjectsRoot = trustedProjectsRoot.toAbsolutePath().normalize().toRealPath()
    private val dispatchers = ConcurrentHashMap<Path, DroidAgentMcpDispatcher>()

    override val instructions: String =
        "For every DroidAgentKit tool call, pass rootPath as the absolute root of the current Android Studio project. " +
            "Only Android Gradle projects under $realTrustedProjectsRoot are allowed."

    override fun listTools(): List<McpTool> = templateDispatcher.listTools().map(::requireProjectRoot)

    override fun call(
        name: String,
        arguments: Map<String, Any?>,
    ): Map<String, Any> {
        val requested =
            arguments["rootPath"]?.toString()?.takeIf { it.isNotBlank() }
                ?: return blocked("rootPath is required for a projects workspace.", "missing-project-root")
        val root =
            try {
                Path.of(requested).toAbsolutePath().normalize()
            } catch (_: InvalidPathException) {
                return blocked("Requested project root is not a valid path.", "invalid-project-root")
            }
        if (!root.exists() || !Files.isDirectory(root)) {
            return blocked("Requested project root '$root' does not exist or is not a directory.", "invalid-project-root")
        }
        val realRoot =
            runCatching { root.toRealPath() }.getOrElse {
                return blocked("Requested project root '$root' could not be resolved.", "invalid-project-root")
            }
        if (!realRoot.startsWith(realTrustedProjectsRoot)) {
            return blocked(
                "Requested project root '$realRoot' is outside trusted projects root '$realTrustedProjectsRoot'.",
                "project-root-denied",
            )
        }
        if (!isGradleProject(realRoot)) {
            return blocked("Requested root '$realRoot' is not a Gradle project.", "invalid-project-root")
        }

        val dispatcher =
            runCatching { dispatchers.computeIfAbsent(realRoot, dispatcherFactory) }.getOrElse {
                return blocked("Could not load project '$realRoot': ${it.message ?: "unknown error"}.", "project-load-failed")
            }
        return dispatcher.call(name, arguments + ("rootPath" to realRoot.toString()))
    }

    private fun requireProjectRoot(tool: McpTool): McpTool {
        val properties =
            (tool.inputSchema["properties"] as? Map<*, *>)
                ?.entries
                ?.associate { it.key.toString() to it.value as Any }
                ?.toMutableMap()
                ?: mutableMapOf()
        properties["rootPath"] =
            mapOf(
                "type" to "string",
                "description" to
                    "Required absolute root of the current Android Studio project. " +
                    "It must be under $realTrustedProjectsRoot.",
            )
        val required =
            ((tool.inputSchema["required"] as? List<*>)?.map(Any?::toString).orEmpty() + "rootPath")
                .distinct()
        return tool.copy(
            description = "${tool.description} Use the current Android Studio project root.",
            inputSchema = tool.inputSchema + mapOf("properties" to properties, "required" to required),
        )
    }

    private fun isGradleProject(root: Path): Boolean =
        root.resolve("settings.gradle.kts").exists() || root.resolve("settings.gradle").exists()

    private fun blocked(
        summary: String,
        warning: String,
    ): Map<String, Any> =
        mapOf(
            "schemaVersion" to "1.0",
            "status" to "blocked",
            "summary" to summary,
            "artifacts" to emptyList<Any>(),
            "redactionsApplied" to emptyList<String>(),
            "warnings" to listOf(warning),
        )
}
