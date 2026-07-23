# @droidagentkit/launcher

Thin Node launcher for the **DroidAgentKit** JVM MCP server.

- **Runtime:** pure JVM. Node is only an install-time shim.
- **Behavior:** `droidagent-mcp` spawns `droidagent serve-mcp --transport stdio --project auto`.
- **Version flag:** `droidagent-mcp --version` prints immutable launcher/server version metadata.
- **Override:** set `DROIDAGENT_BIN` to the absolute path of the `droidagent` CLI if it is not on `PATH`.

## Install

```bash
npm install -g @droidagentkit/launcher
droidagent-mcp --version
```

## Smoke test

```bash
npm run smoke   # prints launcher version, exits 0
```

See `docs/adrs/0001-packaging.md` for the packaging decision (npm launcher primary,
MCPB secondary, OCI rejected).
