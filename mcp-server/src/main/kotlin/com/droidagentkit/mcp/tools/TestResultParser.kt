package com.droidagentkit.mcp.tools

import com.droidagentkit.core.DiagnosticFinding
import com.droidagentkit.core.Severity
import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

data class TestRunSummary(
    val tests: Int,
    val failures: Int,
    val errors: Int,
    val skipped: Int,
    val durationSeconds: Double,
    val reportFiles: List<String>,
)

data class ParsedTestResults(
    val summary: TestRunSummary,
    val findings: List<DiagnosticFinding>,
)

object TestResultParser {
    private const val MAX_REPORTS = 500
    private const val MAX_DETAIL_CHARS = 8_000

    fun parse(root: Path): ParsedTestResults {
        val reports = findReports(root)
        var tests = 0
        var failures = 0
        var errors = 0
        var skipped = 0
        var duration = 0.0
        val findings = mutableListOf<DiagnosticFinding>()

        reports.forEach { report ->
            val document = secureFactory().newDocumentBuilder().parse(report.toFile())
            val suites = document.getElementsByTagName("testsuite")
            for (index in 0 until suites.length) {
                val suite = suites.item(index) as? Element ?: continue
                tests += suite.intAttribute("tests")
                failures += suite.intAttribute("failures")
                errors += suite.intAttribute("errors")
                skipped += suite.intAttribute("skipped")
                duration += suite.doubleAttribute("time")
            }
            val cases = document.getElementsByTagName("testcase")
            for (index in 0 until cases.length) {
                val testCase = cases.item(index) as? Element ?: continue
                val failure = testCase.childElements("failure").firstOrNull()
                val error = testCase.childElements("error").firstOrNull()
                val problem = failure ?: error ?: continue
                val className = testCase.getAttribute("classname")
                val name = testCase.getAttribute("name")
                val message = problem.getAttribute("message").ifBlank { problem.textContent.trim() }
                findings +=
                    DiagnosticFinding(
                        category = if (failure != null) "test.failure" else "test.error",
                        severity = Severity.ERROR,
                        title = "$className.$name",
                        detail = message.take(MAX_DETAIL_CHARS),
                        location = root.relativize(report).toString(),
                    )
            }
        }
        return ParsedTestResults(
            TestRunSummary(
                tests = tests,
                failures = failures,
                errors = errors,
                skipped = skipped,
                durationSeconds = duration,
                reportFiles = reports.map { root.relativize(it).toString() },
            ),
            findings,
        )
    }

    private fun findReports(root: Path): List<Path> {
        val reports = mutableListOf<Path>()
        Files.walk(root, 8).use { paths ->
            paths
                .filter(Files::isRegularFile)
                .filter { it.fileName.toString().startsWith("TEST-") && it.fileName.toString().endsWith(".xml") }
                .filter {
                    val parts = root.relativize(it).map(Path::toString)
                    parts.contains("test-results") || parts.contains("androidTest-results")
                }.limit(MAX_REPORTS.toLong())
                .forEach(reports::add)
        }
        return reports.sortedBy(Path::toString)
    }

    private fun secureFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }

    private fun Element.intAttribute(name: String): Int = getAttribute(name).toIntOrNull() ?: 0

    private fun Element.doubleAttribute(name: String): Double = getAttribute(name).toDoubleOrNull() ?: 0.0

    private fun Element.childElements(tagName: String): List<Element> {
        val nodes = getElementsByTagName(tagName)
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }
}
