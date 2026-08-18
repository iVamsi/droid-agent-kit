package com.droidagentkit.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

data class DroidAgentConfig(
    val schemaVersion: Int = 1,
    val project: ProjectConfig = ProjectConfig(),
    val safety: SafetyConfig = SafetyConfig(),
    val mcp: McpConfig = McpConfig(),
    val reports: ReportsConfig = ReportsConfig(),
    val redaction: RedactionConfig = RedactionConfig(),
) {
    companion object {
        fun default() = DroidAgentConfig()
    }

    fun resolvedExposedToolGroups(): Set<ToolGroup> =
        buildSet {
            add(ToolGroup.CORE)
            addAll(mcp.exposedGroups)
        }
}

data class ProjectConfig(
    val name: String = "inferred",
)

data class SafetyConfig(
    val allowGradleTasks: List<String> =
        listOf(
            ":*:test*UnitTest",
            ":*:lint*",
            ":*:assemble*Debug",
            ":*:*AndroidTest",
            ":*:validate*ScreenshotTest",
        ),
    val allowAnyGradleTask: Boolean = false,
    val allowAdbInput: Boolean = false,
    val allowAppInstall: Boolean = true,
    val allowEmulatorStart: Boolean = false,
    val allowCapabilities: Set<Capability> = emptySet(),
    val adbPath: String = "adb",
    val emulatorPath: String = "emulator",
    val traceProcessorPath: String = "",
    val mitmProxyPath: String = "",
    val maxCommandSeconds: Long = 600,
    /**
     * When set, a destructive operation additionally needs a human to approve it out of band --
     * see [OperationPolicy]. Privileged: only the user policy may turn this on, since a project
     * config that could turn it *off* would defeat the point.
     */
    val requireInteractiveConfirm: Boolean = false,
    /** Session-wide blast-radius caps. Privileged: raising them is a grant. */
    val budgets: BudgetLimits = BudgetLimits(),
) {
    /**
     * A wildcard pattern must never sweep in a task that rewrites the working tree: `:*:lint*`
     * would otherwise admit `lintFix`, and globs cannot express "lint but not lintFix". Naming
     * such a task exactly (or setting [allowAnyGradleTask]) is explicit consent and still works.
     */
    fun isGradleTaskAllowed(task: String): Boolean {
        if (allowAnyGradleTask) return true
        if (allowGradleTasks.any { it == task }) return true
        if (MUTATING_TASK_PATTERNS.any { globToRegex(it).matches(task) }) return false
        return allowGradleTasks.any { pattern -> globToRegex(pattern).matches(task) }
    }

    fun allowedCapabilities(): Set<Capability> {
        if (allowCapabilities.isNotEmpty()) return allowCapabilities
        val fromAliases = mutableSetOf<Capability>()
        if (allowAdbInput) fromAliases.add(Capability.DEVICE_INPUT)
        if (allowAppInstall) fromAliases.add(Capability.APP_INSTALL)
        if (allowEmulatorStart) fromAliases.add(Capability.EMULATOR_CONTROL)
        return fromAliases
    }

    companion object {
        /**
         * Tasks that rewrite sources, baselines, or golden files, or that publish artifacts.
         * Reachable only by naming them exactly or by [allowAnyGradleTask].
         */
        val MUTATING_TASK_PATTERNS =
            listOf(
                "*lintFix*",
                "*updateLintBaseline*",
                "*ktlintFormat*",
                "*spotlessApply*",
                "*publish*",
                "*uploadArchives*",
                "*recordScreenshotTest*",
                "*updateScreenshotTest*",
            )
    }

    private fun globToRegex(pattern: String): Regex {
        val builder = StringBuilder("^")
        pattern.forEach { char ->
            when (char) {
                '*' -> builder.append(".*")
                '.', '(', ')', '[', ']', '{', '}', '+', '?', '^', '$', '\\', '|' -> {
                    builder.append('\\').append(char)
                }
                else -> builder.append(char)
            }
        }
        builder.append('$')
        return Regex(builder.toString())
    }
}

data class McpConfig(
    val exposedGroups: Set<ToolGroup> = emptySet(),
)

data class ReportsConfig(
    val outputDir: String = "build/droidagentkit",
)

data class RedactionConfig(
    val enabled: Boolean = true,
    val extraPatterns: List<String> = emptyList(),
)

data class ConfigError(
    val line: Int,
    val key: String,
    val message: String,
)

