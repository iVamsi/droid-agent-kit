package com.droidagentkit.perfetto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerfettoCoreTest {
    @Test
    fun `config template renders duration buffer and data sources`() {
        val config = PerfettoCaptureConfig(durationSeconds = 5, dataSources = listOf("linux.sched"), bufferSizeKb = 4096)
        val rendered = PerfettoConfigTemplate.render(config)
        assertTrue(rendered.contains("duration_ms: 5000"))
        assertTrue(rendered.contains("size_kb: 4096"))
        assertTrue(rendered.contains("name: \"linux.sched\""))
        assertTrue(rendered.contains("name: \"linux.metadata\""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `capture config rejects out of range duration`() {
        PerfettoCaptureConfig(durationSeconds = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `capture config rejects empty data sources`() {
        PerfettoCaptureConfig(dataSources = emptyList())
    }

    @Test
    fun `parser handles columns and rows arrays`() {
        val output = """{"columns":["process_name","cpu_seconds"],"rows":[["com.example",1.5],["system",0.2]]}"""
        val result = TraceProcessorOutputParser.parse(output)
        assertTrue(result is TraceProcessorQueryResult.Rows)
        val rows = (result as TraceProcessorQueryResult.Rows).rows
        assertEquals(2, rows.size)
        assertEquals("com.example", rows[0]["process_name"])
        assertEquals(1.5, rows[0]["cpu_seconds"])
    }

    @Test
    fun `parser handles records array`() {
        val output = """{"columns":["a","b"],"records":[{"a":1,"b":2}]}"""
        val result = TraceProcessorOutputParser.parse(output)
        assertTrue(result is TraceProcessorQueryResult.Rows)
        val rows = (result as TraceProcessorQueryResult.Rows).rows
        assertEquals(1, rows.size)
        assertEquals(1L, rows[0]["a"])
    }

    @Test
    fun `parser handles json lines`() {
        val output = "{\"name\":\"x\",\"dur\":10}\n{\"name\":\"y\",\"dur\":20}"
        val result = TraceProcessorOutputParser.parse(output)
        assertTrue(result is TraceProcessorQueryResult.Rows)
        val rows = (result as TraceProcessorQueryResult.Rows).rows
        assertEquals(2, rows.size)
        assertEquals("x", rows[0]["name"])
    }

    @Test
    fun `parser surfaces error envelopes as error results`() {
        val output = """{"error":"table not found"}"""
        val result = TraceProcessorOutputParser.parse(output)
        assertTrue(result is TraceProcessorQueryResult.Error)
    }

    @Test
    fun `parser treats empty output as error`() {
        val result = TraceProcessorOutputParser.parse("   ")
        assertTrue(result is TraceProcessorQueryResult.Error)
    }

    @Test
    fun `parser handles nested objects and escaped strings`() {
        val output =
            """{"columns":["name","meta"],"rows":[["a\"b",{"nested":true,"n":2}]]}"""
        val result = TraceProcessorOutputParser.parse(output)
        assertTrue(result is TraceProcessorQueryResult.Rows)
        val row = (result as TraceProcessorQueryResult.Rows).rows.single()
        assertEquals("a\"b", row["name"])
        @Suppress("UNCHECKED_CAST")
        val meta = row["meta"] as Map<String, Any?>
        assertEquals(true, meta["nested"])
        assertEquals(2L, meta["n"])
    }

    @Test(expected = IllegalArgumentException::class)
    fun `capture config rejects buffer size below minimum`() {
        PerfettoCaptureConfig(durationSeconds = 1, bufferSizeKb = 0)
    }

    @Test
    fun `analysis build for rows yields medium confidence and evidence`() {
        val rows = listOf(mapOf("process_name" to "com.example", "cpu_seconds" to 1.5))
        val result = PerfettoAnalysis.build(PerfettoAnalysisType.CPU_UTILIZATION, TraceProcessorQueryResult.Rows(rows))
        assertEquals(PerfettoConfidence.MEDIUM, result.confidence)
        assertEquals(1, result.rowCount)
        assertTrue(result.evidence.isNotEmpty())
        assertTrue(result.summary.contains("com.example"))
    }

    @Test
    fun `analysis build for empty rows yields insufficient confidence`() {
        val result = PerfettoAnalysis.build(PerfettoAnalysisType.FRAME_JANK, TraceProcessorQueryResult.Rows(emptyList()))
        assertEquals(PerfettoConfidence.INSUFFICIENT, result.confidence)
        assertTrue(result.warnings.contains("no-rows"))
    }

    @Test
    fun `analysis build for error yields data unavailable warning`() {
        val result = PerfettoAnalysis.build(PerfettoAnalysisType.BINDER_LATENCY, TraceProcessorQueryResult.Error("boom"))
        assertEquals(PerfettoConfidence.INSUFFICIENT, result.confidence)
        assertTrue(result.warnings.contains("data-unavailable"))
    }

    @Test
    fun `report correlation confidence rises with the number of present analyses`() {
        val results =
            mapOf<PerfettoAnalysisType, TraceProcessorQueryResult>(
                PerfettoAnalysisType.MAIN_THREAD_SLICES to TraceProcessorQueryResult.Rows(listOf(mapOf("dur_ns" to 50_000_000L))),
                PerfettoAnalysisType.BINDER_LATENCY to TraceProcessorQueryResult.Rows(listOf(mapOf("avg_dur_ns" to 20_000_000L))),
                PerfettoAnalysisType.CONTENTION to TraceProcessorQueryResult.Rows(listOf(mapOf("waits" to 3))),
            )
        val report = PerfettoAnalysis.report(results)
        assertEquals(PerfettoConfidence.HIGH, report.confidence)
        assertTrue(report.correlation.contains("suggestive, not a guaranteed root cause"))
        assertTrue(report.correlation.contains("memory-leak detection is out of scope"))
    }

    @Test
    fun `report correlation is insufficient when no analyses have rows`() {
        val results =
            mapOf<PerfettoAnalysisType, TraceProcessorQueryResult>(
                PerfettoAnalysisType.MAIN_THREAD_SLICES to TraceProcessorQueryResult.Rows(emptyList()),
                PerfettoAnalysisType.BINDER_LATENCY to TraceProcessorQueryResult.Error("missing"),
            )
        val report = PerfettoAnalysis.report(results)
        assertEquals(PerfettoConfidence.INSUFFICIENT, report.confidence)
    }

    @Test
    fun `compose recomposition names the hottest composable and its count`() {
        val rows =
            listOf(
                mapOf("composable_name" to "ProductRow", "recomposition_count" to 412L),
                mapOf("composable_name" to "PriceLabel", "recomposition_count" to 96L),
            )

        val result = PerfettoAnalysis.build(PerfettoAnalysisType.COMPOSE_RECOMPOSITION, TraceProcessorQueryResult.Rows(rows))

        assertEquals(PerfettoConfidence.MEDIUM, result.confidence)
        assertEquals(2, result.rowCount)
        assertTrue("summary should name the hottest composable: ${result.summary}", result.summary.contains("ProductRow"))
        assertTrue("summary should carry the count: ${result.summary}", result.summary.contains("412"))
    }

    @Test
    fun `compose recomposition reports insufficient when compose tracing was not captured`() {
        // Compose tracing is opt-in on the app side, so an absent section table is the common case
        // and has to read as "not measured" rather than as "zero recompositions".
        val result = PerfettoAnalysis.build(PerfettoAnalysisType.COMPOSE_RECOMPOSITION, TraceProcessorQueryResult.Rows(emptyList()))

        assertEquals(PerfettoConfidence.INSUFFICIENT, result.confidence)
        assertTrue(result.warnings.contains("no-rows"))
    }

    @Test
    fun `sql resources load for every analysis type`() {
        PerfettoAnalysisType.entries.forEach { type ->
            val sql = PerfettoSql.load(type)
            assertTrue("SQL for $type should be non-empty", sql.isNotBlank())
        }
    }

    @Test
    fun `unparseable json-lines are counted rather than dropped in silence`() {
        // A query whose output is mostly garbage used to return a short row list that looked like a
        // real answer. The count is what lets a caller tell "two matching slices" apart from
        // "we could only read two".
        val text =
            """
            {"name":"good","dur":1}
            not json at all
            {"name":"also good","dur":2}
            {broken
            """.trimIndent()

        val result = TraceProcessorOutputParser.parseRowsForTest(text)

        val rows = result as TraceProcessorQueryResult.Rows
        assertEquals(2, rows.rows.size)
        assertEquals(2, rows.skippedLines)
    }

    @Test
    fun `clean json-lines report nothing skipped`() {
        val text = """{"name":"a","dur":1}""" + "\n" + """{"name":"b","dur":2}"""

        val rows = TraceProcessorOutputParser.parseRowsForTest(text) as TraceProcessorQueryResult.Rows

        assertEquals(2, rows.rows.size)
        assertEquals(0, rows.skippedLines)
    }
}
