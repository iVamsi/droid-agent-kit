# DroidAgentKit: improvement backlog

_Last reviewed: 4 October 2026, against `main` (`4e0b8ec`, 0.3.0-alpha plus the Unreleased section
of the CHANGELOG)._

This is the list of problems found in a full review of every module, the build, CI, the release
pipeline, and the docs. Items are grouped by priority and numbered so [ROADMAP.md](ROADMAP.md) can
refer to them. Each item says what the code does today, why that matters, and what to do next.
Sizes are rough: **S** is under a day, **M** is a few days, **L** is a week or more.

Items marked ✔ were checked by reading the code or running a command during the review. Items
marked _(unverified)_ come from reading alone and need a test or a real run to confirm.

## Shipped since the last review

Everything in [hardening-plan.md](hardening-plan.md) and the
[August roadmap](superpowers/plans/2026-08-08-best-android-mcp-roadmap.md) has landed, except for
the parts listed in this backlog.

| Earlier item | Where it lives now |
|---|---|
| S1: project config widening the Gradle allowlist | `narrowGradleTasks` and `globSubsumes` in `Config.kt` |
| S2: `confirmDestructive` comes from the agent | Documented as an accident guard; opt-in `requireInteractiveConfirm` uses MCP elicitation |
| S3–S8: mutating default tasks, ReDoS, device paths, symlinks, identifiers, DNS | Fixed in 0.2.x; see the August roadmap's status table |
| T1–T7: coverage, detekt, swallowed exceptions, property test, flaky tests | Kover, detekt, and the trust-split property test run in CI |
| Install friction | JRE auto-provisioning, subcommand pass-through, `doctor`, MCPB bundle, Homebrew tap |
| Protocol | Cancellation, progress notifications, elicitation |
| Features | Flow recording, R8 retrace, APK analysis, `ui_find`, screen recording, Compose recomposition, macrobenchmark parsing |

## Urgent: release and CI

### 1. npm `latest` still points at 0.2.7-alpha ✔
- **Status (14 October)**: done. `v0.3.1-alpha` is published and `npm view @droidagentkit/launcher dist-tags` returns `latest: 0.3.1-alpha`.
- **Current state**: `npm view @droidagentkit/launcher dist-tags` returns
  `{ latest: '0.2.7-alpha', next: '0.3.0-alpha' }`. The last release was 9 August. The dist-tag
  fix and three features sit in the CHANGELOG's Unreleased section.
- **Why it matters**: `npx -y @droidagentkit/launcher` is the main install path in the README, and
  `npx` resolves `latest`. Those users get 0.2.7, which still writes typed passwords into recorded
  flows marked `PUBLIC`. 0.3.0 fixed that.
- **Next steps**: cut 0.3.1-alpha. First, fix the false nightly claim in the CHANGELOG (item 2).
  After publishing, confirm `latest` moved. **S**

### 2. The nightly emulator job has not finished since 18 August ✔
- **Status (6 October)**: fixed in #85 and #87. The nightly job passes on API 30 and 34 and opens
  an issue when it fails. The Perfetto analysis check stays opt-in until item 14 is fixed.
- **Current state**: `gh run list --workflow nightly-emulator.yml` shows every run since
  19 August as `cancelled` at the 45-minute job limit. Both API levels hang right after the
  screen-record step. `scripts/e2e-emulator.sh:59-67` starts a new server process for every tool
  call. A managed job keeps that process alive, so the `$(…)` capture never returns.
- **Why it matters**: the nightly job is the only place device tools run on real hardware. Even
  when it runs, `android_screen_record_stop` runs in a different process from `start`, so it
  can't see the job and takes the `unknown-job` path. A cancelled run doesn't alert anyone. The
  CHANGELOG says the job covers screen recording and Perfetto end to end. Neither has ever run.
- **Next steps**:
  1. Drive one long-lived server session per run, for example through a small Node script, so
     start and stop share state.
  2. Put a timeout on each call.
  3. Assert that no `unknown-job` warning comes back.
  4. Open an issue or send a notification when the job fails or is cancelled. **M**

