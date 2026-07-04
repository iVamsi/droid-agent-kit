# DroidAgentKit — OSS Hygiene Design

Date: 2026-07-04
Status: Approved
Part of: [2026-07-01-opensource-roadmap.md](2026-07-01-opensource-roadmap.md) (workstream A)

---

## Overview

The repo has a license and a working CI test job but nothing that signals "contributions welcome":
no CONTRIBUTING guide, no code of conduct, no issue/PR templates, no CODEOWNERS, no lint gate in CI,
and no tagged release. This workstream adds all of those, plus cuts a first tag. It is documentation
and CI-config only — no production code changes.

**Known gap:** no GitHub remote is configured for this repo yet. Anything that would normally reference
a live repo URL (issue-template contact links, Discussions, badges) is either omitted or written so it
degrades gracefully once a remote exists. CODEOWNERS uses the committer email already present in every
commit (`private-maintainer-contact`) as the single default owner. The final tag is created locally only —
there is nothing to push to.

---

## Components

### 1. CONTRIBUTING.md

Sections: dev setup (`./gradlew test`, `:cli:installDist`), module map (linking to CLAUDE.md's module
list rather than duplicating it), branch/commit convention (reference CLAUDE.md's Conventional Commits +
branch naming rules, don't restate them), PR expectations (link to the PR template), and an explicit
callout of the project's non-negotiable constraints for contributors: no telemetry, no arbitrary shell
execution in MCP tools, no new third-party dependencies without discussion, allowlisted Gradle tasks
only, output must stay redacted.

### 2. CODE_OF_CONDUCT.md

Contributor Covenant v2.1 standard text, enforcement contact set to `private-maintainer-contact`.

### 3. CHANGELOG.md — cut the first release section

Currently one `## Unreleased` section. Change: rename it to `## [0.1.0-alpha] - 2026-07-04`, then add a
fresh empty `## Unreleased` section above it (with `### Added` / `### Changed` / `### Fixed` placeholders
per Keep a Changelog convention, only kept if non-empty going forward). No content in the existing
bullets changes.

### 4. GitHub issue templates

`.github/ISSUE_TEMPLATE/bug_report.yml` and `feature_request.yml` (GitHub YAML forms, not legacy
markdown), each with the minimum useful fields (repro steps / expected vs actual for bugs; problem /
proposed solution for features). `.github/ISSUE_TEMPLATE/config.yml` sets `blank_issues_enabled: false`
and has an empty `contact_links` list — no links to a live repo's Discussions/wiki since none exists yet.

### 5. PR template

`.github/PULL_REQUEST_TEMPLATE.md` — a short checklist mirroring CLAUDE.md's Definition of Done:
`./gradlew test` passes, security model preserved (local-only, allowlisted, redacted), relevant `docs/`
updated if CLI subcommands / MCP tool names / Gradle plugin behavior changed.

### 6. CODEOWNERS

`.github/CODEOWNERS` with a single line: `* private-maintainer-contact`.

### 7. Lint CI step

Add ktlint to the build (not detekt — codebase has never been linted; ktlint is formatting-only and
carries far lower risk of a noisy first run than detekt's complexity/smell rules, which would likely need
a baseline-suppression file). Concretely: add the `org.jlleitschuh.gradle.ktlint` Gradle plugin to the
root `build.gradle.kts` (applied to all subprojects, matching how other cross-cutting plugins are already
applied there), then add a `Lint` step to `.github/workflows/ci.yml` running `./gradlew ktlintCheck`
before the existing `Test` step. If the initial `ktlintCheck` run fails against existing code, run
`./gradlew ktlintFormat` once to auto-fix and commit the formatting diff as part of this same workstream
(not deferred) — a CI gate that's broken on day one defeats the purpose.

### 8. First tagged release

Local annotated tag `v0.1.0-alpha` on the commit that lands this workstream's changes. Not pushed (no
remote configured).

---

## Testing

No application code changes, so no unit tests. Verification is: `./gradlew test` still passes,
`./gradlew ktlintCheck` passes clean after the format pass, and CI workflow YAML is valid (checked via
`actionlint` if available locally, otherwise by careful manual review — no CI dependency to add for this
one-time check).

## Out of scope

- Maven Central / Gradle Plugin Portal publishing — already explicitly deferred at the roadmap level.
- detekt — deferred; can be added in a later, separate pass once ktlint is stable in CI.
- Any GitHub remote setup, badge wiring, or Discussions config — blocked on a remote existing.
