package com.droidagentkit.core

import org.junit.Assert.assertEquals
import org.junit.Test

class McpExposedGroupsConfigTest {
    @Test
    fun defaultConfigExposesCoreOnly() {
        assertEquals(setOf(ToolGroup.CORE), DroidAgentConfig.default().resolvedExposedToolGroups())
    }
}
