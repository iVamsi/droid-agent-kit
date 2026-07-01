# DroidAgentKit — Open-Source Growth Roadmap

Date: 2026-07-01
Status: Approved (workstream B fully speced; A/C/D/E scoped at roadmap level only)

---

## Goal

Grow adoption and community contribution for DroidAgentKit as an open-source Android agent-tooling
kit. This is a roadmap-level document — it names five largely-independent workstreams and their
rough scope and sequencing. Only workstream B has a full implementation-ready design; see
[2026-07-01-config-cli-hardening-design.md](2026-07-01-config-cli-hardening-design.md).

## Current state (baseline, captured 2026-07-01)

- ~2,200 main LOC / ~980 test LOC across 8 Gradle modules — genuinely small, alpha-scale.
- `LICENSE` (Apache-2.0) present. No `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `CHANGELOG.md`, issue/PR
  templates, or `CODEOWNERS`.
- Single CI job (`./gradlew test`) — no lint/detekt/ktlint step, no release/publish job, no git tags cut.
- No published Maven Central / Gradle Plugin Portal artifacts — today's only distribution path is
  git clone + `./gradlew :cli:installDist` (or `includeBuild` for the Gradle plugin).
- Config (`DroidAgentConfigLoader`) and CLI (`DroidAgentCliParser`) are both hand-rolled, zero-dependency
  parsers with no validation/help text — the target of workstream B.
- Visual regression: `PngDiffEngine` does real pixel diffing, but `DroidAgentVisualRule.captureCompose()`
  and the Gradle plugin's report/golden-update tasks are stubs — no real screenshot capture pipeline exists yet.
- 8 MCP tools implemented: `android_project_inspect`, `android_gradle_run`, `android_devices_list`,
  `android_app_install`, `android_app_launch`, `android_logcat_capture`, `android_screen_snapshot`,
  `android_report_bundle`.
- `install-mcp` supports Codex, Claude Code, and a generic stdio config — no first-class Cursor/Windsurf/
  Copilot/Zed support.

## Workstreams

### A — OSS hygiene
**Problem:** the repo isn't yet inviting to external contributors despite having a license.
**Scope:** `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `CHANGELOG.md`, GitHub issue/PR templates, `CODEOWNERS`,
a lint/detekt/ktlint CI step, a first tagged release. Low risk, no code redesign.
**Not yet decided:** whether to pursue Maven Central / Plugin Portal publishing — surfaced during
brainstorming and explicitly deferred (not selected as a priority) in favor of the git-clone install path
staying as-is for now.

### B — Config + CLI hardening
**Problem:** malformed `.droidagentkit/config.yaml` or CLI usage fails with raw exceptions or silent
wrong behavior instead of actionable errors; there's no `--help`.
**Scope:** see full design doc. Fully speced, ready for `writing-plans`.

### C — Visual regression, end-to-end
**Problem:** the "Compose Visual Regression Kit" is a headline feature in the README, but there is no
real screenshot capture pipeline — `captureCompose()` wraps a caller-supplied render lambda with no
actual bitmap capture, and the Gradle plugin's report/golden tasks write placeholder text instead of
reading real artifacts.
**Scope (rough, not yet speced):** design a real capture path (likely Robolectric/Paparazzi-style JVM
rendering for `visuals-android-test`, since the project has no Android SDK dependency and instrumentation
tests would require one — this tension needs its own brainstorming session), wire the Gradle plugin tasks
to read actual capture artifacts and produce real reports.

### D — New MCP / agent tools
**Problem:** the current 8 tools cover inspect/build/device/report; there's room for tools agents would
find useful for everyday Android work.
**Scope (rough, candidates to evaluate in a future brainstorm):** a detekt/ktlint run tool, a dependency
version-update check (must stay local-only — no live CVE database calls per the security model), crash/ANR
log triage from captured logcat, build-performance insight extraction from Gradle build scans/profiles.

### E — Broader agent/IDE support
**Problem:** `install-mcp` only knows about Codex and Claude Code by name.
**Scope (rough):** first-class `install-mcp` targets for Cursor, Windsurf, GitHub Copilot, Zed, and any
other stdio-MCP-capable tool with a known config file location/format.

## Sequencing

1. **B** (this cycle) — config/CLI hardening, fully speced and ready to implement.
2. **A** next — independent, low-risk, unblocks a credible public launch.
3. **C, D, E** — order TBD after B ships. D and E will likely add new CLI subcommands/flags, so they
   benefit from B's command-registry existing first; revisit sequencing once B lands.

Each of A, C, D, E gets its own brainstorming → spec → plan cycle before implementation, per this
project's normal workflow.
