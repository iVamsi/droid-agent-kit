# Changelog

All notable changes to DroidAgentKit are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and version numbers follow the alpha
pre-release convention `0.y.z-alpha` until a stable 1.0 release.

## Unreleased

## [0.2.1-alpha] - 2026-07-28

### Changed

- Align `distribution/server.json` with the official MCP Registry schema (`io.github.iVamsi/droidagentkit`).
- Add `mcpName` to `@droidagentkit/launcher` for registry ownership verification.

## [0.2.0-alpha] - 2026-07-28

### Added

- User policy (`~/.droidagentkit/policy.yaml`) as the only place that can grant capabilities, expose
  opt-in tool groups, set host binary paths, or disable redaction (ADR 0002). `droidagent init`
  writes the policy and seeds a grant-free project config.
- Release pipeline (`.github/workflows/release.yml`, tag-triggered) builds a `droidagent-cli` fat jar,
  attaches SHA-256 + CycloneDX SBOM, and publishes `@droidagentkit/launcher` via OIDC trusted publishing.
- Tool-manifest integrity test (SHA-256 pin of every tool name/description/schema).
- CLI/docs: `docs/cli-reference.md`, `docs/troubleshooting.md`, docs index; Cursor project-dir env support.

### Security

- Project config can no longer escalate privileges (config trust split).
- Every `adb shell` path is shell-quoted; `apkPath` confined to project root via `OperationPolicy`.
- Gradle runs scrub `GRADLE_OPTS` / `JAVA_TOOL_OPTIONS` and related env vars.
- HTTP: reject non-loopback bind unless `--allow-remote`; Host allowlist; digest-based bearer compare.
- Findings redaction; stdio message size cap; nested-quantifier `extraPatterns` rejected.
- Proxy restore retries + verifies; leftover-proxy preflight warning.
- Gradle wrapper `distributionSha256Sum` pinned.

### Changed

- MCP server id standardized to `droidagentkit` in docs/quickstart.
- `allowAnyGradleTask` (user policy only) replaces project-file catch-all `*` patterns.

## [0.1.0-alpha] - 2026-07-04

### Changed

- `DroidAgentConfigLoader.load()` now returns `ConfigLoadResult` (`Loaded` or `Invalid`) instead of a
  bare `DroidAgentConfig`. `schemaVersion` and value types (booleans, numbers) are validated; malformed
  config previously fell back to defaults silently or threw an uncaught exception.
- CLI commands now reject unknown flags and print `--help` usage generated from a command registry.
  Previously, unrecognized flags were silently ignored. The `visuals` command still accepts arbitrary
  passthrough flags, since its option set varies by action.
- Config boolean values now require the literal lowercase `true`/`false` and report a validation error
  otherwise. Previously, values were parsed with Kotlin's `String.toBoolean()`, which silently accepted
  any casing of `true` (e.g. `TRUE`) as `true` and silently treated everything else, including typos
  like `Yes`, as `false`.
- An unrecognized CLI command now prints an error and returns exit code 1. Previously it fell through
  silently to the help output with exit code 0.
