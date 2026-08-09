# DroidAgentKit

[![CI](https://github.com/iVamsi/droid-agent-kit/actions/workflows/ci.yml/badge.svg)](https://github.com/iVamsi/droid-agent-kit/actions/workflows/ci.yml)
[![CodeQL](https://github.com/iVamsi/droid-agent-kit/actions/workflows/codeql.yml/badge.svg)](https://github.com/iVamsi/droid-agent-kit/actions/workflows/codeql.yml)
[![OpenSSF Scorecard](https://api.securityscorecards.dev/projects/github.com/iVamsi/droid-agent-kit/badge)](https://scorecard.dev/viewer/?uri=github.com/iVamsi/droid-agent-kit)
[![License: Apache 2.0](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/kotlin-JVM-7f52ff.svg)](https://kotlinlang.org)
[![MCP](https://img.shields.io/badge/MCP-compatible-informational.svg)](https://modelcontextprotocol.io)

Give your AI coding agent real Android tools — Gradle, adb, logcat, lint, crash triage — through a local, permissioned MCP server instead of raw shell access.

DroidAgentKit helps you work on Android projects with AI agents. It runs on your machine, talks to your Gradle tree and (when you allow it) your emulator or device, and gives agents structured answers instead of raw shell output.

**Android-first, deliberately.** Cross-platform automation servers cover iOS too but stop at tapping the screen. DroidAgentKit goes the other way: deep into the Android build system and toolchain — Gradle task allowlists, lint, R8 crash triage, Perfetto traces, app storage, Compose visual regression. There is no iOS support and none planned.

Everything stays local. Commands go through allowlists. Output is redacted before it comes back to the agent. Artifacts land under `build/droidagentkit` by default.

## What you get

Three pieces that work on their own or together:

**MCP server** — A [Model Context Protocol](https://modelcontextprotocol.io) server your agent can call. Inspect the project, run safe Gradle tasks, capture logs and screenshots, triage crashes, run lint, profile builds, and more. Optional groups add device diagnostics, bounded device control, Perfetto tracing, visual regression, read-only app storage inspection, and experimental emulator network capture.

**Readiness auditor** — Scores how agent-friendly your repo is, lists risks, and can generate `AGENTS.md`, a project skill file, and a starter `.droidagentkit/config.yaml`. Use `--redact-public` when you need a share-safe report.

**Visual regression kit** — Pixel-diff reports for Compose/UI captures, a Gradle plugin, and a JUnit rule. MCP tools can diff goldens, generate reports, and update goldens when you explicitly confirm.

## MCP tools at a glance

The **core** group is on by default (16 tools): project inspect, allowlisted Gradle runs, device list, install/launch, logcat, screenshot, accessibility snapshot, report bundle, lint, crash triage, dependency check, build performance, test run, build diagnose, and `android_doctor` (an environment preflight you can run when something fails for no obvious reason).

Turn on more by exposing tool groups when you start the server (see [security model](docs/security-and-permissions.md)):

| Group | What it adds |
| ----- | ------------ |
| `device_read` | Permission audit, dumpsys summaries, memory/battery, bugreport, streaming logcat jobs |
| `device_control` | Emulator control, app uninstall/clear, intents, permissions, tap/swipe/type, file push/pull, small action flows, **flow recording** |
| `perfetto` | Trace capture and Trace Processor analysis (jank, CPU, contention, binder latency, …) |
| `visuals` | Pixel diff, visual report, golden updates |
| `storage` | Read-only SQLite, SharedPreferences, and file tree for **debuggable** apps |
| `network_experimental` | Emulator-only mitmproxy capture + redacted HAR query (requires your own debug CA) |

Dangerous actions (uninstall, clear data, proxy install, golden overwrite, …) need the right capability in config **and** `confirmDestructive=true` on the call. The capability is the real boundary — `confirmDestructive` guards against an accidental call, not a hostile one, since the agent supplies it. See [the threat model](docs/security-and-permissions.md#threat-model-what-this-does-and-does-not-protect-against).

Report bundles include a **capability summary**: which groups are exposed, which capabilities are enabled, and what you still need installed (adb, trace processor, mitmproxy, …). That summary is informational — it does not change your readiness score.

Full tool list and capability IDs: [`docs/security-and-permissions.md`](docs/security-and-permissions.md).

## Quick start

**Prerequisites:** Node 18+ (install-time only for the launcher). A JDK 17+ is used if you have
one — on `PATH`, in `JAVA_HOME`, or named by `DROIDAGENT_JAVA` — and otherwise a pinned Eclipse
Temurin JRE is downloaded once, SHA-256 verified, and cached under `~/.droidagentkit/jre`.
Optional: Android `adb` / platform-tools for device tools; set `ANDROID_HOME` or configure
`adbPath` in the user policy (`~/.droidagentkit/policy.yaml`). Run `doctor` to check all of this.

The `@droidagentkit/launcher` npm package downloads and caches the matching `droidagent-cli` jar from
[GitHub Releases](https://github.com/iVamsi/droid-agent-kit/releases) the first time it runs,
verifying its SHA-256 before executing it.

```bash
npx -y @droidagentkit/launcher --version
```

Run it bare to start the MCP server, or pass any CLI command straight through:

```bash
npx -y @droidagentkit/launcher doctor
npx -y @droidagentkit/launcher init --profile device-control
npx -y @droidagentkit/launcher audit --project /path/to/android --write-agents
```

Point your agent's MCP config at it directly, e.g. for Claude Code:

```bash
claude mcp add droidagentkit -- npx -y @droidagentkit/launcher
```

Or install with one click:

[![Install in Cursor](https://img.shields.io/badge/Cursor-Install_DroidAgentKit-0098FF?logo=cursor&logoColor=white)](cursor://anysphere.cursor-deeplink/mcp/install?name=droidagentkit&config=eyJjb21tYW5kIjoibnB4IiwiYXJncyI6WyIteSIsIkBkcm9pZGFnZW50a2l0L2xhdW5jaGVyIl19)
[![Install in VS Code](https://img.shields.io/badge/VS_Code-Install_DroidAgentKit-007ACC?logo=visualstudiocode&logoColor=white)](https://insiders.vscode.dev/redirect/mcp/install?name=droidagentkit&config=%7B%22command%22%3A%22npx%22%2C%22args%22%3A%5B%22-y%22%2C%22%40droidagentkit%2Flauncher%22%5D%7D)

### Building from source

If you're contributing, or want to run a version that isn't released yet:

```bash
./gradlew :cli:installDist
./cli/build/install/droidagent/bin/droidagent install-mcp
```

Register it once for your agent (Codex, Claude Code, Cursor, Zed, VS Code, Android Studio, …).
Preview without writing files with `install-mcp --dry-run`. For Android Studio across many
projects under one folder:

```bash
droidagent install-mcp --targets android-studio --projects-root ~/Developer/StudioProjects
```

Details and per-host config paths: [`docs/easy-mcp-installation.md`](docs/easy-mcp-installation.md).
Security model + full tool tables: [`docs/security-and-permissions.md`](docs/security-and-permissions.md).
CLI reference + troubleshooting: [`docs/cli-reference.md`](docs/cli-reference.md), [`docs/troubleshooting.md`](docs/troubleshooting.md).

## Try it in chat

After `install-mcp`, open an Android project and ask things like:

- "Inspect this Android project with DroidAgentKit."
- "Run the safe unit tests for the app module."
- "Audit this repo for agent readiness and write AGENTS.md."
- "Capture logcat and triage the crash."
- "What's in the app's SQLite schema?" (with `storage` + `app_data_read` enabled)
- "Capture a Perfetto trace and summarize frame jank." (with `perfetto` enabled)
- "Record what you just did as a flow I can replay." (with `device_control` enabled)

You describe the goal; the agent picks tools from the list it was given at startup.

## How it works

1. Your agent starts `droidagent serve-mcp --transport stdio --project auto` (or talks to the localhost HTTP endpoint Android Studio uses on macOS).
2. MCP handshake (`initialize`, then `tools/list`).
3. The agent calls `android_*` tools via `tools/call`. You don't call them by hand.
4. `--project auto` resolves the active project from `CLAUDE_PROJECT_DIR`, `CODEX_WORKSPACE` /
   `CODEX_PROJECT_DIR`, `GEMINI_PROJECT_DIR` / `GEMINI_WORKSPACE`, `CURSOR_PROJECT_DIR` /
   `CURSOR_WORKSPACE`, `PWD`, then cwd — one user-wide install, many projects.

Optional **resources** and **workflow prompts** are available on non–Android Studio hosts. Tool listings include hints like `readOnlyHint` and `destructiveHint` where relevant.

## CLI without an agent

```bash
droidagent inspect --project /path/to/android --format markdown
droidagent audit --project /path/to/android --write-agents
droidagent audit --project /path/to/android --redact-public
droidagent devices --format json
droidagent serve-mcp --transport stdio --project auto
```

Run `droidagent --help` or `droidagent <command> --help` for flags.

### Generating a config file

```bash
droidagent init                          # interactive: six yes/no prompts explaining risk per area
droidagent init --profile device-control # non-interactive, for scripted setup
droidagent init --profile full           # everything, including storage and network capture
droidagent init --list-profiles          # see all profile names without writing anything
```

Only the `core` tool group (safe, read-only) is enabled by default. `droidagent init` is the fastest way to
turn on more without hand-writing `.droidagentkit/config.yaml` — see
[docs/security-and-permissions.md](docs/security-and-permissions.md) for what each group and capability
actually grants an agent.

## Configuration

Put `.droidagentkit/config.yaml` in your Android project root to tune Gradle allowlists, adb permissions, capability flags, redaction patterns, and report output paths. The auditor can seed a safe default when you run `audit --write-agents`.

## Status

Alpha. MCP tool names and schemas are treated as stable, but behavior will tighten as we learn from real use. No telemetry. No cloud dependency for core workflows.

## More docs

- [Security and permissions](docs/security-and-permissions.md) — capabilities, tool groups, destructive ops
- [Easy MCP installation](docs/easy-mcp-installation.md) — per-host setup
- [CLAUDE.md](CLAUDE.md) — build commands and architecture notes for contributors
