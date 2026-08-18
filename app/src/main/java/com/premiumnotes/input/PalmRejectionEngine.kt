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
    private val settingsProvider: () -> PalmRejectionSettings,
) {
    private val normalizer = InputNormalizer(capabilities)
    private val pointerStates = HashMap<Int, PointerMotionState>()

    // Mutable references that are recreated only when settings actually change.
    private var currentSettings: PalmRejectionSettings = settingsProvider()
    private var classifier = PalmClassifier(currentSettings)
    private var lock = WritingLock(currentSettings.writingHoldoffMs)
    private var lastKnownHoldoffMs = currentSettings.writingHoldoffMs

    fun reset() {
        lock.reset(System.nanoTime())
        pointerStates.clear()
        classifier.resetHistory()
    }

    /** Recreates derived state only when the settings instance actually changes. */
    private fun refreshIfSettingsChanged() {
        val settings = settingsProvider()
        if (settings !== currentSettings) {
            currentSettings = settings
            if (settings.writingHoldoffMs != lastKnownHoldoffMs) {
                lock = WritingLock(settings.writingHoldoffMs)
                lastKnownHoldoffMs = settings.writingHoldoffMs
            }
            // Update the classifier's settings in place: recreating it would wipe the
            // adaptive history (the device's learned contact-size scale), which must
            // survive settings tweaks within a session.
            classifier.updateSettings(settings)
        }
    }

    fun process(frame: InputFrame): ClassifiedFrame {
        refreshIfSettingsChanged()
        val nowNanos = frame.eventTimeNanos

        // Normalize every active contact once so the relative classifier can compare the
        // CURRENT frame's contact sizes against each other.
        val normalized = frame.contacts.map { normalizer.normalize(it) }
        val activeSizesMm = normalized.map { it.maxDimMm }

        val classified = mutableListOf<ClassifiedContact>()
        for (contact in normalized) {
            val state = pointerStates[contact.pointerId]
            val result = classifier.classify(
                contact,
                PalmClassifier.ClassifyContext(
                    mode = currentSettings.mode,
                    pointerCount = frame.pointerCount,
                    activeSizesMm = activeSizesMm,
                    activeWritingPointerId = lock.activePointerId,
                    writingLockActive = lock.isActive,
                    contactSpeedMmPerSec = state?.speedMmPerSec ?: 0f,
                    contactDurationMs = state?.let { (nowNanos - it.downTimeNanos) / 1_000_000L } ?: 0L,
                    fingerWritingEnabled = currentSettings.enableFingerWriting,
                )
            )
            val classifiedContact = ClassifiedContact(
                contact = contact,
                classification = result.classification,
                confidence = result.confidence,
                reason = result.reason,
                effectiveThresholdMm = result.effectiveThresholdMm,
                speedMmPerSec = state?.speedMmPerSec ?: 0f,
                durationMs = state?.let { (nowNanos - it.downTimeNanos) / 1_000_000L } ?: 0L,
            )
            classified += classifiedContact
            // Feed the decision back so the adaptive single-pointer fallback learns this
            // device's real contact-size scale.
            classifier.updateHistory(classifiedContact)
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
            InputAction.DOWN -> {
                // A down can only establish the lock. Only the newly added pointer is
                // eligible — already-down pointers are gestures/palm and must not claim.
                if (!lock.isActive && frame.addedPointerId != null) {
                    val candidate = classified.firstOrNull {
                        it.contact.pointerId == frame.addedPointerId &&
                            it.classification == ContactClassification.WRITING
                    }
                    if (candidate != null) {
                        // Finger writing lifts the hold-off so fast consecutive strokes
                        // are never dropped; a genuine palm is never a WRITING candidate.
                        lock.tryClaim(
                            frame.addedPointerId,
                            nowNanos,
                            respectHoldoff = !currentSettings.enableFingerWriting,
                        )
                    }
                }
            }

            InputAction.POINTER_DOWN -> {
                // A new contact landed while another pointer is already down. Two cases:
                //  1. No writing lock yet (e.g. a resting palm was the first contact). A
                //     newly added WRITING-classified pointer (pen, or a finger with finger
                //     writing enabled) must be able to claim the lock so it can start
                //     drawing even though the palm is still resting.
                //  2. A writing lock is active and a second contact joins. If that contact
                //     is finger-sized it is the start of a two-finger gesture (pan/zoom):
                //     release the writing lock so the pair can drive navigation. A resting
                //     palm is classified PALM and never triggers this; the in-progress
                //     stroke is finalized by the view.
                val addedId = frame.addedPointerId
                if (addedId != null) {
                    val added = classified.firstOrNull { it.contact.pointerId == addedId }
                    if (added != null) {
                        if (!lock.isActive) {
                            if (added.classification == ContactClassification.WRITING) {
                                lock.tryClaim(
                                    addedId,
                                    nowNanos,
                                    respectHoldoff = !currentSettings.enableFingerWriting,
                                )
                            }
                        } else {
                            val nonPalm = added.classification == ContactClassification.WRITING ||
                                added.classification == ContactClassification.FINGER
                            if (nonPalm) lock.reset(nowNanos)
                        }
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
            val activeId = lock.activePointerId
            if (frame.contacts.none { it.pointerId == activeId }) {
                lock.reset(nowNanos)
            }
        }

        return lock.activePointerId
    }

    /**
     * Gesture pointers are contacts allowed to pan/zoom. Rules:
     *  - While a writing lock is active, no gestures (a resting palm must not pan).
     *  - Otherwise up to two non-palm contacts (WRITING fingertips/stylus or FINGER).
     *    WRITING is included because after the lock is dropped by a second contact the
     *    original pointer is still WRITING-classified and must remain part of the gesture.
     */
    private fun selectGesturePointers(
        classified: List<ClassifiedContact>,
        writingPointerId: Int?,
    ): List<Int> {
        if (writingPointerId != null) return emptyList()

        return classified
            .filter {
                it.classification == ContactClassification.WRITING ||
                    it.classification == ContactClassification.FINGER
            }
            .take(2)
            .map { it.contact.pointerId }
    }
}
