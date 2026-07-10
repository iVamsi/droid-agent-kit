# Connect Android Studio to DroidAgentKit MCP

For Codex, Claude Code, and other stdio-capable local agents, prefer the one-command user installer:

```bash
./gradlew :cli:installDist
./cli/build/install/droidagent/bin/droidagent install-mcp
```

See [Easy MCP Installation](easy-mcp-installation.md).

Android Studio uses HTTP MCP configuration today, so start the local HTTP server when using it directly from Android Studio.

DroidAgentKit negotiates MCP protocol version `2025-11-25`. Tool definitions expose JSON input and
output schemas; calls return both readable JSON text and structured MCP result content.

Start the local HTTP MCP server from an Android project:

```bash
droidagent serve-mcp --project . --transport http --host 127.0.0.1 --port 8765
```

The server prints a randomly generated bearer token at startup. Copy it into the configuration below;
each server start uses a new token.

Use this Android Studio MCP configuration:

```json
{
  "mcpServers": {
    "droidagentkit": {
      "httpUrl": "http://127.0.0.1:8765/mcp",
      "headers": {
        "Authorization": "Bearer <TOKEN_PRINTED_BY_DROIDAGENT>"
      }
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
