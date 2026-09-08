package com.vellum.notes.render

import android.graphics.RectF

/**
 * Viewport culling for the canvas display list (60fps workstream).
 *
 * The canvas caches committed content as geometry and redraws it every frame. On
 * large notebooks (1000+ strokes) most of that geometry is off-screen, so each
 * cached item carries a precomputed world-space [RectF] bound and [isVisible]
 * skips anything outside the current viewport clip before issuing draw calls.
 *
 * Bounds are intentionally conservative (padded beyond the widest possible pen):
 * culling must never hide visible ink, only skip what is provably off-screen.
 */
object StrokeCull {

    /**
     * Bounds of a packed point array ([x0, y0, x1, y1, …] in world mm), padded by
     * [padMm] on every side. Returns an empty rect when there are no points.
     */
    fun boundsOf(pointsPacked: FloatArray, padMm: Float): RectF {
        if (pointsPacked.size < 2) return RectF()
        var l = pointsPacked[0]
        var t = pointsPacked[1]
        var r = l
        var b = t
        var i = 2
        while (i + 1 < pointsPacked.size) {
            val x = pointsPacked[i]
            val y = pointsPacked[i + 1]
            if (x < l) l = x
            if (x > r) r = x
            if (y < t) t = y
            if (y > b) b = y
            i += 2
        }
        return RectF(l - padMm, t - padMm, r + padMm, b + padMm)
    }

    /** True when [item] overlaps [clip] (both world mm). Empty bounds are never visible. */
    fun isVisible(item: RectF, clip: RectF): Boolean {
        if (item.isEmpty) return false
        return RectF.intersects(item, clip)
    }

    /**
     * Padding that is guaranteed to contain any pen rendering of a centerline:
     * variable-width pens (fountain ≤ 1.4x, calligraphy ≤ 1.1x base) plus
     * anti-aliasing bleed, with a 1mm floor so hairlines are never cut.
     */
    fun padForWidth(widthMm: Float): Float =
        (widthMm * 0.75f + 0.6f).coerceAtLeast(1f)
}
