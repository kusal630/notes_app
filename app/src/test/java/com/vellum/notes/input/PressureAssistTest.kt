package com.vellum.notes.input

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for saturated-pressure palm confirmation ([PalmRejectionSettings.pressureAssistEnabled]).
 *
 * The ambiguous band is built with fingerMaxMm = 20 (effective ~23mm at default
 * sensitivity) and an 18mm contact: too big to write, small enough to look like
 * a gesture finger. Only saturated pressure flips it to a palm.
 */
class PressureAssistTest {

    private val caps = testCapabilities()
    private val normalizer = InputNormalizer(caps)

    private fun settings() = PalmRejectionSettings(
        mode = PalmRejectionMode.BALANCED,
        sensitivity = 0.5f,
        writingHoldoffMs = 0L,
        fingerMaxMm = 20f,
    )

    /** 18mm contact in the ambiguous finger band (10px/mm capabilities). */
    private fun ambiguousContact(pressure: Float) = TestTouchFactory.contact(
        pointerId = 0,
        x = 500f,
        y = 500f,
        timeMs = 0L,
        majorPx = 180f,
        minorPx = 170f,
        pressure = pressure,
    )

    private fun classify(
        contact: RawTouchContact,
        configure: PalmRejectionSettings.() -> Unit = {},
        ctx: PalmClassifier.ClassifyContext =
            PalmClassifier.ClassifyContext(mode = PalmRejectionMode.BALANCED),
    ): ClassificationResult {
        val s = settings().apply(configure)
        return PalmClassifier(s).classify(normalizer.normalize(contact), ctx)
    }

    @Test
    fun ambiguousBand_withoutSaturatedPressure_staysFinger() {
        val r = classify(ambiguousContact(pressure = 0.7f))
        assertEquals(ContactClassification.FINGER, r.classification)
        assertEquals(ClassificationReason.MEDIUM_CONTACT, r.reason)
    }

    @Test
    fun ambiguousBand_withSaturatedPressure_confirmedPalm() {
        val r = classify(ambiguousContact(pressure = 1.0f))
        assertEquals(ContactClassification.PALM, r.classification)
        assertEquals(ClassificationReason.PRESSURE_SATURATED, r.reason)
    }

    @Test
    fun assistDisabled_saturatedPressure_staysFinger() {
        val r = classify(
            ambiguousContact(pressure = 1.0f),
            configure = { pressureAssistEnabled = false },
        )
        assertEquals(ContactClassification.FINGER, r.classification)
    }

    @Test
    fun noPressureDevice_saturatedValueIgnored() {
        // Pressure reads 0 (unreported): hasPressure is false, so even a maxed
        // size value must not confirm a palm.
        val r = classify(ambiguousContact(pressure = 0f))
        assertEquals(ContactClassification.FINGER, r.classification)
    }

    @Test
    fun honorPressureFalse_saturatedPressure_staysFinger() {
        val ctx = PalmClassifier.ClassifyContext(
            mode = PalmRejectionMode.BALANCED,
            honorPressure = false,
        )
        val r = classify(ambiguousContact(pressure = 1.0f), ctx = ctx)
        assertEquals(ContactClassification.FINGER, r.classification)
    }

    @Test
    fun hardwareStylus_saturatedPressure_stillWriting() {
        // Pressure never overrides a hardware tool signal.
        val stylus = TestTouchFactory.pen(
            majorPx = 300f,
            toolType = TestTouchFactory.TOOL_STYLUS,
        )
        val normalized = normalizer.normalize(stylus.copy(pressure = 1.0f))
        val r = PalmClassifier(settings()).classify(
            normalized,
            PalmClassifier.ClassifyContext(mode = PalmRejectionMode.BALANCED),
        )
        assertEquals(ContactClassification.WRITING, r.classification)
        assertEquals(ClassificationReason.HARDWARE_STYLUS, r.reason)
    }

    @Test
    fun smallPen_saturatedPressure_stillWriting() {
        // Below the writing band (and the suspicious size): pressure is irrelevant.
        val pen = TestTouchFactory.pen().copy(pressure = 1.0f)
        val r = classify(pen)
        assertEquals(ContactClassification.WRITING, r.classification)
    }

    @Test
    fun clearPalm_keepsSizeReason_notPressureReason() {
        // A 30mm palm is decided by size alone; pressure must not relabel it.
        val r = classify(TestTouchFactory.palm())
        assertEquals(ContactClassification.PALM, r.classification)
        assertEquals(ClassificationReason.LARGE_CONTACT, r.reason)
    }

    @Test
    fun relativeMultiPointer_saturatedSecondFinger_stillGestures() {
        // Two similar fingertips, one saturated: relative classification is
        // untouched so the pair can still start a two-finger gesture.
        val a = TestTouchFactory.fingertip(pointerId = 1, x = 200f, y = 200f, timeMs = 0L)
        val b = TestTouchFactory.fingertip(pointerId = 3, x = 400f, y = 200f, timeMs = 0L)
            .copy(pressure = 1.0f)
        val classifier = PalmClassifier(settings())
        val sizes = listOf(
            normalizer.normalize(a).maxDimMm,
            normalizer.normalize(b).maxDimMm,
        )
        val ctx = PalmClassifier.ClassifyContext(
            mode = PalmRejectionMode.BALANCED,
            pointerCount = 2,
            activeSizesMm = sizes,
        )
        val r = classifier.classify(normalizer.normalize(b), ctx)
        assertEquals(ContactClassification.FINGER, r.classification)
    }
}
