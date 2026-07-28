package com.droidagentkit.core

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
