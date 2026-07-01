# Changelog

All notable changes to DroidAgentKit are documented here. This project uses date-based alpha
development until a first tagged release.

## Unreleased

### Changed

- `DroidAgentConfigLoader.load()` now returns `ConfigLoadResult` (`Loaded` or `Invalid`) instead of a
  bare `DroidAgentConfig`. `schemaVersion` and value types (booleans, numbers) are validated; malformed
  config previously fell back to defaults silently or threw an uncaught exception.
- CLI commands now reject unknown flags and print `--help` usage generated from a command registry.
  Previously, unrecognized flags were silently ignored. The `visuals` command still accepts arbitrary
  passthrough flags, since its option set varies by action.
