# Android/Mobile MCP Server Landscape — August 2026

Research snapshot for DroidAgentKit positioning. All claims cite the owning primary source
(GitHub repo, official docs, npm API, or the MCP registry). Download counts cover
2026-07-08 → 2026-08-06 (npm last-month API). Star counts are as observed on 2026-08-08.

## Summary

The field splits into three camps, and DroidAgentKit sits alone in a fourth:

1. **Device/UI automation servers** — mobile-mcp (the category leader, 5.8k stars,
   ~87k npm downloads/month), DroidMind, the many adb-mcp variants, Ghost in the Droid.
   These wrap adb/uiautomator to tap, swipe, screenshot, and read UI trees. Almost none
   touch Gradle, and almost none have a real security model beyond "USB debugging was
   authorized."
2. **Test-framework MCP frontends** — Maestro MCP (backed by the 15.3k-star Maestro
   framework) and appium-mcp (~35k npm downloads/month). Their moat is test authoring:
   the agent explores the app, then emits a repeatable YAML flow or Java/TestNG test.
3. **Official platform surfaces** — Android Studio's Agent Mode is now an MCP *client*
   (HTTP-only, tools-only); the Firebase MCP server covers backend/Crashlytics; Google
   ships Gemini CLI extensions; JetBrains AI Assistant gained MCP across IDEs in 2026.1.
   Google has **no official adb/Gradle MCP server** — the platform vendors provide the
   client sockets and leave the local-toolchain server space open.
