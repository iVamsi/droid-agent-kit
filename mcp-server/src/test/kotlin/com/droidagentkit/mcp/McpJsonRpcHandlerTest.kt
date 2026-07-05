package com.droidagentkit.mcp

import com.droidagentkit.core.DroidAgentConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class McpJsonRpcHandlerTest {
    @Test
    fun `initialize echoes requested protocol version and returns server info`() {
        val handler = McpJsonRpcHandler(DroidAgentMcpDispatcher(DroidAgentConfig.default()))

        val response =
            handler.handle(
                """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}""",
            )

        assertTrue(response != null)
        assertTrue(response!!.contains("\"protocolVersion\":\"2025-03-26\""))
        assertTrue(response.contains("\"name\":\"droidagentkit\""))
        assertTrue(response.contains("\"version\":\"0.1.0-alpha\""))
        assertTrue(response.contains("\"id\":1"))
    }

    @Test
    fun `initialize without protocolVersion falls back to default`() {
        val handler = McpJsonRpcHandler(DroidAgentMcpDispatcher(DroidAgentConfig.default()))

        val response = handler.handle("""{"jsonrpc":"2.0","id":2,"method":"initialize","params":{}}""")

        assertTrue(response!!.contains("\"protocolVersion\":\"2024-11-05\""))
    }

    @Test
    fun `notifications initialized returns no response`() {
        val handler = McpJsonRpcHandler(DroidAgentMcpDispatcher(DroidAgentConfig.default()))

        val response = handler.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")

        assertNull(response)
    }

    @Test
    fun `tools list matches dispatcher listTools`() {
        val dispatcher = DroidAgentMcpDispatcher(DroidAgentConfig.default())
        val handler = McpJsonRpcHandler(dispatcher)

        val response = handler.handle("""{"jsonrpc":"2.0","id":3,"method":"tools/list"}""")

        assertTrue(response!!.contains("\"android_project_inspect\""))
        assertTrue(response.contains("\"android_build_performance\""))
    }

    @Test
    fun `tools call success returns isError false and embeds tool result`() {
        val root = Files.createTempDirectory("dak-mcp-rpc")
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"RpcDemo\"\ninclude(\":app\")")
        Files.createDirectories(root.resolve("app"))
        Files.writeString(root.resolve("app/build.gradle.kts"), "plugins { id(\"com.android.application\") }")
        val handler = McpJsonRpcHandler(DroidAgentMcpDispatcher(DroidAgentConfig.default()))

        val response =
            handler.handle(
                """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"android_project_inspect","arguments":{"rootPath":"${root.toString().replace(
                    "\\",
                    "\\\\",
                )}"}}}""",
            )

        assertTrue(response!!.contains("\"isError\":false"))
        assertTrue(response.contains("RpcDemo"))
    }

    @Test
    fun `tools call for unsupported tool returns isError true`() {
        val handler = McpJsonRpcHandler(DroidAgentMcpDispatcher(DroidAgentConfig.default()))

        val response =
            handler.handle("""{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"not_a_real_tool","arguments":{}}}""")

        assertTrue(response!!.contains("\"isError\":true"))
    }

    @Test
    fun `malformed json returns parse error with null id`() {
        val handler = McpJsonRpcHandler(DroidAgentMcpDispatcher(DroidAgentConfig.default()))

        val response = handler.handle("not json at all")

        assertEquals("""{"error":{"code":-32700, "message":"Parse error"}, "id":null, "jsonrpc":"2.0"}""", response)
    }

    @Test
    fun `valid json missing method returns invalid request`() {
        val handler = McpJsonRpcHandler(DroidAgentMcpDispatcher(DroidAgentConfig.default()))

        val response = handler.handle("""{"jsonrpc":"2.0","id":6}""")

        assertEquals("""{"error":{"code":-32600, "message":"Invalid Request"}, "id":6, "jsonrpc":"2.0"}""", response)
    }

    @Test
    fun `unknown method returns method not found`() {
        val handler = McpJsonRpcHandler(DroidAgentMcpDispatcher(DroidAgentConfig.default()))

        val response = handler.handle("""{"jsonrpc":"2.0","id":7,"method":"resources/list"}""")

        assertEquals("""{"error":{"code":-32601, "message":"Method not found: resources/list"}, "id":7, "jsonrpc":"2.0"}""", response)
    }

    @Test
    fun `unknown method without id returns no response`() {
        val handler = McpJsonRpcHandler(DroidAgentMcpDispatcher(DroidAgentConfig.default()))

        val response = handler.handle("""{"jsonrpc":"2.0","method":"some/notification"}""")

        assertNull(response)
    }
}
