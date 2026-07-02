# Visual Regression End-to-End Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make DroidAgentKit's "Compose Visual Regression Kit" actually work end-to-end — real capture
persistence, real golden-diffing, real reports — per `docs/superpowers/specs/2026-07-02-visual-regression-e2e-design.md`.

**Architecture:** `DroidAgentVisualRule.captureCompose` becomes capture-only (renderer supplies PNG
bytes, the rule persists them). A new `VisualCaptureEngine` object in `visuals-core` owns all
diff/report/golden-update logic, reused by both the Gradle plugin's tasks and the CLI's `visuals`
command — one implementation, two callers.

**Tech Stack:** Kotlin/JVM, `java.awt.image`/`javax.imageio` (JDK built-in, already used by
`PngDiffEngine`), JUnit 4.13.2, Gradle `ProjectBuilder` test fixtures (already used by
`DroidAgentVisualsPluginTest`).

## Global Constraints

- No new third-party dependencies in `visuals-core`, `visuals-android-test`, or `visuals-gradle-plugin` —
  pure JVM only.
- No network calls anywhere in this plan's code.
- Real rendering stays the target project's responsibility — droid-agent-kit never depends on
  Paparazzi, Robolectric, or the Android SDK.
- Scope is pixel-diff only this round — the other 7 `VisualFindingCategory` values stay undetected;
  do not add accessibility-heuristic detection logic.
- No mocks in tests — `Files.createTempDirectory` fixtures and real PNGs written via
  `BufferedImage`/`ImageIO`, matching `VisualDiffTest`'s existing convention.
- `DroidAgentVisualRule(outputDir: Path = Path.of("build/droidagentkit/visuals"))` — this exact default
  must match the Gradle extension's own `outputDir` convention.
- Goldens default to `src/test/resources/droidagentkit/goldens/<caseName>/<envKey>.png` inside the
  target project, where `envKey = "${device}_${theme}_${fontScale}_${locale}"`.

---

## File Map

| File | Change |
|---|---|
| `visuals-core/.../Models.kt` | Add `VisualCapture` data class (moved here from visuals-android-test) |
| `visuals-core/.../VisualCaptureEngine.kt` | New — `persistCapture` (Task 1), then `generateReport`/`updateGoldens`/`renderMarkdown` (Task 2) |
| `visuals-android-test/.../DroidAgentVisualRule.kt` | Rewritten — `captureCompose` takes `() -> ByteArray`, delegates to `VisualCaptureEngine`; `VisualCapture<T>` removed (moved to visuals-core) |
| `visuals-android-test/src/test/.../DroidAgentVisualRuleTest.kt` | Rewritten for the new API |
| `visuals-core/src/test/.../VisualCaptureEngineTest.kt` | New |
| `visuals-gradle-plugin/.../DroidAgentVisualsPlugin.kt` | `goldensDir` extension property; both tasks call `VisualCaptureEngine`; report task throws on `failOnChangedGoldens` |
| `visuals-gradle-plugin/src/test/.../DroidAgentVisualsPluginTest.kt` | Extended |
| `cli/.../DroidAgentMain.kt` | `visuals()` calls `VisualCaptureEngine` directly for `report`/`update-goldens` actions |
| `cli/src/test/.../DroidAgentCliIntegrationTest.kt` | Extended |
| `docs/add-compose-visual-reports.md` | Rewritten for the real `() -> ByteArray` contract and `goldensDir` |

---

### Task 1: Capture persistence — `VisualCaptureEngine.persistCapture` + `DroidAgentVisualRule`

**Files:**
- Modify: `visuals-core/src/main/kotlin/com/droidagentkit/visuals/Models.kt`
- Create: `visuals-core/src/main/kotlin/com/droidagentkit/visuals/VisualCaptureEngine.kt`
- Create: `visuals-core/src/test/kotlin/com/droidagentkit/visuals/VisualCaptureEngineTest.kt`
- Modify: `visuals-android-test/src/main/kotlin/com/droidagentkit/visuals/android/DroidAgentVisualRule.kt`
- Modify: `visuals-android-test/src/test/kotlin/com/droidagentkit/visuals/android/DroidAgentVisualRuleTest.kt`

**Interfaces:**
- Produces: `data class VisualCapture(val caseName: String, val environment: VisualEnvironment, val pngPath: Path, val semanticsPath: Path, val capturedAt: String)` in `com.droidagentkit.visuals`
- Produces: `object VisualCaptureEngine { fun persistCapture(outputDir: Path, caseName: String, device: String, theme: String, fontScale: Float, locale: String, pngBytes: ByteArray, semanticsDump: String): VisualCapture }` in `com.droidagentkit.visuals` — Task 2 adds more functions to this same object; Task 3/4 call `persistCapture` indirectly via `DroidAgentVisualRule`.
- Consumes: existing `VisualEnvironment` (visuals-core `Models.kt`), existing `com.droidagentkit.core.ArtifactWriter`/`ArtifactType` (toolbox-core, already a `visuals-core` dependency).

