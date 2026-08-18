package com.droidagentkit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ConfigAndSafetyTest {
    @Test
    fun `default config allows safe verification tasks`() {
        val safety = DroidAgentConfig.default().safety

        assertTrue(safety.isGradleTaskAllowed(":app:testDebugUnitTest"))
        assertTrue(safety.isGradleTaskAllowed(":feature:lintDebug"))
        assertTrue(safety.isGradleTaskAllowed(":app:assembleDemoDebug"))
        assertTrue(safety.isGradleTaskAllowed(":app:connectedDebugAndroidTest"))
        assertTrue(safety.isGradleTaskAllowed(":app:pixelApi37DebugAndroidTest"))
        assertTrue(safety.isGradleTaskAllowed(":app:validateDebugScreenshotTest"))
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
              extraPatterns:
                - "PRIVATE_[A-Z]+"
            """.trimIndent(),
        )

        val result = DroidAgentConfigLoader.load(dir)

        assertTrue(result is ConfigLoadResult.Loaded)
        val loaded = (result as ConfigLoadResult.Loaded).config
        assertEquals("demo", loaded.project.name)
        assertTrue(loaded.safety.isGradleTaskAllowed(":app:connectedDebugAndroidTest"))
        assertFalse(loaded.safety.allowAppInstall)
        assertEquals(42, loaded.safety.maxCommandSeconds)
        assertEquals("out/reports", loaded.reports.outputDir)
        // Project files cannot disable redaction; default stays on.
        assertTrue(loaded.redaction.enabled)
        assertEquals(listOf("PRIVATE_[A-Z]+"), loaded.redaction.extraPatterns)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `config loader returns invalid for unsupported schema version`() {
        val dir = Files.createTempDirectory("dak-config-schema")
        val config = dir.resolve(".droidagentkit/config.yaml")
        Files.createDirectories(config.parent)
        Files.writeString(config, "schemaVersion: 2\nproject:\n  name: demo\n")

        val result = DroidAgentConfigLoader.load(dir)

        assertTrue(result is ConfigLoadResult.Invalid)
        val error = (result as ConfigLoadResult.Invalid).errors.single()
        assertEquals(1, error.line)
        assertEquals("schemaVersion", error.key)
        assertTrue(error.message.contains("schemaVersion 1"))
    }

    @Test
    fun `config loader returns invalid for non numeric schema version`() {
        val dir = Files.createTempDirectory("dak-config-schema-nan")
        val config = dir.resolve(".droidagentkit/config.yaml")
        Files.createDirectories(config.parent)
        Files.writeString(config, "schemaVersion: next\n")

        val result = DroidAgentConfigLoader.load(dir)

        assertTrue(result is ConfigLoadResult.Invalid)
        assertEquals("schemaVersion", (result as ConfigLoadResult.Invalid).errors.single().key)
    }

    @Test
    fun `config loader collects multiple value errors in one pass`() {
        val dir = Files.createTempDirectory("dak-config-multi")
        val config = dir.resolve(".droidagentkit/config.yaml")
        Files.createDirectories(config.parent)
        Files.writeString(
            config,
            """
            schemaVersion: 1
            safety:
              allowAppInstall: maybe
              maxCommandSeconds: soon
            """.trimIndent(),
        )

        val result = DroidAgentConfigLoader.load(dir)

        assertTrue(result is ConfigLoadResult.Invalid)
        val errors = (result as ConfigLoadResult.Invalid).errors
        assertEquals(2, errors.size)
        assertTrue(errors.any { it.key == "safety.allowAppInstall" })
        assertTrue(errors.any { it.key == "safety.maxCommandSeconds" })
    }

    @Test
    fun `config loader warns but succeeds on unknown key`() {
        val dir = Files.createTempDirectory("dak-config-unknown")
        val config = dir.resolve(".droidagentkit/config.yaml")
        Files.createDirectories(config.parent)
        Files.writeString(config, "schemaVersion: 1\nsafety:\n  saftey: true\n")

        val result = DroidAgentConfigLoader.load(dir)

        assertTrue(result is ConfigLoadResult.Loaded)
        val loaded = result as ConfigLoadResult.Loaded
        assertTrue(loaded.warnings.any { it.contains("safety.saftey") })
    }

    @Test
    fun `config loader rejects report output outside the project`() {
        val dir = Files.createTempDirectory("dak-config-output")
        val config = dir.resolve(".droidagentkit/config.yaml")
        Files.createDirectories(config.parent)
        Files.writeString(config, "reports:\n  outputDir: ../../outside\n")

        val result = DroidAgentConfigLoader.load(dir)

        assertTrue(result is ConfigLoadResult.Invalid)
        assertEquals("reports.outputDir", (result as ConfigLoadResult.Invalid).errors.single().key)
    }

    @Test
    fun `redactor hides common secrets and reports what changed`() {
        val redactor = Redactor(DroidAgentConfig.default().redaction)

        val result =
            redactor.redact(
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

    @Test
    fun `built-in rules stay fast on a long unbroken token`() {
        // Unbounded `[A-Z0-9_]*` either side of a keyword is ambiguous and goes quadratic: 50k
        // identifier characters stall redaction for over five seconds, and ProcessRunner feeds this
        // up to 10MB of attacker-influenced output.
        val redactor = Redactor(DroidAgentConfig.default().redaction)
        val hostile = "A".repeat(200_000) + "!"

        val started = System.nanoTime()
        val result = redactor.redact(hostile)
        val elapsedMs =
            java.util.concurrent.TimeUnit.NANOSECONDS
                .toMillis(System.nanoTime() - started)

        assertTrue("redaction took ${elapsedMs}ms on a 200k token", elapsedMs < 5_000)
        assertEquals(hostile, result.text)
    }

    @Test
    fun `redaction of a hostile extra pattern is bounded and reported`() {
        // Polynomial blowup reproduces reliably where classic exponential patterns do not, and it
        // escapes the static nested-quantifier screen entirely.
        val redactor =
            Redactor(RedactionConfig(enabled = true, extraPatterns = listOf(".*.*.*=.*")))

        val started = System.nanoTime()
        val result = redactor.redact("x".repeat(200_000))
        val elapsedMs =
            java.util.concurrent.TimeUnit.NANOSECONDS
                .toMillis(System.nanoTime() - started)

        assertTrue("redaction took ${elapsedMs}ms", elapsedMs < 5_000)
        assertTrue(result.warnings.any { it.startsWith("redaction-pattern-timeout:extra-0") })
    }

    @Test
    fun `built-in rules still apply when an extra pattern is abandoned`() {
        // The budget is per pattern, so the input has to be small enough that the built-in rules
        // finish far inside it while the catastrophic extra pattern still blows it. At 200KB the
        // built-ins alone can exceed 500ms on a loaded runner; at 2KB they need microseconds and the
        // exponential pattern still never finishes.
        val redactor = Redactor(RedactionConfig(enabled = true, extraPatterns = listOf(".*.*.*=.*")))

        val result = redactor.redact("x".repeat(2_000) + "\nAuthorization: Bearer abc.def.ghi")

        // The point of the test: a user-supplied pattern that misbehaves must never cost us the
        // built-in secret rules, because that would silently leak the very thing redaction exists
        // to catch. The budget is per pattern, which is what makes this hold.
        assertTrue(
            "bearer token must still be redacted: ${result.text.takeLast(80)}",
            result.text.contains("Authorization: Bearer [REDACTED]"),
        )
        assertTrue(result.applied.contains("authorization-bearer"))
    }

    @Test
    fun `an abandoned extra pattern is reported rather than hidden`() {
        val redactor = Redactor(RedactionConfig(enabled = true, extraPatterns = listOf(".*.*.*=.*")))

        val result = redactor.redact("x".repeat(2_000) + "\nAuthorization: Bearer abc.def.ghi")

        assertTrue(
            "a skipped pattern must surface as a warning: ${result.warnings}",
            result.warnings.any { it.startsWith("redaction-pattern-") },
        )
    }

    @Test
    fun `redactor hides aws access key ids`() {
        val redactor = Redactor(DroidAgentConfig.default().redaction)

        val result = redactor.redact("aws_access_key_id=AKIAIOSFODNN7EXAMPLEOK")

        assertFalse(result.text.contains("AKIAIOSFODNN7EXAMPLEOK"))
        assertTrue(result.applied.contains("aws-access-key"))
    }

    @Test
    fun `redactor hides github classic personal access tokens`() {
        val redactor = Redactor(DroidAgentConfig.default().redaction)
        val token = "ghp_" + "A".repeat(36)

        val result = redactor.redact("GH_TOKEN=$token")

        assertFalse(result.text.contains(token))
        assertTrue(result.applied.contains("github-classic-token"))
    }

    @Test
    fun `redactor hides github fine grained tokens`() {
        val redactor = Redactor(DroidAgentConfig.default().redaction)
        val token = "github_pat_" + "B".repeat(82)

        val result = redactor.redact("token=$token")

        assertFalse(result.text.contains(token))
        assertTrue(result.applied.contains("github-fine-grained-token"))
    }

    @Test
    fun `redactor hides pem private key headers`() {
        val redactor = Redactor(DroidAgentConfig.default().redaction)

        val result = redactor.redact("-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEA...")

        assertFalse(result.text.contains("BEGIN RSA PRIVATE KEY"))
        assertTrue(result.applied.contains("pem-private-key"))
    }

    @Test
    fun `redactor hides firebase private key json fragment`() {
        val redactor = Redactor(DroidAgentConfig.default().redaction)
        val input = """{"type":"service_account","private_key":"-----BEGIN PRIVATE KEY-----\nMIIEvAIBADA"}"""

        val result = redactor.redact(input)

        assertFalse(result.text.contains("-----BEGIN PRIVATE KEY"))
        assertTrue(result.applied.contains("firebase-private-key"))
    }

    @Test
    fun `redactor hides generic key and secret assignments with at least 8 char values`() {
        val redactor = Redactor(DroidAgentConfig.default().redaction)

        val hit = redactor.redact("MY_API_KEY=supersecret123")
        val miss = redactor.redact("MY_KEY=short") // 5 chars — under threshold

        assertTrue(hit.applied.contains("generic-secret-assignment"))
        assertFalse(miss.applied.contains("generic-secret-assignment"))
    }

    @Test
    fun `config loader rejects nested-quantifier extraPatterns`() {
        val dir = Files.createTempDirectory("dak-config-redos")
        val config = dir.resolve(".droidagentkit/config.yaml")
        Files.createDirectories(config.parent)
        Files.writeString(
            config,
            """
            schemaVersion: 1
            redaction:
              extraPatterns:
                - "(a+)+"
            """.trimIndent(),
        )

        val result = DroidAgentConfigLoader.load(dir)

        assertTrue(result is ConfigLoadResult.Invalid)
        assertEquals("redaction.extraPatterns", (result as ConfigLoadResult.Invalid).errors.single().key)
    }
}
