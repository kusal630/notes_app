package com.vellum.notes.editor

import com.vellum.notes.model.PenStyle
import com.vellum.notes.model.Point
import com.vellum.notes.model.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * On-device print recognizer: every template self-matches through the real
 * pipeline, perturbed inputs hold, words group left-to-right, and scribbles
 * are rejected with "?" instead of guessed text.
 */
class InkRecognizerTest {

    private fun strokeOf(points: List<Point>, id: Long = 1L): Stroke {
        val flat = FloatArray(points.size * 2)
        points.forEachIndexed { i, p ->
            flat[i * 2] = p.x
            flat[i * 2 + 1] = p.y
        }
        return Stroke(id = id, style = PenStyle(), pointsPacked = flat)
    }

    @Test
    fun everyTemplate_selfMatches() {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        for (c in chars) {
            val input = InkRecognizer.templateInput(c)
            assertTrue("template for $c exists", input.isNotEmpty())
            assertEquals("self-match $c", c.toString(), InkRecognizer.recognize(input))
        }
    }

    @Test
    fun lowercaseLookalikes_resolveToCapitals() {
        // Small c-shaped print has the C shape: deterministic capital.
        val smallC = InkRecognizer.templateInput('C', scale = 0.04f, dx = 30f, dy = 30f)
        assertEquals("C", InkRecognizer.recognize(smallC))
    }

    @Test
    fun perturbedInputs_stillMatch() {
        // Smaller, shifted, slightly jittered print.
        assertEquals(
            "A",
            InkRecognizer.recognize(InkRecognizer.templateInput('A', scale = 0.06f, dx = 40f, dy = 120f, jitter = 0.15f)),
        )
        assertEquals(
            "H",
            InkRecognizer.recognize(InkRecognizer.templateInput('H', scale = 0.11f, dx = 5f, dy = 5f, jitter = 0.1f)),
        )
        assertEquals(
            "7",
            InkRecognizer.recognize(InkRecognizer.templateInput('7', scale = 0.05f, dx = 200f, dy = 60f, jitter = 0.1f)),
        )
    }

    @Test
    fun word_hi_groupsLeftToRight() {
        val h = InkRecognizer.templateInput('H', dx = 0f, firstId = 1L)
        val i = InkRecognizer.templateInput('I', dx = 9f, firstId = 10L)
        assertEquals("HI", InkRecognizer.recognize(h + i))
    }

    @Test
    fun word_multiStrokeLetters_groupByOverlap() {
        // "AX": A (2 strokes) + X (2 strokes) with a clear inter-letter gap.
        val a = InkRecognizer.templateInput('A', dx = 0f, firstId = 1L)
        val x = InkRecognizer.templateInput('X', dx = 10f, firstId = 10L)
        assertEquals("AX", InkRecognizer.recognize(a + x))
    }

    @Test
    fun scribble_isRejectedNotGuessed() {
        val zigzag = (0 until 12).flatMap { i ->
            listOf(Point(i * 2f, if (i % 2 == 0) 0f else 8f))
        }
        assertEquals("?", InkRecognizer.recognize(listOf(strokeOf(zigzag))))
    }

    @Test
    fun tinyDot_isSkipped() {
        val dot = listOf(Point(50f, 50f), Point(50.2f, 50.2f))
        assertEquals("?", InkRecognizer.recognize(listOf(strokeOf(dot))))
    }

    @Test
    fun emptyInput_isEmpty() {
        assertEquals("", InkRecognizer.recognize(emptyList()))
    }

    @Test
    fun singleStrokeT_matches() {
        // Two-stroke T drawn as separate strokes still groups (overlap) and matches.
        val bar = InkRecognizer.templateInput('T', dx = 0f).take(1)
        val stem = InkRecognizer.templateInput('T', dx = 0f, firstId = 5L).drop(1)
        assertEquals("T", InkRecognizer.recognize(bar + stem))
    }
}
