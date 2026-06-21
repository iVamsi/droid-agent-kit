package com.droidagentkit.cli

class DroidAgentCliParser {
    fun parse(args: Array<String>): CliCommand {
        if (args.isEmpty()) return CliCommand.Help
        val command = args.first()
        val options = parseOptions(args.drop(1))
        return when (command) {
            "serve-mcp" -> CliCommand.ServeMcp(
                project = options["project"] ?: ".",
                transport = options["transport"] ?: "http",
                host = options["host"] ?: "127.0.0.1",
                port = options["port"]?.toIntOrNull() ?: 8765,
            )
            "inspect" -> CliCommand.Inspect(
                project = options["project"] ?: ".",
                format = options["format"] ?: "markdown",
                output = options["output"],
            )
            "gradle" -> CliCommand.Gradle(
                project = options["project"] ?: ".",
                task = options["task"] ?: error("--task is required"),
            )
            "devices" -> CliCommand.Devices(
                project = options["project"] ?: ".",
                format = options["format"] ?: "json",
            )
            "snapshot" -> CliCommand.Snapshot(
                device = options["device"] ?: error("--device is required"),
                output = options["output"] ?: "build/droidagentkit/snapshot",
            )
            "audit" -> CliCommand.Audit(
                project = options["project"] ?: ".",
                writeAgents = options.containsKey("write-agents"),
                verify = options.containsKey("verify"),
                failUnder = options["fail-under"]?.toIntOrNull(),
                redactPublic = options.containsKey("redact-public"),
            )
            "visuals" -> {
                val action = args.getOrNull(1) ?: "report"
                CliCommand.Visuals(action, parseOptions(args.drop(2)))
            }
            "install-mcp" -> CliCommand.InstallMcp(
                targets = (options["targets"] ?: "all")
                    .split(',')
                    .map { it.trim().lowercase() }
                    .filter { it.isNotBlank() },
                binPath = options["bin"],
                dryRun = options.containsKey("dry-run"),
                applyClaude = !options.containsKey("dry-run") && !options.containsKey("no-claude-apply"),
            )
            else -> CliCommand.Help
        }
    }

    private fun parseOptions(tokens: List<String>): Map<String, String> {
        val options = linkedMapOf<String, String>()
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            if (token.startsWith("--")) {
                val key = token.removePrefix("--")
                val next = tokens.getOrNull(index + 1)
                if (next != null && !next.startsWith("--")) {
                    options[key] = next
                    index += 2
                } else {
                    options[key] = "true"
                    index += 1
                }
            } else {
                index += 1
            }
        }
        return options
    }
}
