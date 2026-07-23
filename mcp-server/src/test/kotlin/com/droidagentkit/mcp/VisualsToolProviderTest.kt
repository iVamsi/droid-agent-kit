package com.droidagentkit.mcp

import com.droidagentkit.core.Capability
import com.droidagentkit.core.DroidAgentConfig
import com.droidagentkit.core.ToolGroup
import com.droidagentkit.visuals.VisualCaptureEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

class VisualsToolProviderTest {
    private fun config(
        root: Path,
        capabilities: Set<Capability> = setOf(Capability.GOLDEN_UPDATE),
    ): DroidAgentConfig {
        val base = DroidAgentConfig.default()
        return base.copy(safety = base.safety.copy(allowCapabilities = capabilities))
    }

    private fun dispatcher(
        root: Path,
        config: DroidAgentConfig,
    ): DroidAgentMcpDispatcher = DroidAgentMcpDispatcher(config, root, exposedGroups = setOf(ToolGroup.CORE, ToolGroup.VISUALS))

    @Test
    fun `visuals tools are listed only when the group is exposed`() {
        val root = Files.createTempDirectory("dak-visuals-list")
        val dispatcher = dispatcher(root, config(root))

        val names = dispatcher.listTools().map { it.name }
        assertTrue(names.contains("android_visual_diff"))
        assertTrue(names.contains("android_visual_report"))
        assertTrue(names.contains("android_visual_update_goldens"))
    }

    @Test
    fun `visuals tools are hidden when the group is not exposed`() {
        val root = Files.createTempDirectory("dak-visuals-hidden")
        val dispatcher = DroidAgentMcpDispatcher(DroidAgentConfig.default(), root)

        val names = dispatcher.listTools().map { it.name }
        assertTrue(!names.contains("android_visual_diff"))
    }

    @Test
    fun `visual diff compares two pngs and returns a diff artifact`() {
        val root = Files.createTempDirectory("dak-visuals-diff")
        val baseline = root.resolve("baseline.png")
        val candidate = root.resolve("candidate.png")
        Files.write(baseline, solidColorPng(Color.WHITE))
        Files.write(candidate, solidColorPngWithChangedPixel(Color.WHITE, Color.BLACK))

        val result =
            dispatcher(root, config(root)).call(
                "android_visual_diff",
                mapOf("rootPath" to root.toString(), "baselinePath" to baseline.toString(), "candidatePath" to candidate.toString()),
            )

        assertEquals("failed", result["status"])
        assertEquals(false, result["passed"])
        val artifacts = result["artifacts"] as List<*>
        assertTrue(artifacts.any { (it as Map<*, *>)["type"] == "image_diff" })
    }

    @Test
    fun `visual diff passes for identical pngs`() {
        val root = Files.createTempDirectory("dak-visuals-diff-pass")
        val baseline = root.resolve("baseline.png")
        val candidate = root.resolve("candidate.png")
        val png = solidColorPng(Color.WHITE)
        Files.write(baseline, png)
        Files.write(candidate, png)

        val result =
            dispatcher(root, config(root)).call(
                "android_visual_diff",
                mapOf("rootPath" to root.toString(), "baselinePath" to baseline.toString(), "candidatePath" to candidate.toString()),
            )

        assertEquals("success", result["status"])
        assertEquals(true, result["passed"])
    }

    @Test
    fun `visual diff blocks when baseline escapes the project root`() {
        val root = Files.createTempDirectory("dak-visuals-diff-escape")
        val outside = Files.createTempDirectory("dak-visuals-outside").resolve("baseline.png")
        Files.write(outside, solidColorPng(Color.WHITE))

        val result =
            dispatcher(root, config(root)).call(
                "android_visual_diff",
                mapOf(
                    "rootPath" to root.toString(),
                    "baselinePath" to outside.toString(),
                    "candidatePath" to root.resolve("c.png").toString(),
                ),
            )

        assertEquals("blocked", result["status"])
        assertTrue((result["warnings"] as List<*>).contains("missing-baseline"))
    }

    @Test
    fun `visual report diffs captures against goldens and returns findings`() {
        val root = Files.createTempDirectory("dak-visuals-report")
        val capturesDir = root.resolve("build/droidagentkit/visuals")
        val goldensDir = root.resolve("goldens")
        val png = solidColorPng(Color.WHITE)
        VisualCaptureEngine.persistCapture(capturesDir, "home_screen", "phone_412x915", "light", 1.0f, "en", png, "")
        val goldenDir = goldensDir.resolve("home_screen")
        Files.createDirectories(goldenDir)
        Files.write(goldenDir.resolve("phone_412x915_light_1.0_en.png"), png)

        val result =
            dispatcher(root, config(root)).call(
                "android_visual_report",
                mapOf("rootPath" to root.toString(), "goldensDir" to goldensDir.toString()),
            )

        assertEquals("success", result["status"])
        assertEquals(1, result["cases"])
    }

    @Test
    fun `visual report detects missing captures when a matrix is supplied`() {
        val root = Files.createTempDirectory("dak-visuals-report-missing")
        val capturesDir = root.resolve("build/droidagentkit/visuals")
        val goldensDir = root.resolve("goldens")
        VisualCaptureEngine.persistCapture(capturesDir, "home_screen", "phone_412x915", "light", 1.0f, "en", solidColorPng(Color.WHITE), "")

        val result =
            dispatcher(root, config(root)).call(
                "android_visual_report",
                mapOf(
                    "rootPath" to root.toString(),
                    "goldensDir" to goldensDir.toString(),
                    "themes" to "light,dark",
                ),
            )

        assertTrue(
            (result["warnings"] as List<*>).any {
                it.toString().startsWith("missing-capture:home_screen:") &&
                    it.toString().contains("dark")
            },
        )
    }

    @Test
    fun `update goldens is blocked without the golden_update capability`() {
        val root = Files.createTempDirectory("dak-visuals-update-nocap")
        val result =
            dispatcher(root, config(root, capabilities = emptySet())).call(
                "android_visual_update_goldens",
                mapOf("rootPath" to root.toString(), "goldensDir" to root.resolve("goldens").toString(), "confirmDestructive" to true),
            )

        assertEquals("blocked", result["status"])
        assertTrue((result["warnings"] as List<*>).contains("capability-not-enabled"))
    }

    @Test
    fun `update goldens is blocked without confirmDestructive`() {
        val root = Files.createTempDirectory("dak-visuals-update-noconfirm")
        val result =
            dispatcher(root, config(root)).call(
                "android_visual_update_goldens",
                mapOf("rootPath" to root.toString(), "goldensDir" to root.resolve("goldens").toString()),
            )

        assertEquals("blocked", result["status"])
        assertTrue((result["warnings"] as List<*>).contains("destructive-confirmation-required"))
    }

    @Test
    fun `update goldens copies captures into the goldens directory`() {
        val root = Files.createTempDirectory("dak-visuals-update-ok")
        val capturesDir = root.resolve("build/droidagentkit/visuals")
        val goldensDir = root.resolve("goldens")
        val png = solidColorPng(Color.WHITE)
        VisualCaptureEngine.persistCapture(capturesDir, "home_screen", "phone_412x915", "light", 1.0f, "en", png, "")

        val result =
            dispatcher(root, config(root)).call(
                "android_visual_update_goldens",
                mapOf("rootPath" to root.toString(), "goldensDir" to goldensDir.toString(), "confirmDestructive" to true),
            )

        assertEquals("success", result["status"])
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
