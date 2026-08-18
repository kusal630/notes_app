package com.premiumnotes.input

import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Pure-Kotlin heuristic that infers whether an in-progress gesture is freehand writing
 * or a deliberate scribble-erase, using only the pointer's path history — no tool type,
 * pressure, or hardware assumptions, so it works the same for passive stylus and finger.
 *
 * The signal is a "tight scribble": the pointer turns direction repeatedly while staying
 * inside a small box, within a short time. Normal writing flows in one direction with few
 * reversals; crossing out a word is a single pass (≈0 reversals), so it never triggers.
 * Starting on existing ink lowers the reversal threshold slightly (the user is more likely
 * erasing than writing on top of content), but is never a trigger by itself.
 *
 * The detector only observes samples of an already-accepted (palm-rejected) pointer. It
 * never decides which touches are valid; it only suggests an intent for the current
 * gesture, and the caller decides whether to honor it.
 */
class WriteEraseDetector(
    /** Reversals required before erase intent fires. */
    val minReversals: Int = 4,
    /** Reversals required when the gesture started on existing ink. */
    val minReversalsOnInk: Int = 3,
    /** Gesture path must stay within this bounding box (world mm) to count as scribble. */
    val scribbleBoxMm: Float = 24f,
    /** The reversal threshold must be reached within this many ms of gesture start. */
    val maxDurationMs: Long = 2500,
    /** Minimum path length (world mm) before any evaluation happens. */
    val minPathMm: Float = 8f,
    /** Direction changes closer than this (world mm) are jitter, not reversals. */
    private val minSegmentMm: Float = 1.5f,
) {

    /** Per-gesture decision. [WRITE] keeps whatever tool is active. */
    enum class Intent { WRITE, ERASE }

    private class Sample(val x: Float, val y: Float, val tMs: Long)

    private val samples = ArrayList<Sample>()
    private var lastTurningPointX = Float.NaN
    private var lastTurningPointY = Float.NaN
    private var turningPoints = 0
    private var startedOnInk = false

    /** Begin a new gesture (call on DOWN/POINTER_DOWN). */
    fun reset(startedOnInk: Boolean) {
        samples.clear()
        this.startedOnInk = startedOnInk
        lastTurningPointX = Float.NaN
        lastTurningPointY = Float.NaN
        turningPoints = 0
    }

    /** Feed one accepted (palm-rejected) sample in world mm. */
    fun addSample(x: Float, y: Float, timeNanos: Long) {
        val tMs = timeNanos / 1_000_000L
        val last = samples.lastOrNull()
        if (last != null && hypot(x - last.x, y - last.y) < minSegmentMm) {
            // Replace the last sample with the newer one: keeps the path clean for
            // direction math without inflating the length with sub-noise movement.
            samples[samples.size - 1] = Sample(x, y, tMs)
            return
        }
        samples += Sample(x, y, tMs)
        countTurningPoint()
    }

    /** Whether the gesture so far reads as a deliberate scribble-erase. */
    fun intent(): Intent {
        if (turningPoints < threshold()) return Intent.WRITE
        if (samples.size < 2) return Intent.WRITE
        val elapsed = samples.last().tMs - samples.first().tMs
        if (elapsed > maxDurationMs) return Intent.WRITE
        if (pathLengthMm < minPathMm) return Intent.WRITE
        if (boundingBoxSize() > scribbleBoxMm) return Intent.WRITE
        return Intent.ERASE
    }

    /** The path's cumulative length (world mm). */
    val pathLengthMm: Float
        get() {
            var len = 0f
            for (i in 1 until samples.size) {
                len += hypot(samples[i].x - samples[i - 1].x, samples[i].y - samples[i - 1].y)
            }
            return len
        }

    /** Max(width, height) of the gesture's bounding box in world mm. */
    fun boundingBoxSize(): Float {
        if (samples.isEmpty()) return 0f
        var minX = samples[0].x
        var maxX = samples[0].x
        var minY = samples[0].y
        var maxY = samples[0].y
        for (s in samples) {
            if (s.x < minX) minX = s.x
            if (s.x > maxX) maxX = s.x
            if (s.y < minY) minY = s.y
            if (s.y > maxY) maxY = s.y
        }
        return max(maxX - minX, maxY - minY)
    }

    private fun threshold(): Int = if (startedOnInk) minReversalsOnInk else minReversals

    /**
     * Counts a direction reversal whenever the incoming and outgoing segment directions
     * differ by more than ~90 degrees (a genuine turn, not a wiggle). Reversals at nearly
     * the same location are deduplicated so a single back-and-forth oscillation counts as
     * one reversal, and sustained scribbling accumulates distinct reversals.
     */
    private fun countTurningPoint() {
        if (samples.size < 3) return
        val n = samples.size
        val a = samples[n - 3]
        val b = samples[n - 2]
        val c = samples[n - 1]
        if (turnAngle(a, b, c) > 90f) {
            val dx = c.x - b.x
            val dy = c.y - b.y
            if (!lastTurningPointX.isNaN()) {
                if (hypot(b.x - lastTurningPointX, b.y - lastTurningPointY) < minSegmentMm * 1.5f) return
                if (hypot(dx, dy) < minSegmentMm * 1.5f) return
            }
            turningPoints++
            lastTurningPointX = b.x
            lastTurningPointY = b.y
        }
    }

    /** Smaller angle (0..180) between the incoming and outgoing direction at [b]. */
    private fun turnAngle(a: Sample, b: Sample, c: Sample): Float {
        // Incoming direction (b -> a reversed) vs outgoing direction (b -> c): a full
        // direction reversal reads as ~180 degrees.
        val v1x = b.x - a.x
        val v1y = b.y - a.y
        val v2x = c.x - b.x
        val v2y = c.y - b.y
        val l1 = hypot(v1x, v1y)
        val l2 = hypot(v2x, v2y)
        if (l1 < 1e-6f || l2 < 1e-6f) return 0f
        val cosAng = ((v1x * v2x + v1y * v2y) / (l1 * l2)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cosAng).toDouble()).toFloat()
    }
}
