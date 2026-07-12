package com.droidagentkit.visuals

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

enum class ScreenshotArtifactRole {
    REFERENCE,
    ACTUAL,
    DIFF,
    UNCLASSIFIED,
}

data class OfficialScreenshotArtifact(
    val role: ScreenshotArtifactRole,
    val path: Path,
    val label: String,
    val confidence: String,
)

data class OfficialScreenshotReport(
    val variant: String,
    val reportPath: Path,
    val artifacts: List<OfficialScreenshotArtifact>,
    val sourceUrl: String = "https://developer.android.com/studio/preview/compose-screenshot-testing",
    val experimental: Boolean = true,
)

object ComposeScreenshotReportAdapter {
    fun import(
        moduleRoot: Path,
        variant: String,
    ): OfficialScreenshotReport? {
        require(variant.matches(Regex("[A-Za-z0-9_-]+"))) { "Variant contains unsupported characters." }
        val normalizedRoot = moduleRoot.toAbsolutePath().normalize()
        val report = normalizedRoot.resolve("build/reports/screenshotTest/preview/$variant/index.html")
        if (!report.exists()) return null
        val html = Files.readString(report)
        val artifacts =
            IMAGE_TAG
                .findAll(html)
                .mapNotNull { match ->
                    val attributes = match.groupValues[1]
                    val source = attribute(attributes, "src") ?: return@mapNotNull null
                    val label = attribute(attributes, "alt").orEmpty()
                    val path = resolveLocalImage(report.parent, source) ?: return@mapNotNull null
                    if (!path.startsWith(normalizedRoot) || !path.exists()) return@mapNotNull null
                    val evidence = "$label ${path.fileName}".lowercase()
                    val role =
                        when {
                            "reference" in evidence -> ScreenshotArtifactRole.REFERENCE
                            "actual" in evidence -> ScreenshotArtifactRole.ACTUAL
                            "diff" in evidence || "difference" in evidence -> ScreenshotArtifactRole.DIFF
                            else -> ScreenshotArtifactRole.UNCLASSIFIED
                        }
                    OfficialScreenshotArtifact(
                        role = role,
                        path = path,
                        label = label,
                        confidence = if (role == ScreenshotArtifactRole.UNCLASSIFIED) "unknown" else "declared-label",
                    )
                }.distinctBy { it.path }
                .sortedBy { it.path.toString() }
                .toList()
        return OfficialScreenshotReport(variant, report, artifacts)
    }

    private fun attribute(
        attributes: String,
        name: String,
    ): String? = Regex("""\b$name\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(attributes)?.groupValues?.get(1)

    private fun resolveLocalImage(
        reportDirectory: Path,
        source: String,
    ): Path? {
        if (source.startsWith("data:") || source.startsWith("http:") || source.startsWith("https:")) return null
        val decoded = runCatching { URI(null, null, source, null).path }.getOrNull() ?: return null
        if (!decoded.lowercase().endsWith(".png")) return null
        return reportDirectory.resolve(decoded).normalize().toAbsolutePath()
    }

    private val IMAGE_TAG = Regex("""<img\b([^>]*)>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
}