sealed interface ConfigLoadResult {
    data class Loaded(
        val config: DroidAgentConfig,
        val warnings: List<String> = emptyList(),
    ) : ConfigLoadResult

    data class Invalid(
        val errors: List<ConfigError>,
    ) : ConfigLoadResult
}

/**
 * Which file a config document came from. The trust split makes privileged keys —
 * capabilities, opt-in tool groups, host binary paths, disabling redaction, any-task Gradle —
 * honored only from [USER_POLICY]; a project config containing them gets warnings and defaults
 * for those keys instead.
 */
enum class ConfigSource {
    PROJECT,
    USER_POLICY,
}

object DroidAgentConfigLoader {
    private const val USER_POLICY_ENV = "DROIDAGENTKIT_POLICY"
    private const val USER_POLICY_PROPERTY = "droidagentkit.policy"
    private const val USER_POLICY_DISPLAY_PATH = "~/.droidagentkit/policy.yaml"

    private val knownSections = setOf("project", "safety", "mcp", "reports", "redaction")
    private val knownKeys =
        setOf(
            "project.name",
            "safety.allowGradleTasks",
            "safety.allowAnyGradleTask",
            "safety.allowAdbInput",
            "safety.allowAppInstall",
            "safety.allowEmulatorStart",
            "safety.allowCapabilities",
            "safety.requireInteractiveConfirm",
            "safety.maxDestructivePerMinute",
            "safety.maxArtifactBytesPerSession",
            "safety.adbPath",
            "safety.emulatorPath",
            "safety.traceProcessorPath",
            "safety.mitmProxyPath",
            "safety.maxCommandSeconds",
            "safety.requireInteractiveConfirm",
            "safety.maxDestructivePerMinute",
            "safety.maxArtifactBytesPerSession",
            "mcp.exposedGroups",
            "reports.outputDir",
            "redaction.enabled",
            "redaction.extraPatterns",
        )

    /** Keys that can grant new authority and are therefore ignored (with a warning) in project files. */
    private val privilegedKeys =
        setOf(
            "safety.allowAnyGradleTask",
            "safety.allowAdbInput",
            "safety.allowEmulatorStart",
            "safety.allowCapabilities",
            "safety.requireInteractiveConfirm",
            "safety.maxDestructivePerMinute",
            "safety.maxArtifactBytesPerSession",
            "safety.adbPath",
            "safety.emulatorPath",
            "safety.traceProcessorPath",
            "safety.mitmProxyPath",
            "mcp.exposedGroups",
            "redaction.enabled",
        )

    /** Gradle task patterns that match every task; too broad for a project file. */
    private val catchAllGradlePatterns = setOf("*", "**")

    fun defaultUserPolicyPath(): Path =
        System.getProperty(USER_POLICY_PROPERTY)?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
            ?: System.getenv(USER_POLICY_ENV)?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
            ?: Path.of(System.getProperty("user.home"), ".droidagentkit", "policy.yaml")

    fun load(projectRoot: Path): ConfigLoadResult {
        val path = projectRoot.resolve(".droidagentkit/config.yaml")
        if (!path.exists()) return ConfigLoadResult.Loaded(DroidAgentConfig.default())
        return parse(Files.readAllLines(path), ConfigSource.PROJECT)
    }

    fun loadUserPolicy(policyPath: Path = defaultUserPolicyPath()): ConfigLoadResult {
        if (!policyPath.exists()) return ConfigLoadResult.Loaded(DroidAgentConfig.default())
        return parse(Files.readAllLines(policyPath), ConfigSource.USER_POLICY)
    }

    /**
     * Loads the project config and the user policy, then merges them per the trust split:
     * privileged fields come from the policy, project-restrictable fields from the project file.
     */
    fun loadEffective(
        projectRoot: Path,
        policyPath: Path = defaultUserPolicyPath(),
    ): ConfigLoadResult {
        val projectResult = load(projectRoot)
        val policyResult = loadUserPolicy(policyPath)
        if (projectResult is ConfigLoadResult.Invalid) return projectResult
        if (policyResult is ConfigLoadResult.Invalid) {
            return ConfigLoadResult.Invalid(
                policyResult.errors.map { ConfigError(it.line, it.key, "user policy: ${it.message}") },
            )
        }
        val project = (projectResult as ConfigLoadResult.Loaded)
        val policy = (policyResult as ConfigLoadResult.Loaded)
        val (merged, mergeWarnings) = mergeWithWarnings(project.config, policy.config)
        val warnings =
            project.warnings +
                policy.warnings.map { "user policy: $it" } +
                mergeWarnings
        return ConfigLoadResult.Loaded(merged, warnings)
    }

