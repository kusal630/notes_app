package com.premiumnotes.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WritingLockTest {

    @Test
    fun claimSucceedsWhenFree() {
        val lock = WritingLock(holdoffMs = 0)
        assertTrue(lock.tryClaim(7, nowNanos = 1_000_000L))
        assertEquals(7, lock.activePointerId)
        assertTrue(lock.isWritingPointer(7))
    }

    @Test
    fun cannotClaimWhileActive() {
        val lock = WritingLock(holdoffMs = 0)
        assertTrue(lock.tryClaim(1, 0L))
        assertFalse(lock.tryClaim(2, 100_000L))
        assertEquals(1, lock.activePointerId)
    }

    @Test
    fun releaseOnlyReleasesMatchingPointer() {
        val lock = WritingLock(holdoffMs = 0)
        lock.tryClaim(1, 0L)
        assertFalse(lock.release(2, 10_000L))
        assertEquals(1, lock.activePointerId)
        assertTrue(lock.release(1, 20_000L))
        assertNull(lock.activePointerId)
    }

    @Test
    fun holdoffPreventsImmediateReclaim() {
        val lock = WritingLock(holdoffMs = 100)
        val MS = 1_000_000L
        lock.tryClaim(1, 0L)
        lock.release(1, 50L * MS)
        // 10ms after lift — still within hold-off.
        assertFalse(lock.tryClaim(2, 60L * MS))
        // After the hold-off window elapses, a new pointer may claim.
        assertTrue(lock.tryClaim(2, 160L * MS))
        assertEquals(2, lock.activePointerId)
    }

    @Test
    fun holdoffBypassedWhenNotRespected() {
        val lock = WritingLock(holdoffMs = 100)
        val MS = 1_000_000L
        lock.tryClaim(1, 0L)
        lock.release(1, 50L * MS)
        // Finger writing: a contact immediately after a lift may re-claim so fast
        // consecutive strokes are not dropped.
        assertTrue(lock.tryClaim(2, 60L * MS, respectHoldoff = false))
        assertEquals(2, lock.activePointerId)
    }

    @Test
    fun resetClearsLock() {
        val lock = WritingLock(holdoffMs = 0)
        lock.tryClaim(1, 0L)
        lock.reset(5_000L)
        assertNull(lock.activePointerId)
        assertFalse(lock.isActive)
    }
}