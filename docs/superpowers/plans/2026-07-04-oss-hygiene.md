# OSS Hygiene Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make DroidAgentKit inviting to external contributors: contributor docs, a code of conduct, GitHub issue/PR templates, CODEOWNERS, a ktlint CI gate, and a first tagged release.

**Architecture:** Pure documentation + CI-config changes, no production code. Five sequential tasks, each independently committable: (1) CONTRIBUTING + CODE_OF_CONDUCT, (2) CHANGELOG release cut, (3) GitHub templates + CODEOWNERS, (4) ktlint wiring into build + CI, (5) tag the release.

**Tech Stack:** Gradle Kotlin DSL, GitHub Actions YAML, GitHub issue-form YAML, ktlint via `org.jlleitschuh.gradle.ktlint` Gradle plugin `14.2.0`.

## Global Constraints

- No GitHub remote is configured for this repo. Do not write any URL that assumes a live repo (badges, Discussions links, `github.com/<org>/...` references) — omit instead.
- Do not publish personal email addresses, machine paths, or local identities in repository content.
- No production code changes. If any step would touch a `src/main` or `src/test` Kotlin file for reasons other than ktlint auto-format, stop and flag it — out of scope.
- `./gradlew test` must pass after every task.
- Project version is already `0.1.0-alpha` in root `build.gradle.kts` (`version = "0.1.0-alpha"`) — the release tag must match this exactly: `v0.1.0-alpha`.
- Final git tag is created locally only. Do not attempt to push it (no remote exists).

---

### Task 1: CONTRIBUTING.md + CODE_OF_CONDUCT.md

**Files:**
- Create: `CONTRIBUTING.md`
- Create: `CODE_OF_CONDUCT.md`

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces: nothing consumed by later tasks — these are terminal, standalone docs.

- [ ] **Step 1: Write `CONTRIBUTING.md`**

```markdown
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
```

- [ ] **Step 2: Write `CODE_OF_CONDUCT.md`**

```markdown
# Contributor Covenant Code of Conduct

## Our Pledge

We as members, contributors, and leaders pledge to make participation in our community a
harassment-free experience for everyone, regardless of age, body size, visible or invisible
disability, ethnicity, sex characteristics, gender identity and expression, level of experience,
education, socio-economic status, nationality, personal appearance, race, caste, color, religion, or
sexual identity and orientation.

We pledge to act and interact in ways that contribute to an open, welcoming, diverse, inclusive, and
healthy community.

## Our Standards

Examples of behavior that contributes to a positive environment for our community include:

- Demonstrating empathy and kindness toward other people
- Being respectful of differing opinions, viewpoints, and experiences
- Giving and gracefully accepting constructive feedback
- Accepting responsibility and apologizing to those affected by our mistakes, and learning from the
  experience
- Focusing on what is best not just for us as individuals, but for the overall community

Examples of unacceptable behavior include:

- The use of sexualized language or imagery, and sexual attention or advances of any kind
- Trolling, insulting or derogatory comments, and personal or political attacks
- Public or private harassment
- Publishing others' private information, such as a physical or email address, without their
  explicit permission
- Other conduct which could reasonably be considered inappropriate in a professional setting

## Enforcement Responsibilities

Community leaders are responsible for clarifying and enforcing our standards of acceptable behavior
and will take appropriate and fair corrective action in response to any behavior that they deem
inappropriate, threatening, offensive, or harmful.

Community leaders have the right and responsibility to remove, edit, or reject comments, commits,
code, wiki edits, issues, and other contributions that are not aligned to this Code of Conduct, and
will communicate reasons for moderation decisions when appropriate.

## Scope

This Code of Conduct applies within all community spaces, and also applies when an individual is
officially representing the community in public spaces. Examples of representing our community
include using an official e-mail address, posting via an official social media account, or acting as
an appointed representative at an online or offline event.

## Enforcement

Instances of abusive, harassing, or otherwise unacceptable behavior may be reported to the community
leaders responsible for enforcement through a private maintainer contact channel published in the
repository metadata. Sensitive incident details must not be placed in public issues. All complaints
will be reviewed and investigated promptly and fairly.

All community leaders are obligated to respect the privacy and security of the reporter of any
incident.

## Enforcement Guidelines

Community leaders will follow these Community Impact Guidelines in determining the consequences for
any action they deem in violation of this Code of Conduct:

### 1. Correction

**Community Impact**: Use of inappropriate language or other behavior deemed unprofessional or
unwelcome in the community.

**Consequence**: A private, written warning from community leaders, providing clarity around the
nature of the violation and an explanation of why the behavior was inappropriate. A public apology
may be requested.

### 2. Warning

**Community Impact**: A violation through a single incident or series of actions.

**Consequence**: A warning with consequences for continued behavior. No interaction with the people
involved, including unsolicited interaction with those enforcing the Code of Conduct, for a specified
period of time. This includes avoiding interactions in community spaces as well as external channels
like social media. Violating these terms may lead to a temporary or permanent ban.

### 3. Temporary Ban

**Community Impact**: A serious violation of community standards, including sustained inappropriate
behavior.

**Consequence**: A temporary ban from any sort of interaction or public communication with the
community for a specified period of time. No public or private interaction with the people involved,
including unsolicited interaction with those enforcing the Code of Conduct, is allowed during this
period. Violating these terms may lead to a permanent ban.

### 4. Permanent Ban

**Community Impact**: Demonstrating a pattern of violation of community standards, including
sustained inappropriate behavior, harassment of an individual, or aggression toward or disparagement
of classes of individuals.

**Consequence**: A permanent ban from any sort of public interaction within the community.

## Attribution

This Code of Conduct is adapted from the [Contributor Covenant][homepage], version 2.1, available at
https://www.contributor-covenant.org/version/2/1/code_of_conduct.html.

Community Impact Guidelines were inspired by [Mozilla's code of conduct enforcement ladder][Mozilla CoC].

[homepage]: https://www.contributor-covenant.org
[Mozilla CoC]: https://github.com/mozilla/diversity
```

