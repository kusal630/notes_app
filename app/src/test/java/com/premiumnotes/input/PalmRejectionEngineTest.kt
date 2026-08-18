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

    // --- Phase 3: two palms/heels resting simultaneously with pen writing --------------

    @Test
    fun twoPalmsRestingWithPenAcceptsOnlyTheGenuinelySmallest() {
        val e = engine()

        // Palm 1 lands alone first (cold start) -> rejected as palm, no lock.
        val palmFirst = e.process(
            TestTouchFactory.frame(InputAction.DOWN, 0L, listOf(TestTouchFactory.palm(pointerId = 2, timeMs = 0L)), added = 2)
        )
        assertNull(palmFirst.activeWritingPointerId)
        assertEquals(ContactClassification.PALM, palmFirst.contactFor(2)?.classification)

        // Palm 2 lands while palm 1 still rests -> rejected too (even the smallest active
        // contact is palm-sized, so nothing here is genuinely small). No gestures.
        val palm1 = TestTouchFactory.palm(pointerId = 2, x = 500f, y = 700f, timeMs = 20L)
        val palm2 = TestTouchFactory.palm(pointerId = 4, x = 800f, y = 900f, timeMs = 20L)
        val twoPalms = e.process(
            TestTouchFactory.frame(InputAction.POINTER_DOWN, 20L, listOf(palm1, palm2), added = 4)
        )
        assertNull(twoPalms.activeWritingPointerId)
        assertEquals(ContactClassification.PALM, twoPalms.contactFor(2)?.classification)
        assertEquals(ContactClassification.PALM, twoPalms.contactFor(4)?.classification)
        assertTrue(twoPalms.gesturePointerIds.isEmpty())

        // Pen joins while both palms rest: it is the genuinely smallest contact and must
        // claim writing immediately; both larger contacts stay rejected.
        val pen = TestTouchFactory.pen(pointerId = 0, x = 120f, y = 110f, timeMs = 40L)
        val out = e.process(
            TestTouchFactory.frame(InputAction.POINTER_DOWN, 40L, listOf(palm1, palm2, pen), added = 0)
        )
        assertEquals(0, out.activeWritingPointerId)
        assertEquals(ContactClassification.WRITING, out.contactFor(0)?.classification)
        assertEquals(ContactClassification.PALM, out.contactFor(2)?.classification)
        assertEquals(ContactClassification.PALM, out.contactFor(4)?.classification)
        assertTrue(out.gesturePointerIds.isEmpty())
    }

    // --- Phase 3: adaptive single-pointer fallback after history exists ----------------

    @Test
    fun lonePalmAfterPenStrokesIsRejectedByAdaptiveHistory() {
        val e = engine()

        // A pen stroke seeds the confirmed-small range and establishes/lifts the lock.
        e.process(TestTouchFactory.frame(InputAction.DOWN, 0L, listOf(TestTouchFactory.pen(0, timeMs = 0L)), added = 0))
        e.process(
            TestTouchFactory.frame(InputAction.UP, 10L, listOf(TestTouchFactory.pen(0, x = 100f, y = 100f, timeMs = 10L)), lifted = 0)
        )

        // A lone palm lands after the pen lifted -> rejected via the adaptive fallback:
        // it is far larger than the device's confirmed-small range. No drawing, no lock.
        val out = e.process(
            TestTouchFactory.frame(InputAction.DOWN, 30L, listOf(TestTouchFactory.palm(2, timeMs = 30L)), added = 2)
        )
        assertNull(out.activeWritingPointerId)
        assertEquals(ContactClassification.PALM, out.contactFor(2)?.classification)
    }

    @Test
    fun loneFingerTapAfterPenStrokesStaysValid() {
        val e = engine()

        // A pen stroke seeds the confirmed-small range.
        e.process(TestTouchFactory.frame(InputAction.DOWN, 0L, listOf(TestTouchFactory.pen(0, timeMs = 0L)), added = 0))
        e.process(
            TestTouchFactory.frame(InputAction.UP, 10L, listOf(TestTouchFactory.pen(0, x = 100f, y = 100f, timeMs = 10L)), lifted = 0)
        )

        // A lone fingertip (UI tap) after pen strokes must NOT be misclassified as a palm.
        val out = e.process(
            TestTouchFactory.frame(InputAction.DOWN, 30L, listOf(TestTouchFactory.fingertip(3, timeMs = 30L)), added = 3)
        )
        val cls = out.contactFor(3)?.classification
        assertTrue(cls == ContactClassification.FINGER || cls == ContactClassification.WRITING)
    }

    // --- Bug regression: resting palm must never permanently block writing -------------

    /** A medium palm (~15mm) whose size sits in the finger/writing band. */
    private fun mediumPalm(
        pointerId: Int = 2,
        x: Float = 500f,
        y: Float = 700f,
        timeMs: Long = 0L,
    ) = TestTouchFactory.contact(
        pointerId, x, y, timeMs,
        majorPx = 150f, minorPx = 120f, pressure = 1f, size = 0.28f,
    )

    @Test
    fun penTakesOverWritingLockFromFalselyLockedMediumPalm() {
        val e = engine()

        // A medium palm alone is below the finger threshold and is misclassified WRITING,
        // claiming the writing lock — the root cause of "palm on screen then nothing writes".
        val palmAlone = e.process(
            TestTouchFactory.frame(InputAction.DOWN, 0L, listOf(mediumPalm(timeMs = 0L)), added = 2)
        )
        assertEquals(2, palmAlone.activeWritingPointerId)
        assertEquals(ContactClassification.WRITING, palmAlone.contactFor(2)?.classification)

        // A genuinely small pen contact lands while the palm still rests. It is ~5.8x
        // smaller than the locked contact, so the lock must be handed to it — NOT dropped,
        // which would turn both contacts into a two-finger gesture that swallows the stroke.
        val palm = mediumPalm(timeMs = 20L)
        val pen = TestTouchFactory.pen(pointerId = 0, x = 120f, y = 110f, timeMs = 20L)
        val out = e.process(
            TestTouchFactory.frame(InputAction.POINTER_DOWN, 20L, listOf(palm, pen), added = 0)
        )
        assertEquals(0, out.activeWritingPointerId)
        assertTrue(out.gesturePointerIds.isEmpty())

        // Pen keeps writing with the palm still resting: lock stays on the pen, no gesture.
        val move = e.process(
            TestTouchFactory.frame(
                InputAction.MOVE, 30L,
                listOf(mediumPalm(x = 500f, y = 700f, timeMs = 30L), TestTouchFactory.pen(0, x = 140f, y = 130f, timeMs = 30L)),
            )
        )
        assertEquals(0, move.activeWritingPointerId)
        assertEquals(ContactClassification.WRITING, move.contactFor(0)?.classification)
        assertTrue(move.gesturePointerIds.isEmpty())
    }

    @Test
    fun fingerWritingRecoversAfterAmbiguousGestureWithMediumPalm() {
        val e = engine()

        // Medium palm rests alone (false lock), then a similar-sized fingertip lands.
        e.process(
            TestTouchFactory.frame(InputAction.DOWN, 0L, listOf(mediumPalm(timeMs = 0L)), added = 2)
        )
        val palm = mediumPalm(timeMs = 20L)
        val finger = TestTouchFactory.fingertip(pointerId = 0, x = 220f, y = 220f, timeMs = 20L)
        val gesture = e.process(
            TestTouchFactory.frame(InputAction.POINTER_DOWN, 20L, listOf(palm, finger), added = 0)
        )
        // The fingertip is NOT dramatically smaller than the palm (ratio < 2.5), so this is
        // an (ambiguous) two-finger gesture: the false lock drops and the pair may navigate.
        assertNull(gesture.activeWritingPointerId)
        assertEquals(2, gesture.gesturePointerIds.size)

        // Everything lifts.
        e.process(TestTouchFactory.frame(InputAction.POINTER_UP, 40L, listOf(mediumPalm(x = 500f, y = 700f, timeMs = 40L)), lifted = 0))
        e.process(TestTouchFactory.frame(InputAction.UP, 50L, listOf(mediumPalm(x = 500f, y = 700f, timeMs = 50L)), lifted = 2))

        // Writing alone afterwards must work — no stuck lock, no swallowed strokes.
        val alone = e.process(
            TestTouchFactory.frame(InputAction.DOWN, 70L, listOf(TestTouchFactory.fingertip(0, x = 220f, y = 220f, timeMs = 70L)), added = 0)
        )
        assertEquals(0, alone.activeWritingPointerId)
        assertEquals(ContactClassification.WRITING, alone.contactFor(0)?.classification)
    }
}