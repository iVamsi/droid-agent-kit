package com.droidagentkit.cli

import com.droidagentkit.auditor.AgentDocumentWriter
import com.droidagentkit.auditor.AgentsDocumentGenerator
import com.droidagentkit.auditor.ReadinessAuditor
import com.droidagentkit.core.ConfigLoadResult
import com.droidagentkit.core.DroidAgentConfig
import com.droidagentkit.core.DroidAgentConfigLoader
import com.droidagentkit.core.Json
import com.droidagentkit.core.ResultStatus
import com.droidagentkit.inspector.AndroidProjectInspector
import com.droidagentkit.mcp.DroidAgentMcpDispatcher
import com.droidagentkit.mcp.DroidAgentMcpHttpServer
import com.droidagentkit.mcp.DroidAgentStdioServer
import com.droidagentkit.visuals.VisualCaptureEngine
import com.droidagentkit.visuals.VisualTolerance
import java.nio.file.Files
import java.nio.file.Path

internal fun resolveServerConfig(
    configResult: ConfigLoadResult,
    onMessage: (String) -> Unit,
): DroidAgentConfig =
    when (configResult) {
        is ConfigLoadResult.Loaded -> {
            configResult.warnings.forEach { onMessage("droidagentkit config warning: $it") }
            configResult.config
        }
        is ConfigLoadResult.Invalid -> {
            configResult.errors.forEach {
                onMessage("droidagentkit config warning: line ${it.line}: ${it.key} — ${it.message} (using defaults)")
            }
            DroidAgentConfig.default()
        }
    }

fun main(args: Array<String>) {
    val exitCode = DroidAgentCli().run(args)
    if (exitCode != 0) kotlin.system.exitProcess(exitCode)
}

