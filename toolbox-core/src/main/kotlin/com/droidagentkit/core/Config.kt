package com.droidagentkit.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

data class DroidAgentConfig(
    val schemaVersion: Int = 1,
    val project: ProjectConfig = ProjectConfig(),
    val safety: SafetyConfig = SafetyConfig(),
    val reports: ReportsConfig = ReportsConfig(),
    val redaction: RedactionConfig = RedactionConfig(),
) {
    companion object {
        fun default() = DroidAgentConfig()
    }
}

data class ProjectConfig(
    val name: String = "inferred",
)

data class SafetyConfig(
    val allowGradleTasks: List<String> = listOf(
        ":*:test*UnitTest",
        ":*:lint*",
        ":*:assemble*Debug",
    ),
    val allowAdbInput: Boolean = false,
    val allowAppInstall: Boolean = true,
    val allowEmulatorStart: Boolean = false,
    val maxCommandSeconds: Long = 600,
) {
    fun isGradleTaskAllowed(task: String): Boolean =
        allowGradleTasks.any { pattern -> globToRegex(pattern).matches(task) }

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

data class ReportsConfig(
    val outputDir: String = "build/droidagentkit",
)

data class RedactionConfig(
    val enabled: Boolean = true,
    val extraPatterns: List<String> = emptyList(),
)

object DroidAgentConfigLoader {
    fun load(projectRoot: Path): DroidAgentConfig {
        val path = projectRoot.resolve(".droidagentkit/config.yaml")
        if (!path.exists()) return DroidAgentConfig.default()

        val lines = Files.readAllLines(path)
        var section = ""
        var projectName = "inferred"
        val allowGradleTasks = mutableListOf<String>()
        var allowAdbInput = false
        var allowAppInstall = true
        var allowEmulatorStart = false
        var maxCommandSeconds = 600L
        var outputDir = "build/droidagentkit"
        var redactionEnabled = true
        val extraPatterns = mutableListOf<String>()
        var listTarget = ""

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isBlank() || line.startsWith("#")) continue
            if (!rawLine.startsWith(" ") && line.endsWith(":")) {
                section = line.removeSuffix(":")
                listTarget = ""
                continue
            }
            if (line.endsWith(":")) {
                listTarget = "$section.${line.removeSuffix(":")}"
                continue
            }
            if (line.startsWith("- ")) {
                val value = line.removePrefix("- ").unquote()
                when (listTarget) {
                    "safety.allowGradleTasks" -> allowGradleTasks.add(value)
                    "redaction.extraPatterns" -> extraPatterns.add(value)
                }
                continue
            }
            val key = line.substringBefore(":", missingDelimiterValue = "").trim()
            val value = line.substringAfter(":", missingDelimiterValue = "").trim().unquote()
            when ("$section.$key") {
                "project.name" -> projectName = value
                "safety.allowAdbInput" -> allowAdbInput = value.toBoolean()
                "safety.allowAppInstall" -> allowAppInstall = value.toBoolean()
                "safety.allowEmulatorStart" -> allowEmulatorStart = value.toBoolean()
                "safety.maxCommandSeconds" -> maxCommandSeconds = value.toLong()
                "reports.outputDir" -> outputDir = value
                "redaction.enabled" -> redactionEnabled = value.toBoolean()
            }
        }

        val safety = SafetyConfig(
            allowGradleTasks = allowGradleTasks.ifEmpty { SafetyConfig().allowGradleTasks },
            allowAdbInput = allowAdbInput,
            allowAppInstall = allowAppInstall,
            allowEmulatorStart = allowEmulatorStart,
            maxCommandSeconds = maxCommandSeconds,
        )
        return DroidAgentConfig(
            project = ProjectConfig(projectName),
            safety = safety,
            reports = ReportsConfig(outputDir),
            redaction = RedactionConfig(redactionEnabled, extraPatterns),
        )
    }

    private fun String.unquote(): String = trim().removeSurrounding("\"").removeSurrounding("'")
}
