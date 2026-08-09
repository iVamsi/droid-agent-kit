package com.droidagentkit.mcp.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetraceEngineTest {
    private val mappingText =
        """
        # compiler: R8
        com.example.app.LoginPresenter -> a.b.c:
            java.lang.String token -> a
            1:5:void onSubmit(java.lang.String):42:46 -> a
            6:9:boolean validate(java.lang.String):80:83 -> a
            void logout() -> b
        com.example.app.NetworkError -> a.b.d:
        com.example.app.Outer${'$'}Inner -> a.b.e:
            1:1:void tick():12:12 -> a
        """.trimIndent()

    private val mapping = RetraceEngine.parse(mappingText)

    @Test
    fun `parses every class in the mapping`() {
        assertEquals(3, mapping.size)
    }

    @Test
    fun `maps an obfuscated frame back to its original symbol and line`() {
        val trace = "\tat a.b.c.a(SourceFile:3)"

        val result = RetraceEngine.retrace(trace, mapping)

        // Obfuscated line 3 sits in the 1:5 range, so the original line is 42 + (3 - 1) = 44.
        assertEquals("\tat com.example.app.LoginPresenter.onSubmit(LoginPresenter.java:44)", result)
    }

    @Test
    fun `picks the right overload when two methods share an obfuscated name`() {
        // Both onSubmit and validate collapse to `a`; only the line range distinguishes them.
        val result = RetraceEngine.retrace("\tat a.b.c.a(SourceFile:7)", mapping)

        assertTrue("expected validate, got: $result", result.contains("validate"))
        assertTrue("line should map into the 80..83 range: $result", result.contains(":81"))
    }

    @Test
    fun `leaves a frame it cannot map exactly as it was`() {
        // A wrong symbol is worse than an obfuscated one: it sends the reader somewhere real that
        // had nothing to do with the crash.
        val trace = "\tat com.unknown.Thing.method(Thing.java:1)"

        assertEquals(trace, RetraceEngine.retrace(trace, mapping))
    }

    @Test
    fun `deobfuscates the exception header and Caused by lines`() {
        val trace =
            """
            a.b.d: connection reset
            ${'\t'}at a.b.c.a(SourceFile:3)
            Caused by: a.b.d
            """.trimIndent()

        val result = RetraceEngine.retrace(trace, mapping)

        assertTrue("header should be readable: $result", result.startsWith("com.example.app.NetworkError: connection reset"))
        assertTrue("caused-by should be readable: $result", result.contains("Caused by: com.example.app.NetworkError"))
    }

    @Test
    fun `handles inner classes and derives the source file from the outer class`() {
        val result = RetraceEngine.retrace("\tat a.b.e.a(SourceFile:1)", mapping)

        assertEquals("\tat com.example.app.Outer${'$'}Inner.tick(Outer.java:12)", result)
    }

    @Test
    fun `a method with no line information still gets its name back`() {
        val result = RetraceEngine.retrace("\tat a.b.c.b(Unknown Source)", mapping)

        assertTrue("expected logout, got: $result", result.contains("logout"))
    }

    @Test
    fun `non-frame lines pass through untouched`() {
        val trace = "FATAL EXCEPTION: main\nProcess: com.example.app, PID: 1234"

        assertEquals(trace, RetraceEngine.retrace(trace, mapping))
    }

    @Test
    fun `an empty mapping changes nothing`() {
        val trace = "\tat a.b.c.a(SourceFile:3)"

        assertEquals(trace, RetraceEngine.retrace(trace, RetraceEngine.parse("")))
    }

    @Test
    fun `fields are ignored since they never appear in stack frames`() {
        // `java.lang.String token -> a` must not be mistaken for a method named `a`.
        val result = RetraceEngine.retrace("\tat a.b.c.a(SourceFile:3)", mapping)

        assertTrue("should resolve to a method, not the field: $result", result.contains("onSubmit"))
    }

    @Test
    fun `retrace is applied end to end through the crash triage tool`() {
        // The engine being right does not prove it is reachable; this drives the actual tool.
        val root =
            java.nio.file.Files
                .createTempDirectory("dak-retrace-tool")
        val mappingDir = root.resolve("build/outputs/mapping/release")
        java.nio.file.Files
            .createDirectories(mappingDir)
        java.nio.file.Files
            .writeString(mappingDir.resolve("mapping.txt"), mappingText)

        val fakeAdb = root.resolve("adb")
        java.nio.file.Files.writeString(
            fakeAdb,
            "#!/bin/sh\n" +
                "echo 'FATAL EXCEPTION: main'\n" +
                "echo '\tat a.b.c.a(SourceFile:3)'\n",
        )
        com.droidagentkit.mcp.assumePosixFilesystem()
        java.nio.file.Files.setPosixFilePermissions(
            fakeAdb,
            java.nio.file.attribute.PosixFilePermissions
                .fromString("rwxr-xr-x"),
        )

        val base =
            com.droidagentkit.core.DroidAgentConfig
                .default()
        val config = base.copy(safety = base.safety.copy(adbPath = fakeAdb.toString()))
        val dispatcher = com.droidagentkit.mcp.DroidAgentMcpDispatcher(config, root)

        val result =
            dispatcher.call(
                "android_crash_triage",
                mapOf("rootPath" to root.toString(), "deviceSerial" to "emulator-5554"),
            )

        val summary = result["summary"].toString()
        assertTrue("mapping should be auto-discovered and reported: $summary", summary.contains("de-obfuscated"))
    }
}
