package com.droidagentkit.device

import com.droidagentkit.core.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionAuditParserTest {
    private val pkg = "com.example.app"

    @Test
    fun `parses runtime and install permissions with grant state`() {
        val dump =
            """
            Package [$pkg] (id=1)
              install permissions:
                android.permission.INTERNET: granted=true
              runtime permissions:
                android.permission.ACCESS_FINE_LOCATION: granted=true, flags=[ ... ]
                android.permission.CAMERA: granted=false, flags=[ ... ]
            """.trimIndent()

        val result = PermissionAuditParser.parse(pkg, dump)

        assertEquals(3, result.entries.size)
        val camera = result.entries.first { it.name == "android.permission.CAMERA" }
        assertEquals(false, camera.granted)
        assertTrue(camera.runtime)
        val internet = result.entries.first { it.name == "android.permission.INTERNET" }
        assertTrue(internet.granted)
        assertTrue(!internet.runtime)
        assertTrue(result.findings.any { it.title == "runtime-permission-not-granted" && it.location == "android.permission.CAMERA" })
    }

    @Test
    fun `warns when package is not mentioned in dump`() {
        val result = PermissionAuditParser.parse(pkg, "Package [other.pkg] (id=2)")
        assertTrue(result.entries.isEmpty())
        assertTrue(result.findings.any { it.title == "package-not-found" })
    }

    @Test
    fun `warns on empty dump output`() {
        val result = PermissionAuditParser.parse(pkg, "")
        assertTrue(result.findings.any { it.title == "empty-package-dump" })
    }

    @Test
    fun `warns when no permission section is parsed`() {
        val dump = "Package [$pkg] (id=1)\n  some unrelated line"
        val result = PermissionAuditParser.parse(pkg, dump)
        assertTrue(result.findings.any { it.title == "no-permission-section" })
    }
}

class DumpsysSummaryParserTest {
    private val serial = "emulator-5554"

    @Test
    fun `meminfo summary extracts total free and used ram`() {
        val dump =
            """
            Total RAM: 1,234,567 KB
            Free RAM: 123,456 KB
            Used RAM: 1,111,111 KB
            """.trimIndent()

        val summary = DumpsysSummaryParser.parse(DumpsysPreset.MEMINFO, serial, dump)
        assertEquals(1234567L, summary.summary["totalRamKb"])
        assertEquals(123456L, summary.summary["freeRamKb"])
        assertEquals(1111111L, summary.summary["usedRamKb"])
        assertEquals(serial, summary.provenance["deviceSerial"])
    }

    @Test
    fun `gfxinfo summary extracts frame counts`() {
        val dump =
            """
            Total frames rendered: 1234
            Janky frames: 56 (4.5%)
            Number of slow renders: 12
            """.trimIndent()

        val summary = DumpsysSummaryParser.parse(DumpsysPreset.GFXINFO, serial, dump)
        assertEquals(1234, summary.summary["totalFrames"])
        assertEquals(56, summary.summary["jankyFrames"])
        assertEquals(12, summary.summary["slowRenders"])
    }

    @Test
    fun `battery summary extracts level status health temperature voltage`() {
        val dump =
            """
            level: 42
            scale: 100
            status: Discharging
            health: Good
            temperature: 250
            voltage: 4200
            technology: Li-ion
            """.trimIndent()

        val summary = DumpsysSummaryParser.parseBattery(serial, dump)
        assertEquals(42, summary.summary["level"])
        assertEquals("Discharging", summary.summary["status"])
        assertEquals("Good", summary.summary["health"])
        assertEquals(250, summary.summary["temperatureTenthsCelsius"])
        assertEquals(4200, summary.summary["voltageMillivolts"])
    }

    @Test
    fun `empty dumpsys output produces a warning finding`() {
        val summary = DumpsysSummaryParser.parse(DumpsysPreset.MEMINFO, serial, "")
        assertTrue(summary.findings.any { it.title == "empty-dumpsys" && it.severity == Severity.WARNING })
    }

    @Test
    fun `package preset returns a note pointing at the permission audit`() {
        val dump = "Package [$serial]"
        val summary = DumpsysSummaryParser.parse(DumpsysPreset.PACKAGE, serial, dump, packageName = "com.example")
        assertTrue(summary.summary["note"].toString().contains("android_permission_audit"))
    }
}
