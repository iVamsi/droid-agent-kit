# DroidAgentKit

DroidAgentKit is an open-source Kotlin/JVM toolkit for Android developers using AI and agentic coding tools.

It ships three independently usable alpha tools:

- **Android Agent Toolbox MCP Server** for safe project inspection, Gradle, adb, logcat, screenshot, and report-bundle workflows.
- **Agent Readiness Auditor** for generating agent-ready instructions, command maps, risk reports, and readiness scores.
- **Compose Visual Regression + AI Report Kit** for deterministic visual diff reports and agent fix packets.

The first alpha is local-only, uses explicit command allowlists, redacts likely secrets, and writes artifacts under `build/droidagentkit` by default.

## Fastest Setup

Build the local CLI:

```bash
./gradlew :cli:installDist
```

Install DroidAgentKit as a user-wide MCP server for supported agent tools:

```bash
./cli/build/install/droidagent/bin/droidagent install-mcp
```

That command:

- updates the Codex user config at `~/.codex/config.toml`;
- runs Claude Code's user-scope MCP install command when `claude` is available;
- merges a `droidagentkit` entry into Cursor's, Zed's, and VS Code's user-level MCP configs, preserving any other servers/settings already there;
- prints a generic stdio MCP config for other tools;
- registers the server with `--project auto`, so it resolves the active project from agent-provided environment variables such as `CLAUDE_PROJECT_DIR`, `CODEX_WORKSPACE`, or the current working directory.

Preview without changing files:

```bash
./cli/build/install/droidagent/bin/droidagent install-mcp --dry-run
```

After installation, open any Android project in Codex or Claude Code and ask for Android-specific help such as:

- "Inspect this Android project with DroidAgentKit."
- "Run the safe unit test task for the app module."
- "Audit this repo for agent readiness."
- "Capture Android diagnostics and summarize likely failures."

## How It Works

`droidagent serve-mcp` is a long-running MCP (Model Context Protocol) server. You never run it by
hand — once `install-mcp` registers it, your agent tool launches it as a subprocess automatically:

1. The agent (Claude Code, Cursor, Codex, Zed, or VS Code + GitHub Copilot Chat) starts
   `droidagent serve-mcp --transport stdio --project auto` itself, whenever it starts up.
2. The agent and server perform the MCP `2025-11-25` handshake over JSON-RPC 2.0: `initialize`, then
   `notifications/initialized`.
3. The agent sends `tools/list` to discover the 14 `android_*` tools (project inspection, allowlisted
   Gradle runs, adb, logcat, lint, crash triage, and more) and their descriptions and input schemas.
4. From then on, whenever your prompt matches what a tool description says it does, the agent's own
   model decides to call it via `tools/call` — you don't invoke tools directly, you just ask in natural
   language (see the example prompts above).
5. `--project auto` resolves whichever Android project is "active" from agent-provided environment
   variables (`CLAUDE_PROJECT_DIR`, `CODEX_WORKSPACE`, ...) or the current working directory, so one
   user-wide registration works across every Android project you open.

## Direct CLI Usage

```bash
./cli/build/install/droidagent/bin/droidagent inspect --project /path/to/android/project --format markdown
./cli/build/install/droidagent/bin/droidagent audit --project /path/to/android/project --write-agents
./cli/build/install/droidagent/bin/droidagent serve-mcp --transport stdio --project auto
```

Run any subcommand with `--help` to see its flags, e.g. `droidagent gradle --help`.
