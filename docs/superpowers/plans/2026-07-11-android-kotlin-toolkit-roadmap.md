# Android and Kotlin Toolkit Modernization Roadmap

**Prepared:** 2026-07-11
**Scope:** DroidAgentKit repository and its public CLI, MCP, inspector, auditor, and visual-report surfaces.
**Evidence policy:** Externally changing claims in this plan come from official Android, Kotlin,
Gradle, or MCP documentation linked below. Recommendations are explicitly marked as project
inferences. The toolkit must remain local-only, deterministic, allowlisted, and free of telemetry.

## Implementation status

- P0 implemented: Kotlin 2.4.0/Gradle 9.5.0 supported pairing, root version catalog, strict
  configuration cache, JDK 17/21 CI matrix, and SHA-256 dependency verification. PGP verification
  was not enabled because signing-key resolution did not complete; no unverifiable key data was
  accepted.
- P1 implemented: AGP 9 built-in Kotlin, Android-KMP, API levels, modern source sets, managed
  devices/groups, screenshot tests, plugin aliases, confidence, and compatibility evidence.
- P2 implemented: `android_test_run`, hardened JUnit parsing, `android_build_diagnose`, conservative
  diagnostic classification, bounded findings, and raw-artifact fallback.
- P3 implemented: official Compose screenshot report discovery/import with local path containment,
  declared role evidence, and explicit experimental provenance.
- P4 implemented: readiness profiles and policy versioning, app-only rule applicability, toolchain
  compatibility findings, dependency-verification checks, and configuration-cache checks.
- P5 implemented for the supported host surface: the existing stdio/HTTP MCP 2025-11-25 contract
  tests remain authoritative, Android Studio stays HTTP-first, and the structured tools are additive.
- P6 partially implemented: versioned official evidence resource, JDK matrix, and dependency
  checksums are present. Release publication, SBOM, and provenance remain release-pipeline work and
  are intentionally not fabricated without an established release workflow.

## 1. Verified repository baseline

The following facts were verified directly in the current working tree:

- The build uses Kotlin JVM `2.3.20`, Gradle `9.5.1`, JVM toolchain 17, and ktlint `14.2.0`.
- CI runs `ktlintCheck` and `test` on JDK 21.
- The MCP server exposes 12 `android_*` tools and supports both stdio and streamable HTTP.
- `AndroidProjectInspector` infers modules, variants, dependencies, versions, and command specs by
  parsing Gradle and manifest text.
- `DroidAgentVisualRule.captureCompose` receives rendered PNG bytes from a caller; it is not an
  official Compose preview renderer or screenshot-test runner.
- `ReadinessAuditor` applies one fixed 100-point model to every repository, including app-specific
  ProGuard and Baseline Profile checks.
- There is no root version catalog, dependency-verification metadata, persistent configuration-cache
  setting, structured test-result tool, or structured build-failure tool.

Relevant code:

- `build.gradle.kts`
- `gradle/wrapper/gradle-wrapper.properties`
- `android-inspector/src/main/kotlin/com/droidagentkit/inspector/AndroidProjectInspector.kt`
- `visuals-android-test/src/main/kotlin/com/droidagentkit/visuals/android/DroidAgentVisualRule.kt`
- `auditor-cli/src/main/kotlin/com/droidagentkit/auditor/ReadinessAuditor.kt`
- `mcp-server/src/main/kotlin/com/droidagentkit/mcp/DroidAgentMcpDispatcher.kt`

## 2. Official developments that affect the toolkit

### 2.1 Kotlin and Gradle compatibility

Verified facts:

- Kotlin `2.4.0` was released on 2026-06-03 and is the current stable release. It adds stable
  context parameters, explicit backing fields, stable common UUID APIs, Java 26 support, and Gradle
  9.5.0 compatibility.
- JetBrains documents Kotlin Gradle plugin `2.4.0` as fully supported with Gradle `7.6.3–9.5.0`.
  Kotlin `2.3.20–2.3.21` is fully supported only through Gradle `9.3.0`. JetBrains states newer
  combinations can be used but may produce deprecations or have features that do not work.
