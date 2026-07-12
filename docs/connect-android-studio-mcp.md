# Connect Android Studio to DroidAgentKit MCP

Build and register DroidAgentKit for Android Studio with one command:

```bash
./gradlew :cli:installDist
./cli/build/install/droidagent/bin/droidagent install-mcp \
  --targets android-studio \
  --projects-root ~/Developer/StudioProjects
```

On macOS this command:

- discovers installed `AndroidStudio*` configuration directories;
- merges the server into each `mcp.json` without removing unrelated servers;
- creates an owner-only persistent bearer token; and
- installs and starts a user LaunchAgent so no terminal has to remain open.

Restart Android Studio after the first registration, or reload its MCP configuration from Settings.
Every Gradle project beneath the trusted directory then uses the same registration and background
service; no per-project MCP installation is needed.

See [Easy MCP Installation](easy-mcp-installation.md) for every supported client and Linux/Windows
service notes.

Android Studio uses streamable HTTP MCP configuration today. It does not support stdio MCP servers,
MCP resources, or MCP prompts. DroidAgentKit therefore exposes its tools through an authenticated
loopback HTTP endpoint for this target. See the official
[Add an MCP server to Android Studio](https://developer.android.com/studio/gemini/add-mcp-server)
documentation.

DroidAgentKit negotiates MCP protocol version `2025-11-25`. Tool definitions expose JSON input and
output schemas; calls return both readable JSON text and structured MCP result content. In workspace
mode, every tool requires the active project's absolute `rootPath`. DroidAgentKit resolves and checks
that path against `--projects-root` before reading project configuration or running any command.

For manual or non-macOS setup, create a token file containing at least 32 URL-safe characters and
start the local HTTP MCP server from an Android project:

```bash
droidagent serve-mcp --project . --projects-root ~/Developer/StudioProjects \
  --transport http --host 127.0.0.1 --port 8765 \
  --bearer-token-file ~/.droidagentkit/android-studio/bearer-token
```

Use this Android Studio MCP configuration:

```json
{
  "mcpServers": {
    "droidagentkit": {
      "httpUrl": "http://127.0.0.1:8765/mcp",
      "headers": {
        "Authorization": "Bearer <TOKEN_FROM_FILE>"
      },
      "timeout": 30000,
      "enabled": true
    }
  }
}
```

The alpha server exposes Android-specific tools for project inspection, allowlisted Gradle tasks, adb device listing, app install/launch, logcat capture, screenshots, and report bundles.

Security defaults:

- The server binds to `127.0.0.1`.
- HTTP requests require a per-server random bearer token.
- Gradle tasks must match `.droidagentkit/config.yaml`.
- adb actions require explicit device serials for device-specific workflows.
- Command output is redacted before it is summarized or attached to reports.
