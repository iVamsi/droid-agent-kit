# DroidAgentKit — MCP Protocol Fix Design

Date: 2026-07-05
Status: Approved

---

## Problem

`DroidAgentStdioServer.runOnce()` and `DroidAgentMcpHttpServer` (both in
`mcp-server/src/main/kotlin/com/droidagentkit/mcp/DroidAgentMcpServer.kt`) do not implement the MCP
wire protocol. Each one regex-extracts a `"name"` field from the raw request and calls the tool
dispatcher directly:

```kotlin
class DroidAgentStdioServer(...) {
    fun runOnce(line: String): String {
        val tool = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(line)?.groupValues?.get(1)
            ?: return Json.write(mapOf("status" to "failed", "summary" to "Missing tool name."))
        return Json.write(dispatcher.call(tool, emptyMap()))
    }
}
```

Real MCP clients (Claude Code, Cursor, Codex, Zed, VS Code, and Android Studio over HTTP) speak
JSON-RPC 2.0 and require an `initialize` / `notifications/initialized` handshake before any tool
calls, plus `tools/list` and `tools/call` methods with envelopes carrying `jsonrpc`, `id`, `method`,
`params`. None of that exists today. Confirmed by hand: sending a real `initialize` request to the
stdio server returns `{"status":"unsupported","summary":"Unknown MCP tool: initialize", ...}` instead
of a valid handshake response — every `install-mcp` target and the documented Android Studio HTTP
setup fail to connect as a direct result. There is zero existing test coverage on either server class.

This predates the current session's work — it is a foundational gap in `mcp-server`, not a regression
from any of the OSS-hygiene or IDE-support workstreams.

## Architecture

Add one new shared component: `McpJsonRpcHandler`
(`mcp-server/src/main/kotlin/com/droidagentkit/mcp/McpJsonRpc.kt`). It owns all protocol logic. Both
`DroidAgentStdioServer` and `DroidAgentMcpHttpServer` become thin transport adapters — read bytes in,
delegate to the handler, write bytes out. This keeps the two transports' actual responsibility (framing:
line-based for stdio, HTTP request/response for HTTP) separate from protocol logic (JSON-RPC routing,
which is identical for both).

**Parsing/serialization split:** incoming messages are parsed with `kotlinx.serialization.json`
(already a `mcp-server` dependency, added in the earlier new-MCP-tools workstream for SARIF parsing) —
the hand-rolled `com.droidagentkit.core.Json` object only writes JSON, it never parses. Outgoing
responses continue to use the existing `Json.write()`, which already recursively serializes
`Map`/`List`/`String`/`Number`/`Boolean`/`null`/`Enum` — building a response is just constructing the
right `Map<String, Any?>` shape and handing it to `Json.write()`. A small converter,
`JsonElement.toKotlinValue(): Any?`, bridges the two: it recursively converts a parsed `JsonElement`
(`JsonObject`, `JsonArray`, `JsonPrimitive`) into the plain Kotlin values (`Map`, `List`, `String`,
`Long`/`Double`, `Boolean`, `null`) that `Json.write()` and the dispatcher's `Map<String, Any?>`
argument type already expect. This one converter is used for two things: echoing a request's `id`
verbatim in its response (which must preserve whether the client sent a string, a number, or omitted
it), and converting `tools/call`'s `params.arguments` object into the dispatcher's expected argument
map.

## Handler logic

`McpJsonRpcHandler.handle(rawMessage: String): String?` — returns `null` when no response should be
sent (JSON-RPC notifications never get a response; this is different from an empty-but-present
response).

| Input | Response |
|---|---|
| `method: "initialize"` | `{"jsonrpc":"2.0","id":<echoed>,"result":{"protocolVersion":<echoed from request params>,"capabilities":{"tools":{}},"serverInfo":{"name":"droidagentkit","version":"0.1.0-alpha"}}}` |
| `method: "notifications/initialized"` | `null` (no response — this is a notification, not a request) |
| `method: "tools/list"` | `{"jsonrpc":"2.0","id":<echoed>,"result":{"tools":[{"name","description","inputSchema"}, ...]}}` sourced directly from `dispatcher.listTools()` |
| `method: "tools/call"` | Parses `params.name` (required string) and `params.arguments` (optional object, defaults to empty), calls `dispatcher.call(name, arguments)`, wraps the resulting `Map<String, Any>` as `{"jsonrpc":"2.0","id":<echoed>,"result":{"content":[{"type":"text","text":<JSON string of the tool result map>}],"isError":<true if the tool result's "status" is "failed", "blocked", or "unsupported">}}` |
| Unknown/unimplemented method | `{"jsonrpc":"2.0","id":<echoed>,"error":{"code":-32601,"message":"Method not found: <method>"}}` |
| Body is not valid JSON | `{"jsonrpc":"2.0","id":null,"error":{"code":-32700,"message":"Parse error"}}` |
| Valid JSON but missing `method` | `{"jsonrpc":"2.0","id":<echoed or null>,"error":{"code":-32600,"message":"Invalid Request"}}` |

