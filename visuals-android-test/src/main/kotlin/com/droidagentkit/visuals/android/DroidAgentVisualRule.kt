package com.droidagentkit.visuals.android

import com.droidagentkit.visuals.CaptureEnvironment
import com.droidagentkit.visuals.VisualCapture
import com.droidagentkit.visuals.VisualCaptureEngine
import com.droidagentkit.visuals.VisualMatrix
import java.nio.file.Path

/**
 * JVM byte-capture adapter. This is NOT an Android JUnit/instrumentation lifecycle rule; target
 * projects own Compose rendering and call this helper to persist capture bytes for diffing.
 */
class DroidAgentVisualRule(
    private val outputDir: Path = Path.of("build/droidagentkit/visuals"),
) {
    fun captureCompose(
        name: String,
        matrix: VisualMatrix = VisualMatrix.standard(),
        semantics: List<String> = emptyList(),
        render: () -> ByteArray,
    ): VisualCapture {
        matrix.validate()
        val environment = matrix.cartesian().first()
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

    /**
     * Caller-owned full Cartesian capture: renders one PNG per environment in [matrix] and returns
     * every capture. Empty axes and cardinality above [VisualMatrix.MAX_CARDINALITY] are rejected
     * before any rendering happens.
     */
    fun captureMatrix(
        name: String,
        matrix: VisualMatrix,
        render: (CaptureEnvironment) -> ByteArray,
    ): List<VisualCapture> {
        matrix.validate()
        return matrix.cartesian().map { environment ->
            VisualCaptureEngine.persistCapture(
                outputDir = outputDir,
                caseName = name,
                device = environment.device,
                theme = environment.theme,
                fontScale = environment.fontScale,
                locale = environment.locale,
                pngBytes = render(environment),
                semanticsDump = "",
            )
        }
    }
}
