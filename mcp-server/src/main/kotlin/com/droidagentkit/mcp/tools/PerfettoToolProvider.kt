package com.droidagentkit.mcp.tools

import com.droidagentkit.core.ArtifactSensitivity
import com.droidagentkit.core.ArtifactType
import com.droidagentkit.core.AuthorizationDecision
import com.droidagentkit.core.Capability
import com.droidagentkit.core.CommandSpec
import com.droidagentkit.core.OperationRequest
import com.droidagentkit.core.OutputMode
import com.droidagentkit.core.ResultStatus
import com.droidagentkit.core.ToolGroup
import com.droidagentkit.core.ToolResult
import com.droidagentkit.device.DeviceToolContext
import com.droidagentkit.mcp.McpTool
import com.droidagentkit.perfetto.PerfettoAnalysis
import com.droidagentkit.perfetto.PerfettoAnalysisResult
import com.droidagentkit.perfetto.PerfettoAnalysisType
import com.droidagentkit.perfetto.PerfettoCapture
import com.droidagentkit.perfetto.PerfettoCaptureConfig
import com.droidagentkit.perfetto.PerfettoConfigTemplate
import com.droidagentkit.perfetto.PerfettoReport
import com.droidagentkit.perfetto.PerfettoSql
import com.droidagentkit.perfetto.TraceProcessorCommands
import com.droidagentkit.perfetto.TraceProcessorOutputParser
import com.droidagentkit.perfetto.TraceProcessorQueryResult
import java.nio.file.Files
import java.nio.file.Path

