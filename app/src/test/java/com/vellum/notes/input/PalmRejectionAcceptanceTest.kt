package com.vellum.notes.input

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Acceptance tests for palm rejection: the canvas must write in all four
 * real-world cases. Driven through [PalmRejectionEngine] exactly as the canvas
 * consumes it (10px/mm tablet, no stylus hardware — purely software rejection).
 *
 * 1. Palm rests first, then the pen lands → the pen writes.
 * 2. Writing first, palm lands mid-stroke → the stroke continues.
 * 3. Writing with no palm → writes.
 * 4. Writing, palm joins, writing continues → keeps writing.
 */
class PalmRejectionAcceptanceTest {

    private var settings = testSettings()

    private fun engine(configure: PalmRejectionSettings.() -> Unit = {}) =
        PalmRejectionEngine(testCapabilities()) {
            settings = testSettings().apply(configure)
            settings
        }

    /** Passive pen tip (≈2.6mm): the writer in every case. */
    private fun pen(pointerId: Int, x: Float, y: Float, timeMs: Long) =
        TestTouchFactory.pen(pointerId = pointerId, x = x, y = y, timeMs = timeMs)

    /** Resting palm heel (≈30mm). */
    private fun palm(pointerId: Int, x: Float, y: Float, timeMs: Long) =
        TestTouchFactory.palm(pointerId = pointerId, x = x, y = y, timeMs = timeMs)

    @Test
    fun case1_palmDownFirst_thenPenWrites() {
        val e = engine()

        // Palm rests on the canvas first.
        val palmDown = e.process(
            TestTouchFactory.frame(
                InputAction.DOWN, 0L, listOf(palm(2, 500f, 700f, 0L)), added = 2,
            )
        )
        assertEquals(ContactClassification.PALM, palmDown.contactFor(2)?.classification)

        // The pen lands while the palm is still resting: it must write.
        val penDown = e.process(
            TestTouchFactory.frame(
                InputAction.POINTER_DOWN, 10L,
                listOf(palm(2, 500f, 700f, 0L), pen(0, 200f, 200f, 10L)), added = 0,
            )
        )
        assertEquals(ContactClassification.WRITING, penDown.contactFor(0)?.classification)
        assertEquals(0, penDown.activeWritingPointerId)
        assertEquals(ContactClassification.PALM, penDown.contactFor(2)?.classification)

        // The stroke continues while the palm stays down.
        val move = e.process(
            TestTouchFactory.frame(
                InputAction.MOVE, 20L,
                listOf(palm(2, 500f, 700f, 0L), pen(0, 240f, 220f, 10L)),
            )
        )
        assertEquals(ContactClassification.WRITING, move.contactFor(0)?.classification)
        assertEquals(0, move.activeWritingPointerId)
    }

    @Test
    fun case2_writingFirst_thenPalmLandsMidStroke() {
        val e = engine()

        val down = e.process(
            TestTouchFactory.frame(
                InputAction.DOWN, 0L, listOf(pen(0, 200f, 200f, 0L)), added = 0,
            )
        )
        assertEquals(ContactClassification.WRITING, down.contactFor(0)?.classification)
        assertEquals(0, down.activeWritingPointerId)

        // Palm lands mid-stroke: the stroke must continue, the palm is rejected.
        val palmJoins = e.process(
            TestTouchFactory.frame(
                InputAction.POINTER_DOWN, 10L,
                listOf(pen(0, 200f, 200f, 0L), palm(2, 500f, 700f, 10L)), added = 2,
            )
        )
        assertEquals(ContactClassification.WRITING, palmJoins.contactFor(0)?.classification)
        assertEquals(0, palmJoins.activeWritingPointerId)
        assertEquals(ContactClassification.PALM, palmJoins.contactFor(2)?.classification)

        val move = e.process(
            TestTouchFactory.frame(
                InputAction.MOVE, 20L,
                listOf(pen(0, 240f, 220f, 0L), palm(2, 500f, 700f, 10L)),
            )
        )
        assertEquals(ContactClassification.WRITING, move.contactFor(0)?.classification)
        assertEquals(0, move.activeWritingPointerId)
    }

    @Test
    fun case3_writeWithoutPalm_writes() {
        val e = engine()

        val down = e.process(
            TestTouchFactory.frame(
                InputAction.DOWN, 0L, listOf(pen(0, 200f, 200f, 0L)), added = 0,
            )
        )
        assertEquals(ContactClassification.WRITING, down.contactFor(0)?.classification)
        assertEquals(0, down.activeWritingPointerId)

        var last = down
        var x = 200f
        var t = 10L
        while (t <= 60L) {
            x += 20f
            last = e.process(
                TestTouchFactory.frame(
                    InputAction.MOVE, t, listOf(pen(0, x, 200f, 0L)),
                )
            )
            assertEquals(ContactClassification.WRITING, last.contactFor(0)?.classification)
            t += 10L
        }
        assertEquals(0, last.activeWritingPointerId)
    }

    @Test
    fun case4_writePalmJoinsAndWritingContinues_keepsWriting() {
        val e = engine()

        e.process(
            TestTouchFactory.frame(
                InputAction.DOWN, 0L, listOf(pen(0, 200f, 200f, 0L)), added = 0,
            )
        )
        e.process(
            TestTouchFactory.frame(
                InputAction.POINTER_DOWN, 10L,
                listOf(pen(0, 200f, 200f, 0L), palm(2, 500f, 700f, 10L)), added = 2,
            )
        )

        // Writing continues across several frames with the palm resting.
        var last: ClassifiedFrame? = null
        var x = 200f
        var t = 20L
        while (t <= 100L) {
            x += 20f
            last = e.process(
                TestTouchFactory.frame(
                    InputAction.MOVE, t,
                    listOf(pen(0, x, 200f, 0L), palm(2, 500f, 700f, 10L)),
                )
            )
            assertEquals("t=$t pen", ContactClassification.WRITING, last.contactFor(0)?.classification)
            assertEquals("t=$t palm", ContactClassification.PALM, last.contactFor(2)?.classification)
            t += 10L
        }
        assertEquals(0, last?.activeWritingPointerId)
    }
}
