# DroidAgentKit — `droidagent init` Onboarding Wizard Design

Date: 2026-07-26
Status: Approved

---

## Overview

**Problem:** only the `core` MCP tool group (safe, read-only) is on by default. Every other group —
`device_read`, `device_control`, `perfetto`, `visuals`, `storage`, `network_experimental` — requires
hand-writing `.droidagentkit/config.yaml` with exact `ToolGroup`/`Capability` enum names, cross-referencing
`docs/security-and-permissions.md` to know which capability unlocks which tool. This is real friction, but
the two-layer safety model it protects is not incidental complexity: `CapabilityPolicy.kt`'s
`DefaultOperationPolicy.authorize()` is the only thing standing between an agent and irreversible actions
(app uninstall, permission mutation, arbitrary-app SQLite reads, live MITM interception) on a device that
might be the user's everyday phone. `confirmDestructive` is a flag the *calling agent* sets on its own tool
call, not a human click-through — so the config file is the actual human checkpoint, not a formality.

**Goal:** remove the YAML-authoring friction without removing the checkpoint. A `droidagent init` command
generates the config file for you, either interactively (explaining risk in plain language, one group at a
time) or non-interactively via named `--profile` presets for scripted/CI setup. The generated file stays
fully explicit — no new "profile" concept leaks into the config schema itself — so committing it to a repo
still tells a reviewer exactly what's enabled without needing to know what a profile name currently expands
to.

---

## Command surface

```
droidagent init [--profile <name>[,<name>...]] [--force] [--list-profiles] [--project <path>]
```

- `--project <path>` → project root to write `.droidagentkit/config.yaml` into. Defaults to cwd, same
  convention as `inspect`/`audit`/`devices`.
- No flags, run in a terminal (`System.console() != null`) → interactive prompts (see below).
- `--profile <name>[,<name>...]` → skips prompts entirely; unions the named profiles' groups/capabilities
  and writes the config directly. Comma-separated so CI can combine (e.g. `--profile device-control,storage`).
- `--force` → required to overwrite an existing `.droidagentkit/config.yaml`; without it, `init` refuses
  and exits 1, matching `git init`-style safety (a wizard silently clobbering hand-tuned `allowGradleTasks`
  or `redaction.extraPatterns` edits would be worse than the friction it's meant to remove).
- `--list-profiles` → prints the profile table below and exits 0. No side effects, works without a TTY.
- No flags and no TTY (e.g. piped into a script) → exit 1 with a message pointing at `--list-profiles` and
  `--profile`. Never silently fall back to defaults — a config file's contents must always be a choice
  someone actually made, interactively or via an explicit flag.

## Profiles

Composed from the six askable areas below (a profile is not a schema concept — see "Config output").

| Profile | Expands to |
|---|---|
| `core` | Nothing extra. Included for symmetry with `--list-profiles` and explicit no-op scripting. |
| `device-control` | `device_read` + `device_control` groups. Capabilities: `device_input`, `app_control`, `emulator_control`, `emulator_restore`, `file_export`, `file_import`. Excludes `app_destructive`, `permission_mutation`, `sensitive_diagnostics`. |
| `full` | Everything: `device-control` plus `app_destructive`, `permission_mutation`, `sensitive_diagnostics`, the `perfetto` group, the `visuals` group + `golden_update`, the `storage` group + `app_data_read`, the `network_experimental` group + `network_interception`. |
| `storage` | `storage` group + `app_data_read`, alone. |
| `network-experimental` | `network_experimental` group + `network_interception`, alone. |

`full` is implemented as the union of the other profiles' expansions (plus the two irreversible-action
capabilities), not as an independently hand-maintained list — this is the only place that needs to change
if a new `ToolGroup`/`Capability` is ever added and should default into `full`.

## Interactive prompt flow

Six top-level yes/no questions, one per `ToolGroup` (excluding `CORE`, which is unconditional), each naming
the risk in plain language. Three have a conditional follow-up for the sub-capability that is qualitatively
worse than the rest of that group:

1. **Device diagnostics** — "Let the agent read device diagnostics (dumpsys, memory, battery, permission
   state)? [y/N]"
   - if yes → "Also allow full bugreport capture? This dumps extensive device/app data, including data
     from other apps. [y/N]" → adds `sensitive_diagnostics`
