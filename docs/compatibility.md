# Compatibility and stability contract

What "stable" means for each surface, so you can tell which changes will break you and which will
not. The project is pre-1.0; this document says where that actually bites.

## Surfaces

| Surface | Stability now | What may change |
|---|---|---|
| **MCP tool names** | Stable | Nothing removed or renamed without a major bump. New tools are added freely. |
| **MCP tool input schemas** | Additive-only | New optional properties may appear. Existing properties keep their names, types, and meaning. A required property is never added to an existing tool. |
| **MCP tool output** | Additive-only | New top-level keys may appear. `status`, `summary`, `artifacts`, `warnings` keep their shapes. |
| **Result `status` values** | Stable | `success`, `partial`, `failed`, `blocked`, `unsupported`. No new values without a minor bump and a changelog note. |
| **Warning codes** | Not stable | Strings like `no-match` or `flow-had-failures` are for humans. Do not branch on them. |
| **CLI subcommands and flags** | Stable | Flags are not removed or repurposed. New flags may appear with defaults preserving current behavior. |
| **CLI stdout format** | Not stable except `--format json` | Human-readable output may be reworded at any time. Parse the JSON. |
| **CLI exit codes** | Stable | `0` success, `1` failure, `2` usage error or threshold breach. |
| **Config schema** | Versioned | `schemaVersion: 1`. A breaking change bumps it, and the loader rejects a version it does not know rather than guessing. |
| **Config keys** | Additive-only within a schema version | New keys default to current behavior. A grant key is never silently moved from the policy to the project file. |
| **Artifact layout** | Not stable | Paths under `build/droidagentkit/` may be reorganized. Use the `artifacts` array in tool output, never a hardcoded path. |
| **`opaqueId` on artifacts** | Stable per run, not across runs | It identifies an artifact within one result; it is not a durable key. |

## Versions

- **JDK floor: 17.** Raising it is a breaking change. Tested against 17 and 21.
- **MCP protocol: `2025-11-25`.** The server accepts that version and answers with it. Support for
  additional versions may be added; support for the current one will not be dropped in a minor.
- **Gradle, AGP, Kotlin in *your* project:** the inspector parses text and executes nothing, so
  there is no supported-version matrix. Unrecognized shapes degrade to a partial report with a
  warning rather than an error.

## What "alpha" currently means

Tool names and schemas are already treated as stable API, and the pinned tool-manifest hash in CI
fails the build on any accidental drift. The alpha label reflects two things that are *not* settled:

1. **Defaults may tighten.** A capability or default allowlist entry may become more restrictive in
   a minor release if it turns out to grant more than users expect. Loosening never happens
   silently.
2. **Behavior under partial failure is still being learned.** Which situations produce `partial`
   versus `failed` may be adjusted as real usage shows what is more useful.

## Exit criteria for 1.0

- No known trust-model breaks (a project config cannot produce an effective config more permissive
  than the user policy, asserted by property test, not by example).
- Two consecutive releases with no security fix.
- The 3-OS test matrix blocking rather than advisory.
- Device tools verified against a real emulator on a schedule, not only against fakes.
- ~~A dist-tag policy in the release workflow~~ **done.** `scripts/npm-dist-tag.sh` computes the
  tag: stable goes to `latest`, prereleases to `next`, and a prerelease additionally claims
  `latest` only while no stable has ever shipped (otherwise `npx`, which resolves `latest`, would
  break pre-1.0). Table-driven tests run on every PR.
