package com.vellum.notes.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitivityLevelTest {

    @Test
    fun presetValues_spanTheRangeInOrder() {
        assertTrue(SensitivityLevel.LOW.value < SensitivityLevel.MEDIUM.value)
        assertTrue(SensitivityLevel.MEDIUM.value < SensitivityLevel.HIGH.value)
        for (level in SensitivityLevel.entries) {
            assertTrue(level.value in 0f..1f)
        }
    }

    @Test
    fun medium_matchesTheDefaultSensitivity() {
        assertEquals(PalmRejectionSettings().sensitivity, SensitivityLevel.MEDIUM.value, 0.001f)
    }

    @Test
    fun fromValue_snapsToNearestPreset() {
        assertEquals(SensitivityLevel.LOW, SensitivityLevel.fromValue(0f))
        assertEquals(SensitivityLevel.LOW, SensitivityLevel.fromValue(0.2f))
        assertEquals(SensitivityLevel.MEDIUM, SensitivityLevel.fromValue(0.5f))
        assertEquals(SensitivityLevel.HIGH, SensitivityLevel.fromValue(0.8f))
        assertEquals(SensitivityLevel.HIGH, SensitivityLevel.fromValue(1f))
        assertEquals(SensitivityLevel.MEDIUM, SensitivityLevel.fromValue(0.6f))
    }

    @Test
    fun presets_widenOrNarrowTheWritingBand() {
        val base = PalmRejectionSettings()
        val low = base.copy(sensitivity = SensitivityLevel.LOW.value)
        val high = base.copy(sensitivity = SensitivityLevel.HIGH.value)
        assertTrue(low.effectiveWritingMaxMm() > base.effectiveWritingMaxMm())
        assertTrue(high.effectiveWritingMaxMm() < base.effectiveWritingMaxMm())
        assertTrue(low.effectiveFingerMaxMm() > high.effectiveFingerMaxMm())
    }
}
