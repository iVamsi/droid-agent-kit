# Changelog

All notable changes to DroidAgentKit are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and version numbers follow the alpha
pre-release convention `0.y.z-alpha` until a stable 1.0 release.

## Unreleased

### Added

- `android_screen_record_start` and `android_screen_record_stop` in the `device_read` group, gated
  on `sensitive_diagnostics`. Recording runs as a bounded managed job so the agent can drive the UI
  while it records. Stop pulls the MP4 into sensitive artifact storage and deletes it from the
  device; that delete runs even when the pull retrieves nothing.
- A `compose_recomposition` analysis for `android_perfetto_analyze`, reporting the most-recomposed
  composables. It returns no rows unless the app was built with
  `androidx.compose.runtime:runtime-tracing`, and reports that as "not measured" rather than as
  zero recompositions.
- `android_test_run` now reports androidx macrobenchmark metrics when a run produced
  `*-benchmarkData.json`. Startup metrics and sampled frame percentiles are kept separate rather
  than merged, since the two are not interchangeable. The key is absent when no benchmark ran.
- The nightly emulator job covers screen recording end to end and runs the Perfetto SQL through a
  real Trace Processor, pinned by version and checksum.

### Changed

- Gradle 9.7.0, shadow 9.6.1, logback 1.6.2, and the GitHub Actions bumped to current majors.
- Regenerating `gradle/verification-metadata.xml` no longer pins Kotlin release-candidate
  checksums. KGP's ABI-validation classpath asked for the newest build tools, and
  `--write-verification-metadata` resolves every resolvable configuration; it is now pinned to the
  catalog version.

### Fixed

- CodeQL failed on every pull request. `codeql-action/init` had been bumped to v4.37.6 while
  `analyze` and `upload-sarif` stayed on v4.37.3, and the mismatch surfaces as a configuration
  error rather than a version complaint. All three are pinned together, and a hygiene check now
  fails when they drift apart.

- The 0.3.0-alpha release published to npm but left `latest` pointing at 0.2.7-alpha. The dist-tag
  logic published prereleases to `next` and then ran `npm dist-tag add ... latest`, which cannot
  work under OIDC trusted publishing: OIDC mints a credential scoped to `npm publish` alone, so the
  second call fails with E401 — *after* the package is already published, leaving the release
  half-done and skipping the MCP registry publish that depends on it. The tag is now chosen up
  front and applied by the single `npm publish`, with no follow-up call. The release workflow also
  gains a dispatch entry point to publish the registry for an already-released tag, since
  re-tagging a published version is not possible.

## [0.3.0-alpha] - 2026-08-09

### Security

- **Recorded flows no longer leak typed secrets.** `android_input_type` captures whatever was
  typed, so a recorded login flow contained the password — and the three emitted files were marked
  `PUBLIC`, which is the exact flag report bundles and `audit --redact-public` use to decide what is
  safe to share. Flow artifacts are now `SENSITIVE`, their contents pass through the same redactor
  as command output, and the tool result carries a `flow-contains-typed-text` warning. Redaction is
  still pattern-based and will not recognise an arbitrary password, hence the explicit warning.

### Changed

- npm releases now choose their dist-tag instead of always publishing to `latest`. Stable versions
  take `latest`; prereleases take `next`, and additionally claim `latest` only while no stable
  release has ever shipped — `npx` resolves `latest`, so without that condition the documented
  install command would break before 1.0. After the first stable release, a later prerelease can no
  longer hide it from `npm install`. This removes the last blocker on shipping a non-alpha version.

## [0.2.7-alpha] - 2026-08-09

### Added

- **MCPB bundle** attached to every release (`android-agent-kit-<version>.mcpb`), for one-click
  install in hosts that support it. Built and schema-validated by `scripts/build-mcpb.sh` on every
  PR, not just at release time. The previous `distribution/mcp.json` was replaced: it used a
  bespoke `bundle`/`server`/`runtime` shape that conformed to no published spec, so nothing could
  have consumed it.
