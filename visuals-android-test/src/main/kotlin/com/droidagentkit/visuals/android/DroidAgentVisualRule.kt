package com.droidagentkit.visuals.android

data class VisualMatrix(
    val devices: List<String>,
    val themes: List<String>,
    val fontScales: List<Float>,
    val locales: List<String>,
) {
    companion object {
        fun standard() = VisualMatrix(
            devices = listOf("phone_412x915"),
            themes = listOf("light"),
            fontScales = listOf(1.0f),
            locales = listOf("en"),
        )
    }
}

data class VisualCapture<T>(
    val caseName: String,
    val environment: CaptureEnvironment,
    val renderedValue: T,
    val semanticsDump: String,
)

data class CaptureEnvironment(
    val device: String,
    val theme: String,
    val fontScale: Float,
    val locale: String,
)

class DroidAgentVisualRule {
    fun <T> captureCompose(
        name: String,
        matrix: VisualMatrix = VisualMatrix.standard(),
        semantics: List<String> = emptyList(),
        render: () -> T,
    ): VisualCapture<T> {
        val environment = CaptureEnvironment(
            device = matrix.devices.firstOrNull() ?: "phone_412x915",
            theme = matrix.themes.firstOrNull() ?: "light",
            fontScale = matrix.fontScales.firstOrNull() ?: 1.0f,
            locale = matrix.locales.firstOrNull() ?: "en",
        )
        return VisualCapture(
            caseName = name,
            environment = environment,
            renderedValue = render(),
            semanticsDump = semantics.joinToString(separator = "\n"),
        )
    }
}
