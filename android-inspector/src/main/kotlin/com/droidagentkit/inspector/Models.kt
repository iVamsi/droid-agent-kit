package com.droidagentkit.inspector

import com.droidagentkit.core.CommandSpec

enum class ProjectSupport {
    SUPPORTED,
    PARTIAL,
    UNSUPPORTED,
}

enum class AndroidModuleType {
    APPLICATION,
    LIBRARY,
    DYNAMIC_FEATURE,
    KMP_ANDROID,
    UNKNOWN,
}

data class AndroidModuleSummary(
    val path: String,
    val directory: String,
    val type: AndroidModuleType,
    val namespace: String?,
    val packageName: String?,
    val launcherActivities: List<String>,
    val usesCompose: Boolean,
    val hasUnitTests: Boolean,
    val hasAndroidTests: Boolean,
    val moduleDependencies: List<String> = emptyList(),
)

data class AndroidProjectReport(
    val projectName: String,
    val support: ProjectSupport,
    val rootPath: String,
    val modules: List<AndroidModuleSummary>,
    val versions: Map<String, String>,
    val commandMatrix: List<CommandSpec>,
    val warnings: List<String>,
)
