package com.droidagentkit.cli

class InitWizard(
    private val readLine: () -> String?,
    private val print: (String) -> Unit,
) {
    fun run(): ProfileExpansion? {
        var expansion = ProfileExpansion(emptySet(), emptySet())

        if (askYesNo("Let the agent read device diagnostics (dumpsys, memory, battery, permission state)?")) {
            expansion = ProfileCatalog.union(expansion, ProfileCatalog.Q1_DEVICE_READ)
            if (askYesNo(
                    "Also allow full bugreport capture? This dumps extensive device/app data, " +
                        "including data from other apps.",
                )
            ) {
                expansion = ProfileCatalog.union(expansion, ProfileCatalog.Q1_BUGREPORT)
            }
        }
        if (askYesNo("Let the agent tap/swipe/type, open deep links, start/stop emulators, and push/pull files?")) {
            expansion = ProfileCatalog.union(expansion, ProfileCatalog.Q2_DEVICE_CONTROL)
            if (askYesNo(
                    "Also allow uninstalling apps, clearing app data, and granting/revoking permissions? " +
                        "These are irreversible on the device.",
                )
            ) {
                expansion = ProfileCatalog.union(expansion, ProfileCatalog.Q2_IRREVERSIBLE)
            }
        }
        if (askYesNo("Let the agent capture and analyze system performance traces?")) {
            expansion = ProfileCatalog.union(expansion, ProfileCatalog.Q3_PERFETTO)
        }
        if (askYesNo("Let the agent diff and report on UI screenshots?")) {
            expansion = ProfileCatalog.union(expansion, ProfileCatalog.Q4_VISUALS)
            if (askYesNo("Also allow overwriting golden/baseline images?")) {
                expansion = ProfileCatalog.union(expansion, ProfileCatalog.Q4_GOLDEN)
            }
        }
        if (askYesNo("Let the agent read a debuggable app's own SQLite databases and SharedPreferences (read-only)?")) {
            expansion = ProfileCatalog.union(expansion, ProfileCatalog.Q5_STORAGE)
        }
        if (askYesNo(
                "Let the agent intercept emulator network traffic via mitmproxy? Requires a debug-trusted " +
                    "CA already installed; emulator-only.",
            )
        ) {
            expansion = ProfileCatalog.union(expansion, ProfileCatalog.Q6_NETWORK)
        }

        val groupsText =
            expansion.groups
                .map { it.name.lowercase() }
                .sorted()
                .ifEmpty { listOf("(none)") }
                .joinToString(", ")
        val capsText =
            expansion.capabilities
                .map { it.name.lowercase() }
                .sorted()
                .ifEmpty { listOf("(none)") }
                .joinToString(", ")
        print("This will enable groups: $groupsText")
        print("and capabilities: $capsText")

        return if (askYesNo("Write this to .droidagentkit/config.yaml?")) expansion else null
    }

    private fun askYesNo(question: String): Boolean {
        print("$question [y/N] ")
        val answer = readLine()?.trim()?.lowercase()
        return answer == "y" || answer == "yes"
    }
}
