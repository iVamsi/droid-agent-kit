package com.droidagentkit.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

object McpJsonConfigMerger {
    private val prettyJson = Json { prettyPrint = true }

    fun merge(existingJson: String, topLevelKey: String, serverName: String, serverConfig: JsonObject): String {
        val root = if (existingJson.isBlank()) buildJsonObject { } else Json.parseToJsonElement(existingJson).jsonObject
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
