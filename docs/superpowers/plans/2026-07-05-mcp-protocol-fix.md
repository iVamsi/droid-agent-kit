# MCP Protocol Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make DroidAgentKit's stdio and HTTP MCP servers speak real JSON-RPC 2.0 / MCP protocol (initialize handshake, tools/list, tools/call) instead of the current regex-based ad hoc parser, so `install-mcp`'s registrations (Codex, Claude Code, Cursor, Zed, VS Code) and the documented Android Studio HTTP setup can actually connect.

**Architecture:** One new shared component, `McpJsonRpcHandler`, owns all protocol logic (parsing via `kotlinx.serialization.json`, building responses via the existing hand-rolled `Json.write()`). `DroidAgentStdioServer` and `DroidAgentMcpHttpServer` become thin transport adapters that delegate to it.

**Tech Stack:** Kotlin 2.x, JVM 17, `kotlinx-serialization-json:1.11.0` (already a `mcp-server` dependency), `com.sun.net.httpserver` (JDK-bundled), JUnit 4.13.2.

## Global Constraints

- No new third-party dependencies — `kotlinx-serialization-json` is already present in `mcp-server/build.gradle.kts`; do not add anything else.
- `DroidAgentMcpDispatcher`'s tool set, schemas, and behavior are unchanged — this fix only makes the existing tools reachable over a spec-compliant transport.
- The `initialize` response's `protocolVersion` must be copied verbatim from the request's `params.protocolVersion` (not hardcoded), falling back to `"2024-11-05"` only when the request omits it entirely.
- Server identity in the `initialize` response: `serverInfo.name = "droidagentkit"`, `serverInfo.version = "0.1.0-alpha"`.
- JSON-RPC notifications (messages with no `"id"` key) never get a response — this is different from an empty response.
- `tools/call` results wrap the dispatcher's existing `Map<String, Any>` tool result as a single `text` content item; `isError` is `true` when the tool result's `"status"` is `"failed"`, `"blocked"`, or `"unsupported"`.
- Error codes: Parse error → `-32700`, Invalid Request → `-32600`, Method not found → `-32601`.
- Tests use real fixtures (`Files.createTempDirectory`, real `HttpServer` instances, real `HttpURLConnection` requests) — never mock data classes, matching existing repo convention.
- Test run command: `./gradlew :mcp-server:test --tests "com.droidagentkit.mcp.<ClassName>"`.
- Commit format: `type(scope): description`.

---

### Task 1: McpJsonRpcHandler — core protocol logic

**Files:**
- Create: `mcp-server/src/main/kotlin/com/droidagentkit/mcp/McpJsonRpc.kt`
- Test: `mcp-server/src/test/kotlin/com/droidagentkit/mcp/McpJsonRpcHandlerTest.kt`

**Interfaces:**
- Consumes: `DroidAgentMcpDispatcher.listTools(): List<McpTool>` (fields: `name: String`, `description: String`, `inputSchema: Map<String, Any>`) and `DroidAgentMcpDispatcher.call(name: String, arguments: Map<String, Any?>): Map<String, Any>` (existing, both in `mcp-server/src/main/kotlin/com/droidagentkit/mcp/DroidAgentMcpDispatcher.kt`, unchanged). Also consumes the existing `com.droidagentkit.core.Json.write(value: Any?): String` writer (`toolbox-core`), unchanged.
- Produces: `class McpJsonRpcHandler(dispatcher: DroidAgentMcpDispatcher)` with `fun handle(rawMessage: String): String?` — Task 2's transport adapters call this exact signature.

- [ ] **Step 1: Write the failing tests**

Create `mcp-server/src/test/kotlin/com/droidagentkit/mcp/McpJsonRpcHandlerTest.kt`:

```kotlin
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
                """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"android_project_inspect","arguments":{"rootPath":"${root.toString().replace("\\", "\\\\")}"}}}""",
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

        assertEquals("""{"error":{"code":-32700,"message":"Parse error"},"id":null,"jsonrpc":"2.0"}""", response)
    }

    @Test
    fun `valid json missing method returns invalid request`() {
        val handler = McpJsonRpcHandler(DroidAgentMcpDispatcher(DroidAgentConfig.default()))

        val response = handler.handle("""{"jsonrpc":"2.0","id":6}""")

        assertEquals("""{"error":{"code":-32600,"message":"Invalid Request"},"id":6,"jsonrpc":"2.0"}""", response)
    }

    @Test
    fun `unknown method returns method not found`() {
        val handler = McpJsonRpcHandler(DroidAgentMcpDispatcher(DroidAgentConfig.default()))

        val response = handler.handle("""{"jsonrpc":"2.0","id":7,"method":"resources/list"}""")

        assertEquals("""{"error":{"code":-32601,"message":"Method not found: resources/list"},"id":7,"jsonrpc":"2.0"}""", response)
    }

    @Test
    fun `unknown method without id returns no response`() {
        val handler = McpJsonRpcHandler(DroidAgentMcpDispatcher(DroidAgentConfig.default()))

        val response = handler.handle("""{"jsonrpc":"2.0","method":"some/notification"}""")

        assertNull(response)
    }
}
```