    fun mergeWithUserPolicy(
        project: DroidAgentConfig,
        policy: DroidAgentConfig,
    ): DroidAgentConfig = mergeWithWarnings(project, policy).first

    /**
     * Applies the trust split. Privileged fields are taken from the policy outright. The two
     * fields a project may legitimately set: the Gradle task allowlist and the command timeout —
     * are *narrowed* against the policy rather than replaced, so a project file can never end up
     * with more authority than the policy grants.
     */
    private fun mergeWithWarnings(
        project: DroidAgentConfig,
        policy: DroidAgentConfig,
    ): Pair<DroidAgentConfig, List<String>> {
        val (gradleTasks, taskWarnings) =
            narrowGradleTasks(project.safety.allowGradleTasks, policy.safety.allowGradleTasks)
        val timeout = minOf(project.safety.maxCommandSeconds, policy.safety.maxCommandSeconds)
        val timeoutWarnings =
            if (project.safety.maxCommandSeconds > policy.safety.maxCommandSeconds) {
                listOf(
                    "safety.maxCommandSeconds ${project.safety.maxCommandSeconds} exceeds the user policy limit " +
                        "${policy.safety.maxCommandSeconds} — clamped to ${policy.safety.maxCommandSeconds}",
                )
            } else {
                emptyList()
            }
        val merged =
            project.copy(
                safety =
                    project.safety.copy(
                        allowGradleTasks = gradleTasks,
                        allowAnyGradleTask = policy.safety.allowAnyGradleTask,
                        allowAdbInput = policy.safety.allowAdbInput,
                        allowAppInstall = project.safety.allowAppInstall && policy.safety.allowAppInstall,
                        allowEmulatorStart = policy.safety.allowEmulatorStart,
                        allowCapabilities = policy.safety.allowCapabilities,
                        adbPath = policy.safety.adbPath,
                        emulatorPath = policy.safety.emulatorPath,
                        traceProcessorPath = policy.safety.traceProcessorPath,
                        mitmProxyPath = policy.safety.mitmProxyPath,
                        maxCommandSeconds = timeout,
                        requireInteractiveConfirm = policy.safety.requireInteractiveConfirm,
                        // Narrowed rather than replaced, like maxCommandSeconds: a project may
                        // choose to be *more* restrained than the policy, never less.
                        budgets =
                            BudgetLimits(
                                maxDestructivePerMinute =
                                    minOf(project.safety.budgets.maxDestructivePerMinute, policy.safety.budgets.maxDestructivePerMinute),
                                maxArtifactBytesPerSession =
                                    minOf(
                                        project.safety.budgets.maxArtifactBytesPerSession,
                                        policy.safety.budgets.maxArtifactBytesPerSession,
                                    ),
                            ),
                    ),
                mcp = McpConfig(exposedGroups = policy.mcp.exposedGroups),
                redaction =
                    RedactionConfig(
                        enabled = policy.redaction.enabled,
                        extraPatterns = (project.redaction.extraPatterns + policy.redaction.extraPatterns).distinct(),
                    ),
            )
        return merged to (taskWarnings + timeoutWarnings)
    }

    /**
     * Keeps only the project patterns that are at least as specific as something the policy
     * already allows. A pattern that would admit tasks the policy does not is dropped with a
     * warning rather than failing the load, matching how privileged keys are handled elsewhere.
     * If nothing survives, the policy's own list applies, never an empty (or wider) allowlist.
     */
    private fun narrowGradleTasks(
        projectPatterns: List<String>,
        policyPatterns: List<String>,
    ): Pair<List<String>, List<String>> {
        val warnings = mutableListOf<String>()
        val kept =
            projectPatterns.filter { pattern ->
                val subsumed = policyPatterns.any { globSubsumes(outer = it, inner = pattern) }
                if (!subsumed) {
                    warnings +=
                        "safety.allowGradleTasks pattern '$pattern' allows tasks the user policy " +
                        "($USER_POLICY_DISPLAY_PATH) does not — ignored. A project config can only " +
                        "narrow the allowlist, never widen it."
                }
                subsumed
            }
        return (kept.ifEmpty { policyPatterns }) to warnings
    }

