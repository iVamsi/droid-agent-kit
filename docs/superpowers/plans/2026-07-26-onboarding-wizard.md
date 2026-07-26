# `droidagent init` Onboarding Wizard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** add a `droidagent init` CLI command that generates `.droidagentkit/config.yaml`, either interactively (six yes/no prompts explaining risk per MCP tool group) or non-interactively via named `--profile` presets, without introducing any new concept into the config schema itself.

**Architecture:** a pure-function `ConfigYaml` renderer in `toolbox-core` (shared with the existing `audit --write-agents` starter-config path), a `ProfileCatalog` in `cli` built from nine small reusable "question-block" constants (one per wizard question/follow-up), an `InitWizard` that walks those same blocks interactively with injected I/O for testability, and a thin `DroidAgentMain.init()` handler that wires TTY detection, `--force`/refuse-if-exists, and file writing around whichever of the two (`ProfileCatalog` or `InitWizard`) produced the result.

**Tech Stack:** Kotlin/JVM, JUnit 4, no new third-party dependencies.

Full design context: `docs/superpowers/specs/2026-07-26-onboarding-wizard-design.md`.

## Global Constraints

- Zero new third-party dependencies — JDK stdlib only (`readlnOrNull()`, `System.console()`).
- No change to `DroidAgentConfig`/`DroidAgentConfigLoader` schema or parsing (`toolbox-core/src/main/kotlin/com/droidagentkit/core/Config.kt`) — every file this feature writes must already be understood by the existing loader, unmodified.
- `AgentDocumentWriter`'s existing starter-config behavior (write only if `.droidagentkit/config.yaml` is absent, `core`-only content) must stay byte-identical for anyone who never runs `init` — enforced by a regression test, not just visual inspection.
- Config files must stay fully explicit: literal `ToolGroup`/`Capability` enum-name lists, never a "profile" name written into the YAML itself.
- Real fixtures/filesystem in tests, no mocks — follow existing repo conventions (`DeviceControlToolProviderTest`'s fake-`adb`-script pattern, `DroidAgentCliIntegrationTest`'s real-tempdir pattern).
- `./gradlew test` must pass after every task.

---

### Task 1: `ConfigYaml` shared renderer

**Files:**
- Create: `toolbox-core/src/main/kotlin/com/droidagentkit/core/ConfigYaml.kt`
- Test: `toolbox-core/src/test/kotlin/com/droidagentkit/core/ConfigYamlTest.kt`

**Interfaces:**
- Produces: `object ConfigYaml { fun render(groups: Set<ToolGroup>, capabilities: Set<Capability>): String }` — used by Task 2 (`AgentDocumentWriter`) and Task 6 (`DroidAgentMain.init()`).

