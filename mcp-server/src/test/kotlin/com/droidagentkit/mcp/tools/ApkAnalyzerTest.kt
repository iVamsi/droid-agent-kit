package com.droidagentkit.mcp.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ApkAnalyzerTest {
    /** An APK is just a zip, so a synthetic one exercises the real code path with no fixtures. */
    private fun buildApk(
        name: String,
        entries: Map<String, ByteArray>,
    ): Path {
        val file = Files.createTempDirectory("dak-apk").resolve(name)
        ZipOutputStream(Files.newOutputStream(file)).use { zip ->
            entries.forEach { (entryName, bytes) ->
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    private fun bytes(size: Int) = ByteArray(size) { (it % 251).toByte() }

    private val sample =
        mapOf(
            "AndroidManifest.xml" to bytes(1_000),
            "classes.dex" to bytes(50_000),
            "classes2.dex" to bytes(20_000),
            "res/layout/main.xml" to bytes(2_000),
            "resources.arsc" to bytes(8_000),
            "assets/data.json" to bytes(4_000),
            "lib/arm64-v8a/libnative.so" to bytes(30_000),
            "lib/x86_64/libnative.so" to bytes(28_000),
            "META-INF/CERT.SF" to bytes(500),
        )

    @Test
    fun `groups entries the way the APK Analyzer does`() {
        val report = ApkAnalyzer.analyze(buildApk("app.apk", sample))

        val byName = report.categories.associateBy { it.category }
        assertEquals(2, byName.getValue("dex").fileCount)
        assertEquals(70_000, byName.getValue("dex").uncompressedBytes)
        assertEquals(2, byName.getValue("native").fileCount)
        assertEquals(1, byName.getValue("res").fileCount)
        assertEquals(1, byName.getValue("assets").fileCount)
        assertEquals(1, byName.getValue("resources.arsc").fileCount)
        assertEquals(1, byName.getValue("manifest").fileCount)
    }

    @Test
    fun `categories are ordered by what actually costs the most`() {
        val report = ApkAnalyzer.analyze(buildApk("app.apk", sample))

        assertEquals("dex should dominate this apk", "dex", report.categories.first().category)
    }

    @Test
    fun `reports every native ABI present`() {
        val report = ApkAnalyzer.analyze(buildApk("app.apk", sample))

        assertEquals(listOf("arm64-v8a", "x86_64"), report.abis)
    }

    @Test
    fun `counts files and totals both compressed and uncompressed sizes`() {
        val report = ApkAnalyzer.analyze(buildApk("app.apk", sample))

        assertEquals(sample.size, report.fileCount)
        assertEquals(sample.values.sumOf { it.size.toLong() }, report.totalUncompressedBytes)
        // Download size and installed size answer different questions; both must be present.
        assertTrue("compressed total should be populated", report.totalCompressedBytes > 0)
    }

    @Test
    fun `lists the largest entries so the answer is actionable`() {
        val report = ApkAnalyzer.analyze(buildApk("app.apk", sample))

        assertEquals("classes.dex", report.largestEntries.first().first)
    }

    @Test
    fun `diff attributes growth to the category that caused it`() {
        val base = buildApk("base.apk", sample)
        val grown = buildApk("grown.apk", sample + ("classes3.dex" to bytes(40_000)))

        val diff = ApkAnalyzer.diff(base, grown)

        assertTrue("total should grow", diff.totalCompressedDelta > 0)
        val biggest = diff.categories.first()
        assertEquals("dex", biggest.category)
        assertEquals(40_000, biggest.uncompressedDelta)
    }

    @Test
    fun `diff reports a shrink as a negative delta`() {
        val base = buildApk("base.apk", sample)
        val slimmed = buildApk("slim.apk", sample - "lib/x86_64/libnative.so")

        val diff = ApkAnalyzer.diff(base, slimmed)

        assertTrue("total should shrink: ${diff.totalCompressedDelta}", diff.totalCompressedDelta < 0)
        assertEquals(-28_000, diff.categories.first { it.category == "native" }.uncompressedDelta)
    }

    @Test
    fun `an empty apk analyzes to zero rather than failing`() {
        val report = ApkAnalyzer.analyze(buildApk("empty.apk", emptyMap()))

        assertEquals(0, report.fileCount)
        assertEquals(0, report.totalUncompressedBytes)
        assertTrue(report.categories.isEmpty())
    }

    @Test
    fun `categorization covers the shapes an apk actually contains`() {
        assertEquals("dex", ApkAnalyzer.categoryOf("classes.dex"))
        assertEquals("native", ApkAnalyzer.categoryOf("lib/armeabi-v7a/libc++_shared.so"))
        assertEquals("res", ApkAnalyzer.categoryOf("res/drawable/ic.png"))
        assertEquals("resources.arsc", ApkAnalyzer.categoryOf("resources.arsc"))
        assertEquals("other", ApkAnalyzer.categoryOf("kotlin/kotlin.kotlin_builtins"))
        assertEquals("arm64-v8a", ApkAnalyzer.nativeAbiOf("lib/arm64-v8a/libx.so"))
        assertEquals(null, ApkAnalyzer.nativeAbiOf("classes.dex"))
    }
}
