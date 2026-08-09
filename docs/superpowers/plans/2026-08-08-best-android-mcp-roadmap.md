# DroidAgentKit "Best Android MCP" Roadmap & Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Workstreams are independent unless a dependency is stated; execute one workstream per branch/PR.

**Goal:** Make DroidAgentKit the best Android MCP server: zero-friction install, the deepest Android-specific tool surface, verified stability across OSes, a security model that stays ahead of the field, and CI that proves all of it on every PR.

**Architecture:** Keep the existing shape — pure-JVM Kotlin monorepo, capability-gated tool groups, `ProcessRunner`-only execution, trust-split config. Every new feature lands as a tool in an existing group (or a new opt-in group) behind the same policy layer. Installation work happens in `distribution/` and `cli/` without touching the security core.

**Tech Stack:** Kotlin/JVM 17, kotlinx-serialization-json, sqlite-jdbc (no new runtime deps unless a task says so), GitHub Actions, npm launcher (Node install-time only).

## Execution status (complete, 2026-08-08)

Every workstream implemented and shipped as a 9-PR stack, each branch based on the previous.
All checks green on the tip, including both Windows legs.

| PR | Branch | Covers |
| --- | --- | --- |
| #24 | `chore/discoverability-and-install-ux` | WS0-T1/T2, WS5-T1/T2/T4, WS1-T2/T3 |
| #25 | `feat/launcher-jre-provisioning` | WS1-T1 |
| #26 | `feat/mcp-cancellation-and-progress` | WS2-T1/T2 |
| #27 | `feat/mcp-elicitation-confirm` | WS2-T3 (closes hardening finding S2) |
| #28 | `feat/security-budgets-and-artifact-hygiene` | WS4-T1/T2 |
| #29 | `feat/flow-recording` | WS6-T0/T0b |
| #30 | `feat/retrace-and-apk-analysis` | WS6-T1/T2 |
| #31 | `feat/ui-find-and-token-efficiency` | WS6-T4/T7 |
| #32 | `chore/stability-docs-and-ci-hardening` | WS3-T1/T2/T3, WS4-T3, WS5-T3/T4 |

544 tests (from 442). Tools: 18 core (from 15), plus three new device-control tools.

**Deferred deliberately, with reasons:**

- **WS0-T1 registry rename** — verified the MCP registry searches `name` only, so no description
  edit helps. Renaming mints a second listing and orphans the current one; `server.json` publishes
  on the next release tag, so the edit was left unmade pending a decision. See the task.
- **WS1-T4 MCPB bundle / WS1-T5 Homebrew** — packaging work with no code risk, gated on the
  registry-identity decision above since both surface the same name.
- **WS6-T3 screen recording, T5 Compose recomposition, T6 macrobenchmark** — each needs a real
  device or trace fixture to be verified rather than assumed; the nightly emulator job (WS5-T3)
  is the prerequisite and now exists.
- **WS6-T8 live viewer** — explicitly a time-boxed spike in the plan, and the spike was not run.

**Found while implementing, not in the original plan:** the E2E smoke test's stdio `initialize`
round-trip was guarded behind `command -v timeout`, absent on macOS, so it had been silently
skipping itself there; a mis-escaped Kotlin template made elicitation request ids un-matchable
(tests hung rather than failed); nested `safety.budgets.*` config keys parsed as nothing because
the YAML loader is single-level; and adding a constructor parameter after a lambda silently
re-bound trailing-lambda call sites, twice.

## Global Constraints

- `./gradlew test` must pass after every task (Definition of Done, CLAUDE.md).
- Preserve the alpha security model: local-only, explicit allowlists, redacted command output, no telemetry, no network calls from the toolkit itself (Agent Boundaries, CLAUDE.md). The only sanctioned downloads are install-time (launcher fetching the release jar / JRE provisioning in WS1, both checksum-verified).
- All command execution goes through `ProcessRunner`; Gradle authorization stays in `SafetyConfig.isGradleTaskAllowed`.
- MCP tool names are stable public API — additive changes only; update `docs/` when tool names/schemas change.
- No new third-party runtime dependencies without an explicit task line item; `toolbox-core` stays zero-dependency.
- Grants live only in the user policy (`~/.droidagentkit/policy.yaml`); project config can only narrow. Any new capability or tool group must be added to `ConfigTrustTest` and the trust-split property test.
- Conventional commits; branch names `{type}/{short-description}`.
- Coverage floor: keep `:toolbox-core:koverVerify` green; new policy-adjacent code ≥ 85% line coverage.

---

## Where the project stands (audit, 2026-08-08)

Verified against HEAD (`e5b7efd`, 0.2.5-alpha), 13,514 main-source LOC, 442 tests.

**The prior hardening plan (docs/hardening-plan.md) is essentially executed.** Verified in code, not just in commit messages:

