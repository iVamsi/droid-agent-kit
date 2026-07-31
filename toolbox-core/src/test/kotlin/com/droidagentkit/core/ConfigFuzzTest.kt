package com.droidagentkit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.random.Random

/**
 * Property-ish fuzz: malformed YAML must never hang or throw out of the loader — Invalid or Loaded only.
 */
class ConfigFuzzTest {
    @Test
    fun `random YAML lines never crash the loader`() {
        val rng = Random(42)
        repeat(200) {
            val dir = Files.createTempDirectory("dak-fuzz-config")
            val config = dir.resolve(".droidagentkit/config.yaml")
            Files.createDirectories(config.parent)
            val lines =
                buildList {
                    add("schemaVersion: 1")
                    repeat(rng.nextInt(0, 12)) {
                        add(randomLine(rng))
                    }
                }
            Files.writeString(config, lines.joinToString("\n"))
            val result = DroidAgentConfigLoader.load(dir)
            assertTrue(result is ConfigLoadResult.Loaded || result is ConfigLoadResult.Invalid)
        }
    }

    /**
     * The property that actually matters: whatever a project file says, the effective config is
     * never more permissive than the user policy along any axis.
     *
     * Crash-freedom (above) is the weaker guarantee, and it held even while a project file could
     * widen the Gradle allowlist to `:*:*` and grant itself arbitrary task execution. This asserts
     * the invariant directly rather than checking specific known-bad inputs.
     */
    @Test
    fun `no project config is ever more permissive than the policy`() {
        val rng = Random(1337)
        repeat(300) { iteration ->
            val dir = Files.createTempDirectory("dak-fuzz-trust")
            val config = dir.resolve(".droidagentkit/config.yaml")
            Files.createDirectories(config.parent)
            Files.writeString(
                config,
                buildList {
                    add("schemaVersion: 1")
                    repeat(rng.nextInt(0, 14)) { add(hostileLine(rng)) }
                }.joinToString("\n"),
            )

            val policyPath = Files.createTempFile("dak-fuzz-policy", ".yaml")
            Files.writeString(policyPath, randomPolicy(rng))

            val effective = DroidAgentConfigLoader.loadEffective(dir, policyPath)
            if (effective !is ConfigLoadResult.Loaded) return@repeat
            val policy = DroidAgentConfigLoader.loadUserPolicy(policyPath)
            if (policy !is ConfigLoadResult.Loaded) return@repeat

            val got = effective.config
            val allowed = policy.config
            val where = "iteration $iteration"

            // Grants come only from the policy.
            assertEquals(where, allowed.safety.allowCapabilities, got.safety.allowCapabilities)
            assertEquals(where, allowed.mcp.exposedGroups, got.mcp.exposedGroups)
            assertEquals(where, allowed.safety.adbPath, got.safety.adbPath)
            assertEquals(where, allowed.safety.emulatorPath, got.safety.emulatorPath)
            assertEquals(where, allowed.redaction.enabled, got.redaction.enabled)
            assertEquals(where, allowed.safety.allowAnyGradleTask, got.safety.allowAnyGradleTask)
            assertEquals(where, allowed.safety.allowEmulatorStart, got.safety.allowEmulatorStart)
            assertEquals(where, allowed.safety.allowAdbInput, got.safety.allowAdbInput)

            // Bounds may only tighten.
            assertTrue(
                "$where: timeout ${got.safety.maxCommandSeconds} > policy ${allowed.safety.maxCommandSeconds}",
                got.safety.maxCommandSeconds <= allowed.safety.maxCommandSeconds,
            )
            if (!allowed.safety.allowAppInstall) {
                assertTrue("$where: app install re-enabled", !got.safety.allowAppInstall)
            }

            // Every surviving task pattern must be covered by one the policy already allows.
            got.safety.allowGradleTasks.forEach { pattern ->
                assertTrue(
                    "$where: '$pattern' is broader than the policy allows",
                    allowed.safety.allowGradleTasks.any { DroidAgentConfigLoader.globSubsumes(it, pattern) },
                )
            }
        }
    }

    private fun randomPolicy(rng: Random): String =
        buildList {
            add("schemaVersion: 1")
            add("safety:")
            if (rng.nextBoolean()) add("  maxCommandSeconds: ${rng.nextInt(1, 900)}")
            if (rng.nextBoolean()) add("  allowAppInstall: false")
            if (rng.nextBoolean()) {
                add("  allowGradleTasks:")
                add("    - \":*:test*UnitTest\"")
                if (rng.nextBoolean()) add("    - \":*:assemble*Debug\"")
            }
            if (rng.nextBoolean()) {
                add("  allowCapabilities:")
                add("    - app_data_read")
            }
        }.joinToString("\n")

    private fun hostileLine(rng: Random): String {
        val lines =
            listOf(
                "safety:",
                "  allowGradleTasks:",
                "    - \":*:*\"",
                "    - \"*\"",
                "    - \"**\"",
                "    - \"*publish*\"",
                "    - \":*:test*UnitTest\"",
                "    - \":app:${rng.nextInt()}\"",
                "  allowAnyGradleTask: true",
                "  allowAdbInput: true",
                "  allowEmulatorStart: true",
                "  allowAppInstall: true",
                "  maxCommandSeconds: ${rng.nextInt(900, 100000)}",
                "  adbPath: /tmp/evil-adb",
                "  emulatorPath: /tmp/evil-emulator",
                "  allowCapabilities:",
                "    - app_destructive",
                "    - network_interception",
                "mcp:",
                "  exposedGroups:",
                "    - device_control",
                "    - network_experimental",
                "redaction:",
                "  enabled: false",
            )
        return lines[rng.nextInt(lines.size)]
    }

    private fun randomLine(rng: Random): String {
        val keys =
            listOf(
                "project:",
                "  name: ${rng.nextInt()}",
                "safety:",
                "  allowGradleTasks:",
                "    - \":app:${rng.nextInt()}\"",
                "    - \"*\"",
                "  allowCapabilities:",
                "    - app_destructive",
                "  allowAppInstall: ${if (rng.nextBoolean()) "true" else "maybe"}",
                "  maxCommandSeconds: ${if (rng.nextBoolean()) rng.nextInt().toString() else "soon"}",
                "redaction:",
                "  enabled: false",
                "  extraPatterns:",
                "    - \"(a+)+\"",
                "    - \"PRIVATE_[A-Z]+\"",
                "mcp:",
                "  exposedGroups:",
                "    - device_control",
                "bogus: ${rng.nextInt()}",
                "# comment ${rng.nextInt()}",
                "",
            )
        return keys[rng.nextInt(keys.size)]
    }
}
