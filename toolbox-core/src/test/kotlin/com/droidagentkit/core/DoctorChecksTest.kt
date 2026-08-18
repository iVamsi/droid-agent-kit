package com.droidagentkit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * The doctor exists to turn "it didn't work" into a specific, actionable line. Two properties
 * matter more than the individual checks: a missing *optional* tool must never be reported as a
 * failure (most users never enable perfetto or mitmproxy), and a broken *policy* must never be
 * reported as merely a warning, because that is what silently decides authority.
 */
class DoctorChecksTest {
    private fun checks(
        binaries: Map<String, String?> = emptyMap(),
        env: Map<String, String> = emptyMap(),
        javaVersion: String = "17.0.9",
    ) = DoctorChecks(
        probe = { command -> binaries[command.first()] },
        env = { name -> env[name] },
        javaVersion = javaVersion,
    )

    private fun project(vararg lines: String): Path {
        val dir = Files.createTempDirectory("dak-doctor-project")
        if (lines.isNotEmpty()) {
            Files.createDirectories(dir.resolve(".droidagentkit"))
            Files.writeString(dir.resolve(".droidagentkit/config.yaml"), lines.joinToString("\n"))
        }
        return dir
    }

    private fun policy(vararg lines: String): Path {
        val dir = Files.createTempDirectory("dak-doctor-policy")
        val path = dir.resolve("policy.yaml")
        if (lines.isNotEmpty()) Files.writeString(path, lines.joinToString("\n"))
        return path
    }

    private fun DoctorReport.check(name: String): DoctorCheck =
        checks.firstOrNull { it.name == name } ?: error("no check named '$name' in ${checks.map { it.name }}")

    @Test
    fun `a healthy environment passes`() {
        val report =
            checks(binaries = mapOf("adb" to "Android Debug Bridge version 1.0.41"))
                .run(project(), policy())

        assertEquals(CheckStatus.OK, report.check("java").status)
        assertEquals(CheckStatus.OK, report.check("adb").status)
        assertTrue("healthy environment should not fail", report.ok)
    }

    @Test
    fun `a missing adb warns rather than fails`() {
        // Plenty of real use is project-only: inspect, audit, lint, crash triage. Failing the
        // whole doctor because no device tooling is installed would train people to ignore it.
        val report = checks().run(project(), policy())

        assertEquals(CheckStatus.WARN, report.check("adb").status)
        assertTrue("missing adb is not fatal", report.ok)
        assertTrue(
            "a warning must say what to do about it",
            report.check("adb").remedy!!.contains("platform-tools"),
        )
    }

    @Test
    fun `a java older than 17 fails`() {
        val report = checks(javaVersion = "11.0.20").run(project(), policy())

        assertEquals(CheckStatus.FAIL, report.check("java").status)
        assertFalse(report.ok)
    }

    @Test
    fun `an unparseable user policy fails and names the line`() {
        // The policy is the only place grants come from. If it does not parse, the server falls
        // back to defaults, quietly running with less authority than the user believes.
        val report =
            checks().run(
                project(),
                policy("schemaVersion: 1", "safety:", "  maxCommandSeconds: not-a-number"),
            )

        val policyCheck = report.check("user policy")
        assertEquals(CheckStatus.FAIL, policyCheck.status)
        assertTrue("should quote the offending line: ${policyCheck.detail}", policyCheck.detail.contains("3"))
        assertFalse(report.ok)
    }

    @Test
    fun `a project config that tries to escalate is surfaced as a warning`() {
        val report =
            checks().run(
                project(
                    "schemaVersion: 1",
                    "safety:",
                    "  allowCapabilities:",
                    "    - app_destructive",
                ),
                policy(),
            )

        val projectCheck = report.check("project config")
        assertEquals(CheckStatus.WARN, projectCheck.status)
        assertTrue(
            "should explain the key was ignored: ${projectCheck.detail}",
            projectCheck.detail.contains("allowCapabilities"),
        )
    }

    @Test
    fun `optional tools are only checked when their group is enabled`() {
        val withoutPerfetto = checks().run(project(), policy())
        assertTrue(
            "trace_processor should not be reported when perfetto is off",
            withoutPerfetto.checks.none { it.name == "trace_processor" },
        )

        val withPerfetto =
            checks().run(
                project(),
                policy("schemaVersion: 1", "mcp:", "  exposedGroups:", "    - perfetto"),
            )
        assertEquals(CheckStatus.WARN, withPerfetto.check("trace_processor").status)
        assertTrue("still not fatal", withPerfetto.ok)
    }

    @Test
    fun `enabled groups and capabilities are reported for confirmation`() {
        val report =
            checks().run(
                project(),
                policy(
                    "schemaVersion: 1",
                    "safety:",
                    "  allowCapabilities:",
                    "    - device_input",
                    "mcp:",
                    "  exposedGroups:",
                    "    - device_control",
                ),
            )

        assertTrue(report.check("tool groups").detail.contains("device_control"))
        assertTrue(report.check("capabilities").detail.contains("device_input"))
    }

    @Test
    fun `an unwritable artifact directory fails`() {
        val root = project()
        // A regular file where the artifact directory must go: writes cannot succeed, and the
        // failure would otherwise only surface mid-tool-call.
        Files.createDirectories(root.resolve("build"))
        Files.writeString(root.resolve("build/droidagentkit"), "not a directory")

        val report = checks().run(root, policy())

        assertEquals(CheckStatus.FAIL, report.check("artifact directory").status)
        assertFalse(report.ok)
    }
}
