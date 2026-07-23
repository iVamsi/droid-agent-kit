package com.droidagentkit.perfetto

/**
 * The set of Perfetto analyses DroidAgentKit runs. Each maps to a versioned SQL resource under
 * `src/main/resources/sql/`. Queries are defensive: they return empty rows when the required
 * tables/data sources are absent (old Android versions, partial captures), and the parser turns
 * trace_processor errors into `data-unavailable` findings rather than crashing.
 */
enum class PerfettoAnalysisType(
    val sqlResourceName: String,
) {
    CPU_UTILIZATION("cpu_utilization.sql"),
    MAIN_THREAD_SLICES("main_thread_slices.sql"),
    FRAME_JANK("frame_jank.sql"),
    BINDER_LATENCY("binder_latency.sql"),
    CONTENTION("contention.sql"),
}

/** Loads versioned SQL from the module resources. */
object PerfettoSql {
    fun load(type: PerfettoAnalysisType): String {
        val path = "/sql/${type.sqlResourceName}"
        val resource =
            PerfettoSql::class.java.getResourceAsStream(path)
                ?: return errorResult("missing-sql-resource", "Could not load SQL resource $path.")
        return resource.bufferedReader().use { it.readText() }
    }

    private fun errorResult(
        code: String,
        message: String,
    ): String = "-- ERROR [$code]: $message"
}

/** Builds the trace_processor_shell invocation. The provider writes the SQL to a file first. */
object TraceProcessorCommands {
    fun query(
        shellPath: String,
        tracePath: String,
        sqlFilePath: String,
    ): List<String> = listOf(shellPath, tracePath, "--json", "--query-file", sqlFilePath)
}

/** Outcome of a single trace_processor query. */
sealed interface TraceProcessorQueryResult {
    data class Rows(
        val rows: List<Map<String, Any?>>,
    ) : TraceProcessorQueryResult

    data class Error(
        val message: String,
    ) : TraceProcessorQueryResult
}

/**
 * Parses trace_processor_shell `--json --query-file` output into rows. Handles the common shapes:
 * `{"columns":[...], "rows":[[...], ...]}`, `{"columns":[...], "records":[{...}]}`, a JSON array of
 * objects, JSON-lines (one object per line), and `{"error":"..."}` error envelopes.
 */
