# Changelog

All notable changes to DroidAgentKit are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and version numbers follow the alpha
pre-release convention `0.y.z-alpha` until a stable 1.0 release.

## Unreleased

### Added

- `droidagent init` command generates `.droidagentkit/config.yaml` interactively (a six-question wizard) or
  via named `--profile` presets (`core`, `device-control`, `full`, `storage`, `network-experimental`).
- Release pipeline (`.github/workflows/release.yml`, tag-triggered) builds a `droidagent-cli` fat jar via
  the `com.gradleup.shadow` plugin, publishes it as a GitHub Release asset with a SHA-256 checksum, and
  publishes `@droidagentkit/launcher` to npm using OIDC trusted publishing (no stored token). The npm
  launcher now auto-downloads, verifies, and caches that jar on first run instead of requiring a local
  Gradle build; see `docs/adrs/0001-packaging.md`.

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
