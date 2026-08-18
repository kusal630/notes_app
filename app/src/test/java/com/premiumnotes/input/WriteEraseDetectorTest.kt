package com.premiumnotes.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WriteEraseDetectorTest {

    private fun detector() = WriteEraseDetector()

    /**
     * Feeds a back-and-forth zigzag of exactly [turns] direction reversals, all within a
     * small box and fast enough to stay inside the time budget.
     */
    private fun zigzag(
        d: WriteEraseDetector,
        turns: Int,
        widthMm: Float = 8f,
        startedOnInk: Boolean = false,
    ) {
        d.reset(startedOnInk)
        var x = 0f
        var t = 0L
        d.addSample(x, 0f, t)
        // Each extra sample creates a 180-degree turn at the previous vertex.
        repeat(turns + 1) {
            x = if (x == 0f) widthMm else 0f
            t += 40_000_000L
            d.addSample(x, 0f, t)
        }
    }

    @Test
    fun tightScribbleFiresEraseIntent() {
        val d = detector()
        zigzag(d, turns = 4)
        assertEquals(WriteEraseDetector.Intent.ERASE, d.intent())
    }

    @Test
    fun singlePassStraightLineIsWrite() {
        val d = detector()
        d.reset(startedOnInk = false)
        // A long straight line across the page.
        for (i in 0..20) d.addSample(i * 5f, 10f, i * 30_000_000L)
        assertEquals(WriteEraseDetector.Intent.WRITE, d.intent())
    }

    @Test
    fun wavyCursiveWritingIsNotErase() {
        val d = detector()
        d.reset(startedOnInk = false)
        // Writing-like gentle waves, no tight reversals.
        for (i in 0..40) {
            val x = i * 3f
            val y = 10f + 4f * kotlin.math.sin(i * 0.7f).toFloat()
            d.addSample(x, y, i * 25_000_000L)
        }
        assertEquals(WriteEraseDetector.Intent.WRITE, d.intent())
    }

    @Test
    fun crossingOutAWordDoesNotTrigger() {
        val d = detector()
        // Cross-out: a single stroke starting ON ink, drawn straight across — zero
        // reversals, so it must stay write even though it starts on ink.
        d.reset(startedOnInk = true)
        for (i in 0..15) d.addSample(i * 4f, 20f, i * 30_000_000L)
        assertEquals(WriteEraseDetector.Intent.WRITE, d.intent())
    }

    @Test
    fun travellingScribbleAcrossPageIsNotErase() {
        val d = detector()
        d.reset(startedOnInk = false)
        // Zigzag but travelling far across the page: the path escapes the tight box.
        var t = 0L
        for (i in 0 until 8) {
            t += 40_000_000L
            d.addSample(i * 30f, 0f, t)
            t += 40_000_000L
            d.addSample(i * 30f + 10f, 40f, t)
            t += 40_000_000L
            d.addSample(i * 30f + 20f, 80f, t)
        }
        assertEquals(WriteEraseDetector.Intent.WRITE, d.intent())
    }

    @Test
    fun shortTapIsNotErase() {
        val d = detector()
        d.reset(startedOnInk = false)
        d.addSample(0f, 0f, 0L)
        d.addSample(2f, 1f, 30_000_000L)
        d.addSample(4f, 2f, 60_000_000L)
        assertEquals(WriteEraseDetector.Intent.WRITE, d.intent())
    }

    @Test
    fun slowScribbleBeyondTimeWindowIsNotErase() {
        val d = detector()
        d.reset(startedOnInk = false)
        var t = 0L
        // Same zigzag, but each segment takes very long (> total 2.5s budget).
        d.addSample(0f, 0f, t)
        repeat(8) { i ->
            t += 700_000_000L
            d.addSample(if (i % 2 == 0) 8f else 0f, 5f, t)
            t += 700_000_000L
            d.addSample(0f, 10f, t)
        }
        assertEquals(WriteEraseDetector.Intent.WRITE, d.intent())
    }

    @Test
    fun startedOnInkLowersReversalThreshold() {
        val d = detector()
        // 3 reversals is enough when starting on ink but not on empty paper.
        zigzag(d, turns = 3, startedOnInk = true)
        assertEquals(WriteEraseDetector.Intent.ERASE, d.intent())

        val d2 = detector()
        zigzag(d2, turns = 3, startedOnInk = false)
        assertEquals(WriteEraseDetector.Intent.WRITE, d2.intent())
    }

    @Test
    fun resetClearsGestureState() {
        val d = detector()
        zigzag(d, turns = 4)
        assertEquals(WriteEraseDetector.Intent.ERASE, d.intent())
        d.reset(startedOnInk = false)
        d.addSample(0f, 0f, 0L)
        d.addSample(50f, 0f, 30_000_000L)
        assertEquals(WriteEraseDetector.Intent.WRITE, d.intent())
        assertTrue(d.boundingBoxSize() > 40f)
    }
}