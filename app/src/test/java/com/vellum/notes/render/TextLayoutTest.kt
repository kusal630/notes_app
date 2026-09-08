package com.vellum.notes.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextLayoutTest {

    /** Fixed-width measure: 1 unit per character, so expectations are exact. */
    private val monoMeasure: (String) -> Float = { s -> s.length.toFloat() }

    @Test
    fun wrap_shortText_staysOnOneLine() {
        assertEquals(listOf("hello"), TextLayout.wrap("hello", monoMeasure, 10f))
    }

    @Test
    fun wrap_longText_breaksAtWordBoundaries() {
        val lines = TextLayout.wrap("aaa bbb ccc", monoMeasure, 7f)
        assertEquals(listOf("aaa bbb", "ccc"), lines)
        for (line in lines) assertTrue(line.length <= 7)
    }

    @Test
    fun wrap_preservesExplicitNewlines() {
        assertEquals(
            listOf("ab", "cd"),
            TextLayout.wrap("ab\ncd", monoMeasure, 10f),
        )
    }

    @Test
    fun wrap_singleWordLongerThanWidth_keptWhole() {
        // A single unbreakable word is never split mid-word (matches canvas behavior).
        assertEquals(
            listOf("supercalifragilistic"),
            TextLayout.wrap("supercalifragilistic", monoMeasure, 5f),
        )
    }

    @Test
    fun wrap_emptyText_yieldsOneEmptyLine() {
        assertEquals(listOf(""), TextLayout.wrap("", monoMeasure, 10f))
    }

    @Test
    fun wrap_matchesCanvasWordWrapSemantics() {
        // Same algorithm the canvas used inline: words accumulate until the width
        // would overflow, then break before the overflowing word.
        val lines = TextLayout.wrap("one two three four", monoMeasure, 8f)
        assertEquals(listOf("one two", "three", "four"), lines)
    }
}
