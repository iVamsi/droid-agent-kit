# ADR 0001 — Packaging and registry distribution

- **Status:** Proposed
- **Date:** 2026-07-22
- **Decision driver:** Tranche 9 — Capability reporting, distribution, and documentation

## Context

DroidAgentKit is a Kotlin/JVM MCP server distributed as a Gradle `installDist` CLI
(`cli/build/install/droidagent/bin/droidagent`). To be discoverable and installable by MCP
hosts (Claude Code, Codex, Cursor, Android Studio), the server must be published to a
registry with metadata that lets a host install and launch it on a clean machine.

The plan requires a packaging decision **before** registry publication that compares MCPB
(Model Context Protocol Bundle) against a thin npm launcher, and rejects OCI unless host
adb/USB/socket access is proven usable.

## Options considered

### 1. OCI container image

- **Pros:** Reproducible, sandboxed, popular in backend deployment.
- **Cons:** MCP hosts run the server as a **local** process that needs adb/USB/emulator
  socket access to the developer's machine. A containerized server cannot reach the host's
  adb server, USB devices, or emulator consoles without bespoke, fragile host passthrough.
  DroidAgentKit's value depends on local device access; containerization breaks that
  contract.
- **Decision:** **Rejected.** Do not publish an OCI image unless a host is proven to
  forward adb/USB/socket access to the container. No such host exists today.

### 2. MCPB (Model Context Protocol Bundle)

- **Pros:** Official bundle format; declarative manifest (`mcp.json`) with server metadata,
  arguments, and environment; designed for MCP registry distribution.
- **Cons:** Tooling and host install/launch support is still maturing; does not by itself
  install a JVM. A host still needs a JDK on the path and a way to fetch the JVM artifacts.

### 3. Thin npm launcher

- **Pros:** npm is installed on most developer machines; a small launcher package can
  download/cache the JVM distribution archive (or expect the `droidagent` CLI on PATH),
  verify its checksum, and spawn `droidagent serve-mcp --transport stdio`. Cross-platform
  via Node. Pairs naturally with an `install-mcp` step that writes host config.
- **Cons:** Adds a Node dependency for installation only (not at runtime — the server is
  pure JVM). Requires checksum verification and immutable version metadata.

## Decision

Ship **both** in a layered way, with the npm launcher as the primary install path:

1. **Primary: thin npm launcher** (`@droidagentkit/launcher`). It locates or fetches the
   JVM `droidagent` CLI, verifies the SHA-256 against `verification-metadata`, and spawns
   `droidagent serve-mcp --transport stdio --project auto`. It exposes immutable version
   metadata (`bin`, `version`) and works on macOS/Linux/Windows.
2. **Registry metadata: `server.json`** in the official MCP registry schema, published
   **only after** the npm launcher installs and launches the JVM server from a clean
   machine. PulseMCP visibility is treated as verification, not a separate promise.
3. **MCPB manifest** (`mcp.json`) is generated from the same metadata for hosts that
   prefer bundle-based installation. It is a secondary artifact, kept in sync with
   `server.json`.
4. **OCI** is explicitly **not** produced.

## Consequences

- Distribution adds a Node-only install shim; the runtime stays JVM-only with no new
  runtime dependencies.
- Release must publish: the `installDist` archive + checksums + SBOM, the npm launcher,
  `server.json`, and `mcp.json`.
- Clean-machine smoke tests (macOS/Linux/Windows) must pass before `server.json` is
  published. The smoke test asserts: `npm install` succeeds, `droidagent --version`
  prints the immutable version, and `serve-mcp --transport stdio` answers `initialize`.
- The launcher never silently downgrades: it pins to the requested version and fails
  closed on checksum mismatch.
