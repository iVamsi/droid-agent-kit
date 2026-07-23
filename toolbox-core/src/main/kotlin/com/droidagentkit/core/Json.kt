package com.droidagentkit.core

object Json {
    fun writeToolResult(result: ToolResult): String =
        write(
            mapOf(
                "schemaVersion" to result.schemaVersion,
                "status" to result.status.wireName,
                "summary" to result.summary,
                "artifacts" to result.artifacts.map(::artifactToMap),
                "redactionsApplied" to result.redactionsApplied,
                "warnings" to result.warnings,
            ),
        )

    fun artifactToMap(artifact: ArtifactRef): Map<String, Any> =
        mapOf(
            "type" to artifact.type.wireName,
            "path" to artifact.path,
            "mimeType" to artifact.mimeType,
            "description" to artifact.description,
            "sizeBytes" to artifact.sizeBytes,
            "sha256" to artifact.sha256,
            "sensitivity" to artifact.sensitivity.wireName,
            "opaqueId" to artifact.opaqueId,
        )

    fun write(value: Any?): String =
        when (value) {
            null -> "null"
            is String -> "\"" + value.escapeJson() + "\""
            is Number, is Boolean -> value.toString()
            is Enum<*> -> write(value.name.lowercase())
            is Map<*, *> ->
                value.entries.sortedBy { it.key.toString() }.joinToString(prefix = "{", postfix = "}") { (key, item) ->
                    write(key.toString()) + ":" + write(item)
                }
            is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { write(it) }
            else -> write(value.toString())
        }

    private fun String.escapeJson(): String =
        buildString {
            for (char in this@escapeJson) {
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
        }
}
