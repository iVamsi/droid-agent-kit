package com.droidagentkit.cli

import com.droidagentkit.core.Capability
import com.droidagentkit.core.ToolGroup

data class ProfileExpansion(
    val groups: Set<ToolGroup>,
    val capabilities: Set<Capability>,
)

object ProfileCatalog {
    val Q1_DEVICE_READ = ProfileExpansion(groups = setOf(ToolGroup.DEVICE_READ), capabilities = emptySet())
    val Q1_BUGREPORT = ProfileExpansion(groups = emptySet(), capabilities = setOf(Capability.SENSITIVE_DIAGNOSTICS))
    val Q2_DEVICE_CONTROL =
        ProfileExpansion(
            groups = setOf(ToolGroup.DEVICE_CONTROL),
            capabilities =
                setOf(
                    Capability.DEVICE_INPUT,
                    Capability.APP_CONTROL,
                    Capability.EMULATOR_CONTROL,
                    Capability.EMULATOR_RESTORE,
                    Capability.FILE_EXPORT,
                    Capability.FILE_IMPORT,
                ),
        )
    val Q2_IRREVERSIBLE =
        ProfileExpansion(groups = emptySet(), capabilities = setOf(Capability.APP_DESTRUCTIVE, Capability.PERMISSION_MUTATION))
    val Q3_PERFETTO = ProfileExpansion(groups = setOf(ToolGroup.PERFETTO), capabilities = setOf(Capability.SENSITIVE_DIAGNOSTICS))
    val Q4_VISUALS = ProfileExpansion(groups = setOf(ToolGroup.VISUALS), capabilities = emptySet())
    val Q4_GOLDEN = ProfileExpansion(groups = emptySet(), capabilities = setOf(Capability.GOLDEN_UPDATE))
    val Q5_STORAGE = ProfileExpansion(groups = setOf(ToolGroup.STORAGE), capabilities = setOf(Capability.APP_DATA_READ))
    val Q6_NETWORK =
        ProfileExpansion(groups = setOf(ToolGroup.NETWORK_EXPERIMENTAL), capabilities = setOf(Capability.NETWORK_INTERCEPTION))

    fun union(vararg expansions: ProfileExpansion): ProfileExpansion =
        ProfileExpansion(
            groups = expansions.flatMap { it.groups }.toSet(),
            capabilities = expansions.flatMap { it.capabilities }.toSet(),
        )

    private val CORE_EXPANSION = ProfileExpansion(emptySet(), emptySet())
    private val DEVICE_CONTROL_EXPANSION = union(Q1_DEVICE_READ, Q2_DEVICE_CONTROL)
    private val STORAGE_EXPANSION = Q5_STORAGE
    private val NETWORK_EXPERIMENTAL_EXPANSION = Q6_NETWORK
    private val FULL_EXTRAS =
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
    private val FULL_EXPANSION =
        union(CORE_EXPANSION, DEVICE_CONTROL_EXPANSION, STORAGE_EXPANSION, NETWORK_EXPERIMENTAL_EXPANSION, FULL_EXTRAS)

    private val PROFILES: Map<String, ProfileExpansion> =
        mapOf(
            "core" to CORE_EXPANSION,
            "device-control" to DEVICE_CONTROL_EXPANSION,
            "storage" to STORAGE_EXPANSION,
            "network-experimental" to NETWORK_EXPERIMENTAL_EXPANSION,
            "full" to FULL_EXPANSION,
        )

    fun names(): List<String> = PROFILES.keys.sorted()

    fun expand(profileNames: List<String>): Result<ProfileExpansion> {
        val unknown = profileNames.filter { it !in PROFILES }
        if (unknown.isNotEmpty()) {
            return Result.failure(IllegalArgumentException(unknown.joinToString(", ")))
        }
        return Result.success(union(*profileNames.map { PROFILES.getValue(it) }.toTypedArray()))
    }
}
