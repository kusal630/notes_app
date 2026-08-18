package com.premiumnotes.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PalmClassifierTest {

    private val caps = testCapabilities()
    private val normalizer = InputNormalizer(caps)

    private fun classify(
        contact: RawTouchContact,
        mode: PalmRejectionMode = PalmRejectionMode.BALANCED,
        ctx: PalmClassifier.ClassifyContext = PalmClassifier.ClassifyContext(mode = mode),
    ): ClassificationResult {
        val classifier = PalmClassifier(testSettings(mode))
        return classifier.classify(normalizer.normalize(contact), ctx)
    }

    @Test
    fun smallPenContactIsWriting() {
        val r = classify(TestTouchFactory.pen(majorPx = 26f, minorPx = 24f))
        assertEquals(ContactClassification.WRITING, r.classification)
        assertTrue(r.confidence > 0.8f)
    }

    @Test
    fun largePalmContactIsRejected() {
        val r = classify(TestTouchFactory.palm())
        assertEquals(ContactClassification.PALM, r.classification)
    }

    @Test
    fun fingertipIsFingerInBalancedMode() {
        val r = classify(TestTouchFactory.fingertip())
        assertEquals(ContactClassification.FINGER, r.classification)
    }

    @Test
    fun stylusToolTypeAlwaysWriting() {
        // Even a huge contact reported as STYLUS is writing (hardware signal trusted).
        val r = classify(TestTouchFactory.pen(majorPx = 300f, toolType = TestTouchFactory.TOOL_STYLUS))
        assertEquals(ContactClassification.WRITING, r.classification)
        assertEquals(ClassificationReason.HARDWARE_STYLUS, r.reason)
        assertEquals(1f, r.confidence, 0.001f)
    }

    @Test
    fun eraserToolTypeIsEraser() {
        val r = classify(TestTouchFactory.pen(toolType = TestTouchFactory.TOOL_ERASER, majorPx = 26f))
        assertEquals(ContactClassification.ERASER, r.classification)
    }

    @Test
    fun unknownToolTypeFallsBackToGeometry() {
        val small = classify(TestTouchFactory.pen(toolType = TestTouchFactory.TOOL_UNKNOWN))
        assertEquals(ContactClassification.WRITING, small.classification)
        val large = classify(TestTouchFactory.palm(toolType = TestTouchFactory.TOOL_UNKNOWN))
        assertEquals(ContactClassification.PALM, large.classification)
    }

    @Test
    fun noGeometryFallsBackToFingerLowConfidence() {
        val r = classify(TestTouchFactory.contact(0, 100f, 100f, 0L, majorPx = 0f, minorPx = 0f, size = 0f))
        assertEquals(ContactClassification.FINGER, r.classification)
        assertTrue(r.confidence < 0.5f)
    }

    @Test
    fun strictModeRejectsFingertipThatBalancedAllows() {
        val strict = classify(TestTouchFactory.fingertip(), mode = PalmRejectionMode.STRICT)
        assertEquals(ContactClassification.PALM, strict.classification)
    }

    @Test
    fun relaxedModeAllowsFingertip() {
        val relaxed = classify(TestTouchFactory.fingertip(), mode = PalmRejectionMode.RELAXED)
        assertEquals(ContactClassification.FINGER, relaxed.classification)
    }

    @Test
    fun fingerWritingAcceptsFingertipInWritingMode() {
        val ctx = PalmClassifier.ClassifyContext(
            mode = PalmRejectionMode.WRITING,
            fingerWritingEnabled = true,
        )
        val r = classify(TestTouchFactory.fingertip(), mode = PalmRejectionMode.WRITING, ctx = ctx)
        assertEquals(ContactClassification.WRITING, r.classification)
    }

    @Test
    fun fingerWritingDisabledRejectsFingertipInWritingMode() {
        val ctx = PalmClassifier.ClassifyContext(
            mode = PalmRejectionMode.WRITING,
            fingerWritingEnabled = false,
        )
        val r = classify(TestTouchFactory.fingertip(), mode = PalmRejectionMode.WRITING, ctx = ctx)
        assertEquals(ContactClassification.PALM, r.classification)
    }

    @Test
    fun palmStillRejectedWithFingerWritingEnabled() {
        val ctx = PalmClassifier.ClassifyContext(
            mode = PalmRejectionMode.WRITING,
            fingerWritingEnabled = true,
        )
        val r = classify(TestTouchFactory.palm(), mode = PalmRejectionMode.WRITING, ctx = ctx)
        assertEquals(ContactClassification.PALM, r.classification)
    }

    @Test
    fun noGeometrySingleContactIsWritingWithFingerWriting() {
        val ctx = PalmClassifier.ClassifyContext(
            mode = PalmRejectionMode.WRITING,
            fingerWritingEnabled = true,
            pointerCount = 1,
        )
        val r = classify(
            TestTouchFactory.contact(0, 100f, 100f, 0L, majorPx = 0f, minorPx = 0f, size = 0f),
            mode = PalmRejectionMode.WRITING,
            ctx = ctx,
        )
        assertEquals(ContactClassification.WRITING, r.classification)
        assertEquals(ClassificationReason.FINGER_WRITING, r.reason)
    }

    @Test
    fun writingModeRejectsSecondaryContactWhileLocked() {
        val lockedCtx = PalmClassifier.ClassifyContext(
            mode = PalmRejectionMode.WRITING,
            activeWritingPointerId = 0,
            writingLockActive = true,
        )
        val secondary = classify(
            TestTouchFactory.palm(pointerId = 2),
            mode = PalmRejectionMode.WRITING,
            ctx = lockedCtx,
        )
        assertEquals(ContactClassification.PALM, secondary.classification)
    }

    @Test
    fun writingModeAcceptsLockedWritingPointer() {
        val lockedCtx = PalmClassifier.ClassifyContext(
            mode = PalmRejectionMode.WRITING,
            activeWritingPointerId = 0,
            writingLockActive = true,
        )
        val writing = classify(
            TestTouchFactory.pen(pointerId = 0),
            mode = PalmRejectionMode.WRITING,
            ctx = lockedCtx,
        )
        assertEquals(ContactClassification.WRITING, writing.classification)
        assertEquals(ClassificationReason.LOCKED_WRITING_POINTER, writing.reason)
    }

    @Test
    fun calibrationShiftsThresholds() {
        val strict = PalmRejectionMode.STRICT
        val settings = testSettings(mode = strict).apply {
            calibration = CalibrationData(penMaxDimMm = 6f)
            sensitivity = 0.9f
        }
        val classifier = PalmClassifier(settings)
        // effective writing max = 6 * (1.6 - 0.9) = 4.2mm; a 5mm contact is now palm.
        val normal = normalizer.normalize(TestTouchFactory.contact(0, 0f, 0f, 0L, majorPx = 50f, minorPx = 46f))
        val r = classifier.classify(normal, PalmClassifier.ClassifyContext(mode = strict))
        assertEquals(ContactClassification.PALM, r.classification)

        // With default calibration the same contact would be accepted as writing.
        val defaults = PalmClassifier(testSettings(mode = strict))
        val r2 = defaults.classify(normal, PalmClassifier.ClassifyContext(mode = strict))
        assertEquals(ContactClassification.WRITING, r2.classification)
    }

    @Test
    fun genuinelySmallestContactWithClearGapIsWritingEvenBeforeHistorySeeded() {
        // A resting contact that is not yet confirmed as a palm (its size sits in the
        // finger/writing band) plus a clearly smaller pen. The pen is the genuinely
        // smallest contact in the frame and must be WRITING even though no palm has been
        // confirmed in history yet — otherwise a real writer landing next to a resting
        // palm can never be accepted.
        val normalizer = InputNormalizer(testCapabilities())
        val palmC = normalizer.normalize(
            TestTouchFactory.contact(1, 500f, 700f, 0L, majorPx = 150f, minorPx = 120f, size = 0f)
        )
        val penC = normalizer.normalize(
            TestTouchFactory.contact(0, 100f, 100f, 0L, majorPx = 26f, minorPx = 24f, size = 0f)
        )
        val ctx = PalmClassifier.ClassifyContext(
            mode = PalmRejectionMode.WRITING,
            pointerCount = 2,
            activeSizesMm = listOf(palmC.maxDimMm, penC.maxDimMm),
            fingerWritingEnabled = true,
        )
        val classifier = PalmClassifier(testSettings(PalmRejectionMode.WRITING))

        // Pen is the smallest and dramatically smaller than the resting contact -> WRITING.
        assertEquals(ContactClassification.WRITING, classifier.classify(penC, ctx).classification)
        assertEquals(ClassificationReason.SMALL_CONTACT, classifier.classify(penC, ctx).reason)
        // The resting contact is the large outlier -> palm.
        assertEquals(ContactClassification.PALM, classifier.classify(palmC, ctx).classification)
    }

    @Test
    fun relativeClassificationIsScaleInvariantAcrossDigitizers() {
        // The SAME pen+palm scenario expressed at two very different absolute scales must
        // classify identically. Raw touch-size values are not calibrated across digitizers,
        // so an absolute threshold would call one "pen" and the other "palm"; the relative
        // ratio-based comparison is immune to that scaling.
        val normalizer = InputNormalizer(testCapabilities())

        fun pen(major: Float) = normalizer.normalize(
            TestTouchFactory.contact(0, 100f, 100f, 0L, majorPx = major, minorPx = major * 0.92f, size = 0f)
        )
        fun palm(major: Float) = normalizer.normalize(
            TestTouchFactory.contact(1, 500f, 700f, 0L, majorPx = major, minorPx = major * 0.8f, size = 0f)
        )

        val scenarios = listOf(
            pen(26f) to palm(300f), // baseline digitizer scale (~2.6mm pen, ~30mm palm)
            pen(52f) to palm(600f), // coarser digitizer, 2x the raw values
            pen(78f) to palm(900f), // 3x the raw values
        )

        for ((penC, palmC) in scenarios) {
            val classifier = PalmClassifier(testSettings(PalmRejectionMode.WRITING))

            // Palm lands alone first (cold start) and is rejected -> seeds confirmed-palm range.
            val palmFirst = classifier.classify(
                palmC,
                PalmClassifier.ClassifyContext(mode = PalmRejectionMode.WRITING, pointerCount = 1, fingerWritingEnabled = true),
            )
            assertEquals(ContactClassification.PALM, palmFirst.classification)
            classifier.updateHistory(
                ClassifiedContact(palmC, palmFirst.classification, palmFirst.confidence, palmFirst.reason, palmFirst.effectiveThresholdMm, 0f, 0L)
            )

            // Pen joins while the palm rests: both are classified by the RELATIVE path.
            val sizes = listOf(palmC.maxDimMm, penC.maxDimMm)
            val ctx = PalmClassifier.ClassifyContext(
                mode = PalmRejectionMode.WRITING,
                pointerCount = 2,
                activeSizesMm = sizes,
                fingerWritingEnabled = true,
            )
            assertEquals(ContactClassification.WRITING, classifier.classify(penC, ctx).classification)
            assertEquals(ContactClassification.PALM, classifier.classify(palmC, ctx).classification)
        }
    }
}