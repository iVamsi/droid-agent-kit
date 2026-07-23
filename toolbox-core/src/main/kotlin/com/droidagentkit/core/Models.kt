package com.droidagentkit.core

enum class OutputMode { TEXT, BINARY }

enum class ArtifactType(
    val wireName: String,
) {
    LOG("log"),
    REPORT("report"),
    SCREENSHOT("screenshot"),
    UI_HIERARCHY("ui_hierarchy"),
    JSON("json"),
    MARKDOWN("markdown"),
    ZIP("zip"),
    BUGREPORT("bugreport"),
    IMAGE_DIFF("image_diff"),
    PERFETTO_TRACE("perfetto_trace"),
    SQLITE_SNAPSHOT("sqlite_snapshot"),
    NETWORK_CAPTURE("network_capture"),
    OTHER("other"),
}

enum class ArtifactSensitivity(
    val wireName: String,
) {
    PUBLIC("public"),
    SENSITIVE("sensitive"),
}

enum class ResultStatus(
    val wireName: String,
) {
    SUCCESS("success"),
    PARTIAL("partial"),
    FAILED("failed"),
    BLOCKED("blocked"),
    UNSUPPORTED("unsupported"),
}

enum class Severity(
    val wireName: String,
) {
    INFO("info"),
    WARNING("warning"),
    ERROR("error"),
    CRITICAL("critical"),
}

data class ArtifactRef(
    val type: ArtifactType,
    val path: String,
    val mimeType: String,
    val description: String,
    val sizeBytes: Long = 0,
    val sha256: String = "",
    val sensitivity: ArtifactSensitivity = ArtifactSensitivity.PUBLIC,
    val opaqueId: String = "",
)

data class DiagnosticFinding(
    val category: String,
    val severity: Severity,
    val title: String,
    val detail: String,
    val location: String? = null,
)

data class CommandSpec(
    val id: String,
    val command: List<String>,
    val workingDirectory: String,
    val mutatesProject: Boolean,
    val requiresDevice: Boolean,
    val timeoutSeconds: Long,
    val outputMode: OutputMode = OutputMode.TEXT,
    val artifactType: ArtifactType? = null,
    val artifactName: String? = null,
    val sensitivity: ArtifactSensitivity = ArtifactSensitivity.PUBLIC,
    val maxCaptureBytes: Long = DEFAULT_BINARY_CAPTURE_BYTES,
) {
    private companion object {
        const val DEFAULT_BINARY_CAPTURE_BYTES = 256L * 1024 * 1024
    }
}

data class ToolResult(
    val schemaVersion: String = "1.0",
    val status: ResultStatus,
    val summary: String,
    val artifacts: List<ArtifactRef> = emptyList(),
    val redactionsApplied: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
)
