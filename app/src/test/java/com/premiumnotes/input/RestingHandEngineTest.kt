package com.premiumnotes.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integration tests for the resting-hand layer: several small contacts resting on the
 * screen while one of them (or a fresh one) actually writes. These are the scenarios the
 * size-only classifier cannot resolve (resting fingers are fingertip-sized), so they are
 * driven entirely by motion/timing/cluster/edge evidence from the [RestingHandTracker].
 */
class RestingHandEngineTest {

    private var settings = testSettings()

    private fun engine(configure: PalmRejectionSettings.() -> Unit = {}) =
        PalmRejectionEngine(testCapabilities()) {
            settings = testSettings().apply(configure)
            settings
        }

    private fun fingertip(pointerId: Int, x: Float, y: Float, timeMs: Long) =
        TestTouchFactory.fingertip(pointerId, x, y, timeMs)

    /** A fingertip-sized writing contact (≈9mm) — the "pen" in resting-hand scenarios. */
    private fun writer(pointerId: Int, x: Float, y: Float, timeMs: Long) =
        TestTouchFactory.pen(pointerId, x, y, timeMs, majorPx = 90f, minorPx = 82f)

    @Test
    fun newContactInRestingContextIsBufferedThenPromotedWhenItMoves() {
        val e = engine()

        // Two resting fingers land first.
        e.process(TestTouchFactory.frame(
            InputAction.DOWN, 0L, listOf(fingertip(1, 150f, 300f, 0L)), added = 1))
        e.process(TestTouchFactory.frame(
            InputAction.POINTER_DOWN, 10L,
            listOf(fingertip(1, 150f, 300f, 0L), fingertip(3, 500f, 300f, 10L)), added = 3))

        // A writer lands while resting fingers are down: 3+ contacts with several small
        // ones -> it is buffered as CANDIDATE and does NOT claim the lock yet.
        val buffered = e.process(TestTouchFactory.frame(
            InputAction.POINTER_DOWN, 20L,
            listOf(
                fingertip(1, 150f, 300f, 0L),
                fingertip(3, 500f, 300f, 10L),
                writer(0, 100f, 100f, 20L),
            ), added = 0))
        assertEquals(ContactClassification.CANDIDATE, buffered.contactFor(0)?.classification)
        assertNull(buffered.activeWritingPointerId)
        assertTrue(buffered.gesturePointerIds.isEmpty())

        // It moves like a stroke: promoted to WRITING and the writing lock is claimed.
        val promoted = e.process(TestTouchFactory.frame(
            InputAction.MOVE, 30L,
            listOf(
                fingertip(1, 150f, 300f, 0L),
                fingertip(3, 500f, 300f, 10L),
                writer(0, 140f, 140f, 20L),
            )))
        assertEquals(ContactClassification.WRITING, promoted.contactFor(0)?.classification)
        assertEquals(ClassificationReason.PROMOTED_TO_WRITING, promoted.contactFor(0)?.reason)
        assertEquals(0, promoted.activeWritingPointerId)

        // The lock is sticky: it keeps writing on the next MOVE frame.
        val steady = e.process(TestTouchFactory.frame(
            InputAction.MOVE, 40L,
            listOf(
                fingertip(1, 150f, 300f, 0L),
                fingertip(3, 500f, 300f, 10L),
                writer(0, 180f, 180f, 20L),
            )))
        assertEquals(0, steady.activeWritingPointerId)
        assertEquals(ContactClassification.WRITING, steady.contactFor(0)?.classification)
    }

