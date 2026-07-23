package com.droidagentkit.mcp.tools

import com.droidagentkit.core.ArtifactSensitivity
import com.droidagentkit.core.ArtifactType
import com.droidagentkit.core.AuthorizationDecision
import com.droidagentkit.core.Capability
import com.droidagentkit.core.CommandSpec
import com.droidagentkit.core.JobState
import com.droidagentkit.core.ManagedJobSpec
import com.droidagentkit.core.OperationRequest
import com.droidagentkit.core.OutputMode
import com.droidagentkit.core.Redactor
import com.droidagentkit.core.ResultStatus
import com.droidagentkit.core.ToolGroup
import com.droidagentkit.core.ToolResult
import com.droidagentkit.device.DeviceToolContext
import com.droidagentkit.mcp.McpTool
import com.droidagentkit.network.HarParser
import com.droidagentkit.network.NetworkCaptureException
import com.droidagentkit.network.NetworkCapturePlanner
import com.droidagentkit.network.NetworkCommandExecutor
import com.droidagentkit.network.ProxyController
import java.nio.file.Files
import java.nio.file.Path

class NetworkToolProvider(
    private val context: DeviceToolContext,
) : McpToolProvider {
    override val group: ToolGroup = ToolGroup.NETWORK_EXPERIMENTAL

    private val toolNames: Set<String> =
        setOf(
            "android_network_capture_start",
            "android_network_capture_query",
        )

    private val adbPath: String get() = context.config.safety.adbPath

    override fun listTools(): List<McpTool> = buildTools()

    override fun supports(name: String): Boolean = name in toolNames

    override fun call(
        name: String,
        arguments: Map<String, Any?>,
    ): Map<String, Any> =
        when (name) {
            "android_network_capture_start" -> captureStart(arguments)
            "android_network_capture_query" -> captureQuery(arguments)
            else -> unsupported(name)
        }

    private fun buildTools(): List<McpTool> =
        listOf(
            McpTool(
                name = "android_network_capture_start",
                title = "Start an emulator-only network capture",
                description =
                    "Start a bounded mitmproxy capture on an emulator for a debuggable app. " +
                        "Emulator-only; requires a user-installed debug CA. " +
                        "Restores the prior global proxy on stop, cancel, timeout, or crash. " +
                        "Experimental; requires network_interception and confirmDestructive=true.",
                inputSchema = startSchema,
                outputSchema = toolResultSchema,
                annotations = mapOf("destructiveHint" to true, "openWorldHint" to true),
            ),
            McpTool(
                name = "android_network_capture_query",
                title = "Query a captured HAR",
                description =
                    "Parse a finalized mitmproxy HAR dump into redacted flow summaries. " +
                        "Bodies are disabled by default; sensitive headers are always redacted. " +
                        "Requires network_interception.",
                inputSchema = querySchema,
                outputSchema = toolResultSchema,
                annotations = mapOf("readOnlyHint" to true),
            ),
        )

    private fun contextExecutor(root: Path): NetworkCommandExecutor =
        object : NetworkCommandExecutor {
            override fun run(
                command: List<String>,
                binary: Boolean,
            ): ByteArray {
                val result =
                    context.run(
                        root,
                        CommandSpec(
                            id = "network-adb",
                            command = command,
                            workingDirectory = root.toString(),
                            mutatesProject = false,
                            requiresDevice = true,
                            timeoutSeconds = 30,
                            outputMode = if (binary) OutputMode.BINARY else OutputMode.TEXT,
                            sensitivity = ArtifactSensitivity.SENSITIVE,
                        ),
                    )
                val artifactPath = result.artifacts.firstOrNull()?.let { Path.of(it.path) }
                return artifactPath?.let { runCatching { Files.readAllBytes(it) }.getOrDefault(ByteArray(0)) } ?: ByteArray(0)
            }
        }

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
            ToolResult(status = ResultStatus.UNSUPPORTED, summary = "Unknown network tool: $name", warnings = listOf("unknown-tool")),
        )

    private val rootPathProp: Map<String, Any> =
        mapOf("type" to "string", "description" to "Absolute path of the target Android project root.")

    private val startSchema: Map<String, Any> =
        schema(
            "rootPath",
            "deviceSerial",
            "packageName",
            "confirmDestructive",
            props =
                mapOf(
                    "rootPath" to rootPathProp,
                    "deviceSerial" to str("Emulator adb serial to target."),
                    "packageName" to str("Debuggable Android package whose traffic to capture."),
                    "durationSeconds" to num("Capture window in seconds. Default 30, max 120."),
                    "confirmDestructive" to bool("Must be true: this installs and later restores the device global proxy."),
                ),
        )

    private val querySchema: Map<String, Any> =
        schema(
            "rootPath",
            "capturePath",
            props =
                mapOf(
                    "rootPath" to rootPathProp,
                    "capturePath" to str("HAR path returned by android_network_capture_start."),
                    "includeBodies" to bool("If true, include redacted request/response bodies. Defaults to false."),
                ),
        )

    private fun schema(
        vararg required: String,
        props: Map<String, Map<String, Any>>,
    ): Map<String, Any> {
        val base: MutableMap<String, Any> = mutableMapOf("type" to "object", "properties" to props)
        if (required.isNotEmpty()) base["required"] = required.toList()
        return base
    }

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

    private fun str(desc: String): Map<String, Any> = mapOf("type" to "string", "description" to desc)

    private fun num(desc: String): Map<String, Any> = mapOf("type" to "number", "description" to desc)

    private fun bool(desc: String): Map<String, Any> = mapOf("type" to "boolean", "description" to desc)

    private fun captureStart(arguments: Map<String, Any?>): Map<String, Any> {
        val tool = "android_network_capture_start"
        val serial = arguments["deviceSerial"]?.toString()?.takeIf { it.isNotBlank() } ?: return missingSerial(tool)
        val packageName =
            arguments["packageName"]?.toString()?.takeIf { it.isNotBlank() }
                ?: return blocked("missing-package", "packageName is required for $tool.")
        val confirmDestructive = arguments["confirmDestructive"] == true
        val durationSeconds = (arguments["durationSeconds"]?.toString()?.toLongOrNull() ?: 30L).coerceIn(1L, 120L)
        val decision =
            context.authorize(
                OperationRequest(
                    operationId = tool,
                    requiredCapabilities = setOf(Capability.NETWORK_INTERCEPTION),
                    destructive = true,
                    deviceSerial = serial,
                    packageName = packageName,
                    confirmDestructive = confirmDestructive,
                    mutating = true,
                ),
            )
        if (decision is AuthorizationDecision.Denied) {
            return context.resultMap(ToolResult(status = ResultStatus.BLOCKED, summary = decision.reason, warnings = listOf(decision.code)))
        }
        val operation = (decision as AuthorizationDecision.Allowed).operation
        val mitmPath =
            context.config.safety.mitmProxyPath
                .ifBlank { System.getenv("MITMPROXY_PATH") ?: "" }
        val root = context.resolveRoot(arguments)
        val exec = contextExecutor(root)
        val harDir = context.artifactOutputDir(root).resolve("network")
        val harName = "capture-${context.safeId("$serial-${System.currentTimeMillis()}")}.har"
        val plan =
            try {
                NetworkCapturePlanner.plan(exec, adbPath, serial, packageName, mitmPath, harDir, harName)
            } catch (e: NetworkCaptureException) {
                return blocked(e.code, e.message ?: e.code)
            }
        val proxy = ProxyController(adbPath).installProxy(exec, serial, "10.0.2.2", plan.listenPort)
        val jobId = "net-${context.safeId(serial)}-${System.currentTimeMillis()}"
        val restore: () -> Unit = {
            try {
                ProxyController(adbPath).restoreProxy(exec, serial, proxy.priorProxy)
            } catch (_: Exception) {
            }
        }
        val spec =
            CommandSpec(
                id = jobId,
                command = plan.command,
                workingDirectory = root.toString(),
                mutatesProject = false,
                requiresDevice = false,
                timeoutSeconds = durationSeconds,
                outputMode = OutputMode.TEXT,
                sensitivity = ArtifactSensitivity.SENSITIVE,
            )
        val snapshot =
            context.jobRunner().start(
                ManagedJobSpec(id = jobId, operation = operation, command = spec, timeoutSeconds = durationSeconds + 5, cleanup = restore),
            )
        if (snapshot.state == JobState.PENDING) {
            restore()
            return context.resultMap(
                ToolResult(
                    status = ResultStatus.BLOCKED,
                    summary = "Capture job could not start: ${snapshot.warnings.joinToString(", ")}.",
                    warnings =
                        snapshot.warnings + "proxy-restored",
                ),
            )
        }
        return context.resultMap(
            ToolResult(
                status = ResultStatus.SUCCESS,
                summary = "Started network capture $jobId on $serial for ${durationSeconds}s (proxy ${plan.deviceProxy}).",
            ),
        ) +
            mapOf(
                "jobId" to jobId,
                "jobState" to jobStatusWire(snapshot.state),
                "harPath" to plan.harPath,
                "deviceProxy" to plan.deviceProxy,
                "listenPort" to plan.listenPort,
            )
    }

    private fun captureQuery(arguments: Map<String, Any?>): Map<String, Any> {
        val tool = "android_network_capture_query"
        val capturePath =
            arguments["capturePath"]?.toString()?.takeIf { it.isNotBlank() }
                ?: return blocked("missing-capture-path", "capturePath is required for $tool.")
        val includeBodies = arguments["includeBodies"] == true
        val decision =
            context.authorize(
                OperationRequest(
                    operationId = tool,
                    requiredCapabilities = setOf(Capability.NETWORK_INTERCEPTION),
                    destructive = false,
                ),
            )
        if (decision is AuthorizationDecision.Denied) {
            return context.resultMap(ToolResult(status = ResultStatus.BLOCKED, summary = decision.reason, warnings = listOf(decision.code)))
        }
        val root = context.resolveRoot(arguments)
        val confined = confinedHar(root, capturePath) ?: return blocked("path-escape", "capturePath must stay inside the project root.")
        if (!Files.exists(confined)) {
            return context.resultMap(
                ToolResult(
                    status = ResultStatus.PARTIAL,
                    summary = "Capture not finalized yet (job still running or process crashed).",
                    warnings = listOf("capture-not-finalized"),
                ),
            ) + mapOf("flows" to emptyList<Map<String, Any>>(), "pinningSuspected" to false)
        }
        val harJson = runCatching { Files.readString(confined) }.getOrDefault("")
        val redactor = Redactor(context.config.redaction)
        val result = HarParser.parse(harJson, includeBodies, redactor)
        val artifact =
            context.registerExistingArtifact(
                root,
                confined,
                ArtifactType.NETWORK_CAPTURE,
                "mitmproxy HAR capture (redacted summary). Sensitive: contains app traffic metadata.",
                ArtifactSensitivity.SENSITIVE,
            )
        val status = if (result.pinningSuspected) ResultStatus.UNSUPPORTED else ResultStatus.SUCCESS
        return context.resultMap(
            ToolResult(
                status = status,
                summary = "Parsed ${result.flows.size} flow(s) from ${confined.fileName}.",
                artifacts = listOf(artifact),
                redactionsApplied = result.redactionsApplied,
                warnings = result.warnings,
            ),
        ) +
            mapOf(
                "flows" to result.flows.map { it.toMap() },
                "pinningSuspected" to result.pinningSuspected,
            )
    }

    private fun confinedHar(
        root: Path,
        capturePath: String,
    ): Path? =
        runCatching {
            val resolved = Path.of(capturePath).toAbsolutePath().normalize()
            if (!resolved.startsWith(root.toAbsolutePath().normalize())) null else resolved
        }.getOrNull()

    private fun com.droidagentkit.network.FlowSummary.toMap(): Map<String, Any> =
        mapOf(
            "method" to method,
            "scheme" to scheme,
            "host" to host,
            "path" to path,
            "status" to status,
            "contentType" to contentType,
            "requestHeaders" to requestHeaders,
            "responseHeaders" to responseHeaders,
            "requestBody" to (requestBody ?: ""),
            "responseBody" to (responseBody ?: ""),
            "error" to (error ?: ""),
        )

    private fun jobStatusWire(state: JobState): String =
        when (state) {
            JobState.PENDING -> "pending"
            JobState.RUNNING -> "running"
            JobState.SUCCEEDED -> "success"
            JobState.FAILED -> "failed"
            JobState.CANCELLED -> "cancelled"
            JobState.EXPIRED -> "expired"
        }
}
