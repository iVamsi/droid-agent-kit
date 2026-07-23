package com.droidagentkit.visuals

import com.droidagentkit.core.ResultStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO

class VisualCaptureEngineTest {
    @Test
    fun `persistCapture writes png semantics file and manifest line`() {
        val outputDir = Files.createTempDirectory("dak-visual-capture")
        val pngBytes = byteArrayOf(1, 2, 3, 4)

        val capture =
            VisualCaptureEngine.persistCapture(
                outputDir = outputDir,
                caseName = "home_screen",
                device = "phone_412x915",
                theme = "light",
                fontScale = 1.0f,
                locale = "en",
                pngBytes = pngBytes,
                semanticsDump = "Button: Start",
            )

        assertEquals("home_screen", capture.caseName)
        assertEquals("phone_412x915", capture.environment.device)
        assertEquals("light", capture.environment.theme)
        assertTrue(Files.exists(capture.pngPath))
        assertTrue(Files.exists(capture.semanticsPath))
        assertEquals("Button: Start", Files.readString(capture.semanticsPath))
        assertTrue(Files.readAllBytes(capture.pngPath).contentEquals(pngBytes))

        val manifest = outputDir.resolve("captures/manifest.tsv")
        assertTrue(Files.exists(manifest))
        val fields = Files.readAllLines(manifest).single().split("\t")
        assertEquals("home_screen", fields[0])
        assertEquals("phone_412x915", fields[1])
        assertEquals("light", fields[2])
        assertEquals("1.0", fields[3])
        assertEquals("en", fields[4])
    }

    @Test
    fun `persistCapture appends multiple manifest lines across calls`() {
        val outputDir = Files.createTempDirectory("dak-visual-capture-multi")

        VisualCaptureEngine.persistCapture(outputDir, "home_screen", "phone_412x915", "light", 1.0f, "en", byteArrayOf(1), "")
        VisualCaptureEngine.persistCapture(outputDir, "home_screen", "phone_412x915", "dark", 1.0f, "en", byteArrayOf(2), "")

        val manifest = outputDir.resolve("captures/manifest.tsv")
        assertEquals(2, Files.readAllLines(manifest).size)
    }

    @Test
    fun `generateReport marks case as success when capture matches golden within tolerance`() {
        val outputDir = Files.createTempDirectory("dak-report-success")
        val goldensDir = Files.createTempDirectory("dak-goldens-success")
        val png = solidColorPng(Color.WHITE)
        VisualCaptureEngine.persistCapture(outputDir, "home_screen", "phone_412x915", "light", 1.0f, "en", png, "")
        val goldenDir = goldensDir.resolve("home_screen")
        Files.createDirectories(goldenDir)
        Files.write(goldenDir.resolve("phone_412x915_light_1.0_en.png"), png)

        val report = VisualCaptureEngine.generateReport(outputDir, goldensDir, VisualTolerance())

        assertEquals(ResultStatus.SUCCESS, report.status)
        assertEquals(1, report.cases.size)
        assertEquals(ResultStatus.SUCCESS, report.cases[0].status)
    }

    @Test
    fun `generateReport flags case as failed when diff exceeds tolerance`() {
        val outputDir = Files.createTempDirectory("dak-report-fail")
        val goldensDir = Files.createTempDirectory("dak-goldens-fail")
        val golden = solidColorPng(Color.WHITE)
        val capture = solidColorPngWithChangedPixel(Color.WHITE, Color.BLACK)
        VisualCaptureEngine.persistCapture(outputDir, "home_screen", "phone_412x915", "light", 1.0f, "en", capture, "")
        val goldenDir = goldensDir.resolve("home_screen")
        Files.createDirectories(goldenDir)
        Files.write(goldenDir.resolve("phone_412x915_light_1.0_en.png"), golden)

        val report =
            VisualCaptureEngine.generateReport(
                outputDir,
                goldensDir,
                VisualTolerance(maxChangedPixelPercent = 0.0, maxColorDistance = 0),
            )

        assertEquals(ResultStatus.FAILED, report.status)
        assertEquals(VisualFindingCategory.PIXEL_DIFF, report.findings.single().category)
    }

    @Test
    fun `generateReport flags case as partial with no-golden warning when golden is missing`() {
        val outputDir = Files.createTempDirectory("dak-report-nogolden")
        val goldensDir = Files.createTempDirectory("dak-goldens-nogolden")
        VisualCaptureEngine.persistCapture(outputDir, "home_screen", "phone_412x915", "light", 1.0f, "en", solidColorPng(Color.WHITE), "")

        val report = VisualCaptureEngine.generateReport(outputDir, goldensDir, VisualTolerance())

        assertEquals(ResultStatus.PARTIAL, report.status)
        assertEquals(VisualSeverity.WARNING, report.findings.single().severity)
        assertTrue(
            report.findings
                .single()
                .title
                .contains("No golden image yet"),
        )
    }

