package com.droidagentkit.perfetto

/**
 * Capture parameters for a Perfetto trace. All knobs are bounded so an agent cannot request an
 * unbounded capture that fills device storage.
 */
data class PerfettoCaptureConfig(
    val durationSeconds: Long = 10L,
    val dataSources: List<String> = DEFAULT_DATA_SOURCES,
    val bufferSizeKb: Long = 8192L,
    val maxFileSizeBytes: Long = 50L * 1024L * 1024L,
) {
    init {
        require(durationSeconds in 1..600) { "durationSeconds must be in 1..600" }
        require(bufferSizeKb in 256..65536) { "bufferSizeKb must be in 256..65536" }
        require(maxFileSizeBytes in 1..(2L * 1024L * 1024L * 1024L)) { "maxFileSizeBytes out of range" }
        require(dataSources.isNotEmpty()) { "at least one data source is required" }
        require(dataSources.size <= 16) { "too many data sources (max 16)" }
    }

    companion object {
        val DEFAULT_DATA_SOURCES: List<String> =
            listOf(
                "linux.process_stats",
                "linux.process_tracking",
                "android.surfaceflinger.frametimeline",
                "android.binder",
                "linux.sched",
                "linux.cpu.frequency",
            )
    }
}

/**
 * Renders a versioned Perfetto config (protobuf text format) from a [PerfettoCaptureConfig].
 *
 * The template is intentionally explicit and versioned: changing it changes the rendered output and
 * therefore the trace contents, so callers can pin a known template per release.
 */
object PerfettoConfigTemplate {
    const val VERSION = 1

    fun render(config: PerfettoCaptureConfig): String =
        buildString {
            appendLine("# DroidAgentKit Perfetto config v$VERSION")
            appendLine("duration_ms: ${config.durationSeconds * 1000}")
            appendLine("max_file_size_kb: ${config.maxFileSizeBytes / 1024}")
            appendLine("buffers {")
            appendLine("  size_kb: ${config.bufferSizeKb}")
            appendLine("  fill_policy: DISCARD")
            appendLine("}")
            config.dataSources.forEach { source ->
                appendLine("data_sources {")
                appendLine("  config {")
                appendLine("    name: \"$source\"")
                appendLine("  }")
                appendLine("}")
            }
            appendLine("data_sources {")
            appendLine("  config { name: \"linux.metadata\" }")
            appendLine("}")
        }
}

/**
 * Builds the on-device Perfetto capture invocation. The provider executes these adb commands in
 * order via the allowlisted ProcessRunner; perfetto-core never runs adb itself.
 *
 * Steps: push the rendered config, run `perfetto --txt -c <cfg> -o <trace>`, then the provider pulls
 * the trace and deletes the remote file in cleanup.
 */
object PerfettoCapture {
    const val REMOTE_DIR = "/data/misc/perfetto-traces"

    fun remoteTraceName(sessionId: String): String = "droidagentkit-$sessionId.perfetto-trace"

    fun remoteTracePath(sessionId: String): String = "$REMOTE_DIR/${remoteTraceName(sessionId)}"

    fun pushConfigCommand(
        adbPath: String,
        serial: String,
        localConfigPath: String,
        remoteConfigPath: String,
    ): List<String> = listOf(adbPath, "-s", serial, "push", localConfigPath, remoteConfigPath)

    fun perfettoCommand(
        adbPath: String,
        serial: String,
        remoteConfigPath: String,
        remoteTracePath: String,
    ): List<String> =
        listOf(
            adbPath,
            "-s",
            serial,
            "shell",
            "perfetto",
            "--txt",
            "-c",
            remoteConfigPath,
            "-o",
            remoteTracePath,
        )

    fun pullCommand(
        adbPath: String,
        serial: String,
        remoteTracePath: String,
        localTracePath: String,
    ): List<String> = listOf(adbPath, "-s", serial, "pull", remoteTracePath, localTracePath)

    fun cleanupCommand(
        adbPath: String,
        serial: String,
        vararg remotePaths: String,
    ): List<String> = listOf(adbPath, "-s", serial, "shell", "rm", "-f", *remotePaths)
}
