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

    private val PROFILES: Map<String, ProfileExpansion> =
        mapOf(
            "core" to ProfileExpansion(emptySet(), emptySet()),
            "device-control" to union(Q1_DEVICE_READ, Q2_DEVICE_CONTROL),
            "storage" to Q5_STORAGE,
            "network-experimental" to Q6_NETWORK,
            "full" to
                union(
                    Q1_DEVICE_READ,
                    Q1_BUGREPORT,
                    Q2_DEVICE_CONTROL,
                    Q2_IRREVERSIBLE,
                    Q3_PERFETTO,
                    Q4_VISUALS,
                    Q4_GOLDEN,
                    Q5_STORAGE,
                    Q6_NETWORK,
                ),
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
