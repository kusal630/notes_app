package com.premiumnotes.editor

import com.premiumnotes.input.SmoothingMode
import com.premiumnotes.input.StrokeSmoother
import com.premiumnotes.model.PenStyle
import com.premiumnotes.model.Point
import com.premiumnotes.model.Stroke
import kotlin.math.hypot

/**
 * Pure, per-stroke accumulator. Consumes writing-pointer samples (world coordinates,
 * millimeters), applies a dead-zone so a stationary pen does not make dots, runs the
 * active smoothing mode, and produces a committed [Stroke] on pen-up.
 *
 * The stroke is never built directly from raw events — this is the "noise filtering →
 * point smoothing → stroke construction" stage of the pipeline.
 */
class StrokeBuilder(
    private val style: PenStyle,
    private val id: Long,
    private val deadZoneMm: Float = 0.5f,
) {
    private val smoother: StrokeSmoother = StrokeSmoother.create(style.smoothing)
    private val points = ArrayList<Point>()
    private var anchorX = 0f
    private var anchorY = 0f
    private var hasAnchor = false
    private var lastSampleX = 0f
    private var lastSampleY = 0f

    fun onDown(x: Float, y: Float) {
        smoother.reset()
        points.clear()
        hasAnchor = true
        lastSampleX = x
        lastSampleY = y
        anchorX = x
        anchorY = y
        points += Point(x, y)
    }

    /**
     * Adds a raw sample. Returns true if the stroke gained a point (so the caller can
     * decide to invalidate the canvas).
     */
    fun onMove(x: Float, y: Float, t: Long): Boolean {
        if (!hasAnchor) return false
        // Dead zone: ignore movement smaller than the anchor tolerance (in mm).
        if (hypot(x - lastSampleX, y - lastSampleY) < deadZoneMm) return false
        lastSampleX = x
        lastSampleY = y
        val smoothed = smoother.process(x, y, t)
        if (smoothed.isEmpty()) return false
        points += smoothed.map { Point(it[0], it[1]) }
        return true
    }

    /** Returns the committed stroke, or null if the stroke was too short to keep. */
    fun onUp(x: Float, y: Float, t: Long): Stroke? {
        if (!hasAnchor) return null
        // Snap the pen-up point exactly.
        if (points.isEmpty()) points += Point(x, y)
        else if (points.last() != Point(x, y)) {
            val flushed = smoother.flush()
            points += flushed.map { Point(it[0], it[1]) }
            if (points.last() != Point(x, y)) points += Point(x, y)
        }
        hasAnchor = false
        if (points.size < 2) return null
        return Stroke(
            id = id,
            style = style,
            pointsPacked = Stroke.pack(points),
        )
    }

    fun onCancel() {
        hasAnchor = false
        smoother.reset()
        points.clear()
    }

    /** Live point list for incremental rendering of the in-progress stroke. */
    val livePoints: List<Point> get() = points
}