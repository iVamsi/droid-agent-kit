package com.droidagentkit.cli

import com.droidagentkit.auditor.AgentDocumentWriter
import com.droidagentkit.auditor.AgentsDocumentGenerator
import com.droidagentkit.auditor.ReadinessAuditor
import com.droidagentkit.core.ConfigLoadResult
import com.droidagentkit.core.ConfigYaml
import com.droidagentkit.core.DroidAgentConfig
import com.droidagentkit.core.DroidAgentConfigLoader
import com.droidagentkit.core.Json
import com.droidagentkit.core.ResultStatus
import com.droidagentkit.inspector.AndroidProjectInspector
import com.droidagentkit.mcp.DroidAgentMcpHttpServer
import com.droidagentkit.mcp.DroidAgentStdioServer
import com.droidagentkit.mcp.DroidAgentWorkspaceDispatcher
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
            is CliCommand.Devices -> devices(command)
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
            is CliCommand.Init -> init(command)
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
        val baseReport = ReadinessAuditor(AndroidProjectInspector()).audit(root, command.redactPublic)
        val generated =
            if (command.writeAgents) {
                AgentDocumentWriter().write(root, baseReport, mergeAgents = false)
            } else {
                emptyList()
            }
        val report = baseReport.copy(generatedDocuments = generated)
        val markdown = AgentsDocumentGenerator().generate(report)
        val outDir = root.resolve("build/droidagentkit/audit")
        Files.createDirectories(outDir)
        Files.writeString(outDir.resolve("readiness-report.md"), markdown)
        Files.writeString(
            outDir.resolve("readiness-report.json"),
            Json.write(
                mapOf(
                    "score" to report.score,
                    "level" to report.level.name.lowercase(),
                    "risks" to report.risks.map { it.id },
                    "generatedDocuments" to report.generatedDocuments.map { it.path },
                ),
            ),
        )
        println("Readiness ${report.score}/100 (${report.level})")
        return if (command.failUnder != null && report.score < command.failUnder) 2 else 0
    }

    private fun serveMcp(command: CliCommand.ServeMcp): Int {
        val projectRoot = ProjectLocator.resolve(command.project)
        val config = resolveServerConfig(DroidAgentConfigLoader.loadEffective(projectRoot)) { System.err.println(it) }
        val projectDispatcher = mcpDispatcher(config, projectRoot)
        val dispatcher =
            command.projectsRoot?.let { configuredRoot ->
                val projectsRoot = Path.of(configuredRoot).toAbsolutePath().normalize()
                if (!Files.isDirectory(projectsRoot)) {
                    System.err.println("Projects root '$projectsRoot' does not exist or is not a directory.")
                    return 1
                }
                DroidAgentWorkspaceDispatcher(projectsRoot, projectDispatcher) { root ->
                    val projectConfig =
                        resolveServerConfig(DroidAgentConfigLoader.loadEffective(root)) { System.err.println(it) }
                    mcpDispatcher(projectConfig, root)
                }
            } ?: projectDispatcher
        if (command.transport == "stdio") {
            val stdio = DroidAgentStdioServer(dispatcher)
            generateSequence { readlnOrNull() }.forEach { line ->
                stdio.runOnce(line)?.let { println(it) }
            }
        } else {
            val configuredToken =
                command.bearerTokenFile?.let { tokenFile ->
                    val token =
                        runCatching { Files.readString(Path.of(tokenFile)).trim() }
                            .getOrElse {
                                System.err.println("Could not read bearer token file '$tokenFile': ${it.message}")
                                return 1
                            }
                    if (!token.matches(Regex("[A-Za-z0-9_-]{32,}"))) {
                        System.err.println("Bearer token file '$tokenFile' does not contain a valid token.")
                        return 1
                    }
                    token
                }
            val server = DroidAgentMcpHttpServer(dispatcher, command.host, command.port, configuredToken, command.allowRemote)
            try {
                server.start()
            } catch (error: IllegalArgumentException) {
                System.err.println(error.message)
                return 1
            }
            println("DroidAgentKit MCP server listening at http://${command.host}:${command.port}/mcp")
            if (configuredToken == null) {
                System.err.println(
                    "Bearer token written to stderr only. Prefer --bearer-token-file for non-interactive hosts.",
                )
                System.err.println("Set Authorization: Bearer ${server.bearerToken} in the MCP client configuration.")
            } else {
                println("Using the bearer token from ${command.bearerTokenFile}.")
            }
            Thread.currentThread().join()
        }
        return 0
    }

    private fun devices(command: CliCommand.Devices): Int {
        val root = ProjectLocator.resolve(command.project)
        val config =
            when (val configResult = DroidAgentConfigLoader.loadEffective(root)) {
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
        val result = mcpDispatcher(config, root).call("android_devices_list", mapOf("rootPath" to root.toString()))
        val adbOutput = readAdbDevicesOutput(result)
        val output =
            if (command.format == "markdown") {
                renderDevicesMarkdown(adbOutput)
            } else {
                Json.write(result)
            }
        println(output)
        return if (result["status"] == "failed" || result["status"] == "blocked") 2 else 0
    }

    private fun readAdbDevicesOutput(result: Map<String, Any>): String {
        val artifacts = result["artifacts"] as? List<*> ?: emptyList<Any>()
        val firstArtifact = artifacts.firstOrNull() as? Map<*, *> ?: return ""
        val path = firstArtifact["path"]?.toString() ?: return ""
        return runCatching { Files.readString(Path.of(path)) }.getOrDefault("")
    }

    private fun mcpCall(
        project: String,
        tool: String,
        args: Map<String, Any>,
    ): Int {
        val root = ProjectLocator.resolve(project)
        val config =
            when (val configResult = DroidAgentConfigLoader.loadEffective(root)) {
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
        val result = mcpDispatcher(config, root).call(tool, args + ("rootPath" to root.toString()))
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
        val projectRoot = ProjectLocator.resolve(command.project)
        val projectsRoot = command.projectsRoot?.let { Path.of(it).toAbsolutePath().normalize() }
        val options =
            McpInstallOptions(
                targets = McpInstallTargets.parse(command.targets),
                binPath = binPath.toAbsolutePath().normalize(),
                projectRoot = projectRoot,
                projectsRoot = projectsRoot,
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

    private fun init(command: CliCommand.Init): Int {
        if (command.listProfiles) {
            println(profileListing())
            return 0
        }
        val root = Path.of(command.project).toAbsolutePath().normalize()
        val configPath = root.resolve(".droidagentkit/config.yaml")
        val policyPath = DroidAgentConfigLoader.defaultUserPolicyPath()
        if (Files.exists(policyPath) && !command.force) {
            System.err.println("$policyPath already exists. Rerun with --force to regenerate it.")
            return 1
        }

        val expansion: ProfileExpansion
        if (command.profiles.isNotEmpty()) {
            val result = ProfileCatalog.expand(command.profiles)
            if (result.isFailure) {
                System.err.println("Unknown profile(s): ${result.exceptionOrNull()?.message}")
                println(profileListing())
                return 1
            }
            expansion = result.getOrThrow()
        } else {
            if (System.console() == null) {
                System.err.println(
                    "No terminal detected and no --profile given. Run 'droidagent init --list-profiles' to see " +
                        "options, or 'droidagent init --profile <name>'.",
                )
                return 1
            }
            val wizardResult = InitWizard(readLine = ::readlnOrNull, print = ::println).run()
            if (wizardResult == null) {
                println("Aborted, no file written.")
                return 0
            }
            expansion = wizardResult
        }

        // Grants (capabilities, tool groups) go to the user policy — the only config the server
        // honors them from. The project config is seeded separately, without grants.
        Files.createDirectories(policyPath.parent)
        Files.writeString(policyPath, ConfigYaml.renderUserPolicy(expansion.groups, expansion.capabilities))
        println("Wrote $policyPath")

        if (Files.exists(configPath)) {
            println("Kept existing $configPath (project config holds no grants; edit it for task allowlist/output options).")
        } else {
            val projectName = AndroidProjectInspector().inspect(root).projectName
            Files.createDirectories(configPath.parent)
            Files.writeString(configPath, ConfigYaml.renderProject(projectName))
            println("Wrote $configPath")
        }
        return 0
    }

    private fun profileListing(): String =
        buildString {
            appendLine("Available profiles:")
            ProfileCatalog.names().forEach { name -> appendLine("  $name — ${ProfileCatalog.description(name)}") }
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

internal data class AdbDeviceRow(
    val serial: String,
    val state: String,
    val details: Map<String, String>,
)

internal fun parseAdbDevices(adbOutput: String): List<AdbDeviceRow> {
    val rows = mutableListOf<AdbDeviceRow>()
    adbOutput.lineSequence().forEach { rawLine ->
        val line = rawLine.trim()
        if (line.isEmpty()) return@forEach
        if (line.startsWith("List of devices attached")) return@forEach
        if (line.startsWith("*")) return@forEach
        val tokens = line.split(Regex("\\s+"))
        if (tokens.size < 2) return@forEach
        val serial = tokens[0]
        val state = tokens[1]
        val details = mutableMapOf<String, String>()
        tokens.drop(2).forEach { token ->
            val eq = token.indexOf(':')
            if (eq > 0) details[token.substring(0, eq)] = token.substring(eq + 1)
        }
        rows.add(AdbDeviceRow(serial, state, details))
    }
    return rows
}

internal fun renderDevicesMarkdown(adbOutput: String): String {
    val devices = parseAdbDevices(adbOutput)
    return buildString {
        appendLine("# Connected adb devices")
        appendLine()
        if (devices.isEmpty()) {
            appendLine("_(no devices)_")
        } else {
            appendLine("| Serial | State | Product | Model | Device | Transport |")
            appendLine("|--------|-------|---------|-------|--------|-----------|")
            devices.forEach { line ->
                appendLine(
                    "| ${line.serial} | ${line.state} | ${line.details["product"] ?: "-"}" +
                        " | ${line.details["model"] ?: "-"} | ${line.details["device"] ?: "-"}" +
                        " | ${line.details["transport_id"] ?: "-"} |",
                )
            }
        }
    }
}