    /**
     * True when every task name matched by [inner] is also matched by [outer]. Both are globs
     * whose only metacharacter is `*` (any run of characters), matching
     * [SafetyConfig.isGradleTaskAllowed].
     *
     * A `*` in [outer] absorbs any part of [inner], including [inner]'s own `*`. A literal in
     * [outer] must be met by the identical literal in [inner]; if [inner] has a `*` there it can
     * generate a character [outer] would reject, so the pair fails. That asymmetry is what makes
     * the check sound: it can reject a legitimate narrowing, but never accepts a widening.
     */
    internal fun globSubsumes(
        outer: String,
        inner: String,
    ): Boolean {
        val covers = Array(outer.length + 1) { BooleanArray(inner.length + 1) }
        covers[outer.length][inner.length] = true
        for (o in outer.length - 1 downTo 0) {
            for (i in inner.length downTo 0) {
                covers[o][i] =
                    if (outer[o] == '*') {
                        covers[o + 1][i] || (i < inner.length && covers[o][i + 1])
                    } else {
                        i < inner.length && inner[i] == outer[o] && covers[o + 1][i + 1]
                    }
            }
        }
        return covers[0][0]
    }

    private fun parse(
        lines: List<String>,
        source: ConfigSource,
    ): ConfigLoadResult {
        for ((index, rawLine) in lines.withIndex()) {
            val line = rawLine.trim()
            if (!line.startsWith("schemaVersion")) continue
            val rawValue = line.substringAfter(":", missingDelimiterValue = "").trim().unquote()
            val version = rawValue.toIntOrNull()
            if (version == null || version != 1) {
                return ConfigLoadResult.Invalid(
                    listOf(
                        ConfigError(
                            index + 1,
                            "schemaVersion",
                            "unsupported schema version '$rawValue'; this build supports schemaVersion 1",
                        ),
                    ),
                )
            }
            break
        }

        val errors = mutableListOf<ConfigError>()
        val warnings = mutableListOf<String>()
        var section = ""
        var projectName = "inferred"
        val allowGradleTasks = mutableListOf<String>()
        var allowAdbInput = false
        var allowAppInstall = true
        var allowEmulatorStart = false
        var allowAnyGradleTask = false
        val allowCapabilities = mutableSetOf<Capability>()
        val exposedGroups = mutableSetOf<ToolGroup>()
        var aliasUsedWithCapabilities = false
        var maxCommandSeconds = 600L
        var requireInteractiveConfirm = false
        var maxDestructivePerMinute = BudgetLimits().maxDestructivePerMinute
        var maxArtifactBytesPerSession = BudgetLimits().maxArtifactBytesPerSession
        var adbPath = "adb"
        var emulatorPath = "emulator"
        var traceProcessorPath = ""
        var mitmProxyPath = ""
        var outputDir = "build/droidagentkit"
        var outputDirLine: Int? = null
        var redactionEnabled = true
        val extraPatterns = mutableListOf<String>()
        var listTarget = ""

        for ((index, rawLine) in lines.withIndex()) {
            val lineNumber = index + 1
            val line = rawLine.trim()
            if (line.isBlank() || line.startsWith("#") || line.startsWith("schemaVersion")) continue
            if (!rawLine.startsWith(" ") && line.endsWith(":")) {
                section = line.removeSuffix(":")
                listTarget = ""
                if (section !in knownSections) warnings += "line $lineNumber: unknown section '$section' — ignored"
                continue
            }
            if (line.endsWith(":")) {
                listTarget = "$section.${line.removeSuffix(":")}"
                continue
            }
            if (line.startsWith("- ")) {
                val value = line.removePrefix("- ").unquote()
                when (listTarget) {
                    "safety.allowGradleTasks" ->
                        if (source == ConfigSource.PROJECT && value in catchAllGradlePatterns) {
                            warnings +=
                                "line $lineNumber: Gradle task pattern '$value' matches every task and is too broad for a " +
                                "project config — ignored. Set safety.allowAnyGradleTask: true in the user policy " +
                                "($USER_POLICY_DISPLAY_PATH) to allow any task"
                        } else {
                            allowGradleTasks.add(value)
                        }
                    "safety.allowCapabilities" ->
                        if (source == ConfigSource.PROJECT) {
                            warnings += privilegedWarning(lineNumber, listTarget)
                        } else {
                            val capability = parseCapability(value, lineNumber, errors)
                            if (capability != null) allowCapabilities.add(capability)
                        }
                    "mcp.exposedGroups" ->
                        if (source == ConfigSource.PROJECT) {
                            warnings += privilegedWarning(lineNumber, listTarget)
                        } else {
                            val group = parseToolGroup(value, lineNumber, errors)
                            if (group != null) exposedGroups.add(group)
                        }
                    "redaction.extraPatterns" -> {
                        val rejection = validateExtraPattern(value)
                        if (rejection != null) {
                            errors += ConfigError(lineNumber, "redaction.extraPatterns", rejection)
                        } else {
                            extraPatterns.add(value)
                        }
                    }
                }
                continue
            }
            val key = line.substringBefore(":", missingDelimiterValue = "").trim()
            val value = line.substringAfter(":", missingDelimiterValue = "").trim().unquote()
            val fullKey = "$section.$key"
            // In project files, privileged keys are ignored with a warning. Assignments equal to the
            // built-in default (allowAdbInput: false, redaction.enabled: true, …) are silent no-ops so
            // previously generated configs don't spam warnings. The reverse direction, granting a
            // capability or disabling redaction, is always blocked here.
            if (source == ConfigSource.PROJECT && fullKey in privilegedKeys) {
                val isDefaultNoop =
                    when (fullKey) {
                        // requireInteractiveConfirm is deliberately absent: for every other key
                        // here the dangerous direction is `true`, so "equals the default" is a
                        // harmless no-op worth staying quiet about. For this one the dangerous
                        // direction is `false` (a project trying to switch a protection *off*)
                        // and that must never be swallowed silently.
                        "safety.allowAdbInput", "safety.allowEmulatorStart", "safety.allowAnyGradleTask" -> value == "false"
                        "redaction.enabled" -> value == "true"
                        else -> false
                    }
                if (!isDefaultNoop) {
                    warnings += privilegedWarning(lineNumber, fullKey)
                    continue
                }
            }
            when (fullKey) {
                "project.name" -> projectName = value
                "safety.allowAnyGradleTask" ->
                    allowAnyGradleTask =
                        value.toStrictBooleanOrError(lineNumber, fullKey, errors) ?: allowAnyGradleTask
                "safety.allowAdbInput" -> {
                    allowAdbInput = value.toStrictBooleanOrError(lineNumber, fullKey, errors) ?: allowAdbInput
                    if (allowAdbInput && allowCapabilities.isNotEmpty()) aliasUsedWithCapabilities = true
                }
                "safety.allowAppInstall" -> {
                    allowAppInstall = value.toStrictBooleanOrError(lineNumber, fullKey, errors) ?: allowAppInstall
                    if (allowAppInstall && allowCapabilities.isNotEmpty()) aliasUsedWithCapabilities = true
                }
                "safety.allowEmulatorStart" ->
                    allowEmulatorStart =
                        value.toStrictBooleanOrError(lineNumber, fullKey, errors) ?: allowEmulatorStart
                "safety.maxCommandSeconds" -> maxCommandSeconds = value.toLongOrError(lineNumber, fullKey, errors) ?: maxCommandSeconds
                "safety.requireInteractiveConfirm" ->
                    requireInteractiveConfirm =
                        value.toStrictBooleanOrError(lineNumber, fullKey, errors) ?: requireInteractiveConfirm
                "safety.maxDestructivePerMinute" ->
                    maxDestructivePerMinute =
                        value.toLongOrError(lineNumber, fullKey, errors)?.toInt() ?: maxDestructivePerMinute
                "safety.maxArtifactBytesPerSession" ->
                    maxArtifactBytesPerSession =
                        value.toLongOrError(lineNumber, fullKey, errors) ?: maxArtifactBytesPerSession
                "safety.adbPath" -> adbPath = value
                "safety.emulatorPath" -> emulatorPath = value
                "safety.traceProcessorPath" -> traceProcessorPath = value
                "safety.mitmProxyPath" -> mitmProxyPath = value
                "reports.outputDir" -> {
                    outputDir = value
                    outputDirLine = lineNumber
                }
                "redaction.enabled" -> redactionEnabled = value.toStrictBooleanOrError(lineNumber, fullKey, errors) ?: redactionEnabled
                else -> if (fullKey !in knownKeys) warnings += "line $lineNumber: unknown key '$fullKey' — ignored"
            }
        }

        if (!outputDir.isSafeProjectRelativePath()) {
            errors +=
                ConfigError(
                    outputDirLine ?: 0,
                    "reports.outputDir",
                    "must be a non-empty relative path inside the project",
                )
        }

        if (errors.isNotEmpty()) return ConfigLoadResult.Invalid(errors)

        if (aliasUsedWithCapabilities) {
            warnings +=
                "safety.allowCapabilities takes precedence over deprecated allowAdbInput/allowAppInstall/allowEmulatorStart aliases; aliases ignored"
        }

        val safety =
            SafetyConfig(
                allowGradleTasks = allowGradleTasks.ifEmpty { SafetyConfig().allowGradleTasks },
                allowAnyGradleTask = allowAnyGradleTask,
                allowAdbInput = allowAdbInput,
                allowAppInstall = allowAppInstall,
                allowEmulatorStart = allowEmulatorStart,
                allowCapabilities = allowCapabilities.toSet(),
                adbPath = adbPath,
                emulatorPath = emulatorPath,
                traceProcessorPath = traceProcessorPath,
                mitmProxyPath = mitmProxyPath,
                maxCommandSeconds = maxCommandSeconds,
                requireInteractiveConfirm = requireInteractiveConfirm,
                budgets = BudgetLimits(maxDestructivePerMinute, maxArtifactBytesPerSession),
            )
        return ConfigLoadResult.Loaded(
            DroidAgentConfig(
                project = ProjectConfig(projectName),
                safety = safety,
                mcp = McpConfig(exposedGroups = exposedGroups.toSet()),
                reports = ReportsConfig(outputDir),
                redaction = RedactionConfig(redactionEnabled, extraPatterns),
            ),
            warnings = warnings,
        )
    }

