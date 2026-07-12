package com.droidagentkit.auditor

import com.droidagentkit.inspector.AndroidProjectInspector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ReadinessAuditorTest {
    @Test
    fun `auditor scores agent-ready repo and generates AGENTS content`() {
        val root = sampleAndroidProject()
        Files.writeString(root.resolve("AGENTS.md"), "# Existing human instructions\n\nRun tests before changing code.\n")
        Files.createDirectories(root.resolve(".github/workflows"))
        Files.writeString(root.resolve(".github/workflows/ci.yml"), "name: CI\nrun: ./gradlew :app:testDebugUnitTest\n")

        val report = ReadinessAuditor(AndroidProjectInspector()).audit(root)
        val agents = AgentsDocumentGenerator().generate(report)

        assertTrue(report.score >= 75)
        assertEquals(ReadinessLevel.USABLE_WITH_REVIEW, report.level)
        assertTrue(agents.contains("Safe Commands"))
        assertTrue(agents.contains(":app:testDebugUnitTest"))
        assertTrue(report.risks.none { it.id == "missing-agent-instructions" })
    }

    @Test
    fun `writer does not overwrite existing AGENTS by default`() {
        val root = sampleAndroidProject()
        Files.writeString(root.resolve("AGENTS.md"), "# Keep me\n")
        val report = ReadinessAuditor(AndroidProjectInspector()).audit(root)

        val written = AgentDocumentWriter().write(root, report, mergeAgents = false)

        assertTrue(Files.exists(root.resolve("AGENTS.generated.md")))
        assertEquals("# Keep me\n", Files.readString(root.resolve("AGENTS.md")))
        assertTrue(written.any { it.path.endsWith("AGENTS.generated.md") })
    }

    @Test
    fun `auditor flags missing tests and secrets`() {
        val root = Files.createTempDirectory("dak-risky")
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"Risky\"\ninclude(\":app\")")
        Files.createDirectories(root.resolve("app"))
        Files.writeString(root.resolve("app/build.gradle.kts"), "plugins { id(\"com.android.application\") }")
        Files.writeString(root.resolve("local.properties"), "apiKey=AIzaSyA-ExampleSecret")

        val report = ReadinessAuditor(AndroidProjectInspector()).audit(root)

        assertTrue(report.score < 75)
        assertTrue(report.risks.any { it.id == "missing-agent-instructions" })
        assertTrue(report.risks.any { it.id == "possible-secret" })
        assertFalse(report.risks.joinToString("\n") { it.evidence.joinToString() }.contains("AIzaSyA-ExampleSecret"))
    }

    @Test
    fun `secret scanner skips files inside build directories`() {
        val root = Files.createTempDirectory("dak-scanner-skip")
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"Skip\"\n")
        // Secret in build/ — should be ignored
        val buildSecrets = root.resolve("build/outputs")
        Files.createDirectories(buildSecrets)
        Files.writeString(buildSecrets.resolve("secret.properties"), "STORE_PASSWORD=shouldbeskipped")
        // Secret outside build/ — should be caught
        Files.writeString(root.resolve("local.properties"), "STORE_PASSWORD=shouldbecaught")

        val report = ReadinessAuditor(AndroidProjectInspector()).audit(root)

        val evidence =
            report.risks
                .filter { it.id == "possible-secret" }
                .flatMap { it.evidence }
                .joinToString()
        assertTrue(evidence.contains("local.properties"))
        assertFalse(evidence.contains("build/outputs"))
    }

    @Test
    fun `auditor ignores unreadable binary files while scanning visual hooks`() {
        val root = sampleAndroidProject()
        Files.write(root.resolve(".DS_Store"), byteArrayOf(0xFF.toByte(), 0xFE.toByte()))

        val report = ReadinessAuditor(AndroidProjectInspector()).audit(root)

        assertTrue(report.score >= 0)
    }

    @Test
    fun `auditor awards points and skips risk for static analysis config`() {
        val root = sampleAndroidProject()
        Files.writeString(root.resolve("detekt.yml"), "build:\n  maxIssues: 0\n")

        val withDetekt = ReadinessAuditor(AndroidProjectInspector()).audit(root)
        val withoutDetekt = ReadinessAuditor(AndroidProjectInspector()).audit(sampleAndroidProject())

        assertTrue(withDetekt.score > withoutDetekt.score)
        assertTrue(withDetekt.risks.none { it.id == "missing-static-analysis" })
        assertTrue(withoutDetekt.risks.any { it.id == "missing-static-analysis" })
    }

    @Test
    fun `auditor awards points for proguard rules presence`() {
        val root = sampleAndroidProject()
        Files.writeString(root.resolve("app/proguard-rules.pro"), "-keep class * { *; }\n")

        val report = ReadinessAuditor(AndroidProjectInspector()).audit(root)

        assertTrue(report.risks.none { it.id == "missing-proguard" })
    }

    @Test
    fun `auditor surfaces missing version catalog as named risk`() {
        val root = Files.createTempDirectory("dak-no-catalog")
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"NoCatalog\"\ninclude(\":app\")")
        Files.createDirectories(root.resolve("app"))
        Files.writeString(root.resolve("app/build.gradle.kts"), "plugins { id(\"com.android.application\") }")

        val report = ReadinessAuditor(AndroidProjectInspector()).audit(root)

        assertTrue(report.risks.any { it.id == "missing-version-catalog" })
    }

    @Test
    fun `auditor uses JVM tooling profile without app release penalties`() {
        val root = Files.createTempDirectory("dak-jvm-tooling")
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"Tools\"\ninclude(\":cli\")")
        Files.createDirectories(root.resolve("cli/src/test/kotlin"))
        Files.writeString(root.resolve("cli/build.gradle.kts"), "plugins { kotlin(\"jvm\") }")

        val report = ReadinessAuditor(AndroidProjectInspector()).audit(root)

        assertEquals(ReadinessProfile.JVM_TOOLING, report.profile)
        assertEquals("2026-07-11", report.policyVersion)
        assertTrue(report.commandMatrix.any { it.command.last() == ":cli:test" })
        assertTrue(report.risks.none { it.id == "missing-proguard" })
        assertTrue(report.risks.none { it.id == "missing-baseline-profile" })
    }

    private fun sampleAndroidProject() =
        Files.createTempDirectory("dak-ready").also { root ->
            Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"Ready\"\ninclude(\":app\")")
            Files.createDirectories(root.resolve("app/src/test/java"))
            Files.writeString(
                root.resolve("app/build.gradle.kts"),
                "plugins { id(\"com.android.application\") }\nandroid { namespace = \"com.example.ready\" }",
            )
        }
}
