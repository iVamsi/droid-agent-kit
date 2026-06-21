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

data class ReadinessRisk(
    val id: String,
    val severity: Severity,
    val title: String,
    val evidence: List<String>,
    val fix: String,
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
)
