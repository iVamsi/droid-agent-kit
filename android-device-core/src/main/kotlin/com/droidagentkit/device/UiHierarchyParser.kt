package com.droidagentkit.device

import com.droidagentkit.core.DiagnosticFinding
import com.droidagentkit.core.Severity
import org.w3c.dom.Element
import org.w3c.dom.Node
import javax.xml.parsers.DocumentBuilderFactory

data class UiHierarchyParseResult(
    val nodeCount: Int,
    val nodes: List<Map<String, Any>>,
    val findings: List<DiagnosticFinding>,
)

object UiHierarchyParser {
    fun parse(xml: String): UiHierarchyParseResult {
        if (xml.isBlank()) {
            return UiHierarchyParseResult(0, emptyList(), listOf(malformed("empty accessibility XML")))
        }
        val document =
            try {
                DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
            } catch (error: Exception) {
                return UiHierarchyParseResult(0, emptyList(), listOf(malformed(error.message ?: "parse error")))
            }
        val root = document.documentElement ?: return UiHierarchyParseResult(0, emptyList(), listOf(malformed("missing root element")))
        val nodes = mutableListOf<Map<String, Any>>()
        val findings = mutableListOf<DiagnosticFinding>()
        val selectorCounts = mutableMapOf<String, Int>()
        val leafBounds = mutableListOf<String>()

        fun walk(
            element: Element,
            depth: Int,
        ) {
            if (element.tagName != "node") {
                element.childElements().forEach { walk(it, depth) }
                return
            }
            val index = element.getAttribute("index").toIntOrNull() ?: -1
            val text = element.getAttribute("text").orEmpty()
            val resourceId = element.getAttribute("resource-id").orEmpty()
            val className = element.getAttribute("class").orEmpty()
            val packageName = element.getAttribute("package").orEmpty()
            val contentDesc = element.getAttribute("content-desc").orEmpty()
            val bounds = element.getAttribute("bounds").orEmpty()
            val clickable = element.getAttribute("clickable").toBooleanStrict()
            val enabled = element.getAttribute("enabled").toBooleanStrict()
            val childElements = element.childElements()
            if (bounds.isBlank()) {
                findings +=
                    DiagnosticFinding(
                        "accessibility",
                        Severity.WARNING,
                        "missing-bounds",
                        "Node $className has no bounds attribute.",
                        className,
                    )
            }
            if (resourceId.isNotBlank()) {
                val key = "$resourceId|$text|$className"
                selectorCounts[key] = (selectorCounts[key] ?: 0) + 1
            }
            if (className.contains("androidx.compose", ignoreCase = true) && resourceId.isBlank()) {
                findings +=
                    DiagnosticFinding(
                        "accessibility",
                        Severity.INFO,
                        "compose-node-without-resource-id",
                        "Compose node '$className' has no resource-id; enable testTagsAsResourceId for stable selectors.",
                        className,
                    )
            }
            if (childElements.isEmpty()) leafBounds.add(bounds)
            nodes +=
                mapOf(
                    "index" to index,
                    "depth" to depth,
                    "text" to text,
                    "resourceId" to resourceId,
                    "class" to className,
                    "package" to packageName,
                    "contentDesc" to contentDesc,
                    "bounds" to bounds,
                    "clickable" to clickable,
                    "enabled" to enabled,
                    "childCount" to childElements.size,
                )
            childElements.forEach { walk(it, depth + 1) }
        }
        walk(root, 0)

        selectorCounts.filter { it.value > 1 }.forEach { (key, count) ->
            val parts = key.split("|")
            findings +=
                DiagnosticFinding(
                    "accessibility",
                    Severity.WARNING,
                    "duplicate-selector",
                    "Selector resource-id='${parts[0]}' text='${parts[1]}' class='${parts[2]}' appears $count times; not unique.",
                    parts[0],
                )
        }
        if (nodes.size == 1 || (leafBounds.isNotEmpty() && leafBounds.toSet().size == 1)) {
            findings +=
                DiagnosticFinding(
                    "accessibility",
                    Severity.WARNING,
                    "stale-tree-suspected",
                    "Accessibility dump may be stale (single node or all leaf bounds identical); re-capture after the screen settles.",
                    null,
                )
        }
        return UiHierarchyParseResult(nodes.size, nodes, findings)
    }

    private fun malformed(detail: String) = DiagnosticFinding("accessibility", Severity.ERROR, "malformed-accessibility-xml", detail, null)

    private fun Element.childElements(): List<Element> {
        val result = mutableListOf<Element>()
        val children = childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeType == Node.ELEMENT_NODE) result.add(child as Element)
        }
        return result
    }

    private fun String.toBooleanStrict(): Boolean = this == "true"
}
