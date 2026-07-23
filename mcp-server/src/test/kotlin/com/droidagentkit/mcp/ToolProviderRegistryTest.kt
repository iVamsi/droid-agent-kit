package com.droidagentkit.mcp

import com.droidagentkit.core.ToolGroup
import com.droidagentkit.mcp.tools.DuplicateToolException
import com.droidagentkit.mcp.tools.McpToolProvider
import com.droidagentkit.mcp.tools.ToolProviderRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolProviderRegistryTest {
    private fun tool(name: String): McpTool = McpTool(name, "title", "desc", mapOf("type" to "object"), mapOf("type" to "object"))

    private fun provider(
        group: ToolGroup,
        tools: List<String>,
    ): McpToolProvider =
        object : McpToolProvider {
            override val group: ToolGroup = group

            override fun listTools(): List<McpTool> = tools.map(::tool)

            override fun supports(name: String): Boolean = name in tools

            override fun call(
                name: String,
                arguments: Map<String, Any?>,
            ): Map<String, Any> = mapOf("name" to name)
        }

    private fun registry(
        groups: Set<ToolGroup>,
        vararg providers: McpToolProvider,
    ): ToolProviderRegistry = ToolProviderRegistry(providers = providers.toList(), exposedGroups = groups)

    @Test
    fun `lists tools from exposed groups only`() {
        val reg = registry(setOf(ToolGroup.CORE), provider(ToolGroup.CORE, listOf("a")))
        assertEquals(listOf("a"), reg.listTools().map { it.name })
    }

    @Test
    fun `filters out tools from non exposed groups`() {
        val reg =
            registry(
                setOf(ToolGroup.CORE),
                provider(ToolGroup.CORE, listOf("a")),
                provider(ToolGroup.DEVICE_CONTROL, listOf("b")),
            )
        assertEquals(listOf("a"), reg.listTools().map { it.name })
    }

    @Test
    fun `combines tools when multiple groups exposed`() {
        val reg =
            registry(
                setOf(ToolGroup.CORE, ToolGroup.DEVICE_READ),
                provider(ToolGroup.CORE, listOf("a")),
                provider(ToolGroup.DEVICE_READ, listOf("b")),
            )
        assertEquals(listOf("a", "b"), reg.listTools().map { it.name })
    }

    @Test(expected = DuplicateToolException::class)
    fun `rejects duplicate tool names across providers`() {
        registry(
            setOf(ToolGroup.CORE, ToolGroup.DEVICE_READ),
            provider(ToolGroup.CORE, listOf("dup")),
            provider(ToolGroup.DEVICE_READ, listOf("dup")),
        )
    }

    @Test
    fun `unknown tool call returns unsupported`() {
        val reg = registry(setOf(ToolGroup.CORE), provider(ToolGroup.CORE, listOf("a")))
        val result = reg.call("missing", emptyMap())
        assertEquals("unsupported", result["status"])
        assertTrue((result["warnings"] as List<*>).contains("unknown-tool"))
    }
}
