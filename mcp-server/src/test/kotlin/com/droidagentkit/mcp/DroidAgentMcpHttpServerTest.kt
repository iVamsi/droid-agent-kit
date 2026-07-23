package com.droidagentkit.mcp

import com.droidagentkit.core.DroidAgentConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.Socket
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
        val body = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25"}}"""
        connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }

        val status = connection.responseCode
        val responseBody = connection.inputStream.readBytes().toString(StandardCharsets.UTF_8)

        assertEquals(200, status)
        assertTrue(responseBody.contains("\"protocolVersion\":\"2025-11-25\""))
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

    @Test
    fun `request from a non-local origin is rejected`() {
        val port = server.boundPort ?: error("server did not bind a port")
        Socket("127.0.0.1", port).use { socket ->
            val writer = socket.getOutputStream().writer(StandardCharsets.UTF_8)
            writer.write(
                "POST /mcp HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\n" +
                    "Authorization: Bearer test-token\r\n" +
                    "Origin: https://malicious.example\r\n" +
                    "Content-Length: 2\r\n\r\n{}",
            )
            writer.flush()

            val statusLine =
                socket
                    .getInputStream()
                    .bufferedReader()
                    .readLine()
            assertTrue(statusLine.contains(" 403 "))
        }
    }

    @Test
    fun `get is rejected because MCP requests use post`() {
        val port = server.boundPort ?: error("server did not bind a port")
        val connection = URI("http://127.0.0.1:$port/mcp").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("Authorization", "Bearer test-token")

        assertEquals(405, connection.responseCode)
    }

    @Test
    fun `unsupported MCP protocol header is rejected`() {
        val port = server.boundPort ?: error("server did not bind a port")
        val connection = URI("http://127.0.0.1:$port/mcp").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Authorization", "Bearer test-token")
        connection.setRequestProperty("MCP-Protocol-Version", "2024-11-05")
        connection.outputStream.use { it.write("{}".toByteArray(StandardCharsets.UTF_8)) }

        assertEquals(400, connection.responseCode)
    }

    @Test
    fun `request with non-json accept header is rejected with 406`() {
        val port = server.boundPort ?: error("server did not bind a port")
        val connection = URI("http://127.0.0.1:$port/mcp").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Authorization", "Bearer test-token")
        connection.setRequestProperty("Accept", "text/plain")
        connection.outputStream.use { it.write("{}".toByteArray(StandardCharsets.UTF_8)) }

        assertEquals(406, connection.responseCode)
    }

    @Test
    fun `notification without id returns 202`() {
        val port = server.boundPort ?: error("server did not bind a port")
        val connection = URI("http://127.0.0.1:$port/mcp").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Authorization", "Bearer test-token")
        connection.setRequestProperty("Content-Type", "application/json")
        val body = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
        connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }

        assertEquals(202, connection.responseCode)
    }
}