    @Test
    fun stationaryRestingFingersBecomeRestingAndNeverDrawOrGesture() {
        val e = engine()

        // Three similar-sized fingers land (the first two establish, the third completes
        // the resting context).
        e.process(TestTouchFactory.frame(
            InputAction.DOWN, 0L, listOf(fingertip(1, 150f, 150f, 0L)), added = 1))
        e.process(TestTouchFactory.frame(
            InputAction.POINTER_DOWN, 10L,
            listOf(fingertip(1, 150f, 150f, 0L), fingertip(3, 500f, 200f, 10L)), added = 3))
        e.process(TestTouchFactory.frame(
            InputAction.POINTER_DOWN, 20L,
            listOf(
                fingertip(1, 150f, 150f, 0L),
                fingertip(3, 500f, 200f, 10L),
                fingertip(4, 800f, 250f, 20L),
            ), added = 4))

        // All three are buffered while still.
        val held = e.process(TestTouchFactory.frame(
            InputAction.MOVE, 100L,
            listOf(
                fingertip(1, 150f, 150f, 0L),
                fingertip(3, 500f, 200f, 10L),
                fingertip(4, 800f, 250f, 20L),
            )))
        assertEquals(ContactClassification.CANDIDATE, held.contactFor(1)?.classification)

        // Long enough with no movement: every finger is RESTING, no lock, no gesture.
        val resting = e.process(TestTouchFactory.frame(
            InputAction.MOVE, 400L,
            listOf(
                fingertip(1, 150f, 150f, 0L),
                fingertip(3, 500f, 200f, 10L),
                fingertip(4, 800f, 250f, 20L),
            )))
        for (id in listOf(1, 3, 4)) {
            assertEquals(ContactClassification.RESTING, resting.contactFor(id)?.classification)
        }
        assertNull(resting.activeWritingPointerId)
        assertTrue(resting.gesturePointerIds.isEmpty())
    }

    @Test
    fun twoFingersStillGestureWithARestingPalmPresent() {
        val e = engine()

        // A large palm rests; two fingers land on top of it (same frame, so neither one
        // is ever the lone writer).
        e.process(TestTouchFactory.frame(
            InputAction.DOWN, 0L, listOf(TestTouchFactory.palm(2, timeMs = 0L)), added = 2))
        val down = e.process(TestTouchFactory.frame(
            InputAction.POINTER_DOWN, 10L,
            listOf(
                TestTouchFactory.palm(2, timeMs = 0L),
                fingertip(1, 100f, 100f, 10L),
                fingertip(3, 300f, 100f, 10L),
            ), added = 3))
        assertNull(down.activeWritingPointerId)

        // Both fingers move together: they are reclassified FINGER and become a gesture
        // pair even though a palm is resting.
        val moving = e.process(TestTouchFactory.frame(
            InputAction.MOVE, 20L,
            listOf(
                TestTouchFactory.palm(2, timeMs = 0L),
                fingertip(1, 150f, 100f, 10L),
                fingertip(3, 350f, 100f, 10L),
            )))
        assertEquals(ContactClassification.FINGER, moving.contactFor(1)?.classification)
        assertEquals(ContactClassification.FINGER, moving.contactFor(3)?.classification)
        assertNull(moving.activeWritingPointerId)
        assertTrue(moving.gesturePointerIds.containsAll(listOf(1, 3)))
    }

    @Test
    fun lockedWriterCancelledOnSustainedPalmGrowth() {
        val e = engine()

        // Pen starts a stroke (small contact).
        e.process(TestTouchFactory.frame(
            InputAction.DOWN, 0L, listOf(TestTouchFactory.pen(0, timeMs = 0L)), added = 0))

        // Its contact grows to palm size and STAYS there. The smoothed size takes several
        // frames to cross the hysteresis threshold; once it does, the lock is cancelled
        // and the pointer is reclassified as a palm.
        var out: ClassifiedFrame? = null
        var cancelled = false
        var i = 1
        while (i <= 15) {
            out = e.process(TestTouchFactory.frame(
                InputAction.MOVE, (10L * i),
                listOf(TestTouchFactory.pen(0, x = 100f, y = 100f, timeMs = 0L, majorPx = 300f, minorPx = 260f)),
            ))
            if (out.activeWritingPointerId == null) {
                cancelled = true
                break
            }
            i++
        }
        assertTrue(cancelled)
        assertEquals(ContactClassification.PALM, out?.contactFor(0)?.classification)
        assertEquals(ClassificationReason.PALM_GROWTH_CANCELLED, out?.contactFor(0)?.reason)
        assertNull(out?.activeWritingPointerId)
    }

