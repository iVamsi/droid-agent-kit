# Visual Regression, End-to-End (Workstream C) — Design

Date: 2026-07-02
Status: Approved, ready for `writing-plans`

## Goal

Make the "Compose Visual Regression Kit" — the README's headline feature — actually work end-to-end.
Today `DroidAgentVisualRule.captureCompose()` wraps a caller-supplied render lambda and returns it
unpersisted; the Gradle plugin's `droidAgentVisualsReport`/`droidAgentVisualsUpdateGoldens` tasks and
the CLI's `visuals` command all write hardcoded placeholder text regardless of what ran. This closes
that gap per [2026-07-01-opensource-roadmap.md](2026-07-01-opensource-roadmap.md), workstream C.

## Current state

- `visuals-core`: `PngDiffEngine` does real pixel diffing (works today). `VisualReportBuilder`
  aggregates `VisualCaseResult`s into a `VisualReport`, but nothing produces real `VisualCaseResult`s.
- `visuals-android-test`: `DroidAgentVisualRule.captureCompose<T>(name, matrix, semantics, render: () ->
  T): VisualCapture<T>` just calls `render()` and returns an in-memory wrapper — nothing is written to
  disk. The module is pure `kotlin("jvm")`, no Android/Compose dependency.
- `visuals-gradle-plugin`: `DroidAgentVisualsReportTask` and `DroidAgentVisualsUpdateGoldensTask` both
  write fixed placeholder text/markers, ignoring the extension's `matrix`/`tolerance`/
  `failOnChangedGoldens` settings entirely.
- CLI: `droidagent visuals <action>` writes one hardcoded placeholder line, regardless of `action`.

## Scope decisions from brainstorming

- **Pixel-diff only.** `VisualFindingCategory` already declares 8 categories (`pixel_diff`,
  `text_clipping`, `element_overlap`, `missing_semantics_label`, `small_touch_target`,
  `contrast_warning`, `rtl_layout_issue`, `large_font_overflow`, `unexpected_blank_screen`). This round
  only implements real detection for `PIXEL_DIFF`. The other 7 remain schema-supported, undetected —
  future work, not a regression from today (nothing detects them today either).
- **Renderer-agnostic capture, no new dependency.** droid-agent-kit does not add Paparazzi, Robolectric,
  or any Android SDK dependency to its own modules. `captureCompose`'s render contract becomes
  `() -> ByteArray` (PNG-encoded bytes) — the target project supplies real pixels via whatever renderer
  it chooses (Paparazzi is the documented recommendation, since it needs no emulator, but nothing is
  hard-wired to it). droid-agent-kit's job is capture persistence, diffing, and reporting — all pure JVM
  file I/O, matching the project's existing zero-third-party-dependency posture for these modules.
- **`DroidAgentVisualRule` learns its output location from a constructor default**, not a
  Gradle-plugin-injected system property: `DroidAgentVisualRule(outputDir: Path =
  Path.of("build/droidagentkit/visuals"))`. This matches the Gradle extension's own default for the same
  path. If a user customizes the extension's `outputDir`, they pass the same value to the rule's
  constructor — a documented single point of friction for non-default setups, in exchange for not
  building a plugin-to-test-JVM system-property wiring mechanism.
- **Goldens live in `src/test/resources/droidagentkit/goldens/`** inside the target project's module, by
  default — source-controlled, reviewable in PRs, conventional Gradle test-resource location.

## Architecture

