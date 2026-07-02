package com.droidagentkit.mcp.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DependencyVersionCheckerTest {
    @Test
    fun `flags same coordinate declared with different versions across modules`() {
        val root = Files.createTempDirectory("dak-dep-drift")
        Files.createDirectories(root.resolve("app"))
        Files.createDirectories(root.resolve("core"))
        Files.writeString(root.resolve("app/build.gradle.kts"), "implementation(\"com.squareup.okhttp3:okhttp:4.11.0\")")
        Files.writeString(root.resolve("core/build.gradle.kts"), "implementation(\"com.squareup.okhttp3:okhttp:4.12.0\")")

        val findings = DependencyVersionChecker.check(root)

        assertEquals(1, findings.size)
        assertEquals("dependency_drift", findings[0].category)
        assertTrue(findings[0].title.contains("com.squareup.okhttp3:okhttp"))
    }

    @Test
    fun `does not flag consistent versions`() {
        val root = Files.createTempDirectory("dak-dep-consistent")
        Files.createDirectories(root.resolve("app"))
        Files.createDirectories(root.resolve("core"))
        Files.writeString(root.resolve("app/build.gradle.kts"), "implementation(\"com.squareup.okhttp3:okhttp:4.12.0\")")
        Files.writeString(root.resolve("core/build.gradle.kts"), "implementation(\"com.squareup.okhttp3:okhttp:4.12.0\")")

        val findings = DependencyVersionChecker.check(root)

        assertTrue(findings.isEmpty())
    }

    @Test
    fun `flags unused catalog library alias`() {
        val root = Files.createTempDirectory("dak-dep-catalog")
        Files.createDirectories(root.resolve("gradle"))
        Files.createDirectories(root.resolve("app"))
        Files.writeString(
            root.resolve("gradle/libs.versions.toml"),
            """
            [versions]
            okhttp = "4.12.0"
            unusedLib = "1.0.0"

            [libraries]
            okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
            orphan-lib = { module = "com.example:orphan", version.ref = "unusedLib" }
            """.trimIndent(),
        )
        Files.writeString(root.resolve("app/build.gradle.kts"), "implementation(libs.okhttp)")

        val findings = DependencyVersionChecker.check(root)

        assertTrue(findings.any { it.title.contains("orphan-lib") })
    }

    @Test
    fun `does not flag when no version catalog is present`() {
        val root = Files.createTempDirectory("dak-dep-no-catalog")
        Files.createDirectories(root.resolve("app"))
        Files.writeString(root.resolve("app/build.gradle.kts"), "implementation(\"com.squareup.okhttp3:okhttp:4.12.0\")")

        val findings = DependencyVersionChecker.check(root)

        assertTrue(findings.isEmpty())
    }
}
