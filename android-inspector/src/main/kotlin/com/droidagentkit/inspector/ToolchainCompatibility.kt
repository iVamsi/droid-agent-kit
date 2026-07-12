package com.droidagentkit.inspector

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

internal object ToolchainCompatibility {
    private const val KOTLIN_COMPATIBILITY_URL =
        "https://kotlinlang.org/docs/gradle-configure-project.html"
    private const val AGP_92_URL =
        "https://developer.android.com/build/releases/agp-9-2-0-release-notes"

    fun inspect(
        root: Path,
        versions: Map<String, String>,
    ): ToolchainSummary {
        val kotlin = versionFor(versions, "kotlin") ?: parseRootPluginVersion(root, "org.jetbrains.kotlin")
        val agp = versionFor(versions, "agp", "androidGradlePlugin") ?: parseRootPluginVersion(root, "com.android")
        val gradle = parseGradleVersion(root)
        val findings = mutableListOf<CompatibilityFinding>()

        findings += kotlinGradleFinding(kotlin, gradle)
        if (agp != null) findings += agpFinding(agp, gradle, Runtime.version().feature())

        return ToolchainSummary(
            kotlinVersion = kotlin,
            gradleVersion = gradle,
            agpVersion = agp,
            findings = findings,
        )
    }

    private fun kotlinGradleFinding(
        kotlin: String?,
        gradle: String?,
    ): CompatibilityFinding {
        if (kotlin == null || gradle == null) {
            return CompatibilityFinding(
                component = "kotlin-gradle",
                version = listOfNotNull(kotlin, gradle).joinToString(" / ").ifBlank { null },
                status = CompatibilityStatus.UNKNOWN,
                detail = "Kotlin and Gradle versions could not both be proven from project files.",
                sourceUrl = KOTLIN_COMPATIBILITY_URL,
            )
        }
        val range =
            when {
                kotlin == "2.4.0" -> VersionRange("7.6.3", "9.5.0")
                kotlin == "2.3.20" || kotlin == "2.3.21" -> VersionRange("7.6.3", "9.3.0")
                else -> null
            }
        if (range == null) {
            return CompatibilityFinding(
                component = "kotlin-gradle",
                version = "$kotlin / $gradle",
                status = CompatibilityStatus.UNKNOWN,
                detail = "No embedded official compatibility range exists for Kotlin $kotlin.",
                sourceUrl = KOTLIN_COMPATIBILITY_URL,
            )
        }
        val supported = compareVersions(gradle, range.minimum) >= 0 && compareVersions(gradle, range.maximum) <= 0
        return CompatibilityFinding(
            component = "kotlin-gradle",
            version = "$kotlin / $gradle",
            status = if (supported) CompatibilityStatus.SUPPORTED else CompatibilityStatus.OUTSIDE_DOCUMENTED_RANGE,
            detail = "Kotlin $kotlin is documented as fully supported with Gradle ${range.minimum}–${range.maximum}.",
            sourceUrl = KOTLIN_COMPATIBILITY_URL,
        )
    }

    private fun agpFinding(
        agp: String,
        gradle: String?,
        jdk: Int,
    ): CompatibilityFinding {
        if (!agp.startsWith("9.2.")) {
            return CompatibilityFinding(
                component = "agp",
                version = agp,
                status = CompatibilityStatus.UNKNOWN,
                detail = "No embedded official compatibility range exists for AGP $agp.",
                sourceUrl = AGP_92_URL,
            )
        }
        if (gradle == null) {
            return CompatibilityFinding(
                component = "agp",
                version = agp,
                status = CompatibilityStatus.UNKNOWN,
                detail = "AGP 9.2 requires Gradle 9.4.1 or newer and JDK 17; Gradle was not detected.",
                sourceUrl = AGP_92_URL,
            )
        }
        val supported = compareVersions(gradle, "9.4.1") >= 0 && jdk >= 17
        return CompatibilityFinding(
            component = "agp",
            version = agp,
            status = if (supported) CompatibilityStatus.SUPPORTED else CompatibilityStatus.OUTSIDE_DOCUMENTED_RANGE,
            detail = "AGP 9.2 requires Gradle 9.4.1 or newer and JDK 17; detected Gradle $gradle and JDK $jdk.",
            sourceUrl = AGP_92_URL,
        )
    }

    private fun versionFor(
        versions: Map<String, String>,
        vararg keys: String,
    ): String? = keys.firstNotNullOfOrNull { versions[it] }

    private fun parseGradleVersion(root: Path): String? {
        val wrapper = root.resolve("gradle/wrapper/gradle-wrapper.properties")
        if (!wrapper.exists()) return null
        return Regex("gradle-([0-9]+(?:\\.[0-9]+){1,2})-(?:bin|all)\\.zip")
            .find(Files.readString(wrapper))
            ?.groupValues
            ?.get(1)
    }

    private fun parseRootPluginVersion(
        root: Path,
        pluginPrefix: String,
    ): String? =
        listOf(root.resolve("build.gradle.kts"), root.resolve("build.gradle"))
            .firstOrNull { it.exists() }
            ?.let(Files::readString)
            ?.let { text ->
                Regex("(?:id\\s*\\(\\s*[\"']$pluginPrefix[^\"']*[\"']\\s*\\)|kotlin\\s*\\([^)]*\\))\\s*version\\s*[\"']([^\"']+)[\"']")
                    .find(text)
                    ?.groupValues
                    ?.get(1)
            }

    private fun compareVersions(
        left: String,
        right: String,
    ): Int {
        val a = left.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val b = right.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        return (0 until maxOf(a.size, b.size))
            .firstNotNullOfOrNull { index ->
                val difference = (a.getOrNull(index) ?: 0).compareTo(b.getOrNull(index) ?: 0)
                difference.takeIf { it != 0 }
            } ?: 0
    }

    private data class VersionRange(
        val minimum: String,
        val maximum: String,
    )
}