2. **Device control** — "Let the agent tap/swipe/type, open deep links, start/stop emulators, and
   push/pull files? [y/N]"
   - if yes → "Also allow uninstalling apps, clearing app data, and granting/revoking permissions? These
     are irreversible on the device. [y/N]" → adds `app_destructive`, `permission_mutation`
3. **Perfetto tracing** — "Let the agent capture and analyze system performance traces? [y/N]" → adds
   `sensitive_diagnostics` if not already granted by question 1's follow-up (same capability, two
   independent group gates — see "Shared capabilities" below)
4. **Visual regression** — "Let the agent diff and report on UI screenshots? [y/N]"
   - if yes → "Also allow overwriting golden/baseline images? [y/N]" → adds `golden_update`
5. **App storage inspection** — "Let the agent read a debuggable app's own SQLite databases and
   SharedPreferences (read-only)? [y/N]" → adds `app_data_read`
6. **Network capture (experimental)** — "Let the agent intercept emulator network traffic via mitmproxy?
   Requires a debug-trusted CA already installed; emulator-only. [y/N]" → adds `network_interception`

After all six: print the exact groups + capabilities that will be written, then a final "Write this to
.droidagentkit/config.yaml? [y/N]" gate before touching disk. Answering "N" here aborts with no file
written and exit code 0 (an intentional no-op, not an error).

### Shared capabilities

`sensitive_diagnostics` gates both `android_bugreport` (in `device_read`) and both Perfetto tools (in
`perfetto`) — it is one flat capability, not scoped per-group, matching how `Capability` already works
everywhere else in the codebase (`CapabilityPolicy.kt`). Answering "yes" to either question 1's follow-up
or question 3 grants the same capability; answering yes to both is not double-counted, it just means the
capability was already in the accumulating set. A user who wants Perfetto but not bugreports gets exactly
that: the `perfetto` group exposed, `sensitive_diagnostics` granted, but `device_read` never exposed — so
`android_bugreport` stays hidden by the group gate even though the capability happens to be on.

## Config output

No new schema concept. The wizard/profile path always renders the same explicit YAML shape
`DroidAgentConfigLoader` already understands — `mcp.exposedGroups` and `safety.allowCapabilities` as
literal enum-name lists, using the existing lowercase-with-underscores spelling the loader's
`parseToolGroup`/`parseCapability` already accept. Extracted into a shared renderer (see File map) so the
existing `audit --write-agents` starter-config path and the new `init` path can't drift into two different
serializations of the same config shape:

```yaml
schemaVersion: 1
project:
  name: inferred
safety:
  allowGradleTasks:
    - ":*:test*UnitTest"
    - ":*:lint*"
    - ":*:assemble*Debug"
    - ":*:*AndroidTest"
    - ":*:validate*ScreenshotTest"
  allowCapabilities:
    - device_input
    - app_control
    - emulator_control
    - emulator_restore
    - file_export
    - file_import
  maxCommandSeconds: 600
mcp:
  exposedGroups:
    - device_read
    - device_control
reports:
  outputDir: "build/droidagentkit"
redaction:
  enabled: true
  extraPatterns: []
```

(`--profile device-control` output shown, without the bugreport follow-up.)

`mcp:` and `allowCapabilities:` are omitted entirely when empty (the `core`-only case), matching today's
`AgentDocumentWriter.defaultConfigYaml()` output byte-for-byte so existing behavior doesn't change for
anyone who never runs `init`.

## Error handling

| Condition | Behavior |
|---|---|
| `.droidagentkit/config.yaml` exists, no `--force` | Print `.droidagentkit/config.yaml already exists. Rerun with --force to regenerate it.`, exit 1. No prompts run, no file touched. |
| No TTY and no `--profile` | Print a message pointing at `--list-profiles`/`--profile`, exit 1. |
| Unknown name in `--profile` | Print the invalid name(s) plus the same table as `--list-profiles`, exit 1. |
| Interactive flow, final confirm answered "N" | Print "Aborted, no file written.", exit 0. |
| `--list-profiles` | Print the table, exit 0, regardless of TTY/existing config/`--force`. |

---

## Testing

Real fixtures/filesystem, no mocks, following existing conventions (`DeviceControlToolProviderTest`'s
fake-`adb`-script pattern for hermetic CLI tests).

