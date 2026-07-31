package com.droidagentkit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ArtifactSafetyTest {
    @Test
    fun `refuses to write an artifact through a planted symlink`() {
        // The output directory lives under the inspected project (build/droidagentkit/), so a
        // repository can commit a link there named like an artifact the writer produces. Following
        // it would hand the repository a write primitive pointing anywhere on disk.
        val dir = Files.createTempDirectory("dak-art-symlink")
        val outsideDir = Files.createTempDirectory("dak-art-outside")
        val victim = outsideDir.resolve("authorized_keys")
        Files.writeString(victim, "original")
        Files.createSymbolicLink(dir.resolve("gradle-run.log"), victim)

        val writer = ArtifactWriter(dir)

        listOf(
            { writer.writeText("gradle-run.log", "pwned") },
            { writer.writeBytes("gradle-run.log", "pwned".toByteArray(Charsets.UTF_8)) },
            { writer.writeStream("gradle-run.log", ArtifactType.LOG, "log") { it.write(1) } },
        ).forEach { attempt ->
            var threw = false
            try {
                attempt()
            } catch (_: Exception) {
                threw = true
            }
            assertTrue("expected the write to be refused", threw)
        }
        assertEquals("original", Files.readString(victim))
    }

    @Test
    fun `registerExistingFile rejects a symlink escaping the output directory`() {
        val dir = Files.createTempDirectory("dak-art-register")
        val outsideDir = Files.createTempDirectory("dak-art-register-outside")
        val secret = outsideDir.resolve("secret.txt")
        Files.writeString(secret, "classified")
        val link = dir.resolve("innocent.log")
        Files.createSymbolicLink(link, secret)

        val writer = ArtifactWriter(dir)

        var threw = false
        try {
            writer.registerExistingFile(link, ArtifactType.LOG, "log")
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue("expected registration to be refused", threw)
    }

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