- [ ] **Step 3: Verify both files exist with expected content**

Run: `test -f CONTRIBUTING.md && test -f CODE_OF_CONDUCT.md`
Expected: no error.

- [ ] **Step 4: Commit**

```bash
git add CONTRIBUTING.md CODE_OF_CONDUCT.md
git commit -m "docs: add CONTRIBUTING.md and CODE_OF_CONDUCT.md"
```

---

### Task 2: Cut the first CHANGELOG release section

**Files:**
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces: nothing consumed by later tasks.

**Current content of `CHANGELOG.md`** (for exact context — do not paraphrase, edit this file directly):

```markdown
# Changelog

All notable changes to DroidAgentKit are documented here. This project uses date-based alpha
development until a first tagged release.

## Unreleased

### Changed

- `DroidAgentConfigLoader.load()` now returns `ConfigLoadResult` (`Loaded` or `Invalid`) instead of a
  bare `DroidAgentConfig`. `schemaVersion` and value types (booleans, numbers) are validated; malformed
  config previously fell back to defaults silently or threw an uncaught exception.
- CLI commands now reject unknown flags and print `--help` usage generated from a command registry.
  Previously, unrecognized flags were silently ignored. The `visuals` command still accepts arbitrary
  passthrough flags, since its option set varies by action.
- Config boolean values now require the literal lowercase `true`/`false` and report a validation error
  otherwise. Previously, values were parsed with Kotlin's `String.toBoolean()`, which silently accepted
  any casing of `true` (e.g. `TRUE`) as `true` and silently treated everything else, including typos
  like `Yes`, as `false`.
- An unrecognized CLI command now prints an error and returns exit code 1. Previously it fell through
  silently to the help output with exit code 0.
```

- [ ] **Step 1: Rewrite `CHANGELOG.md`**

Replace the entire file content with:

```markdown
# Changelog

All notable changes to DroidAgentKit are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and version numbers follow the alpha
pre-release convention `0.y.z-alpha` until a stable 1.0 release.

## Unreleased

## [0.1.0-alpha] - 2026-07-04

### Changed

- `DroidAgentConfigLoader.load()` now returns `ConfigLoadResult` (`Loaded` or `Invalid`) instead of a
  bare `DroidAgentConfig`. `schemaVersion` and value types (booleans, numbers) are validated; malformed
  config previously fell back to defaults silently or threw an uncaught exception.
- CLI commands now reject unknown flags and print `--help` usage generated from a command registry.
  Previously, unrecognized flags were silently ignored. The `visuals` command still accepts arbitrary
  passthrough flags, since its option set varies by action.
- Config boolean values now require the literal lowercase `true`/`false` and report a validation error
  otherwise. Previously, values were parsed with Kotlin's `String.toBoolean()`, which silently accepted
  any casing of `true` (e.g. `TRUE`) as `true` and silently treated everything else, including typos
  like `Yes`, as `false`.
- An unrecognized CLI command now prints an error and returns exit code 1. Previously it fell through
  silently to the help output with exit code 0.
```

- [ ] **Step 2: Verify the release header is present and the file has exactly one `## [0.1.0-alpha]` heading**

Run: `grep -c "## \[0.1.0-alpha\]" CHANGELOG.md`
Expected: `1`

- [ ] **Step 3: Commit**

