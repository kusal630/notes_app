package com.vellum.notes.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WritingPostureTest {

    @Test
    fun palmSide_followsZoneConvention() {
        assertEquals(PalmZoneSide.LEFT, WritingPosture.RIGHT_HANDED.palmSide())
        assertEquals(PalmZoneSide.RIGHT, WritingPosture.LEFT_HANDED.palmSide())
        assertNull(WritingPosture.TWO_HANDED.palmSide())
    }

    @Test
    fun horizontalMargins_rightHanded_palmSideKeepsFullMargin() {
        val full = 300f
        // Right-handed palm anchors right: right edge full, left edge reduced.
        assertEquals(full, WritingPosture.RIGHT_HANDED.horizontalEdgeMarginPx(leftEdge = false, full), 0.001f)
        assertEquals(
            full * WritingPosture.WRITING_SIDE_EDGE_FRACTION,
            WritingPosture.RIGHT_HANDED.horizontalEdgeMarginPx(leftEdge = true, full),
            0.001f,
        )
    }

    @Test
    fun horizontalMargins_leftHanded_mirrorsRightHanded() {
        val full = 300f
        assertEquals(full, WritingPosture.LEFT_HANDED.horizontalEdgeMarginPx(leftEdge = true, full), 0.001f)
        assertEquals(
            full * WritingPosture.WRITING_SIDE_EDGE_FRACTION,
            WritingPosture.LEFT_HANDED.horizontalEdgeMarginPx(leftEdge = false, full),
            0.001f,
        )
    }

    @Test
    fun horizontalMargins_twoHanded_symmetricFullMargins() {
        val full = 300f
        assertEquals(full, WritingPosture.TWO_HANDED.horizontalEdgeMarginPx(leftEdge = true, full), 0.001f)
        assertEquals(full, WritingPosture.TWO_HANDED.horizontalEdgeMarginPx(leftEdge = false, full), 0.001f)
    }

    @Test
    fun reducedMargin_staysPositiveAndBelowFull() {
        val reduced = WritingPosture.RIGHT_HANDED.horizontalEdgeMarginPx(leftEdge = true, 300f)
        assertTrue(reduced > 0f)
        assertTrue(reduced < 300f)
    }

    @Test
    fun withWritingPosture_syncsZoneSide() {
        val base = PalmRejectionSettings()
        val right = base.withWritingPosture(WritingPosture.RIGHT_HANDED)
        assertEquals(WritingPosture.RIGHT_HANDED, right.writingPosture)
        assertEquals(PalmZoneSide.LEFT, right.palmZone.side)

        val left = base.withWritingPosture(WritingPosture.LEFT_HANDED)
        assertEquals(PalmZoneSide.RIGHT, left.palmZone.side)

        // Two-handed leaves the zone side untouched.
        val two = base.copy(palmZone = base.palmZone.copy(side = PalmZoneSide.RIGHT))
            .withWritingPosture(WritingPosture.TWO_HANDED)
        assertEquals(WritingPosture.TWO_HANDED, two.writingPosture)
        assertEquals(PalmZoneSide.RIGHT, two.palmZone.side)
    }

    @Test
    fun withWritingPosture_doesNotMutateReceiver() {
        val base = PalmRejectionSettings()
        base.withWritingPosture(WritingPosture.LEFT_HANDED)
        assertEquals(WritingPosture.RIGHT_HANDED, base.writingPosture)
        assertEquals(PalmZoneSide.LEFT, base.palmZone.side)
    }
}
