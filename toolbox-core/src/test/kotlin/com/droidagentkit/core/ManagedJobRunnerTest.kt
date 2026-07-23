package com.droidagentkit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ManagedJobRunnerTest {
    private fun runner(dir: java.nio.file.Path): InProcessManagedJobRunner {
        val writer = ArtifactWriter(dir)
        val redactor = Redactor(DroidAgentConfig.default().redaction)
        return InProcessManagedJobRunner(ProcessRunner(redactor, writer), maxReadOnlyConcurrency = 2, ttlSeconds = 60)
    }

    private fun authorizedRequest(
        serial: String? = null,
        mutating: Boolean = false,
    ): AuthorizedOperation =
        AuthorizedOperation(
            OperationRequest(
                operationId = "job",
                requiredCapabilities = emptySet(),
                destructive = false,
                mutating = mutating,
                deviceSerial = serial,
            ),
        )

    private fun spec(
        id: String,
        command: List<String>,
        operation: AuthorizedOperation,
        timeoutSeconds: Long = 5,
    ): ManagedJobSpec =
        ManagedJobSpec(
            id = id,
            operation = operation,
            command = CommandSpec(id, command, System.getProperty("user.dir"), false, false, timeoutSeconds, OutputMode.TEXT),
            timeoutSeconds = timeoutSeconds,
        )

    @Test
    fun `start reports running and final status for a fast command`() {
        val dir = Files.createTempDirectory("dak-job-fast")
        val r = runner(dir)
        val op = authorizedRequest()
        val snapshot = r.start(spec("fast", listOf("true"), op))
        assertEquals(JobState.RUNNING, snapshot.state)
        Thread.sleep(300)
        val final = r.status("fast")
        assertTrue("expected success but was ${final.state}", final.state == JobState.SUCCEEDED || final.state == JobState.RUNNING)
        r.shutdown()
    }

    @Test
    fun `cancel stops a long running job`() {
        val dir = Files.createTempDirectory("dak-job-cancel")
        val r = runner(dir)
        val op = authorizedRequest()
        r.start(spec("sleep", listOf("sleep", "30"), op, timeoutSeconds = 30))
        Thread.sleep(200)
        val cancelled = r.cancel("sleep")
        assertEquals(JobState.CANCELLED, cancelled.state)
        r.shutdown()
    }

    @Test
    fun `second mutating job on same device is rejected as busy`() {
        val dir = Files.createTempDirectory("dak-job-lock")
        val r = runner(dir)
        val op = authorizedRequest(serial = "emulator-5554", mutating = true)
        r.start(spec("long1", listOf("sleep", "30"), op, timeoutSeconds = 30))
        Thread.sleep(200)
        val second = r.start(spec("long2", listOf("true"), op, timeoutSeconds = 5))
        assertEquals(JobState.PENDING, second.state)
        assertTrue(second.warnings.contains("device-busy"))
        r.cancel("long1")
        r.shutdown()
    }

    @Test
    fun `unknown job id reports expired`() {
        val dir = Files.createTempDirectory("dak-job-unknown")
        val r = runner(dir)
        val snapshot = r.status("does-not-exist")
        assertEquals(JobState.EXPIRED, snapshot.state)
        assertTrue(snapshot.warnings.contains("unknown-job"))
        r.shutdown()
    }
}
