package com.droidagentkit.mcp

import java.nio.file.Files
import java.nio.file.Path

/**
 * A concrete, addressable MCP resource served by DroidAgentKit.
 *
 * Resources are project-scoped, read-only, and resolved through registered providers so that
 * path confinement and capability gating stay enforced. The [reader] returns the raw text body;
 * callers wrap it in the MCP `resources/read` envelope.
 */
data class McpResource(
    val uri: String,
    val name: String,
    val description: String,
    val mimeType: String,
    val reader: () -> String,
)

/**
 * A URI-template MCP resource. Variable segments are declared in [variables] so the protocol can
 * advertise the template shape; [reader] receives the resolved variable bindings and returns
 * null when the binding cannot be resolved (e.g. an unknown artifact id), which the registry
 * surfaces as a not-found result.
 */
data class McpResourceTemplate(
    val uriTemplate: String,
    val name: String,
    val description: String,
    val mimeType: String,
    val variables: List<String>,
    val reader: (Map<String, String>) -> String?,
)

/** Outcome of resolving a resource read request. */
sealed interface ResourceReadResult {
    data class Found(
        val uri: String,
        val mimeType: String,
        val text: String,
    ) : ResourceReadResult

    data class NotFound(
        val uri: String,
    ) : ResourceReadResult
}

/**
 * Registry of concrete resources and URI templates. Providers register resources at construction;
 * the registry never resolves paths itself — it delegates to the registered readers, keeping
 * path-confinement logic in the dispatcher/providers that already enforce it.
 */
class McpResourceRegistry {
    private val resources = linkedMapOf<String, McpResource>()
    private val templates = mutableListOf<McpResourceTemplate>()

    fun register(resource: McpResource) {
        resources[resource.uri] = resource
    }

    fun registerTemplate(template: McpResourceTemplate) {
        templates.add(template)
    }

    fun list(): List<McpResource> = resources.values.toList()

    fun listTemplates(): List<McpResourceTemplate> = templates.toList()

    fun read(uri: String): ResourceReadResult {
        resources[uri]?.let {
            return ResourceReadResult.Found(uri = uri, mimeType = it.mimeType, text = it.reader())
        }
        for (template in templates) {
            val bindings = matchTemplate(template.uriTemplate, uri)
            if (bindings != null) {
                val text = template.reader(bindings)
                if (text != null) {
                    return ResourceReadResult.Found(uri = uri, mimeType = template.mimeType, text = text)
                } else {
                    return ResourceReadResult.NotFound(uri)
                }
            }
        }
        return ResourceReadResult.NotFound(uri)
    }

    private fun matchTemplate(
        template: String,
        uri: String,
    ): Map<String, String>? {
        val variableNames = template.split('/').filter { it.startsWith("{") && it.endsWith("}") }.map { it.drop(1).dropLast(1) }
        if (variableNames.isEmpty()) return null
        val regex = buildRegex(template)
        val match = regex.matchEntire(uri) ?: return null
        return variableNames.zip(match.groupValues.drop(1)).toMap()
    }

    private fun buildRegex(template: String): Regex {
        val escaped =
            template.split('/').joinToString("/") { segment ->
                if (segment.startsWith("{") && segment.endsWith("}")) "([^/]+)" else Regex.escape(segment)
            }
        return Regex("^$escaped$")
    }
}

/** Helpers for the standard project-scoped resources DroidAgentKit advertises. */
object McpProjectResources {
    const val INSPECT_URI = "droidagent://project/inspect"
    const val AGENTS_DOC_URI = "droidagent://project/agents-doc"
    const val READINESS_URI = "droidagent://project/readiness"
    const val ARTIFACT_TEMPLATE = "droidagent://artifacts/{id}"
    const val GOLDEN_TEMPLATE = "droidagent://goldens/{case}"

    fun registerProject(
        registry: McpResourceRegistry,
        projectRoot: Path,
        inspect: () -> String,
        readiness: () -> String,
    ) {
        registry.register(
            McpResource(
                uri = INSPECT_URI,
                name = "project-inspect",
                description = "Static Gradle/manifest inspection report for the bound Android project root.",
                mimeType = "text/markdown",
                reader = inspect,
            ),
        )
        registry.register(
            McpResource(
                uri = AGENTS_DOC_URI,
                name = "project-agents-doc",
                description = "AGENTS.md instructions for the bound project, if present.",
                mimeType = "text/markdown",
                reader = { readAgentsDoc(projectRoot) },
            ),
        )
        registry.register(
            McpResource(
                uri = READINESS_URI,
                name = "project-readiness",
                description = "Agent-readiness audit report for the bound project.",
                mimeType = "text/markdown",
                reader = readiness,
            ),
        )
    }

    private fun readAgentsDoc(projectRoot: Path): String {
        val file = projectRoot.resolve("AGENTS.md")
        return if (Files.exists(file)) Files.readString(file) else "# AGENTS.md not found at project root."
    }
}
