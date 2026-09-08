package com.vellum.notes.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Palm rejection audit in the app-default configuration (WRITING mode, finger
 * writing on, no stylus hardware): every realistic hand combination must
 * behave — finger writers in both touchdown orders, hardware stylus, and a
 * two-finger gesture sharing the screen with a resting palm.
 */
class PalmAuditTest {

    private var settings = testSettings(mode = PalmRejectionMode.WRITING)

    private fun engine() = PalmRejectionEngine(testCapabilities()) {
        settings = testSettings(mode = PalmRejectionMode.WRITING)
        settings
    }

    private fun fingertip(pointerId: Int, x: Float, y: Float, timeMs: Long) =
        TestTouchFactory.fingertip(pointerId, x, y, timeMs)

    private fun palm(pointerId: Int, x: Float, y: Float, timeMs: Long) =
        TestTouchFactory.palm(pointerId, x, y, timeMs)

    private fun stylus(pointerId: Int, x: Float, y: Float, timeMs: Long) =
        TestTouchFactory.pen(
            pointerId = pointerId, x = x, y = y, timeMs = timeMs,
            toolType = TestTouchFactory.TOOL_STYLUS,
        )

    @Test
    fun fingerWriter_firstPalmSecond_keepsDrawing() {
        val e = engine()

        val down = e.process(
            TestTouchFactory.frame(
                InputAction.DOWN, 0L, listOf(fingertip(1, 300f, 400f, 0L)), added = 1,
            )
        )
        assertEquals(ContactClassification.WRITING, down.contactFor(1)?.classification)
        assertEquals(1, down.activeWritingPointerId)

        val palmJoins = e.process(
            TestTouchFactory.frame(
                InputAction.POINTER_DOWN, 10L,
                listOf(fingertip(1, 300f, 400f, 0L), palm(2, 600f, 800f, 10L)), added = 2,
            )
        )
        assertEquals(1, palmJoins.activeWritingPointerId)
        assertEquals(ContactClassification.PALM, palmJoins.contactFor(2)?.classification)

        val move = e.process(
            TestTouchFactory.frame(
                InputAction.MOVE, 20L,
                listOf(fingertip(1, 340f, 420f, 0L), palm(2, 600f, 800f, 10L)),
            )
        )
        assertEquals(ContactClassification.WRITING, move.contactFor(1)?.classification)
        assertEquals(1, move.activeWritingPointerId)
    }

    @Test
    fun fingerWriter_palmFirstFingerSecond_writes() {
        val e = engine()

        e.process(
            TestTouchFactory.frame(
                InputAction.DOWN, 0L, listOf(palm(2, 600f, 800f, 0L)), added = 2,
            )
        )
        val fingerDown = e.process(
            TestTouchFactory.frame(
                InputAction.POINTER_DOWN, 10L,
                listOf(palm(2, 600f, 800f, 0L), fingertip(1, 300f, 400f, 10L)), added = 1,
            )
        )
        // The finger claims the lock (a resting palm is already down)…
        assertEquals(1, fingerDown.activeWritingPointerId)

        val move = e.process(
            TestTouchFactory.frame(
                InputAction.MOVE, 20L,
                listOf(palm(2, 600f, 800f, 0L), fingertip(1, 340f, 420f, 10L)),
            )
        )
        assertEquals(ContactClassification.WRITING, move.contactFor(1)?.classification)
        assertEquals(1, move.activeWritingPointerId)
        assertEquals(ContactClassification.PALM, move.contactFor(2)?.classification)
    }

