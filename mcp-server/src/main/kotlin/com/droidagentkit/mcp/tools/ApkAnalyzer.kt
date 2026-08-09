package com.droidagentkit.mcp.tools

import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Reports where an APK's size actually goes, and what changed between two of them.
 *
 * "The app got bigger" is one of the most common Android questions and one an agent could not
 * previously answer at all. An APK is a zip, so this reads the central directory with the JDK's own
 * `ZipFile` -- no new dependency, and no need to unpack anything.
 *
 * Both compressed and uncompressed totals are reported because they answer different questions:
 * download size is what the user pays for, installed size is what the device pays for, and a change
 * that moves one and not the other is usually a compression change rather than new code.
 */
object ApkAnalyzer {
    data class CategorySize(
        val category: String,
        val fileCount: Int,
        val compressedBytes: Long,
        val uncompressedBytes: Long,
    )

    data class ApkReport(
        val path: String,
        val totalCompressedBytes: Long,
        val totalUncompressedBytes: Long,
        val fileCount: Int,
        val categories: List<CategorySize>,
        val abis: List<String>,
        val largestEntries: List<Pair<String, Long>>,
    )

    data class CategoryDelta(
        val category: String,
        val compressedDelta: Long,
        val uncompressedDelta: Long,
    )

    data class ApkDiff(
        val base: ApkReport,
        val candidate: ApkReport,
        val totalCompressedDelta: Long,
        val categories: List<CategoryDelta>,
    )

    private const val TOP_ENTRY_COUNT = 15

    fun analyze(apk: Path): ApkReport {
        val categories = linkedMapOf<String, MutableList<Triple<String, Long, Long>>>()
        val abis = sortedSetOf<String>()
        var totalCompressed = 0L
        var totalUncompressed = 0L
        var count = 0
        val entrySizes = mutableListOf<Pair<String, Long>>()

        ZipFile(apk.toFile()).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                // A zip written without sizes reports -1; treating that as 0 keeps totals honest
                // rather than producing a negative that looks like a bug elsewhere.
                val compressed = entry.compressedSize.coerceAtLeast(0)
                val uncompressed = entry.size.coerceAtLeast(0)
                count++
                totalCompressed += compressed
                totalUncompressed += uncompressed
                categories
                    .getOrPut(categoryOf(entry.name)) { mutableListOf() }
                    .add(Triple(entry.name, compressed, uncompressed))
                nativeAbiOf(entry.name)?.let { abis.add(it) }
                entrySizes.add(entry.name to compressed)
            }
        }

        return ApkReport(
            path = apk.toString(),
            totalCompressedBytes = totalCompressed,
            totalUncompressedBytes = totalUncompressed,
            fileCount = count,
            categories =
                categories
                    .map { (name, files) ->
                        CategorySize(
                            category = name,
                            fileCount = files.size,
                            compressedBytes = files.sumOf { it.second },
                            uncompressedBytes = files.sumOf { it.third },
                        )
                    }.sortedByDescending { it.compressedBytes },
            abis = abis.toList(),
            largestEntries = entrySizes.sortedByDescending { it.second }.take(TOP_ENTRY_COUNT),
        )
    }

    fun diff(
        base: Path,
        candidate: Path,
    ): ApkDiff {
        val baseReport = analyze(base)
        val candidateReport = analyze(candidate)
        val names = (baseReport.categories.map { it.category } + candidateReport.categories.map { it.category }).distinct()
        val deltas =
            names
                .map { name ->
                    val b = baseReport.categories.firstOrNull { it.category == name }
                    val c = candidateReport.categories.firstOrNull { it.category == name }
                    CategoryDelta(
                        category = name,
                        compressedDelta = (c?.compressedBytes ?: 0) - (b?.compressedBytes ?: 0),
                        uncompressedDelta = (c?.uncompressedBytes ?: 0) - (b?.uncompressedBytes ?: 0),
                    )
                }.sortedByDescending { kotlin.math.abs(it.compressedDelta) }
        return ApkDiff(
            base = baseReport,
            candidate = candidateReport,
            totalCompressedDelta = candidateReport.totalCompressedBytes - baseReport.totalCompressedBytes,
            categories = deltas,
        )
    }

    /** Mirrors how Android Studio's APK Analyzer groups entries, so numbers are comparable. */
    internal fun categoryOf(name: String): String =
        when {
            name.endsWith(".dex") -> "dex"
            name.startsWith("lib/") -> "native"
            name.startsWith("res/") -> "res"
            name == "resources.arsc" -> "resources.arsc"
            name.startsWith("assets/") -> "assets"
            name.startsWith("META-INF/") -> "META-INF"
            name == "AndroidManifest.xml" -> "manifest"
            else -> "other"
        }

    /** `lib/arm64-v8a/libfoo.so` -> `arm64-v8a`. */
    internal fun nativeAbiOf(name: String): String? {
        if (!name.startsWith("lib/")) return null
        val abi = name.removePrefix("lib/").substringBefore('/')
        return abi.takeIf { it.isNotBlank() && it != name }
    }
}
