package com.premiumnotes.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PalmZoneTest {

    @Test
    fun disabledByDefault() {
        val zone = PalmZone()
        assertFalse(zone.enabled)
        assertEquals(PalmZoneMode.OFF, zone.mode)
    }

    @Test
    fun fromPalmSizesZoneWithPaddingAndDefaultsToAuto() {
        val zone = PalmZone.fromPalm(palmWidthMm = 80f, palmHeightMm = 50f, side = PalmZoneSide.RIGHT)
        assertTrue(zone.enabled)
        assertEquals(PalmZoneMode.AUTO, zone.mode)
        assertEquals(PalmZoneSide.RIGHT, zone.side)
        assertEquals(144f, zone.widthMm, 0.001f)   // 80 * 1.8
        assertEquals(70f, zone.heightMm, 0.001f)   // 50 * 1.4
    }

    @Test
    fun movedToSwitchesToManualAndClampsFractions() {
        val zone = PalmZone(mode = PalmZoneMode.AUTO).movedTo(1.5f, -0.2f)
        assertEquals(PalmZoneMode.MANUAL, zone.mode)
        assertEquals(1f, zone.centerXFrac, 0.001f)
        assertEquals(0f, zone.centerYFrac, 0.001f)
    }

    @Test
    fun enabledForAutoAndManualOnly() {
        assertTrue(PalmZone(mode = PalmZoneMode.AUTO).enabled)
        assertTrue(PalmZone(mode = PalmZoneMode.MANUAL).enabled)
        assertFalse(PalmZone(mode = PalmZoneMode.OFF).enabled)
    }
}
