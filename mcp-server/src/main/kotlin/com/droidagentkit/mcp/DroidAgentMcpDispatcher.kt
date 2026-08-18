package com.droidagentkit.mcp

import com.droidagentkit.auditor.CapabilitySummaryBuilder
import com.droidagentkit.core.ArtifactRef
import com.droidagentkit.core.ArtifactSensitivity
import com.droidagentkit.core.ArtifactType
import com.droidagentkit.core.ArtifactWriter
import com.droidagentkit.core.AuthorizationDecision
import com.droidagentkit.core.CancellationToken
import com.droidagentkit.core.Capability
import com.droidagentkit.core.CheckStatus
import com.droidagentkit.core.CommandSpec
import com.droidagentkit.core.DefaultOperationPolicy
import com.droidagentkit.core.DiagnosticFinding
import com.droidagentkit.core.DoctorChecks
import com.droidagentkit.core.DroidAgentConfig
import com.droidagentkit.core.DroidAgentConfigLoader
import com.droidagentkit.core.InProcessManagedJobRunner
import com.droidagentkit.core.InteractiveConfirmer
import com.droidagentkit.core.Json
import com.droidagentkit.core.ManagedJobRunner
import com.droidagentkit.core.OperationPolicy
import com.droidagentkit.core.OperationRequest
import com.droidagentkit.core.ProcessRunner
import com.droidagentkit.core.ProgressReporter
import com.droidagentkit.core.Redactor
import com.droidagentkit.core.ResultStatus
import com.droidagentkit.core.Severity
import com.droidagentkit.core.ShellQuote
import com.droidagentkit.core.ToolGroup
import com.droidagentkit.core.ToolResult
import com.droidagentkit.device.DeviceToolContext
import com.droidagentkit.device.UiElementFinder
import com.droidagentkit.device.UiElementMatch
import com.droidagentkit.device.UiFindResult
import com.droidagentkit.inspector.AndroidProjectInspector
import com.droidagentkit.mcp.tools.ApkAnalyzer
import com.droidagentkit.mcp.tools.BuildFailureParser
import com.droidagentkit.mcp.tools.BuildProfileParser
import com.droidagentkit.mcp.tools.CoreToolProvider
import com.droidagentkit.mcp.tools.CrashLogTriage
import com.droidagentkit.mcp.tools.DependencyVersionChecker
import com.droidagentkit.mcp.tools.DeviceControlToolProvider
import com.droidagentkit.mcp.tools.DeviceReadToolProvider
import com.droidagentkit.mcp.tools.LintResultParser
import com.droidagentkit.mcp.tools.MacrobenchmarkResultParser
import com.droidagentkit.mcp.tools.McpToolProvider
import com.droidagentkit.mcp.tools.NetworkToolProvider
import com.droidagentkit.mcp.tools.PerfettoToolProvider
import com.droidagentkit.mcp.tools.RetraceEngine
import com.droidagentkit.mcp.tools.StorageToolProvider
import com.droidagentkit.mcp.tools.TestResultParser
import com.droidagentkit.mcp.tools.ToolProviderRegistry
import com.droidagentkit.mcp.tools.VisualsToolProvider
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists

data class McpTool(
    val name: String,
    val title: String,
    val description: String,
    val inputSchema: Map<String, Any>,
    val outputSchema: Map<String, Any>,
    val annotations: Map<String, Boolean> = emptyMap(),
)

/**
 * Carries the active [ToolCallContext] to the one place that spawns processes.
 *
 * A thread-local rather than a parameter because every tool routes through a deep, entirely
 * synchronous call graph before reaching `run`, and threading a transport concern through ~40
 * private methods would put it in reach of code that has no business seeing it. The stdio server
 * executes one call per pooled thread, so the binding is exact; anything that never sets it reads
 * [ToolCallContext.NONE] and behaves exactly as before.
 */
internal object CurrentToolCall {
    private val current = ThreadLocal.withInitial { ToolCallContext.NONE }

    fun <T> with(
        context: ToolCallContext,
        block: () -> T,
    ): T {
        val previous = current.get()
        current.set(context)
        return try {
            block()
        } finally {
            current.set(previous)
        }
    }

    fun context(): ToolCallContext = current.get()
}

/**
 * Cancellation and progress plumbing for one tool call.
 *
 * Passed separately from `arguments` because it is transport state, not tool input: it must never
 * appear in a tool's input schema or be settable by the model.
 */
data class ToolCallContext(
    val cancellation: CancellationToken = CancellationToken.NONE,
    val progress: ProgressReporter = ProgressReporter.NONE,
    /**
     * Reaches the human behind the client. Defaults to unavailable, which the policy turns into a
     * denial rather than a pass -- a transport with no way to ask must not be able to satisfy a
     * requirement that a human approved something.
     */
    val confirmer: InteractiveConfirmer = InteractiveConfirmer.UNAVAILABLE,
) {
    companion object {
        val NONE = ToolCallContext()
    }
}

private const val DOCTOR_PROBE_TIMEOUT_SECONDS = 10L

interface McpDispatcher {
    val instructions: String

    fun listTools(): List<McpTool>

    fun call(
        name: String,
        arguments: Map<String, Any?>,
    ): Map<String, Any>

    /**
     * Defaulted so dispatchers that cannot be cancelled (or do not care) keep working unchanged;
     * the ones that run long processes override this.
     */
    fun call(
        name: String,
        arguments: Map<String, Any?>,
        context: ToolCallContext,
    ): Map<String, Any> = call(name, arguments)

    /** MCP resources/prompts are advertised only to hosts that support them; AS stays tools-only. */
    fun resourceRegistry(): McpResourceRegistry = McpResourceRegistry()

    fun promptRegistry(): McpPromptRegistry = McpPromptRegistry()

    fun exposedToolNames(): Set<String> = listTools().map { it.name }.toSet()
}

