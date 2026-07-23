package com.droidagentkit.auditor

import com.droidagentkit.core.ArtifactRef
import com.droidagentkit.core.CommandSpec
import com.droidagentkit.core.Severity
import com.droidagentkit.inspector.AndroidModuleSummary
import com.droidagentkit.inspector.ProjectSupport

enum class ReadinessLevel {
    AGENT_READY,
    USABLE_WITH_REVIEW,
    SMALL_TASKS_ONLY,
    UNSAFE_FOR_AUTONOMY,
}

enum class ReadinessProfile {
    ANDROID_APP,
    ANDROID_LIBRARY,
    ANDROID_KMP_LIBRARY,
    JVM_TOOLING,
    MIXED_REPOSITORY,
}

data class ProjectSummary(
    val name: String,
    val rootPath: String,
    val support: ProjectSupport,
)

data class RecommendedAction(
    val id: String,
    val title: String,
    val command: String?,
)

/**
 * Server-side capability summary surfaced in report bundles. Reflects the DroidAgentKit MCP
 * server's own configuration (not the target project): which tool groups are exposed, which
 * capabilities are enabled, what optional executables are configured, and which dangerous flags
 * are on. Capability enablement is reported as a fact, never rewarded in readiness scoring.
 */
data class CapabilitySummary(
    val exposedToolGroups: List<String>,
    val enabledCapabilities: List<String>,
    val dangerousFlags: List<String>,
    val optionalExecutables: Map<String, String>,
    val prerequisites: List<String>,
)

data class ReadinessRisk(
    val id: String,
    val severity: Severity,
    val title: String,
    val evidence: List<String>,
    val fix: String,
    val applicability: String = "all",
    val confidence: String = "declared",
    val source: String = "DroidAgentKit project policy",
)

data class ReadinessReport(
    val schemaVersion: String = "1.0",
    val project: ProjectSummary,
    val score: Int,
    val level: ReadinessLevel,
    val commandMatrix: List<CommandSpec>,
    val moduleMap: List<AndroidModuleSummary>,
    val risks: List<ReadinessRisk>,
    val generatedDocuments: List<ArtifactRef> = emptyList(),
    val recommendedActions: List<RecommendedAction>,
    val profile: ReadinessProfile = ReadinessProfile.MIXED_REPOSITORY,
    val policyVersion: String = "2026-07-11",
    val capabilitySummary: CapabilitySummary? = null,
)
