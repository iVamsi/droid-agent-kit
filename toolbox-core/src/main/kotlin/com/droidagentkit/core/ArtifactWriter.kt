package com.droidagentkit.core

import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
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
        sensitivity: ArtifactSensitivity = ArtifactSensitivity.PUBLIC,
    ): ArtifactRef {
        val safeName = sanitize(name)
        val path = outputDir.resolve(safeName)
        Files.writeString(path, text)
        return ref(path, type, description, sensitivity, text.length.toLong(), sha256(text.toByteArray(Charsets.UTF_8)))
    }

    fun writeBytes(
        name: String,
        bytes: ByteArray,
        type: ArtifactType = ArtifactType.SCREENSHOT,
        description: String = name,
        sensitivity: ArtifactSensitivity = ArtifactSensitivity.PUBLIC,
    ): ArtifactRef {
        val safeName = sanitize(name)
        val path = outputDir.resolve(safeName)
        Files.write(path, bytes)
        return ref(path, type, description, sensitivity, bytes.size.toLong(), sha256(bytes))
    }

    fun writeStream(
        name: String,
        type: ArtifactType,
        description: String,
        sensitivity: ArtifactSensitivity = ArtifactSensitivity.PUBLIC,
        maxBytes: Long = MAX_STREAM_BYTES,
        drain: (OutputStream) -> Unit,
    ): StreamResult {
        val safeName = sanitize(name)
        val path = outputDir.resolve(safeName)
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        var truncated = false
        Files.newOutputStream(path).use { out ->
            val buf = ByteArray(BUFFER_SIZE)
            object : OutputStream() {
                override fun write(b: Int) {
                    if (written < maxBytes) {
                        out.write(b)
                        digest.update(b.toByte())
                        written++
                    } else {
                        truncated = true
                    }
                }

                override fun write(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ) {
                    if (written >= maxBytes) {
                        truncated = true
                        return
                    }
                    val remaining = maxBytes - written
                    if (len.toLong() > remaining) {
                        out.write(b, off, remaining.toInt())
                        digest.update(b, off, remaining.toInt())
                        written = maxBytes
                        truncated = true
                    } else {
                        out.write(b, off, len)
                        digest.update(b, off, len)
                        written += len
                    }
                }
            }.use { sink -> drain(sink) }
        }
        val artifact = ref(path, type, description, sensitivity, written, hex(digest.digest()))
        return StreamResult(artifact, truncated)
    }

    fun captureStream(
        name: String,
        input: InputStream,
        type: ArtifactType,
        description: String,
        sensitivity: ArtifactSensitivity = ArtifactSensitivity.SENSITIVE,
        maxBytes: Long = MAX_STREAM_BYTES,
    ): StreamResult =
        writeStream(name, type, description, sensitivity, maxBytes) { sink ->
            val buf = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buf)
                if (read == -1) break
                sink.write(buf, 0, read)
            }
        }

    fun registerExistingFile(
        file: Path,
        type: ArtifactType,
        description: String,
        sensitivity: ArtifactSensitivity = ArtifactSensitivity.PUBLIC,
    ): ArtifactRef {
        val resolved = file.toAbsolutePath().normalize()
        val outputRoot = outputDir.toAbsolutePath().normalize()
        require(resolved.startsWith(outputRoot)) { "Artifact path '$resolved' is outside the configured output directory." }
        val size = Files.size(resolved)
        return ref(resolved, type, description, sensitivity, size, sha256File(resolved))
    }

    private fun sha256File(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buf = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buf)
                if (read == -1) break
                digest.update(buf, 0, read)
            }
        }
        return hex(digest.digest())
    }

    private fun ref(
        path: Path,
        type: ArtifactType,
        description: String,
        sensitivity: ArtifactSensitivity,
        sizeBytes: Long,
        sha256: String,
    ): ArtifactRef {
        val opaqueId = assignOpaqueId(path, sensitivity)
        return ArtifactRef(
            type = type,
            path = path.toString(),
            mimeType = mimeTypeFor(path),
            description = description,
            sizeBytes = sizeBytes,
            sha256 = sha256,
            sensitivity = sensitivity,
            opaqueId = opaqueId,
        )
    }

    private fun assignOpaqueId(
        path: Path,
        sensitivity: ArtifactSensitivity,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(path.toAbsolutePath().toString().toByteArray(Charsets.UTF_8))
        return "art_" + hex(digest.digest()).take(24)
    }

    private fun sanitize(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "-")

    private fun mimeTypeFor(path: Path): String =
        when (path.fileName.toString().substringAfterLast('.', "")) {
            "json" -> "application/json"
            "md" -> "text/markdown"
            "xml" -> "application/xml"
            "png" -> "image/png"
            "zip" -> "application/zip"
            "db" -> "application/vnd.sqlite3"
            "har" -> "application/json"
            "trace", "perfetto-trace", "bin" -> "application/octet-stream"
            else -> "text/plain"
        }

    private fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun hex(bytes: ByteArray): String = bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }

    data class StreamResult(
        val artifact: ArtifactRef,
        val truncated: Boolean,
    )

    private companion object {
        const val BUFFER_SIZE = 8 * 1024
        const val MAX_STREAM_BYTES = 256L * 1024 * 1024
    }
}