`DroidAgentVisualRule.captureCompose` becomes capture-only: it calls the caller's `render: () ->
ByteArray`, then persists the result — it does **not** diff against goldens itself. All diff/report/
golden-update logic moves into one new object, `VisualCaptureEngine`, in `visuals-core`, reused by both
the Gradle plugin's tasks and the CLI's `visuals` command, so there is exactly one implementation of
"read captures, compare to goldens, build report" rather than two independently-drifting ones.

## `DroidAgentVisualRule` (visuals-android-test)

```kotlin
class DroidAgentVisualRule(private val outputDir: Path = Path.of("build/droidagentkit/visuals")) {
    fun captureCompose(
        name: String,
        matrix: VisualMatrix = VisualMatrix.standard(),
        semantics: List<String> = emptyList(),
        render: () -> ByteArray,
    ): VisualCapture {
        val environment = CaptureEnvironment(
            device = matrix.devices.firstOrNull() ?: "phone_412x915",
            theme = matrix.themes.firstOrNull() ?: "light",
            fontScale = matrix.fontScales.firstOrNull() ?: 1.0f,
            locale = matrix.locales.firstOrNull() ?: "en",
        )
        return VisualCaptureEngine.persistCapture(
            outputDir, name, environment, render(), semantics.joinToString("\n"),
        )
    }
}
```

`VisualCapture` drops its generic type parameter — it now represents the concrete record of what was
written to disk (case name, environment, PNG path, semantics-sidecar path), not an arbitrary
caller-returned value.

**Persistence format**, under `<outputDir>/captures/`:
- `<case>__<envKey>.png` — the raw PNG bytes.
- `<case>__<envKey>.semantics.txt` — the joined semantics dump, in its own file specifically to avoid
  needing to escape embedded newlines/tabs in a single-line manifest format.
- One line appended to `manifest.tsv`: `caseName\tdevice\ttheme\tfontScale\tlocale\tpngFile\tsemanticsFile\tcapturedAtIso`
  — all plain scalar fields, tab-separated, hand-parsed (no library), matching the project's existing
  convention of narrow hand-rolled parsers for its own emitted formats (e.g. `DroidAgentConfigLoader`).
  `envKey` is `"${device}_${theme}_${fontScale}_${locale}"`.

## `VisualCaptureEngine` (visuals-core)

```kotlin
object VisualCaptureEngine {
    fun persistCapture(outputDir: Path, caseName: String, environment: CaptureEnvironment, pngBytes: ByteArray, semanticsDump: String): VisualCapture
    fun generateReport(outputDir: Path, goldensDir: Path, tolerance: VisualTolerance): VisualReport
    fun updateGoldens(outputDir: Path, goldensDir: Path): List<Path>
}
```

- `generateReport` reads `<outputDir>/captures/manifest.tsv`. For each entry:
  - If a matching golden exists at `<goldensDir>/<case>/<envKey>.png`, runs `PngDiffEngine.compare()`
    with the given `tolerance`. A `PIXEL_DIFF` `VisualFinding` is produced when the diff exceeds
    tolerance.
  - If the golden and capture differ in dimensions, `PngDiffEngine.compare` currently throws
    (`require(...)`) — this becomes a caught case producing a `VisualFinding(PIXEL_DIFF, ERROR,
    "image dimensions changed")` instead of an uncaught exception, since a dimension change is a real
    visual regression, not a tool bug.
  - If no golden exists yet (first run for that case+environment), the `VisualCaseResult` gets status
    `PARTIAL` with a finding explaining "no golden yet — run `droidAgentVisualsUpdateGoldens`" — never
    silently skipped.
  - Cases within tolerance get status `SUCCESS`, no finding.
  - The existing `VisualReportBuilder` aggregates the resulting `VisualCaseResult`s into the final
    `VisualReport` (unchanged).
- `updateGoldens` copies every captured PNG over its golden counterpart (creating the case's golden
  directory if new), returns the list of paths it touched.

## Gradle plugin (visuals-gradle-plugin)

- `DroidAgentVisualsExtension` gains `goldensDir: DirectoryProperty`, convention
  `project.layout.projectDirectory.dir("src/test/resources/droidagentkit/goldens")`.
- `DroidAgentVisualsReportTask` gains `@InputDirectory goldensDir`, `@Input tolerance` (declared on the
  extension today but never wired into the task — a pre-existing gap this fixes), `@Input
  failOnChangedGoldens`. Calls `VisualCaptureEngine.generateReport(...)`, writes the real report as
  markdown (matching the `android_report_bundle` MCP tool's existing markdown-report style) via the
  existing `ArtifactWriter`, and throws `GradleException` when `failOnChangedGoldens` is true and the
  report status is `FAILED`.
- `DroidAgentVisualsUpdateGoldensTask` gains `@InputDirectory` (captures, via `outputDir`) and
  `@OutputDirectory goldensDir`. Calls `VisualCaptureEngine.updateGoldens(...)`.
- `failOnAccessibilityWarnings` stays declared on the extension but is explicitly a no-op this round
  (documented, not silently dropped) — there are no accessibility findings to fail on yet, per the
  pixel-diff-only scope decision above.

## CLI (`cli` module)

`droidagent visuals report --project <path> [--goldens-dir <path>] [--output-dir <path>]` and
`droidagent visuals update-goldens ...` call `VisualCaptureEngine` directly — no Gradle shell-out, since
capture already happened during the target project's own `./gradlew test` run before the CLI command is
invoked. Both flags are optional, defaulting to the same conventions the Gradle extension uses
(`build/droidagentkit/visuals`, `src/test/resources/droidagentkit/goldens`). This is new optional-flag
scope on the existing freeform `visuals` command (`CliCommandSpec`'s `visuals` entry stays
`freeformOptions = true`; the two flags are read from `command.options`, not added to a fixed schema).

## Testing

`VisualCaptureEngine` is pure filesystem logic (no Gradle, no Android) — tested with
`Files.createTempDirectory` fixtures: real small PNGs written via `ImageIO`/`BufferedImage` (matching
`VisualDiffTest`'s existing approach), a hand-written `manifest.tsv`, and assertions on the resulting
`VisualReport`. `DroidAgentVisualRule` gets a test verifying `captureCompose` writes exactly the 3
expected files with correct content. Gradle plugin task tests get extended to verify the report task
fails when `failOnChangedGoldens=true` and a real diff exceeds tolerance, and passes through unchanged
when within tolerance. No mocks anywhere, per repo convention.

## File map

| File | Change |
|---|---|
| `visuals-core/.../Models.kt` | `VisualCapture` loses its generic type parameter |
| `visuals-core/.../VisualCaptureEngine.kt` | New |
| `visuals-android-test/.../DroidAgentVisualRule.kt` | `captureCompose` signature change, delegates persistence to `VisualCaptureEngine` |
| `visuals-gradle-plugin/.../DroidAgentVisualsPlugin.kt` | `goldensDir` extension property; both tasks call `VisualCaptureEngine`, throw on `failOnChangedGoldens` |
| `cli/.../DroidAgentMain.kt` | `visuals()` calls `VisualCaptureEngine` directly instead of writing a placeholder |
| `visuals-core/src/test/.../VisualCaptureEngineTest.kt` | New |
| `visuals-android-test/src/test/.../DroidAgentVisualRuleTest.kt` | New (module currently has no test source set) |
| `visuals-gradle-plugin/src/test/...` | Extended (existing stub tests) |
| `docs/add-compose-visual-reports.md` | Updated to reflect the real `() -> ByteArray` contract and `goldensDir` |

## Constraints preserved

- No new third-party dependencies in `visuals-core`, `visuals-android-test`, or `visuals-gradle-plugin` —
  pure JVM (`java.awt.image`, `javax.imageio`), matching the project's zero-dependency-by-default posture
  for these modules (the one existing exception, `kotlinx-serialization-json`, is scoped to `mcp-server`
  only and unrelated to this workstream).
- No network calls.
- Real rendering stays the target project's responsibility — droid-agent-kit never depends on Paparazzi,
  Robolectric, or AGP.
- Accessibility-category findings (`text_clipping`, `contrast_warning`, etc.) remain undetected this
  round — documented as future scope, not silently dropped from the schema.
