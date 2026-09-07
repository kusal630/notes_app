// Copyright (C) 2026 codeRed
// Vellum is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, either version 3 of the License, or (at your option) any later version.
package com.vellum.notes.editor

import com.vellum.notes.model.PenStyle
import com.vellum.notes.model.PenType
import com.vellum.notes.model.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-geometry tests for strike-out word erase rect hit-testing. */
class EraseInRectTest {

    private fun stroke(id: Long, vararg pts: Float) = Stroke(
        id = id,
        style = PenStyle(type = PenType.BALLPOINT),
        pointsPacked = floatArrayOf(*pts),
    )

    @Test
    fun vertexInsideRect_isHit() {
        val s = stroke(1, 10f, 10f, 50f, 10f)
        assertTrue(rectCovers(s, 5f, 5f, 55f, 15f))
    }

    @Test
    fun segmentMidpointInsideRect_isHit() {
        // sparse samples outside, midpoint crosses the rect
        val s = stroke(2, 0f, 0f, 100f, 0f)
        assertTrue(rectCovers(s, 40f, -2f, 60f, 2f))
    }

    @Test
    fun strokeOutsideRect_isNotHit() {
        val s = stroke(3, 0f, 100f, 50f, 100f)
        assertTrue(!rectCovers(s, 0f, 0f, 50f, 50f))
    }

    /** Mirrors NoteEditorState.collectEraseRect's stroke test. */
    private fun rectCovers(s: Stroke, loX: Float, loY: Float, hiX: Float, hiY: Float): Boolean {
        fun inRect(x: Float, y: Float) = x >= loX && x <= hiX && y >= loY && y <= hiY
        val pts = s.pointsPacked
        var i = 0
        while (i + 1 < pts.size) {
            if (inRect(pts[i], pts[i + 1])) return true
            i += 2
        }
        if (pts.size >= 4) {
            i = 0
            while (i + 3 < pts.size) {
                val mx = (pts[i] + pts[i + 2]) / 2f
                val my = (pts[i + 1] + pts[i + 3]) / 2f
                if (inRect(mx, my)) return true
                i += 2
            }
        }
        return false
    }

    @Test
    fun wordGrouping_threeStrokesInWordAllCovered() {
        // a "word" of three short strokes side by side inside the scribble box
        val w1 = stroke(10, 10f, 10f, 14f, 12f)
        val w2 = stroke(11, 16f, 10f, 20f, 12f)
        val w3 = stroke(12, 22f, 10f, 26f, 12f)
        val box = listOf(w1, w2, w3)
        val covered = box.count { rectCovers(it, 8f, 6f, 28f, 16f) }
        assertEquals(3, covered)
    }
}
