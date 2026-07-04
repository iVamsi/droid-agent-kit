package com.droidagentkit.visuals.gradle

import com.droidagentkit.visuals.VisualCaptureEngine
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO

class DroidAgentVisualsPluginTest {
    @Test
    fun `plugin registers expected visual tasks and extension`() {
        val project = ProjectBuilder.builder().build()

        project.plugins.apply(DroidAgentVisualsPlugin::class.java)

        assertNotNull(project.extensions.findByName("droidAgentVisuals"))
        assertNotNull(project.tasks.findByName("droidAgentVisualsReport"))
        assertNotNull(project.tasks.findByName("droidAgentVisualsUpdateGoldens"))
        assertTrue(
            project.tasks
                .getByName("droidAgentVisualsUpdateGoldens")
                .description!!
                .contains("golden"),
        )
    }

    @Test
    fun `report task throws when failOnChangedGoldens is true and a diff exceeds tolerance`() {
        val outputDir = Files.createTempDirectory("dak-plugin-report-fail")
        val goldensDir = Files.createTempDirectory("dak-plugin-goldens-fail")
        VisualCaptureEngine.persistCapture(outputDir, "home_screen", "phone_412x915", "light", 1.0f, "en", solidColorPng(Color.BLACK), "")
        val goldenDir = goldensDir.resolve("home_screen")
        Files.createDirectories(goldenDir)
        Files.write(goldenDir.resolve("phone_412x915_light_1.0_en.png"), solidColorPng(Color.WHITE))

        val project = ProjectBuilder.builder().build()
        project.plugins.apply(DroidAgentVisualsPlugin::class.java)
        val extension = project.extensions.getByType(DroidAgentVisualsExtension::class.java)
        extension.outputDir.set(outputDir.toFile())
        extension.goldensDir.set(goldensDir.toFile())
        val task = project.tasks.getByName("droidAgentVisualsReport") as DroidAgentVisualsReportTask

        val error = assertThrows(GradleException::class.java) { task.writeReport() }

        assertTrue(error.message!!.contains("visual regression"))
    }

    @Test
    fun `report task does not throw when failOnChangedGoldens is false`() {
        val outputDir = Files.createTempDirectory("dak-plugin-report-nofail")
        val goldensDir = Files.createTempDirectory("dak-plugin-goldens-nofail")
        VisualCaptureEngine.persistCapture(outputDir, "home_screen", "phone_412x915", "light", 1.0f, "en", solidColorPng(Color.BLACK), "")
        val goldenDir = goldensDir.resolve("home_screen")
        Files.createDirectories(goldenDir)
        Files.write(goldenDir.resolve("phone_412x915_light_1.0_en.png"), solidColorPng(Color.WHITE))

        val project = ProjectBuilder.builder().build()
        project.plugins.apply(DroidAgentVisualsPlugin::class.java)
        val extension = project.extensions.getByType(DroidAgentVisualsExtension::class.java)
        extension.outputDir.set(outputDir.toFile())
        extension.goldensDir.set(goldensDir.toFile())
        extension.failOnChangedGoldens.set(false)
        val task = project.tasks.getByName("droidAgentVisualsReport") as DroidAgentVisualsReportTask

        task.writeReport()

        assertTrue(Files.exists(outputDir.resolve("visual-report.md")))
    }

    @Test
    fun `update-goldens task copies captures into goldensDir`() {
        val outputDir = Files.createTempDirectory("dak-plugin-update")
        val goldensDir = Files.createTempDirectory("dak-plugin-update-dest")
        val png = solidColorPng(Color.WHITE)
        VisualCaptureEngine.persistCapture(outputDir, "home_screen", "phone_412x915", "light", 1.0f, "en", png, "")

        val project = ProjectBuilder.builder().build()
        project.plugins.apply(DroidAgentVisualsPlugin::class.java)
        val extension = project.extensions.getByType(DroidAgentVisualsExtension::class.java)
        extension.outputDir.set(outputDir.toFile())
        extension.goldensDir.set(goldensDir.toFile())
        val task = project.tasks.getByName("droidAgentVisualsUpdateGoldens") as DroidAgentVisualsUpdateGoldensTask

        task.updateGoldens()

        assertTrue(Files.exists(goldensDir.resolve("home_screen/phone_412x915_light_1.0_en.png")))
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
}
