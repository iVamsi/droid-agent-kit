package com.droidagentkit.mcp.tools

import com.droidagentkit.core.ArtifactSensitivity
import com.droidagentkit.core.ArtifactType
import com.droidagentkit.core.AuthorizationDecision
import com.droidagentkit.core.CommandSpec
import com.droidagentkit.core.JobState
import com.droidagentkit.core.Json
import com.droidagentkit.core.ManagedJobSpec
import com.droidagentkit.core.OperationRequest
import com.droidagentkit.core.OutputMode
import com.droidagentkit.core.ResultStatus
import com.droidagentkit.core.ShellQuote
import com.droidagentkit.core.ToolGroup
import com.droidagentkit.core.ToolResult
import com.droidagentkit.device.DeviceToolContext
import com.droidagentkit.device.DumpsysPreset
import com.droidagentkit.device.DumpsysSummaryParser
import com.droidagentkit.device.PermissionAuditEntry
import com.droidagentkit.device.PermissionAuditParser
import com.droidagentkit.mcp.McpTool
import java.nio.file.Files
import java.nio.file.Path

class DeviceReadToolProvider(
    private val context: DeviceToolContext,
) : McpToolProvider {
    override val group: ToolGroup = ToolGroup.DEVICE_READ

    private val toolNames: Set<String> =
        setOf(
            "android_permission_audit",
            "android_dumpsys",
            "android_memory_summary",
            "android_battery_summary",
            "android_bugreport",
            "android_logcat_start",
            "android_job_status",
            "android_job_cancel",
        )

    override fun listTools(): List<McpTool> = buildTools()

    override fun supports(name: String): Boolean = name in toolNames

    override fun call(
        name: String,
        arguments: Map<String, Any?>,
    ): Map<String, Any> =
        when (name) {
            "android_permission_audit" -> permissionAudit(arguments)
            "android_dumpsys" -> dumpsys(arguments)
            "android_memory_summary" -> memorySummary(arguments)
            "android_battery_summary" -> batterySummary(arguments)
            "android_bugreport" -> bugreport(arguments)
            "android_logcat_start" -> logcatStart(arguments)
            "android_job_status" -> jobStatus(arguments)
            "android_job_cancel" -> jobCancel(arguments)
            else -> unsupported(name)
        }

    private fun buildTools(): List<McpTool> =
        listOf(
            tool(
                "android_permission_audit",
                "Audit Android runtime permissions",
                "Run dumpsys package for a debuggable or queried package and report runtime permission grant state as evidence. Read-only.",
                schema(
                    "deviceSerial",
                    "packageName",
                    props =
                        mapOf(
                            "rootPath" to rootPathProp,
                            "deviceSerial" to str("adb device serial to query."),
                            "packageName" to str("Android package name to audit."),
                        ),
                ),
            ),
            tool(
                "android_dumpsys",
                "Run a fixed dumpsys preset",
                "Run one of a fixed set of dumpsys presets (meminfo, gfxinfo, cpuinfo, batterystats, package) and return a bounded summary plus the raw evidence. No arbitrary service names.",
                schema(
                    "deviceSerial",
                    "preset",
                    props =
                        mapOf(
                            "rootPath" to rootPathProp,
                            "deviceSerial" to str("adb device serial to query."),
                            "preset" to str("Dumpsys preset: meminfo, gfxinfo, cpuinfo, batterystats, or package."),
                            "packageName" to str("Required when preset is package."),
                        ),
                ),
            ),
            tool(
                "android_memory_summary",
                "Summarize device memory",
                "Run dumpsys meminfo and return Total/Free/Used RAM as evidence with device provenance. Not a leak diagnosis.",
                schema(
                    "deviceSerial",
                    props =
                        mapOf(
                            "rootPath" to rootPathProp,
                            "deviceSerial" to str("adb device serial to query."),
                        ),
                ),
            ),
            tool(
                "android_battery_summary",
                "Summarize device battery",
                "Run dumpsys battery and return level, status, health, temperature, and voltage as evidence with device provenance.",
                schema(
                    "deviceSerial",
                    props =
                        mapOf(
                            "rootPath" to rootPathProp,
                            "deviceSerial" to str("adb device serial to query."),
                        ),
                ),
            ),
            tool(
                "android_bugreport",
                "Capture an Android bugreport",
                "Run adb bugreport and stream the ZIP directly to sensitive artifact storage. Requires the sensitive-diagnostics capability.",
                schema(
                    "deviceSerial",
                    props =
                        mapOf(
                            "rootPath" to rootPathProp,
                            "deviceSerial" to str("adb device serial to capture from."),
                        ),
                ),
            ),
            tool(
                "android_logcat_start",
                "Start a managed logcat capture",
                "Start a bounded managed logcat job on a device and return a job id plus the concrete log resource URI. Clients poll android_job_status. Package filtering resolves the PID safely and warns when the process is absent.",
                schema(
                    "deviceSerial",
                    props =
                        mapOf(
                            "rootPath" to rootPathProp,
                            "deviceSerial" to str("adb device serial to capture from."),
                            "packageName" to str("Optional package to filter by PID via pidof."),
                            "filter" to str("Optional logcat filter expression (e.g. *:I). Bounded to safe tokens."),
                            "durationSeconds" to num("Capture duration in seconds. Default 30, max 300."),
                        ),
                ),
            ),
            tool(
                "android_job_status",
                "Poll a managed job",
                "Return the current state and artifact (if any) of a managed job started by android_logcat_start.",
                schema(
                    "jobId",
                    props =
                        mapOf(
                            "rootPath" to rootPathProp,
                            "jobId" to str("Managed job id returned by a start tool."),
                        ),
                ),
            ),
            tool(
                "android_job_cancel",
                "Cancel a managed job",
                "Cancel a running managed job and release its device lock. Returns the final job state.",
                schema(
                    "jobId",
                    props =
                        mapOf(
                            "rootPath" to rootPathProp,
                            "jobId" to str("Managed job id to cancel."),
                        ),
                ),
            ),
        )

    private fun tool(
        name: String,
        title: String,
        description: String,
        inputSchema: Map<String, Any>,
        annotations: Map<String, Boolean> = mapOf("readOnlyHint" to true),
    ): McpTool = McpTool(name, title, description, inputSchema, toolResultSchema, annotations)

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

    private fun schema(
        vararg required: String,
        props: Map<String, Map<String, Any>>,
    ): Map<String, Any> {
        val base: MutableMap<String, Any> = mutableMapOf("type" to "object", "properties" to props)
        if (required.isNotEmpty()) base["required"] = required.toList()
        return base
    }

    private fun str(desc: String): Map<String, Any> = mapOf("type" to "string", "description" to desc)

    private fun num(desc: String): Map<String, Any> = mapOf("type" to "number", "description" to desc)

    private val rootPathProp: Map<String, Any> =
        mapOf(
            "type" to "string",
            "description" to "Absolute path of the target Android project root.",
        )

    private fun unsupported(name: String): Map<String, Any> =
        context.resultMap(
            ToolResult(status = ResultStatus.UNSUPPORTED, summary = "Unknown device-read tool: $name", warnings = listOf("unknown-tool")),
        )

    private fun requireSerial(arguments: Map<String, Any?>): String? = arguments["deviceSerial"]?.toString()?.takeIf { it.isNotBlank() }

    private val adbPath: String get() = context.config.safety.adbPath

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
    ): Map<String, Any> =
        context.resultMap(
            ToolResult(status = ResultStatus.BLOCKED, summary = reason, warnings = listOf(code)),
        )

    private fun runAdbText(
        serial: String,
        shellArgs: List<String>,
        id: String,
        root: Path,
        timeoutSeconds: Long = 60,
    ): ToolResult =
        context.run(
            root,
            CommandSpec(
                id = id,
                command = listOf(adbPath, "-s", serial, "shell") + shellArgs.map(ShellQuote::quote),
                workingDirectory = root.toString(),
                mutatesProject = false,
                requiresDevice = true,
                timeoutSeconds = timeoutSeconds,
                outputMode = OutputMode.TEXT,
                sensitivity = ArtifactSensitivity.SENSITIVE,
            ),
        )

    private fun permissionAudit(arguments: Map<String, Any?>): Map<String, Any> {
        val serial = requireSerial(arguments) ?: return missingSerial("android_permission_audit")
        val packageName =
            arguments["packageName"]?.toString()?.takeIf { it.isNotBlank() }
                ?: return blocked("missing-package", "packageName is required for android_permission_audit.")
        val root = context.resolveRoot(arguments)
        val runResult = runAdbText(serial, listOf("dumpsys", "package", packageName), "adb-permission-audit", root)
        if (runResult.status == ResultStatus.BLOCKED) return context.resultMap(runResult)
        val logArtifact = runResult.artifacts.firstOrNull()
        val logText = logArtifact?.let { runCatching { Files.readString(Path.of(it.path)) }.getOrDefault("") } ?: ""
        val parsed = PermissionAuditParser.parse(packageName, logText)
        val summary =
            buildString {
                append("Audited ${parsed.entries.size} permission(s) for $packageName")
                val runtime = parsed.entries.count { it.runtime }
                val granted = parsed.entries.count { it.runtime && it.granted }
                append(" ($granted/$runtime runtime granted).")
            }
        return context.resultMapWithFindings(
            runResult.copy(summary = summary),
            parsed.findings,
        ) + mapOf("permissions" to parsed.entries.map { it.toMap() })
    }

    private fun PermissionAuditEntry.toMap(): Map<String, Any> = mapOf("name" to name, "granted" to granted, "runtime" to runtime)

    private fun dumpsys(arguments: Map<String, Any?>): Map<String, Any> {
        val serial = requireSerial(arguments) ?: return missingSerial("android_dumpsys")
        val presetName =
            arguments["preset"]?.toString()?.takeIf { it.isNotBlank() }
                ?: return blocked("missing-preset", "preset is required for android_dumpsys.")
        val preset =
            DumpsysPreset.entries.firstOrNull { it.wireName.equals(presetName, ignoreCase = true) }
                ?: return blocked(
                    "invalid-dumpsys-preset",
                    "Unknown dumpsys preset '$presetName'. Use meminfo, gfxinfo, cpuinfo, batterystats, or package.",
                )
        val packageName = arguments["packageName"]?.toString()?.takeIf { it.isNotBlank() }
        if (preset == DumpsysPreset.PACKAGE && packageName.isNullOrBlank()) {
            return blocked("missing-package", "packageName is required when preset is package.")
        }
        val root = context.resolveRoot(arguments)
        val shellArgs =
            if (preset ==
                DumpsysPreset.PACKAGE
            ) {
                listOf("dumpsys", preset.service, packageName!!)
            } else {
                listOf("dumpsys", preset.service)
            }
        val runResult = runAdbText(serial, shellArgs, "adb-dumpsys-${preset.wireName}", root)
        if (runResult.status == ResultStatus.BLOCKED) return context.resultMap(runResult)
        val logText = runResult.artifacts.firstOrNull()?.let { runCatching { Files.readString(Path.of(it.path)) }.getOrDefault("") } ?: ""
        val parsed = DumpsysSummaryParser.parse(preset, serial, logText, packageName)
        val summary = "Captured dumpsys ${preset.service} for $serial."
        return context.resultMapWithFindings(runResult.copy(summary = summary), parsed.findings) +
            mapOf("dumpsysSummary" to mapOf("summary" to parsed.summary, "provenance" to parsed.provenance))
    }

    private fun memorySummary(arguments: Map<String, Any?>): Map<String, Any> {
        val serial = requireSerial(arguments) ?: return missingSerial("android_memory_summary")
        val root = context.resolveRoot(arguments)
        val runResult = runAdbText(serial, listOf("dumpsys", "meminfo"), "adb-memory-summary", root)
        if (runResult.status == ResultStatus.BLOCKED) return context.resultMap(runResult)
        val logText = runResult.artifacts.firstOrNull()?.let { runCatching { Files.readString(Path.of(it.path)) }.getOrDefault("") } ?: ""
        val parsed = DumpsysSummaryParser.parse(DumpsysPreset.MEMINFO, serial, logText)
        val summary = "Captured memory summary for $serial."
        return context.resultMapWithFindings(runResult.copy(summary = summary), parsed.findings) +
            mapOf("memorySummary" to mapOf("summary" to parsed.summary, "provenance" to parsed.provenance))
    }

    private fun batterySummary(arguments: Map<String, Any?>): Map<String, Any> {
        val serial = requireSerial(arguments) ?: return missingSerial("android_battery_summary")
        val root = context.resolveRoot(arguments)
        val runResult = runAdbText(serial, listOf("dumpsys", "battery"), "adb-battery-summary", root)
        if (runResult.status == ResultStatus.BLOCKED) return context.resultMap(runResult)
        val logText = runResult.artifacts.firstOrNull()?.let { runCatching { Files.readString(Path.of(it.path)) }.getOrDefault("") } ?: ""
        val parsed = DumpsysSummaryParser.parseBattery(serial, logText)
        val summary = "Captured battery summary for $serial."
        return context.resultMapWithFindings(runResult.copy(summary = summary), parsed.findings) +
            mapOf("batterySummary" to mapOf("summary" to parsed.summary, "provenance" to parsed.provenance))
    }

    private fun bugreport(arguments: Map<String, Any?>): Map<String, Any> {
        val serial = requireSerial(arguments) ?: return missingSerial("android_bugreport")
        val decision =
            context.authorize(
                OperationRequest(
                    operationId = "android_bugreport",
                    requiredCapabilities = setOf(com.droidagentkit.core.Capability.SENSITIVE_DIAGNOSTICS),
                    destructive = false,
                    deviceSerial = serial,
                ),
            )
        if (decision is AuthorizationDecision.Denied) {
            return context.resultMap(
                ToolResult(status = ResultStatus.BLOCKED, summary = decision.reason, warnings = listOf(decision.code)),
            )
        }
        val root = context.resolveRoot(arguments)
        val outputDir = context.artifactOutputDir(root)
        val bugDir = outputDir.resolve("bugreport-${context.safeId(serial)}-${System.currentTimeMillis()}")
        Files.createDirectories(bugDir)
        val runResult =
            context.run(
                root,
                CommandSpec(
                    id = "adb-bugreport",
                    command = listOf(adbPath, "-s", serial, "bugreport", bugDir.toString()),
                    workingDirectory = root.toString(),
                    mutatesProject = false,
                    requiresDevice = true,
                    timeoutSeconds = 300,
                    outputMode = OutputMode.TEXT,
                    sensitivity = ArtifactSensitivity.SENSITIVE,
                ),
            )
        if (runResult.status == ResultStatus.BLOCKED) return context.resultMap(runResult)
        val zip =
            Files
                .list(bugDir)
                .use { it.filter { p -> p.fileName.toString().endsWith(".zip") }.toList() }
                .maxByOrNull { Files.getLastModifiedTime(it).toMillis() }
        if (zip == null) {
            return context.resultMap(
                runResult.copy(
                    status = ResultStatus.PARTIAL,
                    summary = "Bugreport command finished but no zip was produced in $bugDir.",
                    warnings = runResult.warnings + "no-bugreport-zip-found",
                ),
            )
        }
        val artifact =
            context.registerExistingArtifact(
                root,
                zip,
                ArtifactType.BUGREPORT,
                "Android bugreport ZIP captured via adb bugreport. Sensitive: contains device diagnostics.",
                ArtifactSensitivity.SENSITIVE,
            )
        return context.resultMap(
            runResult.copy(
                summary = "Captured bugreport for $serial (${artifact.sizeBytes} bytes).",
                artifacts = runResult.artifacts + artifact,
            ),
        )
    }

    private fun logcatStart(arguments: Map<String, Any?>): Map<String, Any> {
        val serial = requireSerial(arguments) ?: return missingSerial("android_logcat_start")
        val root = context.resolveRoot(arguments)
        val packageName = arguments["packageName"]?.toString()?.takeIf { it.isNotBlank() }
        val filter = arguments["filter"]?.toString()?.takeIf { it.isNotBlank() }
        val durationSeconds = (arguments["durationSeconds"]?.toString()?.toLongOrNull() ?: 30L).coerceIn(1L, 300L)
        val filterTokens =
            if (filter.isNullOrBlank()) {
                emptyList()
            } else {
                filter.split(Regex("\\s+")).filter { it.isNotBlank() }
            }
        if (filterTokens.size > 8) return blocked("filter-too-long", "logcat filter is bounded to 8 tokens.")
        val invalidToken = filterTokens.firstOrNull { !it.matches(Regex("^[A-Za-z0-9_*:.-]+$")) }
        if (invalidToken !=
            null
        ) {
            return blocked(
                "invalid-filter-token",
                "logcat filter token '$invalidToken' contains unsupported characters.",
            )
        }
        val warnings = mutableListOf<String>()
        val logcatArgs = mutableListOf("logcat")
        if (packageName != null) {
            val pidResult = runAdbText(serial, listOf("pidof", packageName), "adb-pidof", root, timeoutSeconds = 10)
            val pidText =
                pidResult.artifacts.firstOrNull()?.let { runCatching { Files.readString(Path.of(it.path)) }.getOrDefault("") } ?: ""
            val pid = Regex("\\d+").find(pidText)?.value
            if (pid != null) {
                logcatArgs.add("--pid=$pid")
            } else {
                warnings += "package-process-not-found"
            }
        }
        logcatArgs.addAll(filterTokens)
        val jobId = "logcat-${context.safeId(serial)}-${System.currentTimeMillis()}"
        val decision =
            context.authorize(
                OperationRequest(
                    operationId = "android_logcat_start",
                    requiredCapabilities = emptySet(),
                    destructive = false,
                    deviceSerial = serial,
                    mutating = false,
                ),
            )
        if (decision is AuthorizationDecision.Denied) {
            return context.resultMap(ToolResult(status = ResultStatus.BLOCKED, summary = decision.reason, warnings = listOf(decision.code)))
        }
        val operation = (decision as AuthorizationDecision.Allowed).operation
        val spec =
            CommandSpec(
                id = jobId,
                command = listOf(adbPath, "-s", serial, "shell") + logcatArgs,
                workingDirectory = root.toString(),
                mutatesProject = false,
                requiresDevice = true,
                timeoutSeconds = durationSeconds,
                outputMode = OutputMode.TEXT,
                sensitivity = ArtifactSensitivity.SENSITIVE,
            )
        val snapshot =
            context.jobRunner().start(
                ManagedJobSpec(
                    id = jobId,
                    operation = operation,
                    command = spec,
                    timeoutSeconds = durationSeconds + 5,
                    cleanup = {},
                ),
            )
        if (snapshot.state == JobState.PENDING) {
            return context.resultMap(
                ToolResult(
                    status = ResultStatus.BLOCKED,
                    summary = "Job could not start: ${snapshot.warnings.joinToString(", ")}.",
                    warnings = snapshot.warnings,
                ),
            )
        }
        val logUri = context.artifactOutputDir(root).resolve("$jobId.log").toString()
        return context.resultMap(
            ToolResult(
                status = ResultStatus.SUCCESS,
                summary = "Started logcat job $jobId on $serial for ${durationSeconds}s.",
                warnings = warnings,
            ),
        ) + mapOf("jobId" to jobId, "jobState" to jobStatusWire(snapshot.state), "logUri" to logUri)
    }

    private fun jobStatus(arguments: Map<String, Any?>): Map<String, Any> {
        val jobId =
            arguments["jobId"]?.toString()?.takeIf { it.isNotBlank() }
                ?: return blocked("missing-job-id", "jobId is required for android_job_status.")
        val snapshot = context.jobRunner().status(jobId)
        return jobEnvelope(snapshot, jobId, "status")
    }

    private fun jobCancel(arguments: Map<String, Any?>): Map<String, Any> {
        val jobId =
            arguments["jobId"]?.toString()?.takeIf { it.isNotBlank() }
                ?: return blocked("missing-job-id", "jobId is required for android_job_cancel.")
        val snapshot = context.jobRunner().cancel(jobId)
        return jobEnvelope(snapshot, jobId, "cancel")
    }

    private fun jobEnvelope(
        snapshot: com.droidagentkit.core.JobSnapshot,
        jobId: String,
        action: String,
    ): Map<String, Any> =
        mapOf(
            "schemaVersion" to "1.0",
            "status" to jobStatusWire(snapshot.state),
            "summary" to "Job $jobId $action: ${snapshot.state.name.lowercase()}.",
            "artifacts" to listOfNotNull(snapshot.artifact).map(Json::artifactToMap),
            "redactionsApplied" to emptyList<String>(),
            "warnings" to snapshot.warnings,
            "jobId" to jobId,
            "jobState" to jobStatusWire(snapshot.state),
            "redactedTail" to snapshot.redactedTail,
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
