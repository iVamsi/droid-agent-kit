package com.droidagentkit.visuals

import com.droidagentkit.core.ArtifactRef
import com.droidagentkit.core.ArtifactType
import com.droidagentkit.core.ArtifactWriter
import com.droidagentkit.core.ResultStatus
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant

object VisualCaptureEngine {
    fun persistCapture(
        outputDir: Path,
        caseName: String,
        device: String,
        theme: String,
        fontScale: Float,
        locale: String,
        pngBytes: ByteArray,
        semanticsDump: String,
    ): VisualCapture {
        val capturesDir = outputDir.resolve("captures")
        val writer = ArtifactWriter(capturesDir)
        val key = envKey(device, theme, fontScale, locale)
        val pngRef = writer.writeBytes(
            "$caseName--$key.png",
            pngBytes,
            ArtifactType.SCREENSHOT,
            "Visual capture: $caseName ($key)",
        )
        val semanticsRef = writer.writeText(
            "$caseName--$key.semantics.txt",
            semanticsDump,
            ArtifactType.OTHER,
            "Semantics dump: $caseName ($key)",
        )
        val capturedAt = Instant.now().toString()
        appendManifestLine(capturesDir, caseName, device, theme, fontScale, locale, Path.of(pngRef.path).fileName.toString(), Path.of(semanticsRef.path).fileName.toString(), capturedAt)
        return VisualCapture(
            caseName = caseName,
            environment = VisualEnvironment(device = device, theme = theme, fontScale = fontScale, locale = locale),
            pngPath = Path.of(pngRef.path),
            semanticsPath = Path.of(semanticsRef.path),
            capturedAt = capturedAt,
        )
    }

    fun generateReport(outputDir: Path, goldensDir: Path, tolerance: VisualTolerance): VisualReport {
        val capturesDir = outputDir.resolve("captures")
        val manifest = capturesDir.resolve("manifest.tsv")
        val entries = if (Files.exists(manifest)) Files.readAllLines(manifest).mapNotNull(::parseManifestLine) else emptyList()
        val cases = entries.map { entry -> buildCaseResult(entry, capturesDir, goldensDir, tolerance, outputDir) }
        return VisualReportBuilder().build(cases)
    }

    fun updateGoldens(outputDir: Path, goldensDir: Path): List<Path> {
        val capturesDir = outputDir.resolve("captures")
        val manifest = capturesDir.resolve("manifest.tsv")
        if (!Files.exists(manifest)) return emptyList()
        val entries = Files.readAllLines(manifest).mapNotNull(::parseManifestLine)
        return entries.map { entry ->
            val key = envKey(entry.device, entry.theme, entry.fontScale, entry.locale)
            val destDir = goldensDir.resolve(entry.caseName)
            Files.createDirectories(destDir)
            val dest = destDir.resolve("$key.png")
            Files.copy(capturesDir.resolve(entry.pngFile), dest, StandardCopyOption.REPLACE_EXISTING)
            dest
        }
    }

    fun renderMarkdown(report: VisualReport, packageName: String = "unknown"): String = buildString {
        appendLine("# DroidAgentKit Visual Report")
        appendLine()
        appendLine("Package: $packageName")
        appendLine("Status: ${report.status.wireName}")
        appendLine()
        if (report.cases.isEmpty()) {
            appendLine("No visual cases were collected. Add DroidAgentVisualRule-based tests to produce case artifacts.")
        } else {
            report.cases.forEach { case ->
                appendLine(
                    "## ${case.caseName} (${case.environment.device}, ${case.environment.theme}, " +
                        "${case.environment.locale}, ${case.environment.fontScale}x) — ${case.status.wireName}",
                )
                case.findings.forEach { finding ->
                    appendLine("- [${finding.severity.name}] ${finding.title}: ${finding.likelyCause}")
                }
            }
        }
        appendLine()
        append(report.agentFixPacket.markdown)
    }