    @Test
    fun hardwareStylus_winsOverPalmInBothOrders() {
        val penFirst = engine()
        penFirst.process(
            TestTouchFactory.frame(
                InputAction.DOWN, 0L, listOf(stylus(0, 300f, 400f, 0L)), added = 0,
            )
        )
        val palmJoins = penFirst.process(
            TestTouchFactory.frame(
                InputAction.POINTER_DOWN, 10L,
                listOf(stylus(0, 300f, 400f, 0L), palm(2, 600f, 800f, 10L)), added = 2,
            )
        )
        assertEquals(0, palmJoins.activeWritingPointerId)
        assertEquals(ContactClassification.WRITING, palmJoins.contactFor(0)?.classification)

        val palmFirst = engine()
        palmFirst.process(
            TestTouchFactory.frame(
                InputAction.DOWN, 0L, listOf(palm(2, 600f, 800f, 0L)), added = 2,
            )
        )
        val penJoins = palmFirst.process(
            TestTouchFactory.frame(
                InputAction.POINTER_DOWN, 10L,
                listOf(palm(2, 600f, 800f, 0L), stylus(0, 300f, 400f, 10L)), added = 0,
            )
        )
        assertEquals(0, penJoins.activeWritingPointerId)
        assertEquals(ContactClassification.WRITING, penJoins.contactFor(0)?.classification)
    }

    @Test
    fun twoFingerGesture_withRestingPalm_navigatesWithoutDrawing() {
        val e = engine()

        e.process(
            TestTouchFactory.frame(
                InputAction.DOWN, 0L, listOf(palm(2, 600f, 800f, 0L)), added = 2,
            )
        )
        // First finger claims the lock while the palm rests…
        val finger1 = e.process(
            TestTouchFactory.frame(
                InputAction.POINTER_DOWN, 10L,
                listOf(palm(2, 600f, 800f, 0L), fingertip(1, 300f, 400f, 10L)), added = 1,
            )
        )
        assertEquals(1, finger1.activeWritingPointerId)

        // …then a second finger joins: the pair becomes a pan/zoom gesture and
        // the lock is released so the palm cannot steer navigation.
        val finger2 = e.process(
            TestTouchFactory.frame(
                InputAction.POINTER_DOWN, 20L,
                listOf(
                    palm(2, 600f, 800f, 0L),
                    fingertip(1, 300f, 400f, 10L),
                    fingertip(3, 500f, 400f, 20L),
                ), added = 3,
            )
        )
        assertNull(finger2.activeWritingPointerId)
        assertTrue(finger2.gesturePointerIds.containsAll(listOf(1, 3)))
        assertEquals(ContactClassification.PALM, finger2.contactFor(2)?.classification)
    }

    @Test
    fun restingPairNearPalm_neverDrawsOrGestures() {
        val e = engine()

        // Palm plus two stationary fingertips resting together: nothing may
        // draw and nothing may drive gestures.
        e.process(
            TestTouchFactory.frame(
                InputAction.DOWN, 0L, listOf(palm(2, 600f, 800f, 0L)), added = 2,
            )
        )
        e.process(
            TestTouchFactory.frame(
                InputAction.POINTER_DOWN, 10L,
                listOf(palm(2, 600f, 800f, 0L), fingertip(1, 620f, 500f, 10L)), added = 1,
            )
        )
        // Lift the writing finger again so only the resting hand remains, then
        // two fresh stationary fingers land together with the palm.
        e.process(
            TestTouchFactory.frame(
                InputAction.POINTER_UP, 20L,
                listOf(palm(2, 600f, 800f, 0L)), lifted = 1,
            )
        )
        e.process(
            TestTouchFactory.frame(
                InputAction.POINTER_DOWN, 30L,
                listOf(
                    palm(2, 600f, 800f, 0L),
                    fingertip(1, 620f, 500f, 30L),
                    fingertip(3, 700f, 520f, 30L),
                ), added = 3,
            )
        )
        val settled = e.process(
            TestTouchFactory.frame(
                InputAction.MOVE, 600L,
                listOf(
                    palm(2, 600f, 800f, 0L),
                    fingertip(1, 620f, 500f, 30L),
                    fingertip(3, 700f, 520f, 30L),
                ),
            )
        )
        assertNull(settled.activeWritingPointerId)
        assertTrue(settled.gesturePointerIds.isEmpty())
    }
}
