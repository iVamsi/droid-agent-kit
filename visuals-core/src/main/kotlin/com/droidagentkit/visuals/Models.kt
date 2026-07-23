package com.droidagentkit.visuals

import com.droidagentkit.core.ArtifactRef
import com.droidagentkit.core.ResultStatus
import java.nio.file.Path

data class VisualTolerance(
    val maxChangedPixelPercent: Double = 0.10,
    val maxColorDistance: Int = 3,
)

data class PngDiffResult(
    val changedPixels: Int,
    val totalPixels: Int,
    val changedPixelPercent: Double,
    val passed: Boolean,
)

data class VisualEnvironment(
    val theme: String = "light",
    val fontScale: Float = 1.0f,
    val locale: String = "en",
    val device: String = "phone_412x915",
    val apiLevel: Int? = null,
    val density: Float? = null,
)

data class VisualCapture(
    val caseName: String,
    val environment: VisualEnvironment,
    val pngPath: Path,
    val semanticsPath: Path,
    val capturedAt: String,
)

enum class VisualSeverity {
    INFO,
    WARNING,
    ERROR,
}

enum class VisualFindingCategory(
    val wireName: String,
) {
    PIXEL_DIFF("pixel_diff"),
    TEXT_CLIPPING("text_clipping"),
    ELEMENT_OVERLAP("element_overlap"),
    MISSING_SEMANTICS_LABEL("missing_semantics_label"),
    SMALL_TOUCH_TARGET("small_touch_target"),
    CONTRAST_WARNING("contrast_warning"),
    RTL_LAYOUT_ISSUE("rtl_layout_issue"),
    LARGE_FONT_OVERFLOW("large_font_overflow"),
    UNEXPECTED_BLANK_SCREEN("unexpected_blank_screen"),
    SCREENSHOT_CAPTURE_FAILURE("screenshot_capture_failure"),
    MISSING_CAPTURE("missing_capture"),
}

data class VisualFinding(
    val id: String,
    val category: VisualFindingCategory,
    val severity: VisualSeverity,
    val caseName: String,
    val title: String,
    val evidence: List<ArtifactRef>,
    val likelyCause: String,
    val suggestedFixPrompt: String,
)

data class VisualCaseResult(
    val caseName: String,
    val environment: VisualEnvironment,
    val status: ResultStatus,
    val findings: List<VisualFinding>,
)

data class AgentFixPacket(
    val markdown: String,
)

data class VisualReport(
    val schemaVersion: String = "1.0",
    val status: ResultStatus,
    val cases: List<VisualCaseResult>,
    val findings: List<VisualFinding>,
    val artifacts: List<ArtifactRef>,
    val agentFixPacket: AgentFixPacket,
    val warnings: List<String> = emptyList(),
)

/**
 * Cartesian capture matrix shared by the JVM capture helper and the Gradle plugin.
 * Lives in `visuals-core` so the plugin can define expected report combinations without depending
 * on the JVM capture adapter (`visuals-android-test`).
 */
data class VisualMatrix(
    val devices: List<String>,
    val themes: List<String>,
    val fontScales: List<Float>,
    val locales: List<String>,
) {
    fun cartesian(): List<CaptureEnvironment> {
        val envs = mutableListOf<CaptureEnvironment>()
        for (device in devices) {
            for (theme in themes) {
                for (fontScale in fontScales) {
                    for (locale in locales) {
                        envs.add(CaptureEnvironment(device, theme, fontScale, locale))
                    }
                }
            }
        }
        return envs
    }

    fun cardinality(): Long = devices.size.toLong() * themes.size * fontScales.size * locales.size

    fun validate() {
        require(devices.isNotEmpty()) { "VisualMatrix.devices must not be empty." }
        require(themes.isNotEmpty()) { "VisualMatrix.themes must not be empty." }
        require(fontScales.isNotEmpty()) { "VisualMatrix.fontScales must not be empty." }
        require(locales.isNotEmpty()) { "VisualMatrix.locales must not be empty." }
        val card = cardinality()
        require(card in 1..MAX_CARDINALITY) {
            "VisualMatrix cardinality $card exceeds the maximum of $MAX_CARDINALITY."
        }
    }

    companion object {
        const val MAX_CARDINALITY: Int = 64

        fun standard() =
            VisualMatrix(
                devices = listOf("phone_412x915"),
                themes = listOf("light"),
                fontScales = listOf(1.0f),
                locales = listOf("en"),
            )
    }
}

data class CaptureEnvironment(
    val device: String,
    val theme: String,
    val fontScale: Float,
    val locale: String,
)
