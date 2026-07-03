package com.droidagentkit.cli

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

enum class McpInstallTarget {
    CODEX,
    CLAUDE,
    GENERIC,
    CURSOR,
    ZED,
    VSCODE,
}

data class McpInstallOptions(
    val targets: Set<McpInstallTarget>,
    val binPath: Path,
    val dryRun: Boolean,
    val applyClaude: Boolean,
)

data class McpInstallResult(
    val messages: List<String>,
    val changedFiles: List<Path>,
    val genericJson: String,
)

class McpInstaller(
    private val home: Path = Path.of(System.getProperty("user.home")),
    private val osName: String = System.getProperty("os.name"),
    private val commandExecutor: (List<String>) -> Int = { command ->
        ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
            .waitFor()
    },
) {
    fun install(options: McpInstallOptions): McpInstallResult {
        val messages = mutableListOf<String>()
        val changed = mutableListOf<Path>()
        val generic = genericJson(options.binPath)

        if (McpInstallTarget.CODEX in options.targets) {
            val path = home.resolve(".codex/config.toml")
            val newText = installCodexBlock(path, options.binPath)
            if (!options.dryRun) {
                Files.createDirectories(path.parent)
                Files.writeString(path, newText)
                changed.add(path)
            }
            messages += "Codex user MCP config ${if (options.dryRun) "would be updated" else "updated"} at $path"
        }

        if (McpInstallTarget.CLAUDE in options.targets) {
            val command = claudeCommand(options.binPath)
            if (options.applyClaude && !options.dryRun) {
                val exit = runCatching { commandExecutor(command) }.getOrElse { -1 }
                if (exit == 0) {
                    messages += "Claude Code user-scope MCP server installed with claude mcp add."
                } else {
                    messages += "Claude Code command could not be completed automatically. Run manually: ${command.joinToString(" ")}"
                }
            } else {
                messages += "Claude Code user-scope install command: ${command.joinToString(" ")}"
            }
        }

        if (McpInstallTarget.GENERIC in options.targets) {
            messages += "Generic MCP stdio config:\n$generic"
        }

        if (McpInstallTarget.CURSOR in options.targets) {
            installJsonTarget("Cursor", cursorConfigPath(), "mcpServers", cursorServerConfig(options.binPath), options.dryRun, messages, changed)
        }

        if (McpInstallTarget.ZED in options.targets) {
            installJsonTarget("Zed", zedConfigPath(), "context_servers", zedServerConfig(options.binPath), options.dryRun, messages, changed)
        }

        if (McpInstallTarget.VSCODE in options.targets) {
            installJsonTarget("VS Code", vsCodeConfigPath(), "servers", vsCodeServerConfig(options.binPath), options.dryRun, messages, changed)
        }

        return McpInstallResult(messages, changed, generic)
    }

    private fun installJsonTarget(
        label: String,
        path: Path,
        topLevelKey: String,
        serverConfig: JsonObject,
        dryRun: Boolean,
        messages: MutableList<String>,
        changed: MutableList<Path>,
    ) {
        val existingJson = if (path.exists()) Files.readString(path) else ""
        val merged = runCatching { McpJsonConfigMerger.merge(existingJson, topLevelKey, "droidagentkit", serverConfig) }
        merged.fold(
            onSuccess = { newText ->
                if (!dryRun) {
                    Files.createDirectories(path.parent)
                    Files.writeString(path, newText)
                    changed.add(path)
                }
                messages += "$label user MCP config ${if (dryRun) "would be updated" else "updated"} at $path"
            },
            onFailure = { error ->
                messages += "Could not update $path: ${error.message ?: "invalid JSON"}. Check the file manually."
            },
        )
    }

    fun installCodexBlock(path: Path, binPath: Path): String {
        val existing = if (path.exists()) Files.readString(path) else ""
        val block = codexBlock(binPath)
        val pattern = Regex("(?s)\\n?# >>> droidagentkit mcp >>>.*?# <<< droidagentkit mcp <<<\\n?")
        val withoutOld = existing.replace(pattern, "\n").trimEnd()
        return buildString {
            if (withoutOld.isNotBlank()) {
                append(withoutOld)
                appendLine()
                appendLine()
            }
            append(block)
            appendLine()
        }
    }

    private fun codexBlock(binPath: Path): String =
        """
        # >>> droidagentkit mcp >>>
        [mcp_servers.droidagentkit]
        command = "${binPath.toString().escapeToml()}"
        args = ["serve-mcp", "--transport", "stdio", "--project", "auto"]
        # <<< droidagentkit mcp <<<
        """.trimIndent()

    fun claudeCommand(binPath: Path): List<String> = listOf(
        "claude",
        "mcp",
        "add",
        "--scope",
        "user",
        "--transport",
        "stdio",
        "droidagentkit",
        "--",
        binPath.toString(),
        "serve-mcp",
        "--transport",
        "stdio",
        "--project",
        "auto",
    )

    fun genericJson(binPath: Path): String =
        """
        {
          "mcpServers": {
            "droidagentkit": {
              "type": "stdio",
              "command": "${binPath.toString().escapeJson()}",
              "args": ["serve-mcp", "--transport", "stdio", "--project", "auto"]
            }
          }
        }
        """.trimIndent()

    private fun cursorConfigPath(): Path = home.resolve(".cursor/mcp.json")

    private fun zedConfigPath(): Path =
        if (osName.lowercase().contains("win")) appDataPath().resolve("Zed/settings.json") else home.resolve(".config/zed/settings.json")

    private fun vsCodeConfigPath(): Path {
        val name = osName.lowercase()
        return when {
            name.contains("win") -> appDataPath().resolve("Code/User/mcp.json")
            name.contains("mac") -> home.resolve("Library/Application Support/Code/User/mcp.json")
            else -> home.resolve(".config/Code/User/mcp.json")
        }
    }

    private fun appDataPath(): Path = home.resolve("AppData/Roaming")

    private fun serveArgsArray(): JsonArray =
        JsonArray(listOf("serve-mcp", "--transport", "stdio", "--project", "auto").map(::JsonPrimitive))

    private fun cursorServerConfig(binPath: Path): JsonObject = buildJsonObject {
        put("command", binPath.toString())
        put("args", serveArgsArray())
    }

    private fun zedServerConfig(binPath: Path): JsonObject = buildJsonObject {
        put("command", binPath.toString())
        put("args", serveArgsArray())
        put("env", buildJsonObject { })
    }

    private fun vsCodeServerConfig(binPath: Path): JsonObject = buildJsonObject {
        put("type", "stdio")
        put("command", binPath.toString())
        put("args", serveArgsArray())
    }

    private fun String.escapeToml(): String = replace("\\", "\\\\").replace("\"", "\\\"")

    private fun String.escapeJson(): String = replace("\\", "\\\\").replace("\"", "\\\"")
}

object McpInstallTargets {
    fun parse(values: List<String>): Set<McpInstallTarget> {
        val expanded = values.flatMap { if (it == "all") listOf("codex", "claude", "generic", "cursor", "zed", "vscode") else listOf(it) }
        return expanded.mapNotNull {
            when (it.lowercase()) {
                "codex" -> McpInstallTarget.CODEX
                "claude", "claude-code" -> McpInstallTarget.CLAUDE
                "generic", "json" -> McpInstallTarget.GENERIC
                "cursor" -> McpInstallTarget.CURSOR
                "zed" -> McpInstallTarget.ZED
                "vscode" -> McpInstallTarget.VSCODE
                else -> null
            }
        }.toSet().ifEmpty {
            setOf(
                McpInstallTarget.CODEX,
                McpInstallTarget.CLAUDE,
                McpInstallTarget.GENERIC,
                McpInstallTarget.CURSOR,
                McpInstallTarget.ZED,
                McpInstallTarget.VSCODE,
            )
        }
    }
}
