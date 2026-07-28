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
private const val RESOURCE_NOT_FOUND = -32002
private const val INVALID_PARAMS = -32602

private const val SERVER_NAME = "droidagentkit"
private const val SERVER_VERSION = "0.2.2-alpha"
internal const val MCP_PROTOCOL_VERSION = "2025-11-25"
private const val MAX_MESSAGE_CHARS = 1_048_576

private val ERROR_STATUSES = setOf("failed", "blocked", "unsupported")
private val SUPPORTED_PROTOCOL_VERSIONS = setOf(MCP_PROTOCOL_VERSION)

class McpJsonRpcHandler(
    private val dispatcher: McpDispatcher,
) {
    fun handle(rawMessage: String): String? {
        if (rawMessage.length > MAX_MESSAGE_CHARS) {
            return errorResponse(null, INVALID_REQUEST, "Request exceeds $MAX_MESSAGE_CHARS character limit")
        }
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
            "resources/list" -> successResponse(id, handleResourcesList())
            "resources/templates/list" -> successResponse(id, handleResourcesTemplatesList())
            "resources/read" -> handleResourcesRead(id, root["params"] as? JsonObject)
            "prompts/list" -> successResponse(id, handlePromptsList())
            "prompts/get" -> handlePromptsGet(id, root["params"] as? JsonObject)
            else -> if (hasId) errorResponse(id, METHOD_NOT_FOUND, "Method not found: $method") else null
        }
    }

    private fun handleInitialize(params: JsonObject?): Map<String, Any?> {
        val requestedVersion = (params?.get("protocolVersion") as? JsonPrimitive)?.content
        val protocolVersion = requestedVersion?.takeIf { it in SUPPORTED_PROTOCOL_VERSIONS } ?: MCP_PROTOCOL_VERSION
        return mapOf(
            "protocolVersion" to protocolVersion,
            "capabilities" to
                mapOf(
                    "tools" to emptyMap<String, Any?>(),
                    "resources" to mapOf("list" to true, "read" to true),
                    "prompts" to mapOf("list" to true, "get" to true),
                ),
            "serverInfo" to mapOf("name" to SERVER_NAME, "version" to SERVER_VERSION),
            "instructions" to dispatcher.instructions,
        )
    }

    private fun handleToolsList(): Map<String, Any?> =
        mapOf(
            "tools" to
                dispatcher.listTools().map {
                    val tool =
                        mapOf(
                            "name" to it.name,
                            "title" to it.title,
                            "description" to it.description,
                            "inputSchema" to it.inputSchema,
                            "outputSchema" to it.outputSchema,
                        )
                    if (it.annotations.isNotEmpty()) tool + ("annotations" to it.annotations) else tool
                },
        )

    private fun handleResourcesList(): Map<String, Any?> =
        mapOf(
            "resources" to
                dispatcher.resourceRegistry().list().map {
                    mapOf(
                        "uri" to it.uri,
                        "name" to it.name,
                        "description" to it.description,
                        "mimeType" to it.mimeType,
                    )
                },
        )

    private fun handleResourcesTemplatesList(): Map<String, Any?> =
        mapOf(
            "resourceTemplates" to
                dispatcher.resourceRegistry().listTemplates().map {
                    mapOf(
                        "uriTemplate" to it.uriTemplate,
                        "name" to it.name,
                        "description" to it.description,
                        "mimeType" to it.mimeType,
                    )
                },
        )

    private fun handleResourcesRead(
        id: Any?,
        params: JsonObject?,
    ): String {
        val uri =
            (params?.get("uri") as? JsonPrimitive)?.content
                ?: return errorResponse(id, INVALID_PARAMS, "Invalid Request: params.uri is required")
        return when (val result = dispatcher.resourceRegistry().read(uri)) {
            is ResourceReadResult.Found ->
                successResponse(
                    id,
                    mapOf(
                        "contents" to
                            listOf(
                                mapOf(
                                    "uri" to result.uri,
                                    "mimeType" to result.mimeType,
                                    "text" to result.text,
                                ),
                            ),
                    ),
                )
            is ResourceReadResult.NotFound -> errorResponse(id, RESOURCE_NOT_FOUND, "Resource not found: ${result.uri}")
        }
    }

    private fun handlePromptsList(): Map<String, Any?> =
        mapOf(
            "prompts" to
                dispatcher.promptRegistry().list(dispatcher.exposedToolNames()).map {
                    mapOf(
                        "name" to it.name,
                        "description" to it.description,
                        "arguments" to
                            it.arguments.map { a ->
                                mapOf("name" to a.name, "description" to a.description, "required" to a.required)
                            },
                    )
                },
        )

    private fun handlePromptsGet(
        id: Any?,
        params: JsonObject?,
    ): String {
        val name =
            (params?.get("name") as? JsonPrimitive)?.content
                ?: return errorResponse(id, INVALID_PARAMS, "Invalid Request: params.name is required")
        val prompt =
            dispatcher.promptRegistry().get(name, dispatcher.exposedToolNames())
                ?: return errorResponse(id, METHOD_NOT_FOUND, "Prompt not found: $name")
        val args =
            (params["arguments"] as? JsonObject)?.entries?.associate { it.key to (it.value as? JsonPrimitive)?.content.orEmpty() }
                ?: emptyMap()
        return successResponse(id, mapOf("description" to prompt.description, "messages" to prompt.build(args)))
    }

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
