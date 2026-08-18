package com.droidagentkit.core

import java.nio.file.Files
import java.nio.file.Path

enum class CheckStatus {
    OK,
    WARN,
    FAIL,
}

data class DoctorCheck(
    val name: String,
    val status: CheckStatus,
    val detail: String,
    val remedy: String? = null,
)

data class DoctorReport(
    val checks: List<DoctorCheck>,
) {
    /** False when anything is outright broken. Warnings are informational and never fail the run. */
    val ok: Boolean get() = checks.none { it.status == CheckStatus.FAIL }
}

/** Runs a command and returns the text it printed, or null when it could not be executed. */
fun interface BinaryProbe {
    fun probe(command: List<String>): String?
}

/**
 * Answers "why isn't this working?" in one place, before a tool call fails halfway through.
 *
 * A missing optional binary is a WARN, and only when the group that needs it is enabled. Most
 * installs never turn on device control, Perfetto, or network capture, so a missing `mitmproxy`
 * says nothing.
 *
 * A policy file that does not parse is always a FAIL. The loader falls back to defaults, so the
 * server runs with less authority than the user configured and nothing else would report it.
 *
 * Environment lookups are injected so the checks can be tested without spawning processes.
 */
class DoctorChecks(
    private val probe: BinaryProbe,
    private val env: (String) -> String?,
    private val javaVersion: String = System.getProperty("java.version").orEmpty(),
) {
    fun run(
        projectRoot: Path,
        policyPath: Path,
    ): DoctorReport {
        val checks = mutableListOf<DoctorCheck>()
        checks += javaCheck()

        val policyResult = DroidAgentConfigLoader.loadUserPolicy(policyPath)
        checks += policyCheck(policyPath, policyResult)

        val projectResult = DroidAgentConfigLoader.load(projectRoot)
        checks += projectCheck(projectRoot, projectResult)

        val effective =
            when (val merged = DroidAgentConfigLoader.loadEffective(projectRoot, policyPath)) {
                is ConfigLoadResult.Loaded -> merged.config
                is ConfigLoadResult.Invalid -> DroidAgentConfig.default()
            }

        val groups = effective.resolvedExposedToolGroups()
        checks += binaryCheck("adb", effective.safety.adbPath, ADB_REMEDY)
        if (ToolGroup.DEVICE_CONTROL in groups) {
            checks += binaryCheck("emulator", effective.safety.emulatorPath, EMULATOR_REMEDY)
        }
        if (ToolGroup.PERFETTO in groups) {
            checks += optionalPathCheck("trace_processor", effective.safety.traceProcessorPath, TRACE_PROCESSOR_REMEDY)
        }
        if (ToolGroup.NETWORK_EXPERIMENTAL in groups) {
            checks += optionalPathCheck("mitmproxy", effective.safety.mitmProxyPath, MITMPROXY_REMEDY)
        }

        checks += androidHomeCheck()
        checks += artifactDirCheck(projectRoot, effective.reports.outputDir)
        checks += groupsCheck(groups)
        checks += capabilitiesCheck(effective.safety.allowedCapabilities())
        return DoctorReport(checks)
    }

    private fun javaCheck(): DoctorCheck {
        val major = majorJavaVersion(javaVersion)
        return if (major != null && major >= MIN_JAVA) {
            DoctorCheck("java", CheckStatus.OK, "$javaVersion (>= $MIN_JAVA)")
        } else {
            DoctorCheck(
                "java",
                CheckStatus.FAIL,
                "$javaVersion is below the required $MIN_JAVA",
                "Install a JDK $MIN_JAVA or newer (https://adoptium.net) and make sure it is the one on PATH.",
            )
        }
    }

    private fun policyCheck(
        path: Path,
        result: ConfigLoadResult,
    ): DoctorCheck =
        when (result) {
            is ConfigLoadResult.Invalid ->
                DoctorCheck(
                    "user policy",
                    CheckStatus.FAIL,
                    "$path is invalid: ${result.errors.joinToString("; ") { "line ${it.line}: ${it.key} — ${it.message}" }}",
                    "Fix the listed lines. Until then the server runs on built-in defaults, ignoring every grant in this file.",
                )
            is ConfigLoadResult.Loaded ->
                when {
                    !Files.exists(path) ->
                        DoctorCheck(
                            "user policy",
                            CheckStatus.OK,
                            "$path not present — using built-in defaults (core tools only)",
                        )
                    result.warnings.isNotEmpty() ->
                        DoctorCheck("user policy", CheckStatus.WARN, "$path loaded with: ${result.warnings.joinToString("; ")}")
                    else -> DoctorCheck("user policy", CheckStatus.OK, "$path loaded")
                }
        }

    private fun projectCheck(
        projectRoot: Path,
        result: ConfigLoadResult,
    ): DoctorCheck {
        val path = projectRoot.resolve(".droidagentkit/config.yaml")
        return when (result) {
            is ConfigLoadResult.Invalid ->
                DoctorCheck(
                    "project config",
                    CheckStatus.FAIL,
                    "$path is invalid: ${result.errors.joinToString("; ") { "line ${it.line}: ${it.key} — ${it.message}" }}",
                    "Fix the listed lines, or delete the file to fall back to the user policy.",
                )
            is ConfigLoadResult.Loaded ->
                when {
                    !Files.exists(path) ->
                        DoctorCheck(
                            "project config",
                            CheckStatus.OK,
                            "no .droidagentkit/config.yaml — using the policy as-is",
                        )
                    result.warnings.isNotEmpty() ->
                        DoctorCheck(
                            "project config",
                            CheckStatus.WARN,
                            "$path loaded with: ${result.warnings.joinToString("; ")}",
                            "Keys that grant authority are honored only in the user policy. Move them there if they were intended.",
                        )
                    else -> DoctorCheck("project config", CheckStatus.OK, "$path loaded")
                }
        }
    }

    private fun binaryCheck(
        name: String,
        configuredPath: String,
        remedy: String,
    ): DoctorCheck {
        val version = probe.probe(listOf(configuredPath, "--version"))
        return if (version.isNullOrBlank()) {
            DoctorCheck(name, CheckStatus.WARN, "'$configuredPath' is not runnable", remedy)
        } else {
            DoctorCheck(name, CheckStatus.OK, version.lineSequence().first().trim())
        }
    }

    /**
     * For tools whose path defaults to empty rather than to a name on PATH. An unset path is the
     * normal state, so it reads as "not configured" rather than as a broken install.
     */
    private fun optionalPathCheck(
        name: String,
        configuredPath: String,
        remedy: String,
    ): DoctorCheck {
        if (configuredPath.isBlank()) {
            return DoctorCheck(name, CheckStatus.WARN, "not configured, but its tool group is enabled", remedy)
        }
        return binaryCheck(name, configuredPath, remedy)
    }

    private fun androidHomeCheck(): DoctorCheck {
        val home = env("ANDROID_HOME") ?: env("ANDROID_SDK_ROOT")
        return if (home.isNullOrBlank()) {
            DoctorCheck(
                "ANDROID_HOME",
                CheckStatus.WARN,
                "unset",
                "Set ANDROID_HOME (or ANDROID_SDK_ROOT) so device tools can find the SDK, or set safety.adbPath in the user policy.",
            )
        } else {
            DoctorCheck("ANDROID_HOME", CheckStatus.OK, home)
        }
    }

    private fun artifactDirCheck(
        projectRoot: Path,
        outputDir: String,
    ): DoctorCheck {
        val target = projectRoot.resolve(outputDir)
        val existing = generateSequence(target) { it.parent }.firstOrNull { Files.exists(it) }
        return when {
            Files.exists(target) && !Files.isDirectory(target) ->
                DoctorCheck(
                    "artifact directory",
                    CheckStatus.FAIL,
                    "$target exists but is not a directory",
                    "Remove or rename it; artifacts cannot be written until it is a directory.",
                )
            existing != null && !Files.isWritable(existing) ->
                DoctorCheck(
                    "artifact directory",
                    CheckStatus.FAIL,
                    "$existing is not writable",
                    "Grant write permission, or point reports.outputDir somewhere writable.",
                )
            else -> DoctorCheck("artifact directory", CheckStatus.OK, "$target is writable")
        }
    }

    private fun groupsCheck(groups: Set<ToolGroup>): DoctorCheck =
        DoctorCheck(
            "tool groups",
            CheckStatus.OK,
            groups.map { it.name.lowercase() }.sorted().joinToString(", "),
        )

    private fun capabilitiesCheck(capabilities: Set<Capability>): DoctorCheck =
        DoctorCheck(
            "capabilities",
            CheckStatus.OK,
            if (capabilities.isEmpty()) "none granted" else capabilities.map { it.name.lowercase() }.sorted().joinToString(", "),
        )

    private companion object {
        const val MIN_JAVA = 17
        const val ADB_REMEDY =
            "Install Android platform-tools and put adb on PATH, or set safety.adbPath in ~/.droidagentkit/policy.yaml. " +
                "Project-only tools (inspect, audit, lint, crash triage) work without it."
        const val EMULATOR_REMEDY =
            "Install the Android SDK emulator, or set safety.emulatorPath in ~/.droidagentkit/policy.yaml."
        const val TRACE_PROCESSOR_REMEDY =
            "Download trace_processor from https://perfetto.dev and set safety.traceProcessorPath in ~/.droidagentkit/policy.yaml."
        const val MITMPROXY_REMEDY =
            "Install mitmproxy and set safety.mitmProxyPath in ~/.droidagentkit/policy.yaml."

        /** Handles both the modern "17.0.9" scheme and the legacy "1.8.0_392" one. */
        fun majorJavaVersion(raw: String): Int? {
            val cleaned = raw.trim()
            if (cleaned.isEmpty()) return null
            val head = cleaned.substringBefore('-').split('.', '_')
            val first = head.firstOrNull()?.toIntOrNull() ?: return null
            return if (first == 1) head.getOrNull(1)?.toIntOrNull() else first
        }
    }
}
