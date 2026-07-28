package com.droidagentkit.visuals.android

import com.droidagentkit.visuals.VisualMatrix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DroidAgentVisualRuleTest {
    @Test
    fun `captureCompose persists a real png and records deterministic case metadata`() {
        val outputDir = Files.createTempDirectory("dak-visual-rule")
        val rule = DroidAgentVisualRule(outputDir)
        val pngBytes = byteArrayOf(1, 2, 3)

        val capture =
            rule.captureCompose(
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

    @Test
    fun `captureMatrix renders one capture per cartesian environment`() {
        val outputDir = Files.createTempDirectory("dak-visual-rule-matrix")
        val rule = DroidAgentVisualRule(outputDir)
        val matrix =
            VisualMatrix(
                devices = listOf("phone_412x915"),
                themes = listOf("light", "dark"),
                fontScales = listOf(1.0f, 2.0f),
                locales = listOf("en"),
            )

        val captures =
            rule.captureMatrix(name = "home_screen", matrix = matrix) { env ->
                byteArrayOf(
                    env.theme
                        .first()
                        .code
                        .toByte(),
                )
            }

        assertEquals(4, captures.size)
        val keys = captures.map { "${it.environment.theme}_${it.environment.fontScale}" }.toSet()
        assertTrue(keys.contains("light_1.0"))
        assertTrue(keys.contains("light_2.0"))
        assertTrue(keys.contains("dark_1.0"))
        assertTrue(keys.contains("dark_2.0"))
        val manifest = outputDir.resolve("captures/manifest.tsv")
        assertEquals(4, Files.readAllLines(manifest).size)
    }

    @Test
    fun `captureMatrix rejects empty axes`() {
        val outputDir = Files.createTempDirectory("dak-visual-rule-empty")
        val rule = DroidAgentVisualRule(outputDir)
        val matrix = VisualMatrix(devices = emptyList(), themes = listOf("light"), fontScales = listOf(1.0f), locales = listOf("en"))

        assertThrows(IllegalArgumentException::class.java) {
            rule.captureMatrix(name = "home_screen", matrix = matrix) { byteArrayOf(0) }
        }
    }

    @Test
    fun `captureMatrix rejects cardinality above 64`() {
        val outputDir = Files.createTempDirectory("dak-visual-rule-big")
        val rule = DroidAgentVisualRule(outputDir)
        val matrix =
            VisualMatrix(
                devices = (1..9).map { "d$it" },
                themes = listOf("light", "dark"),
                fontScales = listOf(1.0f, 2.0f),
                locales =
                    (1..3).map { "loc$it" },
            )

        assertThrows(IllegalArgumentException::class.java) {
            rule.captureMatrix(name = "home_screen", matrix = matrix) { byteArrayOf(0) }
        }
    }

    @Test
    fun `captureCompose uses first matrix environment`() {
        val outputDir = Files.createTempDirectory("dak-visual-rule-first")
        val rule = DroidAgentVisualRule(outputDir)
        val matrix =
            VisualMatrix(
                devices = listOf("phone_412x915", "tablet"),
                themes = listOf("dark", "light"),
                fontScales = listOf(1.0f),
                locales = listOf("en"),
            )

        val capture = rule.captureCompose(name = "home_screen", matrix = matrix) { byteArrayOf(9) }

        assertEquals("phone_412x915", capture.environment.device)
        assertEquals("dark", capture.environment.theme)
    }
}
