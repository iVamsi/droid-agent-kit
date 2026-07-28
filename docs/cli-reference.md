# CLI reference

Binary: `droidagent` (from `./gradlew :cli:installDist` or the GitHub Release fat jar via
`java -jar droidagent-cli-<version>.jar <command> …`).

The npm launcher (`npx -y @droidagentkit/launcher`) only runs `serve-mcp --transport stdio --project auto`.

| Command | Purpose |
|---------|---------|
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
