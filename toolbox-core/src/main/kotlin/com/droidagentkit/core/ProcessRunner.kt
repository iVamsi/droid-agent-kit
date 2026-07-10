package com.droidagentkit.core

import java.io.ByteArrayOutputStream
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
    fun run(spec: CommandSpec): ToolResult {
        val started = Instant.now()
        val process =
            try {
                ProcessBuilder(spec.command)
                    .directory(Path.of(spec.workingDirectory).toFile())
                    .redirectErrorStream(true)
                    .start()
            } catch (error: Exception) {
                return ToolResult(
                    status = ResultStatus.BLOCKED,
                    summary = "Could not start command ${spec.command.joinToString(" ")}: ${error.message}",
                    warnings = listOf("command-start-failed"),
                )
            }

        val outputExecutor = Executors.newSingleThreadExecutor()
        val output = outputExecutor.submit<CapturedOutput> { readOutput(process.inputStream) }
        val completed: Boolean
        val captured: CapturedOutput
        try {
            completed = process.waitFor(spec.timeoutSeconds, TimeUnit.SECONDS)
            if (!completed) terminate(process)
            captured = output.get()
        } finally {
            outputExecutor.shutdownNow()
        }
        val durationMs = Duration.between(started, Instant.now()).toMillis()

        if (spec.outputMode == OutputMode.BINARY) {
            val artifact =
                artifactWriter.writeBytes(
                    "${spec.id}.bin",
                    captured.bytes,
                    ArtifactType.SCREENSHOT,
                    "${spec.id} binary capture",
                )
            val status =
                when {
                    !completed -> ResultStatus.PARTIAL
                    captured.truncated -> ResultStatus.PARTIAL
                    process.exitValue() == 0 -> ResultStatus.SUCCESS
                    else -> ResultStatus.FAILED
                }
            return ToolResult(
                status = status,
                summary = "${spec.id} captured ${captured.bytes.size} bytes in ${durationMs}ms",
                artifacts = listOf(artifact),
                warnings = warnings(completed, captured.truncated),
            )
        }

        val redacted = redactor.redact(captured.bytes.toString(Charsets.UTF_8))
        val artifact = artifactWriter.writeText("${spec.id}.log", redacted.text)
        val status =
            when {
                !completed -> ResultStatus.PARTIAL
                captured.truncated -> ResultStatus.PARTIAL
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
            warnings = warnings(completed, captured.truncated),
        )
    }

    private fun terminate(process: Process) {
        process.toHandle().descendants().forEach(ProcessHandle::destroy)
        process.destroy()
        if (!process.waitFor(1, TimeUnit.SECONDS)) {
            process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly)
            process.destroyForcibly()
            process.waitFor(1, TimeUnit.SECONDS)
        }
    }

    private fun readOutput(input: InputStream): CapturedOutput {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        var truncated = false
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            val remaining = MAX_CAPTURE_BYTES - bytes.size()
            if (remaining > 0) bytes.write(buffer, 0, minOf(count, remaining))
            if (count > remaining) truncated = true
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
        const val MAX_CAPTURE_BYTES = 10 * 1024 * 1024
    }
}
