# Easy MCP Installation

DroidAgentKit is designed to be installed once and then work in every Android project you open with an agent.

## One-Time Local Setup

From the DroidAgentKit repo:

```bash
./gradlew :cli:installDist
./cli/build/install/droidagent/bin/droidagent install-mcp
```

That is the normal path.

For a preview:

```bash
./cli/build/install/droidagent/bin/droidagent install-mcp --dry-run
```

For only one target:

```bash
./cli/build/install/droidagent/bin/droidagent install-mcp --targets codex
./cli/build/install/droidagent/bin/droidagent install-mcp --targets claude
./cli/build/install/droidagent/bin/droidagent install-mcp --targets generic
```

## What It Installs

### Codex

Adds this managed block to `~/.codex/config.toml`:

```toml
# >>> droidagentkit mcp >>>
[mcp_servers.droidagentkit]
command = "/absolute/path/to/droidagent"
args = ["serve-mcp", "--transport", "stdio", "--project", "auto"]
# <<< droidagentkit mcp <<<
```

The block is idempotent. Running the installer again replaces the old DroidAgentKit block instead of duplicating it.

### Claude Code

Runs the Claude Code user-scope install command:

```bash
claude mcp add --scope user --transport stdio droidagentkit -- \
  /absolute/path/to/droidagent serve-mcp --transport stdio --project auto
```

Claude Code documents user-scope MCP servers as loading across all projects.

### Other MCP Clients

Prints a generic stdio config:

```json
{
  "mcpServers": {
    "droidagentkit": {
      "type": "stdio",
      "command": "/absolute/path/to/droidagent",
      "args": ["serve-mcp", "--transport", "stdio", "--project", "auto"]
    }
  }
}
```

## How Project Auto-Detection Works

`--project auto` resolves the active Android project in this order:

1. `CLAUDE_PROJECT_DIR`
2. `CODEX_WORKSPACE`
3. `CODEX_PROJECT_DIR`
4. `PWD`
5. the process current directory

This lets one user-wide MCP registration adapt to whichever project the agent is currently using.

## Useful Prompts

- "Use DroidAgentKit to inspect this Android project."
- "Use DroidAgentKit to find safe Gradle tasks."
- "Use DroidAgentKit to audit this repo for agent readiness."
- "Use DroidAgentKit to capture Android diagnostics."

## Safety Defaults

- The MCP server uses stdio for local agent tools.
- No arbitrary shell tool is exposed.
- Gradle tasks are allowlisted by `.droidagentkit/config.yaml`.
- Command output is redacted before returning summaries to agents.
