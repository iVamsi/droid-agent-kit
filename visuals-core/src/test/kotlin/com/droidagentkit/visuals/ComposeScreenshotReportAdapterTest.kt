package com.droidagentkit.visuals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ComposeScreenshotReportAdapterTest {
    @Test
    fun `imports only local official screenshot report images with declared roles`() {
        val module = Files.createTempDirectory("dak-official-screenshots")
        val reportDir = module.resolve("build/reports/screenshotTest/preview/debug")
        Files.createDirectories(reportDir.resolve("images"))
        listOf("reference.png", "actual.png", "diff.png", "unknown.png").forEach {
            Files.write(reportDir.resolve("images/$it"), byteArrayOf(1, 2, 3))
        }
        Files.writeString(
            reportDir.resolve("index.html"),
            """
            <img alt="Reference" src="images/reference.png">
            <img alt="Actual" src="images/actual.png">
            <img alt="Difference" src="images/diff.png">
            <img src="images/unknown.png">
            <img alt="remote" src="https://example.com/not-local.png">
            """.trimIndent(),
        )

        val report = ComposeScreenshotReportAdapter.import(module, "debug")!!

        assertTrue(report.experimental)
        assertEquals(4, report.artifacts.size)
        assertEquals(1, report.artifacts.count { it.role == ScreenshotArtifactRole.REFERENCE })
        assertEquals(1, report.artifacts.count { it.role == ScreenshotArtifactRole.ACTUAL })
        assertEquals(1, report.artifacts.count { it.role == ScreenshotArtifactRole.DIFF })
        assertEquals("unknown", report.artifacts.single { it.role == ScreenshotArtifactRole.UNCLASSIFIED }.confidence)
    }

    @Test
    fun `returns null when official report does not exist`() {
        assertNull(ComposeScreenshotReportAdapter.import(Files.createTempDirectory("dak-no-report"), "debug"))
    }
}