- [ ] **Step 1: Write the failing tests for `VisualCaptureEngine.persistCapture`**

Create `visuals-core/src/test/kotlin/com/droidagentkit/visuals/VisualCaptureEngineTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :visuals-core:test --tests "com.droidagentkit.visuals.VisualCaptureEngineTest"`
Expected: FAIL — compile error, `VisualCaptureEngine` does not exist yet.

- [ ] **Step 3: Add `VisualCapture` to `Models.kt`**

In `visuals-core/src/main/kotlin/com/droidagentkit/visuals/Models.kt`, add the import `java.nio.file.Path`
at the top (after the existing `com.droidagentkit.core.ResultStatus` import), and add this data class
directly after `VisualEnvironment`'s closing brace (before `enum class VisualSeverity`):

```kotlin
data class VisualCapture(
    val caseName: String,
    val environment: VisualEnvironment,
    val pngPath: Path,
    val semanticsPath: Path,
    val capturedAt: String,
)
```

- [ ] **Step 4: Implement `VisualCaptureEngine.persistCapture`**

Create `visuals-core/src/main/kotlin/com/droidagentkit/visuals/VisualCaptureEngine.kt`:

```kotlin
package com.droidagentkit.visuals

import com.droidagentkit.core.ArtifactRef
import com.droidagentkit.core.ArtifactType
import com.droidagentkit.core.ArtifactWriter
import com.droidagentkit.core.ResultStatus
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant

object VisualCaptureEngine {
    fun persistCapture(
        outputDir: Path,
        caseName: String,
        device: String,
        theme: String,
        fontScale: Float,
        locale: String,
        pngBytes: ByteArray,
        semanticsDump: String,
    ): VisualCapture {
        val capturesDir = outputDir.resolve("captures")
        val writer = ArtifactWriter(capturesDir)
        val key = envKey(device, theme, fontScale, locale)
        val pngRef = writer.writeBytes(
            "$caseName--$key.png",
            pngBytes,
            ArtifactType.SCREENSHOT,
            "Visual capture: $caseName ($key)",
        )
        val semanticsRef = writer.writeText(
            "$caseName--$key.semantics.txt",
            semanticsDump,
            ArtifactType.OTHER,
            "Semantics dump: $caseName ($key)",
        )
        val capturedAt = Instant.now().toString()
        appendManifestLine(capturesDir, caseName, device, theme, fontScale, locale, Path.of(pngRef.path).fileName.toString(), Path.of(semanticsRef.path).fileName.toString(), capturedAt)
        return VisualCapture(
            caseName = caseName,
            environment = VisualEnvironment(device = device, theme = theme, fontScale = fontScale, locale = locale),
            pngPath = Path.of(pngRef.path),
            semanticsPath = Path.of(semanticsRef.path),
            capturedAt = capturedAt,
        )
    }

    private fun envKey(device: String, theme: String, fontScale: Float, locale: String): String =
        "${device}_${theme}_${fontScale}_${locale}"

    private fun appendManifestLine(
        capturesDir: Path,
        caseName: String,
        device: String,
        theme: String,
        fontScale: Float,
        locale: String,
        pngFile: String,
        semanticsFile: String,
        capturedAt: String,
    ) {
        val manifest = capturesDir.resolve("manifest.tsv")
        val line = listOf(caseName, device, theme, fontScale.toString(), locale, pngFile, semanticsFile, capturedAt).joinToString("\t")
        Files.write(manifest, (line + "\n").toByteArray(), StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }
}
```

Note: `ArtifactRef`, `ResultStatus`, and `StandardCopyOption` are imported now even though only
`persistCapture` uses `ArtifactWriter`/`ArtifactType` at this point — they're needed by `generateReport`/
`updateGoldens` added in Task 2, in this same file. Leaving them out now would just mean adding them
back in Task 2; importing them now avoids that churn.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :visuals-core:test --tests "com.droidagentkit.visuals.VisualCaptureEngineTest"`
Expected: PASS, 2/2 tests green.

- [ ] **Step 6: Commit**

```bash
git add visuals-core/src/main/kotlin/com/droidagentkit/visuals/Models.kt visuals-core/src/main/kotlin/com/droidagentkit/visuals/VisualCaptureEngine.kt visuals-core/src/test/kotlin/com/droidagentkit/visuals/VisualCaptureEngineTest.kt
git commit -m "feat(visuals): add VisualCaptureEngine.persistCapture and VisualCapture model"
```

- [ ] **Step 7: Write the failing test for the rewritten `DroidAgentVisualRule`**

Replace the full contents of
`visuals-android-test/src/test/kotlin/com/droidagentkit/visuals/android/DroidAgentVisualRuleTest.kt`:

```kotlin
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
```

This replaces the old test (which called `captureCompose { "rendered" }` and asserted on a
`renderedValue` field that no longer exists after this task's API change).

- [ ] **Step 8: Run the test to verify it fails**

Run: `./gradlew :visuals-android-test:test --tests "com.droidagentkit.visuals.android.DroidAgentVisualRuleTest"`
Expected: FAIL — compile error, `DroidAgentVisualRule`'s constructor doesn't take a `Path` yet and
`captureCompose`'s render lambda doesn't return `ByteArray` yet.

- [ ] **Step 9: Rewrite `DroidAgentVisualRule`**

Replace the full contents of
`visuals-android-test/src/main/kotlin/com/droidagentkit/visuals/android/DroidAgentVisualRule.kt`:

```kotlin
package com.droidagentkit.visuals.android

