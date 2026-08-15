package com.premiumnotes.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integration tests driving the full [PalmRejectionEngine] with synthetic streams that
 * simulate the real hardware scenarios: pen writing, resting palm, pen+palm together,
 * finger gestures, and pointer cancellation.
 */
class PalmRejectionEngineTest {

    private fun engine(mode: PalmRejectionMode = PalmRejectionMode.WRITING) =
        PalmRejectionEngine(testCapabilities()) { testSettings(mode) }

    @Test
    fun smallPenDownBecomesActiveWritingPointer() {
        val e = engine()
        val pen = TestTouchFactory.pen(pointerId = 0, timeMs = 0L)
        val out = e.process(TestTouchFactory.frame(InputAction.DOWN, 0L, listOf(pen), added = 0))
        assertEquals(0, out.activeWritingPointerId)
        assertEquals(ContactClassification.WRITING, out.contactFor(0)?.classification)
        // No gestures allowed once writing.
        assertTrue(out.gesturePointerIds.isEmpty())
    }

    @Test
    fun largePalmDownNeverClaimsWriting() {
        val e = engine()
        val palm = TestTouchFactory.palm(pointerId = 2, timeMs = 0L)
        val out = e.process(TestTouchFactory.frame(InputAction.DOWN, 0L, listOf(palm), added = 2))
        assertNull(out.activeWritingPointerId)
        assertEquals(ContactClassification.PALM, out.contactFor(2)?.classification)
    }

    @Test
    fun palmRestingWhileWritingIsRejectedAndLockPersists() {
        val e = engine()

        // 1. Pen writes.
        val penDown = TestTouchFactory.pen(pointerId = 0, timeMs = 0L)
        e.process(TestTouchFactory.frame(InputAction.DOWN, 0L, listOf(penDown), added = 0))

        // 2. Palm lands while pen still down.
        val penMove = TestTouchFactory.pen(pointerId = 0, x = 120f, y = 110f, timeMs = 30L)
        val palmDown = TestTouchFactory.palm(pointerId = 2, timeMs = 30L)
        val withPalm = e.process(
            TestTouchFactory.frame(InputAction.POINTER_DOWN, 30L, listOf(penMove, palmDown), added = 2)
        )
        assertEquals(0, withPalm.activeWritingPointerId)
        assertEquals(ContactClassification.PALM, withPalm.contactFor(2)?.classification)
        assertTrue(withPalm.gesturePointerIds.isEmpty())

        // 3. Palm moves around; pen still writing; palm never takes the lock.
        val palmMove = TestTouchFactory.palm(pointerId = 2, x = 520f, y = 740f, timeMs = 50L)
        val penMove2 = TestTouchFactory.pen(pointerId = 0, x = 140f, y = 130f, timeMs = 50L)
        val mid = e.process(TestTouchFactory.frame(InputAction.MOVE, 50L, listOf(penMove2, palmMove)))
        assertEquals(0, mid.activeWritingPointerId)
        assertEquals(ContactClassification.PALM, mid.contactFor(2)?.classification)

        // 4. Palm lifts; pen continues; then pen lifts.
        e.process(TestTouchFactory.frame(InputAction.POINTER_UP, 70L, listOf(penMove2), lifted = 2))
        val penUp = TestTouchFactory.pen(pointerId = 0, x = 150f, y = 140f, timeMs = 90L)
        val after = e.process(TestTouchFactory.frame(InputAction.UP, 90L, listOf(penUp), lifted = 0))
        assertNull(after.activeWritingPointerId)
    }

