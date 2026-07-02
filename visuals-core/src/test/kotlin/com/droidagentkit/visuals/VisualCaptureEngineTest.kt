package com.droidagentkit.visuals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class VisualCaptureEngineTest {
    @Test
    fun `persistCapture writes png semantics file and manifest line`() {
        val outputDir = Files.createTempDirectory("dak-visual-capture")
        val pngBytes = byteArrayOf(1, 2, 3, 4)

        val capture = VisualCaptureEngine.persistCapture(
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
}