    @Test
    fun singleSizeSpikeNeverCancelsLockedWriter() {
        val e = engine()

        // Pen starts a stroke.
        e.process(TestTouchFactory.frame(
            InputAction.DOWN, 0L, listOf(TestTouchFactory.pen(0, timeMs = 0L)), added = 0))

        // One frame reports an absurd palm-sized contact (digitizer spike).
        val spike = e.process(TestTouchFactory.frame(
            InputAction.MOVE, 10L,
            listOf(TestTouchFactory.pen(0, x = 100f, y = 100f, timeMs = 0L, majorPx = 400f, minorPx = 380f)),
        ))
        assertEquals(0, spike.activeWritingPointerId)

        // The very next frame is normal again; the stroke keeps going (hysteresis).
        val normal = e.process(TestTouchFactory.frame(
            InputAction.MOVE, 20L,
            listOf(TestTouchFactory.pen(0, x = 120f, y = 120f, timeMs = 0L)),
        ))
        assertEquals(0, normal.activeWritingPointerId)
        assertEquals(ContactClassification.WRITING, normal.contactFor(0)?.classification)
    }

    @Test
    fun palmRejectionDisabledWritesEverything() {
        val e = engine { palmRejectionEnabled = false }

        // Even a palm-sized contact is accepted and writes when the pipeline is off.
        val out = e.process(TestTouchFactory.frame(
            InputAction.DOWN, 0L, listOf(TestTouchFactory.palm(2, timeMs = 0L)), added = 2))
        assertEquals(ContactClassification.WRITING, out.contactFor(2)?.classification)
        assertEquals(2, out.activeWritingPointerId)
    }

    @Test
    fun restingModeDisabledRestoresLegacyImmediateLock() {
        val e = engine { restingHandModeEnabled = false }

        // A palm rests, then two fingers land on top of it in one frame. Without the
        // resting-hand layer the newly added contact keeps its base classification and
        // claims the writing lock immediately — no CANDIDATE buffering.
        e.process(TestTouchFactory.frame(
            InputAction.DOWN, 0L, listOf(TestTouchFactory.palm(2, timeMs = 0L)), added = 2))
        val out = e.process(TestTouchFactory.frame(
            InputAction.POINTER_DOWN, 10L,
            listOf(
                TestTouchFactory.palm(2, timeMs = 0L),
                fingertip(1, 100f, 100f, 10L),
                fingertip(3, 300f, 100f, 10L),
            ), added = 3))
        assertEquals(ContactClassification.WRITING, out.contactFor(3)?.classification)
        assertEquals(3, out.activeWritingPointerId)
    }

    @Test
    fun restingFingerNearScreenEdgeIsRestingNotWritable() {
        val e = engine()

        // A contact rests within the edge margin (30mm = 300px) of the screen edge while
        // other contacts are present; once stationary long enough it is RESTING.
        e.process(TestTouchFactory.frame(
            InputAction.DOWN, 0L, listOf(TestTouchFactory.palm(2, timeMs = 0L)), added = 2))
        e.process(TestTouchFactory.frame(
            InputAction.POINTER_DOWN, 10L,
            listOf(
                TestTouchFactory.palm(2, timeMs = 0L),
                fingertip(1, 50f, 80f, 10L),
                fingertip(3, 500f, 200f, 10L),
            ), added = 3))

        val resting = e.process(TestTouchFactory.frame(
            InputAction.MOVE, 500L,
            listOf(
                TestTouchFactory.palm(2, timeMs = 0L),
                fingertip(1, 50f, 80f, 10L),
                fingertip(3, 500f, 200f, 10L),
            )))
        assertEquals(ContactClassification.RESTING, resting.contactFor(1)?.classification)
        assertEquals(ClassificationReason.RESTING_EDGE, resting.contactFor(1)?.reason)
    }

