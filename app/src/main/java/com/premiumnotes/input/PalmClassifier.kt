package com.premiumnotes.input

/**
 * Pure per-contact classifier. Given a [NormalizedContact] and a [ClassifyContext] it
 * returns a classification. All decisions derive from real information the OS exposes;
 * hardware stylus signals are trusted only when actually present (tool type == STYLUS /
 * ERASER).
 *
 * Design rules:
 *  - A real stylus tool type is always accepted as writing; a real eraser is always an
 *    eraser. These hardware signals cannot be a palm.
 *  - **Multi-pointer**: when several pointers are active simultaneously, classification
 *    is done RELATIVELY. Raw contact-size values are not calibrated to real-world units
 *    and vary wildly across digitizers and densities, so a fixed absolute size can mean
 *    "pen" on one tablet and "palm" on another. Instead the contact is compared to the
 *    smallest active contact in the SAME frame: a contact that is at least
 *    [PALM_RATIO_VS_SMALLEST] times larger than the smallest active contact is a palm;
 *    anything else is pen/finger input. Because the comparison is relative it adapts to
 *    any hardware with no per-device calibration.
 *  - **Single-pointer fallback**: with only one contact there is nothing to compare
 *    against, so the classifier falls back to an adaptive, self-calibrating rule. It
 *    keeps a short rolling history of contact sizes confirmed as small (valid) and
 *    large (palm) — confirmed only at moments when a relative comparison was actually
 *    possible — and judges a lone new contact against that device's OWN observed range.
 *    On cold start (no history yet) the only sane option is the user-configurable
 *    settings thresholds as a generous default until the first simultaneous-pointer
 *    comparison teaches the classifier this device's real scale.
 *  - In WRITING mode a locked writing pointer is always accepted, and every secondary
 *    contact while the lock is active is treated as a palm unless it is finger-sized
 *    (a second finger starting a two-finger gesture).
 */