class DroidAgentCli(
    private val parser: DroidAgentCliParser = DroidAgentCliParser(),
) {
    fun run(args: Array<String>): Int =
        when (val command = parser.parse(args)) {
            is CliCommand.Help ->
                when {
                    command.error != null -> {
                        System.err.println(command.error)
                        1
                    }
                    command.commandName != null -> {
                        println(usageFor(command.commandName))
                        0
                    }
                    else -> {
                        println(help())
                        0
                    }
                }
            is CliCommand.Inspect -> inspect(command)
            is CliCommand.Audit -> audit(command)
            is CliCommand.ServeMcp -> serveMcp(command)
            is CliCommand.Gradle -> mcpCall(command.project, "android_gradle_run", mapOf("task" to command.task))
            is CliCommand.Devices -> mcpCall(command.project, "android_devices_list", emptyMap())
            is CliCommand.Snapshot ->
                mcpCall(
                    ".",
                    "android_screen_snapshot",
                    mapOf(
                        "deviceSerial" to command.device,
                        "outputName" to command.output,
                    ),
                )
            is CliCommand.Visuals -> visuals(command)
            is CliCommand.InstallMcp -> installMcp(command)
        }

    private fun inspect(command: CliCommand.Inspect): Int {
        val root = Path.of(command.project).toAbsolutePath().normalize()
        val report = AndroidProjectInspector().inspect(root)
        val output =
            if (command.format == "json") {
                Json.write(
                    mapOf(
                        "projectName" to report.projectName,
                        "support" to report.support.name.lowercase(),
                        "modules" to report.modules.map { it.path },
                        "warnings" to report.warnings,
                    ),
                )
            } else {
                buildString {
                    appendLine("# DroidAgentKit Project Inspection")
                    appendLine()
                    appendLine("Project: ${report.projectName}")
                    appendLine("Support: ${report.support}")
                    appendLine()
                    appendLine("## Modules")
                    report.modules.forEach { appendLine("- `${it.path}` ${it.type} namespace=${it.namespace ?: "unknown"}") }
                    appendLine()
                    appendLine("## Safe Commands")
                    report.commandMatrix.forEach { appendLine("- `${it.command.joinToString(" ")}`") }
                }
            }
        writeOrPrint(command.output, output)
        return 0
    }

    private fun audit(command: CliCommand.Audit): Int {
        val root = Path.of(command.project).toAbsolutePath().normalize()
        val report = ReadinessAuditor(AndroidProjectInspector()).audit(root, command.redactPublic)
        if (command.writeAgents) AgentDocumentWriter().write(root, report, mergeAgents = false)
        val markdown = AgentsDocumentGenerator().generate(report)
        val outDir = root.resolve("build/droidagentkit/audit")
        Files.createDirectories(outDir)
        Files.writeString(outDir.resolve("readiness-report.md"), markdown)
        Files.writeString(
            outDir.resolve("readiness-report.json"),
            Json.write(mapOf("score" to report.score, "level" to report.level.name.lowercase(), "risks" to report.risks.map { it.id })),
        )
        println("Readiness ${report.score}/100 (${report.level})")
        return if (command.failUnder != null && report.score < command.failUnder) 2 else 0
    }

    private fun serveMcp(command: CliCommand.ServeMcp): Int {
        val projectRoot = ProjectLocator.resolve(command.project)
        val config = resolveServerConfig(DroidAgentConfigLoader.load(projectRoot)) { System.err.println(it) }
        val dispatcher = DroidAgentMcpDispatcher(config, projectRoot)
        if (command.transport == "stdio") {
            val stdio = DroidAgentStdioServer(dispatcher)
            generateSequence(::readLine).forEach { line ->
                stdio.runOnce(line)?.let { println(it) }
            }
        } else {
            val server = DroidAgentMcpHttpServer(dispatcher, command.host, command.port)
            server.start()
            println("DroidAgentKit MCP server listening at http://${command.host}:${command.port}/mcp")
            println("Set Authorization: Bearer ${server.bearerToken} in the MCP client configuration.")
            Thread.currentThread().join()
        }
        return 0
    }

    private fun mcpCall(
        project: String,
        tool: String,
        args: Map<String, Any>,
    ): Int {
        val root = ProjectLocator.resolve(project)
        val config =
            when (val configResult = DroidAgentConfigLoader.load(root)) {
                is ConfigLoadResult.Loaded -> {
                    configResult.warnings.forEach { System.err.println("droidagentkit config warning: $it") }
                    configResult.config
                }
                is ConfigLoadResult.Invalid -> {
                    configResult.errors.forEach {
                        System.err.println("droidagentkit config error: line ${it.line}: ${it.key} — ${it.message}")
                    }
                    return 1
                }
            }
        val result = DroidAgentMcpDispatcher(config, root).call(tool, args + ("rootPath" to root.toString()))
        println(Json.write(result))
        return if (result["status"] == "failed" || result["status"] == "blocked") 2 else 0
    }

    private fun visuals(command: CliCommand.Visuals): Int {
        val project = command.options["project"] ?: "."
        val root = Path.of(project).toAbsolutePath().normalize()
        val outputDir =
            command.options["output-dir"]
                ?.let { Path.of(it).toAbsolutePath().normalize() }
                ?: root.resolve("build/droidagentkit/visuals")
        val goldensDir =
            command.options["goldens-dir"]
                ?.let { Path.of(it).toAbsolutePath().normalize() }
                ?: root.resolve("src/test/resources/droidagentkit/goldens")
        return when (command.action) {
            "report" -> {
                val report = VisualCaptureEngine.generateReport(outputDir, goldensDir, VisualTolerance())
                val file = outputDir.resolve("visual-report.md")
                Files.createDirectories(file.parent)
                Files.writeString(file, VisualCaptureEngine.renderMarkdown(report))
                println(file)
                if (report.status == ResultStatus.FAILED) 2 else 0
            }
            "update-goldens" -> {
                val updated = VisualCaptureEngine.updateGoldens(outputDir, goldensDir)
                println("Updated ${updated.size} golden image(s) in $goldensDir")
                0
            }
            else -> {
                System.err.println("Unknown visuals action '${command.action}'. Expected 'report' or 'update-goldens'.")
                1
            }
        }
    }

    private fun installMcp(command: CliCommand.InstallMcp): Int {
        val binPath = command.binPath?.let(Path::of) ?: defaultDroidAgentBin()
        val options =
            McpInstallOptions(
                targets = McpInstallTargets.parse(command.targets),
                binPath = binPath.toAbsolutePath().normalize(),
                dryRun = command.dryRun,
                applyClaude = command.applyClaude,
            )
        val result = McpInstaller().install(options)
        result.messages.forEach(::println)
        if (result.changedFiles.isNotEmpty()) {
            println("Changed files:")
            result.changedFiles.forEach { println("- $it") }
        }
        return 0
    }

    private fun defaultDroidAgentBin(): Path {
        System.getenv("DROIDAGENT_BIN")?.takeIf { it.isNotBlank() }?.let { return Path.of(it) }
        val localDist = Path.of("cli/build/install/droidagent/bin/droidagent").toAbsolutePath().normalize()
        if (Files.exists(localDist)) return localDist
        return Path.of("droidagent")
    }

    private fun writeOrPrint(
        outputPath: String?,
        content: String,
    ) {
        if (outputPath == null) {
            println(content)
        } else {
            val path = Path.of(outputPath)
            path.parent?.let(Files::createDirectories)
            Files.writeString(path, content)
        }
    }

    private fun help(): String =
        buildString {
            appendLine("DroidAgentKit alpha")
            appendLine()
            appendLine("Commands:")
            CliCommandRegistry.all.forEach { spec -> appendLine("  ${spec.name} — ${spec.description}") }
            appendLine()
            appendLine("Run 'droidagent <command> --help' for command-specific flags.")
        }

    private fun usageFor(commandName: String): String {
        val spec = CliCommandRegistry.all.first { it.name == commandName }
        return buildString {
            appendLine("${spec.name} — ${spec.description}")
            if (spec.options.isNotEmpty()) {
                appendLine()
                appendLine("Flags:")
                spec.options.forEach { option ->
                    val marker = if (option.required) " (required)" else ""
                    appendLine("  ${option.flag}$marker — ${option.description}")
                }
            }
        }
    }
}