    @Test
    fun velocityBelowThresholdKeepsCandidateResting() {
        val e = engine()

        // A palm rests, then two fingers land (both buffered as CANDIDATE).
        e.process(TestTouchFactory.frame(
            InputAction.DOWN, 0L, listOf(TestTouchFactory.palm(2, timeMs = 0L)), added = 2))
        e.process(TestTouchFactory.frame(
            InputAction.POINTER_DOWN, 10L,
            listOf(
                TestTouchFactory.palm(2, timeMs = 0L),
                fingertip(1, 100f, 100f, 10L),
                fingertip(3, 300f, 100f, 10L),
            ), added = 3))

        // Finger 3 drifts slowly (20px = 2mm over 50ms = 40mm/s, below the 120mm/s stroke
        // gate). Even though it moved > jitter, it must NOT promote and must NOT become a
        // gesture: it stays CANDIDATE.
        val slow = e.process(TestTouchFactory.frame(
            InputAction.MOVE, 60L,
            listOf(
                TestTouchFactory.palm(2, timeMs = 0L),
                fingertip(1, 100f, 100f, 10L),
                fingertip(3, 320f, 100f, 10L),
            )))
        assertEquals(ContactClassification.CANDIDATE, slow.contactFor(3)?.classification)
        assertNull(slow.activeWritingPointerId)
        assertTrue(slow.gesturePointerIds.isEmpty())

        // Once it stops moving for the evaluation window it is demoted to RESTING.
        val resting = e.process(TestTouchFactory.frame(
            InputAction.MOVE, 400L,
            listOf(
                TestTouchFactory.palm(2, timeMs = 0L),
                fingertip(1, 100f, 100f, 10L),
                fingertip(3, 320f, 100f, 10L),
            )))
        assertEquals(ContactClassification.RESTING, resting.contactFor(3)?.classification)
    }

    @Test
    fun slowHandShiftDoesNotBlockWriterPromotion() {
        val e = engine()

        // A palm rests and two fingers land (both buffered as CANDIDATE).
        e.process(TestTouchFactory.frame(
            InputAction.DOWN, 0L, listOf(TestTouchFactory.palm(2, timeMs = 0L)), added = 2))
        e.process(TestTouchFactory.frame(
            InputAction.POINTER_DOWN, 10L,
            listOf(
                TestTouchFactory.palm(2, timeMs = 0L),
                fingertip(1, 100f, 100f, 10L),
                fingertip(3, 300f, 100f, 10L),
            ), added = 3))
        e.process(TestTouchFactory.frame(
            InputAction.MOVE, 100L,
            listOf(
                TestTouchFactory.palm(2, timeMs = 0L),
                fingertip(1, 100f, 100f, 10L),
                fingertip(3, 300f, 100f, 10L),
            )))

        // Both fingers shift slowly and coherently (16px over 40ms = 40mm/s): a whole-hand
        // drift, not a stroke and not a gesture. They are reclassified RESTING via the
        // hand-shift rule.
        val shifted = e.process(TestTouchFactory.frame(
            InputAction.MOVE, 140L,
            listOf(
                TestTouchFactory.palm(2, timeMs = 0L),
                fingertip(1, 116f, 100f, 10L),
                fingertip(3, 316f, 100f, 10L),
            )))
        assertEquals(ContactClassification.RESTING, shifted.contactFor(1)?.classification)
        assertEquals(ClassificationReason.HAND_SHIFT_DRIFT, shifted.contactFor(1)?.reason)
        assertEquals(ContactClassification.RESTING, shifted.contactFor(3)?.classification)
        assertTrue(shifted.gesturePointerIds.isEmpty())

        // A writer lands afterwards and moves fast: still promoted even though a slow hand
        // shift was just rejected.
        e.process(TestTouchFactory.frame(
            InputAction.POINTER_DOWN, 150L,
            listOf(
                TestTouchFactory.palm(2, timeMs = 0L),
                fingertip(1, 116f, 100f, 10L),
                fingertip(3, 316f, 100f, 10L),
                writer(0, 100f, 400f, 150L),
            ), added = 0))
        val promoted = e.process(TestTouchFactory.frame(
            InputAction.MOVE, 160L,
            listOf(
                TestTouchFactory.palm(2, timeMs = 0L),
                fingertip(1, 116f, 100f, 10L),
                fingertip(3, 316f, 100f, 10L),
                writer(0, 180f, 400f, 150L),
            )))
        assertEquals(ContactClassification.WRITING, promoted.contactFor(0)?.classification)
        assertEquals(ClassificationReason.PROMOTED_TO_WRITING, promoted.contactFor(0)?.reason)
        assertEquals(0, promoted.activeWritingPointerId)
    }