4. **Developer-workflow servers (DroidAgentKit's camp)** — servers that treat the *project*
   (Gradle, lint, crash triage, profiling, storage, network) as the object, not just the
   device. The only direct overlaps found are tiny: `@asjackson/androidbuild-mcp`
   (53 tools incl. Gradle, 0 stars) and `@us-all/android-mcp` (76 diagnostic tools,
   7 stars, explicitly *no* Gradle).

**Bottom line:** nobody else combines project intelligence + build tooling + device
control + profiling + storage/network inspection under a capability-gated security
model. DroidAgentKit's biggest competitive risks are (a) mobile-mcp's distribution lead
and iOS support, (b) Maestro/Appium owning the "generate a durable test" outcome, and
(c) its own discoverability (921 npm downloads/month; invisible to an "android" search
on the MCP registry because the listing name doesn't contain the word).

---

## Per-competitor findings

### mobile-next/mobile-mcp — the adoption leader

- **What:** Unified iOS + Android automation for LLM agents across simulators, emulators,
  and real devices; "accessibility-first" — drives the native UI tree and falls back to
  coordinate/screenshot interaction. ([github.com/mobile-next/mobile-mcp](https://github.com/mobile-next/mobile-mcp))
- **Install:** `npx @mobilenext/mobile-mcp@latest`; documented configs for Claude Code,
  Cursor, Windsurf, Copilot, Gemini, Cline. ([repo](https://github.com/mobile-next/mobile-mcp))
- **Transport:** stdio default; SSE server mode via `--listen` with optional Bearer token
  (`MOBILEMCP_AUTH`). ([repo](https://github.com/mobile-next/mobile-mcp))
- **Tools:** device list, app lifecycle (launch/terminate/install/uninstall), screenshots,
  element enumeration, tap/swipe/long-press/gestures, typing, hardware buttons, URL open,
  screen recording, device crash logs. **No Gradle/build, lint, profiling, storage, or
  network tools.** ([repo](https://github.com/mobile-next/mobile-mcp))
- **iOS:** simulators (Xcode) and real devices (go-ios + WebDriverAgent). ([repo](https://github.com/mobile-next/mobile-mcp))
- **Security:** none beyond optional SSE bearer token; has telemetry (opt-out via env var). ([repo](https://github.com/mobile-next/mobile-mcp))
- **Adoption:** 5.8k stars / 492 forks ([repo](https://github.com/mobile-next/mobile-mcp));
  **87,424 npm downloads last month** ([api.npmjs.org](https://api.npmjs.org/downloads/point/last-month/@mobilenext/mobile-mcp)).
- **Unique:** single cross-platform API; part of an ecosystem (mobilewright deterministic
  tests, mobilecli). ([repo](https://github.com/mobile-next/mobile-mcp))

### hyperb1iss/droidmind

- **What:** AI-assistant ↔ Android bridge over adb: device management, full file-system
  ops (browse/read/write/push/pull/delete), app install/uninstall, UI automation
  (tap/swipe/type), shell execution, logs and crash reports. ([github.com/hyperb1iss/droidmind](https://github.com/hyperb1iss/droidmind))
- **Install:** `uvx --from git+https://github.com/hyperb1iss/droidmind droidmind --transport stdio`;
  Python 3.13 / uv from source; Docker. ([repo](https://github.com/hyperb1iss/droidmind))
- **Transport:** stdio and SSE. ([repo](https://github.com/hyperb1iss/droidmind))
- **Security:** the closest peer to DroidAgentKit's model — "command validation, risk
  assessment, and sanitization"; high-risk operations flagged, critical ones blocked by
  default; protected path operations; comprehensive logging. Still shell-command-centric
  rather than capability-granting. ([repo](https://github.com/hyperb1iss/droidmind))
- **Adoption:** 425 stars / 57 forks. ([repo](https://github.com/hyperb1iss/droidmind))

### adb-mcp variants (long tail)

A crowded, mostly thin space; representative verified entries:

- **minhalvp/android-mcp-server** (Python, 796 stars — the most-starred pure-adb one):
  five tools — screenshot, UI layout with clickable elements, package list, **arbitrary
  adb command execution**, activity intents. No allowlists or confirmation model.
  Install by cloning + `uv`. ([github.com/minhalvp/android-mcp-server](https://github.com/minhalvp/android-mcp-server))
- **srmorete/adb-mcp** (TypeScript, 57 stars): nine adb tools; **archived**, superseded by
  `mobile-device-mcp`; notable for having shipped Smithery one-command install
  (`npx -y @smithery/cli install @srmorete/adb-mcp`). ([github.com/srmorete/adb-mcp](https://github.com/srmorete/adb-mcp))
- **us-all/android-mcp-server** (`@us-all/android-mcp`, TypeScript, 7 stars): 76 tools —
  the deepest *diagnostic* surface in the adb camp (logcat/crash extraction, bugreports,
  memory/graphics/CPU diagnostics, emulator AVD + snapshots, battery/network/settings).
  **Read-only by default** with `ANDROID_MCP_ALLOW_WRITE` / `ANDROID_MCP_ALLOW_SHELL`
  gates; stdio plus Streamable HTTP with Bearer auth; ships 5 MCP prompts (crash
  investigation, memory-leak detection, permission audit, startup profiling). Explicitly
  no Gradle/build integration. ([github.com/us-all/android-mcp-server](https://github.com/us-all/android-mcp-server))
- **dev-jackson/androidbuild-mcp** (`@asjackson/androidbuild-mcp`, TypeScript, 0 stars):
  the only found competitor with **Gradle build tools** — build with readable error
  reporting, unit/instrumented tests, lint, coverage, plus emulator lifecycle, adb
  deploy, logcat, UI automation (53 tools, npx install, stdio). No security model.
  Early but listed on the official MCP registry. ([github.com/dev-jackson/androidbuild-mcp](https://github.com/dev-jackson/androidbuild-mcp))
- **ghost-in-the-droid/android-agent** (Python, 298 stars): 62 tools driving real Android
  *and* iOS phones (adb / Appium+WDA over Tailscale); OCR + on-device inference
  (llama.cpp/MediaPipe/MLX), multi-device farms with job queues, reusable YAML "skills"
  that replay at zero LLM cost; `uvx ghost-in-the-droid up` with a `doctor` preflight
  command and a local dashboard. ([github.com/ghost-in-the-droid/android-agent](https://github.com/ghost-in-the-droid/android-agent))
- Others sighted but not deeply verified: richard0913/adb-mcp, landicefu/android-adb-mcp-server,
  httprunner/adb-mcp (Go), CursorTouch/Android-MCP, martingeidobler/android-mcp-server
  (registry-listed), wolfcoming/adb_mcp_server. ([search results](https://github.com/richard0913/adb-mcp))

### Maestro MCP (mobile-dev-inc)

- **What:** Official MCP for the Maestro E2E framework ("Painless E2E Automation for
  Mobile and Web", 15,267 stars / 912 forks — [api.github.com](https://api.github.com/repos/mobile-dev-inc/Maestro)).
  Tools: `list_devices`, `list_cloud_devices`, `inspect_screen`, `take_screenshot`,
  `run` (inline YAML flows or files), `run_on_cloud`, `cheat_sheet`,
  `open_maestro_viewer`, `get_cloud_run_status`. ([docs.maestro.dev/get-started/maestro-mcp](https://docs.maestro.dev/get-started/maestro-mcp))
- **Install:** `claude mcp add maestro -- maestro mcp` (CLI must be installed); one-click
  Cursor button. Stdio JSON-RPC. ([docs](https://docs.maestro.dev/get-started/maestro-mcp))
- **Platforms:** Android emulators, iOS simulators, physical devices, Chromium web. ([docs](https://docs.maestro.dev/get-started/maestro-mcp))
- **Security:** none locally; cloud runs need `maestro login` / API key. ([docs](https://docs.maestro.dev/get-started/maestro-mcp))
- **Unique:** **Maestro Viewer** embeds the emulator into the coding agent's UI with
  real-time command visualization; the agent's exploration converts into a durable,
  re-runnable YAML flow; `cheat_sheet` tool teaches the agent the DSL on demand. ([docs](https://docs.maestro.dev/get-started/maestro-mcp))

### appium/mcp-appium (`appium-mcp`)

- **What:** Official Appium-project MCP for AI-driven Android + iOS automation with
  embedded UiAutomator2/XCUITest drivers (no separate Appium server needed). ([github.com/appium/mcp-appium](https://github.com/appium/mcp-appium))
- **Install:** `npx appium-mcp@latest`; stdio; can attach to remote WebDriver servers. ([repo](https://github.com/appium/mcp-appium))
- **Tools:** session management, element finding (locators + optional AI vision),
  gestures, screenshots, page source, screen recording, geolocation, app lifecycle, and
  **automated Java/TestNG test generation with POM locator output**. ([repo](https://github.com/appium/mcp-appium))
- **Security:** "designed for local single-user or trusted CI"; `REMOTE_SERVER_URL_ALLOW_REGEX`
  to restrict remote endpoints; vision gated behind `AI_VISION_ENABLED`. ([repo](https://github.com/appium/mcp-appium))
- **Adoption:** 446 stars; **35,363 npm downloads last month** ([api.npmjs.org](https://api.npmjs.org/downloads/point/last-month/appium-mcp)).
- **Unique:** "NO_UI mode" cutting token use 60–90% for CI; MCP Apps static viewers for
  screenshots/page source (−64% bandwidth); plugin API. ([repo](https://github.com/appium/mcp-appium))

### Official platforms: Android Studio, Firebase, Google, JetBrains

- **Android Studio Agent Mode (MCP client):** configured via
  `Settings > Tools > AI > MCP Servers` writing an `mcp.json`; supports **Streamable
  HTTP (`httpUrl`) and SSE (`url`) only — no stdio**; supports **tools only — no MCP
  resources or prompt templates**; has browser-based auth flow for remote servers, with
  OAuth known-broken on some servers. ([developer.android.com/studio/gemini/add-mcp-server](https://developer.android.com/studio/gemini/add-mcp-server))
  Agent Mode itself (plan/execute/fix loops) is documented at
  [developer.android.com/studio/gemini/agent-mode](https://developer.android.com/studio/gemini/agent-mode) with a permissions page at
  [developer.android.com/studio/gemini/permissions](https://developer.android.com/studio/gemini/permissions); Otter 3 added LLM
  flexibility and Agent Mode improvements. ([android-developers.googleblog.com](https://android-developers.googleblog.com/2026/01/llm-flexibility-agent-mode-improvements.html))
  **Implication verified in DroidAgentKit's own docs:** its localhost Streamable-HTTP
  server exists precisely to serve this client; very few competitors (only us-all and
  mobile-mcp's SSE mode among those surveyed) can connect to Android Studio at all.
- **Firebase MCP server:** `npx firebase-tools@latest mcp`, stdio; project management,
  Auth users, Firestore/RTDB, Storage, Messaging, **Crashlytics issue investigation**,
  App Hosting, Functions logs, Android SHA-hash management; credentialed via the
  logged-in Firebase CLI; tool calls require user approval. Complementary (backend-side)
  rather than competing (no adb/Gradle). ([firebase.google.com/docs/cli/mcp-server](https://firebase.google.com/docs/cli/mcp-server))
- **Google Gemini CLI:** full MCP client ([geminicli.com/docs/tools/mcp-server](https://geminicli.com/docs/tools/mcp-server/));
  extensions bundle MCP servers + prompts + commands (100+ in the directory —
  [geminicli.com/extensions](https://geminicli.com/extensions/)). The only official Android extension found is the
  enterprise **Android Management API** one (`gemini extensions install
  https://github.com/gemini-cli-extensions/android-management-api`) — device fleet
  management, not development. ([github.com/gemini-cli-extensions/android-management-api](https://github.com/gemini-cli-extensions/android-management-api))
  **No official Google adb/Gradle developer MCP server was found.**
- **JetBrains:** AI Assistant gained MCP support in 2026.1 across the IDE family
  including Android Studio compatibility questions; Junie supports MCP tools
  ([jetbrains.com/help/ai-assistant/junie-agent.html](https://www.jetbrains.com/help/ai-assistant/junie-agent.html)) but Junie is not available in
  Android Studio; an "MCP Servers for AI Assistants" marketplace plugin manages servers
  across IntelliJ-family IDEs including Android Studio. ([plugins.jetbrains.com/plugin/28071](https://plugins.jetbrains.com/plugin/28071-mcp-servers-for-ai-assistants))
  JetBrains is a client platform here, not a competing server.

### minitap/mobile-use (adjacent, not an MCP server)

Multi-agent framework (not MCP) for natural-language phone control via accessibility
trees; Android devices/emulators + iOS simulators (fb-idb); Docker/uv/cloud install;
pluggable LLM backends; claims first 100% score on the AndroidWorld benchmark; 2.7k
stars, Apache-2.0. Relevant as a competing *paradigm* (agent owns the loop vs. exposing
tools to the user's agent) and as a benchmark yardstick. ([github.com/minitap-ai/mobile-use](https://github.com/minitap-ai/mobile-use))

---

## Comparison table

Legend: ✅ yes, ◐ partial, ✗ no. DAK = DroidAgentKit ([README](https://github.com/iVamsi/droid-agent-kit)).

| Dimension | DAK | mobile-mcp | DroidMind | minhalvp adb | us-all | androidbuild-mcp | Maestro MCP | appium-mcp | Ghost in the Droid | Firebase MCP |
|---|---|---|---|---|---|---|---|---|---|---|
| Install | npx launcher (JVM jar) | npx | uvx (git) | clone + uv | npx/Docker | npx | `maestro mcp` (CLI req.) | npx | uvx/pipx | npx (firebase-tools) |
| Transport | stdio + Streamable HTTP | stdio + SSE | stdio + SSE | stdio | stdio + HTTP(Bearer) | stdio | stdio | stdio | stdio (+dashboard) | stdio |
| Project inspect / Gradle | ✅ (allowlisted) | ✗ | ✗ | ✗ | ✗ | ✅ (no allowlist) | ✗ | ✗ | ✗ | ✗ |
| Lint / crash triage / build diagnose | ✅ | ✗ (crash logs only) | ◐ (crash reports) | ✗ | ◐ (crash extraction) | ◐ (lint, errors) | ✗ | ✗ | ✗ | ◐ (Crashlytics) |
| Device control / UI automation | ✅ (gated) | ✅ | ✅ | ◐ | ✅ | ✅ | ✅ (via flows) | ✅ | ✅ | ✗ |
| Screenshots / a11y tree | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ (+OCR) | ✗ |
| Logcat | ✅ | ✗ | ✅ | ✗ | ✅ | ✅ | ✗ | ✗ | ✗ | ✗ |
| Profiling (Perfetto/build perf) | ✅ | ✗ | ✗ | ✗ | ◐ (dumpsys diag) | ✗ | ✗ | ✗ | ✗ | ✗ |
| Storage inspection (SQLite/prefs) | ✅ (read-only, gated) | ✗ | ◐ (raw file ops) | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ◐ (Firestore, cloud) |
| Network capture | ✅ (emulator mitmproxy) | ✗ | ✗ | ✗ | ◐ (net status) | ✗ | ✗ | ✗ | ✗ | ✗ |
| Visual regression | ✅ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ |
| Test generation (durable artifact) | ✗ | ◐ (mobilewright) | ✗ | ✗ | ✗ | ✗ | ✅ (YAML flows) | ✅ (Java/TestNG) | ◐ (YAML skills) | ✗ |
| iOS | ✗ | ✅ | ✗ | ✗ | ✗ | ✗ | ✅ | ✅ | ✅ | n/a |
| Security model | Capability grants + allowlists + redaction + confirm | Bearer (SSE) only | Risk levels + validation | none | Read-only default + 2 env gates | none | cloud auth only | trusted-env + URL regex | zero-app default | CLI creds + per-call approval |
| Arbitrary shell exposed | ✗ | ✗ | ✅ | ✅ | ◐ (gated) | ✗ | ✗ | ✗ | ◐ | ✗ |
| Stars / npm downloads (mo) | — / 921 | 5.8k / 87.4k | 425 / — | 796 / — | 7 / — | 0 / — | 15.3k (framework) / — | 446 / 35.4k | 298 / — | (Google) |
| MCP registry listed | ✅ (as "droidagentkit") | ✗ (npm only) | ✗ | ✗ | ✅ | ✅ | ✗ | ✗ | ✅ | ✗ |

Sources: rows compiled from the per-competitor citations above; DAK column from
[README.md](https://github.com/iVamsi/droid-agent-kit) and `docs/security-and-permissions.md` in this repo; registry rows from
[registry.modelcontextprotocol.io/v0/servers?search=android](https://registry.modelcontextprotocol.io/v0/servers?search=android) and
[?search=droidagent](https://registry.modelcontextprotocol.io/v0/servers?search=droidagent).

---

## Gap analysis: what DroidAgentKit needs to be "the best Android MCP server"

Ordered by competitive impact.

1. **Durable test generation from agent exploration.** Maestro and Appium convert an
   agent session into a repeatable artifact (YAML flow / Java+TestNG test) — that is the
   outcome teams pay for, and DAK's `android_run_flow` executes bounded flows but never
   *emits* one. A "record this session as a flow / Compose UI test / Maestro YAML" tool
   would attack both incumbents' moat while keeping DAK's safety story.
   ([Maestro docs](https://docs.maestro.dev/get-started/maestro-mcp), [appium/mcp-appium](https://github.com/appium/mcp-appium))
2. **Discoverability and distribution.** 921 launcher downloads/month vs mobile-mcp's
   87k. Concretely fixable: the registry listing doesn't surface for `?search=android`
   because the server name lacks the word "android" ([registry query](https://registry.modelcontextprotocol.io/v0/servers?search=android) vs
   [?search=droidagent](https://registry.modelcontextprotocol.io/v0/servers?search=droidagent)) — fix the name/description keywords; add Smithery and other
   catalog listings; add one-click Cursor/VS Code install buttons to the README
   (Maestro and mobile-mcp both do). ([docs.maestro.dev](https://docs.maestro.dev/get-started/maestro-mcp))
3. **A live "watch the agent" viewer.** Maestro Viewer embeds the emulator in the coding
   agent's UI with real-time command visualization; Ghost in the Droid ships a local
   dashboard with MJPEG/WebRTC streaming. DAK returns artifacts but has no live view —
   even a lightweight localhost page streaming screenshots + tool-call log would close
   the demo-appeal gap. ([Maestro docs](https://docs.maestro.dev/get-started/maestro-mcp), [ghost-in-the-droid](https://github.com/ghost-in-the-droid/android-agent))
4. **Token-efficiency modes.** appium-mcp's NO_UI mode (−60–90% tokens) and us-all's
   schema trimming (−73%) show hosts care about context cost; DAK's structured outputs
   help but there's no explicit compact/CI mode or per-tool verbosity control.
   ([appium/mcp-appium](https://github.com/appium/mcp-appium), [us-all](https://github.com/us-all/android-mcp-server))
5. **Workflow prompts parity on Android Studio-adjacent hosts + diagnostic aggregations.**
   us-all ships crash-investigation / memory-leak / startup-profiling MCP prompts and
   one-call aggregations (`device-health`, `analyze-app`); DAK has prompt registries but
   suppresses them for AS (correct, since AS ignores prompts —
   [developer.android.com](https://developer.android.com/studio/gemini/add-mcp-server)) — consider equivalent single-call "triage bundles"
   exposed as plain tools so every host gets them. ([us-all](https://github.com/us-all/android-mcp-server))
6. **iOS (strategic question, not a quick gap).** Every automation leader (mobile-mcp,
   Maestro, appium-mcp, Ghost) is cross-platform. DAK's Gradle/lint/Perfetto depth is
   Android-native; the honest positioning is "best *Android developer* MCP" rather than
   chasing iOS — but state that positioning explicitly.
7. **Smaller ideas from the field:** a `doctor`/preflight tool (Ghost's `uvx … doctor`)
   that checks JDK/adb/trace-processor/mitmproxy in one call; a `cheat_sheet`-style
   self-documentation tool for flow syntax (Maestro); wireless-device connect flow
   (us-all); optional OCR fallback for non-accessible UIs (Ghost).

## Installation-UX patterns worth copying

1. **One-line `claude mcp add` + npx everywhere in docs** — already done
   (`claude mcp add droidagentkit -- npx -y @droidagentkit/launcher`), matching the
   pattern of mobile-mcp/appium-mcp/Maestro. Keep it as the first command in the README. ([README](https://github.com/iVamsi/droid-agent-kit))
2. **Official MCP registry hygiene** — already listed (0.2.5-alpha), but make the listing
   findable: include "Android" prominently in the registry name/description so
   `?search=android` returns it, and keep `server.json` versions current. Competitors
   martingeidobler, us-all, androidbuild-mcp, and Ghost are all registry-listed and
   surface for that query. ([registry](https://registry.modelcontextprotocol.io/v0/servers?search=android))
3. **One-click install buttons + catalog listings** — Cursor deep-link install button
   (Maestro docs show the pattern), VS Code `vscode:mcp/install` links, and a Smithery
   listing (`npx -y @smithery/cli install …`, as srmorete had). Low effort, high
   discoverability. ([docs.maestro.dev](https://docs.maestro.dev/get-started/maestro-mcp), [srmorete/adb-mcp](https://github.com/srmorete/adb-mcp))
4. **MCPB one-click** — DAK already ships `distribution/mcp.json`; none of the surveyed
   competitors do MCPB/Desktop Extensions, so this is a differentiator to advertise, not
   just maintain. (This repo, `distribution/`.)
5. **OAuth for remote servers is a client-side minefield** — Android Studio's HTTP client
   documents OAuth failures with some servers ([developer.android.com](https://developer.android.com/studio/gemini/add-mcp-server));
   DAK's localhost-HTTP-without-OAuth approach for AS is the pragmatic choice; don't
   invest in remote OAuth for now.
6. **Preflight/doctor UX** — `uvx ghost-in-the-droid doctor` before first run reduces
   "it didn't work" churn; DAK's capability summary in report bundles is close — surface
   it as a standalone `droidagent doctor` / `android_doctor` tool. ([ghost-in-the-droid](https://github.com/ghost-in-the-droid/android-agent))

## Where DroidAgentKit is already ahead (verified)

- **Security model.** No competitor has capability grants + user-vs-project config trust
  split + Gradle task allowlists + output redaction + destructive-op confirmation.
  Nearest peers: DroidMind's risk levels ([repo](https://github.com/hyperb1iss/droidmind)) and us-all's two env-var
  gates ([repo](https://github.com/us-all/android-mcp-server)). Several popular competitors expose raw shell
  (minhalvp, DroidMind); DAK deliberately does not.
- **Project/build intelligence.** Static project inspection, allowlisted Gradle runs,
  lint, dependency check, build performance, build diagnose — only the 0-star
  androidbuild-mcp overlaps at all, with no safety layer. ([repo](https://github.com/dev-jackson/androidbuild-mcp))
- **Perfetto profiling, read-only storage inspection, emulator network capture, visual
  regression** — each is unique in the surveyed field (us-all's dumpsys diagnostics are
  the closest partial overlap for profiling). ([us-all](https://github.com/us-all/android-mcp-server))
- **Android Studio compatibility.** AS accepts HTTP/SSE only, tools-only
  ([developer.android.com](https://developer.android.com/studio/gemini/add-mcp-server)); DAK ships a stateless Streamable-HTTP mode and a
  tools-only AS dispatcher plus `install-mcp --targets android-studio`. Most stdio-only
  competitors (Maestro, appium-mcp, androidbuild-mcp, Firebase) cannot connect to AS
  directly at all.
- **Agent-readiness auditor + AGENTS.md/skill generation** — no equivalent exists in any
  surveyed project.
- **Supply-chain posture.** SHA-256-verified jar fetch, no telemetry (mobile-mcp has
  opt-out telemetry — [repo](https://github.com/mobile-next/mobile-mcp)), registry-listed with immutable
  version metadata.

## Watch list

- **mobile-mcp ecosystem** (mobilewright/mobilecli) expanding from automation toward
  testing artifacts. ([repo](https://github.com/mobile-next/mobile-mcp))
- **Maestro** pulling coding-agent users into its Viewer + cloud. ([docs](https://docs.maestro.dev/get-started/maestro-mcp))
- **Google** shipping a first-party Android developer MCP server or bundling equivalent
  tools natively into Agent Mode — the largest structural risk; today Agent Mode's
  built-ins cover file/code operations only and rely on external MCP for the rest.
  ([developer.android.com/studio/gemini/agent-mode](https://developer.android.com/studio/gemini/agent-mode), [Otter 3 feature drop](https://android-developers.googleblog.com/2026/01/llm-flexibility-agent-mode-improvements.html))
