package com.vellum.notes.input

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Engine-level tests for handedness-aware edge margins ([WritingPosture]).
 *
 * Setup: 10px/mm tablet, 2000px viewport, 30mm (300px) full edge margin. The
 * differential band is 105–300px from a vertical edge: inside the full margin
 * but outside the reduced writing-side margin (30mm * 0.35 = 10.5mm = 105px).
 * Two spread fingertips (15mm from one edge, one mid-screen) rest stationary
 * for 500ms; only posture decides whether the edge contact becomes RESTING.
 */
class WritingPostureEdgeTest {

    private var settings = testSettings()

    private fun engine(configure: PalmRejectionSettings.() -> Unit = {}) =
        PalmRejectionEngine(testCapabilities()) {
            settings = testSettings().apply(configure)
            settings
        }

    private fun fingertip(pointerId: Int, x: Float, y: Float, timeMs: Long) =
        TestTouchFactory.fingertip(pointerId, x, y, timeMs)

    /**
     * Rests two spread fingertips ([edgeX] near one vertical edge, other
     * mid-screen) stationary until t=500ms and returns the final frame.
     */
    private fun restPair(
        e: PalmRejectionEngine,
        edgeX: Float,
    ): ClassifiedFrame {
        e.process(
            TestTouchFactory.frame(
                InputAction.DOWN, 0L, listOf(fingertip(1, edgeX, 1000f, 0L)), added = 1,
            ),
        )
        e.process(
            TestTouchFactory.frame(
                InputAction.POINTER_DOWN, 10L,
                listOf(fingertip(1, edgeX, 1000f, 0L), fingertip(3, 1000f, 1000f, 10L)),
                added = 3,
            ),
        )
        return e.process(
            TestTouchFactory.frame(
                InputAction.MOVE, 500L,
                listOf(fingertip(1, edgeX, 1000f, 0L), fingertip(3, 1000f, 1000f, 10L)),
            ),
        )
    }

    @Test
    fun rightHanded_contactInLeftDifferentialBand_staysWritable() {
        val e = engine { writingPosture = WritingPosture.RIGHT_HANDED }
        val out = restPair(e, edgeX = 150f)
        // 15mm from the left (writing-side) edge: outside the reduced 10.5mm
        // margin, so no edge-resting evidence — the contact stays a finger.
        assertEquals(ContactClassification.FINGER, out.contactFor(1)?.classification)
        assertEquals(ContactClassification.FINGER, out.contactFor(3)?.classification)
    }

    @Test
    fun leftHanded_contactInLeftDifferentialBand_rests() {
        val e = engine { writingPosture = WritingPosture.LEFT_HANDED }
        val out = restPair(e, edgeX = 150f)
        // 15mm from the left (palm-side) edge: inside the full 30mm margin.
        assertEquals(ContactClassification.RESTING, out.contactFor(1)?.classification)
        assertEquals(ClassificationReason.RESTING_EDGE, out.contactFor(1)?.reason)
        // The mid-screen contact is unaffected.
        assertEquals(ContactClassification.FINGER, out.contactFor(3)?.classification)
    }

    @Test
    fun rightHanded_contactInRightDifferentialBand_rests() {
        val e = engine { writingPosture = WritingPosture.RIGHT_HANDED }
        val out = restPair(e, edgeX = 1850f)
        // 150px from the right (palm-side) edge: inside the full margin.
        assertEquals(ContactClassification.RESTING, out.contactFor(1)?.classification)
        assertEquals(ClassificationReason.RESTING_EDGE, out.contactFor(1)?.reason)
        assertEquals(ContactClassification.FINGER, out.contactFor(3)?.classification)
    }

    @Test
    fun leftHanded_contactInRightDifferentialBand_staysWritable() {
        val e = engine { writingPosture = WritingPosture.LEFT_HANDED }
        val out = restPair(e, edgeX = 1850f)
        assertEquals(ContactClassification.FINGER, out.contactFor(1)?.classification)
        assertEquals(ContactClassification.FINGER, out.contactFor(3)?.classification)
    }

    @Test
    fun twoHanded_bothEdgesKeepFullMargin() {
        val left = engine { writingPosture = WritingPosture.TWO_HANDED }
        val leftOut = restPair(left, edgeX = 150f)
        assertEquals(ContactClassification.RESTING, leftOut.contactFor(1)?.classification)

        val right = engine { writingPosture = WritingPosture.TWO_HANDED }
        val rightOut = restPair(right, edgeX = 1850f)
        assertEquals(ContactClassification.RESTING, rightOut.contactFor(1)?.classification)
    }

    @Test
    fun deepInsideWritingSideEdge_stillRestsUnderEveryPosture() {
        // 5mm from the left edge is inside even the reduced 10.5mm margin, so a
        // genuinely edge-pressed contact rests regardless of posture (no
        // regression vs the symmetric behavior).
        for (posture in WritingPosture.entries) {
            val e = engine { writingPosture = posture }
            val out = restPair(e, edgeX = 50f)
            assertEquals(
                "posture=$posture",
                ContactClassification.RESTING,
                out.contactFor(1)?.classification,
            )
        }
    }
}
