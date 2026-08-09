package com.droidagentkit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Cancellation only matters if the *process* dies. Asserting that `run` returned promptly would
 * pass even while a Gradle daemon kept building in the background, holding the build lock — which
 * is the exact failure this is meant to prevent.
 */
class ProcessRunnerCancellationTest {
    /**
     * These exercise real process spawning, so they need a real shell. `/bin/sh` is the portable
     * choice on POSIX; a batch equivalent would be testing a different thing, so on Windows they
     * skip rather than pretend.
     */
    @org.junit.Before
    fun requirePosixShell() {
        org.junit.Assume.assumeTrue(
            "spawns /bin/sh",
            !System.getProperty("os.name").startsWith("Windows"),
        )
    }

    private fun runner(dir: java.nio.file.Path) =
        ProcessRunner(
            redactor = Redactor(RedactionConfig()),
            artifactWriter = ArtifactWriter(dir),
        )

    private fun sleepSpec(seconds: Int) =
        CommandSpec(
            id = "sleep-probe",
            command = listOf("/bin/sh", "-c", "sleep $seconds"),
            workingDirectory = System.getProperty("user.dir"),
            mutatesProject = false,
            requiresDevice = false,
            timeoutSeconds = 120,
        )

    @Test
    fun `cancelling mid-run kills the process and returns promptly`() {
        val dir = Files.createTempDirectory("dak-cancel-run")
        val token = CancellationToken()
        val startedProcess =
            java.util.concurrent.atomic
                .AtomicReference<Process>()
        val running = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()

        try {
            val future =
                pool.submit<ToolResult> {
                    runner(dir).run(
                        sleepSpec(seconds = 120),
                        cancellation = token,
                        onProcessStarted = {
                            startedProcess.set(it)
                            running.countDown()
                        },
                    )
                }

            assertTrue("process never started", running.await(30, TimeUnit.SECONDS))
            token.cancel()

            val result = future.get(30, TimeUnit.SECONDS)
            assertEquals(ResultStatus.PARTIAL, result.status)
            assertTrue("should be reported as cancelled: ${result.warnings}", result.warnings.contains("cancelled"))
            assertTrue("the child process must be dead", !startedProcess.get().isAlive)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `a token cancelled before the call never starts a process`() {
        val dir = Files.createTempDirectory("dak-cancel-pre")
        val token = CancellationToken().apply { cancel() }
        var started = false

        val result = runner(dir).run(sleepSpec(seconds = 120), onProcessStarted = { started = true }, cancellation = token)

        assertEquals(ResultStatus.PARTIAL, result.status)
        assertTrue(result.warnings.contains("cancelled"))
        assertTrue("must not spawn anything once already cancelled", !started)
    }

    @Test
    fun `an uncancelled run is unaffected`() {
        val dir = Files.createTempDirectory("dak-cancel-none")

        val result = runner(dir).run(sleepSpec(seconds = 0))

        assertEquals(ResultStatus.SUCCESS, result.status)
        assertTrue(result.warnings.none { it == "cancelled" })
    }
}
