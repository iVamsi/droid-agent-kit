# @droidagentkit/launcher

Thin Node launcher for the **DroidAgentKit** JVM MCP server.

- **Runtime:** pure JVM. Node is only an install-time shim.
- **Behavior:** on first run, downloads the `droidagent-cli-<version>.jar` matching this
  package's version from [GitHub Releases](https://github.com/iVamsi/droid-agent-kit/releases),
  verifies its SHA-256, caches it under `~/.droidagentkit/cli/`, and runs
  `java -jar <jar> serve-mcp --transport stdio --project auto`. Later runs reuse the cached,
  already-verified jar without hitting the network again.
- **Version flag:** `droidagent-mcp --version` prints immutable launcher/server version metadata.
- **Requires:** a JDK 17+ runtime on `PATH` (the same prerequisite as building from source).
- **Overrides:**
  - `DROIDAGENT_BIN` — absolute path to an existing `droidagent` CLI; skips auto-fetch entirely.
  - `DROIDAGENT_CACHE_DIR` — change where the downloaded jar is cached.

## Install

```bash
npx -y @droidagentkit/launcher --version
```

or install it once:

```bash
npm install -g @droidagentkit/launcher
droidagent-mcp --version
```

## Fails closed

A checksum mismatch, a failed download, or a missing `java` on `PATH` all exit non-zero with a
clear message instead of silently degrading the MCP connection or running unverified code.

## Smoke test

```bash
../smoke-test.sh   # exercises --version/--help, the auto-fetch/cache/checksum paths, and
                    # (with DROIDAGENT_E2E=1) a real stdio round-trip against a built CLI
```

The npm launcher is the primary install path; MCPB is secondary. See
`.github/workflows/release.yml` for the release pipeline that publishes this package.
