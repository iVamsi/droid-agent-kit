package com.droidagentkit.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiElementFinderTest {
    private val hierarchy =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <hierarchy rotation="0">
          <node index="0" text="" resource-id="" class="android.widget.FrameLayout" package="com.example.app"
                content-desc="" bounds="[0,0][1080,2400]" clickable="false" enabled="true">
            <node index="0" text="Sign in" resource-id="com.example.app:id/signIn" class="android.widget.Button"
                  package="com.example.app" content-desc="Sign in button" bounds="[100,200][500,320]"
                  clickable="true" enabled="true" />
            <node index="1" text="Delete" resource-id="com.example.app:id/deleteOne" class="android.widget.Button"
                  package="com.example.app" content-desc="" bounds="[100,400][300,480]" clickable="true" enabled="true" />
            <node index="2" text="Delete" resource-id="com.example.app:id/deleteTwo" class="android.widget.Button"
                  package="com.example.app" content-desc="" bounds="[400,400][600,480]" clickable="true" enabled="true" />
            <node index="3" text="Terms and conditions" resource-id="" class="android.widget.TextView"
                  package="com.example.app" content-desc="" bounds="[100,600][900,660]" clickable="false" enabled="true" />
          </node>
        </hierarchy>
        """.trimIndent()

    private val nodes = UiHierarchyParser.parse(hierarchy).nodes

    @Test
    fun `finds a unique element by text and computes its tap point`() {
        val result = UiElementFinder.find(nodes, text = "Sign in")

        val match = (result as UiFindResult.Found).match
        assertEquals(300, match.centerX)
        assertEquals(260, match.centerY)
        assertEquals("com.example.app:id/signIn", match.resourceId)
    }

    @Test
    fun `two elements with the same label are ambiguous rather than a coin flip`() {
        // Picking the first "Delete" silently would do the wrong irreversible thing half the time.
        val result = UiElementFinder.find(nodes, text = "Delete")

        val matches = (result as UiFindResult.Ambiguous).matches
        assertEquals(2, matches.size)
        assertTrue(
            "the caller needs enough to disambiguate",
            matches.map { it.resourceId }.containsAll(listOf("com.example.app:id/deleteOne", "com.example.app:id/deleteTwo")),
        )
    }

    @Test
    fun `an ambiguous label can be narrowed by resource id`() {
        val result = UiElementFinder.find(nodes, text = "Delete", resourceId = "deleteTwo")

        assertEquals(500, (result as UiFindResult.Found).match.centerX)
    }

    @Test
    fun `a miss suggests what is actually on screen`() {
        // "not found" alone leaves an agent guessing; the labels present are the useful part.
        val result = UiElementFinder.find(nodes, text = "Log in")

        val suggestions = (result as UiFindResult.NotFound).suggestions
        assertTrue("should suggest real labels, got $suggestions", suggestions.contains("Sign in"))
    }

    @Test
    fun `matching is substring and case-insensitive by default`() {
        assertTrue(UiElementFinder.find(nodes, text = "sign") is UiFindResult.Found)
    }

    @Test
    fun `exact matching does not fall for a substring`() {
        assertTrue(UiElementFinder.find(nodes, text = "sign", exact = true) is UiFindResult.NotFound)
        assertTrue(UiElementFinder.find(nodes, text = "Sign in", exact = true) is UiFindResult.Found)
    }

    @Test
    fun `non-clickable nodes are excluded by default but reachable when asked for`() {
        // Tapping a TextView is usually a mistake; reading one is not.
        assertTrue(UiElementFinder.find(nodes, text = "Terms") is UiFindResult.NotFound)
        assertTrue(UiElementFinder.find(nodes, text = "Terms", clickableOnly = false) is UiFindResult.Found)
    }

    @Test
    fun `content description is searchable for icon-only controls`() {
        val result = UiElementFinder.find(nodes, contentDesc = "Sign in button")

        assertEquals("Sign in", (result as UiFindResult.Found).match.text)
    }

    @Test
    fun `bounds parsing handles negative offsets and rejects garbage`() {
        assertEquals(5 to 15, UiElementFinder.centerOf("[0,10][10,20]"))
        assertEquals(-5 to 0, UiElementFinder.centerOf("[-10,-10][0,10]"))
        assertEquals(null, UiElementFinder.centerOf(""))
        assertEquals(null, UiElementFinder.centerOf("not-bounds"))
    }

    @Test
    fun `a node without bounds is skipped rather than tapped at zero zero`() {
        val boundless = listOf(mapOf<String, Any>("text" to "Ghost", "clickable" to true, "bounds" to ""))

        assertTrue(UiElementFinder.find(boundless, text = "Ghost") is UiFindResult.NotFound)
    }
}