- Gradle `9.6.1` is current as of 2026-07-06, but it is outside Kotlin 2.4.0's documented fully
  supported range. Gradle recommends 9.6.1 generally and adds `--non-interactive`, improved
  configuration-cache hit rates, and early Gradle 10 migration support.
- Gradle 9 treats the configuration cache as the preferred execution mode, although it is still
  opt-in.

Official evidence:

- [Kotlin release history](https://kotlinlang.org/docs/releases.html)
- [What's new in Kotlin 2.4.0](https://kotlinlang.org/docs/whatsnew24.html)
- [Kotlin Gradle compatibility table](https://kotlinlang.org/docs/gradle-configure-project.html)
- [Gradle 9.6.1 release notes](https://docs.gradle.org/current/release-notes.html)
- [Gradle configuration cache](https://docs.gradle.org/current/userguide/configuration_cache.html)

Project inference:

The current Kotlin 2.3.20/Gradle 9.5.1 pairing should not be treated as a verified compatibility
baseline. DroidAgentKit should first align its own build to an officially fully supported pair and
then teach the inspector to report the same compatibility evidence for inspected projects.

### 2.2 AGP 9 built-in Kotlin and Android-KMP

Verified facts:

- AGP 9 enables built-in Kotlin by default; Android modules no longer need
  `org.jetbrains.kotlin.android` solely to compile Kotlin.
- With built-in Kotlin enabled, combining `org.jetbrains.kotlin.multiplatform` with
  `com.android.library` or `com.android.application` in one module is not supported.
- Google officially supports `com.android.kotlin.multiplatform.library` for Android targets in KMP
  library modules. It uses a single variant, puts Android configuration inside `kotlin { android {} }`,
  disables host/device tests by default, and uses `src/androidMain`, `src/androidHostTest`, and
  `src/androidDeviceTest`.
- AGP 9.2 supports API 37 and requires at least Gradle 9.4.1 and JDK 17.
- Google plans to remove legacy AGP DSL/variant APIs in AGP 10 in late 2026; plugin integrations
  should use public DSL and Variant APIs.

Official evidence:

- [Migrate to built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin)
- [Android-KMP Gradle plugin](https://developer.android.com/kotlin/multiplatform/plugin)
- [AGP 9.2 release notes](https://developer.android.com/build/releases/agp-9-2-0-release-notes)
- [AGP DSL/API migration timeline](https://developer.android.com/build/releases/gradle-plugin-roadmap)
- [Kotlin Multiplatform compatibility guide](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html)

Project inference:

The inspector's current string tests and `src/main`, `src/test`, and `src/androidTest` assumptions
will misclassify or under-report valid AGP 9/KMP modules. Compatibility fixtures and a richer module
model are required before advertising AGP 9/KMP support.

### 2.3 Android 17 and test infrastructure

Verified facts:

- Android 17 is API 37 and reached Platform Stability in Beta 3; Google asks SDK, library, tool, and
  engine developers to test and publish compatible updates.
- Build-managed devices let AGP provision, run, and tear down virtual or remote physical devices,
  support device groups and sharding, and emit test reports.
- AGP 9.2 has experimental unified test and coverage dashboards across test types, modules, and
  variants.

Official evidence:

- [Android 17 overview](https://developer.android.com/about/versions/17/)
- [Android 17 release notes](https://developer.android.com/about/versions/17/release-notes)
- [Build-managed devices](https://developer.android.com/studio/test/managed-devices)
- [AGP 9.2 unified reports](https://developer.android.com/build/releases/agp-9-2-0-release-notes)

Project inference:

DroidAgentKit should expose the SDK levels and test-device matrix it can prove from project files,
then execute discovered test tasks and parse their result artifacts. It should not claim source-level
Android 17 compatibility from regex heuristics.

### 2.4 Compose Preview Screenshot Testing

Verified facts:

- Google's Compose Preview Screenshot Testing is experimental and uses `@PreviewTest`, a dedicated
  `screenshotTest` source set, `update<Variant>ScreenshotTest`, and
  `validate<Variant>ScreenshotTest` tasks.
- Validation produces an HTML report under
  `<module>/build/reports/screenshotTest/preview/<variant>/index.html`.
- Full IDE integration requires AGP 9+, Kotlin 2.2.10+, JDK 17+, and the screenshot plugin; the
  underlying Gradle tasks support older documented minimums.

Official evidence:

- [Compose Preview Screenshot Testing](https://developer.android.com/studio/preview/compose-screenshot-testing)

Project inference:

The toolkit should integrate and normalize Google's generated results rather than attempt to own a
second Compose rendering stack. The existing byte-oriented visual engine remains valuable for
deterministic diffing and agent-readable summaries, but `captureCompose(render = ...)` should no
longer be presented as end-to-end Compose screenshot testing.

### 2.5 Android Studio agents and MCP

Verified facts:

- Android Studio Agent Mode already has tools to deploy apps, inspect the screen, take screenshots,
  inspect Logcat, and interact through `adb shell input`.
- Android Studio supports MCP tools over streamable HTTP, but does not support MCP stdio, resources,
  or prompt templates.
- The current MCP protocol specification is `2025-11-25`.

Official evidence:

- [Android Studio Agent Mode](https://developer.android.com/studio/gemini/agent-mode)
- [Add an MCP server to Android Studio](https://developer.android.com/studio/gemini/add-mcp-server)
- [MCP specification 2025-11-25](https://modelcontextprotocol.io/specification/2025-11-25)

Project inference:

Generic build/deploy/screenshot wrappers are not a durable differentiator. DroidAgentKit should
focus on safe cross-client execution, structured Android findings, reproducible artifacts, and
project-readiness evidence. Streamable HTTP must remain a first-class tested transport.

## 3. Prioritized implementation plan

### P0 — Establish a supported and reproducible build baseline

**Goal:** Remove ambiguity from DroidAgentKit's own toolchain before expanding its compatibility
claims.

Tasks:

1. Add an automated compatibility check that records Kotlin, Gradle, AGP, JDK, and compile/target SDK
   versions with an evidence source and status: `SUPPORTED`, `OUTSIDE_DOCUMENTED_RANGE`, or
   `UNKNOWN`.
2. Align the repository to a pair inside JetBrains' fully supported range. The currently documented
   candidate is Kotlin 2.4.0 with Gradle 9.5.0. Do not move to Gradle 9.6.1 until JetBrains documents
   support or the project explicitly accepts an outside-range test lane.
3. Add `gradle.properties` with strict configuration-cache enablement only after two consecutive
   identical CI invocations demonstrate store then reuse without warnings.
4. Add CI jobs for JDK 17 and 21 because the library toolchain targets 17 while CI currently runs 21.
5. Add Gradle dependency verification using SHA-256 and, where verifiable, PGP signatures. Review
   generated metadata before commit.
6. Add a root version catalog so toolchain and library versions have one inspectable source.

Acceptance criteria:

- `./gradlew ktlintCheck test --configuration-cache` succeeds twice; the second run reports cache
  reuse.
- The compatibility report labels the current pair using only embedded, versioned evidence data.
- CI covers JDK 17 and 21 and has no Kotlin/Gradle compatibility warnings.
- `gradle/verification-metadata.xml` is committed and strict verification passes from an empty Gradle
  dependency cache.
- No runtime network lookup is introduced into CLI or MCP tools.

### P1 — Make project inspection AGP 9, KMP, and API 37 aware

**Goal:** Correctly model current Android project layouts and generate only commands supported by
the detected module.

Tasks:

1. Extend `AndroidModuleSummary` with detected plugin IDs, Kotlin integration mode, compile/min/target
   SDK values, Android/KMP source sets, test capabilities, managed devices/groups, and evidence
   locations.
2. Recognize AGP 9 built-in Kotlin without requiring `org.jetbrains.kotlin.android`.
3. Recognize `com.android.kotlin.multiplatform.library` before generic `com.android.library` tests.
4. Parse the KMP `kotlin { android {} }` layout, `withHostTestBuilder`, `withDeviceTestBuilder`, and
   `withJava()` without assuming build types or product flavors exist.
5. Detect `src/androidMain`, `src/androidHostTest`, `src/androidDeviceTest`, and `src/screenshotTest*`
   alongside classic Android source sets.
6. Add managed-device and device-group command specs using the exact names found in Gradle files.
7. Add fixtures for AGP 8 classic Android, AGP 9 built-in Kotlin, Android-KMP, version-catalog plugin
   aliases, Groovy DSL, API 37, and partial/broken projects.
8. Add a confidence field. Static parser results must say `DECLARED`, `INFERRED`, or `UNKNOWN`; they
   must never imply that a Gradle task was executed successfully.

Acceptance criteria:

- Fixture tests prove correct module type, source sets, SDK levels, and command matrix for each
  supported layout.
- KMP modules do not receive nonexistent `testDebugUnitTest`, `lintDebug`, flavor, or build-type
  commands.
- Every reported version or capability includes its file evidence or is explicitly `UNKNOWN`.
- Existing report schema fields remain backward compatible.

### P2 — Add structured test execution and build diagnosis

**Goal:** Turn raw Gradle output into bounded, source-linked findings that agents can act on.

Tasks:

1. Add `android_test_run` with modes `unit`, `device`, `managed-device`, and `screenshot`. Only tasks
   discovered by the inspector or explicitly allowed in configuration may run.
2. Parse JUnit XML into test counts, failures, skipped tests, durations, class/method names, messages,
   and bounded stack traces. Preserve the original XML/HTML reports as artifacts.
3. Add `android_build_diagnose`, reusing the existing safe Gradle runner, to classify Kotlin/Java
   compiler errors, Android resource linking, manifest merge, lint, test, and configuration-cache
   failures. Unknown output must remain an unclassified raw-log artifact.
4. Normalize findings into the existing `DiagnosticFinding` structure and add stable diagnostic
   codes and source paths where the tool can prove them.
5. Ingest AGP unified reports when present, but keep this optional because the feature is officially
   experimental.
6. Gate Gradle 9.6's `--non-interactive` flag on the detected wrapper version; do not pass it to
   earlier Gradle releases.

Acceptance criteria:

- Golden tests cover passing, failing, skipped, malformed, and oversized test reports.
- Parser output is deterministic and redacted, and stack traces/output obey configured size limits.
- Execution cannot escape the bound project root or bypass Gradle task/argument allowlists.
- An unrecognized failure is reported as `UNKNOWN`, never assigned a guessed cause.

### P3 — Integrate official Compose screenshot-test artifacts

**Goal:** Complement Google's renderer and test engine with deterministic, agent-readable reports.

Tasks:

1. Detect `com.android.compose.screenshot`, the experimental enablement properties,
   `@PreviewTest` source sets, update tasks, validate tasks, references, and result reports.
2. Add allowlisted screenshot modes to `android_test_run`; updating references must remain a separate
   mutating operation that requires explicit configuration permission.
3. Add an adapter that imports official reference/actual/diff images and metadata into
   `VisualCaptureEngine`/`VisualReportGenerator` without rerendering them.
4. Generate a compact agent packet containing failed preview identity, variant, preview parameters,
   dimensions, match metrics when present, and artifact paths.
5. Document `DroidAgentVisualRule.captureCompose(render = ...)` as a low-level byte-capture API.
   Consider deprecation only after the official adapter is stable and migration coverage exists.

Acceptance criteria:

- An official sample report fixture imports deterministically on Linux and macOS.
- No custom renderer or Android Studio-private API is added.
- Reference updates cannot occur through a read-only validation call.
- Experimental status and supported plugin versions are present in generated evidence.

### P4 — Replace the universal readiness score with applicable profiles

**Goal:** Avoid penalizing libraries, KMP modules, and tooling repositories for app-only practices.

Tasks:

1. Add profiles: `ANDROID_APP`, `ANDROID_LIBRARY`, `ANDROID_KMP_LIBRARY`, `JVM_TOOLING`, and
   `MIXED_REPOSITORY`.
2. Represent every rule with `applicability`, `weight`, `evidence`, `confidence`, and official or
   project-policy source.
3. Make ProGuard/R8 and Baseline Profile rules applicable only where the project type and release
   configuration justify them.
4. Add rules for supported toolchain pairing, configuration-cache verification, dependency
   verification, AGP 9/KMP migration state, test-result availability, and API 37 test coverage.
5. Separate observed facts from recommendations in JSON and Markdown reports.
6. Version the scoring policy so a score can be reproduced after rules evolve.

Acceptance criteria:

- Auditing DroidAgentKit no longer produces Android-app-only penalties.
- The same fixture produces byte-stable output for the same policy version.
- Every deduction has file evidence or an explicit `UNKNOWN` state.
- Scores are only compared within the same profile and policy version.

### P5 — Harden agent-host interoperability and focus the MCP surface

**Goal:** Keep DroidAgentKit useful across Android Studio and other MCP hosts without duplicating
host-native capabilities.

Tasks:

1. Add transport contract tests for `initialize`, `ping`, `tools/list`, and `tools/call` over stdio
   and streamable HTTP using MCP `2025-11-25` envelopes.
2. Keep Android Studio installation HTTP-first and clearly state its lack of stdio, resources, and
   prompts support. Keep generic stdio installation for hosts that support it.
3. Make structured test, build, lint, crash, dependency, performance, readiness, and visual findings
   the documented primary value proposition.
4. Add tool annotations/read-only metadata only where the current MCP specification and target hosts
   demonstrably support them; retain server-side enforcement regardless of client hints.
5. Evaluate the official MCP Kotlin SDK in an isolated spike. Adopt it only if it preserves the
   current local-only security model, bounded HTTP behavior, wire compatibility, and acceptable
   dependency footprint.

Acceptance criteria:

- The same tool schemas and structured results are returned over both transports.
- Android Studio can connect using the documented `httpUrl` configuration and call every read-only
  tool.
- No resources/prompts feature is required for Android Studio operation.
- Client metadata never weakens server-side permission checks.

### P6 — Release quality and ongoing evidence maintenance

**Goal:** Make compatibility claims testable and keep evidence from silently becoming stale.

Tasks:

1. Add a versioned `compatibility-evidence.json` resource containing only official version ranges,
   source URLs, retrieval dates, and a schema version. Updates require review and fixtures.
2. Add a scheduled repository-maintenance workflow that reports evidence staleness; it must not add
   runtime network behavior to distributed tools.
3. Test released CLI archives on Linux, macOS, JDK 17, and JDK 21.
4. Publish checksums, SBOM, and build provenance with releases.
5. Document the supported AGP/Kotlin/Gradle/JDK matrix and distinguish `tested`, `documented`, and
   `best effort` combinations.

Acceptance criteria:

- A release cannot claim a combination unless it has both official evidence and a passing CI lane.
- Generated reports display evidence-pack version and retrieval date.
- Release artifacts are reproducible enough for checksum comparison within the documented build
  environment.

## 4. Recommended execution order

1. **Compatibility tranche:** P0 plus the compatibility evidence model from P6.
2. **Inspector tranche:** P1 with fixtures for AGP 9, Android-KMP, and API 37.
3. **Diagnostics tranche:** P2, starting with JUnit XML parsing before adding new MCP handlers.
4. **Visual tranche:** P3 using official screenshot-test fixtures.
5. **Readiness tranche:** P4 after the richer inspector evidence exists.
6. **Interop and release tranche:** P5 and the remaining P6 work.

Each tranche must finish with `./gradlew ktlintCheck test`, configuration-cache verification where
applicable, documentation updates for public behavior, and a security review against the project
boundaries.

## 5. Explicit non-goals

- No arbitrary shell execution.
- No telemetry or runtime calls to version, CVE, Maven, Android, Kotlin, or Gradle services.
- No claims that static regex inspection proves build success or Android platform compatibility.
- No Android Studio-private APIs or attempts to replace Android Studio's device agent tools.
- No custom Compose renderer.
- No dependence on experimental AGP unified reports for core test-result support.
- No unconditional upgrade to a version combination outside official documented compatibility.
