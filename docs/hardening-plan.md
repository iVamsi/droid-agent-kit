# DroidAgentKit Hardening & Stabilization Plan

Findings from a line-by-line review of 13,128 LOC across 66 main-source files, plus build,
CI, and distribution config. Severity reflects impact under the project's own stated threat
model: a local agent operating on a repository the developer may not fully trust.

Each finding lists the evidence that produced it. Items marked **VERIFIED** were reproduced by
executing code, not by reading it.

---

## Phase 0 — Trust-model breaks (fix before promoting the project)

### S1. A project config can widen the Gradle allowlist into arbitrary code execution — **VERIFIED**

`SafetyConfig.allowGradleTasks` is the one privileged-in-spirit field that is **not** in
`DroidAgentConfigLoader.privilegedKeys`, and `mergeWithUserPolicy` keeps the *project's* value
(`Config.kt:207-226`). The only guard is `catchAllGradlePatterns = setOf("*", "**")`, which
matches two literal strings.

`:*:*` is not one of those literals, and `globToRegex` expands `*` to `.*` across every
separator. Reproduced with a project config containing `allowGradleTasks: [":*:*"]` and an
empty user policy:

```
PROBE patterns       = [:*:*, *publish*]
PROBE maxCommandSecs = 99999
PROBE warnings       = []          <-- accepted silently
PROBE allowed[:app:assembleRelease]                          = true
PROBE allowed[:app:publishReleasePublicationToMavenRepository] = true
PROBE allowed[:app:installDebug]                             = true
PROBE allowed[:app:lintFix]                                  = true
PROBE allowed[:buildSrc:jar]                                 = true
```

Gradle tasks execute arbitrary build-script code. So a hostile repository ships a
`.droidagentkit/config.yaml`, the developer points an agent at it, and the repo has granted
itself arbitrary code execution on the developer's machine. This directly contradicts the
documented guarantee that a project config "cannot escalate privileges" and can only "narrow
the Gradle allowlist."

`safety.maxCommandSeconds` is project-controlled by the same path (99999 accepted), so the
project also chooses its own timeout ceiling.

**Fix.** Enforce narrowing structurally rather than blocklisting shapes:

1. Treat the policy/default list as the authoritative set. A project pattern is accepted only
   if every task it can match is also matched by some policy pattern — i.e. project patterns
   must be a strict subset, not a replacement.
2. Reject (don't silently drop) any project pattern that fails that test, with the line number.
3. Clamp `maxCommandSeconds` to `min(project, policy)`.
4. Add `safety.allowGradleTasks` and `safety.maxCommandSeconds` to the trust-split regression
   suite in `ConfigTrustTest.kt` — the existing tests cover capabilities and groups but never
   asserted anything about the task allowlist, which is why this survived.

### S2. `confirmDestructive` is supplied by the agent, not the human

`DefaultOperationPolicy.authorize` gates destructive operations on
`request.confirmDestructive` (`CapabilityPolicy.kt`), but that value arrives in the MCP tool
arguments — i.e. from the model. A compromised or prompt-injected agent simply passes `true`.

This is a guard against *accidental* invocation, which is worth having. It is not a guard
against a hostile agent, and the docs currently read as though it is.

**Fix.** Pick one and be explicit:

- Re-document it honestly as an accident guard, **and/or**
- Add a real out-of-band confirmation for the destructive set (a host-side prompt, or a
  policy-level `requireInteractiveConfirm` that blocks until a human responds on the CLI).

### S3. Default allowlist admits mutating tasks

`:*:lint*` matches `lintFix`, which rewrites source files. `:*:*AndroidTest` runs instrumented
tests, i.e. arbitrary code on a connected device. Both ship in the built-in default
(`Config.kt:31-38`), so they apply to every user who never writes a policy.

**Fix.** Narrow the defaults to non-mutating tasks (`:*:lint`, `:*:lintDebug`, explicit
report-only variants) and move anything that writes source or executes on-device behind an
opt-in capability.

---

## Phase 1 — Exploitable weaknesses

### S4. ReDoS through project-supplied redaction patterns

`redaction.extraPatterns` from the project file are merged into the effective config
(`Config.kt:224`) and compiled into live regexes (`Redaction.kt`). `validateExtraPattern`
screens them with a 256-char cap and one nested-quantifier heuristic:

```
NESTED_QUANTIFIER = (\([^)]*[+*][^)]*\))[+*]|\([^)]*[+*][^)]*[+*][^)]*\)
```

That requires a quantifier *inside* the group. Classic catastrophic patterns like `(a|a)+$`
have none and pass cleanly. Because the redactor runs over every command's output (up to
10 MB), one such pattern hangs every tool call.