Background: today's config-generation code (`auditor-cli/src/main/kotlin/com/droidagentkit/auditor/ReadinessAuditor.kt`, `AgentDocumentWriter.defaultConfigYaml()`, lines 452-473) hardcodes a single YAML string with the three deprecated boolean aliases (`allowAdbInput`, `allowAppInstall`, `allowEmulatorStart`). `ConfigYaml.render` must reproduce that exact string when called with empty sets (so Task 2's refactor is a no-op for existing behavior), and switch to the `allowCapabilities:`/`mcp.exposedGroups:` list form — omitting the three legacy booleans entirely — whenever either set is non-empty. Omitting them (rather than also emitting `allowAdbInput: false` etc. alongside `allowCapabilities`) matters: `DroidAgentConfigLoader`'s per-key parsing branches only set `aliasUsedWithCapabilities = true` when a legacy key line is actually present in the file, so omitting the keys avoids ever triggering the "aliases ignored" warning on every server startup for anyone who used `init` with a non-`core` profile — the two mechanisms would otherwise coexist confusingly in one file.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.droidagentkit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ConfigYamlTest {
    @Test
    fun `render with empty groups and capabilities matches the historical default config`() {
        val expected =
            """
            schemaVersion: 1
            project:
              name: inferred
            safety:
              allowGradleTasks:
                - ":*:test*UnitTest"
                - ":*:lint*"
                - ":*:assemble*Debug"
                - ":*:*AndroidTest"
                - ":*:validate*ScreenshotTest"
              allowAdbInput: false
              allowAppInstall: true
              allowEmulatorStart: false
              maxCommandSeconds: 600
            reports:
              outputDir: "build/droidagentkit"
            redaction:
              enabled: true
              extraPatterns: []
            """.trimIndent()

        assertEquals(expected, ConfigYaml.render(emptySet(), emptySet()))
    }

    @Test
    fun `render with capabilities omits the legacy boolean aliases`() {
        val yaml = ConfigYaml.render(emptySet(), setOf(Capability.DEVICE_INPUT, Capability.APP_CONTROL))

        assertTrue(yaml.contains("  allowCapabilities:"))
        assertTrue(yaml.contains("    - app_control"))
        assertTrue(yaml.contains("    - device_input"))
        assertTrue(!yaml.contains("allowAdbInput"))
        assertTrue(!yaml.contains("allowAppInstall"))
        assertTrue(!yaml.contains("allowEmulatorStart"))
    }

    @Test
    fun `render with groups adds an mcp exposedGroups section`() {
        val yaml = ConfigYaml.render(setOf(ToolGroup.DEVICE_READ, ToolGroup.DEVICE_CONTROL), emptySet())

        assertTrue(yaml.contains("mcp:"))
        assertTrue(yaml.contains("  exposedGroups:"))
        assertTrue(yaml.contains("    - device_control"))
        assertTrue(yaml.contains("    - device_read"))
    }

    @Test
    fun `render with empty groups omits the mcp section entirely`() {
        val yaml = ConfigYaml.render(emptySet(), setOf(Capability.DEVICE_INPUT))

        assertTrue(!yaml.contains("mcp:"))
    }

    @Test
    fun `render output round-trips through the config loader`() {
        val root = Files.createTempDirectory("dak-configyaml-roundtrip")
        val yaml =
            ConfigYaml.render(
                setOf(ToolGroup.DEVICE_READ, ToolGroup.DEVICE_CONTROL),
                setOf(Capability.DEVICE_INPUT, Capability.APP_CONTROL),
            )
        val configPath = root.resolve(".droidagentkit/config.yaml")
        Files.createDirectories(configPath.parent)
        Files.writeString(configPath, yaml)

        val result = DroidAgentConfigLoader.load(root)

        assertTrue(result is ConfigLoadResult.Loaded)
        val loaded = result as ConfigLoadResult.Loaded
        assertTrue(loaded.warnings.isEmpty())
        assertEquals(setOf(ToolGroup.DEVICE_READ, ToolGroup.DEVICE_CONTROL), loaded.config.mcp.exposedGroups)
        assertEquals(setOf(Capability.DEVICE_INPUT, Capability.APP_CONTROL), loaded.config.safety.allowCapabilities)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :toolbox-core:test --tests "com.droidagentkit.core.ConfigYamlTest"`
Expected: FAIL — `ConfigYaml` is unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.droidagentkit.core

object ConfigYaml {
    fun render(
        groups: Set<ToolGroup>,
        capabilities: Set<Capability>,
    ): String {
        val lines = mutableListOf<String>()
        lines += "schemaVersion: 1"
        lines += "project:"
        lines += "  name: inferred"
        lines += "safety:"
        lines += "  allowGradleTasks:"
        lines += "    - \":*:test*UnitTest\""
        lines += "    - \":*:lint*\""
        lines += "    - \":*:assemble*Debug\""
        lines += "    - \":*:*AndroidTest\""
        lines += "    - \":*:validate*ScreenshotTest\""
        if (capabilities.isEmpty()) {
            lines += "  allowAdbInput: false"
            lines += "  allowAppInstall: true"
            lines += "  allowEmulatorStart: false"
        } else {
            lines += "  allowCapabilities:"
            capabilities.map { it.name.lowercase() }.sorted().forEach { lines += "    - $it" }
        }
        lines += "  maxCommandSeconds: 600"
        if (groups.isNotEmpty()) {
            lines += "mcp:"
            lines += "  exposedGroups:"
            groups.map { it.name.lowercase() }.sorted().forEach { lines += "    - $it" }
        }
        lines += "reports:"
        lines += "  outputDir: \"build/droidagentkit\""
        lines += "redaction:"
        lines += "  enabled: true"
        lines += "  extraPatterns: []"
        return lines.joinToString("\n")
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :toolbox-core:test --tests "com.droidagentkit.core.ConfigYamlTest"`
Expected: PASS, all 5 tests.

- [ ] **Step 5: Commit**

```bash
git add toolbox-core/src/main/kotlin/com/droidagentkit/core/ConfigYaml.kt toolbox-core/src/test/kotlin/com/droidagentkit/core/ConfigYamlTest.kt
git commit -m "feat: add shared ConfigYaml renderer for explicit tool-group/capability config"
```

---

### Task 2: Wire `AgentDocumentWriter` to `ConfigYaml`

**Files:**
- Modify: `auditor-cli/src/main/kotlin/com/droidagentkit/auditor/ReadinessAuditor.kt:452-473` (`AgentDocumentWriter.defaultConfigYaml`)

**Interfaces:**
- Consumes: `ConfigYaml.render(groups: Set<ToolGroup>, capabilities: Set<Capability>): String` from Task 1.

This task exists on its own (rather than folded into Task 1) because it changes existing production behavior on a real, already-tested path (`audit --write-agents`) — worth its own gate even though the change is one line.

- [ ] **Step 1: Confirm the regression test that must keep passing**

Run: `./gradlew :cli:test --tests "com.droidagentkit.cli.DroidAgentCliIntegrationTest"`
Expected: PASS (baseline, before touching anything) — in particular `audit --write-agents populates generatedDocuments in the readiness report`.

- [ ] **Step 2: Replace the hardcoded string**

In `auditor-cli/src/main/kotlin/com/droidagentkit/auditor/ReadinessAuditor.kt`, add the import:

```kotlin
import com.droidagentkit.core.ConfigYaml
```

Replace:

```kotlin
    private fun defaultConfigYaml(): String =
        """
        schemaVersion: 1
        project:
          name: inferred
        safety:
          allowGradleTasks:
            - ":*:test*UnitTest"
            - ":*:lint*"
            - ":*:assemble*Debug"
            - ":*:*AndroidTest"
            - ":*:validate*ScreenshotTest"
          allowAdbInput: false
          allowAppInstall: true
          allowEmulatorStart: false
          maxCommandSeconds: 600
        reports:
          outputDir: "build/droidagentkit"
        redaction:
          enabled: true
          extraPatterns: []
        """.trimIndent()
```

with:

```kotlin
    private fun defaultConfigYaml(): String = ConfigYaml.render(emptySet(), emptySet())
```

- [ ] **Step 3: Confirm `auditor-cli` already depends on `toolbox-core`**

Run: `grep -n "toolbox-core" auditor-cli/build.gradle.kts`
Expected: `implementation(project(":toolbox-core"))` is present (it already is — no build file change needed).

- [ ] **Step 4: Run the regression test**

Run: `./gradlew :cli:test --tests "com.droidagentkit.cli.DroidAgentCliIntegrationTest"`
Expected: PASS — identical to Step 1's baseline, proving `ConfigYaml.render(emptySet(), emptySet())` produces output the rest of the pipeline can't distinguish from the old hardcoded string.

- [ ] **Step 5: Run the full auditor-cli suite**

Run: `./gradlew :auditor-cli:test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add auditor-cli/src/main/kotlin/com/droidagentkit/auditor/ReadinessAuditor.kt
git commit -m "refactor: AgentDocumentWriter uses shared ConfigYaml renderer"
```

---

### Task 3: `ProfileCatalog`

**Files:**
- Create: `cli/src/main/kotlin/com/droidagentkit/cli/ProfileCatalog.kt`
- Test: `cli/src/test/kotlin/com/droidagentkit/cli/ProfileCatalogTest.kt`

**Interfaces:**
- Produces:
  - `data class ProfileExpansion(val groups: Set<ToolGroup>, val capabilities: Set<Capability>)`
  - `object ProfileCatalog` with:
    - Public question-block constants: `Q1_DEVICE_READ`, `Q1_BUGREPORT`, `Q2_DEVICE_CONTROL`, `Q2_IRREVERSIBLE`, `Q3_PERFETTO`, `Q4_VISUALS`, `Q4_GOLDEN`, `Q5_STORAGE`, `Q6_NETWORK` (each a `ProfileExpansion`) — consumed directly by Task 4 (`InitWizard`).
    - `fun union(vararg expansions: ProfileExpansion): ProfileExpansion`
    - `fun names(): List<String>` — sorted profile names, used by `--list-profiles` in Task 6.
    - `fun expand(profileNames: List<String>): Result<ProfileExpansion>` — `Result.failure(IllegalArgumentException(...))` listing unknown names in the message if any name isn't recognized; otherwise `Result.success` of the union.

The nine question-block constants exist so `ProfileCatalog` and `InitWizard` (Task 4) build their results out of the exact same pieces — a wizard answer sequence and a named profile are two different ways of picking the same set of blocks, never two independently-maintained lists of `ToolGroup`/`Capability` enum values.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.droidagentkit.cli

import com.droidagentkit.core.Capability
import com.droidagentkit.core.ToolGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileCatalogTest {
    @Test
    fun `core profile is empty`() {
        val expansion = ProfileCatalog.expand(listOf("core")).getOrThrow()

        assertEquals(emptySet<ToolGroup>(), expansion.groups)
        assertEquals(emptySet<Capability>(), expansion.capabilities)
    }

    @Test
    fun `device-control profile excludes irreversible and diagnostic capabilities`() {
        val expansion = ProfileCatalog.expand(listOf("device-control")).getOrThrow()

        assertEquals(setOf(ToolGroup.DEVICE_READ, ToolGroup.DEVICE_CONTROL), expansion.groups)
        assertEquals(
            setOf(
                Capability.DEVICE_INPUT,
                Capability.APP_CONTROL,
                Capability.EMULATOR_CONTROL,
                Capability.EMULATOR_RESTORE,
                Capability.FILE_EXPORT,
                Capability.FILE_IMPORT,
            ),
            expansion.capabilities,
        )
    }

    @Test
    fun `storage profile is the storage group alone`() {
        val expansion = ProfileCatalog.expand(listOf("storage")).getOrThrow()

        assertEquals(setOf(ToolGroup.STORAGE), expansion.groups)
        assertEquals(setOf(Capability.APP_DATA_READ), expansion.capabilities)
    }

    @Test
    fun `network-experimental profile is the network_experimental group alone`() {
        val expansion = ProfileCatalog.expand(listOf("network-experimental")).getOrThrow()

        assertEquals(setOf(ToolGroup.NETWORK_EXPERIMENTAL), expansion.groups)
        assertEquals(setOf(Capability.NETWORK_INTERCEPTION), expansion.capabilities)
    }

    @Test
    fun `full profile equals the union of every other named profile plus perfetto, visuals, and the irreversible extras`() {
        val core = ProfileCatalog.expand(listOf("core")).getOrThrow()
        val deviceControl = ProfileCatalog.expand(listOf("device-control")).getOrThrow()
        val storage = ProfileCatalog.expand(listOf("storage")).getOrThrow()
        val networkExperimental = ProfileCatalog.expand(listOf("network-experimental")).getOrThrow()
        val extrasNotCoveredByAnyOtherNamedProfile =
            ProfileExpansion(
                groups = setOf(ToolGroup.PERFETTO, ToolGroup.VISUALS),
                capabilities =
                    setOf(
                        Capability.APP_DESTRUCTIVE,
                        Capability.PERMISSION_MUTATION,
                        Capability.SENSITIVE_DIAGNOSTICS,
                        Capability.GOLDEN_UPDATE,
                    ),
            )
        val expected =
            ProfileCatalog.union(core, deviceControl, storage, networkExperimental, extrasNotCoveredByAnyOtherNamedProfile)

        val actual = ProfileCatalog.expand(listOf("full")).getOrThrow()

        assertEquals(expected.groups, actual.groups)
        assertEquals(expected.capabilities, actual.capabilities)
    }

    @Test
    fun `comma-combining two profiles unions their groups and capabilities`() {
        val expansion = ProfileCatalog.expand(listOf("storage", "network-experimental")).getOrThrow()

        assertEquals(setOf(ToolGroup.STORAGE, ToolGroup.NETWORK_EXPERIMENTAL), expansion.groups)
        assertEquals(setOf(Capability.APP_DATA_READ, Capability.NETWORK_INTERCEPTION), expansion.capabilities)
    }

    @Test
    fun `unknown profile name is rejected with the name surfaced`() {
        val result = ProfileCatalog.expand(listOf("not-a-real-profile"))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("not-a-real-profile"))
    }

    @Test
    fun `names lists every profile sorted`() {
        assertEquals(
            listOf("core", "device-control", "full", "network-experimental", "storage"),
            ProfileCatalog.names(),
        )
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :cli:test --tests "com.droidagentkit.cli.ProfileCatalogTest"`
Expected: FAIL — `ProfileCatalog`/`ProfileExpansion` unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.droidagentkit.cli

import com.droidagentkit.core.Capability
import com.droidagentkit.core.ToolGroup

data class ProfileExpansion(
    val groups: Set<ToolGroup>,
    val capabilities: Set<Capability>,
)

object ProfileCatalog {
    val Q1_DEVICE_READ = ProfileExpansion(groups = setOf(ToolGroup.DEVICE_READ), capabilities = emptySet())
    val Q1_BUGREPORT = ProfileExpansion(groups = emptySet(), capabilities = setOf(Capability.SENSITIVE_DIAGNOSTICS))
    val Q2_DEVICE_CONTROL =
        ProfileExpansion(
            groups = setOf(ToolGroup.DEVICE_CONTROL),
            capabilities =
                setOf(
                    Capability.DEVICE_INPUT,
                    Capability.APP_CONTROL,
                    Capability.EMULATOR_CONTROL,
                    Capability.EMULATOR_RESTORE,
                    Capability.FILE_EXPORT,
                    Capability.FILE_IMPORT,
                ),
        )
    val Q2_IRREVERSIBLE =
        ProfileExpansion(groups = emptySet(), capabilities = setOf(Capability.APP_DESTRUCTIVE, Capability.PERMISSION_MUTATION))
    val Q3_PERFETTO = ProfileExpansion(groups = setOf(ToolGroup.PERFETTO), capabilities = setOf(Capability.SENSITIVE_DIAGNOSTICS))
    val Q4_VISUALS = ProfileExpansion(groups = setOf(ToolGroup.VISUALS), capabilities = emptySet())
    val Q4_GOLDEN = ProfileExpansion(groups = emptySet(), capabilities = setOf(Capability.GOLDEN_UPDATE))
    val Q5_STORAGE = ProfileExpansion(groups = setOf(ToolGroup.STORAGE), capabilities = setOf(Capability.APP_DATA_READ))
    val Q6_NETWORK =
        ProfileExpansion(groups = setOf(ToolGroup.NETWORK_EXPERIMENTAL), capabilities = setOf(Capability.NETWORK_INTERCEPTION))

    fun union(vararg expansions: ProfileExpansion): ProfileExpansion =
        ProfileExpansion(
            groups = expansions.flatMap { it.groups }.toSet(),
            capabilities = expansions.flatMap { it.capabilities }.toSet(),
        )

    private val PROFILES: Map<String, ProfileExpansion> =
        mapOf(
            "core" to ProfileExpansion(emptySet(), emptySet()),
            "device-control" to union(Q1_DEVICE_READ, Q2_DEVICE_CONTROL),
            "storage" to Q5_STORAGE,
            "network-experimental" to Q6_NETWORK,
            "full" to
                union(
                    Q1_DEVICE_READ,
                    Q1_BUGREPORT,
                    Q2_DEVICE_CONTROL,
                    Q2_IRREVERSIBLE,
                    Q3_PERFETTO,
                    Q4_VISUALS,
                    Q4_GOLDEN,
                    Q5_STORAGE,
                    Q6_NETWORK,
                ),
        )

    fun names(): List<String> = PROFILES.keys.sorted()

    fun expand(profileNames: List<String>): Result<ProfileExpansion> {
        val unknown = profileNames.filter { it !in PROFILES }
        if (unknown.isNotEmpty()) {
            return Result.failure(IllegalArgumentException(unknown.joinToString(", ")))
        }
        return Result.success(union(*profileNames.map { PROFILES.getValue(it) }.toTypedArray()))
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :cli:test --tests "com.droidagentkit.cli.ProfileCatalogTest"`
Expected: PASS, all 8 tests.

- [ ] **Step 5: Commit**

```bash
git add cli/src/main/kotlin/com/droidagentkit/cli/ProfileCatalog.kt cli/src/test/kotlin/com/droidagentkit/cli/ProfileCatalogTest.kt
git commit -m "feat: add ProfileCatalog with named config profiles built from shared question blocks"
```

---

### Task 4: `InitWizard`

**Files:**
- Create: `cli/src/main/kotlin/com/droidagentkit/cli/InitWizard.kt`
- Test: `cli/src/test/kotlin/com/droidagentkit/cli/InitWizardTest.kt`

**Interfaces:**
- Consumes: `ProfileCatalog.{Q1_DEVICE_READ, Q1_BUGREPORT, Q2_DEVICE_CONTROL, Q2_IRREVERSIBLE, Q3_PERFETTO, Q4_VISUALS, Q4_GOLDEN, Q5_STORAGE, Q6_NETWORK, union}` from Task 3.
- Produces: `class InitWizard(private val readLine: () -> String?, private val print: (String) -> Unit) { fun run(): ProfileExpansion? }` — consumed by Task 6 (`DroidAgentMain.init()`), constructed there with `::readlnOrNull` and `::print`.

`run()` returns `null` if the user declines the final write-confirmation (an intentional no-op, not an error — Task 6 turns that into "Aborted, no file written." + exit 0).

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.droidagentkit.cli

import com.droidagentkit.core.Capability
import com.droidagentkit.core.ToolGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InitWizardTest {
    private fun wizard(answers: List<String>): InitWizard {
        val iterator = answers.iterator()
        return InitWizard(readLine = { if (iterator.hasNext()) iterator.next() else null }, print = {})
    }

    @Test
    fun `answering no to every question and yes to the final confirm writes an empty expansion`() {
        // 6 top-level questions, all "n", then "y" to the final write-confirmation.
        val result = wizard(listOf("n", "n", "n", "n", "n", "n", "y")).run()

        assertEquals(ProfileExpansion(emptySet(), emptySet()), result)
    }

    @Test
    fun `answering yes to every question and every follow-up matches the full profile`() {
        // device-read=y, bugreport-followup=y, device-control=y, irreversible-followup=y,
        // perfetto=y, visuals=y, golden-followup=y, storage=y, network=y, final-confirm=y
        val result = wizard(listOf("y", "y", "y", "y", "y", "y", "y", "y", "y", "y")).run()

        val expected =
            ProfileCatalog.union(
                ProfileCatalog.Q1_DEVICE_READ,
                ProfileCatalog.Q1_BUGREPORT,
                ProfileCatalog.Q2_DEVICE_CONTROL,
                ProfileCatalog.Q2_IRREVERSIBLE,
                ProfileCatalog.Q3_PERFETTO,
                ProfileCatalog.Q4_VISUALS,
                ProfileCatalog.Q4_GOLDEN,
                ProfileCatalog.Q5_STORAGE,
                ProfileCatalog.Q6_NETWORK,
            )
        assertEquals(expected, result)
    }

    @Test
    fun `declining a follow-up grants the group but not the follow-up capability`() {
        // device-read=y, bugreport-followup=n, then no to the remaining 5 questions, final-confirm=y
        val result = wizard(listOf("y", "n", "n", "n", "n", "n", "n", "y")).run()

        assertEquals(ProfileExpansion(setOf(ToolGroup.DEVICE_READ), emptySet()), result)
    }

    @Test
    fun `declining the final confirmation returns null regardless of earlier answers`() {
        // device-control=y, irreversible-followup=y, then no to remaining 4 questions, final-confirm=n
        val result = wizard(listOf("n", "y", "y", "n", "n", "n", "n")).run()

        assertNull(result)
    }

    @Test
    fun `perfetto alone grants sensitive_diagnostics without exposing device_read`() {
        // device-read=n, device-control=n, perfetto=y, visuals=n, storage=n, network=n, final-confirm=y
        val result = wizard(listOf("n", "n", "y", "n", "n", "n", "y")).run()

        assertEquals(ProfileExpansion(setOf(ToolGroup.PERFETTO), setOf(Capability.SENSITIVE_DIAGNOSTICS)), result)
    }

    @Test
    fun `blank or unrecognized answers are treated as no`() {
        val result = wizard(listOf("", "maybe", "n", "n", "n", "n", "y")).run()

        assertEquals(ProfileExpansion(emptySet(), emptySet()), result)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :cli:test --tests "com.droidagentkit.cli.InitWizardTest"`
Expected: FAIL — `InitWizard` unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.droidagentkit.cli

class InitWizard(
    private val readLine: () -> String?,
    private val print: (String) -> Unit,
) {
    fun run(): ProfileExpansion? {
        var expansion = ProfileExpansion(emptySet(), emptySet())

        if (askYesNo("Let the agent read device diagnostics (dumpsys, memory, battery, permission state)?")) {
            expansion = ProfileCatalog.union(expansion, ProfileCatalog.Q1_DEVICE_READ)
            if (askYesNo(
                    "Also allow full bugreport capture? This dumps extensive device/app data, " +
                        "including data from other apps.",
                )
            ) {
                expansion = ProfileCatalog.union(expansion, ProfileCatalog.Q1_BUGREPORT)
            }
        }
        if (askYesNo("Let the agent tap/swipe/type, open deep links, start/stop emulators, and push/pull files?")) {
            expansion = ProfileCatalog.union(expansion, ProfileCatalog.Q2_DEVICE_CONTROL)
            if (askYesNo(
                    "Also allow uninstalling apps, clearing app data, and granting/revoking permissions? " +
                        "These are irreversible on the device.",
                )
            ) {
                expansion = ProfileCatalog.union(expansion, ProfileCatalog.Q2_IRREVERSIBLE)
            }
        }
        if (askYesNo("Let the agent capture and analyze system performance traces?")) {
            expansion = ProfileCatalog.union(expansion, ProfileCatalog.Q3_PERFETTO)
        }
        if (askYesNo("Let the agent diff and report on UI screenshots?")) {
            expansion = ProfileCatalog.union(expansion, ProfileCatalog.Q4_VISUALS)
            if (askYesNo("Also allow overwriting golden/baseline images?")) {
                expansion = ProfileCatalog.union(expansion, ProfileCatalog.Q4_GOLDEN)
            }
        }
        if (askYesNo("Let the agent read a debuggable app's own SQLite databases and SharedPreferences (read-only)?")) {
            expansion = ProfileCatalog.union(expansion, ProfileCatalog.Q5_STORAGE)
        }
        if (askYesNo(
                "Let the agent intercept emulator network traffic via mitmproxy? Requires a debug-trusted " +
                    "CA already installed; emulator-only.",
            )
        ) {
            expansion = ProfileCatalog.union(expansion, ProfileCatalog.Q6_NETWORK)
        }

        val groupsText = expansion.groups.map { it.name.lowercase() }.sorted().ifEmpty { listOf("(none)") }.joinToString(", ")
        val capsText = expansion.capabilities.map { it.name.lowercase() }.sorted().ifEmpty { listOf("(none)") }.joinToString(", ")
        print("This will enable groups: $groupsText")
        print("and capabilities: $capsText")

        return if (askYesNo("Write this to .droidagentkit/config.yaml?")) expansion else null
    }

    private fun askYesNo(question: String): Boolean {
        print("$question [y/N] ")
        val answer = readLine()?.trim()?.lowercase()
        return answer == "y" || answer == "yes"
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :cli:test --tests "com.droidagentkit.cli.InitWizardTest"`
Expected: PASS, all 6 tests.

- [ ] **Step 5: Commit**

```bash
git add cli/src/main/kotlin/com/droidagentkit/cli/InitWizard.kt cli/src/test/kotlin/com/droidagentkit/cli/InitWizardTest.kt
git commit -m "feat: add InitWizard interactive prompt flow"
```

---

### Task 5: `CliCommand.Init` + parser + registry

**Files:**
- Modify: `cli/src/main/kotlin/com/droidagentkit/cli/CliCommand.kt`
- Modify: `cli/src/main/kotlin/com/droidagentkit/cli/CliCommandSpec.kt`
- Modify: `cli/src/main/kotlin/com/droidagentkit/cli/DroidAgentCliParser.kt`
- Test: `cli/src/test/kotlin/com/droidagentkit/cli/CliParserTest.kt` (extend existing file)

**Interfaces:**
- Produces: `data class Init(val profiles: List<String>, val force: Boolean, val listProfiles: Boolean, val project: String) : CliCommand()` — consumed by Task 6's `DroidAgentCli.run()`/`init()`.

- [ ] **Step 1: Write the failing tests (append to existing `CliParserTest.kt`)**

```kotlin
    @Test
    fun `parser understands init with a single profile`() {
        val command = DroidAgentCliParser().parse(arrayOf("init", "--profile", "device-control", "--project", "."))

        assertEquals(
            CliCommand.Init(profiles = listOf("device-control"), force = false, listProfiles = false, project = "."),
            command,
        )
    }

    @Test
    fun `parser splits comma-separated init profiles`() {
        val command = DroidAgentCliParser().parse(arrayOf("init", "--profile", "storage,network-experimental"))

        assertEquals(
            CliCommand.Init(profiles = listOf("storage", "network-experimental"), force = false, listProfiles = false, project = "."),
            command,
        )
    }

    @Test
    fun `parser understands init force and list-profiles flags`() {
        val force = DroidAgentCliParser().parse(arrayOf("init", "--profile", "full", "--force"))
        val list = DroidAgentCliParser().parse(arrayOf("init", "--list-profiles"))

        assertEquals(CliCommand.Init(profiles = listOf("full"), force = true, listProfiles = false, project = "."), force)
        assertEquals(CliCommand.Init(profiles = emptyList(), force = false, listProfiles = true, project = "."), list)
    }

    @Test
    fun `parser rejects unknown flag on init`() {
        val command = DroidAgentCliParser().parse(arrayOf("init", "--profil", "full"))

        assertTrue(command is CliCommand.Help)
        assertTrue((command as CliCommand.Help).error!!.contains("--profil"))
    }
```

Add the required imports if not already present at the top of the file (`CliCommand`, `assertEquals`, `assertTrue` are already imported by the existing file — no new imports needed).

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :cli:test --tests "com.droidagentkit.cli.CliParserTest"`
Expected: FAIL — `CliCommand.Init` unresolved, and `init` is not a recognized command name.

- [ ] **Step 3: Add `CliCommand.Init`**

In `cli/src/main/kotlin/com/droidagentkit/cli/CliCommand.kt`, add before the closing `data class Help(...)`:

```kotlin
    data class Init(
        val profiles: List<String>,
        val force: Boolean,
        val listProfiles: Boolean,
        val project: String,
    ) : CliCommand()

```

- [ ] **Step 4: Register the command in `CliCommandSpec.kt`**

In `cli/src/main/kotlin/com/droidagentkit/cli/CliCommandSpec.kt`, add to the `CliCommandRegistry.all` list (after the `"install-mcp"` entry, before the closing `)`):

```kotlin
            CliCommandSpec(
                "init",
                "Generate .droidagentkit/config.yaml interactively or from a named profile.",
                listOf(
                    CliOption(
                        "--profile",
                        "Comma-separated profile name(s): core, device-control, full, storage, network-experimental.",
                    ),
                    CliOption("--force", "Overwrite an existing config.yaml.", takesValue = false),
                    CliOption("--list-profiles", "Print available profiles and exit.", takesValue = false),
                    CliOption("--project", "Project root path. Defaults to cwd."),
                ),
            ),
```

- [ ] **Step 5: Parse the command in `DroidAgentCliParser.kt`**

In `cli/src/main/kotlin/com/droidagentkit/cli/DroidAgentCliParser.kt`, add a branch to the `when (commandName)` block (after the `"install-mcp" ->` branch, before `else ->`):

```kotlin
            "init" ->
                CliCommand.Init(
                    profiles =
                        (options["profile"] ?: "")
                            .split(',')
                            .map { it.trim() }
                            .filter { it.isNotBlank() },
                    force = options.containsKey("force"),
                    listProfiles = options.containsKey("list-profiles"),
                    project = options["project"] ?: ".",
                )
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew :cli:test --tests "com.droidagentkit.cli.CliParserTest"`
Expected: PASS, all tests including the 4 new ones.

- [ ] **Step 7: Commit**

```bash
git add cli/src/main/kotlin/com/droidagentkit/cli/CliCommand.kt cli/src/main/kotlin/com/droidagentkit/cli/CliCommandSpec.kt cli/src/main/kotlin/com/droidagentkit/cli/DroidAgentCliParser.kt cli/src/test/kotlin/com/droidagentkit/cli/CliParserTest.kt
git commit -m "feat: add init command to CLI parser and registry"
```

---

### Task 6: `DroidAgentMain.init()` wiring + docs

**Files:**
- Modify: `cli/src/main/kotlin/com/droidagentkit/cli/DroidAgentMain.kt`
- Test: `cli/src/test/kotlin/com/droidagentkit/cli/InitCommandTest.kt` (new)
- Modify: `README.md`
- Modify: `docs/security-and-permissions.md`

**Interfaces:**
- Consumes: `ProfileCatalog.expand/names` (Task 3), `InitWizard` (Task 4), `ConfigYaml.render` (Task 1), `CliCommand.Init` (Task 5).

This is the integration task: everything built in Tasks 1-5 gets wired together behind the `init` command, and is the first point where the feature is actually runnable end-to-end.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.droidagentkit.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class InitCommandTest {
    @Test
    fun `init with a profile writes the expected config without prompting`() {
        val root = Files.createTempDirectory("dak-init-profile")

        val exitCode = DroidAgentCli().run(arrayOf("init", "--project", root.toString(), "--profile", "storage"))

        assertEquals(0, exitCode)
        val yaml = Files.readString(root.resolve(".droidagentkit/config.yaml"))
        assertTrue(yaml.contains("app_data_read"))
        assertTrue(yaml.contains("storage"))
    }

    @Test
    fun `init refuses to overwrite an existing config without force`() {
        val root = Files.createTempDirectory("dak-init-refuse")
        val configPath = root.resolve(".droidagentkit/config.yaml")
        Files.createDirectories(configPath.parent)
        Files.writeString(configPath, "schemaVersion: 1\n")

        val exitCode = DroidAgentCli().run(arrayOf("init", "--project", root.toString(), "--profile", "full"))

        assertEquals(1, exitCode)
        assertEquals("schemaVersion: 1\n", Files.readString(configPath))
    }

    @Test
    fun `init with force overwrites an existing config`() {
        val root = Files.createTempDirectory("dak-init-force")
        val configPath = root.resolve(".droidagentkit/config.yaml")
        Files.createDirectories(configPath.parent)
        Files.writeString(configPath, "schemaVersion: 1\n")

        val exitCode = DroidAgentCli().run(arrayOf("init", "--project", root.toString(), "--profile", "storage", "--force"))

        assertEquals(0, exitCode)
        assertTrue(Files.readString(configPath).contains("app_data_read"))
    }

    @Test
    fun `init rejects an unknown profile name and lists valid ones`() {
        val root = Files.createTempDirectory("dak-init-unknown-profile")

        val exitCode = DroidAgentCli().run(arrayOf("init", "--project", root.toString(), "--profile", "not-a-profile"))

        assertEquals(1, exitCode)
        assertFalse(Files.exists(root.resolve(".droidagentkit/config.yaml")))
    }

    @Test
    fun `init list-profiles exits zero and writes nothing, even over an existing config`() {
        val root = Files.createTempDirectory("dak-init-list")
        val configPath = root.resolve(".droidagentkit/config.yaml")
        Files.createDirectories(configPath.parent)
        Files.writeString(configPath, "schemaVersion: 1\n")

        val exitCode = DroidAgentCli().run(arrayOf("init", "--project", root.toString(), "--list-profiles"))

        assertEquals(0, exitCode)
        assertEquals("schemaVersion: 1\n", Files.readString(configPath))
    }

    @Test
    fun `init with no profile and no terminal exits non-zero without writing`() {
        // Gradle's test JVM has no attached console, so System.console() is reliably null here —
        // this exercises the real no-TTY guard, not a fake.
        val root = Files.createTempDirectory("dak-init-no-tty")

        val exitCode = DroidAgentCli().run(arrayOf("init", "--project", root.toString()))

        assertEquals(1, exitCode)
        assertFalse(Files.exists(root.resolve(".droidagentkit/config.yaml")))
    }

    @Test
    fun `combining two profiles writes the union`() {
        val root = Files.createTempDirectory("dak-init-combo")

        val exitCode =
            DroidAgentCli().run(arrayOf("init", "--project", root.toString(), "--profile", "storage,network-experimental"))

        assertEquals(0, exitCode)
        val yaml = Files.readString(root.resolve(".droidagentkit/config.yaml"))
        assertTrue(yaml.contains("app_data_read"))
        assertTrue(yaml.contains("network_interception"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :cli:test --tests "com.droidagentkit.cli.InitCommandTest"`
Expected: FAIL — `when` in `DroidAgentCli.run()` is not exhaustive (`CliCommand.Init` unhandled), compile error.

- [ ] **Step 3: Add the `init` branch to `DroidAgentCli.run()`**

In `cli/src/main/kotlin/com/droidagentkit/cli/DroidAgentMain.kt`, add to the `when (val command = parser.parse(args))` block in `run()` (after `is CliCommand.InstallMcp -> installMcp(command)`):

```kotlin
            is CliCommand.Init -> init(command)
```

- [ ] **Step 4: Implement the `init` handler**

Add these imports at the top of `DroidAgentMain.kt` (alongside the existing `com.droidagentkit.*` imports):

```kotlin
import com.droidagentkit.core.ConfigYaml
```

Add this private method to the `DroidAgentCli` class (near `installMcp`):

```kotlin
    private fun init(command: CliCommand.Init): Int {
        if (command.listProfiles) {
            println(profileListing())
            return 0
        }
        val root = Path.of(command.project).toAbsolutePath().normalize()
        val configPath = root.resolve(".droidagentkit/config.yaml")
        if (Files.exists(configPath) && !command.force) {
            System.err.println("$configPath already exists. Rerun with --force to regenerate it.")
            return 1
        }

        val expansion: ProfileExpansion
        if (command.profiles.isNotEmpty()) {
            val result = ProfileCatalog.expand(command.profiles)
            if (result.isFailure) {
                System.err.println("Unknown profile(s): ${result.exceptionOrNull()?.message}")
                println(profileListing())
                return 1
            }
            expansion = result.getOrThrow()
        } else {
            if (System.console() == null) {
                System.err.println(
                    "No terminal detected and no --profile given. Run 'droidagent init --list-profiles' to see " +
                        "options, or 'droidagent init --profile <name>'.",
                )
                return 1
            }
            val wizardResult = InitWizard(readLine = ::readlnOrNull, print = ::println).run()
            if (wizardResult == null) {
                println("Aborted, no file written.")
                return 0
            }
            expansion = wizardResult
        }

        Files.createDirectories(configPath.parent)
        Files.writeString(configPath, ConfigYaml.render(expansion.groups, expansion.capabilities))
        println("Wrote $configPath")
        return 0
    }

    private fun profileListing(): String =
        buildString {
            appendLine("Available profiles:")
            ProfileCatalog.names().forEach { appendLine("  $it") }
        }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :cli:test --tests "com.droidagentkit.cli.InitCommandTest"`
Expected: PASS, all 7 tests.

- [ ] **Step 6: Run the full test suite**

Run: `./gradlew test`
Expected: PASS, every module — this is the first point where a regression in `CliCommandRegistry`'s validation (e.g. `init` accidentally breaking `--help` generation) would surface across the whole CLI test suite.

- [ ] **Step 7: Document the command**

In `README.md`, add a short section near the existing CLI usage examples (mirror the style of the existing `install-mcp` bullet):

```markdown
### Generating a config file

```bash
droidagent init                          # interactive: six yes/no prompts explaining risk per area
droidagent init --profile device-control # non-interactive, for scripted setup
droidagent init --profile full           # everything, including storage and network capture
droidagent init --list-profiles          # see all profile names without writing anything
```

Only the `core` tool group (safe, read-only) is enabled by default. `droidagent init` is the fastest way to
turn on more without hand-writing `.droidagentkit/config.yaml` — see
[docs/security-and-permissions.md](docs/security-and-permissions.md) for what each group and capability
actually grants an agent.
```

In `docs/security-and-permissions.md`, add a new section right after the "Default `.droidagentkit/config.yaml`" YAML block (before "## MCP Tools"):

```markdown
## Generating config with `droidagent init`

`droidagent init` generates `.droidagentkit/config.yaml` without hand-writing `ToolGroup`/`Capability` enum
names. Run it with no flags in a terminal for six yes/no prompts (one per tool group below, each explaining
the risk in plain language, with follow-ups for bugreport capture, irreversible device-control actions, and
golden-image overwrites). For scripted/CI setup, use a named profile instead:

| Profile | Enables |
|---|---|
| `core` | Nothing extra (the default). |
| `device-control` | Device diagnostics (excluding bugreport) + device control (excluding uninstall/clear-data/permission mutation). |
| `full` | Everything below, including `storage` and `network-experimental`. |
| `storage` | Read-only SQLite/SharedPreferences inspection for a debuggable app, alone. |
| `network-experimental` | Emulator-only mitmproxy interception, alone. |

`--profile` accepts a comma-separated list (e.g. `--profile device-control,storage`). `init` refuses to
overwrite an existing `config.yaml` unless `--force` is passed. The generated file is always fully explicit
— literal group/capability names, never a reference to the profile that produced it — so it stays reviewable
without needing to know what a profile currently expands to.
```

- [ ] **Step 8: Commit**

```bash
git add cli/src/main/kotlin/com/droidagentkit/cli/DroidAgentMain.kt cli/src/test/kotlin/com/droidagentkit/cli/InitCommandTest.kt README.md docs/security-and-permissions.md
git commit -m "feat: wire droidagent init command end-to-end, document profiles"
```

---

## Final verification

`System.console() == null` is always true inside Gradle's test JVM, so `InitCommandTest` can only exercise
the no-TTY guard, never the real interactive path end-to-end — `InitWizard`'s prompt/accumulation logic is
covered by `InitWizardTest` in isolation instead. The manual checks below are the only place the full
`DroidAgentMain.init()` wiring around a live terminal session gets exercised, in particular declining the
final write-confirmation, which no automated test reaches.

- [ ] Run `./gradlew test` once more from a clean state and confirm every module passes.
- [ ] Run `./gradlew :cli:installDist` and manually run `./cli/build/install/droidagent/bin/droidagent init --list-profiles` to sanity-check real output formatting outside the test harness.
- [ ] Manually run `./cli/build/install/droidagent/bin/droidagent init --project /tmp/some-dir --profile full` and inspect the written YAML by eye.
- [ ] Manually run `./cli/build/install/droidagent/bin/droidagent init --project /tmp/some-dir-2` in a real terminal, answer "n" to all six prompts, and confirm the final summary shows "(none)" for both groups and capabilities.
- [ ] In the same manual run, when asked "Write this to .droidagentkit/config.yaml? [y/N]", answer "n" and confirm it prints "Aborted, no file written.", exits 0, and no `.droidagentkit/config.yaml` was created at `/tmp/some-dir-2`.
- [ ] Run it again and answer "y" at the final confirmation; confirm the file is written and its content matches what the summary line promised.
