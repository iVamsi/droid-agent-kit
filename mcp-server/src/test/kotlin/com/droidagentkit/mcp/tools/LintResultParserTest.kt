package com.droidagentkit.mcp.tools

import com.droidagentkit.core.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LintResultParserTest {
    @Test
    fun `parses android lint xml issues with severity and location`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <issues format="6" by="lint 8.5.0">
                <issue
                    id="HardcodedText"
                    severity="Warning"
                    message="Hardcoded string &quot;Submit&quot;, should use `@string` resource"
                    category="Internationalization">
                    <location
                        file="src/main/res/layout/activity_main.xml"
                        line="12"
                        column="9"/>
                </issue>
                <issue
                    id="UnusedResources"
                    severity="Error"
                    message="The resource `R.string.old_label` appears to be unused"
                    category="Performance">
                    <location
                        file="src/main/res/values/strings.xml"
                        line="45"/>
                </issue>
            </issues>
        """.trimIndent()

        val findings = LintResultParser.parseAndroidLintXml(xml)

        assertEquals(2, findings.size)
        assertEquals("HardcodedText", findings[0].title)
        assertEquals(Severity.WARNING, findings[0].severity)
        assertEquals("src/main/res/layout/activity_main.xml:12", findings[0].location)
        assertEquals("UnusedResources", findings[1].title)
        assertEquals(Severity.ERROR, findings[1].severity)
    }

    @Test
    fun `returns empty list for malformed android lint xml`() {
        val findings = LintResultParser.parseAndroidLintXml("not xml at all")

        assertTrue(findings.isEmpty())
    }

    @Test
    fun `parses detekt checkstyle xml errors with rule name and location`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <checkstyle version="4.3">
                <file name="/project/app/src/main/kotlin/com/example/MainActivity.kt">
                    <error line="23" column="5" severity="warning" message="Function name is too long" source="detekt.style.FunctionNaming"/>
                    <error line="41" column="1" severity="error" message="Class has too many functions" source="detekt.complexity.TooManyFunctions"/>
                </file>
            </checkstyle>
        """.trimIndent()

        val findings = LintResultParser.parseDetektCheckstyleXml(xml)

        assertEquals(2, findings.size)
        assertEquals("FunctionNaming", findings[0].title)
        assertEquals(Severity.WARNING, findings[0].severity)
        assertEquals("/project/app/src/main/kotlin/com/example/MainActivity.kt:23", findings[0].location)
        assertEquals("TooManyFunctions", findings[1].title)
        assertEquals(Severity.ERROR, findings[1].severity)
    }

    @Test
    fun `parses detekt sarif results with rule id, message, and location`() {
        val sarif = """
            {
              "version": "2.1.0",
              "runs": [
                {
                  "tool": { "driver": { "name": "detekt" } },
                  "results": [
                    {
                      "ruleId": "LongMethod",
                      "level": "warning",
                      "message": { "text": "Method is too long" },
                      "locations": [
                        {
                          "physicalLocation": {
                            "artifactLocation": { "uri": "app/src/main/kotlin/com/example/Util.kt" },
                            "region": { "startLine": 88 }
                          }
                        }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val findings = LintResultParser.parseDetektSarif(sarif)

        assertEquals(1, findings.size)
        assertEquals("LongMethod", findings[0].title)
        assertEquals("Method is too long", findings[0].detail)
        assertEquals(Severity.WARNING, findings[0].severity)
        assertEquals("app/src/main/kotlin/com/example/Util.kt:88", findings[0].location)
    }
}
