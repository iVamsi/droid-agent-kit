package com.droidagentkit.device

/** A node matched by a query, with the tap point already computed from its bounds. */
data class UiElementMatch(
    val text: String,
    val contentDesc: String,
    val resourceId: String,
    val className: String,
    val bounds: String,
    val clickable: Boolean,
    val enabled: Boolean,
    val centerX: Int,
    val centerY: Int,
)

sealed interface UiFindResult {
    data class Found(
        val match: UiElementMatch,
    ) : UiFindResult

    /** Nothing matched. [suggestions] are the nearest on-screen labels, to make the error useful. */
    data class NotFound(
        val suggestions: List<String>,
    ) : UiFindResult

    /** More than one node matched; tapping a guess would be a coin flip, so the caller must refine. */
    data class Ambiguous(
        val matches: List<UiElementMatch>,
    ) : UiFindResult
}

/**
 * Finds a UI node by what a human would call it, rather than by pixel coordinates.
 *
 * Coordinate taps are why recorded interactions break: they encode a screen size and a layout, so
 * they stop meaning anything the moment either changes. Matching on text, content description, or
 * resource id keeps an agent's intent ("tap Sign in") intact across devices.
 *
 * Ambiguity is an error rather than a first-match-wins guess. Two nodes labelled "Delete" on one
 * screen is exactly the case where picking one silently does the wrong thing irreversibly.
 */
object UiElementFinder {
    private const val MAX_SUGGESTIONS = 8

    fun find(
        nodes: List<Map<String, Any>>,
        text: String? = null,
        contentDesc: String? = null,
        resourceId: String? = null,
        exact: Boolean = false,
        clickableOnly: Boolean = true,
    ): UiFindResult {
        val candidates =
            nodes
                .mapNotNull { toMatch(it) }
                .filter { candidate ->
                    if (clickableOnly && !candidate.clickable) return@filter false
                    matches(candidate.text, text, exact) &&
                        matches(candidate.contentDesc, contentDesc, exact) &&
                        matches(candidate.resourceId, resourceId, exact)
                }

        return when {
            candidates.size == 1 -> UiFindResult.Found(candidates.first())
            candidates.size > 1 -> UiFindResult.Ambiguous(candidates)
            else ->
                UiFindResult.NotFound(
                    nodes
                        .mapNotNull { toMatch(it) }
                        .flatMap { listOf(it.text, it.contentDesc) }
                        .filter { it.isNotBlank() }
                        .distinct()
                        .take(MAX_SUGGESTIONS),
                )
        }
    }

    /** A null criterion matches everything; that is how callers query on one attribute only. */
    private fun matches(
        actual: String,
        wanted: String?,
        exact: Boolean,
    ): Boolean {
        if (wanted == null) return true
        return if (exact) actual == wanted else actual.contains(wanted, ignoreCase = true)
    }

    internal fun toMatch(node: Map<String, Any>): UiElementMatch? {
        val bounds = node["bounds"]?.toString().orEmpty()
        val center = centerOf(bounds) ?: return null
        return UiElementMatch(
            text = node["text"]?.toString().orEmpty(),
            contentDesc = node["contentDesc"]?.toString() ?: node["content-desc"]?.toString().orEmpty(),
            resourceId = node["resourceId"]?.toString() ?: node["resource-id"]?.toString().orEmpty(),
            className = node["className"]?.toString() ?: node["class"]?.toString().orEmpty(),
            bounds = bounds,
            clickable = node["clickable"] == true || node["clickable"]?.toString() == "true",
            enabled = node["enabled"] == true || node["enabled"]?.toString() == "true",
            centerX = center.first,
            centerY = center.second,
        )
    }

    /** uiautomator writes bounds as `[left,top][right,bottom]`. */
    internal fun centerOf(bounds: String): Pair<Int, Int>? {
        val match = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""").find(bounds) ?: return null
        val (left, top, right, bottom) = match.destructured
        return ((left.toInt() + right.toInt()) / 2) to ((top.toInt() + bottom.toInt()) / 2)
    }
}