import com.droidagentkit.visuals.VisualCapture
import com.droidagentkit.visuals.VisualCaptureEngine
import java.nio.file.Path

data class VisualMatrix(
    val devices: List<String>,
    val themes: List<String>,
    val fontScales: List<Float>,
    val locales: List<String>,
) {
    companion object {
        fun standard() = VisualMatrix(
            devices = listOf("phone_412x915"),
            themes = listOf("light"),
            fontScales = listOf(1.0f),
            locales = listOf("en"),
        )
    }
}

data class CaptureEnvironment(
    val device: String,
    val theme: String,
    val fontScale: Float,
    val locale: String,
)

class DroidAgentVisualRule(private val outputDir: Path = Path.of("build/droidagentkit/visuals")) {
    fun captureCompose(
        name: String,
        matrix: VisualMatrix = VisualMatrix.standard(),
        semantics: List<String> = emptyList(),
        render: () -> ByteArray,
    ): VisualCapture {
        val environment = CaptureEnvironment(
            device = matrix.devices.firstOrNull() ?: "phone_412x915",
            theme = matrix.themes.firstOrNull() ?: "light",
            fontScale = matrix.fontScales.firstOrNull() ?: 1.0f,
            locale = matrix.locales.firstOrNull() ?: "en",
        )
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
}
```

`VisualCapture<T>` is removed from this file entirely (it now lives in `visuals-core`, returned directly
by `VisualCaptureEngine.persistCapture`). `VisualMatrix` and `CaptureEnvironment` are unchanged from
before.

- [ ] **Step 10: Run the test to verify it passes**

Run: `./gradlew :visuals-android-test:test --tests "com.droidagentkit.visuals.android.DroidAgentVisualRuleTest"`
Expected: PASS, 1/1 test green.

- [ ] **Step 11: Run both modules' full test suites**

Run: `./gradlew :visuals-core:test :visuals-android-test:test`
Expected: PASS, all tests green.

- [ ] **Step 12: Commit**

```bash
git add visuals-android-test/src/main/kotlin/com/droidagentkit/visuals/android/DroidAgentVisualRule.kt visuals-android-test/src/test/kotlin/com/droidagentkit/visuals/android/DroidAgentVisualRuleTest.kt
git commit -m "feat(visuals): make DroidAgentVisualRule persist real PNG captures"
```

---

### Task 2: Diffing and reporting — `generateReport`, `updateGoldens`, `renderMarkdown`

**Files:**
- Modify: `visuals-core/src/main/kotlin/com/droidagentkit/visuals/VisualCaptureEngine.kt`
- Modify: `visuals-core/src/test/kotlin/com/droidagentkit/visuals/VisualCaptureEngineTest.kt`

**Interfaces:**
- Produces: `fun generateReport(outputDir: Path, goldensDir: Path, tolerance: VisualTolerance): VisualReport`, `fun updateGoldens(outputDir: Path, goldensDir: Path): List<Path>`, `fun renderMarkdown(report: VisualReport, packageName: String = "unknown"): String` — all on `VisualCaptureEngine`. Task 3 (Gradle plugin) and Task 4 (CLI) call these three directly.
- Consumes (from Task 1): `VisualCaptureEngine.persistCapture` (used to set up test fixtures), `VisualCapture`.
- Consumes (pre-existing): `PngDiffEngine().compare(baseline, candidate, diffOutput, tolerance): PngDiffResult`, `VisualReportBuilder().build(cases: List<VisualCaseResult>): VisualReport`, `VisualCaseResult`, `VisualFinding`, `VisualFindingCategory`, `VisualSeverity`, `VisualTolerance` (all in `com.droidagentkit.visuals`, same package, no import needed).

- [ ] **Step 1: Write the failing tests**

Append these test methods to `visuals-core/src/test/kotlin/com/droidagentkit/visuals/VisualCaptureEngineTest.kt`
(before the closing `}` of the class), and add the needed imports at the top of the file:

```kotlin
import com.droidagentkit.core.ResultStatus
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
```

```kotlin
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

        val report = VisualCaptureEngine.generateReport(outputDir, goldensDir, VisualTolerance(maxChangedPixelPercent = 0.0, maxColorDistance = 0))

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
        assertTrue(report.findings.single().title.contains("No golden image yet"))
    }

    @Test
    fun `generateReport flags dimension mismatch as a finding not a crash`() {
        val outputDir = Files.createTempDirectory("dak-report-dims")
        val goldensDir = Files.createTempDirectory("dak-goldens-dims")
        VisualCaptureEngine.persistCapture(outputDir, "home_screen", "phone_412x915", "light", 1.0f, "en", solidColorPng(Color.WHITE, size = 20), "")
        val goldenDir = goldensDir.resolve("home_screen")
        Files.createDirectories(goldenDir)
        Files.write(goldenDir.resolve("phone_412x915_light_1.0_en.png"), solidColorPng(Color.WHITE, size = 10))

        val report = VisualCaptureEngine.generateReport(outputDir, goldensDir, VisualTolerance())

        assertEquals(ResultStatus.FAILED, report.status)
        assertTrue(report.findings.single().title.contains("dimensions changed"))
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
    fun `renderMarkdown includes case name package name and status`() {
        val outputDir = Files.createTempDirectory("dak-markdown")
        val goldensDir = Files.createTempDirectory("dak-markdown-goldens")
        VisualCaptureEngine.persistCapture(outputDir, "home_screen", "phone_412x915", "light", 1.0f, "en", solidColorPng(Color.WHITE), "")

        val report = VisualCaptureEngine.generateReport(outputDir, goldensDir, VisualTolerance())
        val markdown = VisualCaptureEngine.renderMarkdown(report, "com.example.app")

        assertTrue(markdown.contains("home_screen"))
        assertTrue(markdown.contains("com.example.app"))
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

    private fun solidColorPngWithChangedPixel(fill: Color, changed: Color, size: Int = 10): ByteArray {
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
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :visuals-core:test --tests "com.droidagentkit.visuals.VisualCaptureEngineTest"`
Expected: FAIL — compile error, `generateReport`/`updateGoldens`/`renderMarkdown` don't exist yet.

- [ ] **Step 3: Implement `generateReport`, `updateGoldens`, `renderMarkdown`**

Append these members inside the `VisualCaptureEngine` object in
`visuals-core/src/main/kotlin/com/droidagentkit/visuals/VisualCaptureEngine.kt` (after
`persistCapture`, before the closing `}` of the object):

```kotlin
    fun generateReport(outputDir: Path, goldensDir: Path, tolerance: VisualTolerance): VisualReport {
        val capturesDir = outputDir.resolve("captures")
        val manifest = capturesDir.resolve("manifest.tsv")
        val entries = if (Files.exists(manifest)) Files.readAllLines(manifest).mapNotNull(::parseManifestLine) else emptyList()
        val cases = entries.map { entry -> buildCaseResult(entry, capturesDir, goldensDir, tolerance, outputDir) }
        return VisualReportBuilder().build(cases)
    }

    fun updateGoldens(outputDir: Path, goldensDir: Path): List<Path> {
        val capturesDir = outputDir.resolve("captures")
        val manifest = capturesDir.resolve("manifest.tsv")
        if (!Files.exists(manifest)) return emptyList()
        val entries = Files.readAllLines(manifest).mapNotNull(::parseManifestLine)
        return entries.map { entry ->
            val key = envKey(entry.device, entry.theme, entry.fontScale, entry.locale)
            val destDir = goldensDir.resolve(entry.caseName)
            Files.createDirectories(destDir)
            val dest = destDir.resolve("$key.png")
            Files.copy(capturesDir.resolve(entry.pngFile), dest, StandardCopyOption.REPLACE_EXISTING)
            dest
        }
    }

    fun renderMarkdown(report: VisualReport, packageName: String = "unknown"): String = buildString {
        appendLine("# DroidAgentKit Visual Report")
        appendLine()
        appendLine("Package: $packageName")
        appendLine("Status: ${report.status.wireName}")
        appendLine()
        if (report.cases.isEmpty()) {
            appendLine("No visual cases were collected. Add DroidAgentVisualRule-based tests to produce case artifacts.")
        } else {
            report.cases.forEach { case ->
                appendLine(
                    "## ${case.caseName} (${case.environment.device}, ${case.environment.theme}, " +
                        "${case.environment.locale}, ${case.environment.fontScale}x) — ${case.status.wireName}",
                )
                case.findings.forEach { finding ->
                    appendLine("- [${finding.severity.wireName}] ${finding.title}: ${finding.likelyCause}")
                }
            }
        }
        appendLine()
        append(report.agentFixPacket.markdown)
    }

    private fun buildCaseResult(entry: ManifestEntry, capturesDir: Path, goldensDir: Path, tolerance: VisualTolerance, outputDir: Path): VisualCaseResult {
        val environment = VisualEnvironment(device = entry.device, theme = entry.theme, fontScale = entry.fontScale, locale = entry.locale)
        val key = envKey(entry.device, entry.theme, entry.fontScale, entry.locale)
        val capturePng = capturesDir.resolve(entry.pngFile)
        val goldenPng = goldensDir.resolve(entry.caseName).resolve("$key.png")

        if (!Files.exists(goldenPng)) {
            return VisualCaseResult(
                caseName = entry.caseName,
                environment = environment,
                status = ResultStatus.PARTIAL,
                findings = listOf(
                    VisualFinding(
                        id = "${entry.caseName}-$key-no-golden",
                        category = VisualFindingCategory.PIXEL_DIFF,
                        severity = VisualSeverity.WARNING,
                        caseName = entry.caseName,
                        title = "No golden image yet for $key",
                        evidence = listOf(ArtifactRef(ArtifactType.SCREENSHOT, capturePng.toString(), "image/png", "Fresh capture, no baseline yet")),
                        likelyCause = "This case/environment has never had droidAgentVisualsUpdateGoldens run for it.",
                        suggestedFixPrompt = "Run droidAgentVisualsUpdateGoldens (or `droidagent visuals update-goldens`) to accept this as the baseline.",
                    ),
                ),
            )
        }

        val diffDir = outputDir.resolve("diffs")
        Files.createDirectories(diffDir)
        val diffFile = diffDir.resolve("${entry.caseName}--$key.png")

        val diffResult = try {
            PngDiffEngine().compare(goldenPng, capturePng, diffFile, tolerance)
        } catch (error: IllegalArgumentException) {
            return VisualCaseResult(
                caseName = entry.caseName,
                environment = environment,
                status = ResultStatus.FAILED,
                findings = listOf(
                    VisualFinding(
                        id = "${entry.caseName}-$key-dimension-mismatch",
                        category = VisualFindingCategory.PIXEL_DIFF,
                        severity = VisualSeverity.ERROR,
                        caseName = entry.caseName,
                        title = "Image dimensions changed for $key",
                        evidence = listOf(ArtifactRef(ArtifactType.SCREENSHOT, capturePng.toString(), "image/png", "Fresh capture")),
                        likelyCause = error.message ?: "Captured image dimensions differ from the golden.",
                        suggestedFixPrompt = "Review the layout change, then run droidAgentVisualsUpdateGoldens if intentional.",
                    ),
                ),
            )
        }

        if (diffResult.passed) {
            return VisualCaseResult(caseName = entry.caseName, environment = environment, status = ResultStatus.SUCCESS, findings = emptyList())
        }
        return VisualCaseResult(
            caseName = entry.caseName,
            environment = environment,
            status = ResultStatus.FAILED,
            findings = listOf(
                VisualFinding(
                    id = "${entry.caseName}-$key-pixel-diff",
                    category = VisualFindingCategory.PIXEL_DIFF,
                    severity = VisualSeverity.ERROR,
                    caseName = entry.caseName,
                    title = "Pixel diff exceeds tolerance for $key",
                    evidence = listOf(ArtifactRef(ArtifactType.IMAGE_DIFF, diffFile.toString(), "image/png", "Pixel diff overlay")),
                    likelyCause = "${"%.2f".format(diffResult.changedPixelPercent)}% of pixels changed (tolerance: ${tolerance.maxChangedPixelPercent}%).",
                    suggestedFixPrompt = "Review the diff image, then run droidAgentVisualsUpdateGoldens if intentional.",
                ),
            ),
        )
    }

    private fun parseManifestLine(line: String): ManifestEntry? {
        val parts = line.split("\t")
        if (parts.size != 8) return null
        val fontScale = parts[3].toFloatOrNull() ?: return null
        return ManifestEntry(parts[0], parts[1], parts[2], fontScale, parts[4], parts[5], parts[6], parts[7])
    }

    private data class ManifestEntry(
        val caseName: String,
        val device: String,
        val theme: String,
        val fontScale: Float,
        val locale: String,
        val pngFile: String,
        val semanticsFile: String,
        val capturedAt: String,
    )
```

Note the `"${"%.2f".format(diffResult.changedPixelPercent)}% of pixels changed..."` construction: the
`.format()` call runs first and produces a plain string like `"5.23"`, which is then interpolated into
the surrounding string template that has its own literal `%` characters — this is NOT the same as
passing `%%`-escaped text through `.format()` again, which would produce a doubled `%%` in the output.
Use this exact form.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :visuals-core:test --tests "com.droidagentkit.visuals.VisualCaptureEngineTest"`
Expected: PASS, 8/8 tests green (2 from Task 1 plus 6 new).

- [ ] **Step 5: Run the full visuals-core suite**

Run: `./gradlew :visuals-core:test`
Expected: PASS, all tests green, including the pre-existing `VisualDiffTest`.

- [ ] **Step 6: Commit**

```bash
git add visuals-core/src/main/kotlin/com/droidagentkit/visuals/VisualCaptureEngine.kt visuals-core/src/test/kotlin/com/droidagentkit/visuals/VisualCaptureEngineTest.kt
git commit -m "feat(visuals): add generateReport, updateGoldens, and renderMarkdown to VisualCaptureEngine"
```

---

### Task 3: Gradle plugin wiring

**Files:**
- Modify: `visuals-gradle-plugin/src/main/kotlin/com/droidagentkit/visuals/gradle/DroidAgentVisualsPlugin.kt`
- Modify: `visuals-gradle-plugin/src/test/kotlin/com/droidagentkit/visuals/gradle/DroidAgentVisualsPluginTest.kt`

**Interfaces:**
- Consumes (from Task 2): `VisualCaptureEngine.generateReport`, `VisualCaptureEngine.updateGoldens`, `VisualCaptureEngine.renderMarkdown`, `VisualTolerance`.
- Produces: `DroidAgentVisualsExtension.goldensDir: DirectoryProperty` — new extension property Task 4's docs update references.

- [ ] **Step 1: Write the failing tests**

Replace the full contents of
`visuals-gradle-plugin/src/test/kotlin/com/droidagentkit/visuals/gradle/DroidAgentVisualsPluginTest.kt`:

```kotlin
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
        assertTrue(project.tasks.getByName("droidAgentVisualsUpdateGoldens").description!!.contains("golden"))
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
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :visuals-gradle-plugin:test --tests "com.droidagentkit.visuals.gradle.DroidAgentVisualsPluginTest"`
Expected: FAIL — compile error, `extension.goldensDir` is unresolved (doesn't exist yet).

- [ ] **Step 3: Wire `VisualCaptureEngine` into the plugin**

Replace the full contents of
`visuals-gradle-plugin/src/main/kotlin/com/droidagentkit/visuals/gradle/DroidAgentVisualsPlugin.kt`:

```kotlin
package com.droidagentkit.visuals.gradle

import com.droidagentkit.core.ResultStatus
import com.droidagentkit.visuals.VisualCaptureEngine
import com.droidagentkit.visuals.VisualTolerance
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import javax.inject.Inject

class DroidAgentVisualsPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("droidAgentVisuals", DroidAgentVisualsExtension::class.java)
        project.tasks.register("droidAgentVisualsReport", DroidAgentVisualsReportTask::class.java) { task ->
            task.group = "verification"
            task.description = "Writes a DroidAgentKit visual report comparing captures against goldens."
            task.outputDir.set(extension.outputDir)
            task.goldensDir.set(extension.goldensDir)
            task.packageName.set(extension.packageName)
            task.maxChangedPixelPercent.set(extension.tolerance.maxChangedPixelPercent)
            task.maxColorDistance.set(extension.tolerance.maxColorDistance)
            task.failOnChangedGoldens.set(extension.failOnChangedGoldens)
        }
        project.tasks.register("droidAgentVisualsUpdateGoldens", DroidAgentVisualsUpdateGoldensTask::class.java) { task ->
            task.group = "verification"
            task.description = "Updates DroidAgentKit visual golden images explicitly."
            task.outputDir.set(extension.outputDir)
            task.goldensDir.set(extension.goldensDir)
        }
    }
}

abstract class DroidAgentVisualsExtension @Inject constructor(project: Project) {
    val outputDir: DirectoryProperty = project.objects.directoryProperty()
        .convention(project.layout.buildDirectory.dir("droidagentkit/visuals"))
    val goldensDir: DirectoryProperty = project.objects.directoryProperty()
        .convention(project.layout.projectDirectory.dir("src/test/resources/droidagentkit/goldens"))
    val packageName: Property<String> = project.objects.property(String::class.java).convention("")
    val failOnChangedGoldens: Property<Boolean> = project.objects.property(Boolean::class.java).convention(true)
    val failOnAccessibilityWarnings: Property<Boolean> = project.objects.property(Boolean::class.java).convention(false)
    val matrix: VisualMatrixSpec = project.objects.newInstance(VisualMatrixSpec::class.java)
    val tolerance: VisualToleranceSpec = project.objects.newInstance(VisualToleranceSpec::class.java)

    fun matrix(action: VisualMatrixSpec.() -> Unit) {
        matrix.action()
    }

    fun tolerance(action: VisualToleranceSpec.() -> Unit) {
        tolerance.action()
    }
}

abstract class VisualMatrixSpec @Inject constructor(project: Project) {
    val devices: ListProperty<String> = project.objects.listProperty(String::class.java).convention(listOf("phone_412x915"))
    val themes: ListProperty<String> = project.objects.listProperty(String::class.java).convention(listOf("light", "dark"))
    val fontScales: ListProperty<Float> = project.objects.listProperty(Float::class.java).convention(listOf(1.0f, 1.3f, 2.0f))
    val locales: ListProperty<String> = project.objects.listProperty(String::class.java).convention(listOf("en"))
}

abstract class VisualToleranceSpec @Inject constructor(project: Project) {
    val maxChangedPixelPercent: Property<Double> = project.objects.property(Double::class.java).convention(0.10)
    val maxColorDistance: Property<Int> = project.objects.property(Int::class.java).convention(3)
}

abstract class DroidAgentVisualsReportTask : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Internal
    abstract val goldensDir: DirectoryProperty

    @get:Input
    abstract val packageName: Property<String>

    @get:Input
    abstract val maxChangedPixelPercent: Property<Double>

    @get:Input
    abstract val maxColorDistance: Property<Int>

    @get:Input
    abstract val failOnChangedGoldens: Property<Boolean>

    @TaskAction
    fun writeReport() {
        val tolerance = VisualTolerance(maxChangedPixelPercent.get(), maxColorDistance.get())
        val report = VisualCaptureEngine.generateReport(
            outputDir.get().asFile.toPath(),
            goldensDir.get().asFile.toPath(),
            tolerance,
        )
        val file = outputDir.file("visual-report.md").get().asFile
        file.parentFile.mkdirs()
        file.writeText(VisualCaptureEngine.renderMarkdown(report, packageName.orNull ?: "unknown"))
        if (failOnChangedGoldens.get() && report.status == ResultStatus.FAILED) {
            throw GradleException("DroidAgentKit visual regression detected: ${report.findings.size} finding(s). See $file")
        }
    }
}

abstract class DroidAgentVisualsUpdateGoldensTask : DefaultTask() {
    @get:Internal
    abstract val outputDir: DirectoryProperty

    @get:OutputDirectory
    abstract val goldensDir: DirectoryProperty

    @TaskAction
    fun updateGoldens() {
        val updated = VisualCaptureEngine.updateGoldens(
            outputDir.get().asFile.toPath(),
            goldensDir.get().asFile.toPath(),
        )
        logger.lifecycle("DroidAgentKit updated ${updated.size} golden image(s) in ${goldensDir.get().asFile}")
    }
}
```

`goldensDir` is annotated `@Internal` on the report task (read-only reference directory, not meaningfully
trackable by Gradle's up-to-date checking since it's mutated by a different task/tool run entirely) and
`outputDir` is `@Internal` on the update-goldens task for the same reason (it only reads captures from
there) while remaining its declared `@OutputDirectory` on the report task and `goldensDir`'s
`@OutputDirectory` on the update-goldens task, matching where each task actually writes.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :visuals-gradle-plugin:test --tests "com.droidagentkit.visuals.gradle.DroidAgentVisualsPluginTest"`
Expected: PASS, 4/4 tests green.

- [ ] **Step 5: Run the full visuals-gradle-plugin suite**

Run: `./gradlew :visuals-gradle-plugin:test`
Expected: PASS, all tests green.

- [ ] **Step 6: Commit**

```bash
git add visuals-gradle-plugin/src/main/kotlin/com/droidagentkit/visuals/gradle/DroidAgentVisualsPlugin.kt visuals-gradle-plugin/src/test/kotlin/com/droidagentkit/visuals/gradle/DroidAgentVisualsPluginTest.kt
git commit -m "feat(visuals): wire Gradle plugin tasks to VisualCaptureEngine"
```

---

### Task 4: CLI wiring and documentation

**Files:**
- Modify: `cli/src/main/kotlin/com/droidagentkit/cli/DroidAgentMain.kt`
- Modify: `cli/src/test/kotlin/com/droidagentkit/cli/DroidAgentCliIntegrationTest.kt`
- Modify: `docs/add-compose-visual-reports.md`

**Interfaces:**
- Consumes (from Task 2): `VisualCaptureEngine.generateReport`, `VisualCaptureEngine.updateGoldens`, `VisualCaptureEngine.renderMarkdown`, `VisualTolerance`.

- [ ] **Step 1: Write the failing tests**

Append these test methods to `cli/src/test/kotlin/com/droidagentkit/cli/DroidAgentCliIntegrationTest.kt`
(before the closing `}` of the class), and add these imports at the top of the file:

```kotlin
import com.droidagentkit.visuals.VisualCaptureEngine
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
```

```kotlin
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
```

Note the existing `assertEquals`/`Files` imports already present in this file cover what these new tests
need beyond the four added above.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :cli:test --tests "com.droidagentkit.cli.DroidAgentCliIntegrationTest"`
Expected: FAIL — the current `visuals()` handler always writes a placeholder and returns 0 regardless of
action, so the regression-exit-code and update-goldens assertions fail.

- [ ] **Step 3: Rewrite `DroidAgentMain.visuals()`**

In `cli/src/main/kotlin/com/droidagentkit/cli/DroidAgentMain.kt`, add these imports (after the existing
`com.droidagentkit.mcp.DroidAgentStdioServer` import, before `java.nio.file.Files`):

```kotlin
import com.droidagentkit.core.ResultStatus
import com.droidagentkit.visuals.VisualCaptureEngine
import com.droidagentkit.visuals.VisualTolerance
```

Replace the existing `visuals` function:

```kotlin
    private fun visuals(command: CliCommand.Visuals): Int {
        val project = command.options["project"] ?: "."
        val root = Path.of(project).toAbsolutePath().normalize()
        val outputDir = command.options["output-dir"]
            ?.let { Path.of(it).toAbsolutePath().normalize() }
            ?: root.resolve("build/droidagentkit/visuals")
        val goldensDir = command.options["goldens-dir"]
            ?.let { Path.of(it).toAbsolutePath().normalize() }
            ?: root.resolve("src/test/resources/droidagentkit/goldens")
        return when (command.action) {
            "report" -> {
                val report = VisualCaptureEngine.generateReport(outputDir, goldensDir, VisualTolerance())
                val file = outputDir.resolve("visual-report.md")
                Files.createDirectories(file.parent)
                Files.writeString(file, VisualCaptureEngine.renderMarkdown(report))
                println(file)
                if (report.status == ResultStatus.FAILED) 2 else 0
            }
            "update-goldens" -> {
                val updated = VisualCaptureEngine.updateGoldens(outputDir, goldensDir)
                println("Updated ${updated.size} golden image(s) in $goldensDir")
                0
            }
            else -> {
                System.err.println("Unknown visuals action '${command.action}'. Expected 'report' or 'update-goldens'.")
                1
            }
        }
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :cli:test --tests "com.droidagentkit.cli.DroidAgentCliIntegrationTest"`
Expected: PASS, all tests green (3 new plus the pre-existing invalid-config test).

- [ ] **Step 5: Run the full cli suite**

Run: `./gradlew :cli:test`
Expected: PASS, all tests green — the pre-existing `CliParserTest` tests for the `visuals` command
(which only check parsing, not execution) are unaffected by this handler rewrite.

- [ ] **Step 6: Commit**

```bash
git add cli/src/main/kotlin/com/droidagentkit/cli/DroidAgentMain.kt cli/src/test/kotlin/com/droidagentkit/cli/DroidAgentCliIntegrationTest.kt
git commit -m "feat(cli): wire visuals report/update-goldens actions to VisualCaptureEngine"
```

- [ ] **Step 7: Update the documentation**

Replace the full contents of `docs/add-compose-visual-reports.md`:

```markdown
# Add Compose Visual Reports

Apply the Gradle plugin:

\`\`\`kotlin
plugins {
    id("com.droidagentkit.compose-visuals")
}

droidAgentVisuals {
    outputDir.set(layout.buildDirectory.dir("droidagentkit/visuals"))
    goldensDir.set(layout.projectDirectory.dir("src/test/resources/droidagentkit/goldens"))
    packageName.set("com.example.app")
    failOnChangedGoldens.set(true)
    failOnAccessibilityWarnings.set(false)

    matrix {
        devices.add("phone_412x915")
        themes.addAll("light", "dark")
        fontScales.addAll(1.0f, 1.3f, 2.0f)
        locales.addAll("en", "ar")
    }

    tolerance {
        maxChangedPixelPercent.set(0.10)
        maxColorDistance.set(3)
    }
}
\`\`\`

Use the visual test rule in your JVM test source set, rendering each case to real PNG bytes with a
renderer of your choice — for example [Paparazzi](https://github.com/cashapp/paparazzi), which renders
Compose/View screenshots on the JVM with no emulator required:

\`\`\`kotlin
val rule = DroidAgentVisualRule()

val capture = rule.captureCompose(
    name = "home_screen",
    matrix = VisualMatrix.standard(),
    semantics = listOf("Button: Start"),
) {
    // Return PNG-encoded bytes from whatever renderer you use, e.g. Paparazzi's snapshot output.
    renderHomeScreenToPng()
}
\`\`\`

`captureCompose` persists the PNG and a semantics sidecar under `outputDir`. Run
`./gradlew droidAgentVisualsReport` to diff fresh captures against `goldensDir` and produce a real
markdown report, or `./gradlew droidAgentVisualsUpdateGoldens` to accept the current captures as the new
baseline. The equivalent CLI commands are `droidagent visuals report --project <path>` and
`droidagent visuals update-goldens --project <path>` (both also accept `--output-dir`/`--goldens-dir` to
override the defaults).

droid-agent-kit does not depend on Paparazzi, Robolectric, or the Android SDK itself — any renderer that
produces PNG bytes works. Accessibility-based findings (contrast, touch targets, missing semantics
labels) are not yet detected; only pixel-diff regression is implemented today.
```

- [ ] **Step 8: Run the full project test suite**

Run: `./gradlew test`
Expected: PASS, `BUILD SUCCESSFUL`, no regressions in any module.

- [ ] **Step 9: Commit**

```bash
git add docs/add-compose-visual-reports.md
git commit -m "docs: document the real visual regression capture/report/golden-update pipeline"
```