    @Test
    fun fastStrokeAfterPauseStillPromotes() {
        val e = engine()

        // Three fingers land and rest long enough to become RESTING.
        e.process(TestTouchFactory.frame(
            InputAction.DOWN, 0L, listOf(fingertip(1, 150f, 300f, 0L)), added = 1))
        e.process(TestTouchFactory.frame(
            InputAction.POINTER_DOWN, 10L,
            listOf(fingertip(1, 150f, 300f, 0L), fingertip(3, 500f, 300f, 10L)), added = 3))
        e.process(TestTouchFactory.frame(
            InputAction.POINTER_DOWN, 20L,
            listOf(
                fingertip(1, 150f, 300f, 0L),
                fingertip(3, 500f, 300f, 10L),
                fingertip(4, 800f, 300f, 20L),
            ), added = 4))
        e.process(TestTouchFactory.frame(
            InputAction.MOVE, 100L,
            listOf(
                fingertip(1, 150f, 300f, 0L),
                fingertip(3, 500f, 300f, 10L),
                fingertip(4, 800f, 300f, 20L),
            )))
        val resting = e.process(TestTouchFactory.frame(
            InputAction.MOVE, 400L,
            listOf(
                fingertip(1, 150f, 300f, 0L),
                fingertip(3, 500f, 300f, 10L),
                fingertip(4, 800f, 300f, 20L),
            )))
        assertEquals(ContactClassification.RESTING, resting.contactFor(1)?.classification)

        // After the pause, finger 1 breaks into a fast stroke: it is promoted to the writer.
        val resumed = e.process(TestTouchFactory.frame(
            InputAction.MOVE, 410L,
            listOf(
                fingertip(1, 260f, 320f, 0L),
                fingertip(3, 500f, 300f, 10L),
                fingertip(4, 800f, 300f, 20L),
            )))
        assertEquals(ContactClassification.WRITING, resumed.contactFor(1)?.classification)
        assertEquals(ClassificationReason.PROMOTED_TO_WRITING, resumed.contactFor(1)?.reason)
        assertEquals(1, resumed.activeWritingPointerId)
    }

    @Test
    fun allowImmediateDrawWhenIsolatedFalseBuffersIsolatedContact() {
        // A lone contact normally draws immediately; with the setting off it is observed
        // as a CANDIDATE until it moves like a stroke.
        val buffered = engine { allowImmediateDrawWhenIsolated = false }
        val down = buffered.process(TestTouchFactory.frame(
            InputAction.DOWN, 0L, listOf(writer(0, 100f, 100f, 0L)), added = 0))
        assertEquals(ContactClassification.CANDIDATE, down.contactFor(0)?.classification)
        assertNull(down.activeWritingPointerId)

        val promoted = buffered.process(TestTouchFactory.frame(
            InputAction.MOVE, 10L, listOf(writer(0, 180f, 100f, 0L))))
        assertEquals(ContactClassification.WRITING, promoted.contactFor(0)?.classification)
        assertEquals(0, promoted.activeWritingPointerId)

        // Control: with the default (true) the same lone contact writes immediately.
        val immediate = engine()
        val downDefault = immediate.process(TestTouchFactory.frame(
            InputAction.DOWN, 0L, listOf(writer(0, 100f, 100f, 0L)), added = 0))
        assertEquals(ContactClassification.WRITING, downDefault.contactFor(0)?.classification)
        assertEquals(0, downDefault.activeWritingPointerId)
    }

