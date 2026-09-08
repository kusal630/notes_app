package com.vellum.notes.render

import com.vellum.notes.model.PenStyle
import com.vellum.notes.model.Point
import com.vellum.notes.model.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InkBeautifyTest {
    private fun strokeOf(vararg pts: Float): Stroke {
        val points = pts.toList().chunked(2).map { Point(it[0], it[1]) }
        return Stroke(id = 1L, style = PenStyle(), pointsPacked = Stroke.pack(points))
    }

    @Test
    fun shortStroke_passesThrough() {
        val s = strokeOf(0f, 0f, 10f, 0f)
        assertEquals(s.pointsPacked.toList(), InkBeautify.smoothStroke(s).pointsPacked.toList())
    }

    @Test
    fun endpoints_fixed_pointCountGrows() {
        val s = strokeOf(0f, 0f, 10f, 8f, 20f, 0f, 30f, 8f)
        val out = InkBeautify.smoothStroke(s)
        val pts = out.points
        assertEquals(0f, pts.first().x, 0.001f)
        assertEquals(30f, pts.last().x, 0.001f)
        assertTrue(pts.size > s.points.size)
    }

    @Test
    fun spike_amplitudeReduced() {
        // Sharp V spike: smoothing must pull the apex toward the chord.
        val s = strokeOf(0f, 0f, 10f, 0f, 20f, 20f, 30f, 0f, 40f, 0f)
        val before = s.points.maxOf { it.y }
        val after = InkBeautify.smoothStroke(s).points.maxOf { it.y }
        assertTrue(after < before)
    }
}
