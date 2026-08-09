package com.droidagentkit.mcp

import com.droidagentkit.core.Capability
import com.droidagentkit.core.InteractiveConfirmation
import com.droidagentkit.core.OperationRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/** Minimal dispatcher that just reports what the confirmer said, so the transport is under test. */
private class ConfirmingDispatcher : McpDispatcher {
    override val instructions = "test"

    override fun listTools(): List<McpTool> = emptyList()

    override fun call(
        name: String,
        arguments: Map<String, Any?>,
    ): Map<String, Any> = mapOf("status" to "success", "summary" to "no context")

    override fun call(
        name: String,
        arguments: Map<String, Any?>,
        context: ToolCallContext,
    ): Map<String, Any> {
        val outcome =
            context.confirmer.confirm(
                OperationRequest(
                    operationId = "android_app_clear_data",
                    requiredCapabilities = setOf(Capability.APP_DESTRUCTIVE),
                    destructive = true,
                    packageName = "com.example.app",
                    confirmDestructive = true,
                ),
            )
        return mapOf("status" to "success", "summary" to outcome.name)
    }
}

class ElicitationTest {
    private val initializeWithElicitation =
        """{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2025-11-25",""" +
            """"capabilities":{"elicitation":{}},"clientInfo":{"name":"t","version":"0"}}}"""
    private val initializeWithout =
        """{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2025-11-25",""" +
            """"capabilities":{},"clientInfo":{"name":"t","version":"0"}}}"""
    private val toolCall = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"t","arguments":{}}}"""

    /** Drives one call, answering the elicitation the server sends with [answer]. */
    private fun callAnswering(
        initialize: String,
        answer: (String) -> String?,
    ): String {
        val sent = ConcurrentLinkedQueue<String>()
        lateinit var handler: McpJsonRpcHandler
        handler =
            McpJsonRpcHandler(ConfirmingDispatcher()) { frame ->
                sent.add(frame)
                // The client answers on its own thread, exactly as a real host would.
                answer(frame)?.let { reply -> Thread { handler.handle(reply) }.start() }
            }
        handler.handle(initialize)
        val response = handler.handle(toolCall)
        return response!!
    }

    private fun elicitationIdOf(frame: String): String? = Regex("\"id\":\"(dak-elicit-[0-9]+)\"").find(frame)?.groupValues?.get(1)

    @Test
    fun `an accepted elicitation approves the operation`() {
        val response =
            callAnswering(initializeWithElicitation) { frame ->
                val id = elicitationIdOf(frame) ?: return@callAnswering null
                """{"jsonrpc":"2.0","id":"$id","result":{"action":"accept","content":{"approve":true}}}"""
            }

        assertTrue("expected APPROVED in $response", response.contains("APPROVED"))
    }

    @Test
    fun `a declined elicitation refuses the operation`() {
        val response =
            callAnswering(initializeWithElicitation) { frame ->
                val id = elicitationIdOf(frame) ?: return@callAnswering null
                """{"jsonrpc":"2.0","id":"$id","result":{"action":"decline"}}"""
            }

        assertTrue("expected DECLINED in $response", response.contains("DECLINED"))
    }

    @Test
    fun `accepting but answering no is a decline`() {
        // A host may return action=accept with the boolean set to false; consent is the boolean.
        val response =
            callAnswering(initializeWithElicitation) { frame ->
                val id = elicitationIdOf(frame) ?: return@callAnswering null
                """{"jsonrpc":"2.0","id":"$id","result":{"action":"accept","content":{"approve":false}}}"""
            }

        assertTrue("expected DECLINED in $response", response.contains("DECLINED"))
    }

    @Test
    fun `a client that never advertised elicitation is unavailable, not approved`() {
        var asked = false
        val response =
            callAnswering(initializeWithout) { frame ->
                if (frame.contains("elicitation/create")) asked = true
                null
            }

        assertTrue("expected UNAVAILABLE in $response", response.contains("UNAVAILABLE"))
        assertTrue("must not send an elicitation the client cannot handle", !asked)
    }

    @Test
    fun `a client that never answers times out as unavailable rather than hanging`() {
        // Failing closed: the caller turns UNAVAILABLE into a denial, so a silent host blocks the
        // destructive operation instead of waving it through.
        val sent = ConcurrentLinkedQueue<String>()
        val handler = McpJsonRpcHandler(ConfirmingDispatcher()) { sent.add(it) }
        handler.handle(initializeWithElicitation)

        val started = System.nanoTime()
        val outcome = handler.elicitConfirmation("t", "m", timeoutSeconds = 1)
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        assertEquals(InteractiveConfirmation.UNAVAILABLE, outcome)
        assertTrue("should give up near the timeout, took ${elapsedMs}ms", elapsedMs in 500..5_000)
        assertTrue("the request should still have been sent", sent.any { it.contains("elicitation/create") })
    }

    @Test
    fun `the elicitation request carries a boolean schema and names the operation`() {
        val sent = ConcurrentLinkedQueue<String>()
        lateinit var handler: McpJsonRpcHandler
        handler =
            McpJsonRpcHandler(ConfirmingDispatcher()) { frame ->
                sent.add(frame)
                elicitationIdOf(frame)?.let { id ->
                    Thread { handler.handle("""{"jsonrpc":"2.0","id":"$id","result":{"action":"decline"}}""") }.start()
                }
            }
        handler.handle(initializeWithElicitation)
        handler.handle(toolCall)

        val request = sent.first { it.contains("elicitation/create") }
        assertTrue("should name the operation: $request", request.contains("android_app_clear_data"))
        assertTrue("should name the package: $request", request.contains("com.example.app"))
        assertTrue("should request a boolean: $request", request.contains("\"type\":\"boolean\""))
    }
}