### 3. The release workflow can publish a broken launcher ✔
- **Status (6 October)**: fixed in #80. `publish-npm` waits for `build-and-release`, a `test` job
  gates the build, the fat jar gets a stdio `initialize` check, and npm is pinned.
- **Current state**: `publish-npm` needs only `verify-version` (`.github/workflows/release.yml:85`),
  so it runs alongside the jar build. No job runs `./gradlew test`. The shipped fat jar is never
  started: CI tests `installDist`, and the smoke test serves a fake jar. The job runs
  `npm install -g npm@latest` while holding `id-token: write`.
- **Why it matters**: if the jar build fails, npm still ships a launcher that downloads a missing
  file. Shading problems, such as merged service files or the sqlite native libraries, would only
  show up on users' machines.
- **Next steps**: make `publish-npm` depend on `build-and-release`. Add a test job. Run the built
  jar through the stdio `initialize` check. Pin the npm version. **S**

### 4. Dependabot PRs can't pass CI
- **Status (6 October)**: done. #81 added groups, a job that refreshes verification hashes, and
  auto-merge for patch and minor updates. The backlog is merged (#58, #67, #75, #82, #83). Major
  updates still need a person.
- **Current state**: 12 Dependabot PRs are open and all fail. CodeQL bumps arrive as one PR per
  sub-action, and the repo's own hygiene check rejects split pins. The Kotlin bump arrived as
  three PRs. The logback bump fails because `gradle/verification-metadata.xml` has no entry for
  the new version.
- **Why it matters**: dependencies haven't moved since 18 August. `SECURITY.md` promises a weekly
  Dependabot review.
- **Next steps**: add `groups:` to the Dependabot config (one for `github/codeql-action/*`, one for
  Kotlin). Regenerate verification metadata on Dependabot branches, or document the manual step.
  Merge or close the open PRs. **M**

### 5. Old MCP registry listings are still active
- **Status (6 October)**: done. All five `io.github.iVamsi/droidagentkit` versions are
  `deprecated` and point at the new name.
- **Current state**: the rename to `io.github.iVamsi/android-agent-kit` shipped in 0.2.7, but five
  `io.github.iVamsi/droidagentkit` listings (0.2.1–0.2.6) are still `active`.
  `scripts/deprecate-old-registry-listing.sh` was never run.
- **Why it matters**: registry users can find and install old versions without the security fixes.
- **Next steps**: run the script with `--confirm`. **S**

## High: gaps in the security model

### 6. An agent can run any program through `android_perfetto_analyze` ✔
- **Current state**: the tool reads `traceProcessorShell` from the call's arguments and falls back
  to `safety.traceProcessorPath` only when it's missing
  (`mcp-server/.../tools/PerfettoToolProvider.kt:177-179`). It runs
  `[shellPath, tracePath, "--json", "--query-file", sql]`. `tracePath` is never confined to the
  project.
- **Why it matters**: binary paths are meant to come only from the user policy. With the `perfetto`
  group and `sensitive_diagnostics` enabled, an agent can pass `/bin/bash` as the shell and a script
  it wrote as the trace. That's arbitrary code execution, which the project's own rules forbid.
- **Next steps**: remove the argument, or accept it only when it equals the policy value. Pass
  `tracePath` through `authorize(hostPaths = …)`. Update the tool row in
  `security-and-permissions.md`, which documents the override. Note the removal in the CHANGELOG. **S**

### 7. A broken project config turns off the user policy ✔
- **Current state**: when `.droidagentkit/config.yaml` fails to parse, `loadEffective` returns the
  `Invalid` result (`toolbox-core/.../Config.kt:233`). `serve-mcp` then logs a warning and uses
  `DroidAgentConfig.default()` (`cli/.../DroidAgentMain.kt:33-37`). The user policy is never read.
- **Why it matters**: a repository can ship a deliberately broken config, such as
  `maxCommandSeconds: abc`. That switches off `requireInteractiveConfirm` and resets any stricter
  budget or allowlist the user set. A project file is meant to only narrow the policy.
- **Next steps**: when the project file is invalid, merge the user policy with an empty project
  config. Add a test with a strict policy and a broken project file. **S**

### 8. Device XML is parsed without XXE protection ✔
- **Current state**: `UiHierarchyParser.kt:22` and the SharedPreferences parser
  (`storage-inspector/.../AppDataSnapshot.kt:205-209`) use a default `DocumentBuilderFactory`.
  `TestResultParser` and `LintResultParser` already set `disallow-doctype-decl`.
- **Why it matters**: XXE (XML external entity) processing lets an XML file read local files or
  make network requests while it's being parsed. Here the XML comes from the device or from an
  app's own data, so a hostile app can read files on your machine.
- **Next steps**: add one hardened factory to `toolbox-core` and use it in all four parsers. **S**

### 9. The artifact byte budget is never enforced ✔
- **Current state**: `InvocationBudget.recordArtifactBytes` has no callers outside
  `InvocationBudgetTest`.
- **Why it matters**: `security-and-permissions.md` lists `safety.maxArtifactBytesPerSession`
  (default 1 GiB) as a working limit. It does nothing.
- **Next steps**: call it from `ArtifactWriter` and return `budget-exceeded` when the limit is hit.
  **S–M**

### 10. A user policy can't raise the timeout or the budgets ✔
- **Current state**: `mergeWithWarnings` takes `minOf(project, policy)` for `maxCommandSeconds` and
  both budgets (`Config.kt:265, 294-301`). With no project file, the project side is the built-in
  default: 600 seconds, 6 destructive calls per minute, 1 GiB.
- **Why it matters**: you can't allow a Gradle build longer than 10 minutes, even in your own
  policy. The docs say a project may lower these limits. They don't say the policy can't raise them.
- **Next steps**: compare against the project value only when the project file sets it. Add the
  "policy raises" case to `ConfigTrustTest` and the property test. **S**

### 11. Redaction misses common secret shapes
- **Current state**: in `toolbox-core/.../Redaction.kt`:
  - The `pem-private-key` rule replaces only the `BEGIN … PRIVATE KEY` header line, so the
    base64 key body comes back.
  - The assignment rules need `:` or `=` right after the key name, so `"access_token": "…"` in
    JSON isn't matched because of the closing quote.
  - JWTs, `gho_`, `ghs_` and `ghu_` GitHub tokens, Slack `xox*` tokens, `Authorization: Basic`
    and `Cookie` headers have no rule.
  - When a built-in rule times out on a large output, the rule is skipped and the output is
    returned with only a warning.
- **Why it matters**: all tool output goes to the agent and often into shared reports.
- **Next steps**: redact whole PEM blocks. Allow an optional quote before the separator. Add the
  missing token rules. Withhold the output if a built-in rule times out. Add a fixture test per
  shape. **M**

### 12. Smaller trust-boundary issues
- The destructive budget is charged and the human is asked before the host-path and device-path
  checks run (`CapabilityPolicy.kt:135-184`). A call that's later refused still uses budget, and a
  person can approve something the policy then blocks. Run every check first, then charge, then
  ask. **S**
- SharedPreferences file names from the device only reject `/` and `..`, then go unquoted into
  `run-as … cat shared_prefs/<name>`. Database names already use `isBareFileName`. Use it for
  prefs too. **S**
- Database rows and prefs values are added to the result after redaction runs. Redact them, or
  document the exception in the security doc. **S**
- HAR query strings aren't redacted (`HarParser.splitUrl`), so `?access_token=` reaches the agent.
  **S**
- `VisualsToolProvider.confinedPath` uses `normalize()`, not the symlink-aware check. A symlinked
  goldens folder lets `android_visual_update_goldens` write outside the project. **S**
- `install-mcp`: when every `--targets` value is unknown, the parser falls back to all targets.
  So a typo writes every host's config and installs the macOS LaunchAgent. The HTTP token file is
  written first and set to `600` afterwards. **S**
- The npm launcher downloads the jar's SHA-256 from the same GitHub release as the jar. That
  catches corruption but not a replaced release. Bake the hash into the npm package at publish
  time; npm provenance already covers the package. **M**

## High: tools that return wrong or empty results

### 13. `android_input_key` always fails ✔
- **Current state**: the schema requires `keyCode`
  (`mcp-server/.../tools/DeviceControlToolProvider.kt:553`). The code reads `arguments["keycode"]`
  (line 949).
- **Why it matters**: an agent that follows the schema always gets `missing-keycode`.
- **Next steps**: read `keyCode`. Add the schema-parity test from item 32 so this class of bug
  fails CI. **S**

### 14. The Perfetto analyses don't work on real traces ✔
- **Current state**:
  - `cpu_utilization.sql` selects `process_name` from `sched_slice`, which has no such column.
  - `main_thread_slices.sql` joins `slice.utid`, which doesn't exist.
  - `frame_jank.sql` reads `frame_timeline_jank`, which isn't a real table. The real ones are
    `actual_frame_timeline_slice` and `expected_frame_timeline_slice`.
  - The main-thread filter `name = 'main'` doesn't match Android main-thread names.
  - The binder and contention queries match on the wrong names.
  - The default capture config lists `linux.sched`, `linux.cpu.frequency` and `android.binder` as
    data sources (`perfetto-core/.../PerfettoCapture.kt:22-30`). Perfetto has no sources by
    those names. Sched, frequency and binder events come from `linux.ftrace` with an
    `ftrace_config`.
  - `trace_processor_shell` has no `--json` flag. It prints its help and exits, so every analysis
    reports `not-run`. Classic `-q FILE` prints CSV with a header row, so the parser needs a CSV
    path.
- **Why it matters**: every analysis returns "data unavailable" or nothing. The tests feed
  hand-written JSON to the parser and never run SQL, so CI stays green.
- **Next steps**:
  1. Ship a fixed ftrace and atrace config.
  2. Rewrite the queries on the Perfetto standard library (`INCLUDE PERFETTO MODULE …`).
  3. Add a `packageName` filter.
  4. Check in a small real trace and run every query against it in an opt-in test.
  5. Set `DAK_E2E_PERFETTO_ANALYZE=1` in `nightly-emulator.yml` so the nightly job checks analysis
     again. **M**

### 15. Storage inspection misses most Room databases and kills the app on every query ✔
- **Current state**:
  - Only files ending in `.db` count as databases (`AppDataSnapshot.kt:72`,
    `SqliteInspector.kt:56`). Room names databases freely; Now in Android's is `nia-database`.
  - Every list, schema and query call force-stops the app and copies all its data again.
  - The SQL keyword filter uses `contains(" $kw")`, so `SELECT updated_at` and `deleted` are
    blocked.
  - A lowercase `pragma` always fails the PRAGMA allowlist.
- **Why it matters**: on many real apps `android_db_list_databases` finds nothing, and a
  "read-only" tool kills the app it inspects.
- **Next steps**:
  1. Detect SQLite by its file header.
  2. Cache the snapshot per device and package, with a `refresh` flag.
  3. Make force-stop opt-in.
  4. Match keywords on word boundaries.
  5. Uppercase before the PRAGMA check. **S–M**

### 16. The generated SKILL.md has broken frontmatter ✔
- **Current state**: `ReadinessAuditor.kt:433-440` builds the file with a raw string and
  `trimIndent()`. The interpolated AGENTS text starts at column 0, so nothing is trimmed, and
  `---` and `name:` keep 12 leading spaces. The only test checks that the file exists.
- **Why it matters**: hosts don't recognise the skill that `audit --write-agents` writes.
- **Next steps**: build the header separately. Assert that the file starts with `---\n`. **S**

### 17. Dumpsys parsers expect formats Android doesn't print
- **Current state**: `DumpsysSummaryParser` requires `KB` for meminfo, but real output uses `K`
  (`Total RAM: 3,825,572K`). It expects battery `status: Discharging`, but real output is
  `status: 2`. Its `\s*` can match across lines. The gfxinfo and meminfo presets ignore
  `packageName` but still record it in provenance. The test fixtures were written by hand to match
  the parser.
- **Why it matters**: `android_memory_summary` and `android_battery_summary` mostly return
  "not found" or wrong values.
- **Next steps**: capture real output from an emulator as fixtures. Anchor the regexes to lines.
  Map the battery integers to names. Pass the package to gfxinfo and meminfo. **S**

### 18. Recorded flows don't replay correctly
- **Current state**:
  - `android_input_tap_element` calls the public `call("android_input_tap")`, so the tap is
    recorded twice.
  - `android_app_launch` is in `RECORDABLE_TOOLS` but lives in the core dispatcher, so it's never
    recorded, and Maestro's `appId` falls back to `com.example.app`.
  - The Maestro swipe reads `startX`/`endX`, but the tool's arguments are `x1`/`x2`, so swipes come
    out as `null,null`.
  - Resource-id selectors are emitted as text matches.
  - `android_run_flow` can call itself with no depth limit.
- **Why it matters**: flow recording is the feature that turns an agent session into a reusable
  test. Today the output needs hand-fixing.
- **Next steps**: record at the dispatcher level, once per outer call. Fix the argument names and
  selector mapping. Block `run_flow` inside a flow. Add golden tests for each emitter. **S–M**

### 19. Visual diffs fail on a single anti-aliased pixel
- **Current state**: `PngDiffEngine.kt:30` counts any pixel that isn't exactly equal as changed.
  Line 46 also requires the largest color distance to be within `maxColorDistance` (default 3).
  An anti-aliased edge pixel usually differs by 10 to 60. The capture manifest is only ever
  appended to. The Gradle plugin's default matrix (light and dark × three font scales) doesn't
  match what the test helper captures.
- **Why it matters**: `maxChangedPixelPercent` has no effect, so goldens are flaky across machines.
  Out of the box every case reports missing captures.
- **Next steps**: count a pixel as changed only above the distance threshold, then apply the
  percentage. Clear or deduplicate the manifest per run. Align the default matrices. **M**

### 20. Smaller tool bugs
- `android_intent_invoke` always targets `$pkg/.MainActivity`. **S**
- `android_emulator_start` kills the emulator after 600 seconds. **S**
- `android_screen_snapshot` and `android_accessibility_snapshot` run `"adb"`, not `safety.adbPath`.
  **S**
- `android_ui_find` and `android_input_tap_element` pass `rootPath = ""` when the argument is
  missing, which resolves to the working directory and is blocked whenever that isn't the project.
  **S**
- Artifact names are fixed per tool, so two concurrent calls of the same tool overwrite each
  other's log. **S**
- `android_crash_triage` reads `mappingFile`, but the argument isn't in its schema. **S**

## Medium: server robustness and protocol

### 21. An exception inside a tool leaves the client without a reply
- **Current state**: `McpJsonRpcHandler.handleToolsCall` (line 316) has `try`/`finally` but no
  `catch`. Over stdio the exception stays inside the worker's `Future`, so the client waits
  forever. For methods like `resources/read` it escapes the read loop and the server exits.
  Several reads have no `try`, for example `Files.readString` in the dispatcher.
- **Next steps**: wrap each request. Return `isError: true` for tool calls and JSON-RPC error
  `-32603` for everything else. Add a test with a tool that throws. **S**

### 22. The JSON writer can produce invalid JSON ✔
- **Current state**: `toolbox-core/.../Json.kt:46-51` escapes only `\`, `"`, `\n`, `\r` and `\t`.
  Other control characters, such as the ANSI escape codes in Gradle and logcat output, are written
  raw. `NaN` and `Infinity` are written as bare words.
- **Why it matters**: strict clients such as Node's `JSON.parse` reject the whole frame. A single
  colored log line can break a tool call.
- **Next steps**: escape everything below `0x20` as `\u00XX`. Write non-finite numbers as `null`.
  **S**

### 23. Cancelling doesn't stop Gradle or core adb commands
- **Current state**:
  - The Gradle path (`DroidAgentMcpDispatcher.kt:783`) and the core adb paths (lines 1088, 1129,
    1367) call `runner(root).run(...)` without the call's cancellation token. Only the provider
    path at line 1636 passes it.
  - `DroidAgentWorkspaceDispatcher`, used in Android Studio mode, drops the per-call context, so
    cancellation, progress and elicitation are lost there too.
  - Every progress notification reports `0.0`, but the spec requires the value to increase.
- **Why it matters**: the main reason to support cancellation, stopping a long build, doesn't work
  for `android_gradle_run`, `android_lint_run` or `android_test_run`.
- **Next steps**: route these calls through the shared `run(root, spec)` path. Forward the context
  in the workspace dispatcher. Count progress per call. **S**

### 24. `ManagedJobRunner` gets its locks wrong ✔
- **Current state**: in `toolbox-core/.../ManagedJobRunner.kt`:
  - A read-only job with a device serial, such as `android_logcat_start` or
    `android_screen_record_start`, takes a permit from `readOnlySlots` (line 60). Then
    `releaseLock` gives the permit back to the device lock (lines 143-147). After four such
    jobs, every new read-only job fails with `read-concurrency-full`, and the device lock
    collects extra permits, so it stops keeping mutating jobs apart.
  - `cancel()` after completion releases again.
  - The timeout path and the worker thread can both release.
  - Each job's executor is never shut down.
  - Finished jobs are never removed from the map.
  - Expired jobs are marked `EXPIRED` but their process keeps running.
  - Job ids are millisecond timestamps, so two starts in the same millisecond collide.
- **Next steps**: store the acquired semaphore on the job. Finish a job through a single
  compare-and-set so release happens once. Use one shared pool. Drop finished jobs after a TTL.
  Kill expired processes. **M**

### 25. Other server issues
- `CancellationToken.NONE` is shared, and every call without a token adds a hook that holds its
  `Process`. That's a slow leak. **S**
- Over HTTP, a destructive call with `requireInteractiveConfirm` sends the elicitation request to a
  no-op and blocks one of four workers for five minutes. The HTTP handler also shares
  `clientSupportsElicitation` across all clients. **S**
- A tool's `timeoutSeconds` isn't capped by `maxCommandSeconds` in `lintRun` and
  `buildPerformance`. **S**
- A call still waiting in the stdio queue can't be cancelled. On disconnect, child Gradle and adb
  processes aren't killed. **S**

### 26. Protocol details
- `initialize` advertises `resources: {list, read}` and `prompts: {list, get}`. Those keys aren't
  in the spec, which defines `subscribe` and `listChanged`. They're advertised even in workspace
  mode, which has no resources. **S**
- Both resource templates (`droidagent://artifacts/{id}` and `goldens/{case}`) have a reader that
  returns `null`, so every read fails. The security doc describes them as working. Implement them
  or stop advertising them. **S–M**
- `droidagent://project/inspect` says `text/markdown` but returns JSON. **S**
- Only protocol `2025-11-25` is accepted. Over HTTP, any other `MCP-Protocol-Version` header gets a
  400. The server uses nothing that needs that version, so `2025-06-18` could be accepted too.
  **S**
- Error codes: an unknown prompt returns `-32601`, and a missing tool name returns `-32600`. Both
  should be `-32602`. **S**
- Every tool shares one loose `outputSchema`, and job tools return statuses outside the documented
  set (`running`, `pending`, `expired`). The full result is also copied into `text`, which doubles
  the token cost. **M**
- Not implemented: `logging`, `completions`, `listChanged`, pagination cursors. Add `logging`
  first. **M**

### 27. The config loader ignores input it can't read
- **Current state**: the hand-written YAML parser in `Config.kt`:
  - Treats `allowCapabilities: [device_input]` as a scalar and grants nothing, with no warning.
    `ConfigYaml.renderProject` writes `extraPatterns: []` in exactly that style.
  - Drops list items under a misspelled key without a warning.
  - Doesn't strip inline `# comments`, so `adbPath: /x/adb # note` becomes part of the path.
  - Accepts `maxCommandSeconds: 0`, which later throws in `coerceIn(1, 0)`.
- **Why it matters**: the config is where permissions come from, so silent drops hide mistakes.
- **Next steps**: support or reject `[a, b]`. Warn on unknown list keys. Strip comments. Require
  numbers ≥ 1. **S**

### 28. The CLI parser accepts bad input silently
- No `--flag=value` form.
- A non-numeric `--fail-under` becomes `null`, which turns off the CI gate.
- `--transport` isn't validated: anything other than `stdio` starts HTTP.
- `audit --verify` is parsed but unused.
- `init --force` replaces the whole policy, losing custom settings. Its help text says it writes
  `config.yaml`, but it writes the user policy.
- There's no `--version` flag.
- `install-mcp` always exits 0.
- _(unverified)_ re-running `install-mcp` for Claude Code may not update an existing entry.

Size: **S** each.

## Medium: understanding real projects

### 29. The inspector misreads modern multi-module builds
Compared against [Now in Android](https://github.com/android/nowinandroid) (NiA), a common
reference project:
- Module dependencies are empty: only `project(":x")` is parsed, not type-safe accessors like
  `projects.core.domain`. **S**
- The command matrix always emits `testDebugUnitTest` and `lintDebug`, even for flavored modules,
  and those commands go into the generated AGENTS.md. **M**
- Kotlin DSL flavors written as `create("demo")` aren't found, and nested blocks like `ndk {` are
  counted as build types. **S**
- `applicationId` is never parsed. Agents pass the namespace to launch and storage tools and target
  an app that isn't installed. **S**
- SDK levels set in convention plugins, or in the AGP 9 form `compileSdk { version = release(36) }`,
  come back null. **S**
- Convention plugins are found only through `apply(plugin = "…")`. `pluginManager.apply`,
  `buildSrc`, precompiled script plugins and Groovy `includeBuild 'x'` are missed. **M**
- Comments aren't stripped, so a commented-out plugin still counts. **S**
- The toolchain table knows three Kotlin versions and checks the server's own JDK, not the
  project's. NiA comes back `UNKNOWN` on every line. **M**
- Roborazzi and Paparazzi aren't detected. **S**

### 30. The readiness score is easy to inflate
- The checks add up to 115 points and are then clamped to 100. The level uses the unclamped value.
- The "documents device expectations" check passes on any README that mentions `device`.
- Static-analysis detection misses Spotless and detekt's default `config/detekt/detekt.yml`.
- The secret scan stops at 1,000 files in walk order, scans ignored files, and flags every
  `google-services.json` API key as CRITICAL. Firebase keys are public by design.
- Nothing checks that `gradlew` exists.
- `--write-agents` overwrites a hand-edited SKILL.md.

Next steps: normalize the rubric to 100, tighten the cheap checks, and use `git ls-files` for the
secret scan. **M**

### 31. Tests use invented fixtures
- **Current state**: the dumpsys, uiautomator, Perfetto and HAR fixtures were written by hand to
  match the parsers. Several were written in a format Android doesn't produce. That's how items
  14, 17 and 18 passed CI.
- **Next steps**: record real outputs once and check them in: meminfo, battery, gfxinfo, a
  uiautomator dump of a Compose screen, a small `.perfetto-trace`, and a NiA-shaped Gradle tree.
  **M**

### 32. No test checks that schemas match the code
- **Next steps**: for every tool, assert that each required schema property is read by the
  implementation. This catches item 13, the swipe mismatch in item 18, and the missing
  `mappingFile` in item 20. **S**

## Medium: maintainability

### 33. `DroidAgentMcpDispatcher.kt` does too much
- **Current state**: 1,714 lines holding:
  - the `McpTool` type
  - the thread-local call context
  - every core tool's schema and implementation
  - resource and prompt wiring
  - the doctor tool
  - the `DeviceToolContext` that every provider depends on
- **Next steps**: move the core tools into a `CoreToolProvider` built like the others. Move the
  context into its own small class. **L**

### 34. Each provider lists its tools three times
- **Current state**: each provider has a name set, a schema list and a `when` dispatch, which can
  drift apart without an error. Schema helpers (`str`, `num`, `bool`, `schema`, `blocked`) are
  copied into seven files. Numbers are declared `"number"` but parsed with `toIntOrNull`, so
  `500.0` becomes the default. No schema sets `additionalProperties: false`, so misspelled
  arguments are ignored.
- **Next steps**: one table per provider mapping each tool to its schema and handler. One shared
  schema helper. Declare counts as `"integer"`. **M**

### 35. The tool-manifest test hides what changed
- **Current state**: `ToolManifestIntegrityTest` hashes only name, description and input schema,
  and a failure prints a bare hash.
- **Next steps**: compare against a checked-in golden JSON so a failure shows a diff. Add a test
  that every listed tool routes to a handler. Add a test that compares tool names in the docs with
  the code. **S**

## Low: build, docs and hygiene

### 36. The visuals kit can't be installed
`visuals-gradle-plugin` and `visuals-android-test` aren't published anywhere, but
`add-compose-visual-reports.md` tells you to apply the plugin by id. CLAUDE.md gives the wrong
plugin id (`com.droidagentkit.visuals`; the real one is `com.droidagentkit.compose-visuals`).
CLAUDE.md and the README call `DroidAgentVisualRule` a JUnit rule, and its own KDoc says it isn't
one.

Decide whether to publish the kit (Gradle Plugin Portal and Maven Central), document
`includeBuild`, or mark it experimental. **M–L**

### 37. Build files
- The version catalog lists only plugins. `junit:4.13.2` is hardcoded 12 times, and Kotlin is
  pinned in both `settings.gradle.kts` and the catalog, which is why one Kotlin bump arrived as
  three PRs. **S**
- `scripts/restore-verification-metadata.sh` points at a commit lost in a history rewrite, and
  `push-verification-metadata-fix.sh` was a one-off. Delete both. Prune the pre-release entries
  from `verification-metadata.xml`. **S**
- Coverage floors exist for 5 of 12 modules. `storage-inspector`, `network-core`,
  `perfetto-core`, `visuals-core` and `cli` have none. Start with storage and network: they handle
  device data. **S**

### 38. CI speed and reach
- No Gradle cache, no `concurrency` group to cancel superseded runs, and no job timeouts. **S**
- The launcher smoke test never runs on Windows, so the PowerShell unzip and the `java.exe` path
  are untested. **S**
- CodeQL scans only Kotlin. The JavaScript launcher, which downloads and runs code, isn't scanned.
  **S**

### 39. Hand-pinned versions with no freshness check
These are pinned by hand, with no job that checks they still resolve or are current:
- the Temurin JRE in `distribution/npm-launcher/jre-manifest.json`
- `@anthropic-ai/mcpb`
- `mcp-publisher`
- Trace Processor
- the versions in `scripts/generate-sbom.sh`

The SBOM also leaves out `kotlin-stdlib`. Add a monthly job that runs the update scripts and opens
a PR, and generate the SBOM from the runtime classpath. **S–M**

### 40. Docs that no longer match the code
- CLAUDE.md:
  - Says commands go through `ProcessRunner` "with the allowlist enforced" in one place, and
    that it doesn't enforce the allowlist in another.
  - Its two device-control tool lists each miss different tools.
  - Its module test list misses four modules.
- `security-and-permissions.md`:
  - Its tool tables miss `android_apk_analyze`, `android_doctor`, `android_ui_find`,
    `android_input_tap_element` and both `android_flow_record_*` tools.
  - Its `device_read` intro says only `android_bugreport` needs `sensitive_diagnostics`.
  - It describes the resource templates (item 26), the artifact budget (item 9) and PEM
    redaction (item 11) as working.
- `cli-reference.md` misses several flags, for example `audit --verify`, `install-mcp --project`
  and the `generic` target.
- `easy-mcp-installation.md`, `troubleshooting.md` and the npm launcher README still say a JDK is
  required. The launcher now provisions one.
- The README says the project config sets capability flags. Only the user policy can.
- `compatibility.md` still lists the blocking 3-OS matrix as an open 1.0 criterion. It's done.
- `CONTRIBUTING.md` and `AGENTS.md`:
  - CONTRIBUTING names one runtime dependency; there are two.
  - AGENTS.md uses bare `gradle`.
  - AGENTS.md says `server.json` is published by hand.

Size: **S** each.

### 41. Samples can't be built
`samples/` holds parser fixtures, not projects: there's no wrapper, no root build file and no
`compileSdk`. That's enough for `scripts/ci-samples-check.sh`, but the nightly job can't install
or test a sample app. Add one minimal buildable app and keep the rest as fixtures. **M**

### 42. Project health
- One maintainer, no commits since 18 August, and 1 star. The bus factor is 1.
- `SECURITY.md` promises a weekly review of Dependabot, CodeQL and Scorecard alerts. Item 4 shows
  that isn't happening. Either do it or change the wording.
- `.kotlin/` isn't in the repo's `.gitignore`.

---

Do items 1–5 first: they make the released version match `main` and make CI trustworthy again.
Then do items 6–16, which the roadmap groups as 0.4.0.
