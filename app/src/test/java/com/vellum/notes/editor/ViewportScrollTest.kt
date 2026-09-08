package com.vellum.notes.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewportScrollTest {
    @Test
    fun centerNoShift() {
        assertEquals(0f, ViewportScroll.shiftFor(500f, 1000f), 0.001f)
    }

    @Test
    fun bottomEdgeShiftsUp() {
        // pen 20px past the 150px margin -> +20 shift (offset decreases).
        assertEquals(20f, ViewportScroll.shiftFor(870f, 1000f), 0.001f)
    }

    @Test
    fun topEdgeShiftsDown() {
        assertEquals(-20f, ViewportScroll.shiftFor(130f, 1000f), 0.001f)
    }

    @Test
    fun deadbandKillsJitter() {
        assertEquals(0f, ViewportScroll.shiftFor(852f, 1000f), 0.001f)
        assertEquals(0f, ViewportScroll.shiftFor(148f, 1000f), 0.001f)
    }

    @Test
    fun clampKeepsOffsetInRange() {
        // extent 500mm @10px/mm = 5000px, height 1000 -> max 4000.
        assertEquals(4000f, ViewportScroll.clampOffset(9999f, 500f, 10f, 1000f), 0.001f)
        assertEquals(0f, ViewportScroll.clampOffset(-50f, 500f, 10f, 1000f), 0.001f)
        assertEquals(1200f, ViewportScroll.clampOffset(1200f, 500f, 10f, 1000f), 0.001f)
    }
}
