// Copyright (C) 2026 codeRed
// Vellum is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, either version 3 of the License, or (at your option) any later version.
package com.vellum.notes.editor

import com.vellum.notes.input.PalmRejectionSettings
import com.vellum.notes.input.ScribbleSensitivity
import com.vellum.notes.input.WriteEraseDetector
import com.vellum.notes.model.PenStyle
import com.vellum.notes.model.PenType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Strike-out (scribble) word erase: the detector must recognize deliberate
 * back-and-forth strike-outs and the word-erase rect must cover crossed objects.
 */
class ScribbleWordEraseTest {

    private fun detector(s: ScribbleSensitivity) = WriteEraseDetector(
        minReversals = s.minReversals,
        minReversalsOnInk = s.minReversalsOnInk,
        scribbleBoxMm = s.scribbleBoxMm,
        maxDurationMs = s.maxDurationMs,
    )

    private fun scribble(d: WriteEraseDetector, reversals: Int, timeNanos: Long = 0L) {
        // feed a tight zigzag: right, left, right... each leg 12mm
        var x = 0f
        var dir = 1
        // sample 0 is the reset point
        for (i in 0..reversals * 2) {
            d.addSample(x, 0f, timeNanos + i * 40L * 1_000_000L)
            x += 12f * dir
            dir = -dir
        }
    }

    @Test
    fun balancedSensitivity_firesOnFourReversals() {
        val d = detector(ScribbleSensitivity.BALANCED)
        d.reset(startedOnInk = false)
        scribble(d, 4)
        assertEquals(WriteEraseDetector.Intent.ERASE, d.intent())
    }

    @Test
    fun balancedSensitivity_letterWIsNeverErase() {
        val d = detector(ScribbleSensitivity.BALANCED)
        d.reset(startedOnInk = false)
        // a handwritten 'w': two direction changes, path ~2x bbox
        d.addSample(0f, 0f, 0L)
        d.addSample(6f, 10f, 60L * 1_000_000L)
        d.addSample(12f, 0f, 120L * 1_000_000L)
        d.addSample(18f, 10f, 180L * 1_000_000L)
        d.addSample(24f, 0f, 240L * 1_000_000L)
        assertEquals(WriteEraseDetector.Intent.WRITE, d.intent())
    }

    @Test
    fun balancedSensitivity_letterEIsNeverErase() {
        val d = detector(ScribbleSensitivity.BALANCED)
        d.reset(startedOnInk = false)
        // a handwritten 'e': loop with two reversals
        d.addSample(0f, 5f, 0L)
        d.addSample(6f, 0f, 50L * 1_000_000L)
        d.addSample(12f, 5f, 100L * 1_000_000L)
        d.addSample(6f, 10f, 150L * 1_000_000L)
        d.addSample(1f, 6f, 200L * 1_000_000L)
        assertEquals(WriteEraseDetector.Intent.WRITE, d.intent())
    }

    @Test
    fun sensitiveMode_firesOnTwoReversalsWhenStartedOnInk() {
        val d = detector(ScribbleSensitivity.SENSITIVE)
        d.reset(startedOnInk = true)
        scribble(d, 2)
        assertEquals(WriteEraseDetector.Intent.ERASE, d.intent())
    }

    @Test
    fun relaxedMode_requiresFiveReversals() {
        val d = detector(ScribbleSensitivity.RELAXED)
        d.reset(startedOnInk = false)
        // 3 interior turning points (5 samples) < RELAXED's required 5
        scribble(d, 2)
        assertEquals("a few reversals must not fire in RELAXED", WriteEraseDetector.Intent.WRITE, d.intent())
    }

    @Test
    fun relaxedMode_firesOnFiveReversals() {
        val d = detector(ScribbleSensitivity.RELAXED)
        d.reset(startedOnInk = false)
        scribble(d, 5)
        assertEquals(WriteEraseDetector.Intent.ERASE, d.intent())
    }

    @Test
    fun allPresets_areSensiblyOrdered() {
        // sensitivity ordering: SENSITIVE needs fewest reversals
        assertTrue(
            ScribbleSensitivity.SENSITIVE.minReversals <
                ScribbleSensitivity.BALANCED.minReversals
        )
        assertTrue(
            ScribbleSensitivity.BALANCED.minReversals <
                ScribbleSensitivity.RELAXED.minReversals
        )
        val s = PalmRejectionSettings()
        assertEquals(ScribbleSensitivity.BALANCED, s.scribbleSensitivity)
    }
}
