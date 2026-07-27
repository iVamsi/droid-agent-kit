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
    JVM_TOOLING,
    TEST_MODULE,
    UNKNOWN,
}

enum class EvidenceConfidence {
    DECLARED,
    INFERRED,
    UNKNOWN,
}

enum class KotlinIntegration {
    BUILT_IN,
    ANDROID_PLUGIN,
    MULTIPLATFORM,
    NONE,
    UNKNOWN,
}

enum class CompatibilityStatus {
    SUPPORTED,
    OUTSIDE_DOCUMENTED_RANGE,
    UNKNOWN,
}

data class CompatibilityFinding(
    val component: String,
    val version: String?,
    val status: CompatibilityStatus,
    val detail: String,
    val sourceUrl: String,
)

data class ToolchainSummary(
    val kotlinVersion: String? = null,
    val gradleVersion: String? = null,
    val agpVersion: String? = null,
    val jdkVersion: Int = Runtime.version().feature(),
    val evidenceVersion: String = "2026-07-11",
    val findings: List<CompatibilityFinding> = emptyList(),
)

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
    val buildTypes: List<String> = emptyList(),
    val productFlavors: List<String> = emptyList(),
    val pluginIds: List<String> = emptyList(),
    val kotlinIntegration: KotlinIntegration = KotlinIntegration.UNKNOWN,
    val compileSdk: Int? = null,
    val minSdk: Int? = null,
    val targetSdk: Int? = null,
    val sourceSets: List<String> = emptyList(),
    val hasScreenshotTests: Boolean = false,
    val managedDevices: List<String> = emptyList(),
    val managedDeviceGroups: List<String> = emptyList(),
    val confidence: EvidenceConfidence = EvidenceConfidence.UNKNOWN,
)

data class AndroidProjectReport(
    val projectName: String,
    val support: ProjectSupport,
    val rootPath: String,
    val modules: List<AndroidModuleSummary>,
    val versions: Map<String, String>,
    val commandMatrix: List<CommandSpec>,
    val warnings: List<String>,
    val toolchain: ToolchainSummary = ToolchainSummary(),
)
