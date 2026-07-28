package com.droidagentkit.mcp

import com.droidagentkit.core.DroidAgentConfig
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executors

class DroidAgentMcpHttpServer(
    private val dispatcher: McpDispatcher,
    private val host: String = "127.0.0.1",
    private val port: Int = 8765,
    bearerToken: String? = null,
    private val allowRemote: Boolean = false,
) {
    val bearerToken: String = bearerToken ?: generateBearerToken()
    private var server: HttpServer? = null
    private val rpcHandler = McpJsonRpcHandler(dispatcher)

    val boundPort: Int?
        get() = server?.address?.port

    fun start() {
        if (!allowRemote && !isLoopbackHost(host)) {
            throw IllegalArgumentException(
                "Refusing to bind MCP HTTP server to non-loopback host '$host'. " +
                    "Pass --allow-remote to override (local-only by default).",
            )
        }
        val http = HttpServer.create(InetSocketAddress(host, port), 0)
        http.createContext("/mcp") { exchange ->
            if (!isAllowedHost(exchange.requestHeaders.getFirst("Host"))) {
                exchange.sendResponseHeaders(403, -1)
                exchange.responseBody.close()
                return@createContext
            }
            if (!isAllowedOrigin(exchange.requestHeaders.getFirst("Origin"))) {
                exchange.sendResponseHeaders(403, -1)
                exchange.responseBody.close()
                return@createContext
            }
            if (exchange.requestMethod != "POST") {
                exchange.responseHeaders.add("Allow", "POST")
                exchange.sendResponseHeaders(405, -1)
                exchange.responseBody.close()
                return@createContext
            }
            val accept = exchange.requestHeaders.getFirst("Accept")
            if (accept != null && !accept.contains("application/json") && !accept.contains("*/*")) {
                exchange.sendResponseHeaders(406, -1)
                exchange.responseBody.close()
                return@createContext
            }
            val authorization = exchange.requestHeaders.getFirst("Authorization")
            if (!bearerMatches(authorization)) {
                exchange.sendResponseHeaders(401, 0)
                exchange.responseBody.close()
                return@createContext
            }
            val protocolVersion = exchange.requestHeaders.getFirst("MCP-Protocol-Version")
            if (protocolVersion != null && protocolVersion != MCP_PROTOCOL_VERSION) {
                exchange.sendResponseHeaders(400, -1)
                exchange.responseBody.close()
                return@createContext
            }
            val body = exchange.requestBody.readNBytes(MAX_REQUEST_BYTES + 1)
            if (body.size > MAX_REQUEST_BYTES) {
                exchange.sendResponseHeaders(413, -1)
                exchange.responseBody.close()
                return@createContext
            }
            val response = rpcHandler.handle(body.toString(StandardCharsets.UTF_8))
            if (response == null) {
                exchange.sendResponseHeaders(202, -1)
                exchange.responseBody.close()
            } else {
                val bytes = response.toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        http.executor = Executors.newFixedThreadPool(MAX_HTTP_WORKERS)
        http.start()
        server = http
    }

    fun stop() {
        server?.stop(0)
        server = null
    }

    private fun bearerMatches(authorization: String?): Boolean {
        val expected = "Bearer $bearerToken".toByteArray(StandardCharsets.UTF_8)
        val presented = (authorization ?: "").toByteArray(StandardCharsets.UTF_8)
        // Always digest both sides so comparison cost does not depend on early length mismatch.
        val presentedDigest = MessageDigest.getInstance("SHA-256").digest(presented)
        val expectedDigest = MessageDigest.getInstance("SHA-256").digest(expected)
        return MessageDigest.isEqual(presentedDigest, expectedDigest)
    }

    private fun isAllowedOrigin(origin: String?): Boolean {
        if (origin == null) return true
        val originHost = runCatching { URI(origin).host }.getOrNull() ?: return false
        return isLoopbackHost(originHost)
    }

    private fun isAllowedHost(hostHeader: String?): Boolean {
        if (hostHeader.isNullOrBlank()) return true
        val hostname = hostHeader.substringBefore(':').trim().lowercase()
        if (hostname.isEmpty()) return false
        if (allowRemote) return true
        return isLoopbackHost(hostname)
    }

    private companion object {
        const val MAX_HTTP_WORKERS = 4
        const val MAX_REQUEST_BYTES = 1_048_576

        fun isLoopbackHost(hostname: String): Boolean {
            if (hostname == "127.0.0.1" || hostname == "localhost" || hostname == "::1" || hostname == "[::1]") {
                return true
            }
            return runCatching { InetAddress.getByName(hostname).isLoopbackAddress }.getOrDefault(false)
        }

        fun generateBearerToken(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}

class DroidAgentStdioServer(
    private val dispatcher: McpDispatcher = DroidAgentMcpDispatcher(DroidAgentConfig.default(), Path.of(".")),
) {
    private val rpcHandler = McpJsonRpcHandler(dispatcher)

    fun runOnce(line: String): String? = rpcHandler.handle(line)
}
