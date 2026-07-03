# Broader Agent/IDE Support (Workstream E) — Design

Date: 2026-07-02
Status: Approved, ready for `writing-plans`

## Goal

Extend `install-mcp` with first-class support for Cursor, Zed, and VS Code (GitHub Copilot Chat's
config format), per [2026-07-01-opensource-roadmap.md](2026-07-01-opensource-roadmap.md), workstream E.
Today `install-mcp` only knows Codex (TOML file write) and Claude Code (shells out to `claude mcp add`),
plus a generic JSON fallback for anything else.

## Current state

- `McpInstaller` (`cli/src/main/kotlin/com/droidagentkit/cli/McpInstaller.kt`) has `McpInstallTarget`
  (`CODEX`, `CLAUDE`, `GENERIC`), each handled by its own branch in `install()`.
- Codex: writes/merges a marker-delimited TOML block directly into `~/.codex/config.toml` via string
  regex replacement (safe because TOML supports comments as markers).
- Claude: shells out to `claude mcp add --scope user ...` (Claude Code manages its own config via CLI,
  not something droid-agent-kit hand-edits).
- Generic: prints a JSON stdio config block for manual use.
- `cli/build.gradle.kts` has no JSON-parsing dependency today.

## Research findings (verified against official docs, not recalled from training data)

| Target | Config path | Top-level key | Server entry shape |
|---|---|---|---|
| Cursor | `~/.cursor/mcp.json` (Windows: `%USERPROFILE%\.cursor\mcp.json`, same relative to `user.home`) | `mcpServers` | `{command, args}` |
| Zed | mac/linux: `~/.config/zed/settings.json`; windows: `%APPDATA%\Zed\settings.json` | `context_servers` | `{command, args, env}` |
| VS Code (read by GitHub Copilot Chat) | mac: `~/Library/Application Support/Code/User/mcp.json`; linux: `~/.config/Code/User/mcp.json`; windows: `%APPDATA%\Code\User\mcp.json` | `servers` | `{type: "stdio", command, args}` |

Sources: [Cursor MCP docs](https://cursor.com/docs/mcp), [Zed MCP docs](https://zed.dev/docs/ai/mcp),
[VS Code MCP configuration reference](https://code.visualstudio.com/docs/agents/reference/mcp-configuration).

Zed's config lives inside the user's entire shared editor `settings.json` — not a dedicated MCP file —
so any merge logic must touch only the `context_servers.droidagentkit` key and leave every other setting
in that file untouched. VS Code's file is dedicated to MCP but still commonly holds other unrelated
server entries a user configured for other purposes, which must also survive a merge.

## Scope decisions from brainstorming

- **User-scope only**, matching CLAUDE.md's existing "Keep install-mcp idempotent and user-scope by
  default" constraint and the current Codex/Claude behavior — no project-level (`.cursor/mcp.json`,
  `.vscode/mcp.json`) variants this round.
- **VS Code target keyword is `"vscode"`**, not `"copilot"` — the config format is VS Code's own generic
  MCP support (any MCP-aware extension reads it, GitHub Copilot Chat included), not something
  Copilot-specific.
- **Real JSON parsing via `kotlinx-serialization-json`**, added to the `cli` module the same way it was
  added to `mcp-server` for SARIF parsing (workstream D) — extends an existing precedent/exception to the
  project's dependency policy rather than creating a new one. A hand-rolled JSON merger was considered
  and rejected: getting general-purpose JSON round-tripping right (preserving arbitrary existing
  structure, especially for Zed's large shared settings file) is real engineering risk not worth
  re-deriving when the dependency is already approved and present in the same module family.

## Architecture

One shared `McpJsonConfigMerger` object in `cli` handles the parse-merge-write logic for all three new
targets:

```kotlin
object McpJsonConfigMerger {
    fun merge(existingJson: String, topLevelKey: String, serverName: String, serverConfig: JsonObject): String
}
```

It parses `existingJson` (or starts from `{}` if blank/the file doesn't exist yet), ensures a
`JsonObject` exists at `topLevelKey` (creating it if absent, otherwise reusing what's there), upserts
`serverName -> serverConfig` inside that nested object — replacing any prior `droidagentkit` entry while
preserving every sibling server entry — and leaves every other top-level key in the document completely
untouched. Cursor/Zed/VS Code each call this with their own `topLevelKey` string and their own
`serverConfig` `JsonObject` shape (built via `kotlinx.serialization.json`'s `buildJsonObject { }` DSL);
none of them duplicate merge logic.

Path resolution for Zed and VS Code is OS-aware, following the same `System.getProperty("os.name")`
pattern already used elsewhere in this codebase (`DroidAgentMcpDispatcher`, `DroidAgentMain`) for
Windows detection. Cursor's path is the same relative-to-home path on every OS, so it needs no branching.

`McpInstaller` gains an injectable `osName: String = System.getProperty("os.name")` constructor
parameter, matching its existing `home`/`commandExecutor` injection pattern, so tests can deterministically
exercise all three OS path branches regardless of which OS the test suite actually runs on.

## Error handling

If an existing config file contains invalid JSON (e.g. a user hand-edited it incorrectly), the merge
attempt is wrapped so a parse failure produces a warning message
(`"Could not update <path>: invalid JSON, check the file manually."`) and skips the write for that
target — it never crashes the whole `install()` call, corrupts the file, or blocks other targets in the
same `--targets` list from being processed. Dry-run behavior matches Codex: the merge is always computed,
only the actual file write is skipped when `dryRun` is true.

## `McpInstallTargets.parse()` changes

- New keyword mappings: `"cursor"` → `CURSOR`, `"zed"` → `ZED`, `"vscode"` → `VSCODE`.
- `"all"` now expands to all 6 targets (`codex`, `claude`, `generic`, `cursor`, `zed`, `vscode`) instead
  of 3 — matching the existing intent of "all" meaning "every supported target."

## Testing

Extends the existing `McpInstallerTest.kt` pattern: `Files.createTempDirectory` for `home`, no mocks.
Tests cover: idempotent re-install (matching the existing Codex test's shape — install twice, assert one
entry, not two), preservation of a pre-existing sibling server entry when installing `droidagentkit`,
preservation of unrelated top-level keys (critical for the Zed case, simulating a `settings.json` with
other editor settings already present), the invalid-existing-JSON warning path, and OS-specific path
resolution for all three `osName` branches via the new injectable parameter.

## File map

| File | Change |
|---|---|
| `cli/build.gradle.kts` | Add `kotlin("plugin.serialization")` plugin + `kotlinx-serialization-json` dependency |
| `cli/src/main/kotlin/com/droidagentkit/cli/McpInstaller.kt` | Add `CURSOR`/`ZED`/`VSCODE` targets, `McpJsonConfigMerger`, OS-aware path resolution, `osName` constructor param |
| `cli/src/test/kotlin/com/droidagentkit/cli/McpInstallerTest.kt` | Extended |
| `docs/easy-mcp-installation.md` | Document the 3 new targets |
| `README.md` | Update `install-mcp` usage/target list if enumerated there |

## Constraints preserved

- Zero third-party dependencies is the project default, with the `kotlinx-serialization-json` exception
  now extended to a second module (`cli`), matching the precedent set in `mcp-server`.
- No network calls.
- `install-mcp` stays idempotent and user-scope by default (CLAUDE.md's existing Agent Boundaries rule).
- No mocks in tests — fixture-based, matching repo convention.
