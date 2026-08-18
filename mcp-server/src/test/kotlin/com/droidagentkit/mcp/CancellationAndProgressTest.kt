package com.droidagentkit.mcp

import com.droidagentkit.core.CancellationToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A dispatcher whose one tool blocks until cancelled, so the protocol plumbing can be tested
 * without spawning a real build.
 */
private class BlockingDispatcher : McpDispatcher {
    val started = CountDownLatch(1)
    val observedCancellation =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    override val instructions: String = "test"

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
        context.progress.report(0.0, 2.0, "step one")
        started.countDown()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!context.cancellation.isCancelled && System.nanoTime() < deadline) {
            Thread.sleep(5)
        }
        observedCancellation.set(context.cancellation.isCancelled)
        context.progress.report(2.0, 2.0, "done")
        return mapOf("status" to "success", "summary" to "finished")
    }
}

class CancellationAndProgressTest {
    private fun call(
        id: Any,
        progressToken: String? = null,
    ): String {
        val meta = if (progressToken == null) "" else ""","_meta":{"progressToken":"$progressToken"}"""
        val renderedId = if (id is String) "\"$id\"" else id.toString()
        return """{"jsonrpc":"2.0","id":$renderedId,"method":"tools/call","params":{"name":"t","arguments":{}$meta}}"""
    }

    @Test
    fun `a cancellation notification cancels the matching in-flight call`() {
        val dispatcher = BlockingDispatcher()
        val handler = McpJsonRpcHandler(dispatcher)
        val worker = Thread { handler.handle(call(id = 7)) }
        worker.start()

        assertTrue("tool never started", dispatcher.started.await(10, TimeUnit.SECONDS))
        handler.handle("""{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":7}}""")
        worker.join(TimeUnit.SECONDS.toMillis(10))

        assertFalse("worker should have finished", worker.isAlive)
        assertTrue("the tool must observe the cancellation", dispatcher.observedCancellation.get())
    }

    @Test
    fun `a numeric id cancels a call made with the equivalent string id`() {
        // Hosts are free to use either form, and losing the match would leave the process running.
        val dispatcher = BlockingDispatcher()
        val handler = McpJsonRpcHandler(dispatcher)
        val worker = Thread { handler.handle(call(id = "7")) }
        worker.start()

        assertTrue(dispatcher.started.await(10, TimeUnit.SECONDS))
        handler.handle("""{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":7}}""")
        worker.join(TimeUnit.SECONDS.toMillis(10))

        assertTrue(dispatcher.observedCancellation.get())
    }

    @Test
    fun `cancelling an unknown id is ignored rather than erroring`() {
        val handler = McpJsonRpcHandler(BlockingDispatcher())

        val response = handler.handle("""{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":999}}""")

        assertEquals("notifications never get a response", null, response)
    }

    @Test
    fun `progress notifications are emitted only when the client supplies a token`() {
        val withToken = ConcurrentLinkedQueue<String>()
        val dispatcherA = BlockingDispatcher()
        val handlerA = McpJsonRpcHandler(dispatcherA) { withToken.add(it) }
        val workerA = Thread { handlerA.handle(call(id = 1, progressToken = "tok-1")) }
        workerA.start()
        assertTrue(dispatcherA.started.await(10, TimeUnit.SECONDS))
        handlerA.handle("""{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":1}}""")
        workerA.join(TimeUnit.SECONDS.toMillis(10))

        assertTrue("expected progress frames, got $withToken", withToken.isNotEmpty())
        val first = withToken.first()
        assertTrue(first.contains("notifications/progress"))
        assertTrue("the client's token must be echoed back: $first", first.contains("tok-1"))

        val withoutToken = ConcurrentLinkedQueue<String>()
        val dispatcherB = BlockingDispatcher()
        val handlerB = McpJsonRpcHandler(dispatcherB) { withoutToken.add(it) }
        val workerB = Thread { handlerB.handle(call(id = 2)) }
        workerB.start()
        assertTrue(dispatcherB.started.await(10, TimeUnit.SECONDS))
        handlerB.handle("""{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":2}}""")
        workerB.join(TimeUnit.SECONDS.toMillis(10))

        assertTrue("no token means no progress traffic, got $withoutToken", withoutToken.isEmpty())
    }

    @Test
    fun `only tools calls are routed off the reader thread`() {
        val handler = McpJsonRpcHandler(BlockingDispatcher())

        assertTrue(handler.isLongRunning(call(id = 1)))
        assertFalse(handler.isLongRunning("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""))
        assertFalse(handler.isLongRunning("""{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":1}}"""))
        assertFalse("malformed input must not be treated as a call", handler.isLongRunning("not json"))
    }

    @Test
    fun `the stdio loop answers a cancellation while a call is still running`() {
        // The end-to-end property: a cancel sent during a long build has to be read while the build
        // is still running, not after it finishes.
        val dispatcher = BlockingDispatcher()
        val written = ConcurrentLinkedQueue<String>()
        val server = DroidAgentStdioServer(dispatcher)

        val lines =
            sequence {
                yield(call(id = 42))
                // Block until the tool is actually running, then cancel it.
                dispatcher.started.await(10, TimeUnit.SECONDS)
                yield("""{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":42}}""")
            }

        server.run(lines) { written.add(it) }

        assertTrue("the tool must observe the cancellation", dispatcher.observedCancellation.get())
        assertTrue("the call must still be answered", written.any { it.contains("\"id\":42") })
    }

    @Test
    fun `a dispatcher that ignores the context still works`() {
        // The context overload is defaulted so existing dispatchers keep compiling and behaving.
        val plain =
            object : McpDispatcher {
                override val instructions = "plain"

                override fun listTools(): List<McpTool> = emptyList()

                override fun call(
                    name: String,
                    arguments: Map<String, Any?>,
                ): Map<String, Any> = mapOf("status" to "success", "summary" to "ok")
            }

        val response = McpJsonRpcHandler(plain).handle(call(id = 1))

        assertTrue(response!!.contains("\"summary\":\"ok\""))
        assertEquals(false, CancellationToken.NONE.isCancelled)
    }
}
