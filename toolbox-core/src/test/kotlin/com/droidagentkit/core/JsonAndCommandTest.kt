package com.droidagentkit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class JsonAndCommandTest {
    @Test
    fun `tool result serializes stable schema and status`() {
        val result = ToolResult(
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
        val runner = ProcessRunner(
            redactor = Redactor(DroidAgentConfig.default().redaction),
            artifactWriter = ArtifactWriter(outputDir),
        )

        val result = runner.run(
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
    fun `json map serialization uses stable alphabetical key order`() {
        val map = mapOf("z" to "last", "a" to "first", "m" to "middle")

        val json = Json.write(map)

        val indexA = json.indexOf("\"a\"")
        val indexM = json.indexOf("\"m\"")
        val indexZ = json.indexOf("\"z\"")
        assertTrue(indexA < indexM)
        assertTrue(indexM < indexZ)
    }
}