class PerfettoToolProvider(
    private val context: DeviceToolContext,
) : McpToolProvider {
    override val group: ToolGroup = ToolGroup.PERFETTO

    private val adbPath: String get() = context.config.safety.adbPath

    private val toolNames: Set<String> = setOf("android_perfetto_capture", "android_perfetto_analyze")

    override fun listTools(): List<McpTool> = buildTools()

    override fun supports(name: String): Boolean = name in toolNames

    override fun call(
        name: String,
        arguments: Map<String, Any?>,
    ): Map<String, Any> =
        when (name) {
            "android_perfetto_capture" -> capture(arguments)
            "android_perfetto_analyze" -> analyze(arguments)
            else -> unsupported(name)
        }

    private fun buildTools(): List<McpTool> =
        listOf(
            McpTool(
                name = "android_perfetto_capture",
                title = "Capture a Perfetto trace",
                description =
                    "Capture a bounded Perfetto trace on a device, pull it to sensitive artifact storage, " +
                        "and delete the remote file. Requires the sensitive-diagnostics capability.",
                inputSchema = captureSchema(),
                outputSchema = toolResultSchema,
                annotations = mapOf("readOnlyHint" to false, "openWorldHint" to true),
            ),
            McpTool(
                name = "android_perfetto_analyze",
                title = "Analyze a Perfetto trace",
                description =
                    "Run versioned Trace Processor SQL analyses (CPU, main thread, frame jank, binder, contention) " +
                        "over a local Perfetto trace and return a correlated evidence report. " +
                        "Requires the sensitive-diagnostics capability and a configured trace_processor_shell.",
                inputSchema = analyzeSchema(),
                outputSchema = toolResultSchema,
                annotations = mapOf("readOnlyHint" to true, "openWorldHint" to true),
            ),
        )

    private fun captureSchema(): Map<String, Any> =
        schema(
            "deviceSerial",
            "rootPath",
            props =
                mapOf(
                    "rootPath" to rootPathProp,
                    "deviceSerial" to deviceSerialProp,
                    "durationSeconds" to num("Trace duration in seconds (1..600). Defaults to 10."),
                    "dataSources" to str("Comma-separated Perfetto data source names. Defaults to a safe preset."),
                    "bufferSizeKb" to num("Perfetto buffer size in KB (256..65536). Defaults to 8192."),
                    "maxFileSizeBytes" to num("Max trace file size in bytes. Defaults to 50 MiB."),
                ),
        )

    private fun analyzeSchema(): Map<String, Any> =
        schema(
            "tracePath",
            "rootPath",
            props =
                mapOf(
                    "rootPath" to rootPathProp,
                    "tracePath" to str("Local path to a Perfetto trace file to analyze."),
                    "traceProcessorShell" to str("Optional path to trace_processor_shell. Defaults to safety.traceProcessorPath."),
                    "analyses" to
                        str(
                            "Comma-separated analysis names (cpu_utilization, main_thread_slices, frame_jank, binder_latency, " +
                                "contention, compose_recomposition). Defaults to all. compose_recomposition reports no rows " +
                                "unless the app was built with androidx.compose.runtime:runtime-tracing.",
                        ),
                ),
        )

    private fun capture(arguments: Map<String, Any?>): Map<String, Any> {
        val (_, denied) = authorize("android_perfetto_capture", setOf(Capability.SENSITIVE_DIAGNOSTICS), false, arguments)
        if (denied != null) return denied
        val serial = requireSerial(arguments) ?: return missingSerial("android_perfetto_capture")
        val root = context.resolveRoot(arguments)
        val config =
            try {
                buildCaptureConfig(arguments)
            } catch (e: IllegalArgumentException) {
                return blocked("invalid-capture-config", e.message ?: "invalid capture config")
            }
        val sessionId = context.safeId("perfetto-${System.currentTimeMillis()}")
        val outDir = context.artifactOutputDir(root).resolve("perfetto").also { Files.createDirectories(it) }
        val localConfig = outDir.resolve("$sessionId.cfg")
        Files.writeString(localConfig, PerfettoConfigTemplate.render(config))
        val remoteConfig = "/data/local/tmp/$sessionId.cfg"
        val remoteTrace = PerfettoCapture.remoteTracePath(sessionId)
        val localTrace = outDir.resolve("$sessionId.perfetto-trace")

        val push =
            runAdb(root, PerfettoCapture.pushConfigCommand(adbPath, serial, localConfig.toString(), remoteConfig), "perfetto-push-config")
        if (push.status != ResultStatus.SUCCESS) {
            cleanup(root, serial, remoteConfig, remoteTrace)
            return context.resultMap(push)
        }
        val perfetto =
            runAdb(
                root,
                PerfettoCapture.perfettoCommand(adbPath, serial, remoteConfig, remoteTrace),
                "perfetto-run",
                timeoutSeconds = config.durationSeconds + 30L,
            )
        if (perfetto.status != ResultStatus.SUCCESS && perfetto.status != ResultStatus.PARTIAL) {
            cleanup(root, serial, remoteConfig, remoteTrace)
            return context.resultMap(perfetto)
        }
        val pull = runAdb(root, PerfettoCapture.pullCommand(adbPath, serial, remoteTrace, localTrace.toString()), "perfetto-pull")
        cleanup(root, serial, remoteConfig, remoteTrace)
        if (pull.status != ResultStatus.SUCCESS || !Files.exists(localTrace)) {
            return context.resultMap(pull.copy(warnings = pull.warnings + "no-trace-pulled"))
        }
        val ref =
            context.registerExistingArtifact(
                root,
                localTrace,
                ArtifactType.PERFETTO_TRACE,
                "Perfetto trace for $serial",
                ArtifactSensitivity.SENSITIVE,
            )
        return context.resultMap(
            ToolResult(
                status = ResultStatus.SUCCESS,
                summary = "Captured Perfetto trace (${ref.sizeBytes} bytes) for $serial.",
                artifacts = listOf(ref),
            ),
        )
    }

    private fun analyze(arguments: Map<String, Any?>): Map<String, Any> {
        val (_, denied) = authorize("android_perfetto_analyze", setOf(Capability.SENSITIVE_DIAGNOSTICS), false, arguments)
        if (denied != null) return denied
        val tracePathString =
            arguments["tracePath"]?.toString()?.takeIf { it.isNotBlank() }
                ?: return blocked("missing-trace-path", "tracePath is required for android_perfetto_analyze.")
        val tracePath = Path.of(tracePathString)
        if (!Files.exists(tracePath)) return blocked("missing-trace-file", "Trace file does not exist: $tracePathString")
        val shellPath =
            arguments["traceProcessorShell"]?.toString()?.takeIf { it.isNotBlank() }
                ?: context.config.safety.traceProcessorPath
        if (shellPath.isBlank()) {
            return blocked(
                "trace-processor-not-configured",
                "trace_processor_shell is not configured. Set safety.traceProcessorPath; DroidAgentKit does not auto-download it.",
            )
        }
        val root = context.resolveRoot(arguments)
        val analyses = resolveAnalyses(arguments)
        val sqlDir = context.artifactOutputDir(root).resolve("perfetto/sql").also { Files.createDirectories(it) }
        val results = linkedMapOf<PerfettoAnalysisType, TraceProcessorQueryResult>()
        for (type in analyses) {
            val sqlFile = sqlDir.resolve("${context.safeId(type.name)}.sql")
            Files.writeString(sqlFile, PerfettoSql.load(type))
            val cmd = TraceProcessorCommands.query(shellPath, tracePath.toString(), sqlFile.toString())
            val runResult = runCommand(root, cmd, "perfetto-query-${type.name.lowercase()}", 120L)
            val output = readLog(runResult)
            results[type] = TraceProcessorOutputParser.parse(output)
        }
        val report: PerfettoReport = PerfettoAnalysis.report(results)
        return context.resultMap(
            ToolResult(
                status = ResultStatus.SUCCESS,
                summary = "Perfetto analysis: ${report.analyses.size} analyses; confidence=${report.confidence.name.lowercase()}.",
                warnings = report.warnings,
            ),
        ) +
            mapOf(
                "analyses" to report.analyses.map { it.toMap() },
                "correlation" to report.correlation,
                "confidence" to report.confidence.name.lowercase(),
            )
    }

    private fun buildCaptureConfig(arguments: Map<String, Any?>): PerfettoCaptureConfig {
        val duration = (arguments["durationSeconds"]?.toString()?.toLongOrNull() ?: 10L)
        val dataSources =
            arguments["dataSources"]
                ?.toString()
                ?.takeIf { it.isNotBlank() }
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?: PerfettoCaptureConfig.DEFAULT_DATA_SOURCES
        val bufferSizeKb = arguments["bufferSizeKb"]?.toString()?.toLongOrNull() ?: 8192L
        val maxFileSizeBytes = arguments["maxFileSizeBytes"]?.toString()?.toLongOrNull() ?: (50L * 1024L * 1024L)
        return PerfettoCaptureConfig(duration, dataSources, bufferSizeKb, maxFileSizeBytes)
    }

    private fun resolveAnalyses(arguments: Map<String, Any?>): List<PerfettoAnalysisType> {
        val raw =
            arguments["analyses"]
                ?.toString()
                ?.takeIf { it.isNotBlank() }
                ?.split(",")
                ?.map { it.trim().lowercase() }
                ?: return PerfettoAnalysisType.entries
        val known = PerfettoAnalysisType.entries.associateBy { it.name.lowercase() }
        return raw.mapNotNull { known[it] }.ifEmpty { PerfettoAnalysisType.entries }
    }

    private fun cleanup(
        root: Path,
        serial: String,
        vararg remotePaths: String,
    ) {
        runCatching { runAdb(root, PerfettoCapture.cleanupCommand(adbPath, serial, *remotePaths), "perfetto-cleanup") }
    }

    private fun readLog(result: ToolResult): String =
        result.artifacts.firstOrNull()?.let { runCatching { Files.readString(Path.of(it.path)) }.getOrDefault("") } ?: ""

    private fun runAdb(
        root: Path,
        command: List<String>,
        id: String,
        timeoutSeconds: Long = 120L,
    ): ToolResult = runCommand(root, command, id, timeoutSeconds)

    private fun runCommand(
        root: Path,
        command: List<String>,
        id: String,
        timeoutSeconds: Long,
    ): ToolResult =
        context.run(
            root,
            CommandSpec(
                id = id,
                command = command,
                workingDirectory = root.toString(),
                mutatesProject = false,
                requiresDevice = false,
                timeoutSeconds = timeoutSeconds,
                outputMode = OutputMode.TEXT,
                sensitivity = ArtifactSensitivity.SENSITIVE,
            ),
        )

    private fun authorize(
        tool: String,
        capabilities: Set<Capability>,
        destructive: Boolean,
        arguments: Map<String, Any?>,
    ): Pair<AuthorizationDecision, Map<String, Any>?> {
        val serial = requireSerial(arguments)
        val request =
            OperationRequest(
                operationId = tool,
                requiredCapabilities = capabilities,
                destructive = destructive,
                confirmDestructive = arguments["confirmDestructive"] == true,
                deviceSerial = serial,
                mutating = false,
            )
        val decision = context.authorize(request)
        return if (decision is AuthorizationDecision.Denied) {
            decision to
                context.resultMap(ToolResult(status = ResultStatus.BLOCKED, summary = decision.reason, warnings = listOf(decision.code)))
        } else {
            decision to null
        }
    }

    private fun requireSerial(arguments: Map<String, Any?>): String? = arguments["deviceSerial"]?.toString()?.takeIf { it.isNotBlank() }

    private fun missingSerial(tool: String): Map<String, Any> =
        context.resultMap(
            ToolResult(
                status = ResultStatus.BLOCKED,
                summary = "deviceSerial is required for $tool.",
                warnings = listOf("missing-device-serial"),
            ),
        )

    private fun blocked(
        code: String,
        reason: String,
    ): Map<String, Any> = context.resultMap(ToolResult(status = ResultStatus.BLOCKED, summary = reason, warnings = listOf(code)))

    private fun unsupported(name: String): Map<String, Any> =
        context.resultMap(
            ToolResult(status = ResultStatus.UNSUPPORTED, summary = "Unknown perfetto tool: $name", warnings = listOf("unknown-tool")),
        )

    private fun PerfettoAnalysisResult.toMap(): Map<String, Any> =
        mapOf(
            "analysis" to analysis.name.lowercase(),
            "rowCount" to rowCount,
            "rows" to rows,
            "summary" to summary,
            "confidence" to confidence.name.lowercase(),
            "evidence" to evidence,
            "warnings" to warnings,
        )

    private val toolResultSchema: Map<String, Any> =
        mapOf(
            "type" to "object",
            "properties" to
                mapOf(
                    "schemaVersion" to mapOf("type" to "string"),
                    "status" to mapOf("type" to "string"),
                    "summary" to mapOf("type" to "string"),
                    "artifacts" to mapOf("type" to "array"),
                    "redactionsApplied" to mapOf("type" to "array"),
                    "warnings" to mapOf("type" to "array"),
                ),
            "required" to listOf("schemaVersion", "status", "summary"),
        )

    private val rootPathProp: Map<String, Any> =
        mapOf(
            "type" to "string",
            "description" to "Absolute path of the target Android project root.",
        )
    private val deviceSerialProp: Map<String, Any> = mapOf("type" to "string", "description" to "adb device serial to target.")

    private fun str(desc: String): Map<String, Any> = mapOf("type" to "string", "description" to desc)

    private fun num(desc: String): Map<String, Any> = mapOf("type" to "number", "description" to desc)

    private fun schema(
        vararg required: String,
        props: Map<String, Map<String, Any>>,
    ): Map<String, Any> {
        val base: MutableMap<String, Any> = mutableMapOf("type" to "object", "properties" to props)
        if (required.isNotEmpty()) base["required"] = required.toList()
        return base
    }
}
