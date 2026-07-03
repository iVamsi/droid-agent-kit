package com.droidagentkit.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

object McpJsonConfigMerger {
    private val prettyJson = Json { prettyPrint = true }

    fun merge(existingJson: String, topLevelKey: String, serverName: String, serverConfig: JsonObject): String {
        val root = if (existingJson.isBlank()) {
            buildJsonObject { }
        } else {
            Json.parseToJsonElement(JsonCommentStripper.strip(existingJson)).jsonObject
        }
        val existingServers = (root[topLevelKey] as? JsonObject) ?: buildJsonObject { }
        val updatedServers = buildJsonObject {
            existingServers.forEach { (name, config) -> put(name, config) }
            put(serverName, serverConfig)
        }
        val updatedRoot = buildJsonObject {
            root.forEach { (key, value) -> if (key != topLevelKey) put(key, value) }
            put(topLevelKey, updatedServers)
        }
        return prettyJson.encodeToString(JsonObject.serializer(), updatedRoot)
    }
}

private object JsonCommentStripper {
    fun strip(input: String): String {
        val output = StringBuilder(input.length)
        var i = 0
        var inString = false
        var escaped = false
        var pendingComma = false

        fun flushPendingComma() {
            if (pendingComma) {
                output.append(',')
                pendingComma = false
            }
        }

        while (i < input.length) {
            val c = input[i]
            if (inString) {
                output.append(c)
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                i++
                continue
            }
            when {
                c == '"' -> {
                    flushPendingComma()
                    inString = true
                    output.append(c)
                    i++
                }
                c == '/' && i + 1 < input.length && input[i + 1] == '/' -> {
                    i += 2
                    while (i < input.length && input[i] != '\n') i++
                }
                c == '/' && i + 1 < input.length && input[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < input.length && !(input[i] == '*' && input[i + 1] == '/')) i++
                    i += 2
                }
                c == ',' -> {
                    flushPendingComma()
                    pendingComma = true
                    i++
                }
                c.isWhitespace() -> {
                    output.append(c)
                    i++
                }
                c == '}' || c == ']' -> {
                    pendingComma = false
                    output.append(c)
                    i++
                }
                else -> {
                    flushPendingComma()
                    output.append(c)
                    i++
                }
            }
        }
        flushPendingComma()
        return output.toString()
    }
}
