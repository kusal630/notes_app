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
    fun secondFingerWhilePenActiveDropsLockForTwoFingerGesture() {
        val e = engine()
        e.process(TestTouchFactory.frame(InputAction.DOWN, 0L, listOf(TestTouchFactory.pen(0, timeMs = 0L)), added = 0))
        // A finger-sized second contact while a pen tool is active is a gesture intent,
        // not a palm: the lock drops so the pair can pan/zoom out of the page bottom.
        val finger = TestTouchFactory.fingertip(pointerId = 3, timeMs = 20L)
        val pen = TestTouchFactory.pen(0, x = 100f, y = 100f, timeMs = 20L)
        val out = e.process(TestTouchFactory.frame(InputAction.POINTER_DOWN, 20L, listOf(pen, finger), added = 3))
        assertNull(out.activeWritingPointerId)
        assertEquals(listOf(0, 3), out.gesturePointerIds)
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

    @Test
    fun secondFingerDuringPenDropsLockAndEnablesGesture() {
        val e = PalmRejectionEngine(testCapabilities()) {
            testSettings(mode = PalmRejectionMode.WRITING).apply { enableFingerWriting = true }
        }
        // Pen starts writing.
        e.process(TestTouchFactory.frame(InputAction.DOWN, 0L, listOf(TestTouchFactory.pen(0, timeMs = 0L)), added = 0))

        // A finger-sized second contact is a gesture intent (not a palm): the lock is
        // released so the pair can pan/zoom even though a pen tool is selected.
        val pen = TestTouchFactory.pen(0, x = 110f, y = 105f, timeMs = 20L)
        val finger = TestTouchFactory.fingertip(pointerId = 3, timeMs = 20L)
        val out = e.process(
            TestTouchFactory.frame(InputAction.POINTER_DOWN, 20L, listOf(pen, finger), added = 3)
        )
        assertNull(out.activeWritingPointerId)
        assertEquals(ContactClassification.WRITING, out.contactFor(0)?.classification)
        assertEquals(listOf(0, 3), out.gesturePointerIds)
    }

    @Test
    fun palmSizedSecondContactWhileWritingKeepsLock() {
        val e = PalmRejectionEngine(testCapabilities()) {
            testSettings(mode = PalmRejectionMode.WRITING).apply { enableFingerWriting = true }
        }
        e.process(TestTouchFactory.frame(InputAction.DOWN, 0L, listOf(TestTouchFactory.pen(0, timeMs = 0L)), added = 0))

        val pen = TestTouchFactory.pen(0, x = 120f, y = 110f, timeMs = 20L)
        val palm = TestTouchFactory.palm(pointerId = 2, timeMs = 20L)
        val out = e.process(
            TestTouchFactory.frame(InputAction.POINTER_DOWN, 20L, listOf(pen, palm), added = 2)
        )
        // A palm-sized contact is the resting hand: lock persists, no gesture.
        assertEquals(0, out.activeWritingPointerId)
        assertEquals(ContactClassification.PALM, out.contactFor(2)?.classification)
        assertTrue(out.gesturePointerIds.isEmpty())
    }

    // --- Phase 1: palm resting FIRST, then a writing contact lands elsewhere ----------

    @Test
    fun penTouchesWhilePalmAlreadyRestingClaimsWritingLock() {
        val e = engine()
        // Scenario (a): palm rests alone first — correctly rejected, no writing lock.
        val palmFirst = e.process(
            TestTouchFactory.frame(InputAction.DOWN, 0L, listOf(TestTouchFactory.palm(2, timeMs = 0L)), added = 2)
        )
        assertNull(palmFirst.activeWritingPointerId)
        assertEquals(ContactClassification.PALM, palmFirst.contactFor(2)?.classification)

        // Scenario (b): pen touches elsewhere while the palm is still resting. The newly
        // added WRITING pointer must claim the writing lock on POINTER_DOWN.
        val palm = TestTouchFactory.palm(2, x = 500f, y = 700f, timeMs = 20L)
        val pen = TestTouchFactory.pen(pointerId = 0, x = 120f, y = 110f, timeMs = 20L)
        val out = e.process(
            TestTouchFactory.frame(InputAction.POINTER_DOWN, 20L, listOf(palm, pen), added = 0)
        )
        assertEquals(0, out.activeWritingPointerId)
        assertEquals(ContactClassification.WRITING, out.contactFor(0)?.classification)
        assertEquals(ContactClassification.PALM, out.contactFor(2)?.classification)
        // The resting palm must never enable gestures while writing is locked.
        assertTrue(out.gesturePointerIds.isEmpty())
    }

    @Test
    fun fingerTouchesWhilePalmAlreadyRestingClaimsWritingLock() {
        val e = PalmRejectionEngine(testCapabilities()) {
            testSettings(mode = PalmRejectionMode.WRITING).apply { enableFingerWriting = true }
        }
        e.process(
            TestTouchFactory.frame(InputAction.DOWN, 0L, listOf(TestTouchFactory.palm(2, timeMs = 0L)), added = 2)
        )
        // Scenario (c): a bare finger touches elsewhere while the palm rests.
        val palm = TestTouchFactory.palm(2, x = 500f, y = 700f, timeMs = 20L)
        val finger = TestTouchFactory.fingertip(pointerId = 0, x = 220f, y = 220f, timeMs = 20L)
        val out = e.process(
            TestTouchFactory.frame(InputAction.POINTER_DOWN, 20L, listOf(palm, finger), added = 0)
        )
        assertEquals(0, out.activeWritingPointerId)
        assertEquals(ContactClassification.WRITING, out.contactFor(0)?.classification)
        assertTrue(out.gesturePointerIds.isEmpty())
    }

    // --- Phase 1: no mid-stroke flip-flop ----------------------------------------------

    @Test
    fun palmRapidOnOffWhileWritingKeepsLockContinuous() {
        val e = engine()
        e.process(TestTouchFactory.frame(InputAction.DOWN, 0L, listOf(TestTouchFactory.pen(0, timeMs = 0L)), added = 0))

        // Palm lands (POINTER_DOWN): lock persists.
        val withPalm = e.process(
            TestTouchFactory.frame(
                InputAction.POINTER_DOWN, 20L,
                listOf(TestTouchFactory.pen(0, x = 120f, y = 110f, timeMs = 20L), TestTouchFactory.palm(2, timeMs = 20L)),
                added = 2,
            )
        )
        assertEquals(0, withPalm.activeWritingPointerId)

        // Palm lifts (POINTER_UP): lock stays on the pen.
        val palmLift = e.process(
            TestTouchFactory.frame(InputAction.POINTER_UP, 30L, listOf(TestTouchFactory.pen(0, x = 140f, y = 130f, timeMs = 30L)), lifted = 2)
        )
        assertEquals(0, palmLift.activeWritingPointerId)

        // Palm re-lands while pen still down: lock stays.
        val palmAgain = e.process(
            TestTouchFactory.frame(
                InputAction.POINTER_DOWN, 40L,
                listOf(TestTouchFactory.pen(0, x = 160f, y = 150f, timeMs = 40L), TestTouchFactory.palm(2, x = 480f, y = 700f, timeMs = 40L)),
                added = 2,
            )
        )
        assertEquals(0, palmAgain.activeWritingPointerId)

        // Pen continues writing with both contacts down: lock persists every frame.
        val move = e.process(
            TestTouchFactory.frame(
                InputAction.MOVE, 50L,
                listOf(TestTouchFactory.pen(0, x = 200f, y = 190f, timeMs = 50L), TestTouchFactory.palm(2, x = 460f, y = 710f, timeMs = 50L)),
            )
        )
        assertEquals(0, move.activeWritingPointerId)
        assertEquals(ContactClassification.WRITING, move.contactFor(0)?.classification)
        assertTrue(move.gesturePointerIds.isEmpty())
    }

    @Test
    fun borderlinePenReclassificationNeverDropsLockMidStroke() {
        val e = engine()
        // Pen establishes the writing lock normally.
        e.process(TestTouchFactory.frame(InputAction.DOWN, 0L, listOf(TestTouchFactory.pen(0, timeMs = 0L)), added = 0))

        // A later frame reports the SAME pointer with a degenerate/palm-sized contact
        // (e.g. the digitizer briefly saturates). The sticky lock must not flip-flop:
        // the pointer stays the active writer and no gesture is granted.
        val bloatedPen = TestTouchFactory.palm(0, x = 140f, y = 130f, timeMs = 20L)
        val out = e.process(TestTouchFactory.frame(InputAction.MOVE, 20L, listOf(bloatedPen)))
        assertEquals(0, out.activeWritingPointerId)
        assertTrue(out.gesturePointerIds.isEmpty())

        // And the pen keeps writing on the next normal frame.
        val next = e.process(
            TestTouchFactory.frame(InputAction.MOVE, 30L, listOf(TestTouchFactory.pen(0, x = 170f, y = 160f, timeMs = 30L)))
        )
        assertEquals(0, next.activeWritingPointerId)
    }
}