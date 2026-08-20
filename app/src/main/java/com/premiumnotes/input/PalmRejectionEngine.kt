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
    private val restingTracker = RestingHandTracker(capabilities)

    /**
     * The user-reserved palm rest zone resolved to screen pixels, or null when disabled.
     * Updated by the canvas every frame (it knows the zone and its own size).
     */
    private var palmZoneRect: PalmZoneRect? = null

    fun setPalmZoneRect(rect: PalmZoneRect?) {
        palmZoneRect = rect
    }

    /** Viewport size in screen px (used by the resting-hand tracker for edge detection). */
    fun setViewportSize(widthPx: Int, heightPx: Int) {
        restingTracker.viewportWidthPx = widthPx.toFloat()
        restingTracker.viewportHeightPx = heightPx.toFloat()
    }

    fun reset() {
        lock.reset(System.nanoTime())
        pointerStates.clear()
        classifier.resetHistory()
        restingTracker.reset()
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

        // Master switch: with palm rejection off nothing is ever rejected or buffered —
        // every contact behaves as plain writable/finger input.
        if (!currentSettings.palmRejectionEnabled) {
            return processWithoutPalmRejection(frame)
        }

        // Normalize every active contact once so the relative classifier can compare the
        // CURRENT frame's contact sizes against each other.
        val normalized = frame.contacts.map { normalizer.normalize(it) }
        val activeSizesMm = normalized.map { it.maxDimMm }

        val baseClassified = mutableListOf<ClassifiedContact>()
        for (contact in normalized) {
            val state = pointerStates[contact.pointerId]

            // The user-reserved palm rest zone is authoritative for FINGER contacts: any
            // finger contact whose center falls inside it is the resting palm. It can never
            // be the writer and can never drive a gesture. Hardware tools (stylus/eraser)
            // are deliberately exempt — the zone is set aside for the palm, and a pen that
            // wanders into it must keep writing, otherwise strokes are lost. An already
            // locked writing pointer is exempt so an in-progress stroke crossing the zone
            // is not cut mid-stroke.
            val isFingerContact = contact.toolType == ToolKind.FINGER ||
                contact.toolType == ToolKind.UNKNOWN
            val inZone = palmZoneRect?.contains(contact.x, contact.y) == true &&
                isFingerContact
            if (inZone && lock.activePointerId != contact.pointerId) {
                val classifiedContact = ClassifiedContact(
                    contact = contact,
                    classification = ContactClassification.PALM,
                    confidence = 1f,
                    reason = ClassificationReason.IN_PALM_ZONE,
                    effectiveThresholdMm = 0f,
                    speedMmPerSec = state?.speedMmPerSec ?: 0f,
                    durationMs = state?.let { (nowNanos - it.downTimeNanos) / 1_000_000L } ?: 0L,
                )
                baseClassified += classifiedContact
                classifier.updateHistory(classifiedContact)
                continue
            }

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
            baseClassified += classifiedContact
            // Feed the decision back so the adaptive single-pointer fallback learns this
            // device's real contact-size scale.
            classifier.updateHistory(classifiedContact)
        }

        // Resting-hand layer: adds motion/timing/cluster/edge evidence on top of the size
        // decision. It may buffer small contacts in a resting context (CANDIDATE), mark
        // stationary fingers RESTING, promote a moving candidate to WRITING, or cancel a
        // drawing pointer whose smoothed contact size grew palm-like.
        val trackerResult = restingTracker.process(
            frame,
            baseClassified,
            lock.activePointerId,
            currentSettings,
        )
        val classified = trackerResult.classified

        // The lock may change AFTER manageWritingLock runs (a candidate promoted to the
        // writer on a MOVE frame, or a locked pointer cancelled by palm-growth). Re-read it
        // after applying the tracker's lock decisions so the frame reflects the real writer.
        manageWritingLock(frame, classified, nowNanos)
        applyTrackerLockChanges(frame, trackerResult, nowNanos)
        val finalWritingPointerId = lock.activePointerId
        val gestureIds = selectGesturePointers(classified, finalWritingPointerId)

        return ClassifiedFrame(
            frame = frame,
            contacts = classified,
            activeWritingPointerId = finalWritingPointerId,
            gesturePointerIds = gestureIds,
            clusterBounds = trackerResult.clusterBounds,
        )
    }

    /**
     * Fallback used when [PalmRejectionSettings.palmRejectionEnabled] is false: the size
     * classifier, palm zone and resting-hand tracker are all bypassed. Hardware pens always
     * write, erasers erase, a lone finger writes when finger writing is enabled, and any
     * finger pair pans/zooms — no contact is ever rejected.
     */
    private fun processWithoutPalmRejection(frame: InputFrame): ClassifiedFrame {
        val nowNanos = frame.eventTimeNanos
        val classified = frame.contacts.map { contact ->
            val normalized = normalizer.normalize(contact)
            val classification: ContactClassification
            val reason: ClassificationReason
            when (normalized.toolType) {
                ToolKind.STYLUS -> {
                    classification = ContactClassification.WRITING
                    reason = ClassificationReason.HARDWARE_STYLUS
                }
                ToolKind.ERASER -> {
                    classification = ContactClassification.ERASER
                    reason = ClassificationReason.HARDWARE_ERASER
                }
                else -> {
                    if (currentSettings.enableFingerWriting && frame.pointerCount == 1) {
                        classification = ContactClassification.WRITING
                        reason = ClassificationReason.FINGER_WRITING
                    } else {
                        classification = ContactClassification.FINGER
                        reason = ClassificationReason.SMALL_CONTACT
                    }
                }
            }
            ClassifiedContact(
                contact = normalized,
                classification = classification,
                confidence = 0.8f,
                reason = reason,
                effectiveThresholdMm = 0f,
                speedMmPerSec = 0f,
                durationMs = normalized.ageMs(nowNanos),
            )
        }
        val writingPointerId = manageWritingLock(frame, classified, nowNanos)
        val gestureIds = selectGesturePointers(classified, writingPointerId)
        return ClassifiedFrame(frame, classified, writingPointerId, gestureIds)
    }

    /**
     * Applies the resting-hand tracker's writing-lock decisions that happen outside the
     * DOWN/POINTER_DOWN claim path: promoting a buffered candidate to the lock on a MOVE
     * frame, and cancelling a locked pointer whose smoothed size grew palm-like.
     */
    private fun applyTrackerLockChanges(
        frame: InputFrame,
        result: RestingHandTracker.Result,
        nowNanos: Long,
    ) {
        val cancelId = result.cancelLockPointerId
        if (cancelId != null && lock.activePointerId == cancelId) {
            lock.reset(nowNanos)
        }
        val promoteId = result.promoteCandidatePointerId
        if (promoteId != null && !lock.isActive && frame.action == InputAction.MOVE) {
            lock.tryClaim(promoteId, nowNanos, respectHoldoff = false)
        }
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
                    startX = c.contact.x,
                    startY = c.contact.y,
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
                            // A palm is already resting but holds no lock: the newly added
                            // WRITING-classified contact (a pen, or a finger with finger
                            // writing enabled) is the intended writer and claims the lock.
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
                            if (nonPalm) {
                                // A second contact landed while a lock is active. Two cases:
                                //  - The locked pointer is genuinely small (a pen/finger) and
                                //    the new contact is finger-sized too: this is a two-finger
                                //    pan/zoom intent — drop the lock so the pair navigates.
                                //  - The lock is held by a false-positive palm (a resting palm
                                //    whose size fell in the finger/writing band while alone) and
                                //    the new contact is the real writer: hand the lock to it so
                                //    writing works even with the palm still resting — otherwise
                                //    the user can never draw while the palm is down.
                                //
                                // A contact is the real writer when it is WRITING-classified
                                // (a hardware stylus is always the writer, regardless of size)
                                // or when it is clearly smaller than the falsely-locked palm.
                                val locked = classified.firstOrNull { it.contact.pointerId == lock.activePointerId }
                                val lockedDim = locked?.contact?.maxDimMm ?: 0f
                                val addedDim = added.contact.maxDimMm
                                val addedIsConfirmedWriter = added.classification == ContactClassification.WRITING
                                val addedClearlySmaller = lockedDim > 0f && addedDim > 0f &&
                                    lockedDim / addedDim >= PalmClassifier.PALM_HANDOFF_RATIO
                                val handOff = addedIsConfirmedWriter ||
                                    (addedClearlySmaller && currentSettings.enableFingerWriting)
                                if (handOff) {
                                    lock.reset(nowNanos)
                                    lock.tryClaim(addedId, nowNanos, respectHoldoff = false)
                                } else {
                                    lock.reset(nowNanos)
                                }
                            }
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
