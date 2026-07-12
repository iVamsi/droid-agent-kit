package com.droidagentkit.mcp.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class TestResultParserTest {
    @Test
    fun `parses deterministic junit summaries and bounded failures`() {
        val root = Files.createTempDirectory("dak-junit")
        val output = root.resolve("app/build/test-results/testDebugUnitTest")
        Files.createDirectories(output)
        Files.writeString(
            output.resolve("TEST-com.example.WidgetTest.xml"),
            """
            <testsuite tests="3" failures="1" errors="0" skipped="1" time="1.25">
              <testcase classname="com.example.WidgetTest" name="passes"/>
              <testcase classname="com.example.WidgetTest" name="fails">
                <failure message="expected true but was false">stack trace</failure>
              </testcase>
              <testcase classname="com.example.WidgetTest" name="skips"><skipped/></testcase>
            </testsuite>
            """.trimIndent(),
        )

        val result = TestResultParser.parse(root)

        assertEquals(3, result.summary.tests)
        assertEquals(1, result.summary.failures)
        assertEquals(1, result.summary.skipped)
        assertEquals(1, result.findings.size)
        assertEquals("test.failure", result.findings.single().category)
        assertTrue(
            result.findings
                .single()
                .location!!
                .endsWith("TEST-com.example.WidgetTest.xml"),
        )
    }

    @Test(expected = org.xml.sax.SAXParseException::class)
    fun `rejects junit documents with doctypes`() {
        val root = Files.createTempDirectory("dak-junit-xxe")
        val output = root.resolve("build/test-results/test")
        Files.createDirectories(output)
        Files.writeString(output.resolve("TEST-bad.xml"), "<!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]><testsuite/>")

        TestResultParser.parse(root)
    }
}
