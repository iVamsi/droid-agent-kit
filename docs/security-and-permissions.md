# Security and Permissions Model

DroidAgentKit is local-only by default.

## Command Safety

- MCP v1 does not expose arbitrary shell execution.
- Gradle tasks must match configured allowlist patterns.
- adb install and input-style actions are controlled by config.
- Device-specific commands require explicit serials.

Default `.droidagentkit/config.yaml`:

```yaml
schemaVersion: 1
project:
  name: inferred
safety:
  allowGradleTasks:
    - ":*:test*UnitTest"
    - ":*:lint*"
    - ":*:assemble*Debug"
  allowAdbInput: false
  allowAppInstall: true
  allowEmulatorStart: false
  maxCommandSeconds: 600
reports:
  outputDir: "build/droidagentkit"
redaction:
  enabled: true
  extraPatterns: []
```

## Redaction

Built-in redaction covers:

- `Authorization: Bearer ...`
- Google-style API keys beginning with `AIza`
- password assignments
- token and secret assignments

Extra project-specific regexes can be added under `redaction.extraPatterns`.
