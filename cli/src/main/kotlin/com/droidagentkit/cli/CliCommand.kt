package com.droidagentkit.cli

sealed class CliCommand {
    data class ServeMcp(
        val project: String,
        val transport: String,
        val host: String = "127.0.0.1",
        val port: Int = 8765,
    ) : CliCommand()

    data class Inspect(
        val project: String,
        val format: String,
        val output: String?,
    ) : CliCommand()

    data class Gradle(
        val project: String,
        val task: String,
    ) : CliCommand()

    data class Devices(
        val project: String,
        val format: String,
    ) : CliCommand()

    data class Snapshot(
        val device: String,
        val output: String,
    ) : CliCommand()

    data class Audit(
        val project: String,
        val writeAgents: Boolean,
        val verify: Boolean,
        val failUnder: Int?,
        val redactPublic: Boolean,
    ) : CliCommand()

    data class Visuals(
        val action: String,
        val options: Map<String, String>,
    ) : CliCommand()

    data class InstallMcp(
        val targets: List<String>,
        val binPath: String?,
        val dryRun: Boolean,
        val applyClaude: Boolean,
    ) : CliCommand()

    data class Help(
        val error: String? = null,
        val commandName: String? = null,
    ) : CliCommand()
}
