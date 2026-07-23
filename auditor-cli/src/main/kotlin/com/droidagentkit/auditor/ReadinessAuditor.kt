package com.droidagentkit.auditor

import com.droidagentkit.core.ArtifactRef
import com.droidagentkit.core.ArtifactType
import com.droidagentkit.core.RedactionConfig
import com.droidagentkit.core.Redactor
import com.droidagentkit.core.Severity
import com.droidagentkit.inspector.AndroidModuleType
import com.droidagentkit.inspector.AndroidProjectInspector
import com.droidagentkit.inspector.CompatibilityStatus
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
        val profile = determineProfile(project.modules.map { it.type })
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

        if (profile == ReadinessProfile.ANDROID_APP || profile == ReadinessProfile.MIXED_REPOSITORY) {
            score += appReleaseReadinessScore(project.modules, risks)
        } else {
            score += 10
        }

        project.toolchain.findings
            .filter { it.status == CompatibilityStatus.OUTSIDE_DOCUMENTED_RANGE }
            .forEach { finding ->
                risks +=
                    ReadinessRisk(
                        id = "unsupported-${finding.component}",
                        severity = Severity.WARNING,
                        title = "Toolchain pair is outside the documented support range",
                        evidence = listOf(finding.detail),
                        fix = "Select versions inside the official compatibility table or document a tested best-effort lane.",
                        applicability = "all",
                        confidence = "declared",
                        source = finding.sourceUrl,
                    )
            }

        if (!root.resolve("gradle/verification-metadata.xml").exists()) {
            risks +=
                risk(
                    "missing-dependency-verification",
                    Severity.INFO,
                    "Gradle dependency verification is not configured",
                    "No gradle/verification-metadata.xml was found.",
                    "Bootstrap and review SHA-256/PGP dependency verification metadata.",
                )
        }

        val gradleProperties = root.resolve("gradle.properties")
        if (!gradleProperties.exists() || !Files.readString(gradleProperties).contains("org.gradle.configuration-cache=true")) {
            risks +=
                risk(
                    "configuration-cache-not-enabled",
                    Severity.INFO,
                    "Gradle configuration cache is not enabled",
                    "No persistent strict configuration-cache setting was detected.",
                    "Verify store and reuse twice, then enable org.gradle.configuration-cache=true.",
                )
        }

        val level =
            when {
                score >= 90 -> ReadinessLevel.AGENT_READY
                score >= 75 -> ReadinessLevel.USABLE_WITH_REVIEW
                score >= 50 -> ReadinessLevel.SMALL_TASKS_ONLY
                else -> ReadinessLevel.UNSAFE_FOR_AUTONOMY
            }
        val report =
            ReadinessReport(
                project = ProjectSummary(project.projectName, project.rootPath, project.support),
                score = score.coerceIn(0, 100),
                level = level,
                commandMatrix = project.commandMatrix,
                moduleMap = project.modules,
                risks = risks,
                recommendedActions = risks.map { RecommendedAction(it.id, it.fix, null) },
                profile = profile,
            )
        return if (redactPublic) redactPublic(report, root) else report
    }

    private fun redactPublic(
        report: ReadinessReport,
        root: Path,
    ): ReadinessReport {
        val home = System.getProperty("user.home") ?: ""
        val user = System.getProperty("user.name") ?: ""
        val rootAbs = root.toAbsolutePath().normalize().toString()
        val scrub: (String) -> String = { value ->
            var v = value
            if (rootAbs.isNotBlank()) v = v.replace(rootAbs, ".")
            if (home.isNotBlank()) v = v.replace(home, "~")
            if (user.isNotBlank()) v = v.replace(user, "[user]")
            v
                .replace(Regex("\\bemulator-\\d+\\b"), "[serial]")
                .replace(Regex("\\b\\d{1,3}(?:\\.\\d{1,3}){3}:\\d+\\b"), "[serial]")
        }
        return report.copy(
            project = report.project.copy(rootPath = "."),
            moduleMap =
                report.moduleMap.map { module ->
                    val modulePath = Path.of(module.directory).toAbsolutePath().normalize()
                    val rootPath = root.toAbsolutePath().normalize()
                    val relative =
                        runCatching { rootPath.relativize(modulePath).toString() }
                            .getOrElse { modulePath.fileName?.toString() ?: module.directory }
                    module.copy(directory = relative)
                },
            risks =
                report.risks.map { risk ->
                    risk.copy(evidence = risk.evidence.map(scrub))
                },
        )
    }

    private fun determineProfile(types: List<AndroidModuleType>): ReadinessProfile =
        when {
            types.any { it == AndroidModuleType.APPLICATION || it == AndroidModuleType.DYNAMIC_FEATURE } ->
                if (types.any { it == AndroidModuleType.KMP_ANDROID }) ReadinessProfile.MIXED_REPOSITORY else ReadinessProfile.ANDROID_APP
            types.any { it == AndroidModuleType.KMP_ANDROID } -> ReadinessProfile.ANDROID_KMP_LIBRARY
            types.any { it == AndroidModuleType.LIBRARY } -> ReadinessProfile.ANDROID_LIBRARY
            types.any { it == AndroidModuleType.JVM_TOOLING } -> ReadinessProfile.JVM_TOOLING
            types.isNotEmpty() -> ReadinessProfile.MIXED_REPOSITORY
            else -> ReadinessProfile.JVM_TOOLING
        }

    private fun appReleaseReadinessScore(
        modules: List<com.droidagentkit.inspector.AndroidModuleSummary>,
        risks: MutableList<ReadinessRisk>,
    ): Int {
        var score = 0
        val hasProguard = modules.any { Path.of(it.directory).resolve("proguard-rules.pro").exists() }
        if (hasProguard) {
            score += 5
        } else {
            risks +=
                risk(
                    "missing-proguard",
                    Severity.WARNING,
                    "No ProGuard rules file detected",
                    "No proguard-rules.pro found in an Android application module.",
                    "Review release shrinking and add project rules when the application requires them.",
                )
        }
        val hasBaselineProfile =
            modules.any { module ->
                val buildFile = Path.of(module.directory).resolve("build.gradle.kts")
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
                    "No baselineProfile keyword found in an Android application module.",
                    "Evaluate a Baseline Profile for startup and critical user journeys.",
                )
        }
        return score
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
            appendLine("- Profile: ${report.profile}")
            appendLine("- Readiness policy: ${report.policyVersion}")
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
            - ":*:*AndroidTest"
            - ":*:validate*ScreenshotTest"
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
