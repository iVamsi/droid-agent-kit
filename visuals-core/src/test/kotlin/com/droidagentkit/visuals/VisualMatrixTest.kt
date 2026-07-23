package com.droidagentkit.visuals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualMatrixTest {
    @Test
    fun `cartesian iterates the full product of axes`() {
        val matrix =
            VisualMatrix(
                devices = listOf("phone", "tablet"),
                themes = listOf("light", "dark"),
                fontScales = listOf(1.0f, 2.0f),
                locales = listOf("en", "fr"),
            )

        val envs = matrix.cartesian()

        assertEquals(16, envs.size)
        assertEquals(16L, matrix.cardinality())
        assertTrue(envs.any { it.device == "tablet" && it.theme == "dark" && it.fontScale == 2.0f && it.locale == "fr" })
        assertTrue(envs.distinct().size == envs.size)
    }

    @Test
    fun `standard matrix has a single environment`() {
        assertEquals(1, VisualMatrix.standard().cartesian().size)
    }

    @Test
    fun `validate rejects empty axes`() {
        assertThrows(IllegalArgumentException::class.java) {
            VisualMatrix(devices = emptyList(), themes = listOf("light"), fontScales = listOf(1.0f), locales = listOf("en")).validate()
        }
    }

    @Test
    fun `validate rejects cardinality above 64`() {
        val matrix =
            VisualMatrix(
                devices = (1..9).map { "d$it" },
                themes = listOf("light", "dark"),
                fontScales = listOf(1.0f, 2.0f),
                locales =
                    (1..3).map { "l$it" },
            )
        assertThrows(IllegalArgumentException::class.java) { matrix.validate() }
    }

    @Test
    fun `validate accepts cardinality of exactly 64`() {
        val matrix =
            VisualMatrix(
                devices = (1..4).map { "d$it" },
                themes = listOf("light", "dark"),
                fontScales = listOf(1.0f, 2.0f),
                locales =
                    (1..4).map { "l$it" },
            )
        matrix.validate()
        assertEquals(64L, matrix.cardinality())
    }
}
