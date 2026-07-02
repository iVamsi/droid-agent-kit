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