```bash
git add CHANGELOG.md
git commit -m "docs: cut CHANGELOG release section for 0.1.0-alpha"
```

---

### Task 3: GitHub issue templates, PR template, CODEOWNERS

**Files:**
- Create: `.github/ISSUE_TEMPLATE/bug_report.yml`
- Create: `.github/ISSUE_TEMPLATE/feature_request.yml`
- Create: `.github/ISSUE_TEMPLATE/config.yml`
- Create: `.github/PULL_REQUEST_TEMPLATE.md`
- Create: `.github/CODEOWNERS`

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Write `.github/ISSUE_TEMPLATE/bug_report.yml`**

```yaml
name: Bug report
description: Report a problem with DroidAgentKit
labels: ["bug"]
body:
  - type: textarea
    id: description
    attributes:
      label: What happened?
      description: A clear description of the bug.
    validations:
      required: true
  - type: textarea
    id: repro
    attributes:
      label: Steps to reproduce
      description: Exact commands or steps that trigger the problem.
      placeholder: |
        1. Run `./cli/build/install/droidagent/bin/droidagent inspect --project ...`
        2. ...
    validations:
      required: true
  - type: textarea
    id: expected
    attributes:
      label: Expected behavior
    validations:
      required: true
  - type: textarea
    id: actual
    attributes:
      label: Actual behavior
      description: Include the exact error message or output, if any.
    validations:
      required: true
  - type: input
    id: version
    attributes:
      label: DroidAgentKit version / commit
      description: Output of `git rev-parse HEAD`, or the tagged release you're using.
    validations:
      required: false
  - type: input
    id: environment
    attributes:
      label: Environment
      description: OS, JDK version (`java -version`), Gradle version.
    validations:
      required: false
```

- [ ] **Step 2: Write `.github/ISSUE_TEMPLATE/feature_request.yml`**

```yaml
name: Feature request
description: Propose a new feature or enhancement
labels: ["enhancement"]
body:
  - type: textarea
    id: problem
    attributes:
      label: What problem does this solve?
      description: What can't you do today that this would enable?
    validations:
      required: true
  - type: textarea
    id: solution
    attributes:
      label: Proposed solution
      description: What would you like to see happen?
    validations:
      required: true
  - type: textarea
    id: alternatives
    attributes:
      label: Alternatives considered
    validations:
      required: false
```

- [ ] **Step 3: Write `.github/ISSUE_TEMPLATE/config.yml`**

```yaml
blank_issues_enabled: false
contact_links: []
```

- [ ] **Step 4: Write `.github/PULL_REQUEST_TEMPLATE.md`**

```markdown
## Summary

<!-- What does this PR change, and why? -->

## Checklist

- [ ] `./gradlew test` passes
- [ ] `./gradlew ktlintCheck` passes
- [ ] The alpha security model is preserved: local-only execution, explicit Gradle-task allowlists,
      redacted command output, no telemetry
- [ ] `docs/` updated if this changes a public CLI subcommand, an MCP tool name/schema, or Gradle
      plugin behavior
- [ ] Tests added or updated for behavior changes
```

- [ ] **Step 5: Defer `.github/CODEOWNERS`**

Do not create CODEOWNERS until a non-personal GitHub organization or team owner is available.

- [ ] **Step 6: Verify all five files exist**

Run: `test -f .github/ISSUE_TEMPLATE/bug_report.yml && test -f .github/ISSUE_TEMPLATE/feature_request.yml && test -f .github/ISSUE_TEMPLATE/config.yml && test -f .github/PULL_REQUEST_TEMPLATE.md && echo OK`
Expected: `OK`

- [ ] **Step 7: Commit**

```bash
git add .github/ISSUE_TEMPLATE .github/PULL_REQUEST_TEMPLATE.md .github/CODEOWNERS
git commit -m "docs: add GitHub issue/PR templates and CODEOWNERS"
```

---

### Task 4: ktlint CI gate

**Files:**
- Modify: `build.gradle.kts`
- Modify: `.github/workflows/ci.yml`
- Modify: any `.kt` file `ktlintFormat` reformats (auto-generated diff, not hand-written)

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces: `ktlintCheck` and `ktlintFormat` Gradle tasks, available to CI and to contributors locally
  (referenced by `CONTRIBUTING.md`, already written in Task 1).

**Current content of root `build.gradle.kts`:**

```kotlin
plugins {
    kotlin("jvm") version "2.3.20" apply false
}

group = "com.droidagentkit"
version = "0.1.0-alpha"

subprojects {
    group = rootProject.group
    version = rootProject.version

    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>("kotlin") {
            jvmToolchain(17)
        }
    }

    tasks.withType<Test>().configureEach {
        testLogging {
            events("failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
```

