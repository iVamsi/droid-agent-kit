package com.droidagentkit.mcp

import com.droidagentkit.core.DroidAgentConfig
import com.droidagentkit.core.ToolGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class McpJsonRpcHandlerTest {
    @Test
    fun `initialize negotiates the latest supported protocol version and returns server info`() {
        val handler = McpJsonRpcHandler(DroidAgentMcpDispatcher(DroidAgentConfig.default()))

        val response =
            handler.handle(
                """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}""",
            )

        assertTrue(response != null)
        assertTrue(response!!.contains("\"protocolVersion\":\"2025-11-25\""))
        assertTrue(response.contains("\"name\":\"droidagentkit\""))
        assertTrue(response.contains("\"version\":\"0.1.0-alpha\""))
        assertTrue(response.contains("\"id\":1"))
    }

    @Test
    fun `initialize without protocolVersion falls back to default`() {
        val handler = McpJsonRpcHandler(DroidAgentMcpDispatcher(DroidAgentConfig.default()))

        val response = handler.handle("""{"jsonrpc":"2.0","id":2,"method":"initialize","params":{}}""")

        assertTrue(response!!.contains("\"protocolVersion\":\"2025-11-25\""))
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
        assertTrue(response.contains("\"outputSchema\""))
    }

    @Test
    fun `tools call success returns isError false and embeds tool result`() {
        val root = Files.createTempDirectory("dak-mcp-rpc")
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"RpcDemo\"\ninclude(\":app\")")
        Files.createDirectories(root.resolve("app"))
        Files.writeString(root.resolve("app/build.gradle.kts"), "plugins { id(\"com.android.application\") }")
        val handler = McpJsonRpcHandler(DroidAgentMcpDispatcher(DroidAgentConfig.default(), root))

        val response =
            handler.handle(
                """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"android_project_inspect","arguments":{"rootPath":"${root.toString().replace(
                    "\\",
                    "\\\\",
                )}"}}}""",
            )

        assertTrue(response!!.contains("\"isError\":false"))
        assertTrue(response.contains("\"structuredContent\""))
        assertTrue(response.contains("RpcDemo"))
    }

    @Test
    fun `ping returns an empty success result`() {
        val handler = McpJsonRpcHandler(DroidAgentMcpDispatcher(DroidAgentConfig.default()))

        val response = handler.handle("""{"jsonrpc":"2.0","id":9,"method":"ping"}""")

        assertEquals("""{"id":9, "jsonrpc":"2.0", "result":{}}""", response)
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

        val response = handler.handle("""{"jsonrpc":"2.0","id":7,"method":"some/unknown-method"}""")

        assertEquals("""{"error":{"code":-32601, "message":"Method not found: some/unknown-method"}, "id":7, "jsonrpc":"2.0"}""", response)
    }

    @Test
    fun `initialize advertises tools resources and prompts capabilities`() {
        val handler = McpJsonRpcHandler(DroidAgentMcpDispatcher(DroidAgentConfig.default()))

        val response = handler.handle("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""")

        assertTrue(response!!.contains("\"resources\""))
        assertTrue(response.contains("\"prompts\""))
        assertTrue(response.contains("\"tools\""))
    }

    @Test
    fun `tools list includes annotations for annotated tools`() {
        val handler = McpJsonRpcHandler(DroidAgentMcpDispatcher(DroidAgentConfig.default()))

        val response = handler.handle("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")

        assertTrue(response!!.contains("\"readOnlyHint\""))
        assertTrue(response.contains("\"openWorldHint\""))
    }

    @Test
    fun `resources list returns the concrete project resources`() {
        val root = Files.createTempDirectory("dak-resources-list")
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"ResDemo\"")
        val handler = McpJsonRpcHandler(DroidAgentMcpDispatcher(DroidAgentConfig.default(), root))

        val response = handler.handle("""{"jsonrpc":"2.0","id":1,"method":"resources/list"}""")

        assertTrue(response!!.contains("droidagent://project/inspect"))
        assertTrue(response.contains("droidagent://project/agents-doc"))
        assertTrue(response.contains("droidagent://project/readiness"))
    }

    @Test
    fun `resources templates list returns artifact and golden templates`() {
        val root = Files.createTempDirectory("dak-resources-templates")
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"TmplDemo\"")
        val handler = McpJsonRpcHandler(DroidAgentMcpDispatcher(DroidAgentConfig.default(), root))

        val response = handler.handle("""{"jsonrpc":"2.0","id":1,"method":"resources/templates/list"}""")

        assertTrue(response!!.contains("droidagent://artifacts/{id}"))
        assertTrue(response.contains("droidagent://goldens/{case}"))
    }

    @Test
    fun `resources read returns agents doc content`() {
        val root = Files.createTempDirectory("dak-resources-read")
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"ReadDemo\"")
        Files.writeString(root.resolve("AGENTS.md"), "# Project agents doc\n")
        val handler = McpJsonRpcHandler(DroidAgentMcpDispatcher(DroidAgentConfig.default(), root))

        val response =
            handler.handle(
                """{"jsonrpc":"2.0","id":1,"method":"resources/read","params":{"uri":"droidagent://project/agents-doc"}}""",
            )

        assertTrue(response!!.contains("Project agents doc"))
    }

    @Test
    fun `resources read for a malformed uri returns resource not found`() {
        val handler = McpJsonRpcHandler(DroidAgentMcpDispatcher(DroidAgentConfig.default()))

        val response =
            handler.handle("""{"jsonrpc":"2.0","id":1,"method":"resources/read","params":{"uri":"droidagent://does/not/exist"}}""")

        assertTrue(response!!.contains("-32002"))
        assertTrue(response.contains("Resource not found"))
    }

    @Test
    fun `resources read without uri returns invalid params`() {
        val handler = McpJsonRpcHandler(DroidAgentMcpDispatcher(DroidAgentConfig.default()))

        val response = handler.handle("""{"jsonrpc":"2.0","id":1,"method":"resources/read","params":{}}""")

        assertTrue(response!!.contains("-32602"))
    }

    @Test
    fun `prompts list returns prompts whose required tools are exposed`() {
        val handler = McpJsonRpcHandler(DroidAgentMcpDispatcher(DroidAgentConfig.default()))

        val response = handler.handle("""{"jsonrpc":"2.0","id":1,"method":"prompts/list"}""")

        // build-failure-fix and visual-regression-review require only core tools, so they are listed.
        assertTrue(response!!.contains("\"build-failure-fix\""))
        assertTrue(response.contains("\"visual-regression-review\""))
        // crash-investigation requires device-read tools which are not exposed by default.
        assertTrue(!response.contains("\"crash-investigation\""))
    }

    @Test
    fun `prompts list includes device-read prompts when the group is exposed`() {
        val root = Files.createTempDirectory("dak-prompts-dr")
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"PromptDr\"")
        val dispatcher =
            DroidAgentMcpDispatcher(
                DroidAgentConfig.default(),
                root,
                exposedGroups = setOf(ToolGroup.CORE, ToolGroup.DEVICE_READ),
            )
        val handler = McpJsonRpcHandler(dispatcher)

        val response = handler.handle("""{"jsonrpc":"2.0","id":1,"method":"prompts/list"}""")

        assertTrue(response!!.contains("\"crash-investigation\""))
    }

    @Test
    fun `prompts get returns messages for a known prompt`() {
        val handler = McpJsonRpcHandler(DroidAgentMcpDispatcher(DroidAgentConfig.default()))

        val response =
            handler.handle(
                """{"jsonrpc":"2.0","id":1,"method":"prompts/get","params":{"name":"build-failure-fix","arguments":{"task":":app:assembleDebug"}}}""",
            )

        assertTrue(response!!.contains("\"messages\""))
        assertTrue(response.contains(":app:assembleDebug"))
    }

    @Test
    fun `prompts get for an unknown prompt returns method not found`() {
        val handler = McpJsonRpcHandler(DroidAgentMcpDispatcher(DroidAgentConfig.default()))

        val response =
            handler.handle("""{"jsonrpc":"2.0","id":1,"method":"prompts/get","params":{"name":"no-such-prompt"}}""")

        assertTrue(response!!.contains("-32601"))
    }

    @Test
    fun `stdio transport routes resources and prompts through runOnce`() {
        val root = Files.createTempDirectory("dak-stdio")
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"StdioDemo\"")
        val server = DroidAgentStdioServer(DroidAgentMcpDispatcher(DroidAgentConfig.default(), root))

        val resources = server.runOnce("""{"jsonrpc":"2.0","id":1,"method":"resources/list"}""")
        assertTrue(resources!!.contains("droidagent://project/inspect"))
        val prompts = server.runOnce("""{"jsonrpc":"2.0","id":2,"method":"prompts/list"}""")
        assertTrue(prompts!!.contains("\"build-failure-fix\""))
    }

    @Test
    fun `unknown method without id returns no response`() {
        val handler = McpJsonRpcHandler(DroidAgentMcpDispatcher(DroidAgentConfig.default()))

        val response = handler.handle("""{"jsonrpc":"2.0","method":"some/notification"}""")

        assertNull(response)
    }
}