    private fun buildCaseResult(entry: ManifestEntry, capturesDir: Path, goldensDir: Path, tolerance: VisualTolerance, outputDir: Path): VisualCaseResult {
        val environment = VisualEnvironment(device = entry.device, theme = entry.theme, fontScale = entry.fontScale, locale = entry.locale)
        val key = envKey(entry.device, entry.theme, entry.fontScale, entry.locale)
        val capturePng = capturesDir.resolve(entry.pngFile)
        val goldenPng = goldensDir.resolve(entry.caseName).resolve("$key.png")

        if (!Files.exists(goldenPng)) {
            return VisualCaseResult(
                caseName = entry.caseName,
                environment = environment,
                status = ResultStatus.PARTIAL,
                findings = listOf(
                    VisualFinding(
                        id = "${entry.caseName}-$key-no-golden",
                        category = VisualFindingCategory.PIXEL_DIFF,
                        severity = VisualSeverity.WARNING,
                        caseName = entry.caseName,
                        title = "No golden image yet for $key",
                        evidence = listOf(ArtifactRef(ArtifactType.SCREENSHOT, capturePng.toString(), "image/png", "Fresh capture, no baseline yet")),
                        likelyCause = "This case/environment has never had droidAgentVisualsUpdateGoldens run for it.",
                        suggestedFixPrompt = "Run droidAgentVisualsUpdateGoldens (or `droidagent visuals update-goldens`) to accept this as the baseline.",
                    ),
                ),
            )
        }

        val diffDir = outputDir.resolve("diffs")
        Files.createDirectories(diffDir)
        val diffFile = diffDir.resolve("${entry.caseName}--$key.png")

        val diffResult = try {
            PngDiffEngine().compare(goldenPng, capturePng, diffFile, tolerance)
        } catch (error: IllegalArgumentException) {
            return VisualCaseResult(
                caseName = entry.caseName,
                environment = environment,
                status = ResultStatus.FAILED,
                findings = listOf(
                    VisualFinding(
                        id = "${entry.caseName}-$key-dimension-mismatch",
                        category = VisualFindingCategory.PIXEL_DIFF,
                        severity = VisualSeverity.ERROR,
                        caseName = entry.caseName,
                        title = "Image dimensions changed for $key",
                        evidence = listOf(ArtifactRef(ArtifactType.SCREENSHOT, capturePng.toString(), "image/png", "Fresh capture")),
                        likelyCause = error.message ?: "Captured image dimensions differ from the golden.",
                        suggestedFixPrompt = "Review the layout change, then run droidAgentVisualsUpdateGoldens if intentional.",
                    ),
                ),
            )
        }

        if (diffResult.passed) {
            return VisualCaseResult(caseName = entry.caseName, environment = environment, status = ResultStatus.SUCCESS, findings = emptyList())
        }
        return VisualCaseResult(
            caseName = entry.caseName,
            environment = environment,
            status = ResultStatus.FAILED,
            findings = listOf(
                VisualFinding(
                    id = "${entry.caseName}-$key-pixel-diff",
                    category = VisualFindingCategory.PIXEL_DIFF,
                    severity = VisualSeverity.ERROR,
                    caseName = entry.caseName,
                    title = "Pixel diff exceeds tolerance for $key",
                    evidence = listOf(ArtifactRef(ArtifactType.IMAGE_DIFF, diffFile.toString(), "image/png", "Pixel diff overlay")),
                    likelyCause = "${"%.2f".format(diffResult.changedPixelPercent)}% of pixels changed (tolerance: ${tolerance.maxChangedPixelPercent}%).",
                    suggestedFixPrompt = "Review the diff image, then run droidAgentVisualsUpdateGoldens if intentional.",
                ),
            ),
        )
    }

    private fun parseManifestLine(line: String): ManifestEntry? {
        val parts = line.split("\t")
        if (parts.size != 8) return null
        val fontScale = parts[3].toFloatOrNull() ?: return null
        return ManifestEntry(parts[0], parts[1], parts[2], fontScale, parts[4], parts[5], parts[6], parts[7])
    }

    private data class ManifestEntry(
        val caseName: String,
        val device: String,
        val theme: String,
        val fontScale: Float,
        val locale: String,
        val pngFile: String,
        val semanticsFile: String,
        val capturedAt: String,
    )

    private fun envKey(device: String, theme: String, fontScale: Float, locale: String): String =
        "${device}_${theme}_${fontScale}_${locale}"

    private fun appendManifestLine(
        capturesDir: Path,
        caseName: String,
        device: String,
        theme: String,
        fontScale: Float,
        locale: String,
        pngFile: String,
        semanticsFile: String,
        capturedAt: String,
    ) {
        val manifest = capturesDir.resolve("manifest.tsv")
        val line = listOf(caseName, device, theme, fontScale.toString(), locale, pngFile, semanticsFile, capturedAt).joinToString("\t")
        Files.write(manifest, (line + "\n").toByteArray(), StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }
}
