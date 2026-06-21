package com.droidagentkit.core

import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

class ProcessRunner(
    private val redactor: Redactor,
    private val artifactWriter: ArtifactWriter,
) {
    fun run(spec: CommandSpec): ToolResult {
        val started = Instant.now()
        val process = try {
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

        val completed = process.waitFor(spec.timeoutSeconds, TimeUnit.SECONDS)
        val rawOutput = process.inputStream.bufferedReader().readText()
        if (!completed) {
            process.destroyForcibly()
        }
        val redacted = redactor.redact(rawOutput)
        val durationMs = Duration.between(started, Instant.now()).toMillis()
        val artifact = artifactWriter.writeText("${spec.id}.log", redacted.text)
        val status = when {
            !completed -> ResultStatus.PARTIAL
            process.exitValue() == 0 -> ResultStatus.SUCCESS
            else -> ResultStatus.FAILED
        }
        val summary = buildString {
            append("${spec.id} exited with ")
            append(if (completed) process.exitValue().toString() else "timeout")
            append(" in ${durationMs}ms")
            val preview = redacted.text.lineSequence().filter { it.isNotBlank() }.take(5).joinToString("\n")
            if (preview.isNotBlank()) append("\n").append(preview)
        }
        return ToolResult(
            status = status,
            summary = summary,
            artifacts = listOf(artifact),
            redactionsApplied = redacted.applied,
            warnings = if (completed) emptyList() else listOf("command-timeout"),
        )
    }
}