**Fix.** Match on a bounded input (`Matcher` over an interruptible `CharSequence` with a
deadline), cap redaction input size, and drop project-supplied patterns from the privileged
merge — or require them to come from the policy like every other authority-granting key.

### S5. Device-path policy is a denylist

`FORBIDDEN_DEVICE_PATH_PREFIXES` blocks `/system/`, `/proc/`, `/sys/`, `/data/data/`,
`/data/user/`, `/data/app/`. Everything else is permitted, including `/data/local/tmp/` — the
standard staging directory for Android privilege pivots — plus `/etc/`, `/vendor/`, `/cache/`,
and `/mnt/`. The adjacent comment says push/pull is "for public/external storage only," which
is the correct intent but not what the code enforces.

**Fix.** Invert to an allowlist: `/sdcard/`, `/storage/emulated/0/`, and explicitly nothing
else. Keep the `..` normalization, which is already correct.

### S6. Symlink-following writes and lexical-only path confinement

21 write sites versus 5 `toRealPath` calls. Specifically:

- `ArtifactWriter.writeText/writeBytes/writeStream` resolve a sanitized name under `outputDir`
  and write with default options, which **follow symlinks**. `sanitize()` prevents traversal in
  the name, but a repository that commits `build/droidagentkit/gradle-run.log` as a symlink to,
  say, `~/.ssh/authorized_keys` turns the next Gradle run into a write through that link.
- `registerExistingFile` and `DefaultOperationPolicy`'s `hostPaths` check both use
  `normalize()`, which is lexical and does not resolve symlinks, so a link inside an allowed
  root escapes it.

**Fix.** Write with `StandardOpenOption.CREATE_NEW` or `LinkOption.NOFOLLOW_LINKS`, reject
existing symlinks at artifact targets, and switch containment checks to `toRealPath()` with the
root also realpath'd.

### S7. No validation of package names or device serials

No format check exists anywhere for either, across 32 adb invocation sites. Shell injection is
genuinely handled — `ShellQuote.quote` is correct POSIX single-quoting and is applied to every
post-`shell` argument — but a value beginning with `-` sits in *flag position* for `adb -s
<serial>` and `run-as <pkg>`, which is argument injection rather than shell injection.

**Fix.** Central validators: package against `[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z0-9_]+)+`, serial
against `[A-Za-z0-9._:-]+` with a leading-`-` rejection. Apply at the dispatcher boundary so
every provider inherits it.

### S8. DNS resolution on attacker-controlled headers in the request path

`isLoopbackHost` falls through to `InetAddress.getByName(hostname).isLoopbackAddress` for the
`Host` and `Origin` headers. That is a blocking DNS lookup driven by request content — a DoS
lever, and the classic DNS-rebinding shape.

The bearer token holds the line here (32 bytes of `SecureRandom`, constant-time compared via
`MessageDigest.isEqual` — this part is well done). Still worth removing.

**Fix.** Compare against literals only; drop the resolving fallback.

---

## Phase 2 — Stability

The codebase is in better shape here than most at this age: **one** `!!` in 13k lines, one
`exitProcess`, no zip extraction, bounded output capture (10 MB) and artifact streams (256 MB),
and `ProcessRunner` kills process descendants on timeout. The gaps are in tooling and evidence.

