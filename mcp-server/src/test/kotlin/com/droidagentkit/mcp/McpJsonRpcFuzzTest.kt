package com.droidagentkit.mcp

import com.droidagentkit.core.DroidAgentConfig
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Malformed JSON-RPC input must never throw — handler returns an error envelope or null.
 */
class McpJsonRpcFuzzTest {
    @Test
    fun `random payloads never crash the JSON-RPC handler`() {
        val handler = McpJsonRpcHandler(DroidAgentMcpDispatcher(DroidAgentConfig.default()))
        val rng = Random(7)
        val seeds =
            listOf(
                "",
                "{",
                "[]",
                "null",
                "\"string\"",
                """{"jsonrpc":"1.0","id":1,"method":"initialize"}""",
                """{"jsonrpc":"2.0","method":"tools/call","params":{"name":"android_gradle_run","arguments":{"task":"clean"}}}""",
                """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":null}""",
                "x".repeat(10_000),
            )
        seeds.forEach { seed ->
            val response = handler.handle(seed)
            assertTrue(response == null || response.contains("jsonrpc"))
        }
        repeat(100) {
            val junk = ByteArray(rng.nextInt(0, 512)) { rng.nextInt(0, 256).toByte() }.toString(Charsets.ISO_8859_1)
            val response = handler.handle(junk)
            assertTrue(response == null || response.contains("jsonrpc") || response.contains("error"))
        }
    }
}
