# Android MCP Landscape — Competitor Research (2026)

> Research date: 2026-07-22. Goal: map the existing Android / mobile MCP server
> ecosystem so DroidAgentKit can out-feature competitors. Every claim cites a
> primary source URL. Star counts fetched from the GitHub API on 2026-07-22.

DroidAgentKit's **current** MCP tool surface (14 tools), per
`mcp-server/src/test/kotlin/com/droidagentkit/mcp/McpDispatcherTest.kt`:
`android_project_inspect`, `android_gradle_run`, `android_devices_list`,
`android_app_install`, `android_app_launch`, `android_logcat_capture`,
`android_screen_snapshot`, `android_report_bundle`, `android_lint_run`,
`android_crash_triage`, `android_dependency_check`, `android_build_performance`,
`android_test_run`, `android_build_diagnose`.

## 1. Executive summary

The Android MCP field in mid-2026 is **crowded but shallow**. There are ~20+
community servers, almost all TypeScript/Python, almost all thin ADB wrappers
doing the same 10–15 things (screenshot, tap, swipe, logcat, install, list
packages). Differentiation is converging on three axes:

1. **Breadth of ADB surface** — `fullread/deepadb` (204 tools, 45 modules,
   TypeScript, 13★) and `us-all/android-mcp-server` (76 tools, 7★) expose
   dumpsys/getprop/bugreport/baseband that nobody else does.
   https://github.com/fullread/deepadb · https://github.com/us-all/android-mcp-server
2. **Compose / runtime introspection** — `yschimke/compose-ai-tools` (99★,
   Kotlin, headless `@Preview` rendering + semantics diff) and
   `composeproof.dev` (51 tools, embedded in-app SDK for ViewModel /
   navigation / coroutine / recomposition state) own the "AI eyes on Compose"
   niche. https://github.com/yschimke/compose-ai-tools · https://docs.composeproof.dev/
3. **Cross-platform / agent runtime** — `mobile-next/mobile-mcp` (5,537★) and
   `ghost-in-the-droid/android-agent` (257★, 62 tools, iOS + on-device
   inference) treat the phone as an agent body with skills + batched flows.
   https://github.com/mobile-next/mobile-mcp · https://github.com/ghost-in-the-droid/android-agent

**DroidAgentKit's wedge**: it is the only Kotlin/JVM, local-only,
allowlist+redaction-enforced, *project-aware* (parses Gradle/manifest) server.
Competitors are device-only and language-agnostic. The opportunity is to be
the **only server that pairs safe project/gradle/lint/build analysis with deep
device + Compose + Perfetto introspection** — i.e. own the "Android dev agent"
vertical end to end, not just the "ADB remote control" horizontal.

## 2. Existing Android MCP servers on GitHub

Stars via GitHub API, 2026-07-22.

