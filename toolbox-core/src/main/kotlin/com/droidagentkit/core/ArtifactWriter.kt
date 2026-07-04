package com.droidagentkit.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories

class ArtifactWriter(
    private val outputDir: Path,
) {
    init {
        outputDir.createDirectories()
    }

    fun writeText(
        name: String,
        text: String,
        type: ArtifactType = ArtifactType.LOG,
        description: String = name,
    ): ArtifactRef {
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "-")
        val path = outputDir.resolve(safeName)
        Files.writeString(path, text)
        return ArtifactRef(type, path.toString(), mimeTypeFor(path), description)
    }

    fun writeBytes(
        name: String,
        bytes: ByteArray,
        type: ArtifactType = ArtifactType.SCREENSHOT,
        description: String = name,
    ): ArtifactRef {
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "-")
        val path = outputDir.resolve(safeName)
        Files.write(path, bytes)
        return ArtifactRef(type, path.toString(), mimeTypeFor(path), description)
    }

    private fun mimeTypeFor(path: Path): String =
        when (path.fileName.toString().substringAfterLast('.', "")) {
            "json" -> "application/json"
            "md" -> "text/markdown"
            "png" -> "image/png"
            "zip" -> "application/zip"
            else -> "text/plain"
        }
}
