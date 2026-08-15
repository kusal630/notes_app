package com.premiumnotes.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InputNormalizerTest {

    private val normalizer = InputNormalizer(testCapabilities(pxPerMm = 10f))

    @Test
    fun convertsPixelsToMillimeters() {
        val c = normalizer.normalize(
            TestTouchFactory.contact(0, 100f, 200f, 0L, majorPx = 80f, minorPx = 40f)
        )
        assertEquals(8f, c.toolMajorMm, 0.01f)
        assertEquals(4f, c.toolMinorMm, 0.01f)
        assertEquals(8f, c.maxDimMm, 0.01f)
        assertTrue(c.hasGeometry)
    }

    @Test
    fun derivesGeometryFromSizeWhenToolAxisMissing() {
        val c = normalizer.normalize(
            TestTouchFactory.contact(0, 0f, 0f, 0L, majorPx = 0f, minorPx = 0f, size = 0.2f)
        )
        // displayMaxPx = 2000 → 0.2 * 2000 = 400px = 40mm
        assertTrue(c.hasSize)
        assertEquals(40f, c.toolMajorMm, 0.5f)
        assertEquals(40f, c.maxDimMm, 0.5f)
    }

    @Test
    fun marksEverythingUnavailableWhenNothingReported() {
        val c = normalizer.normalize(
            TestTouchFactory.contact(0, 0f, 0f, 0L, majorPx = 0f, minorPx = 0f, size = 0f)
        )
        assertEquals(0f, c.maxDimMm, 0.01f)
        assertEquals(0f, c.areaMm2, 0.01f)
    }

    @Test
    fun degenerateFullScreenSizeIsIgnored() {
        // Emulators and some devices report getSize() near 1.0 (the whole screen).
        // Such a value would dwarf any real palm and must not be used for classification.
        val c = normalizer.normalize(
            TestTouchFactory.contact(0, 0f, 0f, 0L, majorPx = 0f, minorPx = 0f, size = 1.0f)
        )
        assertEquals(false, c.hasSize)
        assertEquals(0f, c.maxDimMm, 0.01f)
    }

    @Test
    fun clampsPressureAndSize() {
        val c = normalizer.normalize(
            TestTouchFactory.contact(0, 0f, 0f, 0L, pressure = 5f, size = 3f)
        )
        assertEquals(1f, c.pressure, 0.01f)
        assertEquals(1f, c.size, 0.01f)
    }

    @Test
    fun mapsToolTypes() {
        val sty = normalizer.normalize(TestTouchFactory.pen(toolType = TestTouchFactory.TOOL_STYLUS))
        assertEquals(ToolKind.STYLUS, sty.toolType)
        val finger = normalizer.normalize(TestTouchFactory.pen(toolType = TestTouchFactory.TOOL_FINGER))
        assertEquals(ToolKind.FINGER, finger.toolType)
        val unknown = normalizer.normalize(TestTouchFactory.pen(toolType = TestTouchFactory.TOOL_UNKNOWN))
        assertEquals(ToolKind.UNKNOWN, unknown.toolType)
    }
}