object TraceProcessorOutputParser {
    fun parse(output: String): TraceProcessorQueryResult {
        val trimmed = output.trim()
        if (trimmed.isEmpty()) return TraceProcessorQueryResult.Error("empty trace_processor output")
        if (trimmed.startsWith("-- ERROR") ||
            trimmed.startsWith("Error") ||
            trimmed.contains("\"error\"", true) &&
            !trimmed.contains("\"columns\"")
        ) {
            return TraceProcessorQueryResult.Error(trimmed.lineSequence().first { it.isNotBlank() }.take(500))
        }
        return try {
            parseRows(trimmed)
        } catch (e: Exception) {
            TraceProcessorQueryResult.Error("unparseable trace_processor output: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun parseRows(text: String): TraceProcessorQueryResult {
        // Try whole-object first; on failure (e.g. JSON-lines, trailing chars) fall back per-line.
        val firstNonWs = text.first { !it.isWhitespace() }
        if (firstNonWs == '{' || firstNonWs == '[') {
            try {
                val value = MiniJson.parse(text)
                val rows = rowsFromValue(value)
                if (rows != null) return TraceProcessorQueryResult.Rows(rows)
                return TraceProcessorQueryResult.Error("no rows in trace_processor output")
            } catch (_: Exception) {
                // Fall through to JSON-lines fallback below.
            }
        }
        // JSON-lines: one object per line.
        val rows = mutableListOf<Map<String, Any?>>()
        text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            try {
                val value = MiniJson.parse(line)
                val lineRows = rowsFromValue(value)
                if (lineRows != null) rows.addAll(lineRows) else rows.addAll(listOf(mapOf("value" to value)))
            } catch (_: Exception) {
                // Skip unparseable line.
            }
        }
        return if (rows.isNotEmpty()) {
            TraceProcessorQueryResult.Rows(rows)
        } else {
            TraceProcessorQueryResult.Error("no rows in trace_processor output")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun rowsFromValue(value: Any?): List<Map<String, Any?>>? =
        when (value) {
            is Map<*, *> -> {
                val error = value["error"]
                if (error is String && value["columns"] == null) return null
                val columns = value["columns"] as? List<*>
                val rowsField = value["rows"] as? List<*>
                val records = value["records"] as? List<*>
                when {
                    columns != null && rowsField != null -> rowsField.map { row -> zipColumns(columns, row) }
                    columns != null && records != null -> records.map { row -> zipColumns(columns, row) }
                    rowsField != null -> rowsField.mapNotNull { it as? Map<String, Any?> }
                    else -> listOf(value as Map<String, Any?>)
                }
            }
            is List<*> -> value.mapNotNull { it as? Map<String, Any?> }
            else -> null
        }

    @Suppress("UNCHECKED_CAST")
    private fun zipColumns(
        columns: List<*>,
        row: Any?,
    ): Map<String, Any?> {
        if (row is Map<*, *>) return row as Map<String, Any?>
        if (row is List<*>) return columns.mapIndexed { i, col -> col.toString() to (row.getOrNull(i) as Any?) }.toMap()
        return mapOf("value" to row)
    }
}

/** Confidence for a single analysis, calibrated from the evidence actually present. */
enum class PerfettoConfidence { HIGH, MEDIUM, LOW, INSUFFICIENT }

data class PerfettoAnalysisResult(
    val analysis: PerfettoAnalysisType,
    val rowCount: Int,
    val rows: List<Map<String, Any?>>,
    val summary: String,
    val confidence: PerfettoConfidence,
    val evidence: List<String>,
    val warnings: List<String> = emptyList(),
)

/**
 * The full Perfetto report. The [correlation] field summarizes ANR/performance evidence across
 * analyses with a confidence level. It NEVER claims a guaranteed root cause and never performs
 * generic memory-leak detection.
 */
data class PerfettoReport(
    val analyses: List<PerfettoAnalysisResult>,
    val correlation: String,
    val confidence: PerfettoConfidence,
    val warnings: List<String>,
)

/** Builds structured analysis results and the cross-analysis correlation from parsed query results. */
object PerfettoAnalysis {
    private const val TOP_N = 10

    fun build(
        type: PerfettoAnalysisType,
        result: TraceProcessorQueryResult,
    ): PerfettoAnalysisResult =
        when (result) {
            is TraceProcessorQueryResult.Error ->
                PerfettoAnalysisResult(
                    analysis = type,
                    rowCount = 0,
                    rows = emptyList(),
                    summary = "${type.name.lowercase().replace('_', ' ')}: data unavailable (${result.message.take(200)}).",
                    confidence = PerfettoConfidence.INSUFFICIENT,
                    evidence = emptyList(),
                    warnings = listOf("data-unavailable"),
                )
            is TraceProcessorQueryResult.Rows -> buildFromRows(type, result.rows)
        }

    private fun buildFromRows(
        type: PerfettoAnalysisType,
        rows: List<Map<String, Any?>>,
    ): PerfettoAnalysisResult {
        if (rows.isEmpty()) {
            return PerfettoAnalysisResult(
                analysis = type,
                rowCount = 0,
                rows = emptyList(),
                summary = "${type.name.lowercase().replace('_', ' ')}: no rows (data source not captured or unsupported Android version).",
                confidence = PerfettoConfidence.INSUFFICIENT,
                evidence = emptyList(),
                warnings = listOf("no-rows"),
            )
        }
        val top = rows.take(TOP_N)
        val evidence = top.map { it.entries.joinToString { (k, v) -> "$k=$v" } }
        val summary = summarize(type, rows, top)
        return PerfettoAnalysisResult(
            analysis = type,
            rowCount = rows.size,
            rows = top,
            summary = summary,
            confidence = PerfettoConfidence.MEDIUM,
            evidence = evidence,
        )
    }

    private fun summarize(
        type: PerfettoAnalysisType,
        rows: List<Map<String, Any?>>,
        top: List<Map<String, Any?>>,
    ): String {
        val label = type.name.lowercase().replace('_', ' ')
        return when (type) {
            PerfettoAnalysisType.CPU_UTILIZATION -> {
                val topProc = top.firstNotNullOfOrNull { it["process_name"]?.toString() } ?: "unknown"
                "$label: ${rows.size} process(es); top by CPU time = $topProc."
            }
            PerfettoAnalysisType.MAIN_THREAD_SLICES -> {
                val longest =
                    top
                        .firstOrNull()
                        ?.get("dur_ns")
                        ?.toString()
                        ?.toLongOrNull() ?: 0L
                "$label: ${rows.size} main-thread slice(s); longest ~${longest / 1_000_000} ms."
            }
            PerfettoAnalysisType.FRAME_JANK -> "$label: ${rows.size} jank frame(s) reported by FrameTimeline."
            PerfettoAnalysisType.BINDER_LATENCY -> {
                val topBinder = top.firstNotNullOfOrNull { it["name"]?.toString() } ?: "unknown"
                "$label: ${rows.size} binder call site(s); slowest = $topBinder."
            }
            PerfettoAnalysisType.CONTENTION -> "$label: ${rows.size} contention wait(s) recorded."
        }
    }

    fun report(results: Map<PerfettoAnalysisType, TraceProcessorQueryResult>): PerfettoReport {
        val analyses = PerfettoAnalysisType.entries.map { build(it, results[it] ?: TraceProcessorQueryResult.Error("not-run")) }
        val mainThread = results[PerfettoAnalysisType.MAIN_THREAD_SLICES] as? TraceProcessorQueryResult.Rows
        val binder = results[PerfettoAnalysisType.BINDER_LATENCY] as? TraceProcessorQueryResult.Rows
        val contention = results[PerfettoAnalysisType.CONTENTION] as? TraceProcessorQueryResult.Rows
        val correlation = buildCorrelation(mainThread, binder, contention)
        val confidence = correlateConfidence(mainThread, binder, contention)
        val warnings = analyses.flatMap { it.warnings }.distinct()
        return PerfettoReport(analyses = analyses, correlation = correlation, confidence = confidence, warnings = warnings)
    }

    private fun buildCorrelation(
        mainThread: TraceProcessorQueryResult.Rows?,
        binder: TraceProcessorQueryResult.Rows?,
        contention: TraceProcessorQueryResult.Rows?,
    ): String {
        val evidence = mutableListOf<String>()
        val longestMs =
            mainThread
                ?.rows
                ?.firstOrNull()
                ?.get("dur_ns")
                ?.toString()
                ?.toLongOrNull()
                ?.div(1_000_000)
        if (longestMs != null && longestMs > 0) evidence.add("longest main-thread slice ~$longestMs ms")
        val slowBinder = binder?.rows?.firstNotNullOfOrNull { it["avg_dur_ns"]?.toString()?.toLongOrNull() }?.div(1_000_000)
        if (slowBinder != null && slowBinder > 0) evidence.add("slowest binder avg ~$slowBinder ms")
        val contentionWaits = contention?.rows?.sumOf { it["waits"]?.toString()?.toIntOrNull() ?: 0 }
        if (contentionWaits != null && contentionWaits > 0) evidence.add("$contentionWaits contention wait(s)")
        if (evidence.isEmpty()) {
            return "No correlated ANR/performance evidence across the captured analyses. " +
                "This is not a guaranteed root-cause statement; memory-leak detection is out of scope."
        }
        return "Correlated evidence: ${evidence.joinToString("; ")}. " +
            "This is suggestive, not a guaranteed root cause; memory-leak detection is out of scope."
    }

    private fun correlateConfidence(
        mainThread: TraceProcessorQueryResult.Rows?,
        binder: TraceProcessorQueryResult.Rows?,
        contention: TraceProcessorQueryResult.Rows?,
    ): PerfettoConfidence {
        var score = 0
        if (mainThread != null && mainThread.rows.isNotEmpty()) score++
        if (binder != null && binder.rows.isNotEmpty()) score++
        if (contention != null && contention.rows.isNotEmpty()) score++
        return when (score) {
            0 -> PerfettoConfidence.INSUFFICIENT
            1 -> PerfettoConfidence.LOW
            2 -> PerfettoConfidence.MEDIUM
            else -> PerfettoConfidence.HIGH
        }
    }
}
