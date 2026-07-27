# Prepare an Android Repo for Agents

Run a static readiness audit:

```bash
droidagent audit --project .
```

Generate local instructions and config:

```bash
droidagent audit --project . --write-agents
```

Generated files:

- `AGENTS.md` or `AGENTS.generated.md` when human instructions already exist.
- `.agents/skills/android-project/SKILL.md`.
- `.droidagentkit/config.yaml`.
- `build/droidagentkit/audit/readiness-report.md`.
- `build/droidagentkit/audit/readiness-report.json`.

Readiness levels:

- `90-100`: agent-ready.
- `75-89`: usable with review.
- `50-74`: agent-assisted only for small tasks.
- `0-49`: unsafe for autonomous changes.

The auditor works offline from local files. It scans Gradle modules, Android manifests, version catalogs, CI config, test source sets, existing instruction files, visual testing hooks, and likely tracked secrets.

## Enabling more than the default tool group

`audit --write-agents` seeds `.droidagentkit/config.yaml` with the safe, read-only `core` group only — it
never overwrites an existing config file. To turn on more (device control, storage inspection, visuals,
etc.), use `droidagent init` instead:

```bash
droidagent init                          # interactive: six yes/no prompts explaining risk per area
droidagent init --profile device-control # non-interactive, for scripted setup
```

If `audit --write-agents` already ran first, `.droidagentkit/config.yaml` exists and `init` will refuse to
touch it — rerun with `--force` to regenerate:

```bash
droidagent init --force --profile device-control
```

See [security-and-permissions.md](security-and-permissions.md) for what each group and capability grants.
