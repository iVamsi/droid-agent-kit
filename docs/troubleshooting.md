# Troubleshooting

## Start here: `droidagent doctor`

```bash
npx -y @droidagentkit/launcher doctor --project /path/to/android
```

Checks the Java version, the user policy and project config (including keys that were ignored
because a project file cannot grant authority), `adb` and any optional binaries the enabled tool
groups need, `ANDROID_HOME`, and whether artifacts can be written. It exits non-zero only for
things that are actually broken -- missing optional tooling is reported as a warning, so a clean
`doctor` on a project-only setup is expected not to mention devices at all. Add `--format json`
to consume it from a script.

## `npx -y @droidagentkit/launcher` fails

1. Confirm the package exists: `npm view @droidagentkit/launcher version`.
2. Confirm a GitHub Release jar exists for that version
   (`droidagent-cli-<version>.jar` + `.sha256`).
3. Need JDK 17+ on `PATH` (`java -version`).
4. Override the binary: `DROIDAGENT_BIN=/path/to/droidagent npx -y @droidagentkit/launcher`.

## MCP connected but device tools fail

- Install Android platform-tools and ensure `adb` is on `PATH`, or set `safety.adbPath` in
  `~/.droidagentkit/policy.yaml`.
- Pass an explicit `deviceSerial` (from `android_devices_list` / `droidagent devices`).

## `--project auto` picked the wrong directory

Resolution order: `CLAUDE_PROJECT_DIR` → `CODEX_WORKSPACE` → `CODEX_PROJECT_DIR` →
`GEMINI_PROJECT_DIR` → `GEMINI_WORKSPACE` → `CURSOR_PROJECT_DIR` → `CURSOR_WORKSPACE` → `PWD` → cwd.
Pass `--project /absolute/path` to pin.

## Capabilities / tool groups missing after `init`

Grants live in the **user policy** (`~/.droidagentkit/policy.yaml`), not the project
`.droidagentkit/config.yaml`. Re-run `droidagent init --profile … --force`, or edit the
policy file. Restart the MCP server after changes.

## Gradle task blocked

Task must match `safety.allowGradleTasks` in the project config (catch-all `*` is rejected there).
To allow any task, set `safety.allowAnyGradleTask: true` in the user policy.

## HTTP MCP won't bind

Default bind is loopback only. Non-loopback hosts require `--allow-remote`. Prefer
`--bearer-token-file` over printing the token.
