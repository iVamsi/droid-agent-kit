package com.droidagentkit.core

enum class ArtifactType(val wireName: String) {
    LOG("log"),
    REPORT("report"),
    SCREENSHOT("screenshot"),
    UI_HIERARCHY("ui_hierarchy"),
    JSON("json"),
    MARKDOWN("markdown"),
    ZIP("zip"),
    IMAGE_DIFF("image_diff"),
    OTHER("other"),
}

enum class ResultStatus(val wireName: String) {
    SUCCESS("success"),
    PARTIAL("partial"),
    FAILED("failed"),
    BLOCKED("blocked"),
    UNSUPPORTED("unsupported"),
}

enum class Severity(val wireName: String) {
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
)

data class CommandSpec(
    val id: String,
    val command: List<String>,
    val workingDirectory: String,
    val mutatesProject: Boolean,
    val requiresDevice: Boolean,
    val timeoutSeconds: Long,
)

data class ToolResult(
    val schemaVersion: String = "1.0",
    val status: ResultStatus,
    val summary: String,
    val artifacts: List<ArtifactRef> = emptyList(),
    val redactionsApplied: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
)
