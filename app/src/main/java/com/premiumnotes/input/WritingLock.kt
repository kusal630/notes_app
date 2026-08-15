package com.premiumnotes.input

/**
 * Tracks the single "active writing pointer". A writing lock is deliberately sticky:
 * once established it is held until the pointer lifts, so a resting palm can never
 * steal or confuse the active writer mid-stroke. After a lift, a short hold-off window
 * prevents a palm from immediately re-claiming writing.
 */
class WritingLock(
    private val holdoffMs: Long = 120L,
) {
    var activePointerId: Int? = null
        private set

    var lockAcquiredAtNanos: Long = 0L
        private set

    private var lastLiftNanos: Long = 0L

    val isActive: Boolean get() = activePointerId != null

    /**
     * Attempts to claim [pointerId] as the writing pointer. Honours the hold-off so a
     * new contact immediately following a pen lift (e.g. the same palm) does not lock on.
     * Finger writing passes [respectHoldoff] = false so fast consecutive finger strokes
     * (lift and immediately re-touch) are never dropped.
     */
    fun tryClaim(pointerId: Int, nowNanos: Long, respectHoldoff: Boolean = true): Boolean {
        if (activePointerId != null) return false
        if (respectHoldoff && nowNanos - lastLiftNanos < holdoffMs * 1_000_000L) return false
        activePointerId = pointerId
        lockAcquiredAtNanos = nowNanos
        return true
    }

    /** True when [pointerId] is the current writing pointer. */
    fun isWritingPointer(pointerId: Int): Boolean = activePointerId == pointerId

    /** Releases the lock when the writing pointer lifts. Returns true if it was active. */
    fun release(pointerId: Int, nowNanos: Long): Boolean {
        if (activePointerId != pointerId) return false
        activePointerId = null
        lastLiftNanos = nowNanos
        return true
    }

    /** Force-releases (ACTION_CANCEL or explicit reset). */
    fun reset(nowNanos: Long) {
        if (activePointerId != null) {
            lastLiftNanos = nowNanos
            activePointerId = null
        }
    }
}