| Repo | Stars | Lang | Tools | Transport | Notable gaps |
|---|---|---|---|---|---|
| `mobile-next/mobile-mcp` https://github.com/mobile-next/mobile-mcp | 5,537 | TS | platform-agnostic accessibility-tree automation; taps/swipes/app lifecycle; `mobilewright` sister project for deterministic tests | stdio | No gradle/lint/build, no Compose introspection, no Perfetto; iOS real-device needs go-ios+WDA+tunnel |
| `fullread/deepadb` https://github.com/fullread/deepadb | 13 | TS | **204 tools / 45 modules** — UI, dumpsys, network-capture, baseband/modem, AT cmds, RIL intercept, OTA diff, SELinux audit, thermal/power, visual regression, CI, cloud farm, plugins | stdio + HTTP/SSE + WebSocket + GraphQL | No project/gradle analysis, no Compose recomposition, no Room query, security is "defense-in-depth" but not allowlisted-by-task |
| `us-all/android-mcp-server` https://github.com/us-all/android-mcp-server | 7 | TS | **76 tools** — logcat/dumpsys/getprop/processes/package internals/intents/port-fwd; 5 MCP Prompts (crash-investigation, memory-leak-detection, permission-audit, app-startup-profile, ui-element-locator); `device-health` + `analyze-app` aggregations | stdio | 2-tier security (write+shell gates); no Compose, no Perfetto, no gradle, no network proxy capture |
| `ghost-in-the-droid/android-agent` https://github.com/ghost-in-the-droid/android-agent | 257 | Py | **62 tools** — screen/touch/apps/context/browser/device/skills/batch/diagnostics; `run_flow` batched primitive; `explore_app` BFS state graph; on-device inference (llama.cpp/MediaPipe/MLX); iOS via Appium+WDA | stdio | Agent-runtime focus, not dev-tooling; no gradle/lint/build/Perfetto; crash triage is shallow (`logcat -b crash`) |
| `appium/appium-mcp` https://github.com/appium/appium-mcp | 433 | TS | Appium 2.x wrapper; NL element finding via vision models; session mgmt; cross-platform iOS+Android | stdio | Heavyweight (Appium server+drivers); no dev/build tooling; no introspection |
| `antarikshc/perfetto-mcp` https://github.com/antarikshc/perfetto-mcp | 206 | Py | NL→PerfettoSQL: `detect_anrs`, `anr_root_cause_analyzer`, `cpu_utilization_profiler`, `main_thread_hotspot_slices`, `detect_jank_frames`, `thread_contention_analyzer`, `binder_transaction_profiler`, memory leak | stdio | **Trace-file only** — no live device capture; no gradle/build; single best-in-class perf tool |
| `yschimke/compose-ai-tools` https://github.com/yschimke/compose-ai-tools | 99 | Kotlin | `@Preview`→PNG render (Robolectric/Compose Desktop), semantics diff (`diff_semantics`), daemon warm renderer, MCP auto-register to Claude/Codex/Antigravity | stdio | Compose-preview only; no device, no gradle run, no logcat; Kotlin (closest peer to DroidAgentKit) |
| `martingeidobler/android-mcp-server` https://github.com/martingeidobler/android-mcp-server | 54 | TS | 25 tools — screenshot, UI tree, tap/tap_and_wait, logcat filter, install, start_emulator, list_avds; persistent ADB shell; device-info cache | stdio | Published to official registry as `io.github.martingeidobler/android`; no gradle/lint/perf/Compose |
| `kaeawc/auto-mobile` https://kaeawc.github.io/auto-mobile/design-docs/mcp/storage/snapshots/ | 36 | TS | `deviceSnapshot` — VM snapshots (emulator), ADB `backup`/`restore` snapshots (all devices), iOS app-container backups; auto-named, host-stored | stdio | Snapshot/restore specialty; narrow scope |
| `tanbro/uiautomator2-mcp-server` https://github.com/tanbro/uiautomator2-mcp-server | 32 | Py | 70+ tools, XPath element filtering, tool-tag filtering, Scrcpy mirroring, built-in AI UI-test framework | stdio | uiautomator2-server APK on device required; no dev tooling |
| `Nam0101/android-mcp-toolkit` https://github.com/Nam0101/android-mcp-toolkit | 19 | JS | SVG→VectorDrawable converter, `manage-logcat` (read/crash/anr/clear), translation-length estimator | stdio | Asset-conversion + logcat; no device control, no build |
| `ulcica/android-mcp` https://github.com/ulcica/android-mcp | 10 | Kotlin | device mgmt, enhanced Layout-Inspector UI hierarchy, debug view attrs, element search, coroutines+caching | stdio | Kotlin peer; layout-inspector angle is unique; no gradle/lint/build |
| `jduartedj/android-mcp-server` https://github.com/jduartedj/android-mcp-server | 6 | TS | 22 tools incl. Scrcpy H.264 streaming, auto-download ADB/Scrcpy | stdio | Streaming angle; no dev tooling |
| `MrNewDelhi/adb-mcp` https://github.com/MrNewDelhi/adb-mcp | 1 | TS | Broad typed ADB: intents (deep links), permissions, port-fwd, content-provider CLI, `adb_backup_restore`, emulator console, bugreport bundle, raw `adb_command` escape hatch | stdio | Most complete "raw ADB" surface; no gradle/lint/Compose |
| `cyclops/android-mcp` (cyclops-top) https://github.com/cyclops-top/android-mcp | 1 | Kotlin | **In-app embedded MCP server** library; Room tools `list_databases`/`inspect_schema`/`execute_sql` (Room 2 & 3 adapters) | stdio (in-process) | Library, not standalone; Room-inspection angle is unique & worth copying |
| `csyjyy/android_proxy_mcp` https://github.com/csyjyy/android_proxy_mcp | 0 | Py | mitmproxy capture: `traffic_list`/`traffic_search`/`traffic_read_body`, Frida RPC decrypt/encrypt, autonomous test sequences | stdio | Network-proxy + Frida niche; no device/build tooling |
| `hortusys/android-sqlite-inspector` https://github.com/hortusys/android-sqlite-inspector | 0 | TS | `list_databases`/`list_tables`/`describe_table`/`query`/`execute` via `adb run-as` pull + better-sqlite3 | stdio | SQLite/Room inspection; narrow |
| `victordsgamorim/logcat_mcp` https://github.com/victordsgamorim/logcat_mcp | 1 | Py | `adb_devices`, `logcat_dump` with package/PID/tag/level filters; zero deps | stdio (JSON-RPC) | Logcat-only |
| `bzcasper/android-adb-mcp` https://github.com/bzcasper/android-adb-mcp | 1 | JS | 11 tools, allowlisted shell, push/pull | stdio | Minimal |
| `moallemi/android-mcp-server` https://github.com/moallemi/android-mcp-server | 1 | TS | screenshot, UI find/tap, install, `adb_stream` (logcat/top), file push/pull, multi-device | stdio | Generic |
| `JanJetze/android-emulator-mcp` https://github.com/JanJetze/android-emulator-mcp | 0 | Py | AVD lifecycle: list/create/delete/start/stop, system images, sensor get/set | stdio | Emulator-only; companion to `JanJetze/adb-mcp` |
| `vs4vijay/espresso-mcp` https://github.com/vs4vijay/espresso-mcp | 0 | Py | AVD list/start/kill, dump_ui_hierarchy, open_uri (deep link), install/start/stop/uninstall, screenshot/record, tap/swipe/type | stdio | Espresso-flavored device control |
| `manuelsiuro/mcp-android-server-python` https://github.com/manuelsiuro/mcp-android-server-python | 0 | Py | uiautomator2 automation + **scenario recording → Espresso test codegen** | stdio | Record→Espresso codegen angle is notable |
| `smutti/mcp_android` https://github.com/smutti/mcp_android | 2 | Py | Multi-device, per-device session caching + UI locks; stdio + streamable-http + sse | multi | Concurrency-angle |

