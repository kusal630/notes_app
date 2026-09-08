package com.vellum.notes.editor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TwoFingerDoubleTapTest {
    @Test
    fun doubleTapFiresOnSecondDown() {
        val d = TwoFingerDoubleTapDetector()
        d.onTwoFingerDown(1000L, 200f, 200f)
        d.onTwoFingerUp(1100L, 202f, 201f)
        assertTrue(d.onTwoFingerDown(1300L, 205f, 205f))
    }

    @Test
    fun singleTapDoesNotFire() {
        val d = TwoFingerDoubleTapDetector()
        assertFalse(d.onTwoFingerDown(1000L, 200f, 200f))
        d.onTwoFingerUp(1100L, 200f, 200f)
    }

    @Test
    fun slowSecondTapDoesNotFire() {
        val d = TwoFingerDoubleTapDetector()
        d.onTwoFingerDown(1000L, 200f, 200f)
        d.onTwoFingerUp(1100L, 200f, 200f)
        assertFalse(d.onTwoFingerDown(2000L, 200f, 200f))
    }

    @Test
    fun dragBreaksPendingTap() {
        val d = TwoFingerDoubleTapDetector()
        d.onTwoFingerDown(1000L, 200f, 200f)
        // Long press-drag: not a tap, breaks the sequence.
        d.onTwoFingerUp(1600L, 400f, 400f)
        assertFalse(d.onTwoFingerDown(1700L, 400f, 400f))
    }

    @Test
    fun tripleTapFiresOnce() {
        val d = TwoFingerDoubleTapDetector()
        d.onTwoFingerDown(1000L, 200f, 200f)
        d.onTwoFingerUp(1100L, 200f, 200f)
        assertTrue(d.onTwoFingerDown(1300L, 200f, 200f))
        d.onTwoFingerUp(1400L, 200f, 200f)
        assertFalse(d.onTwoFingerDown(1500L, 200f, 200f))
    }
}
