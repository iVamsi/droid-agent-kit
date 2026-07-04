package com.droidagentkit.visuals.android

import com.droidagentkit.visuals.VisualCapture
import com.droidagentkit.visuals.VisualCaptureEngine
import java.nio.file.Path

data class VisualMatrix(
    val devices: List<String>,
    val themes: List<String>,
    val fontScales: List<Float>,
    val locales: List<String>,
) {
    companion object {
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

class DroidAgentVisualRule(
    private val outputDir: Path = Path.of("build/droidagentkit/visuals"),
) {
    fun captureCompose(
        name: String,
        matrix: VisualMatrix = VisualMatrix.standard(),
        semantics: List<String> = emptyList(),
        render: () -> ByteArray,
    ): VisualCapture {
        val environment =
            CaptureEnvironment(
                device = matrix.devices.firstOrNull() ?: "phone_412x915",
                theme = matrix.themes.firstOrNull() ?: "light",
                fontScale = matrix.fontScales.firstOrNull() ?: 1.0f,
                locale = matrix.locales.firstOrNull() ?: "en",
            )
        return VisualCaptureEngine.persistCapture(
            outputDir = outputDir,
            caseName = name,
            device = environment.device,
            theme = environment.theme,
            fontScale = environment.fontScale,
            locale = environment.locale,
            pngBytes = render(),
            semanticsDump = semantics.joinToString(separator = "\n"),
        )
    }
}
