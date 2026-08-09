package com.droidagentkit.core

import java.io.InputStream
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ProcessRunner(
    private val redactor: Redactor,
    private val artifactWriter: ArtifactWriter,
) {
    // `cancellation` deliberately precedes `onProcessStarted` so the trailing-lambda form used at
    // existing call sites keeps binding to onProcessStarted rather than silently changing meaning.
    fun run(
        spec: CommandSpec,
        cancellation: CancellationToken = CancellationToken.NONE,
        onProcessStarted: ((Process) -> Unit)? = null,
    ): ToolResult {
        val started = Instant.now()
        if (cancellation.isCancelled) return cancelledResult(spec, 0)
        val process =
            try {
                val builder =
                    ProcessBuilder(spec.command)
                        .directory(Path.of(spec.workingDirectory).toFile())
                        .redirectErrorStream(true)
                if (spec.scrubGradleEnvironment) {
                    ProcessEnvironmentScrubber.scrubGradleOptionVars(builder.environment())
                }
                builder.start()
            } catch (error: Exception) {
                val commandName = spec.command.firstOrNull().orEmpty()
                val hint =
                    when {
                        commandName.endsWith("adb") || commandName == "adb" ->
                            " Install Android platform-tools, ensure adb is on PATH, or set safety.adbPath in ~/.droidagentkit/policy.yaml."
                        else -> ""
                    }
                return ToolResult(
                    status = ResultStatus.BLOCKED,
                    summary = "Could not start command ${spec.command.joinToString(" ")}: ${error.message}.$hint",
                    warnings = listOf("command-start-failed"),
                )
            }
        onProcessStarted?.invoke(process)
        // Registered after start, so the token has to tolerate a cancel that already happened --
        // see CancellationToken.onCancel. Reuses the timeout path's descendant kill, because a
        // Gradle daemon or adb child outliving `destroy()` on the parent is the normal case.
        cancellation.onCancel { terminate(process) }

        val outputExecutor = Executors.newSingleThreadExecutor()
        val captured = outputExecutor.submit<CapturedOutput> { readOutput(process.inputStream) }
        val completed: Boolean
        val capturedOutput: CapturedOutput
        try {
            completed = process.waitFor(spec.timeoutSeconds, TimeUnit.SECONDS)
            if (!completed) terminate(process)
            // Bounded rather than an open-ended get(). The reader blocks until the stdout pipe
            // closes, and a grandchild that inherited the pipe can hold it open after the process
            // we spawned is dead -- `sh -c "sleep 120"` forking `sleep` is the simple case. Waiting
            // forever there would hang the whole tool call on a process nobody is waiting for.
            capturedOutput =
                try {
                    captured.get(OUTPUT_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                } catch (_: java.util.concurrent.TimeoutException) {
                    captured.cancel(true)
                    CapturedOutput(java.io.ByteArrayOutputStream().toByteArray(), truncated = true)
                }
        } finally {
            outputExecutor.shutdownNow()
        }
        val durationMs = Duration.between(started, Instant.now()).toMillis()
        // Checked after the wait rather than before: the process is already dead by now (the hook
        // killed it), and reporting "cancelled" is more useful to the agent than the exit code a
        // SIGKILL happens to produce.
        if (cancellation.isCancelled) return cancelledResult(spec, durationMs)

        if (spec.outputMode == OutputMode.BINARY) {
            return writeBinaryArtifact(spec, process, capturedOutput, completed, durationMs)
        }

        val redacted = redactor.redact(capturedOutput.bytes.toString(Charsets.UTF_8))
        val artifact =
            artifactWriter.writeText(
                "${spec.id}.log",
                redacted.text,
                ArtifactType.LOG,
                "${spec.id} command output",
                spec.sensitivity,
            )
        val status =
            when {
                !completed -> ResultStatus.PARTIAL
                capturedOutput.truncated -> ResultStatus.PARTIAL
                process.exitValue() == 0 -> ResultStatus.SUCCESS
                else -> ResultStatus.FAILED
            }
        val summary =
            buildString {
                append("${spec.id} exited with ")
                append(if (completed) process.exitValue().toString() else "timeout")
                append(" in ${durationMs}ms")
                val preview =
                    redacted.text
                        .lineSequence()
                        .filter { it.isNotBlank() }
                        .take(5)
                        .joinToString("\n")
                if (preview.isNotBlank()) append("\n").append(preview)
            }
        return ToolResult(
            status = status,
            summary = summary,
            artifacts = listOf(artifact),
            redactionsApplied = redacted.applied,
            warnings = warnings(completed, capturedOutput.truncated) + redacted.warnings,
        )
    }

    private fun writeBinaryArtifact(
        spec: CommandSpec,
        process: Process,
        captured: CapturedOutput,
        completed: Boolean,
        durationMs: Long,
    ): ToolResult {
        val artifactName = spec.artifactName ?: "${spec.id}.bin"
        val artifactType = spec.artifactType ?: ArtifactType.SCREENSHOT
        val sensitivity = spec.sensitivity
        val result =
            artifactWriter.writeStream(
                name = artifactName,
                type = artifactType,
                description = "${spec.id} binary capture",
                sensitivity = sensitivity,
                maxBytes = spec.maxCaptureBytes,
            ) { sink -> sink.write(captured.bytes) }
        val status =
            when {
                !completed -> ResultStatus.PARTIAL
                captured.truncated -> ResultStatus.PARTIAL
                process.exitValue() == 0 -> ResultStatus.SUCCESS
                else -> ResultStatus.FAILED
            }
        return ToolResult(
            status = status,
            summary = "${spec.id} captured ${result.artifact.sizeBytes} bytes in ${durationMs}ms",
            artifacts = listOf(result.artifact),
            warnings = warnings(completed, captured.truncated || result.truncated),
        )
    }

    private fun cancelledResult(
        spec: CommandSpec,
        durationMs: Long,
    ): ToolResult =
        ToolResult(
            status = ResultStatus.PARTIAL,
            summary = "${spec.id} was cancelled by the client after ${durationMs}ms.",
            warnings = listOf("cancelled"),
        )

    private fun terminate(process: Process) {
        process.toHandle().descendants().forEach(ProcessHandle::destroy)
        process.destroy()
        if (!process.waitFor(1, TimeUnit.SECONDS)) {
            process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly)
            process.destroyForcibly()
            process.waitFor(1, TimeUnit.SECONDS)
        }
        // Re-enumerated deliberately. The first sweep races the parent's own forking: a shell that
        // had not yet spawned its child when we listed descendants leaves that child running, still
        // holding the stdout pipe. Sweeping again after the parent is dead catches it.
        process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly)
    }

    private fun readOutput(input: InputStream): CapturedOutput {
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        var truncated = false
        try {
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                val remaining = MAX_TEXT_CAPTURE_BYTES - bytes.size()
                if (remaining > 0) bytes.write(buffer, 0, minOf(count, remaining))
                if (count > remaining) truncated = true
            }
        } catch (_: java.io.IOException) {
            // Destroying a process closes its stdout under this reader, which surfaces as
            // "Stream closed" rather than a clean EOF. On the timeout and cancellation paths that
            // is the expected end of the stream, not a failure -- the bytes already read are still
            // the best account of what the command produced, and throwing here would replace a
            // useful partial result with an opaque ExecutionException.
        }
        return CapturedOutput(bytes.toByteArray(), truncated)
    }

    private fun warnings(
        completed: Boolean,
        truncated: Boolean,
    ): List<String> =
        buildList {
            if (!completed) add("command-timeout")
            if (truncated) add("command-output-truncated")
        }

    private data class CapturedOutput(
        val bytes: ByteArray,
        val truncated: Boolean,
    )

    private companion object {
        const val BUFFER_SIZE = 8 * 1024
        const val MAX_TEXT_CAPTURE_BYTES = 10 * 1024 * 1024

        /**
         * How long to keep draining output after the process itself has exited.
         *
         * Generous, because a legitimately noisy build can still have buffered output in flight;
         * bounded, because a surviving grandchild holding the pipe must not hang the call.
         */
        const val OUTPUT_DRAIN_TIMEOUT_SECONDS = 15L
    }
}

/**
 * Removes host env vars that can inject Gradle/JVM flags outside the SAFE_GRADLE_ARGUMENTS
 * allowlist. Applied to the ProcessBuilder environment map before start.
 */
object ProcessEnvironmentScrubber {
    val GRADLE_OPTION_ENV_KEYS: Set<String> =
        setOf(
            "GRADLE_OPTS",
            "JAVA_TOOL_OPTIONS",
            "_JAVA_OPTIONS",
            "JDK_JAVA_OPTIONS",
            "GRADLE_ARGS",
        )

    fun scrubGradleOptionVars(env: MutableMap<String, String>) {
        GRADLE_OPTION_ENV_KEYS.forEach { env.remove(it) }
    }
}
