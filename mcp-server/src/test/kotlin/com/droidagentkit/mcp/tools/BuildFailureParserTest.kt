package com.droidagentkit.mcp.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildFailureParserTest {
    @Test
    fun `classifies only recognized build failures`() {
        val findings =
            BuildFailureParser.parse(
                """
                e: file:///project/src/main/kotlin/App.kt:12:8 Unresolved reference 'missing'
                /project/src/main/java/App.java:7: error: cannot find symbol
                Android resource linking failed
                Manifest merger failed with multiple errors
                Configuration cache problems found in this build.
                """.trimIndent(),
            )

        assertEquals(5, findings.size)
        assertTrue(findings.any { it.category == "compiler.kotlin" && it.location!!.endsWith("App.kt:12:8") })
        assertTrue(findings.any { it.category == "gradle.configuration-cache" })
    }

    @Test
    fun `leaves unknown output unclassified`() {
        assertTrue(BuildFailureParser.parse("Something unfamiliar failed").isEmpty())
    }
}
