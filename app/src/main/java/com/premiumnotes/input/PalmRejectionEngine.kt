package com.premiumnotes.input

import kotlin.math.hypot

/**
 * Orchestrates the palm rejection pipeline for one canvas: it tracks per-pointer motion
 * state (velocity, duration), runs the [PalmClassifier], manages the [WritingLock], and
 * decides which pointers are allowed to drive pan/zoom gestures.
 *
 * The engine is stateful per page/canvas session. It keeps only lightweight numeric
 * per-pointer state — no per-event object churn — so it is safe to run on the input
 * thread every frame.
 */
class PalmRejectionEngine(
    private val capabilities: InputCapabilities,
    private val settings: PalmRejectionSettings,
    private val classifier: PalmClassifier = PalmClassifier(settings),
    private val lock: WritingLock = WritingLock(settings.writingHoldoffMs),
) {
    private val normalizer = InputNormalizer(capabilities)
    private val pointerStates = HashMap<Int, PointerMotionState>()

    fun reset() {
        lock.reset(System.nanoTime())
        pointerStates.clear()
    }

    fun process(frame: InputFrame): ClassifiedFrame {
        val nowNanos = frame.eventTimeNanos
        val ctx = buildContext(frame, nowNanos)

        val classified = mutableListOf<ClassifiedContact>()
        for (raw in frame.contacts) {
            val contact = normalizer.normalize(raw)
            val state = pointerStates[contact.pointerId]
            val result = classifier.classify(
                contact,
                PalmClassifier.ClassifyContext(
                    mode = settings.mode,
                    pointerCount = frame.pointerCount,
                    activeWritingPointerId = lock.activePointerId,
                    writingLockActive = lock.isActive,
                    contactSpeedMmPerSec = state?.speedMmPerSec ?: 0f,
                    contactDurationMs = state?.let { (nowNanos - it.downTimeNanos) / 1_000_000L } ?: 0L,
                )
            )
            classified += ClassifiedContact(
                contact = contact,
                classification = result.classification,
                confidence = result.confidence,
                reason = result.reason,
                effectiveThresholdMm = result.effectiveThresholdMm,
                speedMmPerSec = state?.speedMmPerSec ?: 0f,
                durationMs = state?.let { (nowNanos - it.downTimeNanos) / 1_000_000L } ?: 0L,
            )
        }

        val writingPointerId = manageWritingLock(frame, classified, nowNanos)
        val gestureIds = selectGesturePointers(classified, writingPointerId)

        return ClassifiedFrame(
            frame = frame,
            contacts = classified,
            activeWritingPointerId = writingPointerId,
            gesturePointerIds = gestureIds,
        )
    }

    private fun buildContext(frame: InputFrame, nowNanos: Long): PalmClassifier.ClassifyContext =
        PalmClassifier.ClassifyContext(
            mode = settings.mode,
            pointerCount = frame.pointerCount,
            activeWritingPointerId = lock.activePointerId,
            writingLockActive = lock.isActive,
        )

    private fun manageWritingLock(
        frame: InputFrame,
        classified: List<ClassifiedContact>,
        nowNanos: Long,
    ): Int? {
        // Track per-pointer motion state for contacts that are down.
        for (c in classified) {
            val cid = c.contact.pointerId
            val s = pointerStates[cid]
            if (s == null) {
                pointerStates[cid] = PointerMotionState(
                    downTimeNanos = c.contact.downTimeNanos,
                    lastX = c.contact.x,
                    lastY = c.contact.y,
                    lastTimeNanos = c.contact.eventTimeNanos,
                )
            } else {
                val dtSec = (c.contact.eventTimeNanos - s.lastTimeNanos).coerceAtLeast(1L) / 1_000_000_000.0
                if (dtSec > 0.0) {
                    val distPx = hypot(c.contact.x - s.lastX, c.contact.y - s.lastY)
                    s.speedMmPerSec = (capabilities.dimFromPx(distPx) / dtSec.toFloat())
                    s.lastX = c.contact.x
                    s.lastY = c.contact.y
                    s.lastTimeNanos = c.contact.eventTimeNanos
                }
                s.rawSampleCount++
            }
        }

        when (frame.action) {
            InputAction.DOWN, InputAction.POINTER_DOWN -> {
                // A down can only establish the lock. Only the newly added pointer is
                // eligible — already-down pointers are gestures/palm and must not claim.
                if (!lock.isActive && frame.addedPointerId != null) {
                    val candidate = classified.firstOrNull {
                        it.contact.pointerId == frame.addedPointerId &&
                            it.classification == ContactClassification.WRITING
                    }
                    if (candidate != null) {
                        lock.tryClaim(frame.addedPointerId, nowNanos)
                    }
                }
            }

            InputAction.UP, InputAction.POINTER_UP -> {
                val lifted = frame.liftedPointerId
                if (lifted != null) {
                    lock.release(lifted, nowNanos)
                    pointerStates.remove(lifted)
                }
            }

            InputAction.CANCEL -> {
                lock.reset(nowNanos)
                pointerStates.clear()
            }

            InputAction.MOVE -> Unit
        }

        // Defensive: never leave a stale lock for a pointer no longer present.
        if (lock.activePointerId != null) {
            val stillDown = frame.contacts.map { it.pointerId }.toSet()
            if (lock.activePointerId !in stillDown) {
                lock.reset(nowNanos)
            }
        }

        return lock.activePointerId
    }

    /**
     * Gesture pointers are contacts allowed to pan/zoom. Rules:
     *  - While a writing lock is active, no gestures (a resting palm must not pan).
     *  - Otherwise up to two contacts classified as FINGER (normal finger interaction).
     */
    private fun selectGesturePointers(
        classified: List<ClassifiedContact>,
        writingPointerId: Int?,
    ): List<Int> {
        if (writingPointerId != null) return emptyList()

        return classified
            .filter { it.classification == ContactClassification.FINGER }
            .take(2)
            .map { it.contact.pointerId }
    }
}
