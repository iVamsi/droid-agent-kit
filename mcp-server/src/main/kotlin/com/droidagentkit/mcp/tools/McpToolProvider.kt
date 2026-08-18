package com.droidagentkit.mcp.tools

import com.droidagentkit.core.ToolGroup
import com.droidagentkit.mcp.McpTool

interface McpToolProvider {
    val group: ToolGroup

    fun listTools(): List<McpTool>

    fun supports(name: String): Boolean

    fun call(
        name: String,
        arguments: Map<String, Any?>,
    ): Map<String, Any>
}

class CoreToolProvider(
    private val tools: List<McpTool>,
    private val names: Set<String>,
    private val route: (String, Map<String, Any?>) -> Map<String, Any>,
) : McpToolProvider {
    override val group: ToolGroup = ToolGroup.CORE

    override fun listTools(): List<McpTool> = tools

    override fun supports(name: String): Boolean = name in names

    override fun call(
        name: String,
        arguments: Map<String, Any?>,
    ): Map<String, Any> = route(name, arguments)
}

class DuplicateToolException(
    toolName: String,
    providers: List<String>,
) : IllegalStateException("Tool '$toolName' is registered by multiple providers: ${providers.joinToString(", ")}")

class ToolProviderRegistry(
    private val providers: List<McpToolProvider>,
    private val exposedGroups: Set<ToolGroup> = setOf(ToolGroup.CORE),
) {
    init {
        val byName = providers.flatMap { p -> p.listTools().map { it.name to p } }
        val duplicates = byName.groupingBy { it.first }.eachCount().filter { it.value > 1 }
        if (duplicates.isNotEmpty()) {
            val names = duplicates.keys.first()
            val owners = providers.filter { p -> p.listTools().any { it.name == names } }.map { it::class.simpleName ?: "provider" }
            throw DuplicateToolException(names, owners)
        }
    }

    fun listTools(): List<McpTool> =
        providers
            .filter { it.group in exposedGroups }
            .flatMap { it.listTools() }

    fun call(
        name: String,
        arguments: Map<String, Any?>,
    ): Map<String, Any> {
        val provider =
            providers.firstOrNull { it.supports(name) }
                ?: return mapOf(
                    "schemaVersion" to "1.0",
                    "status" to "unsupported",
                    "summary" to "Unknown MCP tool: $name",
                    "artifacts" to emptyList<Any>(),
                    "redactionsApplied" to emptyList<Any>(),
                    "warnings" to listOf("unknown-tool"),
                )
        // listTools() filters by exposedGroups, but MCP's tools/call is a free-form JSON-RPC
        // method: a client isn't required to have seen a tool via tools/list before calling it
        // by name. Without this check, opt-in groups would only be a listing-time convenience,
        // not an actual access-control boundary.
        if (provider.group !in exposedGroups) {
            return mapOf(
                "schemaVersion" to "1.0",
                "status" to "blocked",
                "summary" to "Tool '$name' requires the '${provider.group.name.lowercase()}' group, which is not exposed by this server.",
                "artifacts" to emptyList<Any>(),
                "redactionsApplied" to emptyList<Any>(),
                "warnings" to listOf("group-not-enabled"),
            )
        }
        return provider.call(name, arguments)
    }

    fun supports(name: String): Boolean = providers.any { it.supports(name) }
}
