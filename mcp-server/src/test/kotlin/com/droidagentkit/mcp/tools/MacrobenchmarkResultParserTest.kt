package com.droidagentkit.mcp.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class MacrobenchmarkResultParserTest {
    @Test
    fun `parses startup metrics from a benchmarkData json`() {
        val root = withBenchmarkData("com.example.app-benchmarkData.json", STARTUP_JSON)

        val parsed = MacrobenchmarkResultParser.parse(root)

        assertEquals(1, parsed.benchmarks.size)
        val benchmark = parsed.benchmarks.first()
        assertEquals("startupCompilationNone", benchmark.name)
        assertEquals("com.example.StartupBenchmark", benchmark.className)

        val ttid = benchmark.metrics.single { it.name == "timeToInitialDisplayMs" }
        assertEquals(250.5, ttid.minimum!!, 0.001)
        assertEquals(275.0, ttid.median!!, 0.001)
        assertEquals(310.25, ttid.maximum!!, 0.001)
        assertTrue("startup metrics are not sampled percentiles", ttid.percentiles.isEmpty())
    }

    @Test
    fun `parses sampled frame timing percentiles`() {
        val root = withBenchmarkData("com.example.app-benchmarkData.json", STARTUP_JSON)

        val parsed = MacrobenchmarkResultParser.parse(root)

        val frame =
            parsed.benchmarks
                .first()
                .metrics
                .single { it.name == "frameDurationCpuMs" }
        assertEquals(5.1, frame.percentiles["P50"]!!, 0.001)
        assertEquals(12.3, frame.percentiles["P90"]!!, 0.001)
        assertEquals(15.0, frame.percentiles["P95"]!!, 0.001)
        assertEquals(22.0, frame.percentiles["P99"]!!, 0.001)
        // Sampled metrics carry percentiles rather than min/median/max, and the parser does not
        // synthesize the ones that are absent.
        assertNull(frame.median)
    }

    @Test
    fun `summary names the slowest startup metric across benchmarks`() {
        val root = withBenchmarkData("com.example.app-benchmarkData.json", STARTUP_JSON)

        val parsed = MacrobenchmarkResultParser.parse(root)

        assertTrue("summary should name the benchmark: ${parsed.summary}", parsed.summary.contains("startupCompilationNone"))
        assertTrue("summary should report the file count: ${parsed.summary}", parsed.summary.contains("1"))
    }

    @Test
    fun `returns an empty result when no benchmark output exists`() {
        val root = Files.createTempDirectory("dak-macro-none")

        val parsed = MacrobenchmarkResultParser.parse(root)

        assertTrue(parsed.benchmarks.isEmpty())
        assertTrue(parsed.resultFiles.isEmpty())
        assertTrue(parsed.warnings.contains("no-benchmark-results"))
    }

    @Test
    fun `an unreadable benchmark file is reported rather than dropped in silence`() {
        // The warning separates a truncated or half-written file from "no benchmarks ran".
        val root = withBenchmarkData("broken-benchmarkData.json", "{ this is not json")

        val parsed = MacrobenchmarkResultParser.parse(root)

        assertTrue(parsed.benchmarks.isEmpty())
        assertTrue(parsed.warnings.any { it.startsWith("unparseable-benchmark-file") })
    }

    @Test
    fun `metrics with no recognizable numbers are skipped without failing the parse`() {
        val root =
            withBenchmarkData(
                "com.example.app-benchmarkData.json",
                """
                {
                  "benchmarks": [
                    {
                      "name": "odd",
                      "className": "com.example.Odd",
                      "metrics": { "weird": { "unitless": "n/a" } },
                      "sampledMetrics": {}
                    }
                  ]
                }
                """.trimIndent(),
            )

        val parsed = MacrobenchmarkResultParser.parse(root)

        assertEquals(1, parsed.benchmarks.size)
        assertTrue(
            parsed.benchmarks
                .first()
                .metrics
                .isEmpty(),
        )
    }

    private fun withBenchmarkData(
        fileName: String,
        content: String,
    ): Path {
        val root = Files.createTempDirectory("dak-macro")
        val outputs = root.resolve("build/outputs/connected_android_test_additional_output/benchmark")
        Files.createDirectories(outputs)
        Files.writeString(outputs.resolve(fileName), content)
        return root
    }

    private companion object {
        val STARTUP_JSON =
            """
            {
              "context": { "cpuLocked": false, "sustainedPerformanceModeEnabled": false },
              "benchmarks": [
                {
                  "name": "startupCompilationNone",
                  "className": "com.example.StartupBenchmark",
                  "totalRunTimeNs": 12345678,
                  "warmupIterations": 3,
                  "repeatIterations": 5,
                  "metrics": {
                    "timeToInitialDisplayMs": {
                      "minimum": 250.5,
                      "maximum": 310.25,
                      "median": 275.0,
                      "runs": [250.5, 275.0, 310.25]
                    }
                  },
                  "sampledMetrics": {
                    "frameDurationCpuMs": {
                      "P50": 5.1,
                      "P90": 12.3,
                      "P95": 15.0,
                      "P99": 22.0,
                      "runs": [[5.1, 12.3]]
                    }
                  }
                }
              ]
            }
            """.trimIndent()
    }
}
