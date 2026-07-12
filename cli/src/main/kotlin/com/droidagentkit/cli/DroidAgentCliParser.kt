package com.droidagentkit.cli

class DroidAgentCliParser {
    fun parse(args: Array<String>): CliCommand {
        if (args.isEmpty() || args.first() == "-h" || args.first() == "--help") return CliCommand.Help()

        val commandName = args.first()
        val spec =
            CliCommandRegistry.all.find { it.name == commandName }
                ?: return CliCommand.Help(error = "Unknown command '$commandName'. Run 'droidagent --help' to see available commands.")

        val rest = args.drop(1)
        if (rest.any { it == "-h" || it == "--help" }) return CliCommand.Help(commandName = commandName)

        val options = parseOptions(rest)

        if (!spec.freeformOptions) {
            val allowedFlags = spec.options.map { it.flag }.toSet()
            val errors = mutableListOf<String>()
            options.keys.filter { "--$it" !in allowedFlags }.forEach { errors += "Unknown flag '--$it' for command '$commandName'." }
            spec.options
                .filter { it.required && it.flag.removePrefix("--") !in options }
                .forEach { errors += "${it.flag} is required for command '$commandName'." }
            if (errors.isNotEmpty()) return CliCommand.Help(error = errors.joinToString(" "))
        }

        return when (commandName) {
            "serve-mcp" ->
                CliCommand.ServeMcp(
                    project = options["project"] ?: ".",
                    transport = options["transport"] ?: "http",
                    host = options["host"] ?: "127.0.0.1",
                    port = options["port"]?.toIntOrNull() ?: 8765,
                    bearerTokenFile = options["bearer-token-file"],
                )
            "inspect" ->
                CliCommand.Inspect(
                    project = options["project"] ?: ".",
                    format = options["format"] ?: "markdown",
                    output = options["output"],
                )
            "gradle" ->
                CliCommand.Gradle(
                    project = options["project"] ?: ".",
                    task = options.getValue("task"),
                )
            "devices" ->
                CliCommand.Devices(
                    project = options["project"] ?: ".",
                    format = options["format"] ?: "json",
                )
            "snapshot" ->
                CliCommand.Snapshot(
                    device = options.getValue("device"),
                    output = options["output"] ?: "build/droidagentkit/snapshot",
                )
            "audit" ->
                CliCommand.Audit(
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
            "install-mcp" ->
                CliCommand.InstallMcp(
                    targets =
                        (options["targets"] ?: "all")
                            .split(',')
                            .map { it.trim().lowercase() }
                            .filter { it.isNotBlank() },
                    binPath = options["bin"],
                    project = options["project"] ?: ".",
                    dryRun = options.containsKey("dry-run"),
                    applyClaude = !options.containsKey("dry-run") && !options.containsKey("no-claude-apply"),
                )
            else -> CliCommand.Help(error = "Unknown command '$commandName'.")
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
