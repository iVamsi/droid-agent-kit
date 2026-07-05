package com.droidagentkit.mcp

import com.droidagentkit.core.DroidAgentConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets

class DroidAgentMcpHttpServerTest {
    private lateinit var server: DroidAgentMcpHttpServer

    @Before
    fun setUp() {
        val dispatcher = DroidAgentMcpDispatcher(DroidAgentConfig.default())
        server = DroidAgentMcpHttpServer(dispatcher, host = "127.0.0.1", port = 0, bearerToken = "test-token")
        server.start()
    }

    @After
    fun tearDown() {
        server.stop()
    }

    @Test
    fun `initialize request over http returns valid json-rpc response`() {
        val port = server.boundPort ?: error("server did not bind a port")
        val connection = URI("http://127.0.0.1:$port/mcp").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Authorization", "Bearer test-token")
        connection.setRequestProperty("Content-Type", "application/json")
        val body = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05"}}"""
        connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }

        val status = connection.responseCode
        val responseBody = connection.inputStream.readBytes().toString(StandardCharsets.UTF_8)

        assertEquals(200, status)
        assertTrue(responseBody.contains("\"protocolVersion\":\"2024-11-05\""))
        assertTrue(responseBody.contains("\"name\":\"droidagentkit\""))
    }

    @Test
    fun `unauthenticated request is rejected with 401`() {
        val port = server.boundPort ?: error("server did not bind a port")
        val connection = URI("http://127.0.0.1:$port/mcp").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write("{}".toByteArray(StandardCharsets.UTF_8)) }

        assertEquals(401, connection.responseCode)
    }
}
