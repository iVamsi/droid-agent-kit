package com.droidagentkit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ConfigAndSafetyTest {
    @Test
    fun `default config allows unit lint and debug assemble tasks only`() {
        val safety = DroidAgentConfig.default().safety

        assertTrue(safety.isGradleTaskAllowed(":app:testDebugUnitTest"))
        assertTrue(safety.isGradleTaskAllowed(":feature:lintDebug"))
        assertTrue(safety.isGradleTaskAllowed(":app:assembleDemoDebug"))
        assertFalse(safety.isGradleTaskAllowed(":app:connectedDebugAndroidTest"))
        assertFalse(safety.isGradleTaskAllowed("clean"))
    }

    @Test
    fun `config loader reads simple yaml overrides`() {
        val dir = Files.createTempDirectory("dak-config")
        val config = dir.resolve(".droidagentkit/config.yaml")
        Files.createDirectories(config.parent)
        Files.writeString(
            config,
            """
            schemaVersion: 1
            project:
              name: demo
            safety:
              allowGradleTasks:
                - ":app:connectedDebugAndroidTest"
              allowAppInstall: false
              maxCommandSeconds: 42
            reports:
              outputDir: "out/reports"
            redaction:
              enabled: false
              extraPatterns:
                - "PRIVATE_[A-Z]+"
            """.trimIndent(),
        )

        val loaded = DroidAgentConfigLoader.load(dir)

        assertEquals("demo", loaded.project.name)
        assertTrue(loaded.safety.isGradleTaskAllowed(":app:connectedDebugAndroidTest"))
        assertFalse(loaded.safety.allowAppInstall)
        assertEquals(42, loaded.safety.maxCommandSeconds)
        assertEquals("out/reports", loaded.reports.outputDir)
        assertFalse(loaded.redaction.enabled)
        assertEquals(listOf("PRIVATE_[A-Z]+"), loaded.redaction.extraPatterns)
    }

    @Test
    fun `redactor hides common secrets and reports what changed`() {
        val redactor = Redactor(DroidAgentConfig.default().redaction)

        val result = redactor.redact(
            """
            Authorization: Bearer abc.def.ghi
            apiKey = "AIzaSyA-ExampleSecret"
            STORE_PASSWORD=swordfish
            harmless=value
            """.trimIndent(),
        )

        assertTrue(result.text.contains("Authorization: Bearer [REDACTED]"))
        assertTrue(result.text.contains("apiKey = \"[REDACTED]\""))
        assertTrue(result.text.contains("STORE_PASSWORD=[REDACTED]"))
        assertTrue(result.applied.contains("authorization-bearer"))
        assertTrue(result.applied.contains("google-api-key"))
        assertTrue(result.applied.contains("password-assignment"))
    }
}
