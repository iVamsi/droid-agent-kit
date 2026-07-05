package com.droidagentkit.cli

data class CliOption(
    val flag: String,
    val description: String,
    val required: Boolean = false,
    val takesValue: Boolean = true,
)

data class CliCommandSpec(
    val name: String,
    val description: String,
    val options: List<CliOption>,
    val freeformOptions: Boolean = false,
)

object CliCommandRegistry {
    val all: List<CliCommandSpec> =
        listOf(
            CliCommandSpec(
                "serve-mcp",
                "Run the DroidAgentKit MCP server.",
                listOf(
                    CliOption("--project", "Project root path. Defaults to cwd."),
                    CliOption("--transport", "Transport: stdio or http. Defaults to http."),
                    CliOption("--host", "Bind host for http transport. Defaults to 127.0.0.1."),
                    CliOption("--port", "Bind port for http transport. Defaults to 8765."),
                ),
            ),
            CliCommandSpec(
                "inspect",
                "Inspect an Android project's modules and versions.",
                listOf(
                    CliOption("--project", "Project root path. Defaults to cwd."),
                    CliOption("--format", "Output format: markdown or json. Defaults to markdown."),
                    CliOption("--output", "Write report to this file instead of stdout."),
                ),
            ),
            CliCommandSpec(
                "gradle",
                "Run an allowlisted Gradle task.",
                listOf(
                    CliOption("--project", "Project root path. Defaults to cwd."),
                    CliOption("--task", "Gradle task to run (must match the configured allowlist).", required = true),
                ),
            ),
            CliCommandSpec(
                "devices",
                "List connected adb devices.",
                listOf(
                    CliOption("--project", "Project root path. Defaults to cwd."),
                    CliOption("--format", "Output format: json or markdown. Defaults to json."),
                ),
            ),
            CliCommandSpec(
                "snapshot",
                "Capture a device screenshot.",
                listOf(
                    CliOption("--device", "adb device serial.", required = true),
                    CliOption("--output", "Output path prefix. Defaults to build/droidagentkit/snapshot."),
                ),
            ),
            CliCommandSpec(
                "audit",
                "Run the agent-readiness auditor.",
                listOf(
                    CliOption("--project", "Project root path. Defaults to cwd."),
                    CliOption("--write-agents", "Write AGENTS.md, skill, and config files.", takesValue = false),
                    CliOption("--verify", "Exit non-zero if readiness regresses.", takesValue = false),
                    CliOption("--fail-under", "Exit non-zero if score is under this threshold."),
                    CliOption("--redact-public", "Redact evidence before writing public-facing output.", takesValue = false),
                ),
            ),
            CliCommandSpec(
                "visuals",
                "Run a visual-regression report/golden-update action.",
                emptyList(),
                freeformOptions = true,
            ),
            CliCommandSpec(
                "install-mcp",
                "Register DroidAgentKit as a user-scope MCP server.",
                listOf(
                    CliOption("--targets", "Comma-separated: codex, claude, generic, cursor, zed, vscode, all. Defaults to all."),
                    CliOption("--bin", "Override path to the droidagent binary."),
                    CliOption("--dry-run", "Preview changes without writing files.", takesValue = false),
                    CliOption("--no-claude-apply", "Skip running the Claude Code apply step.", takesValue = false),
                ),
            ),
        )
}
