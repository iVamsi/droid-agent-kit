package com.droidagentkit.mcp.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/**
 * One metric from a macrobenchmark run.
 *
 * androidx Benchmark writes two shapes into `benchmarkData.json`, and they are not
 * interchangeable. `metrics` entries carry `minimum`/`median`/`maximum` over iterations;
 * `sampledMetrics` entries carry `P50`/`P90`/`P95`/`P99` over samples. Both are modelled here with
 * the other side left empty rather than back-filled, because a synthesized median for a sampled
 * metric would be a number the run never produced.
 */
data class MacrobenchmarkMetric(
    val name: String,
    val minimum: Double? = null,
    val median: Double? = null,
    val maximum: Double? = null,
    val percentiles: Map<String, Double> = emptyMap(),
)

data class MacrobenchmarkRun(
    val name: String,
    val className: String,
    val metrics: List<MacrobenchmarkMetric>,
)

data class ParsedMacrobenchmarkResults(
    val benchmarks: List<MacrobenchmarkRun>,
    val resultFiles: List<String>,
    val summary: String,
    val warnings: List<String>,
)

/**
 * Reads androidx Benchmark `*-benchmarkData.json` files out of a project's build outputs.
 *
 * This reports what the run measured and nothing more: no pass/fail verdict, no regression call,
 * no comparison against a baseline that is not present. Deciding whether 275 ms of startup is
 * acceptable needs a history this parser does not have.
 */
object MacrobenchmarkResultParser {
    private const val MAX_FILES = 100
    private const val MAX_DEPTH = 12
    private const val FILE_SUFFIX = "benchmarkData.json"

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(root: Path): ParsedMacrobenchmarkResults {
        val files = findResultFiles(root)
        if (files.isEmpty()) {
            return ParsedMacrobenchmarkResults(
                benchmarks = emptyList(),
                resultFiles = emptyList(),
                summary = "No macrobenchmark results found under $root.",
                warnings = listOf("no-benchmark-results"),
            )
        }
        val benchmarks = mutableListOf<MacrobenchmarkRun>()
        val warnings = mutableListOf<String>()
        files.forEach { file ->
            val parsed = runCatching { parseFile(file) }
            if (parsed.isSuccess) {
                benchmarks += parsed.getOrThrow()
            } else {
                // A truncated or half-written file is otherwise indistinguishable from "no
                // benchmarks ran", which is the wrong thing for a caller to conclude.
                warnings += "unparseable-benchmark-file:${file.name}"
            }
        }
        return ParsedMacrobenchmarkResults(
            benchmarks = benchmarks,
            resultFiles = files.map { it.toString() },
            summary = summarize(benchmarks, files.size),
            warnings = warnings,
        )
    }

    private fun parseFile(file: Path): List<MacrobenchmarkRun> {
        val root = json.parseToJsonElement(Files.readString(file)).jsonObject
        val benchmarks = root["benchmarks"]?.jsonArray ?: return emptyList()
        return benchmarks.map { entry ->
            val obj = entry.jsonObject
            MacrobenchmarkRun(
                name = obj.stringOrEmpty("name"),
                className = obj.stringOrEmpty("className"),
                metrics = iterationMetrics(obj) + sampledMetrics(obj),
            )
        }
    }

    private fun iterationMetrics(benchmark: JsonObject): List<MacrobenchmarkMetric> =
        (benchmark["metrics"] as? JsonObject).orEmpty().mapNotNull { (name, value) ->
            val obj = value as? JsonObject ?: return@mapNotNull null
            val minimum = obj.doubleOrNull("minimum")
            val median = obj.doubleOrNull("median")
            val maximum = obj.doubleOrNull("maximum")
            if (minimum == null && median == null && maximum == null) return@mapNotNull null
            MacrobenchmarkMetric(name = name, minimum = minimum, median = median, maximum = maximum)
        }

    private fun sampledMetrics(benchmark: JsonObject): List<MacrobenchmarkMetric> =
        (benchmark["sampledMetrics"] as? JsonObject).orEmpty().mapNotNull { (name, value) ->
            val obj = value as? JsonObject ?: return@mapNotNull null
            val percentiles =
                PERCENTILE_KEYS.mapNotNull { key -> obj.doubleOrNull(key)?.let { key to it } }.toMap()
            if (percentiles.isEmpty()) return@mapNotNull null
            MacrobenchmarkMetric(name = name, percentiles = percentiles)
        }

    private fun summarize(
        benchmarks: List<MacrobenchmarkRun>,
        fileCount: Int,
    ): String {
        if (benchmarks.isEmpty()) return "Read $fileCount macrobenchmark result file(s) but found no benchmark entries."
        val slowest =
            benchmarks
                .flatMap { run -> run.metrics.mapNotNull { metric -> metric.median?.let { run to (metric.name to it) } } }
                .maxByOrNull { it.second.second }
        val headline =
            if (slowest == null) {
                "no median-bearing metric"
            } else {
                "slowest median = ${slowest.first.name} ${slowest.second.first} ${trimZero(slowest.second.second)}"
            }
        return "${benchmarks.size} benchmark(s) across $fileCount result file(s); $headline. " +
            "Measurements only -- no regression verdict without a baseline to compare against."
    }

    private fun trimZero(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

    private fun findResultFiles(root: Path): List<Path> {
        if (!Files.isDirectory(root)) return emptyList()
        return Files
            .walk(root, MAX_DEPTH)
            .use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && it.name.endsWith(FILE_SUFFIX) }
                    .limit(MAX_FILES.toLong())
                    .toList()
            }.sortedBy { it.toString() }
    }

    private fun JsonObject?.orEmpty(): Map<String, kotlinx.serialization.json.JsonElement> = this ?: emptyMap()

    private fun JsonObject.stringOrEmpty(key: String): String = (this[key] as? JsonPrimitive)?.content.orEmpty()

    private fun JsonObject.doubleOrNull(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

    private val PERCENTILE_KEYS = listOf("P50", "P90", "P95", "P99")
}