| Finding | Status | Evidence |
| --- | --- | --- |
| S1 project config widening Gradle allowlist | **Fixed** | `narrowGradleTasks` + `globSubsumes` subset proof, timeout clamped (`Config.kt:243-337`) |
| S2 `confirmDestructive` agent-supplied | **Re-documented honestly** (accident guard); real human-in-the-loop still open → WS4-T3 | `CapabilityPolicy.kt:40-46`, README threat-model link |
| S3 mutating tasks in default allowlist | **Fixed** | `MUTATING_TASK_PATTERNS` deny layer (`Config.kt:76-87`) |
| S4 ReDoS via extraPatterns | **Fixed** | `BoundedCharSequence` deadline + `MAX_KEY_CHARS` + per-pattern budget (`Redaction.kt`) |
| S5 device-path denylist | **Fixed** | `ALLOWED_DEVICE_PATH_PREFIXES` allowlist (`CapabilityPolicy.kt:140-141`) |
| S6 symlink-following writes | **Fixed** | `resolveThroughLinks` in policy + artifact writer changes (commit `1c130c9`) |
| S7 unvalidated package/serial | **Fixed** | `DeviceIdentifiers` validators enforced in `DefaultOperationPolicy.authorize` |
| S8 DNS on Host/Origin | **Fixed** | `isLoopbackHost` is literal-only; DNS only on operator-supplied bind host |
| T1 coverage | **Done** | Kover 0.9.9, CI gates `:toolbox-core:koverVerify` on policy classes |
| T2 static analysis | **Done** | detekt in CI |
| T3 swallowed exceptions | **Mostly done** | 7 remaining `catch (_)` sites, 5 justified; 2 generic catches in `TraceProcessor.kt:87,98` → WS3-T2 |
| T4 trust-split property test | **Done** | commit `a26fc99` |
| T5 flaky sleeps / real sockets | **Done** | commit `6ae03a9` |
| T6 device-supplied db filenames | **Fixed** | `isBareFileName` guard (`AppDataSnapshot.kt:74`) |
| T7 unused `sensitivity` param | **Fixed** | threaded through `ref()` |