**Pattern**: ~18 of 24 servers overlap on screenshot/tap/swipe/logcat/install.
Real differentiation lives in deepadb (breadth), us-all (diagnostic prompts),
compose-ai-tools + ComposeProof (Compose), perfetto-mcp (perf), mobile-mcp
(cross-platform), ghost-in-the-droid (agent runtime), cyclops/android-mcp
(Room), android_proxy_mcp (network), auto-mobile (snapshots).

## 3. Official MCP server registry entries

The official registry at `registry.modelcontextprotocol.io` is the authoritative
index (backed by Anthropic, GitHub, Microsoft, PulseMCP).
https://github.com/modelcontextprotocol/registry · https://modelcontextprotocol.io/registry/about.md

A `?search=android` query returns **17 entries** (2026-07-22). Android-dev-relevant ones:

| Registry name | Description | Source |
|---|---|---|
| `com.googleapis.androidmanagement/mcp` | **Google official** remote enterprise Android device/policy/app management | https://developers.google.com/android/management/reference/mcp |
| `io.github.us-all/android` | us-all's 76-tool diagnostic/forensic server (multiple published versions) | https://github.com/us-all/android-mcp-server |
| `io.github.martingeidobler/android` | martingeidobler's 25-tool emulator control | https://github.com/martingeidobler/android-mcp-server |
| `io.github.ghost-in-the-droid/android-agent` | 62-tool agent runtime, iOS + on-device inference | https://github.com/ghost-in-the-droid/android-agent |
| `io.github.fullread/deepadb` | 204-tool ADB server (4 published versions, latest 204 tools/45 modules) | https://github.com/fullread/deepadb |
| `io.github.ako2345/android-security-analyzer` | **Static security analysis of Android source** | https://github.com/ako2345/android-security-analyzer |
| `io.github.frndchagas/expo-android` | Android emulator automation via ADB | https://github.com/frndchagas/expo-android |
| `io.github.pedro-rivas/android-puppeteer-mcp` | UI interaction / screenshots / device control | https://github.com/pedro-rivas/android-puppeteer-mcp |
| `io.github.TecniForgeMaryam/androidapi-mcp` | SMS/WhatsApp/OTP/contacts/device control | (registry entry) |
| `com.clauxel.aistudioandroidreleasegate/*` | AI Studio Android release-gate (CI-flavored) | (registry entry) |
| `com.clauxel.androidcliagentgate/*` | Android CLI agent build-gate | (registry entry) |

