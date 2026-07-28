package com.droidagentkit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class JsonAndCommandTest {
    @Test
    fun `tool result serializes stable schema and status`() {
        val result =
            ToolResult(
                status = ResultStatus.BLOCKED,
                summary = "Gradle task denied",
                artifacts = listOf(ArtifactRef(ArtifactType.LOG, "build/droidagentkit/gradle.log", "text/plain", "Full Gradle output")),
                redactionsApplied = listOf("authorization-bearer"),
                warnings = listOf("Task is not allowlisted"),
            )

        val json = Json.writeToolResult(result)

        assertTrue(json.contains("\"schemaVersion\":\"1.0\""))
        assertTrue(json.contains("\"status\":\"blocked\""))
        assertTrue(json.contains("\"type\":\"log\""))
        assertTrue(json.contains("\"redactionsApplied\":[\"authorization-bearer\"]"))
    }

    @Test
    fun `process runner captures output artifact and redacts summary`() {
        val outputDir = Files.createTempDirectory("dak-command")
        val runner =
            ProcessRunner(
                redactor = Redactor(DroidAgentConfig.default().redaction),
                artifactWriter = ArtifactWriter(outputDir),
            )

        val result =
            runner.run(
                CommandSpec(
                    id = "echo-secret",
                    command = listOf("/bin/sh", "-c", "echo 'Authorization: Bearer abc.def.ghi'"),
                    workingDirectory = outputDir.toString(),
                    mutatesProject = false,
                    requiresDevice = false,
                    timeoutSeconds = 10,
                ),
            )

        assertEquals(ResultStatus.SUCCESS, result.status)
        assertTrue(result.summary.contains("[REDACTED]"))
        assertEquals(1, result.artifacts.size)
        assertTrue(Files.exists(outputDir.resolve("echo-secret.log")))
    }

    @Test
    fun `process runner preserves binary output without text corruption`() {
        val outputDir = Files.createTempDirectory("dak-binary")
        val runner =
            ProcessRunner(
                redactor = Redactor(DroidAgentConfig.default().redaction),
                artifactWriter = ArtifactWriter(outputDir),
            )
        // PNG magic bytes — would be corrupted if decoded as UTF-8
        val tmpFile = outputDir.resolve("test.bin")
        val expectedBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        java.nio.file.Files
            .write(tmpFile, expectedBytes)

        val result =
            runner.run(
                CommandSpec(
                    id = "binary-cat",
                    command = listOf("/bin/sh", "-c", "cat ${tmpFile.toAbsolutePath()}"),
                    workingDirectory = outputDir.toString(),
                    mutatesProject = false,
                    requiresDevice = false,
                    timeoutSeconds = 10,
                    outputMode = OutputMode.BINARY,
                ),
            )

        assertEquals(ResultStatus.SUCCESS, result.status)
        assertEquals(1, result.artifacts.size)
        val written =
            java.nio.file.Files
                .readAllBytes(
                    java.nio.file.Path
                        .of(result.artifacts[0].path),
                )
        org.junit.Assert.assertArrayEquals(expectedBytes, written)
    }

    @Test
    fun `process runner drains verbose output while the process is still running`() {
        val outputDir = Files.createTempDirectory("dak-verbose")
        val runner = ProcessRunner(Redactor(DroidAgentConfig.default().redaction), ArtifactWriter(outputDir))

        val result =
            runner.run(
                CommandSpec(
                    id = "verbose",
                    command = listOf("/bin/sh", "-c", "yes line | head -n 100000"),
                    workingDirectory = outputDir.toString(),
                    mutatesProject = false,
                    requiresDevice = false,
                    timeoutSeconds = 5,
                ),
            )

        assertEquals(ResultStatus.SUCCESS, result.status)
        assertTrue(Files.size(outputDir.resolve("verbose.log")) > 64 * 1024)
    }

    @Test
    fun `json map serialization uses stable alphabetical key order`() {
        val map = mapOf("z" to "last", "a" to "first", "m" to "middle")

        val json = Json.write(map)

        val indexA = json.indexOf("\"a\"")
        val indexM = json.indexOf("\"m\"")
        val indexZ = json.indexOf("\"z\"")
        assertTrue(indexA < indexM)
        assertTrue(indexM < indexZ)
    }

    @Test
    fun `gradle environment scrubber removes option-injection vars and keeps PATH`() {
        val env =
            mutableMapOf(
                "PATH" to "/usr/bin",
                "GRADLE_OPTS" to "--init-script /tmp/evil.gradle",
                "JAVA_TOOL_OPTIONS" to "-javaagent:evil.jar",
                "HOME" to "/home/dev",
            )

        ProcessEnvironmentScrubber.scrubGradleOptionVars(env)

        assertEquals("/usr/bin", env["PATH"])
        assertEquals("/home/dev", env["HOME"])
        assertTrue(!env.containsKey("GRADLE_OPTS"))
        assertTrue(!env.containsKey("JAVA_TOOL_OPTIONS"))
    }
}
