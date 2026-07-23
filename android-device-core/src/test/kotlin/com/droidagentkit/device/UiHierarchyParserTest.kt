package com.droidagentkit.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiHierarchyParserTest {
    private fun hierarchy(vararg nodes: String): String =
        "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>\n<hierarchy rotation=\"0\">${nodes.joinToString("")}</hierarchy>\n"

    private fun node(
        index: Int,
        clazz: String,
        resourceId: String = "",
        text: String = "",
        bounds: String = "[0,0][100,100]",
        children: String = "",
    ): String =
        "<node index=\"$index\" text=\"$text\" resource-id=\"$resourceId\" class=\"$clazz\" package=\"com.example\" " +
            "content-desc=\"\" checkable=\"false\" checked=\"false\" clickable=\"false\" enabled=\"true\" " +
            "focusable=\"false\" focused=\"false\" scrollable=\"false\" long-clickable=\"false\" password=\"false\" " +
            "selected=\"false\" bounds=\"$bounds\">$children</node>"

    @Test
    fun `parses a single node into structured map`() {
        val xml = hierarchy(node(0, "android.widget.FrameLayout"))
        val result = UiHierarchyParser.parse(xml)
        assertEquals(1, result.nodeCount)
        assertEquals("android.widget.FrameLayout", result.nodes[0]["class"])
        assertEquals("[0,0][100,100]", result.nodes[0]["bounds"])
    }

    @Test
    fun `flags missing bounds as a warning`() {
        val xml = hierarchy(node(0, "android.widget.FrameLayout", bounds = ""))
        val result = UiHierarchyParser.parse(xml)
        assertTrue(result.findings.any { it.title == "missing-bounds" })
    }

    @Test
    fun `flags duplicate selectors when resource id text and class repeat`() {
        val child = node(1, "android.widget.Button", resourceId = "com.example:id/login", text = "Login")
        val child2 = node(2, "android.widget.Button", resourceId = "com.example:id/login", text = "Login")
        val xml = hierarchy(node(0, "android.widget.FrameLayout", children = child + child2))
        val result = UiHierarchyParser.parse(xml)
        assertTrue(result.findings.any { it.title == "duplicate-selector" })
    }

    @Test
    fun `flags compose nodes without resource id as info`() {
        val xml = hierarchy(node(0, "androidx.compose.ui.platform.ComposeView"))
        val result = UiHierarchyParser.parse(xml)
        assertTrue(
            result.findings.any {
                it.title == "compose-node-without-resource-id" && it.severity == com.droidagentkit.core.Severity.INFO
            },
        )
    }

    @Test
    fun `returns error finding for malformed xml`() {
        val result = UiHierarchyParser.parse("<hierarchy><node index=\"0\" class=\"broken")
        assertEquals(0, result.nodeCount)
        assertTrue(result.findings.any { it.title == "malformed-accessibility-xml" })
    }

    @Test
    fun `returns error finding for blank input`() {
        val result = UiHierarchyParser.parse("")
        assertTrue(result.findings.any { it.title == "malformed-accessibility-xml" })
        assertEquals(0, result.nodeCount)
    }

    @Test
    fun `warns on stale tree when all leaf bounds are identical`() {
        val leafA = node(1, "android.widget.TextView", bounds = "[0,0][50,50]")
        val leafB = node(2, "android.widget.TextView", bounds = "[0,0][50,50]")
        val xml = hierarchy(node(0, "android.widget.FrameLayout", children = leafA + leafB))
        val result = UiHierarchyParser.parse(xml)
        assertTrue(result.findings.any { it.title == "stale-tree-suspected" })
    }

    @Test
    fun `does not warn stale when leaf bounds differ`() {
        val leafA = node(1, "android.widget.TextView", bounds = "[0,0][50,50]")
        val leafB = node(2, "android.widget.TextView", bounds = "[0,0][200,200]")
        val xml = hierarchy(node(0, "android.widget.FrameLayout", children = leafA + leafB))
        val result = UiHierarchyParser.parse(xml)
        assertTrue(result.findings.none { it.title == "stale-tree-suspected" })
    }
}
