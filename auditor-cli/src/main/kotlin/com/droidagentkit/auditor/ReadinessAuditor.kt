package com.droidagentkit.auditor

import com.droidagentkit.core.ArtifactRef
import com.droidagentkit.core.ArtifactType
import com.droidagentkit.core.RedactionConfig
import com.droidagentkit.core.Redactor
import com.droidagentkit.core.Severity
import com.droidagentkit.inspector.AndroidProjectInspector
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

class ReadinessAuditor(
    private val inspector: AndroidProjectInspector,
) {
    fun audit(
        root: Path,
        redactPublic: Boolean = false,
    ): ReadinessReport {
        val project = inspector.inspect(root)
        val risks = mutableListOf<ReadinessRisk>()
        var score = 0

        if (project.commandMatrix.any { it.id.contains("assemble") || it.id.contains("test") }) {
            score += 20
        } else {
            risks +=
                risk(
                    "missing-build-commands",
                    Severity.ERROR,
                    "Build commands are not discoverable",
                    "No safe Gradle build or test command was inferred.",
                    "Add a Gradle wrapper and document test/build tasks.",
                )
        }

        if (project.modules.any { it.hasUnitTests || it.hasAndroidTests }) {
            score += 15
        } else {
            risks +=
                risk(
                    "missing-tests",
                    Severity.WARNING,
                    "No test source sets were detected",
                    "Expected src/test or src/androidTest in at least one module.",
                    "Add tests or document why verification is manual.",
                )
        }

        if (project.modules.isNotEmpty()) score += 15

        val instructionFiles = listOf("AGENTS.md", "CLAUDE.md", "GEMINI.md", ".cursorrules", ".github/copilot-instructions.md")
        val hasInstructions = instructionFiles.any { root.resolve(it).exists() }
        if (hasInstructions) {
            score += 10
        } else {
            risks +=
                risk(
                    "missing-agent-instructions",
                    Severity.WARNING,
                    "Agent instructions are missing",
                    "No AGENTS.md, CLAUDE.md, GEMINI.md, Cursor, or Copilot instruction file was found.",
                    "Generate AGENTS.md with droidagent audit --write-agents.",
                )
        }

        if (hasCi(root)) {
            score += 10
        } else {
            risks +=
                risk(
                    "missing-ci",
                    Severity.WARNING,
                    "CI workflow was not detected",
                    "No common CI workflow file was found.",
                    "Add CI that runs the same commands listed in AGENTS.md.",
                )
        }

        if (documentsDeviceExpectations(root)) score += 10

        val secretEvidence = findPossibleSecrets(root)
        if (secretEvidence.isEmpty()) {
            score += 10
        } else {
            risks +=
                ReadinessRisk(
                    id = "possible-secret",
                    severity = Severity.CRITICAL,
                    title = "Possible secret was detected",
                    evidence = secretEvidence,
                    fix = "Remove tracked secrets and rotate any exposed credentials.",
                )
        }

        if (hasVisualHooks(root)) score += 5

        // Version catalog (previously implicit, now named)
        if (root.resolve("gradle/libs.versions.toml").exists()) {
            score += 5
        } else {
            risks +=
                risk(
                    "missing-version-catalog",
                    Severity.INFO,
                    "No Gradle version catalog detected",
                    "No gradle/libs.versions.toml found.",
                    "Add a version catalog to centralise dependency versions.",
                )
        }

        // Static analysis config
        val hasStaticAnalysis =
            root.resolve("detekt.yml").exists() ||
                root.resolve(".detekt/config.yml").exists() ||
                project.modules.any { mod ->
                    val buildFile =
                        java.nio.file.Path
                            .of(mod.directory)
                            .resolve("build.gradle.kts")
                    buildFile.exists() && Files.readString(buildFile).contains("ktlint", ignoreCase = true)
                }
        if (hasStaticAnalysis) {
            score += 5
        } else {
            risks +=
                risk(
                    "missing-static-analysis",
                    Severity.WARNING,
                    "No static analysis config detected",
                    "No detekt.yml, .detekt/config.yml, or ktlint reference in any build file.",
                    "Add Detekt or ktlint to catch style and quality issues automatically.",
                )
        }

        // ProGuard rules
        val hasProguard =
            project.modules.any { mod ->
                java.nio.file.Path
                    .of(mod.directory)
                    .resolve("proguard-rules.pro")
                    .exists()
            }
        if (hasProguard) {
            score += 5
        } else {
            risks +=
                risk(
                    "missing-proguard",
                    Severity.WARNING,
                    "No ProGuard rules file detected",
                    "No proguard-rules.pro found in any module directory.",
                    "Add proguard-rules.pro and enable R8 minification in release builds.",
                )
        }

        // Baseline Profile
        val hasBaselineProfile =
            project.modules.any { mod ->
                val buildFile =
                    java.nio.file.Path
                        .of(mod.directory)
                        .resolve("build.gradle.kts")
                buildFile.exists() && Files.readString(buildFile).contains("baselineProfile", ignoreCase = true)
            }
        if (hasBaselineProfile) {
            score += 5
        } else {
            risks +=
                risk(
                    "missing-baseline-profile",
                    Severity.INFO,
                    "No Baseline Profile configuration detected",
                    "No baselineProfile keyword found in any module's build.gradle.kts.",
                    "Add a Baseline Profile to improve startup performance.",
                )
        }

        val level =
            when {
                score >= 90 -> ReadinessLevel.AGENT_READY
                score >= 75 -> ReadinessLevel.USABLE_WITH_REVIEW
                score >= 50 -> ReadinessLevel.SMALL_TASKS_ONLY
                else -> ReadinessLevel.UNSAFE_FOR_AUTONOMY
            }
        return ReadinessReport(
            project = ProjectSummary(project.projectName, project.rootPath, project.support),
            score = score.coerceIn(0, 100),
            level = level,
            commandMatrix = project.commandMatrix,
            moduleMap = project.modules,
            risks = risks,
            recommendedActions = risks.map { RecommendedAction(it.id, it.fix, null) },
        )
    }

    private fun risk(
        id: String,
        severity: Severity,
        title: String,
        evidence: String,
        fix: String,
    ) = ReadinessRisk(id, severity, title, listOf(evidence), fix)

    private fun hasCi(root: Path): Boolean =
        listOf(
            ".github/workflows",
            ".gitlab-ci.yml",
            "bitbucket-pipelines.yml",
        ).any { root.resolve(it).exists() }

    private fun documentsDeviceExpectations(root: Path): Boolean =
        listOf("AGENTS.md", "README.md").any { file ->
            val path = root.resolve(file)
            path.exists() && Files.readString(path).contains(Regex("(?i)(emulator|device|adb|androidTest)"))
        }

    private fun hasVisualHooks(root: Path): Boolean =
        Files.walk(root).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .filter { isScannable(it) }
                .limit(500)
                .anyMatch { path ->
                    val text = runCatching { Files.readString(path) }.getOrDefault("")
                    text.contains("droidAgentVisuals") || text.contains("Paparazzi") || text.contains("Roborazzi")
                }
        }

    private fun findPossibleSecrets(root: Path): List<String> {
        val redactor = Redactor(RedactionConfig())
        val evidence = mutableListOf<String>()
        Files.walk(root).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .filter { isScannable(it) && Files.size(it) <= 1_048_576L }
                .filter { it.fileName.toString() !in setOf("readiness-report.json", "readiness-report.md") }
                .limit(1000)
                .forEach { path ->
                    val text = runCatching { Files.readString(path) }.getOrDefault("")
                    val redacted = redactor.redact(text)
                    if (redacted.applied.isNotEmpty()) {
                        evidence += "${root.relativize(path)}: ${redacted.applied.joinToString()}"
                    }
                }
        }
        return evidence
    }

    private fun isScannable(path: Path): Boolean {
        val str = path.toString().replace('\\', '/')
        if (SKIP_DIRS.any { "/$it/" in str || str.endsWith("/$it") }) return false
        val ext =
            path.fileName
                .toString()
                .substringAfterLast('.', "")
                .lowercase()
        return path.fileName.toString() != ".DS_Store" && ext !in BINARY_EXTENSIONS
    }

    companion object {
        private val SKIP_DIRS = setOf("build", ".gradle", "node_modules", ".git", ".idea", ".cxx")
        private val BINARY_EXTENSIONS =
            setOf(
                "class",
                "jar",
                "aar",
                "so",
                "dylib",
                "dll",
                "png",
                "jpg",
                "jpeg",
                "gif",
                "webp",
                "keystore",
                "jks",
                "bks",
                "p12",
                "zip",
                "apk",
                "aab",
                "apks",
            )
    }
}