Note on the exact-match assertions above: `com.droidagentkit.core.Json.write()` serializes `Map` entries sorted alphabetically by key (see `toolbox-core/src/main/kotlin/com/droidagentkit/core/Json.kt`), so a top-level object with keys `error`, `id`, `jsonrpc` always serializes in that alphabetical order — the exact strings above are correct given that behavior, not arbitrary.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :mcp-server:test --tests "com.droidagentkit.mcp.McpJsonRpcHandlerTest"`
Expected: FAIL — `McpJsonRpcHandler` is unresolved (class doesn't exist yet).

- [ ] **Step 3: Implement `McpJsonRpcHandler`**

Create `mcp-server/src/main/kotlin/com/droidagentkit/mcp/McpJsonRpc.kt`:

```kotlin
package com.droidagentkit.mcp

import com.droidagentkit.core.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.Json as KJson

private const val PARSE_ERROR = -32700
private const val INVALID_REQUEST = -32600
private const val METHOD_NOT_FOUND = -32601

private const val SERVER_NAME = "droidagentkit"
private const val SERVER_VERSION = "0.1.0-alpha"
private const val DEFAULT_PROTOCOL_VERSION = "2024-11-05"

private val ERROR_STATUSES = setOf("failed", "blocked", "unsupported")

class McpJsonRpcHandler(
    private val dispatcher: DroidAgentMcpDispatcher,
) {
    fun handle(rawMessage: String): String? {
        val root =
            try {
                KJson.parseToJsonElement(rawMessage).jsonObject
            } catch (e: Exception) {
                return errorResponse(null, PARSE_ERROR, "Parse error")
            }

        val hasId = root.containsKey("id")
        val id = root["id"]?.toKotlinValue()
        val method = (root["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content

        if (method == null) {
            return if (hasId) errorResponse(id, INVALID_REQUEST, "Invalid Request") else null
        }

        return when (method) {
            "initialize" -> successResponse(id, handleInitialize(root["params"] as? JsonObject))
            "notifications/initialized" -> null
            "tools/list" -> successResponse(id, handleToolsList())
            "tools/call" -> handleToolsCall(id, root["params"] as? JsonObject)
            else -> if (hasId) errorResponse(id, METHOD_NOT_FOUND, "Method not found: $method") else null
        }
    }

    private fun handleInitialize(params: JsonObject?): Map<String, Any?> {
        val protocolVersion = (params?.get("protocolVersion") as? JsonPrimitive)?.content ?: DEFAULT_PROTOCOL_VERSION
        return mapOf(
            "protocolVersion" to protocolVersion,
            "capabilities" to mapOf("tools" to emptyMap<String, Any?>()),
            "serverInfo" to mapOf("name" to SERVER_NAME, "version" to SERVER_VERSION),
        )
    }

    private fun handleToolsList(): Map<String, Any?> =
        mapOf(
            "tools" to
                dispatcher.listTools().map {
                    mapOf("name" to it.name, "description" to it.description, "inputSchema" to it.inputSchema)
                },
        )

    private fun handleToolsCall(
        id: Any?,
        params: JsonObject?,
    ): String {
        val name =
            (params?.get("name") as? JsonPrimitive)?.content
                ?: return errorResponse(id, INVALID_REQUEST, "Invalid Request: params.name is required")
        val argumentsElement = params["arguments"]
        val arguments =
            if (argumentsElement is JsonObject) {
                argumentsElement.entries.associate { it.key to it.value.toKotlinValue() }
            } else {
                emptyMap()
            }
        val result = dispatcher.call(name, arguments)
        val isError = ERROR_STATUSES.contains(result["status"])
        return successResponse(
            id,
            mapOf(
                "content" to listOf(mapOf("type" to "text", "text" to Json.write(result))),
                "isError" to isError,
            ),
        )
    }

    private fun successResponse(
        id: Any?,
        result: Map<String, Any?>,
    ): String = Json.write(mapOf("jsonrpc" to "2.0", "id" to id, "result" to result))

    private fun errorResponse(
        id: Any?,
        code: Int,
        message: String,
    ): String = Json.write(mapOf("jsonrpc" to "2.0", "id" to id, "error" to mapOf("code" to code, "message" to message)))
}

private fun JsonElement.toKotlinValue(): Any? =
    when (this) {
        is JsonNull -> null
        is JsonObject -> entries.associate { it.key to it.value.toKotlinValue() }
        is JsonArray -> map { it.toKotlinValue() }
        is JsonPrimitive ->
            if (isString) {
                content
            } else {
                booleanOrNull ?: longOrNull ?: doubleOrNull ?: content
            }
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :mcp-server:test --tests "com.droidagentkit.mcp.McpJsonRpcHandlerTest"`
Expected: PASS, all 10 tests green.

- [ ] **Step 5: Commit**

```bash
git add mcp-server/src/main/kotlin/com/droidagentkit/mcp/McpJsonRpc.kt mcp-server/src/test/kotlin/com/droidagentkit/mcp/McpJsonRpcHandlerTest.kt
git commit -m "feat(mcp-server): add JSON-RPC 2.0 handler for the MCP protocol"
```

---

### Task 2: Wire stdio and HTTP transports to the handler

**Files:**
- Modify: `mcp-server/src/main/kotlin/com/droidagentkit/mcp/DroidAgentMcpServer.kt` (entire file — both classes)
- Modify: `cli/src/main/kotlin/com/droidagentkit/cli/DroidAgentMain.kt:132-133` (the stdio loop inside `serveMcp`)
- Test: `mcp-server/src/test/kotlin/com/droidagentkit/mcp/DroidAgentMcpHttpServerTest.kt`

**Interfaces:**
- Consumes: `McpJsonRpcHandler(dispatcher: DroidAgentMcpDispatcher)` and `fun handle(rawMessage: String): String?` from Task 1.
- Produces: `DroidAgentStdioServer.runOnce(line: String): String?` (return type changes from `String` to `String?` — Task 2 is the only task that calls this, and it's the last task to touch `DroidAgentMain.kt`, so this is a self-contained signature change). `DroidAgentMcpHttpServer.boundPort: Int?` (new public property, read by this task's own HTTP test).

- [ ] **Step 1: Write the failing HTTP round-trip test**

Create `mcp-server/src/test/kotlin/com/droidagentkit/mcp/DroidAgentMcpHttpServerTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :mcp-server:test --tests "com.droidagentkit.mcp.DroidAgentMcpHttpServerTest"`
Expected: FAIL — `server.boundPort` is unresolved (no such property yet).

- [ ] **Step 3: Rewrite `DroidAgentMcpServer.kt`**

Replace the entire content of `mcp-server/src/main/kotlin/com/droidagentkit/mcp/DroidAgentMcpServer.kt` with:

```kotlin
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
    private val rpcHandler = McpJsonRpcHandler(dispatcher)

    val boundPort: Int?
        get() = server?.address?.port

    fun start() {
        val http = HttpServer.create(InetSocketAddress(host, port), 0)
        http.createContext("/mcp") { exchange ->
            val authorized = bearerToken == null || exchange.requestHeaders.getFirst("Authorization") == "Bearer $bearerToken"
            if (!authorized) {
                exchange.sendResponseHeaders(401, 0)
                exchange.responseBody.close()
                return@createContext
            }
            if (exchange.requestMethod.equals("GET", ignoreCase = true)) {
                val response =
                    Json.write(
                        mapOf(
                            "tools" to
                                dispatcher.listTools().map {
                                    mapOf("name" to it.name, "description" to it.description, "inputSchema" to it.inputSchema)
                                },
                        ),
                    )
                val bytes = response.toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } else {
                val body = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
                val response = rpcHandler.handle(body)
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
    private val rpcHandler = McpJsonRpcHandler(dispatcher)

    fun runOnce(line: String): String? = rpcHandler.handle(line)
}
```

- [ ] **Step 4: Run the HTTP test to verify it passes**

Run: `./gradlew :mcp-server:test --tests "com.droidagentkit.mcp.DroidAgentMcpHttpServerTest"`
Expected: PASS, both tests green.

- [ ] **Step 5: Update the stdio caller in the CLI**

In `cli/src/main/kotlin/com/droidagentkit/cli/DroidAgentMain.kt`, find this block inside `serveMcp` (currently around line 131-133):

```kotlin
        if (command.transport == "stdio") {
            val stdio = DroidAgentStdioServer(dispatcher)
            generateSequence(::readLine).forEach { println(stdio.runOnce(it)) }
        } else {
```

Replace it with:

```kotlin
        if (command.transport == "stdio") {
            val stdio = DroidAgentStdioServer(dispatcher)
            generateSequence(::readLine).forEach { line ->
                stdio.runOnce(line)?.let { println(it) }
            }
        } else {
```

- [ ] **Step 6: Run the full mcp-server and cli test suites**

Run: `./gradlew :mcp-server:test :cli:test`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Manually verify the stdio wiring end-to-end**

Run: `./gradlew :cli:installDist` then:

```bash
printf '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05"}}\n{"jsonrpc":"2.0","method":"notifications/initialized"}\n{"jsonrpc":"2.0","id":2,"method":"tools/list"}\n' | ./cli/build/install/droidagent/bin/droidagent serve-mcp --transport stdio --project auto
```

Expected: exactly two lines of output (the `initialize` response and the `tools/list` response) — no `null` line for the notification in between.

- [ ] **Step 8: Commit**

```bash
git add mcp-server/src/main/kotlin/com/droidagentkit/mcp/DroidAgentMcpServer.kt mcp-server/src/test/kotlin/com/droidagentkit/mcp/DroidAgentMcpHttpServerTest.kt cli/src/main/kotlin/com/droidagentkit/cli/DroidAgentMain.kt
git commit -m "fix(mcp-server): wire stdio and http transports through the JSON-RPC handler"
```

---

### Task 3: Fix stale `--targets` help text

**Files:**
- Modify: `cli/src/main/kotlin/com/droidagentkit/cli/CliCommandSpec.kt:83`

**Interfaces:**
- Consumes: nothing from Tasks 1-2.
- Produces: nothing consumed by later tasks — this is a standalone doc-string fix bundled into this plan per the design doc.

- [ ] **Step 1: Confirm current text**

Run: `grep -n "Comma-separated: codex" cli/src/main/kotlin/com/droidagentkit/cli/CliCommandSpec.kt`
Expected: one match, reading `CliOption("--targets", "Comma-separated: codex, claude, generic, all. Defaults to all."),`

- [ ] **Step 2: Fix the string**

In `cli/src/main/kotlin/com/droidagentkit/cli/CliCommandSpec.kt`, replace:

```kotlin
                    CliOption("--targets", "Comma-separated: codex, claude, generic, all. Defaults to all."),
```

with:

```kotlin
                    CliOption("--targets", "Comma-separated: codex, claude, generic, cursor, zed, vscode, all. Defaults to all."),
```

- [ ] **Step 3: Verify no test asserts the old string**

Run: `grep -rn "Comma-separated: codex" cli/src/test/`
Expected: no matches (confirmed during planning — no existing test pins this exact string).

- [ ] **Step 4: Run the CLI test suite and the CLI's own --help output**

Run: `./gradlew :cli:test`
Expected: `BUILD SUCCESSFUL`.

Run: `./gradlew :cli:installDist && ./cli/build/install/droidagent/bin/droidagent install-mcp --help`
Expected: the `--targets` line now reads `Comma-separated: codex, claude, generic, cursor, zed, vscode, all. Defaults to all.`

- [ ] **Step 5: Commit**

```bash
git add cli/src/main/kotlin/com/droidagentkit/cli/CliCommandSpec.kt
git commit -m "fix(cli): update install-mcp --targets help text to list all 6 targets"
```

---

## Self-Review Notes

- **Spec coverage:** the design doc's handler logic table (initialize, notifications/initialized, tools/list, tools/call, unknown method, parse error, invalid request) is fully covered by Task 1's 10 tests. The stdio/HTTP transport wiring section is covered by Task 2. The bundled help-text fix is Task 3.
- **No placeholders:** every step has complete code; the manual verification command in Task 2 Step 7 is a real, exact command with a stated expected outcome.
- **Type/name consistency:** `McpJsonRpcHandler(dispatcher: DroidAgentMcpDispatcher)` and `fun handle(rawMessage: String): String?` are identical between Task 1 (where it's defined) and Task 2 (where it's consumed by both transport classes). `DroidAgentStdioServer.runOnce`'s return type change (`String` → `String?`) is introduced and consumed within the same task (Task 2), so no cross-task signature drift.
- **Out-of-scope items from the design doc** (SSE/streaming transport, `resources`/`prompts` capabilities) are intentionally not tasked — confirmed no test or step above implies them.
