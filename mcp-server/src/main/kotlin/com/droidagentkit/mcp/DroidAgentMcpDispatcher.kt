package com.droidagentkit.mcp

import com.droidagentkit.core.ArtifactWriter
import com.droidagentkit.core.DroidAgentConfig
import com.droidagentkit.core.Json
import com.droidagentkit.core.ProcessRunner
import com.droidagentkit.core.Redactor
import com.droidagentkit.core.ResultStatus
import com.droidagentkit.core.ToolResult
import com.droidagentkit.inspector.AndroidProjectInspector
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: Map<String, Any>,
)

class DroidAgentMcpDispatcher(
    private val config: DroidAgentConfig,
    private val inspector: AndroidProjectInspector = AndroidProjectInspector(),
) {
    fun listTools(): List<McpTool> = listOf(
        McpTool(
            name = "android_project_inspect",
            description = "Inspect Android Gradle modules, versions, manifests, and safe commands.",
            inputSchema = schema(props = mapOf("rootPath" to rootPathProp)),
        ),
        McpTool(
            name = "android_gradle_run",
            description = "Run a configured allowlisted Gradle task and capture redacted logs.",
            inputSchema = schema(
                "task",
                props = mapOf(
                    "rootPath" to rootPathProp,
                    "task" to str("Gradle task to run (must match the configured allowlist)."),
                    "arguments" to arrStr("Extra Gradle arguments to append."),
                    "rerunTasks" to bool("Pass --rerun-tasks to Gradle."),
                    "stacktrace" to bool("Pass --stacktrace to Gradle."),
                    "timeoutSeconds" to num("Override command timeout in seconds."),
                ),
            ),
        ),
        McpTool(
            name = "android_devices_list",
            description = "List adb devices and basic status.",
            inputSchema = schema(props = mapOf("rootPath" to rootPathProp)),
        ),
        McpTool(
            name = "android_app_install",
            description = "Install an APK when app install is enabled.",
            inputSchema = schema(
                "apkPath", "deviceSerial",
                props = mapOf(
                    "rootPath" to rootPathProp,
                    "apkPath" to str("Absolute path to the APK file to install."),
                    "deviceSerial" to deviceSerialProp,
                    "reinstall" to bool("Pass -r to adb install to reinstall keeping data."),
                ),
            ),
        ),
        McpTool(
            name = "android_app_launch",
            description = "Launch an Android package/activity on an explicit device.",
            inputSchema = schema(
                "deviceSerial", "packageName",
                props = mapOf(
                    "deviceSerial" to deviceSerialProp,
                    "packageName" to str("Android package name (e.g. com.example.app)."),
                    "activityName" to str("Fully qualified activity name. Omit to use the default launcher."),
                ),
            ),
        ),
        McpTool(
            name = "android_logcat_capture",
            description = "Capture redacted logcat output for a device or package.",
            inputSchema = schema(
                "deviceSerial",
                props = mapOf(
                    "deviceSerial" to deviceSerialProp,
                    "maxLines" to num("Maximum number of log lines to capture. Default: 500."),
                ),
            ),
        ),
        McpTool(
            name = "android_screen_snapshot",
            description = "Capture screenshot and UIAutomator XML from an explicit device.",
            inputSchema = schema(
                "deviceSerial",
                props = mapOf(
                    "deviceSerial" to deviceSerialProp,
                    "outputName" to str("Base name for the output artifact. Optional."),
                ),
            ),
        ),
        McpTool(
            name = "android_report_bundle",
            description = "Create an agent-readable Android diagnostic report bundle.",
            inputSchema = schema(props = mapOf("rootPath" to rootPathProp)),
        ),
    )

    fun call(name: String, arguments: Map<String, Any?>): Map<String, Any> = when (name) {
        "android_project_inspect" -> inspect(arguments)
        "android_gradle_run" -> runGradle(arguments)
        "android_devices_list" -> runAdb(listOf("devices", "-l"), "adb-devices", rootPath(arguments))
        "android_app_install" -> install(arguments)
        "android_app_launch" -> launch(arguments)
        "android_logcat_capture" -> logcat(arguments)
        "android_screen_snapshot" -> snapshot(arguments)
        "android_report_bundle" -> reportBundle(arguments)
        else -> resultMap(ToolResult(status = ResultStatus.UNSUPPORTED, summary = "Unknown MCP tool: $name"))
    }

    private fun inspect(arguments: Map<String, Any?>): Map<String, Any> {
        val root = rootPath(arguments)
        val report = inspector.inspect(root)
        return mapOf(
            "schemaVersion" to "1.0",
            "status" to if (report.modules.isEmpty()) "partial" else "success",
            "summary" to "Project ${report.projectName}: ${report.modules.size} module(s), support=${report.support}",
            "project" to mapOf(
                "name" to report.projectName,
                "support" to report.support.name.lowercase(),
                "versions" to report.versions,
                "modules" to report.modules.map {
                    mapOf(
                        "path" to it.path,
                        "type" to it.type.name.lowercase(),
                        "namespace" to (it.namespace ?: ""),
                        "packageName" to (it.packageName ?: ""),
                        "launcherActivities" to it.launcherActivities,
                        "moduleDependencies" to it.moduleDependencies,
                        "buildTypes" to it.buildTypes,
                        "productFlavors" to it.productFlavors,
                    )
                },
                "commands" to report.commandMatrix.map { it.command.joinToString(" ") },
                "warnings" to report.warnings,
            ),
        )
    }

    private fun runGradle(arguments: Map<String, Any?>): Map<String, Any> {
        val root = rootPath(arguments)
        val task = arguments["task"]?.toString().orEmpty()
        if (!config.safety.isGradleTaskAllowed(task)) {
            return resultMap(
                ToolResult(
                    status = ResultStatus.BLOCKED,
                    summary = "Gradle task '$task' is not allowlisted. Update .droidagentkit/config.yaml to allow it.",
                    warnings = listOf("gradle-task-denied"),
                ),
            )
        }
        val wrapper = if (System.getProperty("os.name").startsWith("Windows")) "gradlew.bat" else "./gradlew"
        if (!root.resolve(wrapper.removePrefix("./")).exists()) {
            return resultMap(
                ToolResult(
                    status = ResultStatus.BLOCKED,
                    summary = "Gradle wrapper was not found at ${root.resolve(wrapper.removePrefix("./"))}.",
                    warnings = listOf("missing-gradle-wrapper"),
                ),
            )
        }
        val args = arguments["arguments"] as? List<*> ?: emptyList<String>()
        val timeout = arguments["timeoutSeconds"]?.toString()?.toLongOrNull() ?: config.safety.maxCommandSeconds
        val command = buildList {
            add(wrapper)
            add(task)
            if (arguments["rerunTasks"] == true) add("--rerun-tasks")
            if (arguments["stacktrace"] == true) add("--stacktrace")
            args.forEach { add(it.toString()) }
        }
        return resultMap(runner(root).run(com.droidagentkit.core.CommandSpec("gradle-${task.safeId()}", command, root.toString(), false, false, timeout)))
    }

    private fun install(arguments: Map<String, Any?>): Map<String, Any> {
        if (!config.safety.allowAppInstall) {
            return resultMap(ToolResult(status = ResultStatus.BLOCKED, summary = "App install is disabled by config.", warnings = listOf("app-install-disabled")))
        }
        val apk = arguments["apkPath"]?.toString()
            ?: return resultMap(ToolResult(status = ResultStatus.BLOCKED, summary = "apkPath is required for android_app_install.", warnings = listOf("missing-apk-path")))
        val serial = arguments["deviceSerial"]?.toString()
            ?: return resultMap(ToolResult(status = ResultStatus.BLOCKED, summary = "deviceSerial is required for app install.", warnings = listOf("missing-device-serial")))
        return runAdb(listOf("-s", serial, "install", if (arguments["reinstall"] == true) "-r" else "", apk).filter { it.isNotBlank() }, "adb-install", rootPath(arguments))
    }

    private fun launch(arguments: Map<String, Any?>): Map<String, Any> {
        val serial = arguments["deviceSerial"]?.toString()
            ?: return resultMap(ToolResult(status = ResultStatus.BLOCKED, summary = "deviceSerial is required for app launch.", warnings = listOf("missing-device-serial")))
        val packageName = arguments["packageName"]?.toString()
            ?: return resultMap(ToolResult(status = ResultStatus.BLOCKED, summary = "packageName is required for app launch.", warnings = listOf("missing-package-name")))
        val activity = arguments["activityName"]?.toString()
        val component = if (activity.isNullOrBlank()) packageName else "$packageName/$activity"
        return runAdb(listOf("-s", serial, "shell", "am", "start", "-n", component), "adb-launch", rootPath(arguments))
    }

    private fun logcat(arguments: Map<String, Any?>): Map<String, Any> {
        val serial = arguments["deviceSerial"]?.toString()
            ?: return resultMap(ToolResult(status = ResultStatus.BLOCKED, summary = "deviceSerial is required for logcat capture.", warnings = listOf("missing-device-serial")))
        val maxLines = arguments["maxLines"]?.toString()?.toIntOrNull() ?: 500
        val command = listOf("-s", serial, "logcat", "-d", "-t", maxLines.toString())
        return runAdb(command, "adb-logcat", rootPath(arguments))
    }

    private fun snapshot(arguments: Map<String, Any?>): Map<String, Any> {
        val serial = arguments["deviceSerial"]?.toString()
            ?: return resultMap(ToolResult(status = ResultStatus.BLOCKED, summary = "deviceSerial is required for screenshots.", warnings = listOf("missing-device-serial")))
        val root = rootPath(arguments)
        return resultMap(
            runner(root).run(
                com.droidagentkit.core.CommandSpec(
                    id = "adb-screenshot",
                    command = listOf("adb", "-s", serial, "exec-out", "screencap", "-p"),
                    workingDirectory = root.toString(),
                    mutatesProject = false,
                    requiresDevice = true,
                    timeoutSeconds = 60,
                    outputMode = com.droidagentkit.core.OutputMode.BINARY,
                ),
            ),
        )
    }

    private fun reportBundle(arguments: Map<String, Any?>): Map<String, Any> {
        val root = rootPath(arguments)
        val report = inspector.inspect(root)
        val auditorReport = com.droidagentkit.auditor.ReadinessAuditor(inspector).audit(root)
        val timestamp = java.time.Instant.now().toString()

        val markdown = buildString {
            appendLine("# Android Report — ${report.projectName}")
            appendLine()
            appendLine("Generated: $timestamp   Readiness: ${auditorReport.score}/100 (${auditorReport.level})")
            appendLine()
            appendLine("## Modules")
            appendLine("| Path | Type | Namespace | Unit Tests | Android Tests | Compose |")
            appendLine("|------|------|-----------|------------|---------------|---------|")
            report.modules.forEach { mod ->
                appendLine(
                    "| `${mod.path}` | ${mod.type.name.lowercase()} | ${mod.namespace ?: "—"}" +
                        " | ${if (mod.hasUnitTests) "yes" else "no"}" +
                        " | ${if (mod.hasAndroidTests) "yes" else "no"}" +
                        " | ${if (mod.usesCompose) "yes" else "no"} |",
                )
            }
            appendLine()
            appendLine("## Safe Commands")
            report.commandMatrix.forEach { cmd -> appendLine(cmd.command.joinToString(" ")) }
            appendLine()
            if (report.versions.isNotEmpty()) {
                appendLine("## Key Versions")
                val interestingKeys = setOf("kotlin", "compose", "hilt", "room", "retrofit", "coroutines", "agp")
                val keyVersions = report.versions.entries
                    .filter { (k, _) -> interestingKeys.any { k.contains(it, ignoreCase = true) } }
                    .take(8)
                val display = keyVersions.ifEmpty { report.versions.entries.take(5) }
                appendLine(display.joinToString("   ") { (k, v) -> "$k: $v" })
                appendLine()
            }
            if (auditorReport.risks.isNotEmpty()) {
                appendLine("## Warnings")
                auditorReport.risks.forEach { risk ->
                    appendLine("[${risk.severity.wireName.uppercase()}] ${risk.id} — ${risk.title}")
                }
            }
        }

        val out = root.resolve(config.reports.outputDir).resolve("android-report.md")
        Files.createDirectories(out.parent)
        Files.writeString(out, markdown)

        return resultMap(
            ToolResult(
                status = ResultStatus.SUCCESS,
                summary = "Wrote enriched report bundle to $out (${auditorReport.score}/100 ${auditorReport.level})",
                artifacts = listOf(
                    com.droidagentkit.core.ArtifactRef(
                        com.droidagentkit.core.ArtifactType.MARKDOWN,
                        out.toString(),
                        "text/markdown",
                        "Android report",
                    ),
                ),
            ),
        )
    }

    private fun runAdb(args: List<String>, id: String, root: Path): Map<String, Any> =
        resultMap(runner(root).run(com.droidagentkit.core.CommandSpec(id, listOf("adb") + args, root.toString(), false, true, 60)))

    private fun runner(root: Path): ProcessRunner =
        ProcessRunner(Redactor(config.redaction), ArtifactWriter(root.resolve(config.reports.outputDir)))

    private fun resultMap(result: ToolResult): Map<String, Any> = mapOf(
        "schemaVersion" to result.schemaVersion,
        "status" to result.status.wireName,
        "summary" to result.summary,
        "artifacts" to result.artifacts.map(Json::artifactToMap),
        "redactionsApplied" to result.redactionsApplied,
        "warnings" to result.warnings,
    )

    private fun rootPath(arguments: Map<String, Any?>): Path = Path.of(arguments["rootPath"]?.toString() ?: ".").toAbsolutePath().normalize()

    private fun String.safeId(): String = replace(Regex("[^A-Za-z0-9._-]"), "-").trim('-').ifBlank { "task" }

    private fun schema(vararg required: String, props: Map<String, Map<String, Any>>): Map<String, Any> {
        val base: MutableMap<String, Any> = mutableMapOf("type" to "object", "properties" to props)
        if (required.isNotEmpty()) base["required"] = required.toList()
        return base
    }

    private fun str(desc: String): Map<String, Any> = mapOf("type" to "string", "description" to desc)
    private fun bool(desc: String): Map<String, Any> = mapOf("type" to "boolean", "description" to desc)
    private fun num(desc: String): Map<String, Any> = mapOf("type" to "number", "description" to desc)
    private fun arrStr(desc: String): Map<String, Any> =
        mapOf("type" to "array", "items" to mapOf("type" to "string"), "description" to desc)

    private val rootPathProp get() = str("Absolute path to the Android project root. Defaults to cwd.")
    private val deviceSerialProp get() = str("adb device serial from `adb devices`.")
}