class AgentsDocumentGenerator {
    fun generate(report: ReadinessReport): String =
        buildString {
            appendLine("# AGENTS.md instructions")
            appendLine()
            appendLine("## Project Overview")
            appendLine("- Name: ${report.project.name}")
            appendLine("- Support: ${report.project.support}")
            appendLine("- Readiness: ${report.score}/100 (${report.level})")
            appendLine()
            appendLine("## Safe Commands")
            if (report.commandMatrix.isEmpty()) {
                appendLine("- No safe Gradle commands were inferred. Ask a maintainer before running builds.")
            } else {
                report.commandMatrix.forEach { command ->
                    appendLine("- `${command.command.joinToString(" ")}`")
                }
            }
            appendLine()
            appendLine("## Module Map")
            report.moduleMap.forEach { module ->
                appendLine("- `${module.path}`: ${module.type}, namespace=${module.namespace ?: "unknown"}")
            }
            appendLine()
            appendLine("## Agent Boundaries")
            appendLine("- Do not edit generated files, build outputs, keystores, or local environment files.")
            appendLine("- Prefer the safe commands above before claiming a change is complete.")
            appendLine("- Preserve existing human-authored instructions.")
            appendLine()
            appendLine("## Definition of Done")
            appendLine("- Relevant unit tests or lint tasks pass.")
            appendLine("- User-facing behavior is documented when it changes.")
            appendLine("- New secrets or machine-local paths are not committed.")
        }
}

