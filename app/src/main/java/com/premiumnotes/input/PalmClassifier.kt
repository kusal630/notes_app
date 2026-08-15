package com.premiumnotes.input

/**
 * Pure, stateless per-contact classifier. Given a [NormalizedContact] and a
 * [ClassifyContext] it returns a classification. All decisions derive from real
 * information the OS exposes; hardware stylus signals are trusted only when actually
 * present (tool type == STYLUS / ERASER).
 *
 * Design rules:
 *  - A real stylus tool type is always accepted as writing.
 *  - Otherwise the contact's maximum dimension in mm is compared against thresholds
 *    derived from user settings + calibration (adaptive, not hard-coded).
 *  - In WRITING mode, secondary contacts while a writing lock is active are rejected
 *    outright so a resting palm never produces input.
 */
class PalmClassifier(
    private val settings: PalmRejectionSettings,
) {

    data class ClassifyContext(
        val mode: PalmRejectionMode = PalmRejectionMode.BALANCED,
        val pointerCount: Int = 1,
        val activeWritingPointerId: Int? = null,
        val writingLockActive: Boolean = false,
        val contactSpeedMmPerSec: Float = 0f,
        val contactDurationMs: Long = 0L,
        /** When true, a bare finger may act as the writing pointer (palm rejection still on). */
        val fingerWritingEnabled: Boolean = false,
    )

    fun classify(contact: NormalizedContact, ctx: ClassifyContext): ClassificationResult {
        // Hardware-reported stylus: trust it completely (it cannot be a palm).
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

        // WRITING mode: a locked writing pointer is always accepted; every other
        // secondary contact is rejected regardless of size.
        if (ctx.mode == PalmRejectionMode.WRITING) {
            if (ctx.activeWritingPointerId == contact.pointerId && ctx.writingLockActive) {
                return result(ContactClassification.WRITING, 1f, ClassificationReason.LOCKED_WRITING_POINTER, 0f, ctx)
            }
            if (ctx.writingLockActive) {
                // A secondary contact while writing. A palm-sized contact is the resting
                // hand — reject it and keep the writing lock. A smaller (finger-sized)
                // contact is a second finger starting a two-finger gesture, so it falls
                // through to the size rules below and the engine drops the lock so the
                // pair can navigate.
                val fingerMax = settings.effectiveFingerMaxMm()
                if (contact.maxDimMm > fingerMax) {
                    return result(
                        ContactClassification.PALM, 0.95f, ClassificationReason.SECONDARY_WHILE_WRITING, 0f, ctx
                    )
                }
            }
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

        val writingMax = settings.effectiveWritingMaxMm()
        val fingerMax = settings.effectiveFingerMaxMm()
        val relaxedPalm = settings.effectiveRelaxedPalmMm()
        // With finger writing enabled the writing cutoff widens to the finger threshold so
        // a bare fingertip is accepted, while a resting palm stays above it and is rejected.
        val writingDim = if (ctx.fingerWritingEnabled) maxOf(writingMax, fingerMax) else writingMax

        return when (ctx.mode) {
            PalmRejectionMode.STRICT -> {
                if (dim <= writingDim) result(ContactClassification.WRITING, confidenceNear(dim, writingDim, aboveIsGood = false), ClassificationReason.SMALL_CONTACT, writingDim, ctx)
                else result(ContactClassification.PALM, confidenceNear(dim, writingDim, aboveIsGood = true), ClassificationReason.LARGE_CONTACT, writingDim, ctx)
            }
            PalmRejectionMode.BALANCED -> {
                when {
                    dim <= writingMax -> result(ContactClassification.WRITING, confidenceNear(dim, writingMax, false), ClassificationReason.SMALL_CONTACT, writingMax, ctx)
                    dim <= fingerMax -> result(ContactClassification.FINGER, confidenceNear(dim, fingerMax, false), ClassificationReason.MEDIUM_CONTACT, fingerMax, ctx)
                    else -> result(ContactClassification.PALM, confidenceNear(dim, fingerMax, true), ClassificationReason.LARGE_CONTACT, fingerMax, ctx)
                }
            }
            PalmRejectionMode.RELAXED -> {
                when {
                    dim <= writingMax -> result(ContactClassification.WRITING, confidenceNear(dim, writingMax, false), ClassificationReason.SMALL_CONTACT, writingMax, ctx)
                    dim <= relaxedPalm -> result(ContactClassification.FINGER, confidenceNear(dim, relaxedPalm, false), ClassificationReason.MEDIUM_CONTACT, relaxedPalm, ctx)
                    else -> result(ContactClassification.PALM, confidenceNear(dim, relaxedPalm, true), ClassificationReason.LARGE_CONTACT, relaxedPalm, ctx)
                }
            }
            PalmRejectionMode.WRITING -> {
                // No writing lock yet: a small (or finger-sized when enabled) contact can
                // become the writer; anything larger is a palm and cannot claim writing.
                if (dim <= writingDim) {
                    result(ContactClassification.WRITING, confidenceNear(dim, writingDim, false), ClassificationReason.SMALL_CONTACT, writingDim, ctx)
                } else {
                    result(ContactClassification.PALM, confidenceNear(dim, writingDim, true), ClassificationReason.LARGE_CONTACT, writingDim, ctx)
                }
            }
        }
    }

    /**
     * Confidence from 0..1 based on distance to a threshold. Near the boundary the
     * classifier is less sure; far on the intended side it is confident.
     */
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