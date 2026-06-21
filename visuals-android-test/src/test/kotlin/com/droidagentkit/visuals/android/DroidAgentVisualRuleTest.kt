package com.droidagentkit.visuals.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DroidAgentVisualRuleTest {
    @Test
    fun `rule records deterministic case metadata`() {
        val rule = DroidAgentVisualRule()

        val capture = rule.captureCompose(
            name = "home_screen",
            matrix = VisualMatrix.standard(),
            semantics = listOf("Button: Start"),
        ) {
            "rendered"
        }

        assertEquals("home_screen", capture.caseName)
        assertEquals("rendered", capture.renderedValue)
        assertTrue(capture.semanticsDump.contains("Button: Start"))
        assertTrue(capture.environment.theme.isNotBlank())
    }
}
