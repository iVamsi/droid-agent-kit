package com.droidagentkit.mcp

import com.droidagentkit.core.CancellationToken
import com.droidagentkit.core.InteractiveConfirmation
import com.droidagentkit.core.Json
import com.droidagentkit.core.ProgressReporter
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
private const val SERVER_VERSION = "0.2.6-alpha"
internal const val MCP_PROTOCOL_VERSION = "2025-11-25"
private const val MAX_MESSAGE_CHARS = 1_048_576
private const val ELICITATION_TIMEOUT_SECONDS = 300L

private val ERROR_STATUSES = setOf("failed", "blocked", "unsupported")
private val SUPPORTED_PROTOCOL_VERSIONS = setOf(MCP_PROTOCOL_VERSION)

class McpJsonRpcHandler(
    private val dispatcher: McpDispatcher,
    /**
     * Sink for server-initiated frames (progress notifications). Defaults to discarding them, which
     * is correct for the HTTP JSON-response transport: it has no channel to push on, so a call
     * there simply produces no progress.
     */
    private val notify: (String) -> Unit = {},
) {
    /**
     * Tokens for calls currently executing, keyed by their JSON-RPC id rendered as a string.
     *
     * Ids are `string | number` on the wire, and a host is free to send `1` for one request and
     * `"1"` for the next, so both normalize to the same key rather than silently failing to match
     * a cancellation to its call.
     */
    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, CancellationToken>()

    /** Server-initiated requests awaiting a client response, keyed by the id we generated. */
    private val pendingElicitations =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<JsonObject?>>()

    private val elicitationIds =
        java.util.concurrent.atomic
            .AtomicLong(0)

    /** Set from the client's `initialize`; elicitation is only attempted when it was advertised. */
    @Volatile
    private var clientSupportsElicitation = false

    /** True for methods that can run long enough to be worth executing off the reader thread. */
    fun isLongRunning(rawMessage: String): Boolean =
        runCatching {
            (KJson.parseToJsonElement(rawMessage).jsonObject["method"] as? JsonPrimitive)?.content == "tools/call"
        }.getOrDefault(false)

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
            // A frame with an id and no method is the client *answering* something we asked --
            // the only server-initiated request we make is elicitation/create.
            if (hasId && (root.containsKey("result") || root.containsKey("error"))) {
                completeElicitation(idKey(id), root["result"] as? JsonObject)
                return null
            }
            return if (hasId) errorResponse(id, INVALID_REQUEST, "Invalid Request") else null
        }

        return when (method) {
            "initialize" -> successResponse(id, handleInitialize(root["params"] as? JsonObject))
            "notifications/initialized" -> null
            "notifications/cancelled" -> {
                handleCancelled(root["params"] as? JsonObject)
                null
            }
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

    /**
     * Cancels the matching in-flight call, if it is still running.
     *
     * A cancellation for an unknown id is ignored rather than answered with an error: the spec
     * treats it as a race the client is allowed to lose, and by the time it arrives the call has
     * usually just finished.
     */
    private fun handleCancelled(params: JsonObject?) {
        val requestId = params?.get("requestId")?.toKotlinValue() ?: return
        inFlight[idKey(requestId)]?.cancel()
    }

    /**
     * Asks the human, through the client, to approve a destructive operation.
     *
     * Returns UNAVAILABLE rather than blocking when the client never advertised elicitation, or
     * when it fails to answer inside the timeout. Failing closed matters here: the caller turns an
     * UNAVAILABLE into a denial, so a silent client blocks the operation instead of waving it
     * through.
     */
    fun elicitConfirmation(
        title: String,
        message: String,
        timeoutSeconds: Long = ELICITATION_TIMEOUT_SECONDS,
    ): InteractiveConfirmation {
        if (!clientSupportsElicitation) return InteractiveConfirmation.UNAVAILABLE
        val id = "dak-elicit-" + elicitationIds.incrementAndGet()
        val future = java.util.concurrent.CompletableFuture<JsonObject?>()
        pendingElicitations[id] = future
        try {
            notify(
                Json.write(
                    mapOf(
                        "jsonrpc" to "2.0",
                        "id" to id,
                        "method" to "elicitation/create",
                        "params" to
                            mapOf(
                                "message" to message,
                                "requestedSchema" to
                                    mapOf(
                                        "type" to "object",
                                        "title" to title,
                                        "properties" to
                                            mapOf(
                                                "approve" to
                                                    mapOf(
                                                        "type" to "boolean",
                                                        "description" to "Approve this destructive operation?",
                                                    ),
                                            ),
                                        "required" to listOf("approve"),
                                    ),
                            ),
                    ),
                ),
            )
            val response =
                runCatching { future.get(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS) }
                    .getOrElse { return InteractiveConfirmation.UNAVAILABLE }
                    ?: return InteractiveConfirmation.UNAVAILABLE

            // The spec distinguishes accept/decline/cancel; only an explicit accept carrying
            // approve=true is treated as consent. Anything else means no.
            val action = (response["action"] as? JsonPrimitive)?.content
            if (action != null && action != "accept") return InteractiveConfirmation.DECLINED
            val approved =
                ((response["content"] as? JsonObject)?.get("approve") as? JsonPrimitive)?.booleanOrNull
                    ?: (response["approve"] as? JsonPrimitive)?.booleanOrNull
                    ?: false
            return if (approved) InteractiveConfirmation.APPROVED else InteractiveConfirmation.DECLINED
        } finally {
            pendingElicitations.remove(id)
        }
    }

    private fun completeElicitation(
        key: String,
        result: JsonObject?,
    ) {
        pendingElicitations[key]?.complete(result)
    }

    private fun handleInitialize(params: JsonObject?): Map<String, Any?> {
        clientSupportsElicitation = (params?.get("capabilities") as? JsonObject)?.containsKey("elicitation") == true
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
        val progressToken = (params["_meta"] as? JsonObject)?.get("progressToken")?.toKotlinValue()
        val token = CancellationToken()
        val key = idKey(id)
        // A notification has no id and so cannot be cancelled; registering it under a shared key
        // would let one notification's cancel abort another's call.
        if (id != null) inFlight[key] = token

        val result =
            try {
                dispatcher.call(
                    name,
                    arguments,
                    ToolCallContext(
                        cancellation = token,
                        progress = progressReporter(progressToken),
                        confirmer = { request ->
                            elicitConfirmation(
                                title = "Confirm destructive operation",
                                message =
                                    buildString {
                                        append("DroidAgentKit wants to run '${request.operationId}'")
                                        request.packageName?.let { append(" on package $it") }
                                        request.deviceSerial?.let { append(" (device $it)") }
                                        append(". This is destructive and cannot be undone. Approve?")
                                    },
                            )
                        },
                    ),
                )
            } finally {
                if (id != null) inFlight.remove(key)
            }
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

    private fun progressReporter(progressToken: Any?): ProgressReporter {
        if (progressToken == null) return ProgressReporter.NONE
        return ProgressReporter { progress, total, message ->
            val params =
                buildMap<String, Any?> {
                    put("progressToken", progressToken)
                    put("progress", progress ?: 0.0)
                    if (total != null) put("total", total)
                    if (message.isNotBlank()) put("message", message)
                }
            notify(Json.write(mapOf("jsonrpc" to "2.0", "method" to "notifications/progress", "params" to params)))
        }
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

/** Normalizes a JSON-RPC id to a stable map key; 1 and "1" must denote the same request. */
private fun idKey(id: Any?): String = id.toString()

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
