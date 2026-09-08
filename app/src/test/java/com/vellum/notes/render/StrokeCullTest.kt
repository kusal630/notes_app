package com.vellum.notes.render

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StrokeCullTest {

    @Test
    fun boundsOf_containsAllPointsWithPadding() {
        val pts = floatArrayOf(10f, 20f, 30f, 5f, 20f, 40f)
        val b = StrokeCull.boundsOf(pts, padMm = 2f)
        assertEquals(8f, b.left, 0.001f)
        assertEquals(3f, b.top, 0.001f)
        assertEquals(32f, b.right, 0.001f)
        assertEquals(42f, b.bottom, 0.001f)
    }

    @Test
    fun boundsOf_emptyPoints_returnsEmptyRect() {
        val b = StrokeCull.boundsOf(floatArrayOf(), padMm = 2f)
        assertTrue(b.isEmpty)
        // An empty bound is never visible, even inside a huge clip.
        assertFalse(StrokeCull.isVisible(b, RectF(-1000f, -1000f, 1000f, 1000f)))
    }

    @Test
    fun isVisible_overlappingAndDisjoint() {
        val clip = RectF(0f, 0f, 100f, 100f)
        assertTrue(StrokeCull.isVisible(RectF(50f, 50f, 150f, 150f), clip))
        assertTrue(StrokeCull.isVisible(RectF(-50f, -50f, 10f, 10f), clip))
        assertFalse(StrokeCull.isVisible(RectF(101f, 101f, 200f, 200f), clip))
        assertFalse(StrokeCull.isVisible(RectF(-200f, -200f, -1f, -1f), clip))
    }

    @Test
    fun isVisible_fullyInsideClip() {
        val clip = RectF(0f, 0f, 100f, 100f)
        assertTrue(StrokeCull.isVisible(RectF(10f, 10f, 20f, 20f), clip))
    }

    @Test
    fun padForWidth_coversVariableWidthPens() {
        // Fountain peaks at 1.4x base: padding must exceed half of that on each side.
        assertTrue(StrokeCull.padForWidth(2f) >= 2f * 1.4f / 2f)
        // Hairlines still get a floor so AA bleed is never cut.
        assertEquals(1f, StrokeCull.padForWidth(0.2f), 0.001f)
        assertEquals(1f, StrokeCull.padForWidth(0f), 0.001f)
    }

    @Test
    fun padForWidth_growsWithBaseWidth() {
        assertTrue(StrokeCull.padForWidth(5f) > StrokeCull.padForWidth(1f))
    }
}
