package com.droidagentkit.cli

import com.droidagentkit.visuals.VisualCaptureEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO

class DroidAgentCliIntegrationTest {
    @Test
    fun `run exits non-zero when config is invalid for a command that loads config`() {
        val root = Files.createTempDirectory("dak-cli-invalid-config")
        val configFile = root.resolve(".droidagentkit/config.yaml")
        Files.createDirectories(configFile.parent)
        Files.writeString(configFile, "schemaVersion: 1\nsafety:\n  maxCommandSeconds: soon\n")

        val exitCode = DroidAgentCli().run(arrayOf("devices", "--project", root.toString()))

        assertEquals(1, exitCode)
    }

    @Test
    fun `visuals report writes a real report and exits non-zero on regression`() {
        val root = Files.createTempDirectory("dak-cli-visuals-report")
        val outputDir = root.resolve("build/droidagentkit/visuals")
        val goldensDir = root.resolve("src/test/resources/droidagentkit/goldens")
        VisualCaptureEngine.persistCapture(outputDir, "home_screen", "phone_412x915", "light", 1.0f, "en", solidColorPng(Color.BLACK), "")
        val goldenDir = goldensDir.resolve("home_screen")
        Files.createDirectories(goldenDir)
        Files.write(goldenDir.resolve("phone_412x915_light_1.0_en.png"), solidColorPng(Color.WHITE))

        val exitCode = DroidAgentCli().run(arrayOf("visuals", "report", "--project", root.toString()))

        assertEquals(2, exitCode)
        assertTrue(Files.exists(outputDir.resolve("visual-report.md")))
    }

    @Test
    fun `visuals update-goldens copies fresh captures`() {
        val root = Files.createTempDirectory("dak-cli-visuals-update")
        val outputDir = root.resolve("build/droidagentkit/visuals")
        VisualCaptureEngine.persistCapture(outputDir, "home_screen", "phone_412x915", "light", 1.0f, "en", solidColorPng(Color.WHITE), "")

        val exitCode = DroidAgentCli().run(arrayOf("visuals", "update-goldens", "--project", root.toString()))

        assertEquals(0, exitCode)
        assertTrue(Files.exists(root.resolve("src/test/resources/droidagentkit/goldens/home_screen/phone_412x915_light_1.0_en.png")))
    }

    @Test
    fun `visuals rejects unknown action`() {
        val root = Files.createTempDirectory("dak-cli-visuals-unknown")

        val exitCode = DroidAgentCli().run(arrayOf("visuals", "compare", "--project", root.toString()))

        assertEquals(1, exitCode)
    }

    private fun solidColorPng(color: Color, size: Int = 10): ByteArray {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = color
        graphics.fillRect(0, 0, size, size)
        graphics.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }
}