**Google's official `com.googleapis.androidmanagement/mcp`** is remote HTTP at
`https://androidmanagement.googleapis.com/mcp`, OAuth2, Streamable HTTP. Tools:
`list_enterprises`, `get_device`, `list_devices`, `get_policy`, `list_policies`,
`get_application`, `list_web_apps`, etc. — **enterprise MDM only, not dev
tooling**, but it validates that Google ships Android MCP and sets the
authentication/transport bar.
https://developers.google.com/android/management/use-android-management-mcp

**PulseMCP** (`pulsemcp.com/servers`, 22,288 servers) is the de-facto discovery
layer; it temporarily hosts `server.json` for community repos until maintainers
publish to the official registry. https://www.pulsemcp.com/servers/

**Google I/O 2026 signal**: Android Studio ships pre-bundled agent skills (XML→Compose,
edge-to-edge, Navigation 3), and the **AppFunctions platform API (private preview)
lets your app act as an on-device MCP server** so an agent can call into app
capabilities — a first-party "in-app MCP" direction that overlaps cyclops/android-mcp
and ComposeProof's embedded agent. https://doveletter.dev/release-notes/google-io-2026-android

## 4. Ideal Android dev-agent MCP — capability checklist

Mapped to who currently delivers each (✅ = real implementation, ⚠ = partial).

| Capability | Best current provider | DroidAgentKit has it? |
|---|---|---|
| Device mgmt (list/info/multi-device) | martingeidobler, deepadb | ✅ `android_devices_list` |
| Install / launch / uninstall | mobile-mcp, deepadb | ✅ `android_app_install`/`android_app_launch` |
| Logcat capture + filter + crash buffer | us-all, logcat_mcp, Nam0101 | ✅ `android_logcat_capture`/`android_crash_triage` |
| Screenshot (base64/save) | martingeidobler, mobile-mcp | ✅ `android_screen_snapshot` |
| Gradle run (allowlisted tasks) | **none** — DroidAgentKit unique | ✅ `android_gradle_run` |
| Lint / ktlint / detekt SARIF | **none** — DroidAgentKit unique | ✅ `android_lint_run` |
| Static project inspect (Gradle+manifest parse) | **none** — DroidAgentKit unique | ✅ `android_project_inspect` |
| Dependency analysis / vuln check | **none** — DroidAgentKit unique | ✅ `android_dependency_check` |
| Build performance timing | **none** — DroidAgentKit unique | ✅ `android_build_performance` |
| Build diagnose (failure→fix) | **none** — DroidAgentKit unique | ✅ `android_build_diagnose` |
| Test run (gradle connectedCheck) | **none** — DroidAgentKit unique | ✅ `android_test_run` |
| Report bundle (aggregated) | **none** — DroidAgentKit unique | ✅ `android_report_bundle` |
| UI tree / accessibility / tap-by-element | mobile-mcp, deepadb, ghost | ❌ missing |
| CPU/memory/battery profiling (live) | us-all (`dumpsys`), deepadb | ❌ missing |
| Perfetto trace analysis (ANR/jank/lock) | **perfetto-mcp** (file-only) | ❌ missing |
| Layout inspector (view attrs) | ulcica/android-mcp | ❌ missing |
| Room/SQLite DB inspection | cyclops/android-mcp, hortusys | ❌ missing |
| Network proxy capture (mitm/Charles) | android_proxy_mcp, Charles MCP | ❌ missing |
| Accessibility tree (a11y audit) | deepadb, mobile-mcp | ❌ missing |
| Monkey / UIAutomator orchestration | tanbro, manuelsiuro (→Espresso codegen) | ❌ missing |
| Espresso orchestration / codegen | manuelsiuro, vs4vijay/espresso-mcp | ❌ missing |
| ANR triage (traces.txt + AM logs) | Nam0101 (`manage-logcat anr`), perfetto-mcp | ⚠ via crash_triage only |
| Compose recomposition counts | **composeproof**, compose-ai-tools | ❌ missing |
| Compose `@Preview` headless render + diff | **compose-ai-tools** (Kotlin) | ❌ missing |
| Screenshot diff / visual regression | deepadb (visual-regression module), composeproof | ⚠ visuals-core module exists in repo, not exposed as MCP |
| Deep link invocation | MrNewDelhi/adb-mcp, Amm-ar, vs4vijay | ❌ missing |
| Permissions mgmt (grant/revoke/audit) | us-all (permission-audit prompt), deepadb | ❌ missing |
| File push/pull | moallemi, bzcasper, deepadb | ❌ missing |
| Backup / restore / snapshots | **auto-mobile** (VM+ADB+iOS), deepadb | ❌ missing |
| Emulator lifecycle (AVD create/boot/snapshot) | JanJetze, Amm-ar, martingeidobler | ❌ missing |
| In-app runtime introspection (VM/nav/coroutines/DataStore) | **composeproof** embedded SDK, cyclops | ❌ missing |
| MCP Prompts (workflow templates) | us-all (5 prompts), deepadb (4) | ❌ missing |
| Batched/compound tool calls | ghost (`run_flow`), martingeidobler (`tap_and_wait`) | ❌ missing |
| Multi-transport (stdio + HTTP/SSE) | deepadb, smutti | ⚠ stdio + HTTP exist, not SSE/streamable-http |
| Security: allowlist + redaction | **DroidAgentKit unique** (task-allowlist + Redactor) | ✅ core differentiator |