The `initialize` response's `protocolVersion` is copied verbatim from the request's
`params.protocolVersion` rather than hardcoded. The tool surface exposed here doesn't depend on any
version-specific MCP protocol feature, so confirming whatever version the client asks for maximizes
compatibility across client versions without risking a mismatch against a hardcoded value.

## Transport wiring

**Stdio:** `DroidAgentStdioServer.runOnce(line: String): String?` becomes a thin call into
`McpJsonRpcHandler.handle(line)`. The caller in `DroidAgentMain.serveMcp()` currently does
`generateSequence(::readLine).forEach { println(stdio.runOnce(it)) }`, which would print the literal
text `"null"` to stdout for every notification (corrupting the stream, since stdout must carry only
valid JSON-RPC messages, one per line). This changes to only print when the result is non-null:

```kotlin
generateSequence(::readLine).forEach { line ->
    stdio.runOnce(line)?.let { println(it) }
}
```

**HTTP:** `DroidAgentMcpHttpServer`'s POST path reads the body, calls
`McpJsonRpcHandler.handle(body)`, and writes the result as the response body with `Content-Type:
application/json` and status 200; if the handler returns `null` (a notification), it responds 202 with
an empty body (a valid response — HTTP still requires *a* response, but JSON-RPC requires no
*message*). The existing bearer-token check runs unchanged, before the body is even read. The existing
GET path (an informal raw tool-listing endpoint, not part of the JSON-RPC protocol) is left as-is —
real clients only POST per the Streamable HTTP transport shape Android Studio's config implies
(`httpUrl` + `Authorization` header), so this GET endpoint remains purely a manual-debugging
convenience, documented as such if not already.

## Testing

- Unit tests directly against `McpJsonRpcHandler.handle()`, using real JSON strings (no mocks),
  covering: `initialize` (verify echoed protocolVersion, correct `serverInfo`), `notifications/initialized`
  (verify `null` return), `tools/list` (verify it matches `dispatcher.listTools()`), `tools/call` success
  case, `tools/call` against an unsupported tool name (verify `isError: true`), malformed JSON (`-32700`),
  valid JSON missing `method` (`-32600`), and an unknown method (`-32601`).
- One HTTP round-trip integration test: start a real `DroidAgentMcpHttpServer` on an ephemeral port,
  issue a real `HttpURLConnection` POST with a valid `initialize` body and the correct bearer token,
  assert on the real response body and status code.
- Stdio's read loop itself is not separately integration-tested — Kotlin's `readLine()` reads directly
  from process stdin and can't be redirected in-process without reflection hacks that aren't worth the
  complexity here. The handler-level tests cover its logic completely; the wiring is a two-line
  pass-through already manually verified (the `initialize` handshake test run during this design's
  investigation).

## Also bundled: stale `--targets` help text

`CliCommandSpec.kt`'s `--targets` flag description still reads "Comma-separated: codex, claude,
generic, all." — it was never updated when Cursor/Zed/VS Code support was added (the actual parsing
in `McpInstallTargets.parse()` already supports them; only the help string is stale). Fix: update the
description string to `"Comma-separated: codex, claude, generic, cursor, zed, vscode, all."`.

## Out of scope

- SSE / streaming HTTP transport (the Streamable HTTP spec's optional GET-based server-push stream) —
  not needed for the single-request/response tool-calling this project's tools do.
- An MCP `resources` or `prompts` capability — this server only exposes `tools`, unchanged from today.
- Any change to `DroidAgentMcpDispatcher`'s tool set, schemas, or behavior — this fix is purely about
  making the existing tools reachable over a spec-compliant transport.