class PalmClassifier(
    private var settings: PalmRejectionSettings,
) {

    companion object {
        /**
         * Multi-pointer palm rejection ratio. When a contact is at least this many times
         * larger than the smallest active contact in the same frame, it is a palm.
         * Equivalently, in the two-pointer case pointer A is "significantly smaller"
         * (valid) when its size is less than 1/2.5 = 40% of pointer B's.
         *
         * A resting palm is reliably 3–10x larger than a pen tip or fingertip on the SAME
         * device, so 2.5x separates palm from pen/finger while leaving room for two
         * similar-sized fingers (pan/zoom gesture) to stay valid. This is a ratio, not an
         * absolute value, so it holds across digitizers regardless of their raw scaling.
         */
        const val PALM_RATIO_VS_SMALLEST = 2.5f

        /**
         * A contact smaller than the observed palm range divided by this factor is too
         * small to be that device's palm. Used to decide the "genuinely small" pointer
         * against the device's own confirmed-palm range.
         */
        const val PALM_RANGE_SPLIT = 2f

        /**
         * Single-pointer fallback: a lone contact within this multiple of the observed
         * "confirmed small" (valid) size is accepted as valid input. Kept generous so a
         * finger tap after pen strokes is not rejected; anything beyond it must also
         * match the confirmed-palm range or the settings backstop to be rejected.
         */
        const val VALID_OVERSIZE_MULTIPLE = 2.5f

        /**
         * Rolling-history window. The classifier keeps only the most recent samples so a
         * device's scale stays current (finger sizes and digitizer behavior drift over a
         * session as the hand warms up / applies different pressure).
         */
        const val HISTORY_WINDOW = 20
    }

    /**
     * Rolling history of observed contact sizes. Only sizes that were CONFIRMED by a
     * moment when relative comparison was possible (or by a decisive settings-based
     * classification) are fed here, so the ranges reflect this device's real scale.
     */
    private data class ClassificationHistory(
        val validSizes: ArrayDeque<Float> = ArrayDeque(),
        val palmSizes: ArrayDeque<Float> = ArrayDeque(),
    )

    private var history = ClassificationHistory()

    data class ClassifyContext(
        val mode: PalmRejectionMode = PalmRejectionMode.BALANCED,
        val pointerCount: Int = 1,
        /** Maximum contact dimensions (maxDimMm) of every pointer down in this frame. */
        val activeSizesMm: List<Float> = emptyList(),
        val activeWritingPointerId: Int? = null,
        val writingLockActive: Boolean = false,
        val contactSpeedMmPerSec: Float = 0f,
        val contactDurationMs: Long = 0L,
        /** When true, a bare finger may act as the writing pointer (palm rejection still on). */
        val fingerWritingEnabled: Boolean = false,
    )

    fun classify(contact: NormalizedContact, ctx: ClassifyContext): ClassificationResult {
        // Hardware-reported stylus/eraser: trust it completely (it cannot be a palm).
        when (contact.toolType) {
            ToolKind.STYLUS -> return result(
                ContactClassification.WRITING, 1f, ClassificationReason.HARDWARE_STYLUS, 0f, ctx
            )
            ToolKind.ERASER -> return result(
                ContactClassification.ERASER, 1f, ClassificationReason.HARDWARE_ERASER, 0f, ctx
            )
            ToolKind.MOUSE -> return result(
                ContactClassification.FINGER, 0.6f, ClassificationReason.MEDIUM_CONTACT, 0f, ctx
            )
            ToolKind.FINGER, ToolKind.UNKNOWN -> Unit
        }

        // WRITING mode with an active lock: the locked pointer is always the writer and
        // every other contact is a palm unless it is finger-sized (a second finger that
        // should start a two-finger navigation gesture rather than being a resting palm).
        if (ctx.mode == PalmRejectionMode.WRITING && ctx.writingLockActive) {
            if (ctx.activeWritingPointerId == contact.pointerId) {
                return result(
                    ContactClassification.WRITING, 1f, ClassificationReason.LOCKED_WRITING_POINTER, 0f, ctx
                )
            }
            // A secondary contact while writing. A palm-sized contact is the resting hand —
            // reject it and keep the writing lock. A smaller (finger-sized) contact is a
            // second finger starting a two-finger gesture; it is accepted so the engine can
            // drop the lock and let the pair navigate. The finger/palm split here uses the
            // user's configured finger threshold; it is the gesture-intent disambiguation,
            // not the core palm classification.
            val fingerMax = settings.effectiveFingerMaxMm()
            if (contact.maxDimMm > fingerMax) {
                return result(
                    ContactClassification.PALM, 0.95f, ClassificationReason.SECONDARY_WHILE_WRITING, fingerMax, ctx
                )
            }
            return result(ContactClassification.FINGER, 0.7f, ClassificationReason.MEDIUM_CONTACT, fingerMax, ctx)
        }

        val dim = contact.maxDimMm
        if (!contact.hasGeometry && !contact.hasSize || dim <= 0f) {
            // No size/geometry is reported (e.g. the emulator or cheap digitizers). With
            // finger writing enabled and a single contact we cannot measure a palm, so the
            // only/primary contact is accepted as writing; any secondary contact that lands
            // while the lock is active is still rejected as a palm.
            if (ctx.fingerWritingEnabled && ctx.mode == PalmRejectionMode.WRITING && ctx.pointerCount <= 1) {
                return result(ContactClassification.WRITING, 0.5f, ClassificationReason.FINGER_WRITING, 0f, ctx)
            }
            return result(ContactClassification.FINGER, 0.35f, ClassificationReason.NO_GEOMETRY_INFO, 0f, ctx)
        }

        // Multiple pointers down: classify RELATIVELY against the smallest active contact.
        if (ctx.pointerCount > 1) {
            return classifyRelative(contact, ctx)
        }

        // Single pointer: adaptive fallback using this device's observed ranges.
        return classifySingle(contact, ctx)
    }

    /**
     * Relative classification for simultaneous pointers. The smallest active contact is
     * the reference; a contact that is at least [PALM_RATIO_VS_SMALLEST] times larger is
     * a palm, and the "genuinely smallest" pointer is the valid writing input (or the
     * first finger of a two-finger gesture). Ambiguous mid-size contacts are resolved
     * against the device's own confirmed ranges, and default to a finger when nothing is
     * known (two similar-sized contacts are far more likely a two-finger gesture than a
     * palm, and a resting palm is rejected by the settings backstop / lock instead).
     */
    private fun classifyRelative(contact: NormalizedContact, ctx: ClassifyContext): ClassificationResult {
        // Only sizes we can actually measure participate in the comparison.
        val measurable = ctx.activeSizesMm.filter { it > 0f }
        val minActive = measurable.minOrNull() ?: return classifySingle(contact, ctx)

        val dim = contact.maxDimMm
        val ratioVsSmallest = dim / minActive

        // Clearly the large outlier => palm. This is the primary relative discriminator.
        if (ratioVsSmallest >= PALM_RATIO_VS_SMALLEST) {
            return result(ContactClassification.PALM, 0.9f, ClassificationReason.LARGE_CONTACT, minActive, ctx)
        }

        val avgValid = history.validSizes.averageOrNull()
        val avgPalm = history.palmSizes.averageOrNull()

        // The genuinely smallest pointer. Four cases against the device's confirmed
        // ranges:
        //  - dramatically smaller than the SECOND-smallest contact in this same frame
        //    (>= PALM_RATIO_VS_SMALLEST gap) => the writing candidate, regardless of
        //    history. A resting palm is never this much smaller than a pen/fingertip;
        //    the palm is the LARGE one. This catches a real writer landing next to a
        //    resting palm even before this device's adaptive history is seeded.
        //  - clearly much smaller than the confirmed palm size => the writing candidate;
        //  - no palm reference yet (e.g. two fingers starting a gesture) => valid finger,
        //    never a writer on its own;
        //  - even the smallest contact is itself palm-sized (two palms resting, no pen)
        //    => a palm, because nothing in this frame is genuinely small.
        if (dim <= minActive * 1.0001f) {
            if (measurable.size >= 2) {
                val secondSmallest = measurable.sorted()[1]
                if (secondSmallest / dim >= PALM_RATIO_VS_SMALLEST) {
                    return result(
                        ContactClassification.WRITING, 0.85f, ClassificationReason.SMALL_CONTACT, secondSmallest, ctx
                    )
                }
            }
            if (avgPalm != null) {
                return if (dim <= avgPalm / PALM_RANGE_SPLIT) {
                    result(ContactClassification.WRITING, 0.85f, ClassificationReason.SMALL_CONTACT, minActive, ctx)
                } else {
                    result(ContactClassification.PALM, 0.7f, ClassificationReason.LARGE_CONTACT, minActive, ctx)
                }
            }
            return result(ContactClassification.FINGER, 0.6f, ClassificationReason.MEDIUM_CONTACT, minActive, ctx)
        }

        // Larger than the smallest but not a 2.5x outlier. If it matches the confirmed
        // palm range it is a palm (e.g. a second resting palm whose digitizer reports a
        // smaller-than-typical ellipse); otherwise treat it as another finger (gesture).
        if (avgPalm != null && dim >= avgPalm / PALM_RANGE_SPLIT) {
            return result(ContactClassification.PALM, 0.6f, ClassificationReason.LARGE_CONTACT, minActive, ctx)
        }
        return result(ContactClassification.FINGER, 0.55f, ClassificationReason.MEDIUM_CONTACT, minActive, ctx)
    }

    /**
     * Single-pointer fallback. Judges a lone contact against the device's own observed
     * ranges (see class docs). On cold start — before any comparison has seeded the
     * history — it falls back to the user-configurable settings thresholds: this is the
     * deliberate, clearly-reasoned exception to the no-absolute-value rule, because with
     * no second pointer and no history there is literally nothing to compare against and
     * the settings (with optional user calibration) are the only sane default. Once the
     * first relative comparison has happened, the observed ranges take over.
     */
    private fun classifySingle(contact: NormalizedContact, ctx: ClassifyContext): ClassificationResult {
        val dim = contact.maxDimMm

        val avgValid = history.validSizes.averageOrNull()
        val avgPalm = history.palmSizes.averageOrNull()

        // --- Cold start: no observed ranges yet. Use the user's configured thresholds. ---
        if (avgValid == null && avgPalm == null) {
            return classifyWithSettings(contact, ctx)
        }

        // --- Adaptive: compare against this device's observed ranges. ---

        // Within a generous multiple of the confirmed-small size => valid input.
        if (avgValid != null && dim <= VALID_OVERSIZE_MULTIPLE * avgValid) {
            return classifyValid(contact, ctx, confidence = 0.7f)
        }

        // Matches the confirmed-palm range and is far above the confirmed-small range => palm.
        if (avgPalm != null && dim >= avgPalm / PALM_RANGE_SPLIT) {
            return result(ContactClassification.PALM, 0.7f, ClassificationReason.LARGE_CONTACT, avgPalm, ctx)
        }

        // Ambiguous: larger than anything confirmed small, but not yet matching a
        // confirmed palm. Fall back to the settings thresholds (generous default) rather
        // than guessing — this keeps a finger tap after pen strokes working while a real
        // palm is still caught by the settings backstop.
        return classifyWithSettings(contact, ctx)
    }

    /** Cold-start / settings-backstop decision using the user-configurable thresholds. */
    private fun classifyWithSettings(contact: NormalizedContact, ctx: ClassifyContext): ClassificationResult {
        val dim = contact.maxDimMm
        val writingMax = settings.effectiveWritingMaxMm()
        val fingerMax = settings.effectiveFingerMaxMm()
        val relaxedPalm = settings.effectiveRelaxedPalmMm()
        // With finger writing enabled the writing cutoff widens to the finger threshold so
        // a bare fingertip is accepted, while a resting palm stays above it and is rejected.
        val writingDim = if (ctx.fingerWritingEnabled) maxOf(writingMax, fingerMax) else writingMax

        return when (ctx.mode) {
            PalmRejectionMode.STRICT -> if (dim <= writingDim) {
                result(ContactClassification.WRITING, 0.9f, ClassificationReason.SMALL_CONTACT, writingDim, ctx)
            } else {
                result(ContactClassification.PALM, 0.9f, ClassificationReason.LARGE_CONTACT, writingDim, ctx)
            }

            PalmRejectionMode.BALANCED -> when {
                dim <= writingMax -> result(ContactClassification.WRITING, 0.9f, ClassificationReason.SMALL_CONTACT, writingMax, ctx)
                dim <= fingerMax -> result(ContactClassification.FINGER, 0.6f, ClassificationReason.MEDIUM_CONTACT, fingerMax, ctx)
                else -> result(ContactClassification.PALM, 0.9f, ClassificationReason.LARGE_CONTACT, fingerMax, ctx)
            }

            PalmRejectionMode.RELAXED -> when {
                dim <= writingMax -> result(ContactClassification.WRITING, 0.9f, ClassificationReason.SMALL_CONTACT, writingMax, ctx)
                dim <= relaxedPalm -> result(ContactClassification.FINGER, 0.6f, ClassificationReason.MEDIUM_CONTACT, relaxedPalm, ctx)
                else -> result(ContactClassification.PALM, 0.9f, ClassificationReason.LARGE_CONTACT, relaxedPalm, ctx)
            }

            PalmRejectionMode.WRITING -> if (dim <= writingDim) {
                result(ContactClassification.WRITING, 0.9f, ClassificationReason.SMALL_CONTACT, writingDim, ctx)
            } else {
                result(ContactClassification.PALM, 0.9f, ClassificationReason.LARGE_CONTACT, writingDim, ctx)
            }
        }
    }

    private fun classifyValid(contact: NormalizedContact, ctx: ClassifyContext, confidence: Float): ClassificationResult {
        val fingerMax = settings.effectiveFingerMaxMm()
        val writingMax = settings.effectiveWritingMaxMm()
        return if (ctx.mode == PalmRejectionMode.BALANCED || ctx.mode == PalmRejectionMode.RELAXED) {
            if (contact.maxDimMm <= writingMax) {
                result(ContactClassification.WRITING, confidence, ClassificationReason.SMALL_CONTACT, writingMax, ctx)
            } else if (contact.maxDimMm <= fingerMax) {
                result(ContactClassification.FINGER, confidence, ClassificationReason.MEDIUM_CONTACT, fingerMax, ctx)
            } else {
                result(ContactClassification.WRITING, confidence, ClassificationReason.SMALL_CONTACT, fingerMax, ctx)
            }
        } else {
            result(ContactClassification.WRITING, confidence, ClassificationReason.SMALL_CONTACT, writingMax, ctx)
        }
    }

    /**
     * Feeds one classification back into the rolling history. This is what lets the
     * single-pointer fallback self-calibrate to the current device. Guards keep the
     * ranges honest: a WRITING-sized sample that is dramatically larger than the other
     * confirmed-small samples is discarded (e.g. a digitizer saturation spike), and a
     * PALM-sized sample that is not clearly larger than the confirmed-small range is
     * discarded (likely a misclassification rather than a real palm).
     */
    fun updateHistory(classification: ClassifiedContact) {
        val size = classification.contact.maxDimMm
        if (size <= 0f) return

        when (classification.classification) {
            ContactClassification.WRITING, ContactClassification.FINGER -> {
                val avgValid = history.validSizes.averageOrNull()
                if (avgValid == null || size <= VALID_OVERSIZE_MULTIPLE * avgValid) {
                    push(history.validSizes, size)
                }
            }
            ContactClassification.PALM -> {
                val avgValid = history.validSizes.averageOrNull()
                if (avgValid == null || size > VALID_OVERSIZE_MULTIPLE * avgValid) {
                    push(history.palmSizes, size)
                }
            }
            else -> Unit
        }
    }

    private fun push(queue: ArrayDeque<Float>, value: Float) {
        queue.addLast(value)
        while (queue.size > HISTORY_WINDOW) queue.removeFirst()
    }

    /** Resets the adaptive history. Called by [PalmRejectionEngine.reset] on session start. */
    fun resetHistory() {
        history = ClassificationHistory()
    }

    /**
     * Swaps the active settings WITHOUT discarding the learned device scale. The adaptive
     * history is a property of the device/digitizer, not of the user's settings, so a
     * mid-session settings change must not wipe what the classifier has observed.
     */
    fun updateSettings(newSettings: PalmRejectionSettings) {
        settings = newSettings
    }

    /** Confidence from 0..1 based on distance to a threshold. */
    private fun confidenceNear(dim: Float, threshold: Float, aboveIsGood: Boolean): Float {
        if (threshold <= 0f) return 0.5f
        val margin = (dim - threshold) / threshold
        val confidence = if (aboveIsGood) {
            0.5f + 0.5f * margin.coerceIn(0f, 1f)
        } else {
            0.5f - 0.5f * margin.coerceIn(-1f, 0f)
        }
        return confidence.coerceIn(0f, 1f)
    }

    private fun result(
        classification: ContactClassification,
        confidence: Float,
        reason: ClassificationReason,
        threshold: Float,
        ctx: ClassifyContext,
    ) = ClassificationResult(classification, confidence.coerceIn(0f, 1f), reason, threshold)
}

private fun ArrayDeque<Float>.averageOrNull(): Float? {
    if (isEmpty()) return null
    return average().toFloat()
}