class DroidAgentMcpDispatcher(
    override val config: DroidAgentConfig,
    projectRoot: Path = Path.of("."),
    private val inspector: AndroidProjectInspector = AndroidProjectInspector(),
    private val exposedGroups: Set<ToolGroup> = config.resolvedExposedToolGroups(),
    private val extraProviders: List<McpToolProvider> = emptyList(),
) : McpDispatcher,
    DeviceToolContext {
    private val projectRoot = projectRoot.toAbsolutePath().normalize()
    private val realProjectRoot = this.projectRoot.toRealPath()

    override val instructions: String = "Use DroidAgentKit only for the project root selected when this server started."

    private val operationPolicy: OperationPolicy =
        // The confirmer is read per call rather than injected once: it belongs to whichever
        // client connection is asking, and the policy outlives any single call.
        DefaultOperationPolicy(config.safety, listOf(projectRoot)) { request ->
            CurrentToolCall.context().confirmer.confirm(request)
        }

    private val managedJobRunner: ManagedJobRunner by lazy {
        InProcessManagedJobRunner(
            ProcessRunner(Redactor(config.redaction), ArtifactWriter(artifactOutputDir(projectRoot))),
        )
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

    private val coreAnnotations: Map<String, Map<String, Boolean>> =
        mapOf(
            "android_project_inspect" to mapOf("readOnlyHint" to true),
            "android_gradle_run" to mapOf("idempotentHint" to true, "openWorldHint" to true),
            "android_devices_list" to mapOf("readOnlyHint" to true),
            "android_app_install" to mapOf("idempotentHint" to true, "openWorldHint" to true),
            "android_app_launch" to mapOf("idempotentHint" to true, "openWorldHint" to true),
            "android_logcat_capture" to mapOf("readOnlyHint" to true),
            "android_screen_snapshot" to mapOf("readOnlyHint" to true),
            "android_accessibility_snapshot" to mapOf("readOnlyHint" to true),
            "android_report_bundle" to mapOf("readOnlyHint" to true),
            "android_lint_run" to mapOf("readOnlyHint" to true, "idempotentHint" to true),
            "android_crash_triage" to mapOf("readOnlyHint" to true),
            "android_dependency_check" to mapOf("readOnlyHint" to true),
            "android_build_performance" to mapOf("readOnlyHint" to true),
            "android_test_run" to mapOf("idempotentHint" to true, "openWorldHint" to true),
            "android_build_diagnose" to mapOf("readOnlyHint" to true),
        )

    private val coreTools: List<McpTool> = buildCoreTools().map { it.copy(annotations = coreAnnotations[it.name] ?: it.annotations) }
    private val coreToolNames: Set<String> = coreTools.map { it.name }.toSet()
    private val registry: ToolProviderRegistry =
        ToolProviderRegistry(
            providers =
                listOf(
                    CoreToolProvider(coreTools, coreToolNames) { name, args -> dispatchCore(name, args) },
                    DeviceReadToolProvider(this),
                    DeviceControlToolProvider(this),
                    PerfettoToolProvider(this),
                    VisualsToolProvider(this),
                    StorageToolProvider(this),
                    NetworkToolProvider(this),
                ) + extraProviders,
            exposedGroups = exposedGroups,
        )

    override fun listTools(): List<McpTool> = registry.listTools()

    override fun resourceRegistry(): McpResourceRegistry = resourceRegistryInstance

    override fun promptRegistry(): McpPromptRegistry = promptRegistryInstance

    private val resourceRegistryInstance: McpResourceRegistry by lazy {
        McpResourceRegistry().also { reg ->
            McpProjectResources.registerProject(
                registry = reg,
                projectRoot = projectRoot,
                inspect = { Json.write(inspect(emptyMap())) },
                readiness = { readinessMarkdown() },
            )
            reg.registerTemplate(
                McpResourceTemplate(
                    uriTemplate = McpProjectResources.ARTIFACT_TEMPLATE,
                    name = "artifact-by-id",
                    description = "Resolve a captured artifact by its opaque, project-scoped id.",
                    mimeType = "application/octet-stream",
                    variables = listOf("id"),
                    reader = { _ -> null },
                ),
            )
            reg.registerTemplate(
                McpResourceTemplate(
                    uriTemplate = McpProjectResources.GOLDEN_TEMPLATE,
                    name = "golden-by-case",
                    description = "Resolve a golden screenshot image by test case name.",
                    mimeType = "image/png",
                    variables = listOf("case"),
                    reader = { _ -> null },
                ),
            )
        }
    }

    private val promptRegistryInstance: McpPromptRegistry by lazy {
        McpPromptRegistry().also { McpPrompts.registerAll(it) }
    }

    private fun readinessMarkdown(): String {
        val auditorReport =
            com.droidagentkit.auditor
                .ReadinessAuditor(inspector)
                .audit(projectRoot)
        return buildString {
            appendLine("# Readiness — ${auditorReport.score}/100 (${auditorReport.level})")
            appendLine()
            if (auditorReport.risks.isNotEmpty()) {
                appendLine("## Risks")
                auditorReport.risks.forEach { risk ->
                    appendLine("- [${risk.severity.wireName.uppercase()}] ${risk.id} — ${risk.title}")
                }
            } else {
                appendLine("_(no risks detected)_")
            }
        }
    }

    private fun buildCoreTools(): List<McpTool> =
        listOf(
            McpTool(
                name = "android_project_inspect",
                title = "Inspect Android project",
                description = "Inspect Android Gradle modules, versions, manifests, and safe commands.",
                inputSchema = schema(props = mapOf("rootPath" to rootPathProp)),
                outputSchema = toolResultSchema,
            ),
            McpTool(
                name = "android_gradle_run",
                title = "Run allowlisted Gradle task",
                description = "Run a configured allowlisted Gradle task and capture redacted logs.",
                inputSchema =
                    schema(
                        "task",
                        props =
                            mapOf(
                                "rootPath" to rootPathProp,
                                "task" to str("Gradle task to run (must match the configured allowlist)."),
                                "arguments" to arrStr("Extra Gradle arguments to append."),
                                "rerunTasks" to bool("Pass --rerun-tasks to Gradle."),
                                "stacktrace" to bool("Pass --stacktrace to Gradle."),
                                "timeoutSeconds" to num("Override command timeout in seconds."),
                            ),
                    ),
                outputSchema = toolResultSchema,
            ),
            McpTool(
                name = "android_devices_list",
                title = "List Android devices",
                description = "List adb devices and basic status.",
                inputSchema = schema(props = mapOf("rootPath" to rootPathProp)),
                outputSchema = toolResultSchema,
            ),
            McpTool(
                name = "android_app_install",
                title = "Install Android app",
                description = "Install an APK when app install is enabled.",
                inputSchema =
                    schema(
                        "apkPath",
                        "deviceSerial",
                        props =
                            mapOf(
                                "rootPath" to rootPathProp,
                                "apkPath" to str("Absolute path to the APK file to install."),
                                "deviceSerial" to deviceSerialProp,
                                "reinstall" to bool("Pass -r to adb install to reinstall keeping data."),
                            ),
                    ),
                outputSchema = toolResultSchema,
            ),
            McpTool(
                name = "android_app_launch",
                title = "Launch Android app",
                description = "Launch an Android package/activity on an explicit device.",
                inputSchema =
                    schema(
                        "deviceSerial",
                        "packageName",
                        props =
                            mapOf(
                                "deviceSerial" to deviceSerialProp,
                                "packageName" to str("Android package name (e.g. com.example.app)."),
                                "activityName" to str("Fully qualified activity name. Omit to use the default launcher."),
                            ),
                    ),
                outputSchema = toolResultSchema,
            ),
            McpTool(
                name = "android_logcat_capture",
                title = "Capture Logcat",
                description = "Capture redacted logcat output for a device or package.",
                inputSchema =
                    schema(
                        "deviceSerial",
                        props =
                            mapOf(
                                "deviceSerial" to deviceSerialProp,
                                "maxLines" to num("Maximum number of log lines to capture. Default: 500."),
                            ),
                    ),
                outputSchema = toolResultSchema,
            ),
            McpTool(
                name = "android_screen_snapshot",
                title = "Capture Android screen",
                description = "Capture a PNG screenshot from an explicit device. Use android_accessibility_snapshot for the UI hierarchy.",
                inputSchema =
                    schema(
                        "deviceSerial",
                        props =
                            mapOf(
                                "deviceSerial" to deviceSerialProp,
                                "outputName" to str("Base name for the output PNG artifact. Optional."),
                            ),
                    ),
                outputSchema = toolResultSchema,
            ),
            McpTool(
                name = "android_accessibility_snapshot",
                title = "Capture Android accessibility tree",
                description =
                    "Capture the UIAutomator accessibility hierarchy (not Layout Inspector) from an explicit device " +
                        "and return structured nodes plus the raw XML artifact.",
                inputSchema =
                    schema(
                        "deviceSerial",
                        props =
                            mapOf(
                                "deviceSerial" to deviceSerialProp,
                                "outputName" to str("Base name for the output XML artifact. Optional."),
                                "verbosity" to
                                    str(
                                        "'full' (default) returns every node inline; 'compact' returns only interactive " +
                                            "nodes, cutting payload size. The complete hierarchy is written as an artifact either way.",
                                    ),
                                "compressed" to bool("Pass --compressed to uiautomator dump. Default true."),
                            ),
                    ),
                outputSchema = toolResultSchema,
            ),
            McpTool(
                name = "android_report_bundle",
                title = "Create Android report bundle",
                description = "Create an agent-readable Android diagnostic report bundle.",
                inputSchema = schema(props = mapOf("rootPath" to rootPathProp)),
                outputSchema = toolResultSchema,
            ),
            McpTool(
                name = "android_lint_run",
                title = "Run Android lint",
                description = "Run an allowlisted lint/detekt Gradle task and parse its XML/SARIF report into structured findings.",
                inputSchema =
                    schema(
                        "task",
                        props =
                            mapOf(
                                "rootPath" to rootPathProp,
                                "task" to str("Gradle task to run (must match the configured allowlist)."),
                                "timeoutSeconds" to num("Override command timeout in seconds."),
                            ),
                    ),
                outputSchema = toolResultSchema,
            ),
            McpTool(
                name = "android_crash_triage",
                title = "Triage Android crash",
                description = "Capture logcat from a device and extract structured crash/ANR findings.",
                inputSchema =
                    schema(
                        "deviceSerial",
                        props =
                            mapOf(
                                "deviceSerial" to deviceSerialProp,
                                "maxLines" to num("Maximum number of log lines to capture. Default: 500."),
                            ),
                    ),
                outputSchema = toolResultSchema,
            ),
            McpTool(
                name = "android_dependency_check",
                title = "Check dependency hygiene",
                description =
                    "Check declared dependency versions for drift and orphaned version-catalog entries." +
                        " Local-only, no network calls, no 'latest version' data.",
                inputSchema = schema(props = mapOf("rootPath" to rootPathProp)),
                outputSchema = toolResultSchema,
            ),
            McpTool(
                name = "android_build_performance",
                title = "Profile Android build",
                description = "Run an allowlisted Gradle task with --profile and surface the slowest tasks from the profile report.",
                inputSchema =
                    schema(
                        "task",
                        props =
                            mapOf(
                                "rootPath" to rootPathProp,
                                "task" to str("Gradle task to run with --profile (must match the configured allowlist)."),
                                "timeoutSeconds" to num("Override command timeout in seconds."),
                            ),
                    ),
                outputSchema = toolResultSchema,
            ),
            McpTool(
                name = "android_test_run",
                title = "Run Android tests",
                description = "Run an allowlisted test task and parse local JUnit XML into a deterministic summary and findings.",
                inputSchema =
                    schema(
                        "task",
                        props =
                            mapOf(
                                "rootPath" to rootPathProp,
                                "task" to str("Test task discovered by project inspection or allowed in configuration."),
                                "mode" to str("Test mode: unit, device, managed-device, or screenshot."),
                                "timeoutSeconds" to num("Override command timeout in seconds."),
                            ),
                    ),
                outputSchema = toolResultSchema,
            ),
            McpTool(
                name = "android_build_diagnose",
                title = "Diagnose Android build",
                description = "Run an allowlisted task and classify recognized compiler, resource, manifest, and cache failures.",
                inputSchema =
                    schema(
                        "task",
                        props =
                            mapOf(
                                "rootPath" to rootPathProp,
                                "task" to str("Gradle task to diagnose (must match the configured allowlist)."),
                                "stacktrace" to bool("Pass --stacktrace to Gradle."),
                                "timeoutSeconds" to num("Override command timeout in seconds."),
                            ),
                    ),
                outputSchema = toolResultSchema,
            ),
            McpTool(
                name = "android_ui_find",
                title = "Find a UI element by label",
                description =
                    "Locate an on-screen element by text, content description, or resource id and return its tap point. " +
                        "Read-only. Ambiguous matches are reported with candidates rather than guessed; misses list what is " +
                        "actually on screen.",
                inputSchema =
                    schema(
                        "deviceSerial",
                        props =
                            mapOf(
                                "rootPath" to rootPathProp,
                                "deviceSerial" to str("adb device serial."),
                                "text" to str("Visible text to match."),
                                "contentDesc" to str("Content description to match (for icon-only controls)."),
                                "resourceId" to str("Resource id to match."),
                                "exact" to bool("Require an exact match instead of a case-insensitive substring. Defaults to false."),
                                "clickableOnly" to bool("Only consider clickable nodes. Defaults to true."),
                            ),
                    ),
                annotations = mapOf("readOnlyHint" to true),
                outputSchema = toolResultSchema,
            ),
            McpTool(
                name = "android_apk_analyze",
                title = "Analyze APK size",
                description =
                    "Break an APK's size down by dex, native libraries, resources, and assets, list the largest entries " +
                        "and native ABIs, and optionally diff against a baseline APK to attribute growth to a category.",
                inputSchema =
                    schema(
                        "apkPath",
                        props =
                            mapOf(
                                "rootPath" to rootPathProp,
                                "apkPath" to str("APK path under the project root."),
                                "baselineApkPath" to str("Optional second APK to diff against, under the project root."),
                            ),
                    ),
                annotations = mapOf("readOnlyHint" to true),
                outputSchema = toolResultSchema,
            ),
            McpTool(
                name = "android_doctor",
                title = "Check the DroidAgentKit environment",
                description =
                    "Report the Java version, user policy and project config health, adb and any optional binaries the " +
                        "enabled tool groups need, ANDROID_HOME, artifact writability, and the effective tool groups and " +
                        "capabilities. Read-only. Run this first when a tool fails for no obvious reason.",
                inputSchema = schema(props = mapOf("rootPath" to rootPathProp)),
                annotations = mapOf("readOnlyHint" to true),
                outputSchema = toolResultSchema,
            ),
        )

    private fun doctor(arguments: Map<String, Any?>): Map<String, Any> {
        val root = rootPath(arguments)
        val report =
            DoctorChecks(
                probe = { command -> probeVersion(command) },
                env = { System.getenv(it) },
            ).run(root, DroidAgentConfigLoader.defaultUserPolicyPath())
        val failures = report.checks.filter { it.status == CheckStatus.FAIL }
        val warnings = report.checks.filter { it.status == CheckStatus.WARN }
        return resultMap(
            ToolResult(
                status = if (report.ok) ResultStatus.SUCCESS else ResultStatus.BLOCKED,
                summary =
                    if (report.ok) {
                        "Environment ready. ${warnings.size} optional item(s) not configured."
                    } else {
                        "Not ready: " + failures.joinToString("; ") { "${it.name} — ${it.detail}" }
                    },
                warnings = warnings.map { "${it.name}: ${it.detail}" },
            ),
        ) +
            mapOf(
                "checks" to
                    report.checks.map {
                        mapOf(
                            "name" to it.name,
                            "status" to it.status.name.lowercase(),
                            "detail" to it.detail,
                            "remedy" to (it.remedy ?: ""),
                        )
                    },
            )
    }

    /**
     * Probes a binary for its version banner. Deliberately not routed through [ProcessRunner]: the
     * doctor runs precisely when the configuration may be broken, and a version banner needs
     * neither artifact capture nor redaction. Failure to launch is the answer, not an error.
     */
    private fun probeVersion(command: List<String>): String? =
        runCatching {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(DOCTOR_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            output.takeIf { process.exitValue() == 0 && it.isNotBlank() }
        }.getOrNull()

    override fun call(
        name: String,
        arguments: Map<String, Any?>,
        context: ToolCallContext,
    ): Map<String, Any> = CurrentToolCall.with(context) { call(name, arguments) }

    override fun call(
        name: String,
        arguments: Map<String, Any?>,
    ): Map<String, Any> =
        try {
            registry.call(name, arguments)
        } catch (error: ProjectRootViolation) {
            resultMap(
                ToolResult(
                    status = ResultStatus.BLOCKED,
                    summary = error.message ?: "Requested project root is not allowed.",
                    warnings = listOf("project-root-denied"),
                ),
            )
        }

    private fun dispatchCore(
        name: String,
        arguments: Map<String, Any?>,
    ): Map<String, Any> =
        try {
            when (name) {
                "android_project_inspect" -> inspect(arguments)
                "android_gradle_run" -> runGradle(arguments)
                "android_devices_list" -> runAdb(listOf("devices", "-l"), "adb-devices", rootPath(arguments))
                "android_app_install" -> install(arguments)
                "android_app_launch" -> launch(arguments)
                "android_logcat_capture" -> logcat(arguments)
                "android_screen_snapshot" -> snapshot(arguments)
                "android_accessibility_snapshot" -> accessibilitySnapshot(arguments)
                "android_report_bundle" -> reportBundle(arguments)
                "android_lint_run" -> lintRun(arguments)
                "android_crash_triage" -> crashTriage(arguments)
                "android_dependency_check" -> dependencyCheck(arguments)
                "android_build_performance" -> buildPerformance(arguments)
                "android_test_run" -> testRun(arguments)
                "android_build_diagnose" -> buildDiagnose(arguments)
                "android_doctor" -> doctor(arguments)
                "android_apk_analyze" -> apkAnalyze(arguments)
                "android_ui_find" -> uiFind(arguments)
                else -> resultMap(ToolResult(status = ResultStatus.UNSUPPORTED, summary = "Unknown MCP tool: $name"))
            }
        } catch (error: ProjectRootViolation) {
            resultMap(
                ToolResult(
                    status = ResultStatus.BLOCKED,
                    summary = error.message ?: "Requested project root is not allowed.",
                    warnings = listOf("project-root-denied"),
                ),
            )
        }

    private fun inspect(arguments: Map<String, Any?>): Map<String, Any> {
        val root = rootPath(arguments)
        val report = inspector.inspect(root)
        return mapOf(
            "schemaVersion" to "1.0",
            "status" to if (report.modules.isEmpty()) "partial" else "success",
            "summary" to "Project ${report.projectName}: ${report.modules.size} module(s), support=${report.support}",
            "project" to
                mapOf(
                    "name" to report.projectName,
                    "support" to report.support.name.lowercase(),
                    "versions" to report.versions,
                    "modules" to
                        report.modules.map {
                            mapOf(
                                "path" to it.path,
                                "type" to it.type.name.lowercase(),
                                "namespace" to (it.namespace ?: ""),
                                "packageName" to (it.packageName ?: ""),
                                "launcherActivities" to it.launcherActivities,
                                "moduleDependencies" to it.moduleDependencies,
                                "buildTypes" to it.buildTypes,
                                "productFlavors" to it.productFlavors,
                                "pluginIds" to it.pluginIds,
                                "kotlinIntegration" to it.kotlinIntegration.name.lowercase(),
                                "compileSdk" to (it.compileSdk ?: ""),
                                "minSdk" to (it.minSdk ?: ""),
                                "targetSdk" to (it.targetSdk ?: ""),
                                "sourceSets" to it.sourceSets,
                                "hasScreenshotTests" to it.hasScreenshotTests,
                                "managedDevices" to it.managedDevices,
                                "managedDeviceGroups" to it.managedDeviceGroups,
                                "confidence" to it.confidence.name.lowercase(),
                            )
                        },
                    "toolchain" to
                        mapOf(
                            "kotlinVersion" to (report.toolchain.kotlinVersion ?: ""),
                            "gradleVersion" to (report.toolchain.gradleVersion ?: ""),
                            "agpVersion" to (report.toolchain.agpVersion ?: ""),
                            "jdkVersion" to report.toolchain.jdkVersion,
                            "evidenceVersion" to report.toolchain.evidenceVersion,
                            "findings" to
                                report.toolchain.findings.map { finding ->
                                    mapOf(
                                        "component" to finding.component,
                                        "version" to (finding.version ?: ""),
                                        "status" to finding.status.name.lowercase(),
                                        "detail" to finding.detail,
                                        "sourceUrl" to finding.sourceUrl,
                                    )
                                },
                        ),
                    "commands" to report.commandMatrix.map { it.command.joinToString(" ") },
                    "warnings" to report.warnings,
                ),
        )
    }

    private fun runGradle(arguments: Map<String, Any?>): Map<String, Any> {
        val root = rootPath(arguments)
        val task = arguments["task"]?.toString().orEmpty()
        val args = (arguments["arguments"] as? List<*> ?: emptyList<String>()).map { it.toString() }
        val disallowedArgs = args.filter { it !in SAFE_GRADLE_ARGUMENTS }
        if (disallowedArgs.isNotEmpty()) {
            return resultMap(
                ToolResult(
                    status = ResultStatus.BLOCKED,
                    summary = "Unsupported Gradle arguments: ${disallowedArgs.joinToString(", ")}. Use the dedicated tool options instead.",
                    warnings = listOf("gradle-argument-denied"),
                ),
            )
        }
        val timeout =
            (arguments["timeoutSeconds"]?.toString()?.toLongOrNull() ?: config.safety.maxCommandSeconds)
                .coerceIn(1, config.safety.maxCommandSeconds)
        val extraFlags =
            buildList {
                if (arguments["rerunTasks"] == true) add("--rerun-tasks")
                if (arguments["stacktrace"] == true) add("--stacktrace")
            }
        return resultMap(runAllowlistedGradleTask(root, task, extraFlags + args, timeout))
    }

    private fun runAllowlistedGradleTask(
        root: Path,
        task: String,
        extraArgs: List<String>,
        timeoutSeconds: Long,
    ): ToolResult {
        if (!config.safety.isGradleTaskAllowed(task)) {
            return ToolResult(
                status = ResultStatus.BLOCKED,
                summary = "Gradle task '$task' is not allowlisted. Update .droidagentkit/config.yaml to allow it.",
                warnings = listOf("gradle-task-denied"),
            )
        }
        val wrapper = if (System.getProperty("os.name").startsWith("Windows")) "gradlew.bat" else "./gradlew"
        if (!root.resolve(wrapper.removePrefix("./")).exists()) {
            val wrapperPath = root.resolve(wrapper.removePrefix("./"))
            return ToolResult(
                status = ResultStatus.BLOCKED,
                summary =
                    "Gradle wrapper was not found at $wrapperPath. " +
                        "Run `gradle wrapper` in the project, or open a project that already has gradlew.",
                warnings = listOf("missing-gradle-wrapper"),
            )
        }
        val command =
            buildList {
                add(wrapper)
                add(task)
                extraArgs.forEach { add(it) }
            }
        return runner(
            root,
        ).run(
            CommandSpec(
                "gradle-${task.sanitizeId()}",
                command,
                root.toString(),
                false,
                false,
                timeoutSeconds,
                scrubGradleEnvironment = true,
            ),
        )
    }

    private fun lintRun(arguments: Map<String, Any?>): Map<String, Any> {
        val root = rootPath(arguments)
        val task = arguments["task"]?.toString().orEmpty()
        val timeout = arguments["timeoutSeconds"]?.toString()?.toLongOrNull() ?: config.safety.maxCommandSeconds
        val runResult = runAllowlistedGradleTask(root, task, emptyList(), timeout)
        if (runResult.status == ResultStatus.BLOCKED) {
            return resultMap(runResult)
        }
        val reportFile =
            findNewestLintReport(root)
                ?: return resultMapWithFindings(
                    runResult.copy(
                        status = ResultStatus.PARTIAL,
                        warnings = runResult.warnings + "no-structured-lint-report-found",
                    ),
                    emptyList(),
                )
        val text = Files.readString(reportFile)
        val findings =
            when {
                reportFile.toString().endsWith(".sarif") -> LintResultParser.parseDetektSarif(text)
                text.contains("<issue ") -> LintResultParser.parseAndroidLintXml(text)
                else -> LintResultParser.parseDetektCheckstyleXml(text)
            }
        return resultMapWithFindings(runResult, findings)
    }

    private fun testRun(arguments: Map<String, Any?>): Map<String, Any> {
        val root = rootPath(arguments)
        val task = arguments["task"]?.toString().orEmpty()
        val mode = arguments["mode"]?.toString() ?: "unit"
        if (mode !in TEST_MODES) {
            return resultMap(
                ToolResult(
                    status = ResultStatus.BLOCKED,
                    summary = "Unsupported test mode '$mode'.",
                    warnings = listOf("test-mode-denied"),
                ),
            )
        }
        val runResult = runAllowlistedGradleTask(root, task, emptyList(), boundedTimeout(arguments))
        if (runResult.status == ResultStatus.BLOCKED) return resultMap(runResult)
        val parsed =
            runCatching { TestResultParser.parse(root) }.getOrElse { error ->
                return resultMapWithFindings(
                    runResult.copy(
                        status = ResultStatus.PARTIAL,
                        warnings = runResult.warnings + "malformed-test-report:${error.javaClass.simpleName}",
                    ),
                    emptyList(),
                )
            }
        val result =
            if (parsed.summary.reportFiles.isEmpty()) {
                runResult.copy(
                    status = ResultStatus.PARTIAL,
                    warnings = runResult.warnings + "no-structured-test-report-found",
                )
            } else {
                runResult
            }
        return resultMapWithFindings(result, parsed.findings) +
            mapOf("testSummary" to testSummaryMap(parsed.summary)) +
            macrobenchmarkBlock(root)
    }

    /**
     * Macrobenchmark runs are instrumentation tests, so their results land during a normal
     * android_test_run rather than through a tool of their own. The key is omitted entirely when no
     * benchmark output exists, so an ordinary unit-test run carries nothing extra.
     */
    private fun macrobenchmarkBlock(root: Path): Map<String, Any> {
        val parsed = runCatching { MacrobenchmarkResultParser.parse(root) }.getOrNull() ?: return emptyMap()
        if (parsed.resultFiles.isEmpty()) return emptyMap()
        return mapOf(
            "macrobenchmark" to
                mapOf(
                    "summary" to parsed.summary,
                    "resultFiles" to parsed.resultFiles,
                    "warnings" to parsed.warnings,
                    "benchmarks" to
                        parsed.benchmarks.map { run ->
                            mapOf(
                                "name" to run.name,
                                "className" to run.className,
                                "metrics" to
                                    run.metrics.map { metric ->
                                        buildMap {
                                            put("name", metric.name)
                                            metric.minimum?.let { put("minimum", it) }
                                            metric.median?.let { put("median", it) }
                                            metric.maximum?.let { put("maximum", it) }
                                            if (metric.percentiles.isNotEmpty()) put("percentiles", metric.percentiles)
                                        }
                                    },
                            )
                        },
                ),
        )
    }

    private fun buildDiagnose(arguments: Map<String, Any?>): Map<String, Any> {
        val root = rootPath(arguments)
        val task = arguments["task"]?.toString().orEmpty()
        val flags = if (arguments["stacktrace"] == true) listOf("--stacktrace") else emptyList()
        val runResult = runAllowlistedGradleTask(root, task, flags, boundedTimeout(arguments))
        if (runResult.status == ResultStatus.BLOCKED) return resultMap(runResult)
        val log =
            runResult.artifacts
                .firstOrNull { it.mimeType.startsWith("text/") }
                ?.path
                ?.let(Path::of)
                ?.takeIf { it.normalize().startsWith(root) && it.exists() }
                ?.let(Files::readString)
                .orEmpty()
        val findings = BuildFailureParser.parse(log)
        val result =
            if (runResult.status == ResultStatus.FAILED && findings.isEmpty()) {
                runResult.copy(warnings = runResult.warnings + "unclassified-build-failure")
            } else {
                runResult
            }
        return resultMapWithFindings(result, findings)
    }

    private fun testSummaryMap(summary: com.droidagentkit.mcp.tools.TestRunSummary): Map<String, Any> =
        mapOf(
            "tests" to summary.tests,
            "failures" to summary.failures,
            "errors" to summary.errors,
            "skipped" to summary.skipped,
            "durationSeconds" to summary.durationSeconds,
            "reportFiles" to summary.reportFiles,
        )

    private fun boundedTimeout(arguments: Map<String, Any?>): Long =
        (arguments["timeoutSeconds"]?.toString()?.toLongOrNull() ?: config.safety.maxCommandSeconds)
            .coerceIn(1, config.safety.maxCommandSeconds)

    private fun findNewestLintReport(root: Path): Path? {
        val candidates = mutableListOf<Path>()
        Files.walk(root, 6).use { stream ->
            stream
                .filter { path -> Files.isRegularFile(path) }
                .filter { path ->
                    val parts = root.relativize(path).map { it.toString() }
                    val fileName = path.fileName.toString()
                    parts.contains("build") &&
                        parts.contains("reports") &&
                        (
                            (fileName.startsWith("lint-results") && fileName.endsWith(".xml")) ||
                                (parts.contains("detekt") && (fileName.endsWith(".xml") || fileName.endsWith(".sarif")))
                        )
                }.forEach { candidates.add(it) }
        }
        return candidates.maxByOrNull { Files.getLastModifiedTime(it).toMillis() }
    }

    private val findingRedactor = Redactor(config.redaction)

    override fun resultMapWithFindings(
        result: ToolResult,
        findings: List<DiagnosticFinding>,
    ): Map<String, Any> = resultMap(result) + mapOf("findings" to findings.map(::findingToMap))

    private fun findingToMap(finding: DiagnosticFinding): Map<String, Any?> =
        mapOf(
            "category" to finding.category,
            "severity" to finding.severity.wireName,
            "title" to findingRedactor.redact(finding.title).text,
            "detail" to findingRedactor.redact(finding.detail).text,
            "location" to finding.location?.let { findingRedactor.redact(it).text },
        )

    private fun install(arguments: Map<String, Any?>): Map<String, Any> {
        val apk =
            arguments["apkPath"]?.toString()
                ?: return resultMap(
                    ToolResult(
                        status = ResultStatus.BLOCKED,
                        summary = "apkPath is required for android_app_install.",
                        warnings = listOf("missing-apk-path"),
                    ),
                )
        val serial =
            arguments["deviceSerial"]?.toString()
                ?: return resultMap(
                    ToolResult(
                        status = ResultStatus.BLOCKED,
                        summary = "deviceSerial is required for app install.",
                        warnings = listOf("missing-device-serial"),
                    ),
                )
        val apkPath = Path.of(apk).toAbsolutePath().normalize()
        val decision =
            authorize(
                OperationRequest(
                    operationId = "android_app_install",
                    requiredCapabilities = setOf(Capability.APP_INSTALL),
                    destructive = false,
                    deviceSerial = serial,
                    hostPaths = listOf(apkPath),
                ),
            )
        if (decision is AuthorizationDecision.Denied) {
            return resultMap(
                ToolResult(
                    status = ResultStatus.BLOCKED,
                    summary = decision.reason,
                    warnings = listOf(decision.code),
                ),
            )
        }
        return runAdb(
            listOf(
                "-s",
                serial,
                "install",
                if (arguments["reinstall"] == true) "-r" else "",
                apkPath.toString(),
            ).filter { it.isNotBlank() },
            "adb-install",
            rootPath(arguments),
        )
    }

    private fun launch(arguments: Map<String, Any?>): Map<String, Any> {
        val serial =
            arguments["deviceSerial"]?.toString()
                ?: return resultMap(
                    ToolResult(
                        status = ResultStatus.BLOCKED,
                        summary = "deviceSerial is required for app launch.",
                        warnings = listOf("missing-device-serial"),
                    ),
                )
        val packageName =
            arguments["packageName"]?.toString()
                ?: return resultMap(
                    ToolResult(
                        status = ResultStatus.BLOCKED,
                        summary = "packageName is required for app launch.",
                        warnings = listOf("missing-package-name"),
                    ),
                )
        val activity = arguments["activityName"]?.toString()
        val component = if (activity.isNullOrBlank()) packageName else "$packageName/$activity"
        return runAdb(
            listOf(
                "-s",
                serial,
                "shell",
                ShellQuote.quote("am"),
                ShellQuote.quote("start"),
                ShellQuote.quote("-n"),
                ShellQuote.quote(component),
            ),
            "adb-launch",
            rootPath(arguments),
        )
    }

    private fun logcat(arguments: Map<String, Any?>): Map<String, Any> {
        val serial =
            arguments["deviceSerial"]?.toString()
                ?: return resultMap(
                    ToolResult(
                        status = ResultStatus.BLOCKED,
                        summary = "deviceSerial is required for logcat capture.",
                        warnings = listOf("missing-device-serial"),
                    ),
                )
        val maxLines = arguments["maxLines"]?.toString()?.toIntOrNull() ?: 500
        val command = listOf("-s", serial, "logcat", "-d", "-t", maxLines.toString())
        return runAdb(command, "adb-logcat", rootPath(arguments))
    }

    private fun snapshot(arguments: Map<String, Any?>): Map<String, Any> {
        val serial =
            arguments["deviceSerial"]?.toString()
                ?: return resultMap(
                    ToolResult(
                        status = ResultStatus.BLOCKED,
                        summary = "deviceSerial is required for screenshots.",
                        warnings = listOf("missing-device-serial"),
                    ),
                )
        val root = rootPath(arguments)
        val outputName = (arguments["outputName"]?.toString()?.takeIf { it.isNotBlank() } ?: "adb-screenshot").sanitizeId()
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
                    artifactType = ArtifactType.SCREENSHOT,
                    artifactName = "$outputName.png",
                    sensitivity = com.droidagentkit.core.ArtifactSensitivity.SENSITIVE,
                ),
            ),
        )
    }

    private fun accessibilitySnapshot(arguments: Map<String, Any?>): Map<String, Any> {
        val serial =
            arguments["deviceSerial"]?.toString()
                ?: return resultMap(
                    ToolResult(
                        status = ResultStatus.BLOCKED,
                        summary = "deviceSerial is required for the accessibility snapshot.",
                        warnings = listOf("missing-device-serial"),
                    ),
                )
        val root = rootPath(arguments)
        val outputName = (arguments["outputName"]?.toString()?.takeIf { it.isNotBlank() } ?: "adb-accessibility").sanitizeId()
        val compressed = arguments["compressed"] != false
        val dumpCommand =
            buildList {
                add("adb")
                add("-s")
                add(serial)
                add("exec-out")
                add("uiautomator")
                add("dump")
                if (compressed) add("--compressed")
            }
        val runResult =
            runner(root).run(
                com.droidagentkit.core.CommandSpec(
                    id = "adb-accessibility",
                    command = dumpCommand,
                    workingDirectory = root.toString(),
                    mutatesProject = false,
                    requiresDevice = true,
                    timeoutSeconds = 60,
                    outputMode = com.droidagentkit.core.OutputMode.TEXT,
                ),
            )
        if (runResult.status == ResultStatus.BLOCKED) return resultMap(runResult)
        val xmlArtifact = runResult.artifacts.firstOrNull() ?: return resultMap(runResult)
        val xml = runCatching { Files.readString(Path.of(xmlArtifact.path)) }.getOrElse { "" }
        if (xml.isBlank()) {
            return resultMapWithFindings(
                runResult.copy(status = ResultStatus.PARTIAL, warnings = runResult.warnings + "empty-accessibility-dump"),
                emptyList(),
            )
        }
        val parsed =
            com.droidagentkit.device.UiHierarchyParser
                .parse(xml)
        val rawRef =
            com.droidagentkit.core.ArtifactRef(
                type = ArtifactType.UI_HIERARCHY,
                path = xmlArtifact.path,
                mimeType = "application/xml",
                description = "Raw UIAutomator accessibility XML (accessibility hierarchy, not Layout Inspector)",
                sensitivity = com.droidagentkit.core.ArtifactSensitivity.SENSITIVE,
            )
        // The full tree is the single largest payload any tool returns -- a busy screen runs to
        // tens of thousands of tokens. In compact mode only the interactive nodes come back
        // inline; the complete hierarchy is still on disk as an artifact either way, so nothing is
        // lost, only deferred.
        val compact = isCompact(arguments)
        val inlineNodes =
            if (compact) {
                parsed.nodes.filter { it["clickable"] == true || (it["text"]?.toString()).isNullOrBlank().not() }
            } else {
                parsed.nodes
            }
        val summary =
            if (compact) {
                "Captured accessibility tree: ${parsed.nodeCount} node(s); ${inlineNodes.size} interactive returned inline (compact)."
            } else {
                "Captured accessibility tree: ${parsed.nodeCount} node(s)."
            }
        return resultMapWithFindings(
            runResult.copy(artifacts = runResult.artifacts + rawRef, summary = summary),
            parsed.findings,
        ) + mapOf("accessibilityTree" to inlineNodes)
    }

    /**
     * Whether the caller asked for the trimmed payload.
     *
     * Defaults to full so no existing integration silently starts receiving less than it did.
     */
    private fun isCompact(arguments: Map<String, Any?>): Boolean = arguments["verbosity"]?.toString() == "compact"

    private fun uiFind(arguments: Map<String, Any?>): Map<String, Any> {
        val serial =
            arguments["deviceSerial"]?.toString()
                ?: return resultMap(
                    ToolResult(
                        status = ResultStatus.BLOCKED,
                        summary = "deviceSerial is required for android_ui_find.",
                        warnings = listOf("missing-device-serial"),
                    ),
                )
        val result = findUiElement(arguments + mapOf("deviceSerial" to serial))
        return when (result) {
            is UiFindResult.Found ->
                resultMap(
                    ToolResult(
                        status = ResultStatus.SUCCESS,
                        summary = "Matched '${result.match.text.ifBlank {
                            result.match.contentDesc
                        }}' at (${result.match.centerX},${result.match.centerY}).",
                    ),
                ) + mapOf("element" to matchMap(result.match))
            is UiFindResult.Ambiguous ->
                resultMap(
                    ToolResult(
                        status = ResultStatus.FAILED,
                        summary = "${result.matches.size} elements matched; refine with resourceId or exact=true.",
                        warnings = listOf("ambiguous-match"),
                    ),
                ) + mapOf("candidates" to result.matches.map { matchMap(it) })
            is UiFindResult.NotFound ->
                resultMap(
                    ToolResult(
                        status = ResultStatus.FAILED,
                        summary = "No element matched. On screen now: ${result.suggestions.joinToString(
                            ", ",
                        ).ifBlank { "(no labelled elements)" }}",
                        warnings = listOf("no-match"),
                    ),
                ) + mapOf("suggestions" to result.suggestions)
        }
    }

    override fun findUiElement(arguments: Map<String, Any?>): UiFindResult {
        val serial = arguments["deviceSerial"]?.toString().orEmpty()
        val snapshot = accessibilitySnapshot(mapOf("deviceSerial" to serial, "rootPath" to (arguments["rootPath"] ?: "")))

        @Suppress("UNCHECKED_CAST")
        val nodes = snapshot["accessibilityTree"] as? List<Map<String, Any>> ?: emptyList()
        return UiElementFinder.find(
            nodes = nodes,
            text = arguments["text"]?.toString()?.takeIf { it.isNotBlank() },
            contentDesc = arguments["contentDesc"]?.toString()?.takeIf { it.isNotBlank() },
            resourceId = arguments["resourceId"]?.toString()?.takeIf { it.isNotBlank() },
            exact = arguments["exact"]?.toString()?.toBooleanStrictOrNull() ?: false,
            clickableOnly = arguments["clickableOnly"]?.toString()?.toBooleanStrictOrNull() ?: true,
        )
    }

    private fun matchMap(match: UiElementMatch): Map<String, Any> =
        mapOf(
            "text" to match.text,
            "contentDesc" to match.contentDesc,
            "resourceId" to match.resourceId,
            "className" to match.className,
            "bounds" to match.bounds,
            "clickable" to match.clickable,
            "enabled" to match.enabled,
            "centerX" to match.centerX,
            "centerY" to match.centerY,
        )

    private fun reportBundle(arguments: Map<String, Any?>): Map<String, Any> {
        val root = rootPath(arguments)
        val report = inspector.inspect(root)
        val auditorReport =
            com.droidagentkit.auditor
                .ReadinessAuditor(inspector)
                .audit(root)
                .copy(capabilitySummary = CapabilitySummaryBuilder.build(config, exposedGroups))
        val timestamp =
            java.time.Instant
                .now()
                .toString()

        val markdown =
            buildString {
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
                appendLine("## Key Versions")
                if (report.versions.isNotEmpty()) {
                    val interestingKeys = setOf("kotlin", "compose", "hilt", "room", "retrofit", "coroutines", "agp")
                    val keyVersions =
                        report.versions.entries
                            .filter { (k, _) -> interestingKeys.any { k.contains(it, ignoreCase = true) } }
                            .take(8)
                    val display = keyVersions.ifEmpty { report.versions.entries.take(5) }
                    appendLine(display.joinToString("   ") { (k, v) -> "$k: $v" })
                } else {
                    appendLine("_(none detected)_")
                }
                appendLine()
                appendLine("## Warnings")
                if (auditorReport.risks.isNotEmpty()) {
                    auditorReport.risks.forEach { risk ->
                        appendLine("[${risk.severity.wireName.uppercase()}] ${risk.id} — ${risk.title}")
                    }
                } else {
                    appendLine("_(none detected)_")
                }
                appendLine()
                appendLine("## Capability Summary")
                val summary = auditorReport.capabilitySummary
                if (summary == null) {
                    appendLine("_(not reported)_")
                } else {
                    appendLine("- Exposed tool groups: ${summary.exposedToolGroups.joinToString(", ")}")
                    appendLine("- Enabled capabilities: ${summary.enabledCapabilities.joinToString(", ").ifBlank { "_(none)_" }}")
                    appendLine("- Dangerous flags: ${summary.dangerousFlags.joinToString(", ").ifBlank { "_(none)_" }}")
                    appendLine("- Optional executables: ${summary.optionalExecutables.entries.joinToString(", ") { (k, v) -> "$k=$v" }}")
                    if (summary.prerequisites.isNotEmpty()) {
                        appendLine("- Prerequisites:")
                        summary.prerequisites.forEach { appendLine("  - $it") }
                    }
                }
            }

        val writer = ArtifactWriter(artifactOutputDir(root))
        val ref = writer.writeText("android-report.md", markdown, ArtifactType.MARKDOWN, "Android project report")

        val capabilitySummaryMap: Map<String, Any> =
            auditorReport.capabilitySummary?.let { s ->
                mapOf(
                    "exposedToolGroups" to s.exposedToolGroups,
                    "enabledCapabilities" to s.enabledCapabilities,
                    "dangerousFlags" to s.dangerousFlags,
                    "optionalExecutables" to s.optionalExecutables,
                    "prerequisites" to s.prerequisites,
                )
            } ?: emptyMap()

        return resultMap(
            ToolResult(
                status = ResultStatus.SUCCESS,
                summary = "Wrote enriched report bundle to ${ref.path} (${auditorReport.score}/100 ${auditorReport.level})",
                artifacts = listOf(ref),
            ),
        ) + mapOf("capabilitySummary" to capabilitySummaryMap)
    }

    private fun runAdb(
        args: List<String>,
        id: String,
        root: Path,
    ): Map<String, Any> = resultMap(runAdbCommand(args, id, root))

    private fun runAdbCommand(
        args: List<String>,
        id: String,
        root: Path,
    ): ToolResult =
        runner(root).run(
            CommandSpec(
                id,
                listOf(config.safety.adbPath) + args,
                root.toString(),
                false,
                true,
                60,
            ),
        )

    private fun crashTriage(arguments: Map<String, Any?>): Map<String, Any> {
        val serial =
            arguments["deviceSerial"]?.toString()
                ?: return resultMap(
                    ToolResult(
                        status = ResultStatus.BLOCKED,
                        summary = "deviceSerial is required for crash triage.",
                        warnings = listOf("missing-device-serial"),
                    ),
                )
        val maxLines = arguments["maxLines"]?.toString()?.toIntOrNull() ?: 500
        val root = rootPath(arguments)
        val runResult = runAdbCommand(listOf("-s", serial, "logcat", "-d", "-t", maxLines.toString()), "adb-crash-triage", root)
        val logArtifact = runResult.artifacts.firstOrNull() ?: return resultMap(runResult)
        val rawLogText = Files.readString(Path.of(logArtifact.path))
        val (logText, retraceNote) = applyRetrace(rawLogText, arguments, root)
        val findings = CrashLogTriage.triage(logText)
        val summary =
            if (findings.isEmpty()) {
                "No crashes or ANRs found in the captured logcat window.$retraceNote"
            } else {
                "Found ${findings.size} crash/ANR finding(s) in the captured logcat window.$retraceNote"
            }
        return resultMapWithFindings(runResult.copy(summary = summary), findings)
    }

    /**
     * De-obfuscates the captured log when an R8 mapping is available.
     *
     * A release stack trace reads `a.b.c.a(Unknown Source)`, which tells an agent nothing. The
     * mapping path is confined through the operation policy like any other host path -- it is an
     * argument the model supplies.
     */
    private fun applyRetrace(
        logText: String,
        arguments: Map<String, Any?>,
        root: Path,
    ): Pair<String, String> {
        val explicit = arguments["mappingFile"]?.toString()?.takeIf { it.isNotBlank() }
        val mappingPath =
            if (explicit != null) {
                val candidate = root.resolve(explicit).normalize()
                val decision =
                    operationPolicy.authorize(
                        OperationRequest(
                            operationId = "android_crash_triage",
                            requiredCapabilities = emptySet(),
                            destructive = false,
                            hostPaths = listOf(candidate),
                        ),
                    )
                if (decision is AuthorizationDecision.Denied) return logText to " Mapping ignored: ${decision.reason}"
                candidate.takeIf { Files.exists(it) }
            } else {
                discoverMappingFile(root)
            }
        if (mappingPath == null) {
            return logText to if (explicit != null) " Mapping file not found; frames left obfuscated." else ""
        }
        val mapping = RetraceEngine.parse(Files.readString(mappingPath))
        if (mapping.size == 0) return logText to " Mapping file had no class entries; frames left obfuscated."
        return RetraceEngine.retrace(logText, mapping) to " Frames de-obfuscated with ${mappingPath.fileName} (${mapping.size} classes)."
    }

    /** The conventional R8 output location; picked only when exactly one candidate exists. */
    private fun discoverMappingFile(root: Path): Path? {
        val outputs = root.resolve("build/outputs/mapping")
        if (!Files.isDirectory(outputs)) return null
        return Files
            .walk(outputs)
            .use { stream ->
                stream.filter { it.fileName?.toString() == "mapping.txt" && Files.isRegularFile(it) }.toList()
            }.singleOrNull()
    }

    private fun apkAnalyze(arguments: Map<String, Any?>): Map<String, Any> {
        val root = rootPath(arguments)
        val apkArg =
            arguments["apkPath"]?.toString()?.takeIf { it.isNotBlank() }
                ?: return resultMap(
                    ToolResult(
                        status = ResultStatus.BLOCKED,
                        summary = "apkPath is required for android_apk_analyze.",
                        warnings = listOf("missing-apk-path"),
                    ),
                )
        val baselineArg = arguments["baselineApkPath"]?.toString()?.takeIf { it.isNotBlank() }
        val paths = listOfNotNull(apkArg, baselineArg).map { root.resolve(it).normalize() }
        val decision =
            operationPolicy.authorize(
                OperationRequest(
                    operationId = "android_apk_analyze",
                    requiredCapabilities = emptySet(),
                    destructive = false,
                    hostPaths = paths,
                ),
            )
        if (decision is AuthorizationDecision.Denied) {
            return resultMap(
                ToolResult(status = ResultStatus.BLOCKED, summary = decision.reason, warnings = listOf(decision.code)),
            )
        }
        val missing = paths.filterNot { Files.isRegularFile(it) }
        if (missing.isNotEmpty()) {
            return resultMap(
                ToolResult(
                    status = ResultStatus.BLOCKED,
                    summary = "APK not found: ${missing.joinToString(", ")}",
                    warnings = listOf("apk-not-found"),
                ),
            )
        }

        return if (baselineArg == null) {
            val report = ApkAnalyzer.analyze(paths[0])
            resultMap(
                ToolResult(
                    status = ResultStatus.SUCCESS,
                    summary =
                        "${paths[0].fileName}: ${report.totalCompressedBytes} bytes download, " +
                            "${report.totalUncompressedBytes} bytes installed across ${report.fileCount} entries.",
                ),
            ) + mapOf("apk" to reportMap(report))
        } else {
            val diff = ApkAnalyzer.diff(paths[1], paths[0])
            resultMap(
                ToolResult(
                    status = ResultStatus.SUCCESS,
                    summary = "Download size changed by ${diff.totalCompressedDelta} bytes against the baseline.",
                ),
            ) +
                mapOf(
                    "apk" to reportMap(diff.candidate),
                    "baseline" to reportMap(diff.base),
                    "totalCompressedDelta" to diff.totalCompressedDelta,
                    "categoryDeltas" to
                        diff.categories.map {
                            mapOf(
                                "category" to it.category,
                                "compressedDelta" to it.compressedDelta,
                                "uncompressedDelta" to it.uncompressedDelta,
                            )
                        },
                )
        }
    }

    private fun reportMap(report: ApkAnalyzer.ApkReport): Map<String, Any> =
        mapOf(
            "path" to report.path,
            "totalCompressedBytes" to report.totalCompressedBytes,
            "totalUncompressedBytes" to report.totalUncompressedBytes,
            "fileCount" to report.fileCount,
            "abis" to report.abis,
            "categories" to
                report.categories.map {
                    mapOf(
                        "category" to it.category,
                        "fileCount" to it.fileCount,
                        "compressedBytes" to it.compressedBytes,
                        "uncompressedBytes" to it.uncompressedBytes,
                    )
                },
            "largestEntries" to report.largestEntries.map { mapOf("name" to it.first, "compressedBytes" to it.second) },
        )

    private fun dependencyCheck(arguments: Map<String, Any?>): Map<String, Any> {
        val root = rootPath(arguments)
        val findings = DependencyVersionChecker.check(root)
        val summary =
            if (findings.isEmpty()) {
                "No dependency version drift or orphaned catalog entries found."
            } else {
                "Found ${findings.size} dependency finding(s)."
            }
        return resultMapWithFindings(ToolResult(status = ResultStatus.SUCCESS, summary = summary), findings)
    }

    private fun buildPerformance(arguments: Map<String, Any?>): Map<String, Any> {
        val root = rootPath(arguments)
        val task = arguments["task"]?.toString().orEmpty()
        val timeout = arguments["timeoutSeconds"]?.toString()?.toLongOrNull() ?: config.safety.maxCommandSeconds
        val runResult = runAllowlistedGradleTask(root, task, listOf("--profile"), timeout)
        if (runResult.status == ResultStatus.BLOCKED) {
            return resultMap(runResult)
        }
        val reportFile =
            findNewestProfileReport(root)
                ?: return resultMapWithFindings(
                    runResult.copy(warnings = runResult.warnings + "no-profile-report-found"),
                    emptyList(),
                )
        val html = Files.readString(reportFile)
        val profile = BuildProfileParser.parse(html)
        val findings =
            profile.taskTimings.take(10).map { timing ->
                DiagnosticFinding(
                    category = "slow_task",
                    severity = Severity.INFO,
                    title = timing.taskPath,
                    detail = "${timing.durationMs}ms",
                    location = timing.taskPath,
                )
            }
        val reportArtifact =
            ArtifactRef(
                type = ArtifactType.REPORT,
                path = reportFile.toString(),
                mimeType = "text/html",
                description = "Gradle --profile report",
            )
        val summaryText =
            buildString {
                append("Ran '$task' with --profile.")
                profile.totalBuildTimeMs?.let { append(" Total build time: ${it}ms.") }
                if (findings.isEmpty()) append(" No task timing data could be parsed from the profile report.")
            }
        return resultMapWithFindings(
            runResult.copy(summary = summaryText, artifacts = runResult.artifacts + reportArtifact),
            findings,
        )
    }

    private fun findNewestProfileReport(root: Path): Path? {
        val reportsDir = root.resolve("build/reports/profile")
        if (!Files.isDirectory(reportsDir)) return null
        return Files.list(reportsDir).use { stream ->
            stream
                .filter { it.fileName.toString().endsWith(".html") }
                .toList()
                .maxByOrNull { Files.getLastModifiedTime(it).toMillis() }
        }
    }

    private fun runner(root: Path): ProcessRunner =
        ProcessRunner(
            Redactor(config.redaction),
            ArtifactWriter(artifactOutputDir(root)),
        )

    override fun artifactOutputDir(root: Path): Path {
        val output = root.resolve(config.reports.outputDir).normalize()
        if (!output.startsWith(root)) {
            throw ProjectRootViolation("Configured report output must stay inside the server project root.")
        }
        val existingAncestor = generateSequence(output) { it.parent }.first { it.exists() }
        if (!existingAncestor.toRealPath().startsWith(realProjectRoot)) {
            throw ProjectRootViolation("Configured report output resolves outside the server project root.")
        }
        return output
    }

    override fun run(
        root: Path,
        spec: CommandSpec,
    ): ToolResult {
        val context = CurrentToolCall.context()
        context.progress.report(null, null, "running ${spec.id}")
        return runner(root).run(spec, cancellation = context.cancellation)
    }

    override fun registerExistingArtifact(
        root: Path,
        file: Path,
        type: ArtifactType,
        description: String,
        sensitivity: ArtifactSensitivity,
    ): ArtifactRef = ArtifactWriter(artifactOutputDir(root)).registerExistingFile(file, type, description, sensitivity)

    override fun authorize(request: OperationRequest): AuthorizationDecision = operationPolicy.authorize(request)

    override fun jobRunner(): ManagedJobRunner = managedJobRunner

    override fun safeId(value: String): String = value.sanitizeId()

    override fun resolveRoot(arguments: Map<String, Any?>): Path = rootPath(arguments)

    override fun resultMap(result: ToolResult): Map<String, Any> =
        mapOf(
            "schemaVersion" to result.schemaVersion,
            "status" to result.status.wireName,
            "summary" to result.summary,
            "artifacts" to result.artifacts.map(Json::artifactToMap),
            "redactionsApplied" to result.redactionsApplied,
            "warnings" to result.warnings,
        )

    private fun rootPath(arguments: Map<String, Any?>): Path {
        val requested = Path.of(arguments["rootPath"]?.toString() ?: projectRoot.toString()).toAbsolutePath().normalize()
        if (!requested.exists() || requested.toRealPath() != realProjectRoot) {
            throw ProjectRootViolation("Requested root '$requested' is outside the server project '$projectRoot'.")
        }
        return projectRoot
    }

    private fun String.sanitizeId(): String = replace(Regex("[^A-Za-z0-9._-]"), "-").trim('-').ifBlank { "task" }

    private fun schema(
        vararg required: String,
        props: Map<String, Map<String, Any>>,
    ): Map<String, Any> {
        val base: MutableMap<String, Any> = mutableMapOf("type" to "object", "properties" to props)
        if (required.isNotEmpty()) base["required"] = required.toList()
        return base
    }

    private fun str(desc: String): Map<String, Any> = mapOf("type" to "string", "description" to desc)

    private fun bool(desc: String): Map<String, Any> = mapOf("type" to "boolean", "description" to desc)

    private fun num(desc: String): Map<String, Any> = mapOf("type" to "number", "description" to desc)

    private fun arrStr(desc: String): Map<String, Any> =
        mapOf("type" to "array", "items" to mapOf("type" to "string"), "description" to desc)

    private val rootPathProp get() = str("Android project root bound when this MCP server started. Defaults to that root.")
    private val deviceSerialProp get() = str("adb device serial from `adb devices`.")

    private class ProjectRootViolation(
        message: String,
    ) : IllegalArgumentException(message)

    private companion object {
        val TEST_MODES = setOf("unit", "device", "managed-device", "screenshot")
        val SAFE_GRADLE_ARGUMENTS =
            setOf(
                "--continue",
                "--debug",
                "--full-stacktrace",
                "--info",
                "--no-daemon",
                "--offline",
                "--rerun-tasks",
                "--stacktrace",
            )
    }
}
