package com.vellum.notes.render

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DirtyRectTest {

    @Test
    fun segment_mapsWorldToScreenWithPadding() {
        // World (10,10)-(20,20), scale 10, no offset, pad 2mm:
        // screen (100,100)-(200,200) expanded by 20px each side.
        val r = DirtyRect.segment(10f, 10f, 20f, 20f, 10f, 0f, 0f, 2f)
        assertEquals(RectF(80f, 80f, 220f, 220f), r)
    }

    @Test
    fun segment_handlesReversedEndpointsAndOffsets() {
        val r = DirtyRect.segment(20f, 20f, 10f, 10f, 10f, 5f, -5f, 0f)
        assertEquals(RectF(105f, 95f, 205f, 195f), r)
    }

    @Test
    fun segment_degeneratePoint_stillPadded() {
        val r = DirtyRect.segment(5f, 5f, 5f, 5f, 10f, 0f, 0f, 1f)
        assertEquals(RectF(40f, 40f, 60f, 60f), r)
    }

    @Test
    fun segment_containsBothEndpoints() {
        val scale = 7.5f
        val ox = -30f
        val oy = 120f
        val r = DirtyRect.segment(3f, 8f, 25f, 2f, scale, ox, oy, 1.5f)
        assertTrue(r.left <= 3f * scale + ox)
        assertTrue(r.top <= 2f * scale + oy)
        assertTrue(r.right >= 25f * scale + ox)
        assertTrue(r.bottom >= 8f * scale + oy)
    }

    @Test
    fun padForWidth_coversStrokeCullPadPlusSmoothingSlop() {
        assertTrue(DirtyRect.padForWidth(1f) > StrokeCull.padForWidth(1f))
        assertTrue(DirtyRect.padForWidth(5f) > DirtyRect.padForWidth(1f))
    }
}
