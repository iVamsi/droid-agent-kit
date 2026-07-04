package com.droidagentkit.visuals

import com.droidagentkit.core.ResultStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO

class VisualDiffTest {
    @Test
    fun `diff passes when changed pixels are within tolerance`() {
        val dir = Files.createTempDirectory("dak-visuals")
        val base = dir.resolve("base.png")
        val candidate = dir.resolve("candidate.png")
        writeImage(base, Color.WHITE)
        writeImage(candidate, Color.WHITE, changedPixel = Color(250, 250, 250))

        val result =
            PngDiffEngine().compare(
                baseline = base,
                candidate = candidate,
                diffOutput = dir.resolve("diff.png"),
                tolerance = VisualTolerance(maxChangedPixelPercent = 10.0, maxColorDistance = 10),
            )

        assertEquals(1.0, result.changedPixelPercent, 0.01)
        assertTrue(result.passed)
    }

    @Test
    fun `report marks large visual diff as failed and creates agent fix packet`() {
        val case =
            VisualCaseResult(
                caseName = "home_screen",
                environment = VisualEnvironment(theme = "dark", fontScale = 2.0f, locale = "ar", device = "phone_412x915"),
                status = ResultStatus.FAILED,
                findings =
                    listOf(
                        VisualFinding(
                            id = "large-font-overflow-home",
                            category = VisualFindingCategory.LARGE_FONT_OVERFLOW,
                            severity = VisualSeverity.ERROR,
                            caseName = "home_screen",
                            title = "Text overflows at large font",
                            evidence = emptyList(),
                            likelyCause = "Fixed height container",
                            suggestedFixPrompt = "Inspect HomeScreen fixed heights around the title.",
                        ),
                    ),
            )

        val report = VisualReportBuilder().build(listOf(case))

        assertEquals(ResultStatus.FAILED, report.status)
        assertTrue(report.agentFixPacket.markdown.contains("home_screen"))
        assertTrue(report.agentFixPacket.markdown.contains("Fixed height container"))
    }

    private fun writeImage(
        path: java.nio.file.Path,
        fill: Color,
        changedPixel: Color? = null,
    ) {
        val image = BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = fill
        graphics.fillRect(0, 0, 10, 10)
        graphics.dispose()
        if (changedPixel != null) {
            image.setRGB(0, 0, changedPixel.rgb)
        }
        ImageIO.write(image, "png", path.toFile())
    }
}