    private fun privilegedWarning(
        lineNumber: Int,
        key: String,
    ): String =
        "line $lineNumber: '$key' is only honored in the user policy ($USER_POLICY_DISPLAY_PATH) — " +
            "ignored here (see docs/security-and-permissions.md)"

    /**
     * Rejects extraPatterns that are likely to hang the redactor (catastrophic backtracking) or
     * that fail to compile. Keeps the hand-rolled YAML loader free of a regex engine timeout API.
     */
    private fun validateExtraPattern(pattern: String): String? {
        if (pattern.length > MAX_EXTRA_PATTERN_LENGTH) {
            return "pattern exceeds $MAX_EXTRA_PATTERN_LENGTH characters"
        }
        if (NESTED_QUANTIFIER.containsMatchIn(pattern)) {
            return "pattern looks like nested quantifiers (ReDoS risk); simplify it"
        }
        return try {
            Regex(pattern)
            null
        } catch (error: Exception) {
            "invalid regex: ${error.message}"
        }
    }

    private const val MAX_EXTRA_PATTERN_LENGTH = 256
    private val NESTED_QUANTIFIER = Regex("""(\([^)]*[+*][^)]*\))[+*]|\([^)]*[+*][^)]*[+*][^)]*\)""")

    private fun String.unquote(): String = trim().removeSurrounding("\"").removeSurrounding("'")