    @Test
    fun `generateReport flags dimension mismatch as a finding not a crash`() {
        val outputDir = Files.createTempDirectory("dak-report-dims")
        val goldensDir = Files.createTempDirectory("dak-goldens-dims")
        VisualCaptureEngine.persistCapture(
            outputDir,
            "home_screen",
            "phone_412x915",
            "light",
            1.0f,
            "en",
            solidColorPng(Color.WHITE, size = 20),
            "",
        )
        val goldenDir = goldensDir.resolve("home_screen")
        Files.createDirectories(goldenDir)
        Files.write(goldenDir.resolve("phone_412x915_light_1.0_en.png"), solidColorPng(Color.WHITE, size = 10))

        val report = VisualCaptureEngine.generateReport(outputDir, goldensDir, VisualTolerance())

        assertEquals(ResultStatus.FAILED, report.status)
        assertTrue(
            report.findings
                .single()
                .title
                .contains("dimensions changed"),
        )
    }

    @Test
    fun `updateGoldens copies fresh captures over goldens`() {
        val outputDir = Files.createTempDirectory("dak-update-goldens")
        val goldensDir = Files.createTempDirectory("dak-update-goldens-dest")
        val png = solidColorPng(Color.WHITE)
        VisualCaptureEngine.persistCapture(outputDir, "home_screen", "phone_412x915", "light", 1.0f, "en", png, "")

        val updated = VisualCaptureEngine.updateGoldens(outputDir, goldensDir)

        assertEquals(1, updated.size)
        val golden = goldensDir.resolve("home_screen/phone_412x915_light_1.0_en.png")
        assertTrue(Files.exists(golden))
        assertTrue(Files.readAllBytes(golden).contentEquals(png))
    }

    @Test
    fun `generateReport detects missing captures against the expected matrix`() {
        val outputDir = Files.createTempDirectory("dak-report-missing")
        val goldensDir = Files.createTempDirectory("dak-goldens-missing")
        VisualCaptureEngine.persistCapture(outputDir, "home_screen", "phone_412x915", "light", 1.0f, "en", solidColorPng(Color.WHITE), "")
        val expectedMatrix =
            VisualMatrix(
                devices = listOf("phone_412x915"),
                themes = listOf("light", "dark"),
                fontScales = listOf(1.0f),
                locales = listOf("en"),
            )

        val report = VisualCaptureEngine.generateReport(outputDir, goldensDir, VisualTolerance(), expectedMatrix)

        assertTrue(report.warnings.any { it.startsWith("missing-capture:home_screen:") && it.contains("dark") })
        assertTrue(report.findings.any { it.category == VisualFindingCategory.MISSING_CAPTURE })
        assertEquals(ResultStatus.PARTIAL, report.status)
    }

    @Test
    fun `generateReport without an expected matrix does not emit missing-capture warnings`() {
        val outputDir = Files.createTempDirectory("dak-report-no-matrix")
        val goldensDir = Files.createTempDirectory("dak-goldens-no-matrix")
        VisualCaptureEngine.persistCapture(outputDir, "home_screen", "phone_412x915", "light", 1.0f, "en", solidColorPng(Color.WHITE), "")

        val report = VisualCaptureEngine.generateReport(outputDir, goldensDir, VisualTolerance())

        assertTrue(report.warnings.none { it.startsWith("missing-capture") })
    }

    @Test
    fun `renderMarkdown includes case name package name and status`() {
        val outputDir = Files.createTempDirectory("dak-markdown")
        val goldensDir = Files.createTempDirectory("dak-markdown-goldens")
        VisualCaptureEngine.persistCapture(outputDir, "home_screen", "phone_412x915", "light", 1.0f, "en", solidColorPng(Color.WHITE), "")

        val report = VisualCaptureEngine.generateReport(outputDir, goldensDir, VisualTolerance())
        val markdown = VisualCaptureEngine.renderMarkdown(report, "com.example.app")

        assertTrue(markdown.contains("home_screen"))
        assertTrue(markdown.contains("com.example.app"))
    }

    private fun solidColorPng(
        color: Color,
        size: Int = 10,
    ): ByteArray {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = color
        graphics.fillRect(0, 0, size, size)
        graphics.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    private fun solidColorPngWithChangedPixel(
        fill: Color,
        changed: Color,
        size: Int = 10,
    ): ByteArray {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = fill
        graphics.fillRect(0, 0, size, size)
        graphics.dispose()
        image.setRGB(0, 0, changed.rgb)
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }
}
