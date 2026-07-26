package com.droidagentkit.cli

import com.droidagentkit.core.Capability
import com.droidagentkit.core.ToolGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InitWizardTest {
    private fun wizard(answers: List<String>): InitWizard {
        val iterator = answers.iterator()
        return InitWizard(readLine = { if (iterator.hasNext()) iterator.next() else null }, print = {})
    }

    @Test
    fun `answering no to every question and yes to the final confirm writes an empty expansion`() {
        // 6 top-level questions, all "n", then "y" to the final write-confirmation.
        val result = wizard(listOf("n", "n", "n", "n", "n", "n", "y")).run()

        assertEquals(ProfileExpansion(emptySet(), emptySet()), result)
    }

    @Test
    fun `answering yes to every question and every follow-up matches the full profile`() {
        // device-read=y, bugreport-followup=y, device-control=y, irreversible-followup=y,
        // perfetto=y, visuals=y, golden-followup=y, storage=y, network=y, final-confirm=y
        val result = wizard(listOf("y", "y", "y", "y", "y", "y", "y", "y", "y", "y")).run()

        val expected =
            ProfileCatalog.union(
                ProfileCatalog.Q1_DEVICE_READ,
                ProfileCatalog.Q1_BUGREPORT,
                ProfileCatalog.Q2_DEVICE_CONTROL,
                ProfileCatalog.Q2_IRREVERSIBLE,
                ProfileCatalog.Q3_PERFETTO,
                ProfileCatalog.Q4_VISUALS,
                ProfileCatalog.Q4_GOLDEN,
                ProfileCatalog.Q5_STORAGE,
                ProfileCatalog.Q6_NETWORK,
            )
        assertEquals(expected, result)
    }

    @Test
    fun `declining a follow-up grants the group but not the follow-up capability`() {
        // device-read=y, bugreport-followup=n, then no to the remaining 5 questions, final-confirm=y
        val result = wizard(listOf("y", "n", "n", "n", "n", "n", "n", "y")).run()

        assertEquals(ProfileExpansion(setOf(ToolGroup.DEVICE_READ), emptySet()), result)
    }

    @Test
    fun `declining the final confirmation returns null regardless of earlier answers`() {
        // device-control=y, irreversible-followup=y, then no to remaining 4 questions, final-confirm=n
        val result = wizard(listOf("n", "y", "y", "n", "n", "n", "n")).run()

        assertNull(result)
    }

    @Test
    fun `perfetto alone grants sensitive_diagnostics without exposing device_read`() {
        // device-read=n, device-control=n, perfetto=y, visuals=n, storage=n, network=n, final-confirm=y
        val result = wizard(listOf("n", "n", "y", "n", "n", "n", "y")).run()

        assertEquals(ProfileExpansion(setOf(ToolGroup.PERFETTO), setOf(Capability.SENSITIVE_DIAGNOSTICS)), result)
    }

    @Test
    fun `blank or unrecognized answers are treated as no`() {
        val result = wizard(listOf("", "maybe", "n", "n", "n", "n", "y")).run()

        assertEquals(ProfileExpansion(emptySet(), emptySet()), result)
    }
}