    private fun parseCapability(
        value: String,
        line: Int,
        errors: MutableList<ConfigError>,
    ): Capability? =
        Capability.entries.firstOrNull { it.name.lowercase() == value.lowercase() }
            ?: run {
                errors += ConfigError(line, "safety.allowCapabilities", "unknown capability '$value'")
                null
            }

    private fun parseToolGroup(
        value: String,
        line: Int,
        errors: MutableList<ConfigError>,
    ): ToolGroup? {
        val normalized = value.lowercase().replace('-', '_')
        return ToolGroup.entries.firstOrNull { it.name.lowercase() == normalized }
            ?: run {
                errors += ConfigError(line, "mcp.exposedGroups", "unknown tool group '$value'")
                null
            }
    }

    private fun String.toStrictBooleanOrError(
        line: Int,
        key: String,
        errors: MutableList<ConfigError>,
    ): Boolean? =
        when (this) {
            "true" -> true
            "false" -> false
            else -> {
                errors += ConfigError(line, key, "expected true or false, got '$this'")
                null
            }
        }

    private fun String.toLongOrError(
        line: Int,
        key: String,
        errors: MutableList<ConfigError>,
    ): Long? =
        toLongOrNull() ?: run {
            errors += ConfigError(line, key, "expected a number, got '$this'")
            null
        }

    private fun String.isSafeProjectRelativePath(): Boolean =
        runCatching {
            val path = Path.of(this).normalize()
            isNotBlank() && !path.isAbsolute && !path.startsWith("..")
        }.getOrDefault(false)
}
