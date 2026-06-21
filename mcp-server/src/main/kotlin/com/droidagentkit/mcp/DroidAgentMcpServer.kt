package com.droidagentkit.mcp

import com.droidagentkit.core.DroidAgentConfig
import com.droidagentkit.core.Json
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class DroidAgentMcpHttpServer(
    private val dispatcher: DroidAgentMcpDispatcher,
    private val host: String = "127.0.0.1",
    private val port: Int = 8765,
    private val bearerToken: String? = "local-dev-token",
) {
    private var server: HttpServer? = null

    fun start() {
        val http = HttpServer.create(InetSocketAddress(host, port), 0)
        http.createContext("/mcp") { exchange ->
            val authorized = bearerToken == null || exchange.requestHeaders.getFirst("Authorization") == "Bearer $bearerToken"
            if (!authorized) {
                exchange.sendResponseHeaders(401, 0)
                exchange.responseBody.close()
                return@createContext
            }
            val response = if (exchange.requestMethod.equals("GET", ignoreCase = true)) {
                Json.write(mapOf("tools" to dispatcher.listTools().map {
                    mapOf("name" to it.name, "description" to it.description, "inputSchema" to it.inputSchema)
                }))
            } else {
                val body = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
                val name = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                if (name == null) {
                    Json.write(mapOf("status" to "failed", "summary" to "Request body must include a tool name."))
                } else {
                    Json.write(dispatcher.call(name, emptyMap()))
                }
            }
            val bytes = response.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        http.executor = Executors.newCachedThreadPool()
        http.start()
        server = http
    }

    fun stop() {
        server?.stop(0)
        server = null
    }
}

class DroidAgentStdioServer(
    private val dispatcher: DroidAgentMcpDispatcher = DroidAgentMcpDispatcher(DroidAgentConfig.default()),
) {
    fun runOnce(line: String): String {
        val tool = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(line)?.groupValues?.get(1)
            ?: return Json.write(mapOf("status" to "failed", "summary" to "Missing tool name."))
        return Json.write(dispatcher.call(tool, emptyMap()))
    }
}