- **Profile expansion** (new `ProfileCatalogTest`): each named profile → exact expected `Set<ToolGroup>` +
  `Set<Capability>`; `full`'s expansion equals the union of every other profile's expansion plus the two
  irreversible capabilities (asserted structurally, not by copy-pasted literal lists, so this test fails
  loudly if a future profile is added without updating `full`'s composition); comma-combining two profiles
  unions correctly; unknown profile name is rejected with the invalid name surfaced.
- **YAML rendering** (new `ConfigYamlTest` in `toolbox-core`): empty groups/capabilities renders byte-identical
  to today's `AgentDocumentWriter.defaultConfigYaml()`; non-empty renders both list sections; round-trips
  through `DroidAgentConfigLoader.load()` back to the same `DroidAgentConfig` for every profile.
- **CLI parsing** (extend `cli/src/test/kotlin/com/droidagentkit/cli/CliParserTest.kt` if it exists, else
  new `InitCommandParserTest`): `--profile` comma-splitting, `--force`, `--list-profiles`, unknown flag
  rejection (via the existing `CliCommandRegistry` validation path).
- **Refuse-without-force / non-TTY** (new `InitCommandTest`): existing `.droidagentkit/config.yaml` without
  `--force` writes nothing and exits 1; with `--force` regenerates; no-TTY-no-profile exits 1 without
  writing.
- **Interactive flow**: feed canned y/n answers via an injected `Iterator<String>` (not real stdin — mirrors
  how `DroidAgentStdioServer` already abstracts line reading), assert the resulting groups/capabilities set
  matches hand-traced expectations for a few representative answer sequences (all-no, all-yes, mixed with
  one follow-up declined), and that declining the final confirm writes nothing.

---

## File map

| File | Change |
|---|---|
| `toolbox-core/src/main/kotlin/com/droidagentkit/core/ConfigYaml.kt` (new) | `object ConfigYaml { fun render(groups: Set<ToolGroup>, capabilities: Set<Capability>): String }` — shared explicit-YAML renderer |
| `auditor-cli/.../ReadinessAuditor.kt` (`AgentDocumentWriter.defaultConfigYaml`) | Replace private hardcoded string with `ConfigYaml.render(emptySet(), emptySet())` |
| `cli/src/main/kotlin/com/droidagentkit/cli/ProfileCatalog.kt` (new) | Named profile → `(Set<ToolGroup>, Set<Capability>)` expansion, `full` composed from the others |
| `cli/src/main/kotlin/com/droidagentkit/cli/InitWizard.kt` (new) | Interactive prompt flow (injected line-reader), builds the same `(Set<ToolGroup>, Set<Capability>)` shape as profiles |
| `cli/src/main/kotlin/com/droidagentkit/cli/CliCommand.kt` | Add `data class Init(val profiles: List<String>, val force: Boolean, val listProfiles: Boolean, val project: String) : CliCommand()` |
| `cli/src/main/kotlin/com/droidagentkit/cli/CliCommandSpec.kt` | Register `init` with `--profile`, `--force`, `--list-profiles`, `--project` |
| `cli/src/main/kotlin/com/droidagentkit/cli/DroidAgentCliParser.kt` | Parse `init`, comma-split `--profile` |
| `cli/src/main/kotlin/com/droidagentkit/cli/DroidAgentMain.kt` | `is CliCommand.Init -> init(command)`: refuse/force check, dispatch to `ProfileCatalog` or `InitWizard`, write via `ConfigYaml.render` |
| `toolbox-core/src/test/kotlin/com/droidagentkit/core/ConfigYamlTest.kt` (new) | Rendering + round-trip tests above |
| `cli/src/test/kotlin/com/droidagentkit/cli/ProfileCatalogTest.kt` (new) | Profile expansion tests above |
| `cli/src/test/kotlin/com/droidagentkit/cli/InitCommandTest.kt` (new) | Refuse/force/non-TTY/interactive-flow tests above |
| `README.md`, `docs/security-and-permissions.md` | Document `droidagent init`, the profile table, and the six prompt areas |

## Constraints preserved

- Zero new third-party dependencies — prompt loop is `readlnOrNull()`/`System.console()`, JDK stdlib only.
- No change to `DroidAgentConfig`/`DroidAgentConfigLoader` schema or parsing — `init` only ever produces
  config files the existing loader already understands.
- `audit --write-agents`'s existing starter-config behavior (write only if absent, `core`-only content) is
  unchanged for anyone who never runs `init` — verified by the byte-identical rendering test above.
- Preserves the "explicit allowlists" security principle from `CLAUDE.md`: a committed config file's
  contents are never a name lookup, only literal enum lists.
