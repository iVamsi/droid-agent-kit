package com.droidagentkit.mcp.tools

import com.droidagentkit.core.DiagnosticFinding
import com.droidagentkit.core.Severity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

object LintResultParser {
    fun parseAndroidLintXml(xml: String): List<DiagnosticFinding> {
        val doc = parseXml(xml) ?: return emptyList()
        val issues = doc.getElementsByTagName("issue")
        return (0 until issues.length).mapNotNull { index ->
            val issue = issues.item(index) as? Element ?: return@mapNotNull null
            val locations = issue.getElementsByTagName("location")
            val location = if (locations.length > 0) {
                val loc = locations.item(0) as Element
                val file = loc.getAttribute("file")
                val line = loc.getAttribute("line")
                if (line.isNotBlank()) "$file:$line" else file.ifBlank { null }
            } else {
                null
            }
            DiagnosticFinding(
                category = "lint",
                severity = mapSeverityWord(issue.getAttribute("severity")),
                title = issue.getAttribute("id").ifBlank { "lint-issue" },
                detail = issue.getAttribute("message"),
                location = location,
            )
        }
    }

    fun parseDetektCheckstyleXml(xml: String): List<DiagnosticFinding> {
        val doc = parseXml(xml) ?: return emptyList()
        val files = doc.getElementsByTagName("file")
        val findings = mutableListOf<DiagnosticFinding>()
        for (fileIndex in 0 until files.length) {
            val fileElement = files.item(fileIndex) as? Element ?: continue
            val fileName = fileElement.getAttribute("name")
            val errors = fileElement.getElementsByTagName("error")
            for (errorIndex in 0 until errors.length) {
                val error = errors.item(errorIndex) as? Element ?: continue
                val line = error.getAttribute("line")
                findings += DiagnosticFinding(
                    category = "lint",
                    severity = mapSeverityWord(error.getAttribute("severity")),
                    title = error.getAttribute("source").substringAfterLast('.').ifBlank { "detekt-issue" },
                    detail = error.getAttribute("message"),
                    location = if (line.isNotBlank()) "$fileName:$line" else fileName,
                )
            }
        }
        return findings
    }

    fun parseDetektSarif(sarifJson: String): List<DiagnosticFinding> {
        val root = try {
            Json.parseToJsonElement(sarifJson).jsonObject
        } catch (error: Exception) {
            return emptyList()
        }
        val runs = root["runs"]?.jsonArray ?: return emptyList()
        val findings = mutableListOf<DiagnosticFinding>()
        for (run in runs) {
            val results = run.jsonObject["results"]?.jsonArray ?: continue
            for (result in results) {
                val obj = result.jsonObject
                val ruleId = obj["ruleId"]?.jsonPrimitive?.content ?: "detekt-issue"
                val message = obj["message"]?.jsonObject?.get("text")?.jsonPrimitive?.content ?: ""
                val level = obj["level"]?.jsonPrimitive?.content ?: "warning"
                val physicalLocation = obj["locations"]?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("physicalLocation")?.jsonObject
                val uri = physicalLocation?.get("artifactLocation")?.jsonObject
                    ?.get("uri")?.jsonPrimitive?.content
                val startLine = physicalLocation?.get("region")?.jsonObject
                    ?.get("startLine")?.jsonPrimitive?.content
                val location = when {
                    uri != null && startLine != null -> "$uri:$startLine"
                    uri != null -> uri
                    else -> null
                }
                findings += DiagnosticFinding(
                    category = "lint",
                    severity = mapSeverityWord(level),
                    title = ruleId,
                    detail = message,
                    location = location,
                )
            }
        }
        return findings
    }

    private fun mapSeverityWord(value: String): Severity = when (value.lowercase()) {
        "fatal", "error" -> Severity.ERROR
        "warning" -> Severity.WARNING
        else -> Severity.INFO
    }

    private fun parseXml(xml: String): Document? = try {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
    } catch (error: Exception) {
        null
    }
}