class AgentDocumentWriter {
    fun write(
        root: Path,
        report: ReadinessReport,
        mergeAgents: Boolean,
    ): List<ArtifactRef> {
        val artifacts = mutableListOf<ArtifactRef>()
        val generated = AgentsDocumentGenerator().generate(report)
        val agentsPath = root.resolve("AGENTS.md")
        val target = if (agentsPath.exists() && !mergeAgents) root.resolve("AGENTS.generated.md") else agentsPath
        Files.writeString(target, generated)
        artifacts += ArtifactRef(ArtifactType.MARKDOWN, target.toString(), "text/markdown", "Generated agent instructions")

        val skillPath = root.resolve(".agents/skills/android-project/SKILL.md")
        Files.createDirectories(skillPath.parent)
        Files.writeString(
            skillPath,
            """
            ---
            name: android-project
            description: Repository-specific Android project commands and agent guidance.
            ---

            $generated
            """.trimIndent(),
        )
        artifacts += ArtifactRef(ArtifactType.MARKDOWN, skillPath.toString(), "text/markdown", "Repository Android skill")

        val configPath = root.resolve(".droidagentkit/config.yaml")
        Files.createDirectories(configPath.parent)
        if (!configPath.exists()) {
            Files.writeString(configPath, defaultConfigYaml())
        }
        artifacts += ArtifactRef(ArtifactType.OTHER, configPath.toString(), "text/yaml", "DroidAgentKit config")
        return artifacts
    }

    private fun defaultConfigYaml(): String =
        """
        schemaVersion: 1
        project:
          name: inferred
        safety:
          allowGradleTasks:
            - ":*:test*UnitTest"
            - ":*:lint*"
            - ":*:assemble*Debug"
          allowAdbInput: false
          allowAppInstall: true
          allowEmulatorStart: false
          maxCommandSeconds: 600
        reports:
          outputDir: "build/droidagentkit"
        redaction:
          enabled: true
          extraPatterns: []
        """.trimIndent()
}