    @Test
    fun pointerCannotStealLockWhilePenActive() {
        val e = engine()
        e.process(TestTouchFactory.frame(InputAction.DOWN, 0L, listOf(TestTouchFactory.pen(0, timeMs = 0L)), added = 0))
        // A second small contact appears while the pen is active.
        val finger = TestTouchFactory.fingertip(pointerId = 3, timeMs = 20L)
        val pen = TestTouchFactory.pen(0, x = 100f, y = 100f, timeMs = 20L)
        val out = e.process(TestTouchFactory.frame(InputAction.POINTER_DOWN, 20L, listOf(pen, finger), added = 3))
        assertEquals(0, out.activeWritingPointerId)
        // In WRITING mode the secondary contact is rejected, never FINGER/gesture.
        assertTrue(out.gesturePointerIds.isEmpty())
    }

    @Test
    fun twoFingerGesturesAllowedWhenNothingIsWriting() {
        val e = engine(PalmRejectionMode.BALANCED)
        val f1 = TestTouchFactory.fingertip(pointerId = 0, x = 100f, timeMs = 0L)
        val f2 = TestTouchFactory.fingertip(pointerId = 1, x = 300f, timeMs = 0L)
        val out = e.process(TestTouchFactory.frame(InputAction.POINTER_DOWN, 0L, listOf(f1, f2), added = 1))
        assertNull(out.activeWritingPointerId)
        assertEquals(listOf(0, 1), out.gesturePointerIds)
    }

    @Test
    fun cancelDiscardsLockAndPointerState() {
        val e = engine()
        e.process(TestTouchFactory.frame(InputAction.DOWN, 0L, listOf(TestTouchFactory.pen(0, timeMs = 0L)), added = 0))
        val out = e.process(TestTouchFactory.frame(InputAction.CANCEL, 10L, emptyList()))
        assertNull(out.activeWritingPointerId)
    }

    @Test
    fun penAfterPalmEstablishesLock() {
        val e = engine()
        e.process(TestTouchFactory.frame(InputAction.DOWN, 0L, listOf(TestTouchFactory.palm(2, timeMs = 0L)), added = 2))
        val palmLift = e.process(TestTouchFactory.frame(InputAction.UP, 20L, listOf(TestTouchFactory.palm(2, x = 500f, y = 700f, timeMs = 20L)), lifted = 2))
        assertNull(palmLift.activeWritingPointerId)

        val out = e.process(TestTouchFactory.frame(InputAction.DOWN, 40L, listOf(TestTouchFactory.pen(0, timeMs = 40L)), added = 0))
        assertEquals(0, out.activeWritingPointerId)
    }

    @Test
    fun fingerWritesInWritingModeWhenFingerWritingEnabled() {
        val e = PalmRejectionEngine(testCapabilities()) {
            testSettings(mode = PalmRejectionMode.WRITING).apply { enableFingerWriting = true }
        }
        val finger = TestTouchFactory.fingertip(pointerId = 0, timeMs = 0L)
        val out = e.process(TestTouchFactory.frame(InputAction.DOWN, 0L, listOf(finger), added = 0))
        assertEquals(0, out.activeWritingPointerId)
        assertEquals(ContactClassification.WRITING, out.contactFor(0)?.classification)
    }

    @Test
    fun palmRestingWhileFingerWritesIsRejected() {
        val e = PalmRejectionEngine(testCapabilities()) {
            testSettings(mode = PalmRejectionMode.WRITING).apply { enableFingerWriting = true }
        }

        // Finger starts writing.
        e.process(TestTouchFactory.frame(InputAction.DOWN, 0L, listOf(TestTouchFactory.fingertip(0, timeMs = 0L)), added = 0))

        // Palm lands while the finger is still down; the lock must stay on the finger.
        val fingerMove = TestTouchFactory.fingertip(0, x = 220f, y = 220f, timeMs = 20L)
        val palmDown = TestTouchFactory.palm(pointerId = 2, timeMs = 20L)
        val out = e.process(
            TestTouchFactory.frame(InputAction.POINTER_DOWN, 20L, listOf(fingerMove, palmDown), added = 2)
        )
        assertEquals(0, out.activeWritingPointerId)
        assertEquals(ContactClassification.PALM, out.contactFor(2)?.classification)
        assertTrue(out.gesturePointerIds.isEmpty())
    }
}