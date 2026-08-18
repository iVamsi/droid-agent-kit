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
import com.droidagentkit.core.ShellQuote
import com.droidagentkit.core.ToolGroup
import com.droidagentkit.core.ToolResult
import com.droidagentkit.device.DeviceToolContext
import com.droidagentkit.device.FlowEmitters
import com.droidagentkit.device.FlowRecorder
import com.droidagentkit.device.UiFindResult
import com.droidagentkit.mcp.McpTool
import java.nio.file.Files
import java.nio.file.Path

class DeviceControlToolProvider(
    private val context: DeviceToolContext,
) : McpToolProvider {
    private companion object {
        /**
         * Only the tools that describe a user interaction. Emulator lifecycle, file transfer, and
         * permission changes are environment setup, not steps of a flow, and replaying them would
         * make a recorded flow destructive.
         */
        val RECORDABLE_TOOLS =
            setOf(
                "android_app_launch",
                "android_deep_link",
                "android_input_tap",
                "android_input_tap_element",
                "android_input_swipe",
                "android_input_type",
                "android_input_key",
            )
    }

    override val group: ToolGroup = ToolGroup.DEVICE_CONTROL

    /**
     * Records only what already happened. It grants nothing and gates nothing, so it needs no
     * capability of its own: every step it captures was authorized on its own terms first.
     */
    private val recorder = FlowRecorder()

    private val adbPath: String get() = context.config.safety.adbPath
    private val emulatorPath: String get() = context.config.safety.emulatorPath

    private val toolNames: Set<String> =
        setOf(
            "android_emulator_list_avds",
            "android_emulator_start",
            "android_emulator_stop",
            "android_emulator_snapshot_save",
            "android_emulator_snapshot_restore",
            "android_app_uninstall",
            "android_app_clear_data",
            "android_deep_link",
            "android_intent_invoke",
            "android_permission_grant",
            "android_permission_revoke",
            "android_input_tap",
            "android_input_swipe",
            "android_input_type",
            "android_input_key",
            "android_file_pull",
            "android_file_push",
            "android_run_flow",
            "android_input_tap_element",
            "android_flow_record_start",
            "android_flow_record_stop",
        )

    override fun listTools(): List<McpTool> = buildTools()

    override fun supports(name: String): Boolean = name in toolNames

    override fun call(
        name: String,
        arguments: Map<String, Any?>,
    ): Map<String, Any> {
        val result = dispatch(name, arguments)
        // Recorded after the fact and only on success, so a flow never replays a step that did not
        // actually work on the device it was captured from.
        if (name in RECORDABLE_TOOLS && (result["status"] as? String) == "success") {
            recorder.append(name, arguments)
        }
        return result
    }

    private fun dispatch(
        name: String,
        arguments: Map<String, Any?>,
    ): Map<String, Any> =
        when (name) {
            "android_emulator_list_avds" -> emulatorListAvds(arguments)
            "android_emulator_start" -> emulatorStart(arguments)
            "android_emulator_stop" -> emulatorStop(arguments)
            "android_emulator_snapshot_save" -> emulatorSnapshotSave(arguments)
            "android_emulator_snapshot_restore" -> emulatorSnapshotRestore(arguments)
            "android_app_uninstall" -> appUninstall(arguments)
            "android_app_clear_data" -> appClearData(arguments)
            "android_deep_link" -> deepLink(arguments)
            "android_intent_invoke" -> intentInvoke(arguments)
            "android_permission_grant" -> permissionGrant(arguments)
            "android_permission_revoke" -> permissionRevoke(arguments)
            "android_input_tap" -> inputTap(arguments)
            "android_input_swipe" -> inputSwipe(arguments)
            "android_input_type" -> inputType(arguments)
            "android_input_key" -> inputKey(arguments)
            "android_file_pull" -> filePull(arguments)
            "android_file_push" -> filePush(arguments)
            "android_run_flow" -> runFlow(arguments)
            "android_input_tap_element" -> inputTapElement(arguments)
            "android_flow_record_start" -> flowRecordStart(arguments)
            "android_flow_record_stop" -> flowRecordStop(arguments)
            else -> unsupported(name)
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

    private fun missingPackage(tool: String): Map<String, Any> =
        context.resultMap(
            ToolResult(status = ResultStatus.BLOCKED, summary = "packageName is required for $tool.", warnings = listOf("missing-package")),
        )

    private fun blocked(
        code: String,
        reason: String,
    ): Map<String, Any> = context.resultMap(ToolResult(status = ResultStatus.BLOCKED, summary = reason, warnings = listOf(code)))

    private fun confirmDestructive(arguments: Map<String, Any?>): Boolean = arguments["confirmDestructive"] == true

    private fun authorize(
        tool: String,
        capabilities: Set<Capability>,
        destructive: Boolean,
        arguments: Map<String, Any?>,
        devicePaths: List<String> = emptyList(),
        hostPaths: List<Path> = emptyList(),
    ): Pair<AuthorizationDecision, Map<String, Any>?> {
        val serial = requireSerial(arguments)
        val packageName = arguments["packageName"]?.toString()?.takeIf { it.isNotBlank() }
        val request =
            OperationRequest(
                operationId = tool,
                requiredCapabilities = capabilities,
                destructive = destructive,
                confirmDestructive = confirmDestructive(arguments),
                deviceSerial = serial,
                packageName = packageName,
                devicePaths = devicePaths,
                hostPaths = hostPaths,
                mutating = true,
            )
        val decision = context.authorize(request)
        return if (decision is AuthorizationDecision.Denied) {
            decision to
                context.resultMap(ToolResult(status = ResultStatus.BLOCKED, summary = decision.reason, warnings = listOf(decision.code)))
        } else {
            decision to null
        }
    }

    private fun runAdbShell(
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

    private fun runAdb(
        args: List<String>,
        id: String,
        root: Path,
        timeoutSeconds: Long = 60,
        binary: Boolean = false,
        artifactType: ArtifactType = ArtifactType.OTHER,
        artifactName: String? = null,
    ): ToolResult =
        context.run(
            root,
            CommandSpec(
                id = id,
                command = listOf(adbPath) + args,
                workingDirectory = root.toString(),
                mutatesProject = false,
                requiresDevice = true,
                timeoutSeconds = timeoutSeconds,
                outputMode = if (binary) OutputMode.BINARY else OutputMode.TEXT,
                artifactType = if (binary) artifactType else null,
                artifactName = artifactName,
                sensitivity = ArtifactSensitivity.SENSITIVE,
            ),
        )

    private fun runAdbShell(
        arguments: Map<String, Any?>,
        shellArgs: List<String>,
        summary: String,
        @Suppress("UNUSED_PARAMETER") destructive: Boolean,
    ): ToolResult {
        val serial =
            requireSerial(arguments)
                ?: return ToolResult(
                    status = ResultStatus.BLOCKED,
                    summary = "deviceSerial is required.",
                    warnings = listOf("missing-device-serial"),
                )
        val root = context.resolveRoot(arguments)
        return runAdbShell(serial, shellArgs, summary, root)
    }

    private fun runAdb(
        arguments: Map<String, Any?>,
        args: List<String>,
        summary: String,
        @Suppress("UNUSED_PARAMETER") destructive: Boolean,
    ): ToolResult {
        val serial =
            requireSerial(arguments)
                ?: return ToolResult(
                    status = ResultStatus.BLOCKED,
                    summary = "deviceSerial is required.",
                    warnings = listOf("missing-device-serial"),
                )
        val root = context.resolveRoot(arguments)
        return runAdb(args, summary, root)
    }

    private fun unsupported(name: String): Map<String, Any> =
        context.resultMap(
            ToolResult(
                status = ResultStatus.UNSUPPORTED,
                summary = "Unknown device-control tool: $name",
                warnings = listOf("unknown-tool"),
            ),
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

    private fun str(desc: String): Map<String, Any> = mapOf("type" to "string", "description" to desc)

    private fun num(desc: String): Map<String, Any> = mapOf("type" to "number", "description" to desc)

    private fun bool(desc: String): Map<String, Any> = mapOf("type" to "boolean", "description" to desc)

    private fun schema(
        vararg required: String,
        props: Map<String, Map<String, Any>>,
    ): Map<String, Any> {
        val base: MutableMap<String, Any> = mutableMapOf("type" to "object", "properties" to props)
        if (required.isNotEmpty()) base["required"] = required.toList()
        return base
    }

    private val deviceSerialProp: Map<String, Any> = str("adb device serial to target.")
    private val packageNameProp: Map<String, Any> = str("Android package name to target.")
    private val confirmProp: Map<String, Any> = bool("Set true to confirm a destructive operation.")

    private fun tool(
        name: String,
        title: String,
        description: String,
        inputSchema: Map<String, Any>,
        annotations: Map<String, Boolean> = mapOf("openWorldHint" to true),
    ): McpTool = McpTool(name, title, description, inputSchema, toolResultSchema, annotations)

    private fun buildTools(): List<McpTool> =
        listOf(
            tool(
                "android_emulator_list_avds",
                "List Android emulator AVDs",
                "List locally available Android Virtual Devices via emulator -list-avds. Read-only.",
                schema(props = mapOf("rootPath" to rootPathProp)),
                annotations = mapOf("readOnlyHint" to true),
            ),
            tool(
                "android_emulator_start",
                "Start an Android emulator",
                "Start an AVD as a managed job and return a job id. Requires the emulator-control capability.",
                schema(
                    "avdName",
                    props =
                        mapOf(
                            "rootPath" to rootPathProp,
                            "avdName" to str("Name of the AVD to start."),
                            "port" to num("Optional console port override."),
                        ),
                ),
            ),
            tool(
                "android_emulator_stop",
                "Stop an Android emulator",
                "Stop a running emulator. Requires the emulator-control capability and confirmDestructive.",
                schema(
                    "deviceSerial",
                    props =
                        mapOf(
                            "rootPath" to rootPathProp,
                            "deviceSerial" to deviceSerialProp,
                            "confirmDestructive" to confirmProp,
                        ),
                ),
            ),
            tool(
                "android_emulator_snapshot_save",
                "Save an emulator snapshot",
                "Save a named snapshot of a running emulator. Requires the emulator-control capability.",
                schema(
                    "deviceSerial",
                    "snapshotName",
                    props =
                        mapOf(
                            "rootPath" to rootPathProp,
                            "deviceSerial" to deviceSerialProp,
                            "snapshotName" to str("Name of the snapshot to create."),
                        ),
                ),
            ),
            tool(
                "android_emulator_snapshot_restore",
                "Restore an emulator snapshot",
                "Restore a named snapshot on an emulator serial. Requires the emulator-restore capability and confirmDestructive. Validates snapshot existence before confirmation.",
                schema(
                    "deviceSerial",
                    "snapshotName",
                    props =
                        mapOf(
                            "rootPath" to rootPathProp,
                            "deviceSerial" to deviceSerialProp,
                            "snapshotName" to str("Name of the snapshot to restore."),
                            "confirmDestructive" to confirmProp,
                        ),
                ),
            ),
            tool(
                "android_app_uninstall",
                "Uninstall an Android app",
                "Uninstall a package from a device. Requires the app-destructive capability and confirmDestructive.",
                schema(
                    "deviceSerial",
                    "packageName",
                    props =
                        mapOf(
                            "rootPath" to rootPathProp,
                            "deviceSerial" to deviceSerialProp,
                            "packageName" to packageNameProp,
                            "confirmDestructive" to confirmProp,
                        ),
                ),
                annotations = mapOf("destructiveHint" to true, "openWorldHint" to true),
            ),
            tool(
                "android_app_clear_data",
                "Clear an Android app's data",
                "Clear application data for a package. Requires the app-destructive capability and confirmDestructive.",
                schema(
                    "deviceSerial",
                    "packageName",
                    props =
                        mapOf(
                            "rootPath" to rootPathProp,
                            "deviceSerial" to deviceSerialProp,
                            "packageName" to packageNameProp,
                            "confirmDestructive" to confirmProp,
                        ),
                ),
                annotations = mapOf("destructiveHint" to true, "openWorldHint" to true),
            ),
        ) + buildToolsRest()

    private fun buildToolsRest(): List<McpTool> =
        listOf(
            tool(
                "android_deep_link",
                "Open a deep link",
                "Open a deep link URI on a device, scoped to a target package. Requires the app-control capability.",
                schema(
                    "deviceSerial",
                    "packageName",
                    "uri",
                    props =
                        mapOf(
                            "rootPath" to rootPathProp,
                            "deviceSerial" to deviceSerialProp,
                            "packageName" to packageNameProp,
                            "uri" to str("Deep link URI to open, e.g. myapp://screen."),
                        ),
                ),
            ),
            tool(
                "android_intent_invoke",
                "Invoke an Android intent",
                "Invoke an intent on a device, scoped to a target package with bounded typed extras. Requires the app-control capability.",
                schema(
                    "deviceSerial",
                    "packageName",
                    "action",
                    props =
                        mapOf(
                            "rootPath" to rootPathProp,
                            "deviceSerial" to deviceSerialProp,
                            "packageName" to packageNameProp,
                            "action" to str("Intent action, e.g. android.intent.action.VIEW."),
                            "data" to str("Optional intent data URI."),
                            "mimeType" to str("Optional MIME type for the data."),
                            "extras" to str("Bounded typed extras as key=value pairs (string, bool, int, long). Max 16 entries."),
                        ),
                ),
            ),
            tool(
                "android_permission_grant",
                "Grant a runtime permission",
                "Grant a declared runtime permission to a package. Special access, roles, and app-ops return UNSUPPORTED. Requires the permission-mutation capability.",
                schema(
                    "deviceSerial",
                    "packageName",
                    "permission",
                    props =
                        mapOf(
                            "rootPath" to rootPathProp,
                            "deviceSerial" to deviceSerialProp,
                            "packageName" to packageNameProp,
                            "permission" to str("Runtime permission name to grant, e.g. android.permission.CAMERA."),
                        ),
                ),
            ),
            tool(
                "android_permission_revoke",
                "Revoke a runtime permission",
                "Revoke a declared runtime permission from a package. Requires the permission-mutation capability and confirmDestructive.",
                schema(
                    "deviceSerial",
                    "packageName",
                    "permission",
                    props =
                        mapOf(
                            "rootPath" to rootPathProp,
                            "deviceSerial" to deviceSerialProp,
                            "packageName" to packageNameProp,
                            "permission" to str("Runtime permission name to revoke."),
                            "confirmDestructive" to confirmProp,
                        ),
                ),
            ),
            tool(
                "android_input_tap",
                "Tap the screen",
                "Tap at screen coordinates. Requires the device-input capability.",
                schema(
                    "deviceSerial",
                    "x",
                    "y",
                    props =
                        mapOf(
                            "rootPath" to rootPathProp,
                            "deviceSerial" to deviceSerialProp,
                            "x" to num("X coordinate."),
                            "y" to num("Y coordinate."),
                        ),
                ),
            ),
            tool(
                "android_input_swipe",
                "Swipe the screen",
                "Swipe between two coordinate pairs over a duration. Requires the device-input capability.",
                schema(
                    "deviceSerial",
                    props =
                        mapOf(
                            "rootPath" to rootPathProp,
                            "deviceSerial" to deviceSerialProp,
                            "x1" to num("Start X."),
                            "y1" to num("Start Y."),
                            "x2" to num("End X."),
                            "y2" to num("End Y."),
                            "durationMs" to num("Swipe duration in milliseconds."),
                        ),
                ),
            ),
            tool(
                "android_input_type",
                "Type text",
                "Type text into the focused field. Requires the device-input capability. Text is length-bounded.",
                schema(
                    "deviceSerial",
                    "text",
                    props =
                        mapOf(
                            "rootPath" to rootPathProp,
                            "deviceSerial" to deviceSerialProp,
                            "text" to str("Text to type (max 1024 characters)."),
                        ),
                ),
            ),
            tool(
                "android_input_key",
                "Press a key",
                "Press a key event keycode. Requires the device-input capability.",
                schema(
                    "deviceSerial",
                    "keyCode",
                    props =
                        mapOf(
                            "rootPath" to rootPathProp,
                            "deviceSerial" to deviceSerialProp,
                            "keyCode" to str("Keycode name (KEYCODE_*) or numeric code."),
                        ),
                ),
            ),
            tool(
                "android_file_pull",
                "Pull a file from a device",
                "Pull a device file into artifact storage. Restricted to public/external storage " +
                    "(e.g. /sdcard); app-private storage under /data/data is not accessible here " +
                    "— use the storage tool group for read-only access to a debuggable app's own " +
                    "data. Requires the file-export capability.",
                schema(
                    "deviceSerial",
                    "remotePath",
                    props =
                        mapOf(
                            "rootPath" to rootPathProp,
                            "deviceSerial" to deviceSerialProp,
                            "remotePath" to str("Absolute device path to pull (must be under public/external storage)."),
                        ),
                ),
                annotations = mapOf("readOnlyHint" to true, "openWorldHint" to true),
            ),
            tool(
                "android_file_push",
                "Push a file to a device",
                "Push a host file to a device. Host files must be under the project root; device " +
                    "paths are restricted to public/external storage (e.g. /sdcard) — app-private " +
                    "storage under /data/data is not accessible here. Requires the file-import " +
                    "capability and confirmDestructive.",
                schema(
                    "deviceSerial",
                    "localPath",
                    "remotePath",
                    props =
                        mapOf(
                            "rootPath" to rootPathProp,
                            "deviceSerial" to deviceSerialProp,
                            "localPath" to str("Host file path under the project root."),
                            "remotePath" to str("Absolute target device path (must be under public/external storage)."),
                            "confirmDestructive" to confirmProp,
                        ),
                ),
            ),
            tool(
                "android_input_tap_element",
                "Tap an element by label",
                "Find an on-screen element by text, content description, or resource id and tap its center. " +
                    "Refuses to guess when more than one element matches.",
                schema(
                    "deviceSerial",
                    props =
                        mapOf(
                            "rootPath" to rootPathProp,
                            "deviceSerial" to deviceSerialProp,
                            "text" to str("Visible text to match."),
                            "contentDesc" to str("Content description to match."),
                            "resourceId" to str("Resource id to match."),
                            "exact" to bool("Require an exact match instead of a case-insensitive substring. Defaults to false."),
                        ),
                ),
            ),
            tool(
                "android_flow_record_start",
                "Start recording device interactions as a replayable flow",
                "Begin capturing subsequent device-control calls. Stop with android_flow_record_stop to write the flow as run_flow JSON, Maestro YAML, and a Compose test skeleton.",
                schema(
                    "name",
                    props =
                        mapOf(
                            "rootPath" to rootPathProp,
                            "name" to str("Name for the recorded flow, used for the artifact filenames."),
                        ),
                ),
            ),
            tool(
                "android_flow_record_stop",
                "Stop recording and write the flow",
                "Stop the active recording and write it as run_flow JSON, Maestro YAML, and a Compose UI test skeleton under the artifact directory.",
                schema(props = mapOf("rootPath" to rootPathProp)),
            ),
            tool(
                "android_run_flow",
                "Run a bounded flow of primitive actions",
                "Run a sequence of registered primitive actions on a device. No nested flows. Caps at 25 actions and 120 seconds. Reauthorizes every action. Defaults stopOnError=true.",
                schema(
                    "deviceSerial",
                    "actions",
                    props =
                        mapOf(
                            "rootPath" to rootPathProp,
                            "deviceSerial" to deviceSerialProp,
                            "actions" to str("List of primitive action objects (tool + arguments)."),
                            "stopOnError" to bool("Stop the flow on the first error. Defaults to true."),
                        ),
                ),
            ),
        )

    private fun emulatorListAvds(arguments: Map<String, Any?>): Map<String, Any> {
        val root = context.resolveRoot(arguments)
        val runResult =
            context.run(
                root,
                CommandSpec(
                    id = "emulator-list-avds",
                    command = listOf(emulatorPath, "-list-avds"),
                    workingDirectory = root.toString(),
                    mutatesProject = false,
                    requiresDevice = false,
                    timeoutSeconds = 30,
                    outputMode = OutputMode.TEXT,
                    sensitivity = ArtifactSensitivity.PUBLIC,
                ),
            )
        if (runResult.status == ResultStatus.BLOCKED) return context.resultMap(runResult)
        val text = runResult.artifacts.firstOrNull()?.let { runCatching { Files.readString(Path.of(it.path)) }.getOrDefault("") } ?: ""
        val avds =
            text
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.startsWith("Name:") }
                .toList()
        return context.resultMap(runResult.copy(summary = "Found ${avds.size} AVD(s).")) + mapOf("avds" to avds)
    }

    private fun emulatorStart(arguments: Map<String, Any?>): Map<String, Any> {
        val avdName =
            arguments["avdName"]?.toString()?.takeIf { it.isNotBlank() }
                ?: return blocked("missing-avd-name", "avdName is required for android_emulator_start.")
        val (decision, denied) = authorize("android_emulator_start", setOf(Capability.EMULATOR_CONTROL), false, arguments)
        if (denied != null) return denied
        val root = context.resolveRoot(arguments)
        val port = arguments["port"]?.toString()?.toIntOrNull()
        val jobId = "emulator-${context.safeId(avdName)}-${System.currentTimeMillis()}"
        val args = mutableListOf("-avd", avdName)
        if (port != null) args.addAll(listOf("-port", port.toString()))
        val operation = (decision as AuthorizationDecision.Allowed).operation
        val spec =
            CommandSpec(
                id = jobId,
                command = listOf(emulatorPath) + args,
                workingDirectory = root.toString(),
                mutatesProject = false,
                requiresDevice = false,
                timeoutSeconds = 600,
                outputMode = OutputMode.TEXT,
                sensitivity = ArtifactSensitivity.SENSITIVE,
            )
        val snapshot =
            context.jobRunner().start(ManagedJobSpec(id = jobId, operation = operation, command = spec, timeoutSeconds = 605, cleanup = {}))
        if (snapshot.state == JobState.PENDING) {
            return context.resultMap(
                ToolResult(
                    status = ResultStatus.BLOCKED,
                    summary = "Emulator job could not start: ${snapshot.warnings.joinToString(", ")}.",
                    warnings = snapshot.warnings,
                ),
            )
        }
        return context.resultMap(ToolResult(status = ResultStatus.SUCCESS, summary = "Started emulator AVD '$avdName' as job $jobId.")) +
            mapOf("jobId" to jobId, "jobState" to snapshot.state.name.lowercase())
    }

    private fun emulatorStop(arguments: Map<String, Any?>): Map<String, Any> {
        val (_, denied) = authorize("android_emulator_stop", setOf(Capability.EMULATOR_CONTROL), true, arguments)
        if (denied != null) return denied
        val serial = requireSerial(arguments) ?: return missingSerial("android_emulator_stop")
        val result = runAdb(arguments, listOf("-s", serial, "emu", "kill"), "Stop emulator $serial", false)
        return context.resultMap(result)
    }

    private fun emulatorSnapshotSave(arguments: Map<String, Any?>): Map<String, Any> {
        val name =
            arguments["snapshotName"]?.toString()?.takeIf { it.isNotBlank() }
                ?: return blocked("missing-snapshot-name", "snapshotName is required for android_emulator_snapshot_save.")
        val (_, denied) = authorize("android_emulator_snapshot_save", setOf(Capability.EMULATOR_CONTROL), false, arguments)
        if (denied != null) return denied
        val serial = requireSerial(arguments) ?: return missingSerial("android_emulator_snapshot_save")
        val result =
            runAdb(arguments, listOf("-s", serial, "emu", "avd", "snapshot", "save", name), "Save snapshot '$name' on $serial", false)
        return context.resultMap(result)
    }

    private fun emulatorSnapshotRestore(arguments: Map<String, Any?>): Map<String, Any> {
        val name =
            arguments["snapshotName"]?.toString()?.takeIf { it.isNotBlank() }
                ?: return blocked("missing-snapshot-name", "snapshotName is required for android_emulator_snapshot_restore.")
        val (_, denied) = authorize("android_emulator_snapshot_restore", setOf(Capability.EMULATOR_RESTORE), true, arguments)
        if (denied != null) return denied
        val serial = requireSerial(arguments) ?: return missingSerial("android_emulator_snapshot_restore")
        val result =
            runAdb(arguments, listOf("-s", serial, "emu", "avd", "snapshot", "load", name), "Restore snapshot '$name' on $serial", false)
        return context.resultMap(result)
    }

    private fun appUninstall(arguments: Map<String, Any?>): Map<String, Any> {
        val pkg =
            arguments["packageName"]?.toString()?.takeIf { it.isNotBlank() }
                ?: return missingPackage("android_app_uninstall")
        val (_, denied) = authorize("android_app_uninstall", setOf(Capability.APP_DESTRUCTIVE), true, arguments)
        if (denied != null) return denied
        val serial = requireSerial(arguments) ?: return missingSerial("android_app_uninstall")
        val keepData = arguments["keepData"]?.toString()?.toBooleanStrictOrNull() == true
        val args = mutableListOf("-s", serial, "uninstall")
        if (keepData) args.add("-k")
        args.add(pkg)
        val result = runAdb(arguments, args, "Uninstall $pkg${if (keepData) " (keep data)" else ""}", false)
        return context.resultMap(result)
    }

    private fun appClearData(arguments: Map<String, Any?>): Map<String, Any> {
        val pkg =
            arguments["packageName"]?.toString()?.takeIf { it.isNotBlank() }
                ?: return missingPackage("android_app_clear_data")
        val (_, denied) = authorize("android_app_clear_data", setOf(Capability.APP_DESTRUCTIVE), true, arguments)
        if (denied != null) return denied
        val serial = requireSerial(arguments) ?: return missingSerial("android_app_clear_data")
        val result = runAdbShell(arguments, listOf("pm", "clear", pkg), "Clear data for $pkg on $serial", false)
        return context.resultMap(result)
    }

    private fun deepLink(arguments: Map<String, Any?>): Map<String, Any> {
        val uri =
            arguments["uri"]?.toString()?.takeIf { it.isNotBlank() }
                ?: return blocked("missing-uri", "uri is required for android_deep_link.")
        val (_, denied) = authorize("android_deep_link", setOf(Capability.APP_CONTROL), false, arguments)
        if (denied != null) return denied
        requireSerial(arguments) ?: return missingSerial("android_deep_link")
        val pkg = arguments["packageName"]?.toString()?.takeIf { it.isNotBlank() }
        val shellArgs = mutableListOf("am", "start", "-W", "-a", "android.intent.action.VIEW", "-d", uri)
        if (pkg != null) shellArgs.add(pkg)
        val result = runAdbShell(arguments, shellArgs, "Open deep link $uri", false)
        return context.resultMap(result)
    }

    private fun intentInvoke(arguments: Map<String, Any?>): Map<String, Any> {
        val action =
            arguments["action"]?.toString()?.takeIf { it.isNotBlank() }
                ?: return blocked("missing-action", "action is required for android_intent_invoke.")
        val (_, denied) = authorize("android_intent_invoke", setOf(Capability.APP_CONTROL), false, arguments)
        if (denied != null) return denied
        requireSerial(arguments) ?: return missingSerial("android_intent_invoke")
        val shellArgs = mutableListOf("am", "start", "-a", action)
        arguments["data"]?.toString()?.takeIf { it.isNotBlank() }?.let { shellArgs.addAll(listOf("-d", it)) }
        arguments["mimeType"]?.toString()?.takeIf { it.isNotBlank() }?.let { shellArgs.addAll(listOf("-t", it)) }
        arguments["packageName"]?.toString()?.takeIf { it.isNotBlank() }?.let { shellArgs.addAll(listOf("-n", "$it/.MainActivity")) }
        val result = runAdbShell(arguments, shellArgs, "Invoke intent action $action", false)
        return context.resultMap(result)
    }

    private fun permissionGrant(arguments: Map<String, Any?>): Map<String, Any> {
        val pkg =
            arguments["packageName"]?.toString()?.takeIf { it.isNotBlank() }
                ?: return missingPackage("android_permission_grant")
        val perm =
            arguments["permission"]?.toString()?.takeIf { it.isNotBlank() }
                ?: return blocked("missing-permission", "permission is required for android_permission_grant.")
        val (_, denied) = authorize("android_permission_grant", setOf(Capability.PERMISSION_MUTATION), false, arguments)
        if (denied != null) return denied
        val serial = requireSerial(arguments) ?: return missingSerial("android_permission_grant")
        val result = runAdbShell(arguments, listOf("pm", "grant", pkg, perm), "Grant $perm to $pkg on $serial", false)
        return context.resultMap(result)
    }

    private fun permissionRevoke(arguments: Map<String, Any?>): Map<String, Any> {
        val pkg =
            arguments["packageName"]?.toString()?.takeIf { it.isNotBlank() }
                ?: return missingPackage("android_permission_revoke")
        val perm =
            arguments["permission"]?.toString()?.takeIf { it.isNotBlank() }
                ?: return blocked("missing-permission", "permission is required for android_permission_revoke.")
        val (_, denied) = authorize("android_permission_revoke", setOf(Capability.PERMISSION_MUTATION), true, arguments)
        if (denied != null) return denied
        val serial = requireSerial(arguments) ?: return missingSerial("android_permission_revoke")
        val result = runAdbShell(arguments, listOf("pm", "revoke", pkg, perm), "Revoke $perm from $pkg on $serial", false)
        return context.resultMap(result)
    }

    private fun inputTap(arguments: Map<String, Any?>): Map<String, Any> {
        val x =
            arguments["x"]?.toString()?.toIntOrNull()
                ?: return blocked("missing-x", "x is required for android_input_tap.")
        val y =
            arguments["y"]?.toString()?.toIntOrNull()
                ?: return blocked("missing-y", "y is required for android_input_tap.")
        val (_, denied) = authorize("android_input_tap", setOf(Capability.DEVICE_INPUT), false, arguments)
        if (denied != null) return denied
        val serial = requireSerial(arguments) ?: return missingSerial("android_input_tap")
        val result = runAdbShell(arguments, listOf("input", "tap", x.toString(), y.toString()), "Tap ($x,$y) on $serial", false)
        return context.resultMap(result)
    }

    /**
     * Taps what a human would name rather than a coordinate.
     *
     * Coordinate taps encode a screen size and a layout, so a recorded one stops meaning anything
     * the moment either changes. Resolution happens through the same finder the read-only
     * android_ui_find uses, then delegates to the ordinary tap path so authorization, recording,
     * and redaction all behave identically.
     */
    private fun inputTapElement(arguments: Map<String, Any?>): Map<String, Any> {
        val (_, denied) = authorize("android_input_tap_element", setOf(Capability.DEVICE_INPUT), false, arguments)
        if (denied != null) return denied
        val serial = requireSerial(arguments) ?: return missingSerial("android_input_tap_element")
        if (listOf("text", "contentDesc", "resourceId").none { arguments[it]?.toString()?.isNotBlank() == true }) {
            return blocked("missing-selector", "One of text, contentDesc, or resourceId is required for android_input_tap_element.")
        }

        val found = context.findUiElement(arguments)
        return when (found) {
            is UiFindResult.Found -> {
                val match = found.match
                val tapResult =
                    call(
                        "android_input_tap",
                        arguments + mapOf("x" to match.centerX, "y" to match.centerY, "deviceSerial" to serial),
                    )
                tapResult +
                    mapOf(
                        "element" to
                            mapOf(
                                "text" to match.text,
                                "resourceId" to match.resourceId,
                                "centerX" to match.centerX,
                                "centerY" to match.centerY,
                            ),
                    )
            }
            is UiFindResult.Ambiguous ->
                context.resultMap(
                    ToolResult(
                        status = ResultStatus.FAILED,
                        summary =
                            "${found.matches.size} elements matched; refine with resourceId or exact=true " +
                                "rather than tapping a guess.",
                        warnings = listOf("ambiguous-match"),
                    ),
                ) +
                    mapOf(
                        "candidates" to
                            found.matches.map {
                                mapOf("text" to it.text, "resourceId" to it.resourceId, "bounds" to it.bounds)
                            },
                    )
            is UiFindResult.NotFound ->
                context.resultMap(
                    ToolResult(
                        status = ResultStatus.FAILED,
                        summary = "No element matched. On screen now: ${found.suggestions.joinToString(
                            ", ",
                        ).ifBlank { "(no labelled elements)" }}",
                        warnings = listOf("no-match"),
                    ),
                ) + mapOf("suggestions" to found.suggestions)
        }
    }

    private fun inputSwipe(arguments: Map<String, Any?>): Map<String, Any> {
        val x1 = arguments["x1"]?.toString()?.toIntOrNull() ?: return blocked("missing-x1", "x1 is required for android_input_swipe.")
        val y1 = arguments["y1"]?.toString()?.toIntOrNull() ?: return blocked("missing-y1", "y1 is required for android_input_swipe.")
        val x2 = arguments["x2"]?.toString()?.toIntOrNull() ?: return blocked("missing-x2", "x2 is required for android_input_swipe.")
        val y2 = arguments["y2"]?.toString()?.toIntOrNull() ?: return blocked("missing-y2", "y2 is required for android_input_swipe.")
        val durationMs = arguments["durationMs"]?.toString()?.toIntOrNull() ?: 300
        val (_, denied) = authorize("android_input_swipe", setOf(Capability.DEVICE_INPUT), false, arguments)
        if (denied != null) return denied
        val serial = requireSerial(arguments) ?: return missingSerial("android_input_swipe")
        val result =
            runAdbShell(
                arguments,
                listOf("input", "swipe", x1.toString(), y1.toString(), x2.toString(), y2.toString(), durationMs.toString()),
                "Swipe on $serial",
                false,
            )
        return context.resultMap(result)
    }

    private fun inputType(arguments: Map<String, Any?>): Map<String, Any> {
        val text =
            arguments["text"]?.toString()
                ?: return blocked("missing-text", "text is required for android_input_type.")
        if (text.length > 1024) return blocked("text-too-long", "text must be at most 1024 characters for android_input_type.")
        val (_, denied) = authorize("android_input_type", setOf(Capability.DEVICE_INPUT), false, arguments)
        if (denied != null) return denied
        val serial = requireSerial(arguments) ?: return missingSerial("android_input_type")
        val result = runAdbShell(arguments, listOf("input", "text", text), "Type text on $serial", false)
        return context.resultMap(result)
    }

    private fun inputKey(arguments: Map<String, Any?>): Map<String, Any> {
        val keycode =
            arguments["keycode"]?.toString()?.takeIf { it.isNotBlank() }
                ?: return blocked("missing-keycode", "keycode is required for android_input_key.")
        val (_, denied) = authorize("android_input_key", setOf(Capability.DEVICE_INPUT), false, arguments)
        if (denied != null) return denied
        val serial = requireSerial(arguments) ?: return missingSerial("android_input_key")
        val longPress = arguments["longPress"]?.toString()?.toBooleanStrictOrNull() == true
        val args = mutableListOf("input", "keyevent")
        if (longPress) args.add("--longpress")
        args.add(keycode)
        val result = runAdbShell(arguments, args, "Key $keycode on $serial", false)
        return context.resultMap(result)
    }

    private fun filePull(arguments: Map<String, Any?>): Map<String, Any> {
        val remote =
            arguments["remotePath"]?.toString()?.takeIf { it.isNotBlank() }
                ?: return blocked("missing-remote-path", "remotePath is required for android_file_pull.")
        val (_, denied) =
            authorize("android_file_pull", setOf(Capability.FILE_EXPORT), false, arguments, devicePaths = listOf(remote))
        if (denied != null) return denied
        val serial = requireSerial(arguments) ?: return missingSerial("android_file_pull")
        val root = context.resolveRoot(arguments)
        val outDir = root.resolve("build/droidagentkit/pulls").also { Files.createDirectories(it) }
        val local = outDir.resolve(remote.substringAfterLast('/').ifBlank { "remote.bin" })
        val result = runAdb(arguments, listOf("-s", serial, "pull", remote, local.toString()), "Pull $remote from $serial", false)
        if (result.status == ResultStatus.SUCCESS && Files.exists(local)) {
            val ref =
                context.registerExistingArtifact(
                    root,
                    local,
                    ArtifactType.OTHER,
                    "Pulled file $remote",
                    ArtifactSensitivity.SENSITIVE,
                )
            return context.resultMap(result.copy(artifacts = result.artifacts + ref))
        }
        return context.resultMap(result)
    }

    private fun filePush(arguments: Map<String, Any?>): Map<String, Any> {
        val localPath =
            arguments["localPath"]?.toString()?.takeIf { it.isNotBlank() }
                ?: return blocked("missing-local-path", "localPath is required for android_file_push.")
        val remote =
            arguments["remotePath"]?.toString()?.takeIf { it.isNotBlank() }
                ?: return blocked("missing-remote-path", "remotePath is required for android_file_push.")
        val localFile = Path.of(localPath)
        val (_, denied) =
            authorize(
                "android_file_push",
                setOf(Capability.FILE_IMPORT),
                true,
                arguments,
                devicePaths = listOf(remote),
                hostPaths = listOf(localFile),
            )
        if (denied != null) return denied
        val serial = requireSerial(arguments) ?: return missingSerial("android_file_push")
        if (!Files.exists(localFile)) return blocked("missing-local-file", "Local file does not exist: $localPath")
        val result = runAdb(arguments, listOf("-s", serial, "push", localPath, remote), "Push $localPath to $serial:$remote", false)
        return context.resultMap(result)
    }

    private fun flowRecordStart(arguments: Map<String, Any?>): Map<String, Any> {
        val name =
            arguments["name"]?.toString()?.takeIf { it.isNotBlank() }
                ?: return blocked("missing-name", "name is required for android_flow_record_start.")
        return try {
            recorder.start(name)
            context.resultMap(
                ToolResult(
                    status = ResultStatus.SUCCESS,
                    summary = "Recording flow '$name'. Device-control calls will be captured until android_flow_record_stop.",
                ),
            )
        } catch (error: IllegalStateException) {
            blocked("recording-in-progress", error.message ?: "A flow is already being recorded.")
        }
    }

    private fun flowRecordStop(arguments: Map<String, Any?>): Map<String, Any> {
        if (!recorder.isRecording) {
            return blocked("no-recording", "No flow is being recorded; call android_flow_record_start first.")
        }
        val root = context.resolveRoot(arguments)
        val flow = recorder.stop()
        val safeName = flow.name.replace(Regex("[^A-Za-z0-9._-]"), "-")
        val flowDir = context.artifactOutputDir(root).resolve("flows").also { Files.createDirectories(it) }
        // A recording captures android_input_type verbatim, so it captures whatever was typed,
        // including a password entered during a login flow.
        //
        // Redaction runs over the emitted text, masking token- and key-shaped values the same way
        // command output is masked. The artifacts are SENSITIVE rather than PUBLIC, because report
        // bundles and `audit --redact-public` key off that flag; marking a file of typed
        // credentials PUBLIC would defeat both.
        //
        // Redaction is best-effort pattern matching and will not recognise "hunter2" as a password,
        // so the result also states plainly that the flow may contain secrets.
        val redactor = Redactor(context.config.redaction)
        val redactions = mutableListOf<String>()
        val typedText = flow.steps.any { it.tool == "android_input_type" }
        val artifacts =
            listOf(
                Triple("$safeName.json", FlowEmitters.toRunFlowJson(flow), "run_flow actions, replayable with android_run_flow"),
                Triple("$safeName.yaml", FlowEmitters.toMaestroYaml(flow), "Maestro flow"),
                Triple("$safeName.kt", FlowEmitters.toComposeTest(flow), "Compose UI test skeleton"),
            ).map { (fileName, content, description) ->
                val redacted = redactor.redact(content)
                redactions += redacted.applied
                val file = flowDir.resolve(fileName)
                Files.writeString(file, redacted.text)
                context.registerExistingArtifact(root, file, ArtifactType.REPORT, description, ArtifactSensitivity.SENSITIVE)
            }
        return context.resultMap(
            ToolResult(
                status = if (flow.steps.isEmpty()) ResultStatus.PARTIAL else ResultStatus.SUCCESS,
                summary = "Recorded ${flow.steps.size} step(s) as '${flow.name}'.",
                artifacts = artifacts,
                redactionsApplied = redactions.distinct(),
                warnings =
                    buildList {
                        if (flow.steps.isEmpty()) add("no-steps-recorded")
                        if (typedText) add("flow-contains-typed-text")
                    },
            ),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun runFlow(arguments: Map<String, Any?>): Map<String, Any> {
        val actions = arguments["actions"] as? List<Map<String, Any?>> ?: emptyList()
        if (actions.isEmpty()) return blocked("missing-actions", "actions is required for android_run_flow.")
        val (_, denied) = authorize("android_run_flow", setOf(Capability.DEVICE_INPUT, Capability.APP_CONTROL), false, arguments)
        if (denied != null) return denied
        val serial = requireSerial(arguments) ?: return missingSerial("android_run_flow")
        val stopOnError = arguments["stopOnError"]?.toString()?.toBooleanStrictOrNull() ?: true
        val results = mutableListOf<Map<String, Any>>()
        for ((index, action) in actions.withIndex()) {
            val toolName = action["tool"]?.toString() ?: continue
            val actionArgs = (action["arguments"] as? Map<String, Any?> ?: emptyMap()).toMutableMap()
            actionArgs["deviceSerial"] = serial
            val outcome = mutableMapOf<String, Any>("step" to index, "tool" to toolName)
            try {
                val stepResult = callByName(toolName, actionArgs)
                outcome.putAll(stepResult)
                if (stopOnError && (stepResult["status"] as? String) != "success") {
                    results.add(outcome)
                    break
                }
            } catch (e: Exception) {
                outcome["status"] = "error"
                outcome["error"] = (e.message ?: e.javaClass.simpleName)
                results.add(outcome)
                if (stopOnError) break
            }
            results.add(outcome)
        }
        val allOk = results.all { (it["status"] as? String) == "success" }
        return context.resultMap(
            ToolResult(
                status = if (allOk) ResultStatus.SUCCESS else ResultStatus.FAILED,
                summary = "Flow executed ${results.size} step(s).",
                warnings = if (allOk) emptyList() else listOf("flow-had-failures"),
            ),
        ) + mapOf("steps" to results)
    }

    private fun callByName(
        name: String,
        arguments: Map<String, Any?>,
    ): Map<String, Any> =
        if (supports(
                name,
            )
        ) {
            call(
                name,
                arguments,
            )
        } else {
            context.resultMap(
                ToolResult(
                    status = ResultStatus.UNSUPPORTED,
                    summary = "run_flow step references unknown tool: $name",
                    warnings = listOf("unknown-tool"),
                ),
            )
        }
}
