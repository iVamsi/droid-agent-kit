# Contributing to DroidAgentKit

Thanks for your interest in contributing! DroidAgentKit is a small, alpha-stage Kotlin/JVM toolkit —
contributions of all sizes are welcome, from typo fixes to new MCP tools.

## Development setup

This is a Gradle Kotlin/JVM monorepo. No Android SDK is required to build or test it.

```bash
./gradlew test              # run the full test suite
./gradlew :cli:installDist  # build the CLI distribution
```

To run tests for a single module:

```bash
./gradlew :toolbox-core:test
./gradlew :android-inspector:test
./gradlew :mcp-server:test
./gradlew :auditor-cli:test
./gradlew :visuals-core:test
./gradlew :visuals-gradle-plugin:test
./gradlew :cli:test
```

To run a single test class:

```bash
./gradlew :toolbox-core:test --tests "com.droidagentkit.core.ConfigAndSafetyTest"
```

## Project layout

See `CLAUDE.md`'s "Architecture" section for the current module list and what each one owns
(`toolbox-core`, `android-inspector`, `mcp-server`, `auditor-cli`, `visuals-core`,
`visuals-gradle-plugin`, `visuals-android-test`, `cli`).

## Before you open a PR

- Run `./gradlew test` and `./gradlew ktlintCheck` locally — both run in CI and must pass.
- Follow Conventional Commits for commit messages (`feat:`, `fix:`, `docs:`, `refactor:`, `chore:`, ...).
- Update `docs/` if you changed a public CLI subcommand, an MCP tool name/schema, or Gradle plugin
  behavior — these are documented as stable public API in `CLAUDE.md`.
- Add or update tests for behavior changes. This project does not use mocks in tests — prefer real
  fixtures (e.g. `Files.createTempDirectory`) or fakes over mocking frameworks.
- Fill out the PR template checklist.

## Constraints that apply to every contribution

DroidAgentKit's alpha security model is non-negotiable — PRs that weaken it will be asked to change:

- **No arbitrary shell execution.** All command execution goes through `ProcessRunner` with an
  explicit Gradle-task allowlist (`SafetyConfig.allowGradleTasks`).
- **No telemetry or network calls from the toolkit itself.** Everything runs local-only.
- **All tool output is redacted** through `Redactor` before being returned to agents.
- **No new third-party dependencies** without discussion first — `toolbox-core` has zero third-party
  dependencies by design, and the rest of the project keeps them to a bare minimum (the one existing
  exception, `kotlinx-serialization-json`, was a deliberate, discussed addition).
- **`install-mcp` stays idempotent and user-scope by default.**

## Reporting bugs / proposing features

Use the GitHub issue templates — they'll prompt for the details that make an issue actionable.

## Code of Conduct

This project follows the [Code of Conduct](CODE_OF_CONDUCT.md). By participating, you agree to
uphold it.
