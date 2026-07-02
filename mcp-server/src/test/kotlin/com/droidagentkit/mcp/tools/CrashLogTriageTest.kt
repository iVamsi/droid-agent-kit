package com.droidagentkit.mcp.tools

import com.droidagentkit.core.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLogTriageTest {
    @Test
    fun `extracts fatal exception block with thread and stack frames`() {
        val logcat = """
            07-01 10:00:00.000  1234  1234 I ActivityManager: Start proc
            07-01 10:00:01.000  1234  1234 E AndroidRuntime: FATAL EXCEPTION: main
            07-01 10:00:01.001  1234  1234 E AndroidRuntime: java.lang.NullPointerException: name must not be null
            07-01 10:00:01.002  1234  1234 E AndroidRuntime: 	at com.example.MainActivity.onCreate(MainActivity.kt:42)
            07-01 10:00:01.003  1234  1234 E AndroidRuntime: 	at android.app.Activity.performCreate(Activity.java:8000)
            07-01 10:00:01.004  1234  1234 I ActivityManager: Process com.example died
        """.trimIndent()

        val findings = CrashLogTriage.triage(logcat)

        assertEquals(1, findings.size)
        assertEquals("crash", findings[0].category)
        assertEquals(Severity.CRITICAL, findings[0].severity)
        assertEquals("main", findings[0].location)
        assertTrue(findings[0].detail.contains("NullPointerException"))
        assertTrue(findings[0].detail.contains("MainActivity.kt:42"))
    }

    @Test
    fun `extracts anr block with package name`() {
        val logcat = """
            07-01 10:00:00.000  1234  1234 I ActivityManager: ANR in com.example.app
            07-01 10:00:00.001  1234  1234 I ActivityManager: Reason: Input dispatching timed out (waiting to send key event)
        """.trimIndent()

        val findings = CrashLogTriage.triage(logcat)

        assertEquals(1, findings.size)
        assertEquals("anr", findings[0].category)
        assertEquals(Severity.CRITICAL, findings[0].severity)
        assertEquals("com.example.app", findings[0].location)
    }

    @Test
    fun `extracts input dispatching timeout without an explicit anr in line`() {
        val logcat = "07-01 10:00:00.000  1234  1234 W InputDispatcher: Input dispatching timed out (Waiting because no window)"

        val findings = CrashLogTriage.triage(logcat)

        assertEquals(1, findings.size)
        assertEquals("anr", findings[0].category)
    }

    @Test
    fun `returns empty list when no crash or anr patterns are present`() {
        val logcat = """
            07-01 10:00:00.000  1234  1234 I ActivityManager: Start proc
            07-01 10:00:01.000  1234  1234 D MyApp: onCreate called
        """.trimIndent()

        val findings = CrashLogTriage.triage(logcat)

        assertTrue(findings.isEmpty())
    }
}