    @Test
    fun adaptiveNoiseRaisesPromoteVelocity() {
        // Setup: palm + two resting fingers. The fingers then either stay perfectly still
        // (control) or produce steady low-velocity jitter (noisy). In both engines a new
        // writer moves at 160mm/s — above the 120mm/s configured minimum.
        fun restingFrames(e: PalmRejectionEngine, finger1x: Float, finger3x: Float) {
            e.process(TestTouchFactory.frame(
                InputAction.DOWN, 0L, listOf(TestTouchFactory.palm(2, timeMs = 0L)), added = 2))
            e.process(TestTouchFactory.frame(
                InputAction.POINTER_DOWN, 10L,
                listOf(
                    TestTouchFactory.palm(2, timeMs = 0L),
                    fingertip(1, 100f, 100f, 10L),
                    fingertip(3, 300f, 100f, 10L),
                ), added = 3))
            // Stationary long enough to become RESTING.
            e.process(TestTouchFactory.frame(
                InputAction.MOVE, 400L,
                listOf(
                    TestTouchFactory.palm(2, timeMs = 0L),
                    fingertip(1, 100f, 100f, 10L),
                    fingertip(3, 300f, 100f, 10L),
                )))
            // A few frames of either stillness or slow drift (11px/10ms = 110mm/s, still
            // below the 120mm/s gate so the fingers stay RESTING while producing noise).
            for (t in 1..3) {
                val tMs = 400L + t * 10L
                e.process(TestTouchFactory.frame(
                    InputAction.MOVE, tMs,
                    listOf(
                        TestTouchFactory.palm(2, timeMs = 0L),
                        fingertip(1, 100f + finger1x * t, 100f, 10L),
                        fingertip(3, 300f + finger3x * t, 100f, 10L),
                    )))
            }
        }

        // Control: resting fingers perfectly still -> noise stays ~0 -> the 160mm/s mover
        // is promoted.
        val control = engine()
        restingFrames(control, 0f, 0f)
        control.process(TestTouchFactory.frame(
            InputAction.POINTER_DOWN, 440L,
            listOf(
                TestTouchFactory.palm(2, timeMs = 0L),
                fingertip(1, 100f, 100f, 10L),
                fingertip(3, 300f, 100f, 10L),
                writer(0, 900f, 500f, 440L),
            ), added = 0))
        for (t in 1..3) {
            val tMs = 440L + t * 10L
            control.process(TestTouchFactory.frame(
                InputAction.MOVE, tMs,
                listOf(
                    TestTouchFactory.palm(2, timeMs = 0L),
                    fingertip(1, 100f, 100f, 10L),
                    fingertip(3, 300f, 100f, 10L),
                    writer(0, 900f + 16f * t, 500f, 440L),
                )))
        }
        val controlResult = control.process(TestTouchFactory.frame(
            InputAction.MOVE, 470L,
            listOf(
                TestTouchFactory.palm(2, timeMs = 0L),
                fingertip(1, 100f, 100f, 10L),
                fingertip(3, 300f, 100f, 10L),
                writer(0, 948f, 500f, 440L),
            )))
        assertEquals(ContactClassification.WRITING, controlResult.contactFor(0)?.classification)
        assertEquals(0, controlResult.activeWritingPointerId)

        // Noisy: the resting fingers jitter at ~110mm/s. The adaptive noise estimate raises
        // the effective stroke gate well above 160mm/s, so the SAME mover stays CANDIDATE.
        val noisy = engine()
        restingFrames(noisy, 11f, 11f)
        noisy.process(TestTouchFactory.frame(
            InputAction.POINTER_DOWN, 440L,
            listOf(
                TestTouchFactory.palm(2, timeMs = 0L),
                fingertip(1, 100f + 11f * 3f, 100f, 10L),
                fingertip(3, 300f + 11f * 3f, 100f, 10L),
                writer(0, 900f, 500f, 440L),
            ), added = 0))
        for (t in 1..3) {
            val tMs = 440L + t * 10L
            noisy.process(TestTouchFactory.frame(
                InputAction.MOVE, tMs,
                listOf(
                    TestTouchFactory.palm(2, timeMs = 0L),
                    fingertip(1, 100f + 11f * (3f + t), 100f, 10L),
                    fingertip(3, 300f + 11f * (3f + t), 100f, 10L),
                    writer(0, 900f + 16f * t, 500f, 440L),
                )))
        }
        val noisyResult = noisy.process(TestTouchFactory.frame(
            InputAction.MOVE, 470L,
            listOf(
                TestTouchFactory.palm(2, timeMs = 0L),
                fingertip(1, 100f + 11f * 6f, 100f, 10L),
                fingertip(3, 300f + 11f * 6f, 100f, 10L),
                writer(0, 948f, 500f, 440L),
            )))
        assertTrue(
            "adaptive noise should keep the 160mm/s mover from promoting",
            (noisyResult.contactFor(0)?.windowedVelocityMmPerSec ?: 0f) > 120f,
        )
        assertEquals(ContactClassification.CANDIDATE, noisyResult.contactFor(0)?.classification)
        assertNull(noisyResult.activeWritingPointerId)
    }
}