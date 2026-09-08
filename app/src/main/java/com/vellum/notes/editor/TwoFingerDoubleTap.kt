package com.vellum.notes.editor

import kotlin.math.hypot

/**
 * Nebo-style gesture: double-tap with two fingers = undo.
 *
 * Pure state machine over tap centroids so it is unit-testable off-device.
 * The view feeds it raw two-pointer down/up events and fires undo when
 * [onTwoFingerDown] returns true (i.e. on the second tap's touchdown, matching
 * the platform double-tap feel).
 *
 * A tap counts when two pointers go down and lift quickly with almost no
 * movement; anything longer or draggier is a pan/zoom and is ignored.
 */
class TwoFingerDoubleTapDetector(
    private val tapTimeoutMs: Long = 300L,
    private val doubleTapTimeoutMs: Long = 450L,
    private val maxTapMovePx: Float = 28f,
    private val maxTapGapPx: Float = 64f,
) {
    private var downTimeMs: Long = -1L
    private var downX: Float = 0f
    private var downY: Float = 0f
    private var lastTapUpMs: Long = -1L
    private var lastTapX: Float = 0f
    private var lastTapY: Float = 0f
    /** Set when a down fired: the matching up belongs to the fired pair, not a new tap. */
    private var suppressUp: Boolean = false

    /**
     * Records a two-pointer touchdown at centroid ([x], [y]).
     * @return true when this touchdown completes a double-tap (fire undo).
     */
    fun onTwoFingerDown(nowMs: Long, x: Float, y: Float): Boolean {
        val fires = lastTapUpMs >= 0L &&
            nowMs - lastTapUpMs <= doubleTapTimeoutMs &&
            hypot(x - lastTapX, y - lastTapY) <= maxTapGapPx
        downTimeMs = nowMs
        downX = x
        downY = y
        if (fires) {
            // Consume the pair so a triple-tap does not fire twice.
            lastTapUpMs = -1L
            suppressUp = true
        }
        return fires
    }

    /** Records a two-pointer lift at centroid ([x], [y]). */
    fun onTwoFingerUp(nowMs: Long, x: Float, y: Float) {
        if (suppressUp) {
            suppressUp = false
            downTimeMs = -1L
            return
        }
        if (downTimeMs >= 0L &&
            nowMs - downTimeMs <= tapTimeoutMs &&
            hypot(x - downX, y - downY) <= maxTapMovePx
        ) {
            lastTapUpMs = nowMs
            lastTapX = x
            lastTapY = y
        } else if (downTimeMs >= 0L) {
            // A pan/zoom happened: it breaks any pending first tap.
            lastTapUpMs = -1L
        }
        downTimeMs = -1L
    }

    /** A third finger (or cancel) invalidates any in-progress tap. */
    fun reset() {
        downTimeMs = -1L
        lastTapUpMs = -1L
    }
}
