package com.droidagentkit.mcp.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildProfileParserTest {
    @Test
    fun `parses task timings sorted by duration descending excluding total rows`() {
        val result = BuildProfileParser.parse(SAMPLE_PROFILE_HTML)

        assertEquals(2, result.taskTimings.size)
        assertEquals(":toolbox-core:compileKotlin", result.taskTimings[0].taskPath)
        assertEquals(12L, result.taskTimings[0].durationMs)
        assertEquals(":toolbox-core:compileTestKotlin", result.taskTimings[1].taskPath)
        assertEquals(6L, result.taskTimings[1].durationMs)
    }

    @Test
    fun `extracts total build time and configuration time from the summary tab`() {
        val result = BuildProfileParser.parse(SAMPLE_PROFILE_HTML)

        assertEquals(487L, result.totalBuildTimeMs)
        assertEquals(66L, result.configurationTimeMs)
    }

    @Test
    fun `returns empty result when the task execution section is missing`() {
        val result = BuildProfileParser.parse("<html><body>no profile data</body></html>")

        assertTrue(result.taskTimings.isEmpty())
        assertNull(result.totalBuildTimeMs)
    }

    companion object {
        private val SAMPLE_PROFILE_HTML =
            """
            <html><body>
            <div class="tab" id="tab0">
            <h2>Summary</h2>
            <table>
            <thead>
            <tr>
            <th>Description</th>
            <th class="numeric">Duration</th>
            </tr>
            </thead>
            <tr>
            <td>Total Build Time</td>
            <td class="numeric">0.487s</td>
            </tr>
            <tr>
            <td>Configuring Projects</td>
            <td class="numeric">0.066s</td>
            </tr>
            </table>
            </div>
            <div class="tab" id="tab4">
            <h2>Task Execution</h2>
            <table>
            <thead>
            <tr>
            <th>Task</th>
            <th class="numeric">Duration</th>
            <th>Result</th>
            </tr>
            </thead>
            <tr>
            <td>:toolbox-core</td>
            <td class="numeric">0.021s</td>
            <td>(total)</td>
            </tr>
            <tr>
            <td class="indentPath">:toolbox-core:compileKotlin</td>
            <td class="numeric">0.012s</td>
            <td>UP-TO-DATE</td>
            </tr>
            <tr>
            <td class="indentPath">:toolbox-core:compileTestKotlin</td>
            <td class="numeric">0.006s</td>
            <td>UP-TO-DATE</td>
            </tr>
            </table>
            </div>
            </body></html>
            """.trimIndent()
    }
}