| # | Gap | Action |
|---|---|---|
| T1 | **No coverage measurement.** No kover or jacoco. The 80% target cannot currently be verified. | Add kover; gate CI on the domain/policy packages specifically, not a global average. |
| T2 | **No static analysis beyond formatting.** CI runs `ktlintCheck` only; CodeQL runs separately. No detekt. | Add detekt with a ruleset that fails on empty catch blocks and unused parameters. |
| T3 | **34 swallowed-exception sites** (`catch (_: Exception)`, `getOrNull()`, `getOrDefault`). Some are legitimate; some hide real failures behind empty results. | Audit each; require a logged warning or an explicit `ToolResult` warning for every swallow. |
| T4 | **One fuzz test on the privilege-deciding parser.** `ConfigFuzzTest` asserts only that random YAML "never crashes." | Crash-freedom is the weaker property. Add property tests asserting the *invariant*: for any project input, the effective config is never more permissive than the policy. That invariant is exactly what S1 violates. |
| T5 | **Flake risk.** 6 timing-dependent tests, 4 binding real sockets. | Replace sleeps with deterministic waits; bind port 0 and read the assigned port. |
| T6 | Device-supplied database filenames flow into `snapshotDir.resolve(name)` without the confinement `confinedDatabase` correctly applies elsewhere. | Route every device-derived filename through the same confinement helper. |
| T7 | `ArtifactWriter.assignOpaqueId` accepts a `sensitivity` parameter it never reads. | Remove it, or use it — currently misleading at a security-relevant call site. |

---

## Phase 3 — Project sustainability

- **Bus factor is 1.** Six weeks old, one real contributor, ~no external adoption (the 500
  monthly npm downloads are almost certainly mirrors).
- **SECURITY.md promises a 3-business-day response** to vulnerability reports. That is a hard
  commitment for a single maintainer; either staff it or soften the wording.
- **Document the prompt-injection threat model explicitly.** This is the realistic attack on
  any MCP server: the agent reads hostile content (a logcat line, a crash message, a README)
  and acts on it. The capability system bounds the blast radius well, and saying so plainly —
  including what it does *not* bound — would put this project ahead of most of the field.

---

## Suggested sequencing

1. **S1** first and alone. It is the one finding that voids the project's central security
   claim, and the fix is well-contained: enforce narrowing in the merge, plus regression tests.
2. **S4, S5, S7** next — each is a small, self-contained patch with a clear test.
3. **S2 and the S3 defaults** together, since both are really "what does the user actually
   consent to" questions and should land with one documentation pass.
4. **S6** after that; it touches the most call sites and benefits from the test tooling in T1/T2
   landing first.
5. **Phase 2** continuously alongside.

## Acceptance criteria

- A project-local config cannot produce an effective config more permissive than the user
  policy along *any* axis — asserted by property test, not by example.
- Every device path and host path crossing a trust boundary is checked against an allowlist,
  with symlinks resolved.
- Coverage is measured and enforced on `toolbox-core` policy code.
- The threat model documents what the capability system does and does not protect against.

---

## What the review found working well

Recording this so the plan isn't mistaken for a verdict on the whole codebase:

- `ShellQuote` is correct, and its KDoc shows real understanding of the non-obvious reason it's
  needed (`adb shell` re-joins argv and re-parses it device-side).
- `ProcessRunner` uses list-form exec with no shell, kills descendants on timeout, caps output,
  redacts before returning, and scrubs `GRADLE_OPTS`/`JAVA_TOOL_OPTIONS` to block flag injection.
- The HTTP transport refuses non-loopback binds, validates `Host` and `Origin`, and requires a
  `SecureRandom` bearer token compared in constant time.
- `SqliteInspector.confinedDatabase` is exactly the right shape — reject, then re-verify after
  normalize — and is the model the other path checks should follow.
- Supply chain is strong: 179 pinned components, SHA-pinned Actions, OIDC trusted publishing,
  SBOM, checksums, CodeQL, Scorecard, gitleaks.
- 423 tests, zero `TODO`/`FIXME` in main source, complete OSS governance files.
