package com.droidagentkit.mcp.tools

import com.droidagentkit.core.ArtifactSensitivity
import com.droidagentkit.core.ArtifactType
import com.droidagentkit.core.AuthorizationDecision
import com.droidagentkit.core.Capability
import com.droidagentkit.core.OperationRequest
import com.droidagentkit.core.ResultStatus
import com.droidagentkit.core.ToolGroup
import com.droidagentkit.core.ToolResult
import com.droidagentkit.device.DeviceToolContext
import com.droidagentkit.mcp.McpTool
import com.droidagentkit.visuals.PngDiffEngine
import com.droidagentkit.visuals.VisualCaptureEngine
import com.droidagentkit.visuals.VisualMatrix
import com.droidagentkit.visuals.VisualTolerance
import java.nio.file.Files
import java.nio.file.Path

class VisualsToolProvider(
    private val context: DeviceToolContext,
) : McpToolProvider {
    override val group: ToolGroup = ToolGroup.VISUALS

    private val toolNames: Set<String> =
        setOf("android_visual_diff", "android_visual_report", "android_visual_update_goldens")

    override fun listTools(): List<McpTool> = buildTools()

    override fun supports(name: String): Boolean = name in toolNames

    override fun call(
        name: String,
        arguments: Map<String, Any?>,
    ): Map<String, Any> =
        when (name) {
            "android_visual_diff" -> diff(arguments)
            "android_visual_report" -> report(arguments)
            "android_visual_update_goldens" -> updateGoldens(arguments)
            else -> unsupported(name)
        }

    private fun buildTools(): List<McpTool> =
        listOf(
            McpTool(
                name = "android_visual_diff",
                title = "Diff two PNG images",
                description =
                    "Compare a baseline PNG against a candidate PNG with the configured tolerance and " +
                        "write a pixel-diff overlay artifact. Read-only; emits only pixel-diff evidence.",
                inputSchema = diffSchema(),
                outputSchema = toolResultSchema,
                annotations = mapOf("readOnlyHint" to true),
            ),
            McpTool(
                name = "android_visual_report",
                title = "Build a visual regression report",
                description =
                    "Read the captures manifest under a project visuals directory, diff each case against " +
                        "its golden, and return a structured report with findings and warnings. " +
                        "Optionally pass a matrix to detect missing captures. Read-only.",
                inputSchema = reportSchema(),
                outputSchema = toolResultSchema,
                annotations = mapOf("readOnlyHint" to true),
            ),
            McpTool(
                name = "android_visual_update_goldens",
                title = "Update visual golden images",
                description =
                    "Copy current captures over golden images. Destructive: requires the golden_update " +
                        "capability and confirmDestructive=true.",
                inputSchema = updateGoldensSchema(),
                outputSchema = toolResultSchema,
                annotations = mapOf("destructiveHint" to true, "openWorldHint" to true),
            ),
        )

    private fun diffSchema(): Map<String, Any> =
        schema(
            "baselinePath",
            "candidatePath",
            "rootPath",
            props =
                mapOf(
                    "rootPath" to rootPathProp,
                    "baselinePath" to str("Project-relative or absolute path to the baseline PNG."),
                    "candidatePath" to str("Project-relative or absolute path to the candidate PNG."),
                    "maxChangedPixelPercent" to num("Optional override for max changed pixel percent (default 0.10)."),
                    "maxColorDistance" to num("Optional override for max color distance (default 3)."),
                ),
        )

    private fun reportSchema(): Map<String, Any> =
        schema(
            "rootPath",
            "goldensDir",
            props =
                mapOf(
                    "rootPath" to rootPathProp,
                    "capturesDir" to
                        str("Optional visuals output dir containing captures/manifest.tsv. Defaults to the artifact root visuals dir."),
                    "goldensDir" to str("Project-relative or absolute path to the goldens directory."),
                    "maxChangedPixelPercent" to num("Optional override for max changed pixel percent (default 0.10)."),
                    "maxColorDistance" to num("Optional override for max color distance (default 3)."),
                    "devices" to str("Optional comma-separated expected devices for missing-capture detection."),
                    "themes" to str("Optional comma-separated expected themes."),
                    "fontScales" to str("Optional comma-separated expected font scales."),
                    "locales" to str("Optional comma-separated expected locales."),
                ),
        )

    private fun updateGoldensSchema(): Map<String, Any> =
        schema(
            "rootPath",
            "goldensDir",
            "confirmDestructive",
            props =
                mapOf(
                    "rootPath" to rootPathProp,
                    "goldensDir" to str("Project-relative or absolute path to the goldens directory to overwrite."),
                    "confirmDestructive" to bool("Must be true to overwrite goldens."),
                ),
        )

    private fun diff(arguments: Map<String, Any?>): Map<String, Any> {
        val root = context.resolveRoot(arguments)
        val baseline =
            confinedPath(root, arguments["baselinePath"])
                ?: return blocked("missing-baseline", "baselinePath is required and must stay under the project root.")
        val candidate =
            confinedPath(root, arguments["candidatePath"])
                ?: return blocked("missing-candidate", "candidatePath is required and must stay under the project root.")
        if (!Files.exists(baseline)) return blocked("missing-baseline-file", "Baseline PNG does not exist: $baseline")
        if (!Files.exists(candidate)) return blocked("missing-candidate-file", "Candidate PNG does not exist: $candidate")
        val tolerance = tolerance(arguments)
        val sessionId = context.safeId("visual-diff-${System.currentTimeMillis()}")
        val diffDir = context.artifactOutputDir(root).resolve("visuals/diffs").also { Files.createDirectories(it) }
        val diffFile = diffDir.resolve("$sessionId.png")
        val result =
            try {
                PngDiffEngine().compare(baseline, candidate, diffFile, tolerance)
            } catch (e: IllegalArgumentException) {
                return context.resultMap(
                    ToolResult(
                        status = ResultStatus.FAILED,
                        summary = e.message ?: "Baseline and candidate dimensions differ.",
                        warnings = listOf("dimension-mismatch"),
                    ),
                )
            }
        val diffRef =
            context.registerExistingArtifact(
                root,
                diffFile,
                ArtifactType.IMAGE_DIFF,
                "Pixel diff overlay",
                ArtifactSensitivity.SENSITIVE,
            )
        return context.resultMap(
            ToolResult(
                status = if (result.passed) ResultStatus.SUCCESS else ResultStatus.FAILED,
                summary = "Diff: ${"%.2f".format(
                    result.changedPixelPercent,
                )}% of pixels changed (${result.changedPixels}/${result.totalPixels}).",
                artifacts = listOf(diffRef),
            ),
        ) +
            mapOf(
                "changedPixels" to result.changedPixels,
                "totalPixels" to result.totalPixels,
                "changedPixelPercent" to result.changedPixelPercent,
                "passed" to result.passed,
            )
    }

    private fun report(arguments: Map<String, Any?>): Map<String, Any> {
        val root = context.resolveRoot(arguments)
        val goldensDir =
            confinedPath(root, arguments["goldensDir"])
                ?: return blocked("missing-goldens-dir", "goldensDir is required and must stay under the project root.")
        val capturesDir = confinedPath(root, arguments["capturesDir"]) ?: context.artifactOutputDir(root).resolve("visuals")
        val tolerance = tolerance(arguments)
        val expectedMatrix = expectedMatrix(arguments)
        val report =
            try {
                VisualCaptureEngine.generateReport(capturesDir, goldensDir, tolerance, expectedMatrix)
            } catch (e: IllegalArgumentException) {
                return context.resultMap(
                    ToolResult(
                        status = ResultStatus.BLOCKED,
                        summary = e.message ?: "Invalid visual matrix.",
                        warnings = listOf("invalid-matrix"),
                    ),
                )
            }
        return context.resultMap(
            ToolResult(
                status = report.status,
                summary = "Visual report: ${report.cases.size} case(s), ${report.findings.size} finding(s).",
                warnings = report.warnings,
            ),
        ) +
            mapOf(
                "cases" to report.cases.size,
                "findings" to report.findings.map(::findingToMap),
                "correlation" to report.agentFixPacket.markdown,
            )
    }

    private fun updateGoldens(arguments: Map<String, Any?>): Map<String, Any> {
        val decision = authorize("android_visual_update_goldens", setOf(Capability.GOLDEN_UPDATE), true, arguments)
        if (decision is AuthorizationDecision.Denied) {
            return context.resultMap(ToolResult(status = ResultStatus.BLOCKED, summary = decision.reason, warnings = listOf(decision.code)))
        }
        val root = context.resolveRoot(arguments)
        val goldensDir =
            confinedPath(root, arguments["goldensDir"])
                ?: return blocked("missing-goldens-dir", "goldensDir is required and must stay under the project root.")
        val capturesDir = context.artifactOutputDir(root).resolve("visuals")
        val updated = VisualCaptureEngine.updateGoldens(capturesDir, goldensDir)
        return context.resultMap(
            ToolResult(
                status = ResultStatus.SUCCESS,
                summary = "Updated ${updated.size} golden image(s) in $goldensDir.",
            ),
        ) + mapOf("updatedGoldens" to updated.map { it.toString() })
    }

    private fun confinedPath(
        root: Path,
        raw: Any?,
    ): Path? {
        val text = raw?.toString()?.takeIf { it.isNotBlank() } ?: return null
        val resolved = Path.of(text)
        val absolute = if (resolved.isAbsolute) resolved.normalize() else root.resolve(resolved).normalize()
        if (!absolute.startsWith(root)) return null
        return absolute
    }

    private fun tolerance(arguments: Map<String, Any?>): VisualTolerance {
        val pct = arguments["maxChangedPixelPercent"]?.toString()?.toDoubleOrNull()
        val dist = arguments["maxColorDistance"]?.toString()?.toIntOrNull()
        return VisualTolerance(
            maxChangedPixelPercent = pct ?: VisualTolerance().maxChangedPixelPercent,
            maxColorDistance = dist ?: VisualTolerance().maxColorDistance,
        )
    }

    private fun expectedMatrix(arguments: Map<String, Any?>): VisualMatrix? {
        val devices = csv(arguments["devices"])
        val themes = csv(arguments["themes"])
        val fontScales = arguments["fontScales"]?.toString()?.split(",")?.mapNotNull { it.trim().toFloatOrNull() } ?: emptyList()
        val locales = csv(arguments["locales"])
        if (devices.isEmpty() && themes.isEmpty() && fontScales.isEmpty() && locales.isEmpty()) return null
        val matrix =
            VisualMatrix(
                devices = devices.ifEmpty { listOf("phone_412x915") },
                themes = themes.ifEmpty { listOf("light") },
                fontScales = fontScales.ifEmpty { listOf(1.0f) },
                locales = locales.ifEmpty { listOf("en") },
            )
        matrix.validate()
        return matrix
    }

    private fun csv(raw: Any?): List<String> =
        raw
            ?.toString()
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() } ?: emptyList()

    private fun authorize(
        tool: String,
        capabilities: Set<Capability>,
        destructive: Boolean,
        arguments: Map<String, Any?>,
    ): AuthorizationDecision {
        val request =
            OperationRequest(
                operationId = tool,
                requiredCapabilities = capabilities,
                destructive = destructive,
                confirmDestructive = arguments["confirmDestructive"] == true,
            )
        return context.authorize(request)
    }

    private fun blocked(
        code: String,
        reason: String,
    ): Map<String, Any> = context.resultMap(ToolResult(status = ResultStatus.BLOCKED, summary = reason, warnings = listOf(code)))

    private fun unsupported(name: String): Map<String, Any> =
        context.resultMap(
            ToolResult(status = ResultStatus.UNSUPPORTED, summary = "Unknown visuals tool: $name", warnings = listOf("unknown-tool")),
        )

    private fun findingToMap(finding: com.droidagentkit.visuals.VisualFinding): Map<String, Any> =
        mapOf(
            "id" to finding.id,
            "category" to finding.category.wireName,
            "severity" to finding.severity.name.lowercase(),
            "caseName" to finding.caseName,
            "title" to finding.title,
            "likelyCause" to finding.likelyCause,
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
}