- **Homebrew formula** generated per release by `scripts/generate-homebrew-formula.sh`, pinning the
  checksum from the release's own published `.sha256` asset. The tap
  ([iVamsi/homebrew-android-agent-kit](https://github.com/iVamsi/homebrew-android-agent-kit)) is
  live: `brew install iVamsi/android-agent-kit/droidagent`. Automated formula updates on release
  are opt-in via a `HOMEBREW_TAP_TOKEN` secret, so a release never fails when that secret is
  absent — the formula still ships as a workflow artifact.

### Changed

- **MCP registry listing renamed** to `io.github.iVamsi/android-agent-kit` (was
  `io.github.iVamsi/droidagentkit`). The registry's `search` matches the server name only — not the
  description — so a search for "android", the most obvious term for this project, never returned
  it. `name` is the registry's primary key, so becoming findable required a new one.

  **No action needed for users:** installs reference the npm package `@droidagentkit/launcher`,
  which is unchanged.

  Maintainers: the new name publishes automatically on the next release tag. After that release
  succeeds, run `scripts/deprecate-old-registry-listing.sh --confirm` once to mark the old listing
  deprecated with a pointer to the new one.

## [0.2.6-alpha] - 2026-08-08

### Security

- Destructive operations can require a human out-of-band approval via
  `safety.requireInteractiveConfirm` (user policy only). When enabled, the MCP server uses
  `elicitation/create` so the host — not the model — must confirm. Without elicitation, the call
  is denied rather than approved.
- Session budgets cap destructive invocations per minute and (when wired) artifact bytes per
  session, so a prompt-injected agent holding a legitimately granted capability cannot loop forever.
- Artifact output directories that are symlinks or non-directories are refused before any write.
- Storage `adb shell` arguments (including `fileTree` paths and `run-as` package names) are now
  single-quoted with `ShellQuote`. An unquoted path such as `x; id` could previously break out of
  `run-as` into the adb shell user because `adb shell` re-joins argv for `/system/bin/sh -c`.

### Added

- `droidagent doctor` / `android_doctor` — diagnose JDK, adb, policy, and artifact directory before
  tools fail.
- MCP cancellation and progress notifications for long-running tool calls; ProcessRunner kills
  process descendants so a cancelled command cannot hang on a surviving grandchild.
- Optional npm launcher JRE provisioning (SHA-verified) when the machine has no Java; launcher
  forwards any CLI subcommand, not only `serve-mcp`.
- Device: find/tap UI elements by label, compact accessibility snapshot mode, record/replay agent
  flows (`run_flow` JSON, Maestro YAML, Compose test skeleton).
- Triage: ProGuard/R8 retrace for release crashes; APK size analysis.
- CI: tests on Ubuntu, macOS, and Windows × JDK 17/21 (blocking); launcher smoke + stdio E2E;
  sample-project CLI checks; nightly emulator E2E hook; broader Kover floors.

### Fixed

- Closed stdout is treated as end of output, not a failure.
- Windows test fixtures no longer assume POSIX-only paths/permissions.

## [0.2.5-alpha] - 2026-07-31

### Security

- A project-local `.droidagentkit/config.yaml` could widen the Gradle task allowlist instead of
  narrowing it. `safety.allowGradleTasks` was the one authority-granting field not covered by the
  trust split, and the only guard rejected the literal patterns `*` and `**` — so `:*:*` was
  accepted silently and admitted every namespaced task, including `publish*` and `installDebug`.
  Because Gradle tasks execute arbitrary build-script code, a hostile repository could obtain code
  execution on the machine of anyone who pointed an agent at it. Project patterns are now accepted
  only when at least as specific as a pattern the user policy already allows; anything broader is
  ignored with a warning. `safety.maxCommandSeconds` is clamped to the policy value by the same
  path.
- Wildcard allowlist patterns no longer reach tasks that rewrite the working tree or publish
  artifacts (`lintFix`, `updateLintBaseline`, `spotlessApply`, `publish*`, screenshot-recording
  tasks). The default `:*:lint*` previously admitted `lintFix`, which rewrites sources. Naming such
  a task exactly, or setting `safety.allowAnyGradleTask`, still works as explicit consent.

- The built-in redaction rules went quadratic on a long unbroken run of identifier characters.
  `[A-Z0-9_]*` on both sides of the keyword in the password/token/secret assignment rules is
  ambiguous, so a 50,000-character token stalled redaction for over five seconds and 200,000 hung
  it outright. `ProcessRunner` passes up to 10 MB of command output through the redactor, and that
  output is attacker-influenceable (logcat, build and test output), so any tool call could be
  stalled without touching the config. The identifier runs are now bounded, which keeps matching
  linear — one million characters in roughly 700 ms.
- Redaction now runs each pattern under a matching budget and abandons any that exceeds it,
  reporting `redaction-pattern-timeout`. `StackOverflowError` from deep regex recursion on long
  inputs is caught per pattern as well; being an `Error` rather than an `Exception`, it would
  otherwise have escaped and failed the whole tool call.
- Device paths for generic push/pull are checked against an allowlist of public storage instead of
  a list of blocked prefixes. The denylist left everything unnamed reachable, including
  `/data/local/tmp` — the usual staging directory for Android privilege pivots — as well as
  `/etc`, `/vendor`, `/cache`, and `/mnt`.
- Device serials and package names are validated before use. Shell injection was already handled
  by quoting, but a value beginning with `-` sits in flag position for `adb -s <serial>` and
  `run-as <pkg>`.
- Artifact writes refuse to follow a symlink placed at the target path, and both artifact
  registration and host-path authorization now resolve links instead of normalizing lexically. The
  output directory sits under the inspected project (`build/droidagentkit/` by default), so a
  repository could commit a link named like an artifact the writer produces and redirect the write
  anywhere on disk.
- The HTTP transport no longer resolves DNS for the `Host` and `Origin` request headers, which put
  a blocking lookup on the request path and let a name resolving to loopback satisfy the check.
  Bind-time host resolution, which comes from the operator's own command line, is unchanged.

### Changed

- Documented the threat model explicitly, including that `confirmDestructive` is an accident guard
  supplied by the agent rather than a defense against a hostile one, and that redaction is a
  best-effort denylist.

### Added

- Test coverage is measured (Kover) and enforced in CI. The gate targets the classes that decide
  authority — `DroidAgentConfigLoader`, `SafetyConfig`, `DefaultOperationPolicy`, `Redactor`,
  `DeviceIdentifiers` — rather than a project-wide average, which can stay healthy while exactly
  the security-critical code loses coverage. Those classes measure 92.97%; the project aggregate
  is 88.3%.
- `ConfigFuzzTest` now asserts the trust-split invariant directly: for randomly generated project
  configs and policies, the effective config is never more permissive than the policy along any
  axis. The previous fuzz test only asserted the loader does not crash, which stayed true
  throughout the escalation described above.

- Static analysis (detekt) runs in CI with a deliberately narrow, opt-in ruleset covering swallowed
  failures, dead parameters, and locale-dependent case folding. Formatting stays ktlint's job. The
  ruleset found four dead parameters/properties across the codebase, all now removed or given real
  work; no baseline file was needed.
- `scripts/check-public-hygiene.sh` now fails if a tracked source file is classified as binary. The
  existing leak scan uses `git grep -I`, which skips binary files, so a single stray NUL byte in a
  source file would silently exempt it from that check and from any other binary-skipping scanner.

### Fixed

- Database filenames read from a device are validated as bare filenames before being resolved
  against a host directory, matching the confinement `SqliteInspector` already applied to query
  arguments.
- `FileTreeParser.parseFind` discards entries that fall outside the requested subtree. `find` output
  comes from a device the toolkit does not trust, and the `basePath` argument was previously
  accepted but unused, so entries claiming any path were returned as-is.

### Added

- `scripts/check-release-version.sh` now also checks the release jar name hardcoded in
  `distribution/smoke-test.sh`. It was the one version string no guard covered, so a stale value
  there surfaced as a smoke-test failure rather than a release-blocking version mismatch.

## [0.2.4-alpha] - 2026-07-31

### Changed

- The npm publish job no longer passes `--tag alpha`. Every pre-1.0 release now claims `latest`,
  so `npm install @droidagentkit/launcher` and `npx` resolve to the newest build. Previously
  `latest` stayed pinned to 0.2.0-alpha — the one version published without the flag — while
  0.2.1-alpha through 0.2.3-alpha shipped unreachable by default.

### Added

- `scripts/check-release-version.sh` now fails on the first stable (non-prerelease) version,
  forcing an explicit dist-tag decision before a later prerelease could overwrite `latest` and
  hide the stable release.

### Removed

- The `docs/adrs/` architecture decision records. Packaging rationale is covered by
  `docs/easy-mcp-installation.md` and `.github/workflows/release.yml`; the configuration trust
  split is documented in `docs/security-and-permissions.md`, which the generated user policy
  header and the privileged-key config warning now point at.
- Repository history was rewritten to remove those documents, so commit SHAs from the
  0.2.0-alpha era forward have changed and the `v0.2.0-alpha`–`v0.2.3-alpha` tags were
  re-pointed. The npm provenance attestations for those earlier releases reference commits that
  no longer exist; 0.2.4-alpha is the first release whose attestation resolves against the
  current history.

## [0.2.3-alpha] - 2026-07-28

### Added

- `release.yml` now also publishes `distribution/server.json` to the MCP registry on every tag,
  via a new `publish-mcp-registry` job using `mcp-publisher login github-oidc` — same
  no-stored-token model as the npm publish. Previously this was a manual step.

### Fixed

- `scripts/check-public-hygiene.sh` was flagging a synthetic fake-HOME test fixture in
  `JsonAndCommandTest.kt` as a leaked personal path, failing CI on every push to `main` since
  commit `5be79da`. Excluded that test file from the check.

## [0.2.2-alpha] - 2026-07-28

### Fixed

- No code changes; version bump only, to prove `.github/workflows/release.yml`'s npm OIDC
  trusted-publishing job works fully unattended end to end. The npmjs.com Trusted Publisher
  entry had the GitHub owner saved as `ivamsi` (lowercase); GitHub's OIDC token carries the
  exact-cased `repository` claim (`iVamsi/droid-agent-kit`), and npm compares it
  case-sensitively even though GitHub's own URLs are case-insensitive. Fixed on npmjs.com,
  not in this repo.

## [0.2.1-alpha] - 2026-07-28

### Changed

- Align `distribution/server.json` with the official MCP Registry schema (`io.github.iVamsi/droidagentkit`).
- Add `mcpName` to `@droidagentkit/launcher` for registry ownership verification.
- Shorten `server.json` description under the registry's 100-char limit and add `repository.id`.

## [0.2.0-alpha] - 2026-07-28

### Added

- User policy (`~/.droidagentkit/policy.yaml`) as the only place that can grant capabilities, expose
  opt-in tool groups, set host binary paths, or disable redaction. `droidagent init`
  writes the policy and seeds a grant-free project config.
- Release pipeline (`.github/workflows/release.yml`, tag-triggered) builds a `droidagent-cli` fat jar,
  attaches SHA-256 + CycloneDX SBOM, and publishes `@droidagentkit/launcher` via OIDC trusted publishing.
- Tool-manifest integrity test (SHA-256 pin of every tool name/description/schema).
- CLI/docs: `docs/cli-reference.md`, `docs/troubleshooting.md`, docs index; Cursor project-dir env support.

### Security

- Project config can no longer escalate privileges (config trust split).
- Every `adb shell` path is shell-quoted; `apkPath` confined to project root via `OperationPolicy`.
- Gradle runs scrub `GRADLE_OPTS` / `JAVA_TOOL_OPTIONS` and related env vars.
- HTTP: reject non-loopback bind unless `--allow-remote`; Host allowlist; digest-based bearer compare.
- Findings redaction; stdio message size cap; nested-quantifier `extraPatterns` rejected.
- Proxy restore retries + verifies; leftover-proxy preflight warning.
- Gradle wrapper `distributionSha256Sum` pinned.

### Changed

- MCP server id standardized to `droidagentkit` in docs/quickstart.
- `allowAnyGradleTask` (user policy only) replaces project-file catch-all `*` patterns.

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