## 5. iOS / cross-platform patterns worth adapting

| Server | Stars | Pattern to steal |
|---|---|---|
| `getsentry/XcodeBuildMCP` https://github.com/getsentry/XcodeBuildMCP | 6,109 | **82 tools / 10 workflow groups**, CLI = MCP (same surface for terminal + agent), stateful vs stateless tool split, dynamic tool loading (only enabled workflows exposed → token-frugal), `build_sim`/`build-and-run`/`test`/`boot`/`install`/`launch-app-with-logs`, LLDB debugging, gesture simulation, accessibility inspection. **Closest architectural role model for DroidAgentKit.** https://github.com/getsentry/XcodeBuildMCP/blob/main/docs/TOOLS-CLI.md |
| Apple `xcrun mcpbridge` (Xcode 26.3+) https://blakecrosley.com/blog/xcode-mcp-claude-code | — | First-party MCP bridging into a *running* IDE via XPC for live diagnostics, resolved symbols, SwiftUI previews. Lesson: a live-IDE bridge (Android Studio / AGP) is a defensible moat Google may fill via AppFunctions. |
| `nzrsky/simctl-mcp-server` / `lwsinclair/simctl-mcp-server` https://github.com/nzrsky/simctl-mcp-server | — | Clean simulator lifecycle + `push_notification` + `privacy_control` + `set_location` + `status_bar_override` + `ui_appearance`. Direct Android equivalents (mock-location, status-bar, dark-mode) are easy wins. |
| `mobile-next/mobilewright` https://github.com/mobile-next/mobilewright | — | "Playwright for mobile": auto-waiting, `getByRole`/`expect().toBeVisible()` retry assertions, deterministic test graduation from agent exploration. Pattern: ship a test-graduation path, not just exploration. |
| `appium/appium-mcp` https://github.com/appium/appium-mcp | 433 | NL element finding via vision models; `select_platform`/`select_device` gating tools. Pattern: tool-filtering to cut hallucination. |

## 6. Top 10 must-have capabilities DroidAgentKit is likely missing (ranked by impact)