**Already strong (don't rebuild):** supply chain (pinned SHAs, dependency verification metadata, SBOM, OIDC trusted publishing to npm + MCP registry, CodeQL, Scorecard, public-hygiene check with NUL-byte guard); release automation (version-consistency gate, checksummed fat jar); structured MCP output (`structuredContent` + `outputSchema` already implemented, protocol `2025-11-25`); the capability/trust-split model itself, which is ahead of every competitor surveyed.

**Gaps this plan addresses:**

1. **Install friction:** JDK 17+ is a hard prerequisite the launcher doesn't solve; launcher only runs `serve-mcp` (`init`/`audit`/`install-mcp` need `java -jar` by hand); no Homebrew; `distribution/mcp.json` is metadata only — no `.mcpb` bundle is built or attached to releases; no `doctor` diagnostics command.
2. **Protocol features:** `notifications/cancelled` is parsed and ignored (running Gradle/adb processes are not killed); no `notifications/progress` for long builds; no elicitation (the correct fix for S2's human-in-the-loop gap); no `logging` capability.
3. **CI proof gaps:** Linux-only matrix; `distribution/smoke-test.sh` never runs in CI (nor its `DROIDAGENT_E2E=1` stdio round-trip); no CLI-against-`samples/` integration job (cheap — the inspector is static, no Android SDK needed); coverage gate covers only toolbox-core policy classes.
4. **Feature depth vs. field:** see competitive section below.

---

## Competitive landscape (research summary)

Full research with per-claim citations: [docs/research/android-mcp-landscape.md](../../research/android-mcp-landscape.md). What matters for this plan:

- **mobile-next/mobile-mcp is the adoption leader** (5.8k stars, ~87k npm downloads/month vs. our launcher's 921) on the strength of cross-platform UI automation and a frictionless npx install — but it has **zero** Gradle/build/lint/profiling/storage tooling and essentially no security model. It wins on distribution, not depth.
- **Maestro MCP and appium-mcp own the "durable test artifact" outcome**: an agent session becomes a repeatable YAML flow or Java/TestNG test. DAK's `android_run_flow` executes flows but never *records* one — this is the single biggest feature gap.
- **Google ships no first-party adb/Gradle developer MCP server.** Android Studio Agent Mode is a *client* (HTTP/SSE only, tools-only), Firebase MCP is backend-side. The Android *developer-workflow* MCP space is open, and DAK's Streamable-HTTP Android Studio mode is a real edge since most rivals are stdio-only.
- **DAK is already ahead — uniquely in the surveyed field — on**: the capability/trust-split/redaction security model, project intelligence (inspect, allowlisted Gradle, lint, crash triage, build diagnose), Perfetto profiling, gated storage inspection, emulator network capture, visual regression, and the readiness auditor (no equivalent anywhere). The plan protects these; it does not rebuild them.
- **Embarrassing, cheap-to-fix gap**: the MCP registry listing doesn't surface for `?search=android` because the listed name/description lack the word "android". Fixing keywords + adding catalog listings + one-click install buttons directly attacks the 95× download gap.
- **Positioning decision baked into this plan**: DAK is the best *Android developer* MCP — build-system depth over iOS breadth. iOS is explicitly out of scope; say so in the README rather than leaving it implied.

---

## Workstream 1 — Installation: one command, no prerequisites

Ordered by user impact. WS1-T1 removes the single biggest funnel drop (JDK prerequisite).

### Task WS1-T1: JRE auto-provisioning in the npm launcher

**Files:**
- Modify: `distribution/npm-launcher/index.js`
- Create: `distribution/npm-launcher/jre.js` (resolution + fetch + verify)
- Create: `distribution/npm-launcher/jre-manifest.json` (pinned Temurin 17 JRE URLs + SHA-256 per os/arch)
- Test: `distribution/npm-launcher/test/jre.test.js` (node:test, mock server — reuse `distribution/test-fixtures/mock-release-server.js` pattern)
- Modify: `distribution/smoke-test.sh` (cover the no-java path with `PATH` stripped)

**Interfaces:**
- Produces: `resolveJava() -> string` (absolute path to a `java` executable): order is `DROIDAGENT_JAVA`, `JAVA_HOME/bin/java`, `java` on PATH if ≥ 17 (parse `-version` stderr), else download pinned Temurin JRE to `~/.droidagentkit/jre/<version>-<os>-<arch>/`, SHA-256-verified from `jre-manifest.json`, atomic rename after verify.

**Steps:**

- [ ] **Step 1: Write failing tests** — `jre.test.js`: (a) respects `DROIDAGENT_JAVA`; (b) accepts PATH java when `-version` reports 17+; (c) rejects java 11 and falls through to download; (d) download verifies SHA-256 and fails closed on mismatch; (e) concurrent invocations don't corrupt the cache (lock file or tmp-dir + rename).
- [ ] **Step 2: Run tests, verify they fail** — `node --test distribution/npm-launcher/test/`.
- [ ] **Step 3: Implement `jre.js` + wire into `index.js`** — keep `index.js`'s existing jar fetch/verify untouched; only replace how the `java` binary is located.
- [ ] **Step 4: Tests pass; run `bash distribution/smoke-test.sh`** including a run with `PATH` filtered of java.
- [ ] **Step 5: Update `distribution/npm-launcher/README.md` + root README prerequisites** (JDK line becomes "auto-provisioned if missing"); commit `feat(launcher): auto-provision a verified JRE when none is present`.

**Acceptance:** `npx -y @droidagentkit/launcher --version` succeeds on a machine with no Java installed; a corrupted download exits non-zero with the expected-vs-actual hash printed.

### Task WS1-T2: Launcher passes through subcommands

**Files:**
- Modify: `distribution/npm-launcher/index.js`
- Test: extend `distribution/smoke-test.sh` + `test/launcher-args.test.js`

**Interfaces:**
- Produces: `npx @droidagentkit/launcher <subcommand> [args...]` execs `droidagent <subcommand> [args...]`; bare invocation (no args) keeps today's behavior (`serve-mcp --transport stdio --project auto`) so existing MCP configs don't break.

**Steps:**

- [ ] **Step 1: Failing test** — argv `["init", "--profile", "full"]` spawns the jar with exactly those args; argv `[]` spawns `serve-mcp --transport stdio --project auto`; `--version`/`--help` keep launcher-local behavior.
- [ ] **Step 2: Implement; run tests + smoke test.**
- [ ] **Step 3: Update README Quick start** — replace the "use `java -jar` for init/audit" caveat with `npx -y @droidagentkit/launcher init`. Commit `feat(launcher): pass subcommands through to the CLI`.

### Task WS1-T3: `droidagent doctor`

**Files:**
- Create: `cli/src/main/kotlin/com/droidagentkit/cli/DoctorCommand.kt`
- Modify: `cli/src/main/kotlin/com/droidagentkit/cli/DroidAgentCliParser.kt`, `CliCommandSpec.kt`, `DroidAgentMain.kt`
- Test: `cli/src/test/kotlin/com/droidagentkit/cli/DoctorCommandTest.kt`

**Interfaces:**
- Produces: `droidagent doctor [--project <path>] [--format text|json]` printing check rows: java version, adb present+version, `ANDROID_HOME`, emulator binary, trace_processor, mitmproxy, user policy exists/parses (with its warnings), project config parses, effective tool groups + capabilities, artifact dir writable. Exit 0 if all required checks pass, 1 otherwise (optional tools are warnings, not failures). Reuses `DroidAgentConfigLoader.loadEffective` and probes binaries via `ProcessRunner` with `--version`-style args only.

**Steps:**

- [ ] **Step 1: Failing tests** — `doctor - when adb missing - reports warning not failure`, `doctor - when policy invalid - exits 1 with line numbers`, `doctor - json format - machine readable`. Follow the fake-`ProcessRunner` pattern used in `DevicesFormatterTest.kt`.
- [ ] **Step 2: Implement; tests pass.**
- [ ] **Step 3: Wire into `docs/cli-reference.md` + `docs/troubleshooting.md`** ("run doctor first"). Commit `feat(cli): add doctor command for environment diagnostics`.

### Task WS1-T4: Build and attach an MCPB bundle to releases

**Files:**
- Create: `distribution/mcpb/manifest.json` (MCPB spec format — `distribution/mcp.json` is close but not the packaged format)
- Create: `scripts/build-mcpb.sh` (zips manifest + launcher into `droidagentkit-<version>.mcpb`)
- Modify: `.github/workflows/release.yml` (build + attach to the GitHub Release), `scripts/check-release-version.sh` (version-consistency covers the manifest)
- Test: extend `distribution/smoke-test.sh` (unzip bundle, validate manifest JSON schema fields)

**Steps:**

- [ ] **Step 1: Write manifest per the MCPB/Desktop-Extensions spec** (verify current field names against the spec — research doc has the link).
- [ ] **Step 2: `build-mcpb.sh` + smoke-test assertion; run locally.**
- [ ] **Step 3: Add release-job step after checksums; commit `feat(distribution): package a one-click MCPB bundle`.**

**Acceptance:** release attaches `droidagentkit-<version>.mcpb`; drag-drop install works in Claude Desktop (manual verification once, then covered by manifest-schema assertion).

### Task WS1-T5: Homebrew tap

**Files:**
- Create: `scripts/generate-homebrew-formula.sh` (emits formula from release version + jar SHA-256; formula wraps `java -jar` with a JDK dependency)
- Modify: `.github/workflows/release.yml` (job to push formula to `iVamsi/homebrew-droidagentkit` via a repo-scoped token)
- Docs: README install section gains `brew install iVamsi/droidagentkit/droidagent`

**Steps:**

- [ ] **Step 1: Script + template formula; shellcheck clean; dry-run job mode that writes the formula as a workflow artifact instead of pushing.**
- [ ] **Step 2: Create the tap repo (one-time, manual); wire the push job; commit `feat(distribution): publish a Homebrew formula on release`.**

---

## Workstream 2 — Protocol: progress, cancellation, elicitation

These three make long-running Android operations (Gradle builds, emulator boot, Perfetto capture) first-class in MCP hosts, and close the S2 human-in-the-loop gap properly.

### Task WS2-T1: Honor `notifications/cancelled`

**Files:**
- Modify: `mcp-server/src/main/kotlin/com/droidagentkit/mcp/McpJsonRpcHandler.kt` (track in-flight request id → cancellation hook; currently `"notifications/cancelled" -> null` at line 58)
- Modify: `toolbox-core/src/main/kotlin/com/droidagentkit/core/ProcessRunner.kt` (expose a cooperative cancel that reuses the existing descendant-kill logic from the timeout path)
- Modify: `mcp-server/src/main/kotlin/com/droidagentkit/mcp/DroidAgentMcpDispatcher.kt` (thread a `CancellationToken` into tool execution)
- Test: `mcp-server/src/test/kotlin/com/droidagentkit/mcp/CancellationTest.kt`, extend `toolbox-core/.../ManagedJobRunnerTest.kt` patterns

**Interfaces:**
- Produces: `class CancellationToken { fun cancel(); fun isCancelled(): Boolean; fun onCancel(hook: () -> Unit) }` in `toolbox-core`; `ProcessRunner.run(..., cancellation: CancellationToken? = null)`; cancelled call returns a `ToolResult` error `{ code: "cancelled" }` and the child process tree is dead.

**Steps:**

- [ ] **Step 1: Failing test** — start a long-running fake command (`sleep`-equivalent via a tiny JVM main, matching how `ProcessRunner` tests already spawn processes), send `notifications/cancelled` with the request id, assert the process exits within 2s and the response is the cancelled error. Condition-wait, never fixed sleeps (per commit `6ae03a9` convention).
- [ ] **Step 2–4: Implement token → handler bookkeeping → dispatcher threading; tests green.**
- [ ] **Step 5: Commit `feat(mcp): kill in-flight processes on notifications/cancelled`.**

### Task WS2-T2: Progress notifications for long tools

**Files:**
- Modify: `McpJsonRpcHandler.kt` (accept `_meta.progressToken`, emit `notifications/progress` on the stdio writer; HTTP JSON-response mode skips emission — document that)
- Modify: `DroidAgentMcpDispatcher.kt` (a `ProgressReporter` callback threaded to gradle run / emulator start / perfetto capture / bugreport)
- Test: `mcp-server/src/test/kotlin/com/droidagentkit/mcp/ProgressNotificationTest.kt`

**Interfaces:**
- Produces: `fun interface ProgressReporter { fun report(progress: Double?, message: String) }` (functional interface — house rule). Gradle runs report per task-line milestones parsed from output; emulator start reports boot-state polls.

**Steps:**

- [ ] **Step 1: Failing test** — `tools/call` with `_meta.progressToken` on a fake long tool yields ≥1 `notifications/progress` frame before the response, with the token echoed; without the token, no frames.
- [ ] **Step 2: Implement; tests green; commit `feat(mcp): progress notifications for long-running tools`.**

### Task WS2-T3: Elicitation-backed destructive confirmation (real S2 fix)

**Files:**
- Modify: `McpJsonRpcHandler.kt` (advertise nothing — elicitation is client-capability-gated; send `elicitation/create` when supported)
- Modify: `toolbox-core/src/main/kotlin/com/droidagentkit/core/CapabilityPolicy.kt` (new policy knob `safety.requireInteractiveConfirm: Boolean`, default `false`; privileged key — policy-only)
- Modify: `toolbox-core/src/main/kotlin/com/droidagentkit/core/Config.kt` (parse + trust-split the new key; add to `privilegedKeys`)
- Test: `toolbox-core/.../ConfigTrustTest.kt` (project file cannot set it), `mcp-server/.../ElicitationConfirmTest.kt`

**Interfaces:**
- Produces: when `requireInteractiveConfirm=true` and the client declared the `elicitation` capability at initialize, destructive tools trigger `elicitation/create` ("Really run android_app_clear_data on <pkg>@<serial>?", boolean schema) and proceed only on an affirmative response; when the client lacks elicitation, the tool is denied with code `interactive-confirm-unavailable` and a message telling the user to either use an elicitation-capable host or leave the knob off. `confirmDestructive` keeps its current accident-guard semantics and is still required.

**Steps:**

- [ ] **Step 1: Failing trust-split test** — project config setting `requireInteractiveConfirm` is ignored with the privileged-key warning; property test extended so the invariant covers the new field.
- [ ] **Step 2: Failing handler tests** — with knob on + client capability: elicitation round-trip accept ⇒ allowed, decline ⇒ denied `user-declined`; knob on + no capability ⇒ `interactive-confirm-unavailable`; knob off ⇒ today's behavior byte-for-byte.
- [ ] **Step 3: Implement; green; update `docs/security-and-permissions.md` threat-model section (the "accident guard" caveat gains its opt-in strong mode). Commit `feat(security): optional human-in-the-loop confirmation via MCP elicitation`.**

---

## Workstream 3 — Stabilization

### Task WS3-T1: Windows + macOS CI matrix (see WS5-T1 for the workflow change; this task is the code fixes it will surface)

**Files:** whatever the matrix run flags — expected suspects: path separators in `ArtifactWriter`/`ProjectLocator`, `chmod` assumptions in tests, process-tree kill on Windows (`ProcessHandle.destroy` semantics differ), `#!/usr/bin/env bash` scripts invoked from tests.

**Steps:**

- [ ] **Step 1: Land WS5-T1 with `continue-on-error: true` for windows/macos legs; collect the failure list.**
- [ ] **Step 2: Fix each failure with a regression test; one commit per root cause.**
- [ ] **Step 3: Flip `continue-on-error` off; commit `ci: require green tests on windows and macos`.**

### Task WS3-T2: Finish the swallowed-exception audit (T3 remainder)

**Files:**
- Modify: `perfetto-core/src/main/kotlin/com/droidagentkit/perfetto/TraceProcessor.kt:87,98` (replace `catch (_: Exception)` with typed catches + a `warnings +=` entry in the result, matching the `Redaction.kt` warning pattern)
- Audit (read-only unless a real swallow is found): the 35 `getOrNull()/getOrDefault` sites — `grep -rn "getOrNull()\|getOrDefault" --include="*.kt" */src/main`
- Test: extend `perfetto-core/.../PerfettoCoreTest.kt` — a trace-processor failure surfaces a warning string rather than an empty result.

- [ ] Write the failing warning-surfacing test; fix; one-line note per audited `getOrNull` site in the PR description; commit `fix(perfetto): surface trace-processor failures as warnings`.

### Task WS3-T3: 1.0 stability contract

**Files:**
- Create: `docs/compatibility.md` — what "stable" means per surface: MCP tool names/schemas (additive-only), CLI flags, config schema (`schemaVersion` bump policy), artifact layout, supported protocol versions, JDK floor.
- Modify: `README.md` Status section; `CHANGELOG.md` gains a "pre-1.0 exit criteria" list (all WS4/WS5 tasks + zero known trust-model breaks + two consecutive no-security-fix releases).

- [ ] Write it; link from README; commit `docs: define the 1.0 stability contract`.

---

## Workstream 4 — Security (stay ahead)

### Task WS4-T1: Per-tool rate limiting / output budgets

**Files:**
- Create: `toolbox-core/src/main/kotlin/com/droidagentkit/core/InvocationBudget.kt`
- Modify: `DroidAgentMcpDispatcher.kt` (consult budget before dispatch)
- Test: `toolbox-core/.../InvocationBudgetTest.kt`

**Interfaces:**
- Produces: `class InvocationBudget(clock: java.time.Clock)` (Clock-injected — house rule, no `System.currentTimeMillis`) enforcing: max concurrent processes (default 3), max invocations/minute per destructive tool (default 6), cumulative artifact bytes per session (default 1 GiB). Exceeding ⇒ `Denied("budget-exceeded", …)`. Limits are policy-configurable under `safety.budgets.*`, privileged keys.

- [ ] Failing tests with `Clock.fixed` stepping; implement; trust-split test for the new keys; commit `feat(security): per-session invocation and artifact budgets`.

### Task WS4-T2: Artifact-dir hygiene — reject a pre-existing hostile `build/droidagentkit`

**Files:**
- Modify: `toolbox-core/src/main/kotlin/com/droidagentkit/core/ArtifactWriter.kt` — at construction, if `outputDir` exists: refuse to operate when it is itself a symlink, when any ancestor under the project root is a symlink, or when it is world-writable (POSIX); extend the existing symlink rejection from files (S6 fix) to the directory spine.
- Test: `toolbox-core/.../ArtifactSafetyTest.kt` additions.

- [ ] Failing tests (symlinked outputDir, symlinked parent); implement; commit `fix(security): verify the artifact directory spine, not just leaf files`.

### Task WS4-T3: Threat-model doc: prompt-injection walk-through

**Files:**
- Modify: `docs/security-and-permissions.md` — add a worked example per tool group: "agent reads hostile logcat line / crash message / README → what can it now do with this group enabled, what is bounded by policy, what is not." Include the elicitation strong mode (WS2-T3) in the mitigation table.

- [ ] Write it; cross-link from README and SECURITY.md; commit `docs(security): worked prompt-injection scenarios per tool group`.

---

## Workstream 5 — CI: prove it on every PR

### Task WS5-T1: OS matrix + smoke test + stdio E2E in CI

**Files:**
- Modify: `.github/workflows/ci.yml`

**Steps:**

- [ ] **Step 1:** extend the existing job matrix to `os: [ubuntu-latest, macos-latest, windows-latest]` × `java: [17, 21]` (keep hygiene/lint/detekt/coverage on ubuntu-only legs to save minutes; tests run everywhere). Windows/macOS start `continue-on-error: true` (see WS3-T1).
- [ ] **Step 2:** new `smoke` job (ubuntu + macos): `bash distribution/smoke-test.sh` with `DROIDAGENT_E2E=1` — builds `:cli:installDist`, runs the launcher against the mock release server, and does a real stdio `initialize` round-trip.
- [ ] **Step 3:** commit `ci: os matrix plus launcher smoke and stdio e2e`.

### Task WS5-T2: Samples integration job (no Android SDK needed)

**Files:**
- Modify: `.github/workflows/ci.yml` (new job `samples-integration`)
- Create: `scripts/ci-samples-check.sh`

**Interfaces:**
- The inspector and auditor are static parsers — they read Gradle files without executing Gradle, so this job needs no Android SDK. It runs: `droidagent inspect --project samples/basic-compose --format json`, asserts module/version fields non-empty via `jq`; `inspect` on `samples/multimodule` asserts both modules found; `audit --project samples/basic-compose` asserts a numeric score; `inspect` on `samples/broken-project` asserts a structured error, exit code per `docs/cli-reference.md`.

- [ ] Script with explicit `jq` assertions; wire job; commit `ci: exercise the cli against the sample projects`.

### Task WS5-T3: Nightly emulator E2E (device-tool proof)

**Files:**
- Create: `.github/workflows/nightly-emulator.yml` (schedule, `ubuntu-latest` with KVM per `reactivecircus/android-emulator-runner`, SHA-pinned like every other action in this repo)
- Create: `scripts/e2e-emulator.sh`

**Interfaces:**
- Boots an API-34 AVD, builds `samples/basic-compose`, then drives the *MCP server itself* over stdio: `android_devices_list` (1 device), `android_app_install` + `android_app_launch`, `android_screen_snapshot` (PNG non-empty), `android_logcat_capture`, `android_test_run`. Failures upload the artifact dir. This is the only place device tools get real-hardware verification — everything else mocks `AdbExecutor`.

- [ ] Script; workflow; badge in README once stable for a week; commit `ci: nightly emulator end-to-end over real mcp stdio`.

### Task WS5-T4: Coverage breadth + release-candidate dry-run

**Files:**
- Modify: `build.gradle.kts` (root Kover verify: per-module minimum 70% on `mcp-server`, `android-inspector`, `auditor-cli`, keeping toolbox-core's stricter policy-class rule)
- Modify: `.github/workflows/ci.yml` (gate the new rule; `npm pack --dry-run` on the launcher; `scripts/check-release-version.sh` against the current version so drift fails at PR time, not tag time)

- [ ] Verify the gate fails when a rule is raised to 99 (same both-directions check as commit `c1adf79`); commit `ci: widen coverage gates and dry-run the release surface`.

---

## Workstream 6 — Feature roadmap ("best Android MCP")

Priority order set by the competitive research: **T0 (flow recording) and the WS0 discoverability tasks below are the two highest-leverage items in this entire plan.** T1–T6 deepen the moats no competitor has.

### Task WS6-T0: Record agent sessions as durable test flows (`android_flow_record_*`)

The moat-attack feature: Maestro/appium-mcp turn agent exploration into repeatable tests; DAK executes flows (`android_run_flow`) but never emits one.

**Files:**
- Create: `android-device-core/src/main/kotlin/com/droidagentkit/device/FlowRecorder.kt`
- Create: `android-device-core/src/main/kotlin/com/droidagentkit/device/FlowEmitters.kt` (serialize a recorded step list as (a) DAK's own `android_run_flow` JSON, (b) Maestro YAML, (c) a Compose-UI-test Kotlin skeleton)
- Modify: `mcp-server/src/main/kotlin/com/droidagentkit/mcp/tools/DeviceControlToolProvider.kt` (three tools in DEVICE_CONTROL: `android_flow_record_start`, `android_flow_record_stop` (writes artifacts via `ArtifactWriter`), and interception of the existing tap/swipe/type/key/launch paths so gated input tools append to an active recording)
- Test: `android-device-core/src/test/kotlin/com/droidagentkit/device/FlowRecorderTest.kt`, `FlowEmittersTest.kt`, provider tests

**Interfaces:**
- Produces: `class FlowRecorder(clock: java.time.Clock)` — `start(name)`, `append(step: FlowStep)`, `stop(): RecordedFlow`; `data class FlowStep(kind, target, value, delayMs)`; emitters are pure functions `RecordedFlow -> String`. Recording state lives in the dispatcher session (like `ManagedJobRunner` jobs); no new capability — recording only observes calls that were already authorized, and the *write* happens through `ArtifactWriter` into the artifact dir.

**Steps:**

- [ ] **Step 1: Failing emitter tests** — a fixture `RecordedFlow` (launch → tap element → type → assert-visible) round-trips through the run-flow JSON emitter and back through the `android_run_flow` parser; Maestro YAML output matches a golden string; Kotlin skeleton compilability asserted by string shape (package/class/`@Test` present).
- [ ] **Step 2: Failing recorder tests** — steps appended with `Clock.fixed` timestamps; `stop` without `start` errors; second `start` errors (`recording-in-progress`).
- [ ] **Step 3: Implement recorder + emitters; green.**
- [ ] **Step 4: Provider wiring + tests** — input tools append only while recording; `stop` writes `flows/<name>.json|.yaml|.kt` artifacts and returns their refs; manifest-hash test (`ToolManifestIntegrityTest`) updated.
- [ ] **Step 5: Docs (tool tables, README bullet, a recipe in `docs/`); commit `feat(device): record agent sessions as replayable flows, maestro yaml, and compose test skeletons`.**

### Task WS6-T0b: One-call triage aggregation (`android_doctor` MCP tool)

Companion to the CLI doctor (WS1-T3) so every host gets preflight + one-call diagnostics (pattern proven by us-all's `device-health` and Ghost's `doctor`).

**Files:**
- Modify: `mcp-server/src/main/kotlin/com/droidagentkit/mcp/DroidAgentMcpDispatcher.kt` (CORE tool `android_doctor`, `readOnlyHint=true`, reusing WS1-T3's check engine — extract that engine into `toolbox-core` so CLI and MCP share it)
- Test: dispatcher test asserting the same check rows as the CLI test fixtures

- [ ] Failing test; extract `DoctorChecks` into `toolbox-core`; wire tool; docs; commit `feat(mcp): android_doctor preflight tool`.

### Task WS6-T1: R8/ProGuard mapping-aware crash de-obfuscation

**Files:**
- Create: `mcp-server/src/main/kotlin/com/droidagentkit/mcp/tools/RetraceEngine.kt` (pure-Kotlin mapping.txt parser — frame class/method/line remap; no new dependency)
- Modify: `mcp-server/src/main/kotlin/com/droidagentkit/mcp/tools/CrashLogTriage.kt` (accept optional `mappingFile` arg confined via `OperationPolicy.hostPaths`; auto-discover `build/outputs/mapping/*/mapping.txt`)
- Test: `mcp-server/src/test/kotlin/com/droidagentkit/mcp/tools/RetraceEngineTest.kt` with a fixture mapping + obfuscated trace

- [ ] Failing fixture test (obfuscated frame ⇒ original symbol); implement parser; wire arg + schema (additive) into `android_crash_triage`; update docs tool table; commit `feat(triage): de-obfuscate release crashes with the R8 mapping`.

### Task WS6-T2: APK analysis tool (`android_apk_analyze`)

**Files:**
- Create: `mcp-server/src/main/kotlin/com/droidagentkit/mcp/tools/ApkAnalyzer.kt` (zip central directory read via JDK `ZipFile` — size by category dex/res/assets/native/libs, per-ABI breakdown, optional diff vs a second APK)
- Modify: `DroidAgentMcpDispatcher.kt` (new CORE tool `android_apk_analyze`, `readOnlyHint=true`, apk paths confined via `hostPaths`)
- Test: `ApkAnalyzerTest.kt` (fixture APK built as a zip in the test)

- [ ] Failing test on a synthetic apk-shaped zip; implement; register + manifest-hash test update (`ToolManifestIntegrityTest`); docs; commit `feat(mcp): apk size analysis and diff tool`.

### Task WS6-T3: Screen recording (`android_screen_record`)

**Files:**
- Modify: `mcp-server/src/main/kotlin/com/droidagentkit/mcp/tools/DeviceControlToolProvider.kt` (wrap `adb shell screenrecord` under `ManagedJobRunner` with the existing bounded-job pattern from `android_logcat_start`; `device_input` NOT required — new capability reuse: gate on `DEVICE_INPUT`? No — recording observes, gate on existing `SENSITIVE_DIAGNOSTICS` in `device_read`; confirm group placement in review)
- Test: `DeviceControlToolProviderTest.kt` additions with fake `AdbExecutor`

- [ ] Failing tests: start caps duration at 180s, stop pulls the mp4 through `ArtifactWriter` (sensitivity SENSITIVE), device temp file cleaned up; implement; docs; commit `feat(device): bounded screen recording`.

### Task WS6-T4: Semantic UI interaction (`android_ui_find`, `android_input_tap_element`)

**Files:**
- Modify: `android-device-core/src/main/kotlin/com/droidagentkit/device/UiHierarchyParser.kt` (already parses the hierarchy — add query: by text/desc/resource-id, exact + contains, returns node bounds center)
- Modify: `DeviceControlToolProvider.kt` (`android_ui_find` read-only in DEVICE_READ group; `android_input_tap_element` in DEVICE_CONTROL gated on `device_input`, resolves element then delegates to the existing tap path)
- Test: `UiHierarchyParserTest.kt` + provider tests

- [ ] Failing parser-query tests on the existing XML fixtures; implement; provider tests (ambiguous match ⇒ error listing candidates, zero match ⇒ error with nearest-text suggestions); docs; commit `feat(device): find-by-text and tap-by-element`.

### Task WS6-T5: Compose recomposition analysis from Perfetto traces

**Files:**
- Modify: `perfetto-core/src/main/kotlin/com/droidagentkit/perfetto/TraceProcessor.kt` (new analysis: recomposition-count query over Compose trace sections; top recomposed composables, skipped-frame correlation)
- Modify: `PerfettoToolProvider.kt` (new `analysis=compose_recomposition` mode on `android_perfetto_analyze` — additive enum value)
- Test: `PerfettoCoreTest.kt` fixture with Compose section names

- [ ] Failing fixture test; implement query; docs; commit `feat(perfetto): compose recomposition analysis`.

### Task WS6-T6: Baseline profile / macrobenchmark task support

**Files:**
- Modify: `toolbox-core/src/main/kotlin/com/droidagentkit/core/Config.kt` (default allowlist gains `:*:generateBaselineProfile` — it writes to `src/`, so NO: it belongs in `MUTATING_TASK_PATTERNS` exact-name-only; instead document the exact-name opt-in) 
- Modify: `mcp-server/src/main/kotlin/com/droidagentkit/mcp/tools/BuildProfileParser.kt` (parse macrobenchmark JSON results into structured startup/frame metrics)
- Test: parser fixture test

- [ ] Failing macrobenchmark-results fixture test; implement; docs recipe in `docs/` for the exact-name allowlist entry; commit `feat(perf): parse macrobenchmark results; document baseline-profile opt-in`.

### Task WS6-T7: Token-efficiency controls

appium-mcp's NO_UI mode cuts 60–90% of tokens; us-all trims schemas −73%. DAK equivalent:

**Files:**
- Modify: `DroidAgentMcpDispatcher.kt` + tool providers — additive `verbosity: "full" | "compact"` argument (default `full`) on the highest-output tools (`android_project_inspect`, `android_logcat_capture`, `android_accessibility_snapshot`, `android_dumpsys`, `android_perfetto_analyze`); `compact` returns the structured summary + artifact ref, omitting inline raw text (the full output is always in the artifact anyway).
- Modify: `android_screen_snapshot` — optional `scale` (0.25–1.0) and `format: png|jpeg` args to shrink image payloads.
- Test: per-tool tests asserting compact output byte size < 20% of full on the existing fixtures; schemas remain backward-compatible (old calls unchanged).

- [ ] Failing size-budget tests; implement; docs; commit `feat(mcp): compact verbosity and screenshot scaling to cut token cost`.

### Task WS6-T8 (exploratory, last): Live session viewer

Maestro Viewer / Ghost's dashboard set a demo-appeal bar DAK lacks. Scope deliberately minimal: a localhost page served by the existing `DroidAgentMcpHttpServer` machinery (same loopback + bearer rules) that polls the latest screenshot artifact and tails the tool-call log. **No new network surface beyond what the AS HTTP mode already opens; off by default; explicitly not part of the security-critical path.** Spike behind a `--viewer` flag on `serve-mcp`; ship only if the spike stays under ~500 LOC and adds zero deps. If it doesn't, drop it and revisit post-1.0.

- [ ] Spike branch `feature/live-viewer-spike`; decision recorded in the PR either way.

---

## Workstream 0 — Discoverability (do first; hours, not days)

The research's cheapest-highest-leverage findings. No code risk.

### Task WS0-T1: Make the listing findable for "android"

**Verified 2026-08-08 (experiment, not inference).** The MCP registry's `search` parameter matches
the server **`name` only** — not `description`, not `title`:

```
?search=android   → 23 results, every one has "android" in its name; ours absent
?search=perfetto  → 0 results   (word is in our description)
?search=logcat    → 0 results   (word is in our description)
?search=gradle    → 0 results   (word is in our description)
```

Our name is `io.github.iVamsi/droidagentkit`, so no description or keyword edit can make
`?search=android` return us. `name` is the registry's primary key: publishing under a new name
creates a **second** listing and leaves the current one (0.2.1→0.2.5-alpha, `isLatest: true`)
published and un-deletable.

- [x] **Step 1 (done): npm keywords + Android-first description.** npm *does* index keywords and
  descriptions, the launcher had no `keywords` array at all, and this is fully reversible.
  Landed in `distribution/npm-launcher/package.json`.
- [ ] **Step 2 (DECISION REQUIRED — not actioned):** whether to republish the registry entry as
  `io.github.iVamsi/android-droidagentkit` (or similar). Gains `?search=android` visibility;
  costs a permanently orphaned old listing and a matching `mcpName` change in `package.json`
  (the registry verifies ownership through that field). User-facing install commands reference
  the npm package `@droidagentkit/launcher`, not the registry name, so **no existing user
  breaks either way**. Deliberately left unedited: `server.json` is inert in-repo but publishes
  automatically on the next release tag, so making the edit early would silently spend the
  one-way door.

### Task WS0-T2: One-click installs + catalog listings

**Files:**
- Modify: `README.md` — add Cursor deep-link install button and VS Code `vscode:mcp/install` link (copy the URL formats from the research doc's Maestro citation), alongside the existing `claude mcp add` line; add an explicit "iOS is out of scope — this is the best *Android developer* MCP" positioning sentence to the intro.
- External (manual, listed here so it's tracked): submit to Smithery and any active MCP catalogs; advertise the MCPB bundle (WS1-T4) as a differentiator once it ships.

- [ ] README changes; catalog submissions; commit `docs: one-click install links and explicit android-first positioning`.

---

## Sequencing

1. **WS0** immediately — hours of work, directly attacks the 95× adoption gap.
2. **WS5-T1/T2** next — cheap, and every later task inherits the stronger harness.
3. **WS1-T1/T2/T3** — installation is the adoption bottleneck; independent of everything else.
4. **WS6-T0 (flow recording)** — the research's #1 competitive gap; start as soon as WS5's harness is in.
5. **WS2-T1/T2/T3** — protocol depth; T3 also closes the last hardening-plan security item (S2).
6. **WS3, WS4** interleaved as review capacity allows; WS3-T1 depends on WS5-T1 landing.
7. **Remaining WS6** in numbered order (T0b, T1–T7); each independently shippable; T8 is a time-boxed spike, last.
8. **WS1-T4/T5, WS5-T3/T4** — distribution polish + nightly hardware proof.

## Acceptance criteria (whole plan)

- A developer with no Java, on macOS/Linux/Windows, gets a working server from one `npx`/`claude mcp add` line.
- Every PR proves: 3-OS tests, launcher smoke + stdio E2E, samples integration, coverage gates, detekt/ktlint, hygiene.
- Nightly proves the device tools against a real emulator over real MCP stdio.
- Long tools stream progress and die promptly on cancellation; destructive tools can require a human click on elicitation-capable hosts.
- The trust-split invariant (project ≤ policy) still holds for every new config key, asserted by the property test.
- Feature surface leads the field per the comparison table in `docs/research/android-mcp-landscape.md`.

## Self-review

- Spec coverage: audit ✔ (status table above, verified against HEAD), competitor comparison ✔ (research summary + docs/research/android-mcp-landscape.md), install ✔ (WS0 + WS1), features ✔ (WS2 protocol + WS6 tools, research-prioritized), stabilize ✔ (WS3), security ✔ (WS4 + WS2-T3), tests ✔ (every task is test-first; WS5-T4 widens gates), CI ✔ (WS5).
- Placeholder scan: no `PENDING`/`TBD` markers remain; WS6-T8 is an explicitly time-boxed spike with a recorded decision, not a placeholder.
- Type consistency: `CancellationToken` (WS2-T1) is consumed by WS2-T2's long-tool paths; `ProgressReporter` is a functional interface per house rules; `DoctorChecks` is defined in WS1-T3 and shared by WS6-T0b; `FlowRecorder`/`FlowStep`/`RecordedFlow` (WS6-T0) are self-contained in android-device-core; budget keys (WS4-T1) and `requireInteractiveConfirm` (WS2-T3) both explicitly join `privilegedKeys` + trust-split tests.
- New config keys introduced by this plan (all privileged, policy-only): `safety.requireInteractiveConfirm`, `safety.budgets.*` — both named consistently in WS2-T3/WS4-T1 and both required to extend `ConfigTrustTest` and the trust-split property test.
