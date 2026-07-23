package com.droidagentkit.device

import com.droidagentkit.core.ArtifactRef
import com.droidagentkit.core.ArtifactSensitivity
import com.droidagentkit.core.ArtifactType
import com.droidagentkit.core.AuthorizationDecision
import com.droidagentkit.core.CommandSpec
import com.droidagentkit.core.DiagnosticFinding
import com.droidagentkit.core.DroidAgentConfig
import com.droidagentkit.core.ManagedJobRunner
import com.droidagentkit.core.OperationRequest
import com.droidagentkit.core.ToolResult
import java.nio.file.Path

interface DeviceToolContext {
    val config: DroidAgentConfig

    fun resolveRoot(arguments: Map<String, Any?>): Path

    fun run(
        root: Path,
        spec: CommandSpec,
    ): ToolResult

    fun artifactOutputDir(root: Path): Path

    fun registerExistingArtifact(
        root: Path,
        file: Path,
        type: ArtifactType,
        description: String,
        sensitivity: ArtifactSensitivity,
    ): ArtifactRef

    fun resultMap(result: ToolResult): Map<String, Any>

    fun resultMapWithFindings(
        result: ToolResult,
        findings: List<DiagnosticFinding>,
    ): Map<String, Any>

    fun jobRunner(): ManagedJobRunner

    fun authorize(request: OperationRequest): AuthorizationDecision

    fun safeId(value: String): String
}
