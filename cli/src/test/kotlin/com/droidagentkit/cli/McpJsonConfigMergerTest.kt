package com.droidagentkit.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class McpJsonConfigMergerTest {
    @Test
    fun `merge creates the top-level key and server entry when starting from an empty document`() {
        val serverConfig = buildJsonObject { put("command", "/bin/droidagent") }

        val result = McpJsonConfigMerger.merge("", "mcpServers", "droidagentkit", serverConfig)

        val servers = Json.parseToJsonElement(result).jsonObject["mcpServers"]!!.jsonObject
        assertEquals("/bin/droidagent", servers["droidagentkit"]!!.jsonObject["command"]!!.jsonPrimitive.content)
    }

    @Test
    fun `merge preserves a sibling server entry under the same top-level key`() {
        val existing = """{"mcpServers":{"other-tool":{"command":"/bin/other"}}}"""
        val serverConfig = buildJsonObject { put("command", "/bin/droidagent") }

        val result = McpJsonConfigMerger.merge(existing, "mcpServers", "droidagentkit", serverConfig)

        val servers = Json.parseToJsonElement(result).jsonObject["mcpServers"]!!.jsonObject
        assertEquals(2, servers.size)
        assertEquals("/bin/other", servers["other-tool"]!!.jsonObject["command"]!!.jsonPrimitive.content)
    }

    @Test
    fun `merge replaces a pre-existing droidagentkit entry instead of duplicating it`() {
        val existing = """{"mcpServers":{"droidagentkit":{"command":"/old/path"}}}"""
        val serverConfig = buildJsonObject { put("command", "/new/path") }

        val result = McpJsonConfigMerger.merge(existing, "mcpServers", "droidagentkit", serverConfig)

        val servers = Json.parseToJsonElement(result).jsonObject["mcpServers"]!!.jsonObject
        assertEquals(1, servers.size)
        assertEquals("/new/path", servers["droidagentkit"]!!.jsonObject["command"]!!.jsonPrimitive.content)
    }

    @Test
    fun `merge preserves unrelated top-level keys`() {
        val existing = """{"context_servers":{},"theme":"dark","some_other_setting":42}"""
        val serverConfig = buildJsonObject { put("command", "/bin/droidagent") }

        val result = McpJsonConfigMerger.merge(existing, "context_servers", "droidagentkit", serverConfig)

        val root = Json.parseToJsonElement(result).jsonObject
        assertEquals("dark", root["theme"]!!.jsonPrimitive.content)
        assertEquals("42", root["some_other_setting"]!!.jsonPrimitive.content)
    }

    @Test
    fun `merge throws on invalid existing json`() {
        val serverConfig = buildJsonObject { put("command", "/bin/droidagent") }

        assertThrows(Exception::class.java) {
            McpJsonConfigMerger.merge("not valid json at all {{{", "mcpServers", "droidagentkit", serverConfig)
        }
    }
}