**Current content of `.github/workflows/ci.yml`:**

```yaml
name: CI

on:
  pull_request:
  push:
    branches: [main]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "21"
      - name: Test
        run: ./gradlew test
```

- [ ] **Step 1: Add the ktlint plugin to root `build.gradle.kts`**

Replace the `plugins {}` block and the `subprojects {}` block's opening with:

```kotlin
plugins {
    kotlin("jvm") version "2.3.20" apply false
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0" apply false
}

group = "com.droidagentkit"
version = "0.1.0-alpha"

subprojects {
    group = rootProject.group
    version = rootProject.version

    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>("kotlin") {
            jvmToolchain(17)
        }
    }

    tasks.withType<Test>().configureEach {
        testLogging {
            events("failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
```

The ktlint plugin only activates on projects that also have the Kotlin JVM plugin applied, so applying
it unconditionally to every subproject is safe — it's a no-op on any subproject without Kotlin (there
are none today, but this keeps the config robust if a non-Kotlin module is ever added).

- [ ] **Step 2: Run ktlintCheck to see the current state of the codebase**

Run: `./gradlew ktlintCheck`
Expected: this may PASS or FAIL. Note the outcome — it determines the next step.

- [ ] **Step 3: If Step 2 failed, auto-fix and verify**

Run: `./gradlew ktlintFormat`
Then run: `./gradlew ktlintCheck`
Expected: `BUILD SUCCESSFUL`. If it still fails after `ktlintFormat`, the remaining violations are
not auto-fixable (e.g. a rule requiring manual restructuring) — read the failure output, fix those
specific lines by hand, and re-run `./gradlew ktlintCheck` until it passes. Do not disable or suppress
rules to make it pass; the goal is a clean baseline.

If Step 2 passed, skip this step (no formatting changes needed).

- [ ] **Step 4: Add the lint step to CI, before the test step**

Replace `.github/workflows/ci.yml` with:

```yaml
name: CI

on:
  pull_request:
  push:
    branches: [main]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "21"
      - name: Lint
        run: ./gradlew ktlintCheck
      - name: Test
        run: ./gradlew test
```

- [ ] **Step 5: Run the full test suite to confirm nothing broke**

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add build.gradle.kts .github/workflows/ci.yml
git add -u
git commit -m "ci: add ktlint check as a CI gate"
```

Note: `git add -u` stages any files `ktlintFormat` reformatted in Step 3 (if that step ran). If Step 3
did not run (Step 2 already passed clean), `git add -u` is a no-op and that's expected.

---

### Task 5: Tag the first release

**Files:** none (git operation only).

**Interfaces:**
- Consumes: the final commit from Task 4 (must be the tip of `main` when this task runs).
- Produces: local git tag `v0.1.0-alpha`.

- [ ] **Step 1: Confirm the working tree is clean and on `main`**

Run: `git status --short && git branch --show-current`
Expected: no output from `git status --short` (clean tree), and `main` printed for the branch.

- [ ] **Step 2: Create the annotated tag**

```bash
git tag -a v0.1.0-alpha -m "DroidAgentKit 0.1.0-alpha: first tagged release

Includes: MCP toolbox server, agent readiness auditor, Compose visual
regression kit, hardened config/CLI parsing, and install-mcp support for
Codex, Claude Code, Cursor, Zed, and VS Code."
```

- [ ] **Step 3: Verify the tag exists and points at the expected commit**

Run: `git tag -l -n1 v0.1.0-alpha && git rev-parse v0.1.0-alpha^{commit} && git rev-parse HEAD`
Expected: the tag's message summary prints, and the two commit hashes printed by the last two commands
are identical (the tag points at the current tip of `main`).

Do not push this tag — no git remote is configured for this repo.

---

## Self-Review Notes

- **Spec coverage:** all 8 numbered components in the design doc map 1:1 to a task above (CONTRIBUTING
  + CoC → Task 1; CHANGELOG → Task 2; issue/PR templates + CODEOWNERS → Task 3; ktlint → Task 4; tag →
  Task 5).
- **No placeholders:** every file's full content is written out above; no TBD/TODO markers.
- **Type/name consistency:** `ktlintCheck` / `ktlintFormat` task names are used consistently between
  Task 4's build file, CI workflow, and Task 1's `CONTRIBUTING.md` (already written before Task 4 in
  task order, but referencing the same task names — verified they match the plugin's actual registered
  task names from Gradle Plugin Portal / GitHub docs).
- **Version consistency:** `0.1.0-alpha` is used identically in `build.gradle.kts` (already present),
  `CHANGELOG.md` (Task 2), and the git tag (Task 5).