Ranked by (agent value × frequency-of-use × competitive gap). Each entry names
the competitor that proves demand, and the DroidAgentKit-shaped implementation.

### 1. Perfetto trace analysis (ANR / jank / CPU / lock / memory)
`antarikshc/perfetto-mcp` (206★) proves agents want NL→PerfettoSQL: `detect_anrs`,
`anr_root_cause_analyzer`, `main_thread_hotspot_slices`, `detect_jank_frames`,
`thread_contention_analyzer`, `binder_transaction_profiler`. It is **file-only** —
DroidAgentKit can win by adding **live capture** (`adb shell perfetto -o trace.pb
...` / `atrace`) then reusing its SQL. This is the single highest-impact gap: it
turns `android_crash_triage` from "logcat grep" into "root-caused ANR with ranked
causes." https://github.com/antarikshc/perfetto-mcp

### 2. Compose recomposition + `@Preview` headless render & semantics diff
`yschimke/compose-ai-tools` (99★, Kotlin) renders `@Preview`→PNG via
Robolectric/Compose-Desktop and diffs **semantics trees** (`diff_semantics`) —
"the aria-snapshot story for Compose." ComposeProof (51 tools) adds
`track_recompositions`, `inspect_viewmodel_state`, `inspect_navigation_graph`,
`inspect_coroutine_state`. DroidAgentKit already ships `visuals-core` (PngDiff)
and a Compose visual-regression Gradle plugin — **expose them as MCP** and add
recomposition counts + semantics diff. This is the natural Kotlin-native
extension and the strongest moat (no TS server can do this cleanly).
https://github.com/yschimke/compose-ai-tools · https://docs.composeproof.dev

### 3. Structured UI tree + tap-by-element + compound actions
Every serious competitor (mobile-mcp, deepadb, ghost, martingeidobler) has
`get_ui_tree` + `tap_element` by resource-id/text/content-desc, plus compound
calls like `tap_and_wait` (tap + settle + tree in one round trip) and ghost's
`run_flow` (N actions → 1 call). DroidAgentKit's `android_screen_snapshot` is
pixel-only — no accessibility tree, no element-level interaction. Add
`android_ui_tree` + `android_tap_element` + a `android_run_flow` batch primitive.
Round-trip reduction is the #1 token-cost lever for agents.
https://github.com/martingeidobler/android-mcp-server · https://github.com/ghost-in-the-droid/android-agent

### 4. Room / SQLite inspection
`cyclops/android-mcp` (Kotlin, in-app) exposes `list_databases`/`inspect_schema`/
`execute_sql` with Room 2 & 3 adapters; `hortusys/android-sqlite-inspector` does
it externally via `adb run-as` pull + better-sqlite3. DroidAgentKit can do the
external `run-as` route (no in-app SDK needed) gated by the existing allowlist.
Huge for debugging local-first apps. https://github.com/cyclops-top/android-mcp ·
https://github.com/hortusys/android-sqlite-inspector

### 5. Network proxy capture (mitmproxy / Charles)
`csyjyy/android_proxy_mcp` wraps mitmproxy: `traffic_list`/`traffic_search`/
`traffic_read_body` + Frida RPC for native crypto. A Charles MCP also exists
(118★). DroidAgentKit can orchestrate `mitmdump` + `adb shell settings put global
http_proxy` + cert install, returning filtered JSON. Pairs naturally with
`android_lint_run` for "API integration broke" debugging.
https://github.com/csyjyy/android_proxy_mcp

### 6. MCP Prompts (workflow templates) + aggregations
`us-all/android-mcp-server` ships **5 MCP Prompts** (`crash-investigation`,
`memory-leak-detection`, `permission-audit`, `app-startup-profile`,
`ui-element-locator`) plus `device-health`/`analyze-app` aggregations; deepadb
has 4 prompts. Prompts are first-class MCP — DroidAgentKit already has the
underlying tools (`android_crash_triage`, `android_dependency_check`,
`android_build_performance`) but exposes **zero prompts**. Wrap them as named
prompts to cut agent planning overhead. https://github.com/us-all/android-mcp-server

