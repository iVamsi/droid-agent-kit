package com.droidagentkit.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

enum class McpInstallTarget {
    CODEX,
    CLAUDE,
    GENERIC,
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

        return McpInstallResult(messages, changed, generic)
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

    private fun String.escapeToml(): String = replace("\\", "\\\\").replace("\"", "\\\"")

    private fun String.escapeJson(): String = replace("\\", "\\\\").replace("\"", "\\\"")
}

object McpInstallTargets {
    fun parse(values: List<String>): Set<McpInstallTarget> {
        val expanded = values.flatMap { if (it == "all") listOf("codex", "claude", "generic") else listOf(it) }
        return expanded.mapNotNull {
            when (it.lowercase()) {
                "codex" -> McpInstallTarget.CODEX
                "claude", "claude-code" -> McpInstallTarget.CLAUDE
                "generic", "json" -> McpInstallTarget.GENERIC
                else -> null
            }
        }.toSet().ifEmpty { setOf(McpInstallTarget.CODEX, McpInstallTarget.CLAUDE, McpInstallTarget.GENERIC) }
    }
}
