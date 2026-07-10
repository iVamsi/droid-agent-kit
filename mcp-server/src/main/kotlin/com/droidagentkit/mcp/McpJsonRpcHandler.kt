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
internal const val MCP_PROTOCOL_VERSION = "2025-11-25"

private val ERROR_STATUSES = setOf("failed", "blocked", "unsupported")
private val SUPPORTED_PROTOCOL_VERSIONS = setOf(MCP_PROTOCOL_VERSION)

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

        if ((root["jsonrpc"] as? JsonPrimitive)?.content != "2.0") {
            return if (hasId) errorResponse(id, INVALID_REQUEST, "Invalid Request") else null
        }

        if (method == null) {
            return if (hasId) errorResponse(id, INVALID_REQUEST, "Invalid Request") else null
        }

        return when (method) {
            "initialize" -> successResponse(id, handleInitialize(root["params"] as? JsonObject))
            "notifications/initialized" -> null
            "notifications/cancelled" -> null
            "ping" -> successResponse(id, emptyMap())
            "tools/list" -> successResponse(id, handleToolsList())
            "tools/call" -> handleToolsCall(id, root["params"] as? JsonObject)
            else -> if (hasId) errorResponse(id, METHOD_NOT_FOUND, "Method not found: $method") else null
        }
    }

    private fun handleInitialize(params: JsonObject?): Map<String, Any?> {
        val requestedVersion = (params?.get("protocolVersion") as? JsonPrimitive)?.content
        val protocolVersion = requestedVersion?.takeIf { it in SUPPORTED_PROTOCOL_VERSIONS } ?: MCP_PROTOCOL_VERSION
        return mapOf(
            "protocolVersion" to protocolVersion,
            "capabilities" to mapOf("tools" to emptyMap<String, Any?>()),
            "serverInfo" to mapOf("name" to SERVER_NAME, "version" to SERVER_VERSION),
            "instructions" to "Use DroidAgentKit only for the project root selected when this server started.",
        )
    }

    private fun handleToolsList(): Map<String, Any?> =
        mapOf(
            "tools" to
                dispatcher.listTools().map {
                    mapOf(
                        "name" to it.name,
                        "title" to it.title,
                        "description" to it.description,
                        "inputSchema" to it.inputSchema,
                        "outputSchema" to it.outputSchema,
                    )
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
                "structuredContent" to result,
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