### 7. Emulator lifecycle + snapshots / backup-restore
`JanJetze/android-emulator-mcp` (AVD create/delete/start/stop, system images,
sensors) + `kaeawc/auto-mobile` (`deviceSnapshot`: emulator VM snapshots, ADB
`backup`/`restore`, iOS container backups, host-stored, auto-named). DroidAgentKit
has `android_devices_list` but no AVD control, no snapshot/restore. Deterministic
test fixtures need this. https://github.com/JanJetze/android-emulator-mcp ·
https://kaeawc.github.io/auto-mobile/design-docs/mcp/storage/snapshots/

### 8. Deep link / intent invocation + permissions mgmt
`MrNewDelhi/adb-mcp` has the most complete intent surface (`adb_intent` with
action/data/package/extras/flags/categories), runtime-permission control, and
content-provider CLI; `vs4vijay/espresso-mcp` has `open_uri`. us-all has a
`permission-audit` prompt. DroidAgentKit lacks deep-link launch and permission
grant/revoke/audit — both are trivial over the existing allowlisted ProcessRunner
and high-frequency for test entry. https://github.com/MrNewDelhi/adb-mcp

### 9. File push/pull + bugreport / dumpsys diagnostics
`moallemi`, `bzcasper`, `deepadb` all do push/pull; `us-all` + `deepadb` wrap
`dumpsys` (mem/gfx/cpu), `getprop`, `bugreport`. DroidAgentKit's
`android_report_bundle` aggregates but doesn't expose raw `adb pull`/`push` or
`dumpsys` slices. Add `android_file_pull`/`android_file_push` and a
`android_dumpsys` (mem/gfx/cpu) tool — cheap, high reuse.
https://github.com/moallemi/android-mcp-server · https://github.com/us-all/android-mcp-server

### 10. Dynamic tool loading + multi-transport (streamable-http / SSE)
`getsentry/XcodeBuildMCP` (6,109★) exposes only enabled workflow groups → fewer
tokens, less hallucination; `deepadb` ships stdio + HTTP/SSE + WebSocket. DroidAgentKit
has stdio + a basic HTTP server but no **streamable-http/SSE** and no
**per-skill tool filtering**. As the tool count grows past ~25, dynamic loading
becomes essential for agent context budgets. https://github.com/getsentry/XcodeBuildMCP ·
https://github.com/fullread/deepadb

---

### Honorable mentions (lower impact / narrower)
- **Espresso codegen from recorded scenarios** (manuelsiuro) — niche but sticky.
- **In-app embedded MCP** (cyclops, ComposeProof) — overlaps Google's AppFunctions
  preview; watch, don't build yet.
- **OCR / annotated screenshots** (ghost `ocr_screen`, `screenshot_annotated`) —
  useful when accessibility tree is empty (Canvas/WebView).
- **Static security analysis** (ako2345/android-security-analyzer, 2★) — pairs
  with `android_dependency_check`.

### Recommended sequencing
1. Ship #3 (UI tree + tap-element + run_flow) and #8 (deep link + permissions) —
   lowest effort, biggest "feels complete" jump, all over existing ProcessRunner.
2. Ship #6 (MCP Prompts wrapping existing tools) — near-zero new code, high agent
   UX win.
3. Ship #1 (Perfetto) and #2 (Compose recomposition + expose visuals-core) —
   the durable moat; no TS competitor can match Kotlin-native Compose introspection.
4. Ship #4 (Room), #5 (network proxy), #7 (emulator/snapshots), #9 (file/dumpsys),
   #10 (dynamic loading + SSE) as the long tail.

### Sources (primary)
- GitHub repos cited inline above (star counts via api.github.com, 2026-07-22).
- Official MCP registry: https://registry.modelcontextprotocol.io/v0/servers?search=android
- MCP Registry docs: https://modelcontextprotocol.io/registry/about.md
- Google Android Management MCP: https://developers.google.com/android/management/reference/mcp
- Google I/O 2026 Android: https://doveletter.dev/release-notes/google-io-2026-android
- PulseMCP directory: https://www.pulsemcp.com/servers/
- XcodeBuildMCP tools: https://github.com/getsentry/XcodeBuildMCP/blob/main/docs/TOOLS-CLI.md
- Xcode MCP article: https://blakecrosley.com/blog/xcode-mcp-claude-code


