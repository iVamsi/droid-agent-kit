# CLI reference

Binary: `droidagent` (from `./gradlew :cli:installDist` or the GitHub Release fat jar via
`java -jar droidagent-cli-<version>.jar <command> …`).

The npm launcher runs `serve-mcp --transport stdio --project auto` when invoked bare, and forwards
any other command straight through: `npx -y @droidagentkit/launcher doctor`.

| Command | Purpose |
|---------|---------|
| `doctor` | Check environment, config, and tooling before anything else (`--format text\|json`); exits non-zero if something is broken |
| `serve-mcp` | MCP server (`--transport stdio\|http`, `--host`, `--port`, `--bearer-token-file`, `--allow-remote`, `--projects-root`, `--project`) |
| `install-mcp` | Register with Codex / Claude Code / Cursor / Zed / VS Code / Android Studio (`--targets`, `--dry-run`, `--bin`, `--projects-root`) |
| `init` | Write user policy grants + seed project config (`--profile`, `--force`, `--list-profiles`) |
| `inspect` | Static project inspection (`--format markdown\|json`, `--output`) |
| `audit` | Readiness score + optional `AGENTS.md` (`--write-agents`, `--redact-public`, `--fail-under`) |
| `devices` | List adb devices (`--format`) |
| `gradle` | Run an allowlisted Gradle task (`--task`) |
| `snapshot` | Device screenshot |
| `visuals` | `report` / `update-goldens` |

Run `droidagent <command> --help` for flags. Env for `--project auto`: see
[`docs/troubleshooting.md`](troubleshooting.md).

## Registry identity

The MCP registry lists this server as `io.github.iVamsi/android-agent-kit`.

It was renamed from `io.github.iVamsi/droidagentkit` for one reason: the registry's `search`
matches the server **name only** — not the description, not the title. Verified directly, the terms
`perfetto`, `logcat`, and `gradle` all appear in our registry description and return zero results,
while every hit for `android` carries it in the name. Since `name` is the registry's primary key,
being findable meant publishing under a new one.

Nothing changed for users: installs reference the npm package `@droidagentkit/launcher`, which kept
its name.

The rename has two halves, and the second is easy to forget:

1. `distribution/server.json` `name` and `distribution/npm-launcher/package.json` `mcpName` must
   match exactly — the registry verifies ownership through that npm field. Publishing happens
   automatically on the next release tag.
2. **After** that release succeeds, run `scripts/deprecate-old-registry-listing.sh --confirm` once,
   to mark the old listing deprecated with a pointer to the new one. Skipping this leaves two
   `active` listings competing, with the stale one giving no hint where the project went.
