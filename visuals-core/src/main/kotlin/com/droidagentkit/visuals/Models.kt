package com.droidagentkit.visuals

import com.droidagentkit.core.ArtifactRef
import com.droidagentkit.core.ResultStatus

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

enum class VisualSeverity {
    INFO,
    WARNING,
    ERROR,
}

enum class VisualFindingCategory(val wireName: String) {
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
)
