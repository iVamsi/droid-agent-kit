package com.droidagentkit.cli

import com.droidagentkit.core.Capability
import com.droidagentkit.core.ToolGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileCatalogTest {
    @Test
    fun `core profile is empty`() {
        val expansion = ProfileCatalog.expand(listOf("core")).getOrThrow()

        assertEquals(emptySet<ToolGroup>(), expansion.groups)
        assertEquals(emptySet<Capability>(), expansion.capabilities)
    }

    @Test
    fun `device-control profile excludes irreversible and diagnostic capabilities`() {
        val expansion = ProfileCatalog.expand(listOf("device-control")).getOrThrow()

        assertEquals(setOf(ToolGroup.DEVICE_READ, ToolGroup.DEVICE_CONTROL), expansion.groups)
        assertEquals(
            setOf(
                Capability.DEVICE_INPUT,
                Capability.APP_CONTROL,
                Capability.EMULATOR_CONTROL,
                Capability.EMULATOR_RESTORE,
                Capability.FILE_EXPORT,
                Capability.FILE_IMPORT,
            ),
            expansion.capabilities,
        )
    }

    @Test
    fun `storage profile is the storage group alone`() {
        val expansion = ProfileCatalog.expand(listOf("storage")).getOrThrow()

        assertEquals(setOf(ToolGroup.STORAGE), expansion.groups)
        assertEquals(setOf(Capability.APP_DATA_READ), expansion.capabilities)
    }

    @Test
    fun `network-experimental profile is the network_experimental group alone`() {
        val expansion = ProfileCatalog.expand(listOf("network-experimental")).getOrThrow()

        assertEquals(setOf(ToolGroup.NETWORK_EXPERIMENTAL), expansion.groups)
        assertEquals(setOf(Capability.NETWORK_INTERCEPTION), expansion.capabilities)
    }

    @Test
    fun `full profile equals the union of every other named profile plus perfetto, visuals, and the irreversible extras`() {
        val core = ProfileCatalog.expand(listOf("core")).getOrThrow()
        val deviceControl = ProfileCatalog.expand(listOf("device-control")).getOrThrow()
        val storage = ProfileCatalog.expand(listOf("storage")).getOrThrow()
        val networkExperimental = ProfileCatalog.expand(listOf("network-experimental")).getOrThrow()
        val extrasNotCoveredByAnyOtherNamedProfile =
            ProfileExpansion(
                groups = setOf(ToolGroup.PERFETTO, ToolGroup.VISUALS),
                capabilities =
                    setOf(
                        Capability.APP_DESTRUCTIVE,
                        Capability.PERMISSION_MUTATION,
                        Capability.SENSITIVE_DIAGNOSTICS,
                        Capability.GOLDEN_UPDATE,
                    ),
            )
        val expected =
            ProfileCatalog.union(core, deviceControl, storage, networkExperimental, extrasNotCoveredByAnyOtherNamedProfile)

        val actual = ProfileCatalog.expand(listOf("full")).getOrThrow()

        assertEquals(expected.groups, actual.groups)
        assertEquals(expected.capabilities, actual.capabilities)
    }

    @Test
    fun `comma-combining two profiles unions their groups and capabilities`() {
        val expansion = ProfileCatalog.expand(listOf("storage", "network-experimental")).getOrThrow()

        assertEquals(setOf(ToolGroup.STORAGE, ToolGroup.NETWORK_EXPERIMENTAL), expansion.groups)
        assertEquals(setOf(Capability.APP_DATA_READ, Capability.NETWORK_INTERCEPTION), expansion.capabilities)
    }

    @Test
    fun `unknown profile name is rejected with the name surfaced`() {
        val result = ProfileCatalog.expand(listOf("not-a-real-profile"))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("not-a-real-profile"))
    }

    @Test
    fun `names lists every profile sorted`() {
        assertEquals(
            listOf("core", "device-control", "full", "network-experimental", "storage"),
            ProfileCatalog.names(),
        )
    }

    @Test
    fun `description returns non-blank text for every known profile`() {
        ProfileCatalog.names().forEach { name ->
            assertTrue("expected a description for '$name'", ProfileCatalog.description(name).isNotBlank())
        }
    }
}
