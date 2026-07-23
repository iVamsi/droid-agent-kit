package com.droidagentkit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ArtifactSafetyTest {
    @Test
    fun `writeBytes records size sha256 sensitivity and opaque id`() {
        val dir = Files.createTempDirectory("dak-art")
        val writer = ArtifactWriter(dir)
        val bytes = "hello world".toByteArray(Charsets.UTF_8)
        val ref = writer.writeBytes("shot.png", bytes, ArtifactType.SCREENSHOT, "screen", ArtifactSensitivity.SENSITIVE)
        assertEquals(bytes.size.toLong(), ref.sizeBytes)
        assertEquals(64, ref.sha256.length)
        assertEquals(ArtifactSensitivity.SENSITIVE, ref.sensitivity)
        assertTrue(ref.opaqueId.startsWith("art_"))
        assertEquals("image/png", ref.mimeType)
        assertEquals(
            "shot.png",
            java.nio.file.Path
                .of(ref.path)
                .fileName
                .toString(),
        )
    }

    @Test
    fun `writeText defaults to public sensitivity and computes hash`() {
        val dir = Files.createTempDirectory("dak-art-text")
        val writer = ArtifactWriter(dir)
        val ref = writer.writeText("log.txt", "Authorization: Bearer abc")
        assertEquals(ArtifactSensitivity.PUBLIC, ref.sensitivity)
        assertEquals(64, ref.sha256.length)
        assertEquals("text/plain", ref.mimeType)
    }

    @Test
    fun `writeStream truncates large output at cap and reports truncated`() {
        val dir = Files.createTempDirectory("dak-art-stream")
        val writer = ArtifactWriter(dir)
        val big = ByteArray(200)
        val result =
            writer.writeStream("trace.bin", ArtifactType.PERFETTO_TRACE, "trace", ArtifactSensitivity.SENSITIVE, maxBytes = 100) { sink ->
                sink.write(big)
            }
        assertTrue(result.truncated)
        assertEquals(100, result.artifact.sizeBytes)
        assertEquals(64, result.artifact.sha256.length)
        assertEquals("application/octet-stream", result.artifact.mimeType)
    }

    @Test
    fun `writeStream writes exact bytes when under cap`() {
        val dir = Files.createTempDirectory("dak-art-stream-exact")
        val writer = ArtifactWriter(dir)
        val payload = "tiny".toByteArray(Charsets.UTF_8)
        val result =
            writer.writeStream("out.db", ArtifactType.SQLITE_SNAPSHOT, "db", maxBytes = 1024) { sink ->
                sink.write(payload)
            }
        assertEquals(false, result.truncated)
        assertEquals(payload.size.toLong(), result.artifact.sizeBytes)
        assertEquals("application/vnd.sqlite3", result.artifact.mimeType)
    }
}
