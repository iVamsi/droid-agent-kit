package com.droidagentkit.cli

import com.droidagentkit.core.ConfigError
import com.droidagentkit.core.ConfigLoadResult
import com.droidagentkit.core.DroidAgentConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DroidAgentMainTest {
    @Test
    fun `resolveServerConfig falls back to defaults and reports each error on invalid config`() {
        val messages = mutableListOf<String>()
        val invalid =
            ConfigLoadResult.Invalid(
                listOf(ConfigError(3, "safety.maxCommandSeconds", "expected a number, got 'soon'")),
            )

        val config = resolveServerConfig(invalid) { messages.add(it) }

        assertEquals(DroidAgentConfig.default(), config)
        assertEquals(1, messages.size)
        assertTrue(messages.first().contains("safety.maxCommandSeconds"))
    }

    @Test
    fun `resolveServerConfig passes through loaded config and reports warnings`() {
        val messages = mutableListOf<String>()
        val loaded = ConfigLoadResult.Loaded(DroidAgentConfig.default(), warnings = listOf("unknown key 'x'"))

        val config = resolveServerConfig(loaded) { messages.add(it) }

        assertEquals(DroidAgentConfig.default(), config)
        assertEquals(1, messages.size)
    }
}
