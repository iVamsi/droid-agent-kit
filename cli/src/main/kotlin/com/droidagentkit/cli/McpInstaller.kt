package com.droidagentkit.cli

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.util.Base64
import kotlin.io.path.exists

enum class McpInstallTarget {
    CODEX,
    CLAUDE,
    GENERIC,
    CURSOR,
    ZED,
    VSCODE,
    ANDROID_STUDIO,
}

data class McpInstallOptions(
    val targets: Set<McpInstallTarget>,
    val binPath: Path,
    val projectRoot: Path = Path.of("").toAbsolutePath().normalize(),
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
            installJsonTarget(
                "Cursor",
                cursorConfigPath(),
                "mcpServers",
                cursorServerConfig(options.binPath),
                options.dryRun,
                messages,
                changed,
            )
        }

        if (McpInstallTarget.ZED in options.targets) {
            installJsonTarget(
                "Zed",
                zedConfigPath(),
                "context_servers",
                zedServerConfig(options.binPath),
                options.dryRun,
                messages,
                changed,
            )
        }

        if (McpInstallTarget.VSCODE in options.targets) {
            installJsonTarget(
                "VS Code",
                vsCodeConfigPath(),
                "servers",
                vsCodeServerConfig(options.binPath),
                options.dryRun,
                messages,
                changed,
            )
        }

        if (McpInstallTarget.ANDROID_STUDIO in options.targets) {
            installAndroidStudio(options, messages, changed)
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
                    Files.writeString(path, "$newText\n")
                    changed.add(path)
                }
                messages += "$label user MCP config ${if (dryRun) "would be updated" else "updated"} at $path"
            },
            onFailure = { error ->
                messages += "Could not update $path: ${error.message ?: "invalid JSON"}. Check the file manually."
            },
        )
    }

    fun installCodexBlock(
        path: Path,
        binPath: Path,
    ): String {
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

    fun claudeCommand(binPath: Path): List<String> =
        listOf(
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

    private fun installAndroidStudio(
        options: McpInstallOptions,
        messages: MutableList<String>,
        changed: MutableList<Path>,
    ) {
        val projectRoot = options.projectRoot.toAbsolutePath().normalize()
        val port = 8765
        val stateDirectory = home.resolve(".droidagentkit/android-studio")
        val tokenFile = stateDirectory.resolve("bearer-token")
        val configPaths = androidStudioConfigPaths()

        if (configPaths.isEmpty()) {
            messages += "No Android Studio configuration directory was found. Open Android Studio once, then rerun install-mcp."
            return
        }

        val token = readOrCreateToken(tokenFile, options.dryRun, changed)
        val serverConfig = androidStudioServerConfig(port, token)
        configPaths.forEach { path ->
            installJsonTarget(
                "Android Studio",
                path,
                "mcpServers",
                serverConfig,
                options.dryRun,
                messages,
                changed,
            )
        }

        if (osName.lowercase().contains("mac")) {
            installMacLaunchAgent(
                options,
                projectRoot,
                port,
                tokenFile,
                stateDirectory,
                messages,
                changed,
            )
        } else {
            val command = androidStudioServeCommand(options.binPath, projectRoot, port, tokenFile)
            messages += "Start the Android Studio MCP service after sign-in: ${command.joinToString(" ")}"
        }
    }

    private fun androidStudioConfigPaths(): List<Path> {
        val googleDirectory =
            when {
                osName.lowercase().contains("win") -> appDataPath().resolve("Google")
                osName.lowercase().contains("mac") -> home.resolve("Library/Application Support/Google")
                else -> home.resolve(".config/Google")
            }
        if (!googleDirectory.exists()) return emptyList()
        return Files.list(googleDirectory).use { paths ->
            paths
                .filter { Files.isDirectory(it) && it.fileName.toString().startsWith("AndroidStudio") }
                .map { it.resolve("mcp.json") }
                .sorted()
                .toList()
        }
    }

    private fun readOrCreateToken(
        tokenFile: Path,
        dryRun: Boolean,
        changed: MutableList<Path>,
    ): String {
        val existing =
            if (tokenFile.exists()) {
                Files.readString(tokenFile).trim().takeIf { it.matches(Regex("[A-Za-z0-9_-]{32,}")) }
            } else {
                null
            }
        if (existing != null) return existing

        val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        if (!dryRun) {
            Files.createDirectories(tokenFile.parent)
            Files.writeString(tokenFile, "$token\n")
            runCatching {
                Files.setPosixFilePermissions(
                    tokenFile,
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                )
            }
            changed.add(tokenFile)
        }
        return token
    }

    private fun installMacLaunchAgent(
        options: McpInstallOptions,
        projectRoot: Path,
        port: Int,
        tokenFile: Path,
        stateDirectory: Path,
        messages: MutableList<String>,
        changed: MutableList<Path>,
    ) {
        val label = "com.droidagentkit.mcp.android-studio"
        val plist = home.resolve("Library/LaunchAgents/$label.plist")
        val arguments = androidStudioServeCommand(options.binPath, projectRoot, port, tokenFile)
        val xmlArguments = arguments.joinToString("\n") { "        <string>${it.escapeXml()}</string>" }
        val logFile = stateDirectory.resolve("service.log")
        val content =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>Label</key>
                <string>$label</string>
                <key>ProgramArguments</key>
                <array>
            $xmlArguments
                </array>
                <key>WorkingDirectory</key>
                <string>${projectRoot.toString().escapeXml()}</string>
                <key>RunAtLoad</key>
                <true/>
                <key>KeepAlive</key>
                <true/>
                <key>StandardOutPath</key>
                <string>${logFile.toString().escapeXml()}</string>
                <key>StandardErrorPath</key>
                <string>${logFile.toString().escapeXml()}</string>
            </dict>
            </plist>
            """.trimIndent() + "\n"

        if (options.dryRun) {
            messages += "Android Studio launch agent would be installed at $plist"
            return
        }

        Files.createDirectories(plist.parent)
        Files.createDirectories(stateDirectory)
        Files.writeString(plist, content)
        changed.add(plist)
        runCatching { commandExecutor(listOf("launchctl", "unload", plist.toString())) }
        val exit = runCatching { commandExecutor(listOf("launchctl", "load", "-w", plist.toString())) }.getOrElse { -1 }
        if (exit == 0) {
            messages += "Android Studio MCP service installed and started for $projectRoot on 127.0.0.1:$port."
        } else {
            messages += "Android Studio launch agent was written but could not be started. Run: launchctl load -w $plist"
        }
    }

    private fun androidStudioServeCommand(
        binPath: Path,
        projectRoot: Path,
        port: Int,
        tokenFile: Path,
    ): List<String> =
        listOf(
            binPath.toString(),
            "serve-mcp",
            "--transport",
            "http",
            "--host",
            "127.0.0.1",
            "--port",
            port.toString(),
            "--project",
            projectRoot.toString(),
            "--bearer-token-file",
            tokenFile.toString(),
        )

    private fun androidStudioServerConfig(
        port: Int,
        token: String,
    ): JsonObject =
        buildJsonObject {
            put("httpUrl", "http://127.0.0.1:$port/mcp")
            put("headers", buildJsonObject { put("Authorization", "Bearer $token") })
            put("timeout", 30_000)
            put("enabled", true)
        }

    private fun serveArgsArray(): JsonArray =
        JsonArray(listOf("serve-mcp", "--transport", "stdio", "--project", "auto").map(::JsonPrimitive))

    private fun cursorServerConfig(binPath: Path): JsonObject =
        buildJsonObject {
            put("command", binPath.toString())
            put("args", serveArgsArray())
        }

    private fun zedServerConfig(binPath: Path): JsonObject =
        buildJsonObject {
            put("command", binPath.toString())
            put("args", serveArgsArray())
            put("env", buildJsonObject { })
        }

    private fun vsCodeServerConfig(binPath: Path): JsonObject =
        buildJsonObject {
            put("type", "stdio")
            put("command", binPath.toString())
            put("args", serveArgsArray())
        }

    private fun String.escapeToml(): String = replace("\\", "\\\\").replace("\"", "\\\"")

    private fun String.escapeJson(): String = replace("\\", "\\\\").replace("\"", "\\\"")

    private fun String.escapeXml(): String =
        replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
}

object McpInstallTargets {
    fun parse(values: List<String>): Set<McpInstallTarget> {
        val expanded =
            values.flatMap {
                if (it == "all") {
                    listOf("codex", "claude", "generic", "cursor", "zed", "vscode", "android-studio")
                } else {
                    listOf(it)
                }
            }
        return expanded
            .mapNotNull {
                when (it.lowercase()) {
                    "codex" -> McpInstallTarget.CODEX
                    "claude", "claude-code" -> McpInstallTarget.CLAUDE
                    "generic", "json" -> McpInstallTarget.GENERIC
                    "cursor" -> McpInstallTarget.CURSOR
                    "zed" -> McpInstallTarget.ZED
                    "vscode" -> McpInstallTarget.VSCODE
                    "android-studio", "androidstudio", "studio" -> McpInstallTarget.ANDROID_STUDIO
                    else -> null
                }
            }.toSet()
            .ifEmpty {
                setOf(
                    McpInstallTarget.CODEX,
                    McpInstallTarget.CLAUDE,
                    McpInstallTarget.GENERIC,
                    McpInstallTarget.CURSOR,
                    McpInstallTarget.ZED,
                    McpInstallTarget.VSCODE,
                    McpInstallTarget.ANDROID_STUDIO,
                )
            }
    }
}
