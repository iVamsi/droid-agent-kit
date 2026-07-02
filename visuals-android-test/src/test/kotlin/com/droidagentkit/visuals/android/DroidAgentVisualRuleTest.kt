package com.droidagentkit.visuals.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DroidAgentVisualRuleTest {
    @Test
    fun `captureCompose persists a real png and records deterministic case metadata`() {
        val outputDir = Files.createTempDirectory("dak-visual-rule")
        val rule = DroidAgentVisualRule(outputDir)
        val pngBytes = byteArrayOf(1, 2, 3)

        val capture = rule.captureCompose(
            name = "home_screen",
            matrix = VisualMatrix.standard(),
            semantics = listOf("Button: Start"),
        ) {
            pngBytes
        }

        assertEquals("home_screen", capture.caseName)
        assertTrue(capture.environment.theme.isNotBlank())
        assertTrue(Files.exists(capture.pngPath))
        assertTrue(Files.readAllBytes(capture.pngPath).contentEquals(pngBytes))
        assertEquals("Button: Start", Files.readString(capture.semanticsPath))
    }
}
