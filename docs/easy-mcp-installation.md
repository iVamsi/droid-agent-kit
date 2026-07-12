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
./cli/build/install/droidagent/bin/droidagent install-mcp --targets cursor
./cli/build/install/droidagent/bin/droidagent install-mcp --targets zed
./cli/build/install/droidagent/bin/droidagent install-mcp --targets vscode
./cli/build/install/droidagent/bin/droidagent install-mcp --targets android-studio --project /path/to/android/project
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

### Cursor

Merges a `droidagentkit` entry into `~/.cursor/mcp.json` (creating the file if needed) under the
standard `mcpServers` key, preserving any other MCP servers already configured there:

```json
{
  "mcpServers": {
    "droidagentkit": {
      "command": "/absolute/path/to/droidagent",
      "args": ["serve-mcp", "--transport", "stdio", "--project", "auto"]
    }
  }
}
```

### Zed

Merges a `droidagentkit` entry into Zed's `settings.json` (`~/.config/zed/settings.json` on
macOS/Linux, `%APPDATA%\Zed\settings.json` on Windows) under the `context_servers` key. Since this is
Zed's general editor settings file, the installer only ever touches the `context_servers.droidagentkit`
entry — every other setting in the file is left untouched. Comments and the file's original
formatting/key order are not preserved — the values of every other key are, but the file is rewritten
in this tool's own JSON formatting:

```json
{
  "context_servers": {
    "droidagentkit": {
      "command": "/absolute/path/to/droidagent",
      "args": ["serve-mcp", "--transport", "stdio", "--project", "auto"],
      "env": {}
    }
  }
}
```

### VS Code (GitHub Copilot Chat)

Merges a `droidagentkit` entry into VS Code's user-level `mcp.json` (`~/Library/Application
Support/Code/User/mcp.json` on macOS, `~/.config/Code/User/mcp.json` on Linux,
`%APPDATA%\Code\User\mcp.json` on Windows) under the `servers` key — this is VS Code's own MCP support,
which GitHub Copilot Chat's agent mode reads:

```json
{
  "servers": {
    "droidagentkit": {
      "type": "stdio",
      "command": "/absolute/path/to/droidagent",
      "args": ["serve-mcp", "--transport", "stdio", "--project", "auto"]
    }
  }
}
```

### Android Studio

Android Studio supports MCP tools through streamable HTTP rather than stdio. The installer discovers
each installed Android Studio configuration directory and merges an authenticated `droidagentkit`
entry into its `mcp.json`:

```json
{
  "mcpServers": {
    "droidagentkit": {
      "httpUrl": "http://127.0.0.1:8765/mcp",
      "headers": {
        "Authorization": "Bearer <generated-local-token>"
      },
      "timeout": 30000,
      "enabled": true
    }
  }
}
```

On macOS, the same command installs and starts
`~/Library/LaunchAgents/com.droidagentkit.mcp.android-studio.plist`. It keeps the server available
after login and reads its bearer token from an owner-only file under
`~/.droidagentkit/android-studio/`. Rerunning the command updates the registered project and service
without duplicating the MCP entry.

Android Studio's MCP configuration is IDE-wide while DroidAgentKit deliberately confines each HTTP
service to one explicit project root. Run the Android Studio target again with `--project` when you
want to switch the registered root:

```bash
droidagent install-mcp --targets android-studio --project /path/to/another/android/project
```

On Linux and Windows, the installer writes the official Android Studio `mcp.json` location and prints
the authenticated service command to run after sign-in. Automatic login service installation is
currently macOS-only.

These paths and the `httpUrl`, `headers`, `timeout`, and `enabled` fields follow the official
[Android Studio MCP documentation](https://developer.android.com/studio/gemini/add-mcp-server) and
[Android Studio configuration directory documentation](https://developer.android.com/studio/troubleshoot#directories).

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

- The MCP server uses stdio for capable local agent tools and authenticated loopback HTTP for Android Studio.
- No arbitrary shell tool is exposed.
- Gradle tasks are allowlisted by `.droidagentkit/config.yaml`.
- Command output is redacted before returning summaries to agents.
